"""Compare isolated Java TIN builds using the product DTM acceptance helpers."""

import argparse
import csv
import json
import math
import os
from pathlib import Path
import runpy
import statistics
import subprocess
import tempfile
from types import SimpleNamespace

DTM = runpy.run_path(str(Path(__file__).resolve().parents[1] / "tools/check-dtm.py"))
AREAS = DTM["AREAS"]
Area = DTM["Area"]
NODATA = DTM["NODATA"]
TOLERANCE = DTM["TOLERANCE"]
require = DTM["require"]
pack = DTM["packed_samples"]
ROUNDS = 8
HOLDOUT_ROUNDS = 5

HOLDOUTS = (
    Area("autzen", 10_653_336, 62_304, 62_290, 20, epsg="EPSG:2992", z_unit_metres=1200 / 3937),
    Area("forest-wide", 14_566_627, 712_655, 712_642, 5, size=(280, 280)),
    Area("height", 15_206_933, 787_937, 787_920, 5),
)
HOLDOUT_REGIONS = {
    "autzen": ("autzen-classified.copc.laz", "([636550,636850],[850650,850950])", 636600, 850700),
    "forest-wide": ("so-2019.laz", "([2594050,2594430],[1227550,1227930])", 2594100, 1227600),
    "height": ("so-2019-height.laz", "([2592075,2592325],[1228075,1228325])", 2592100, 1228100),
}
EXPECTED_FILLED = {"urban": 7_227, "forest": 249}


def prepare(args, area, work):
    if area.name in HOLDOUT_REGIONS:
        filename, clip, x, y = HOLDOUT_REGIONS[area.name]
        stages = [{"type": "readers.las", "filename": filename},
                  {"type": "filters.crop", "bounds": clip},
                  {"type": "filters.expression", "expression": "Classification == 2 && Withheld == 0"},
                  {"type": "filters.stats", "dimensions": "Z"}]
        writer = {"type": "writers.dtm", "resolution": 1, "origin_x": x, "origin_y": y,
                  "width": area.size[0], "height": area.size[1]}
    else:
        stages = json.loads(Path(f"examples/dtm-solothurn-{area.name}.json").read_text())["pipeline"]
        writer = stages.pop()
    stages[0]["filename"] = str(args.data / Path(stages[0]["filename"]).name)
    ground = args.ground_dir / area.name / "ground.las"
    if not ground.exists():
        ground.parent.mkdir(parents=True, exist_ok=True)
        config, meta = work / "prefilter.json", work / "ground.json"
        config.write_text(json.dumps([*stages[:-1], {"type": "writers.las", "filename": str(ground),
                                                     "forward": "all", "extra_dims": "all"}]))
        DTM["run"]([args.baseline_cli, "pipeline", config, "--metadata", meta])
        require(json.loads(meta.read_text())["pointsWritten"] == area.selected, "Unexpected ground LAS count")
    ground_stages = [{"type": "readers.las", "filename": str(ground)}, stages[-1]]
    return stages, ground_stages, writer, ground


def version_info(args, label):
    cli = args.baseline_cli if label == "baseline" else args.cli
    faces = args.baseline_faces if label == "baseline" else args.candidate_faces
    return SimpleNamespace(cli=cli, pdal=args.pdal, gdal=args.pdal.parent, faces_source=faces)


def same_output(left, right):
    return left[0] == right[0] and pack(left[1]) == pack(right[1])


def compare_values(args, area, cap, baseline, candidate):
    bm, bv = baseline
    cm, cv = candidate
    bitwise = same_output(baseline, candidate)
    if args.allow_mesh_differences and area.name != "forest-wide":
        require({k: v for k, v in bm.items() if k != "dtm"} == {k: v for k, v in cm.items() if k != "dtm"},
                f"{area.name}: point metadata differs")
    else:
        require(bitwise, f"{area.name}: Java metadata or Float64 samples differ")
    tolerance = TOLERANCE / area.z_unit_metres
    errors = [abs(a - b) for a, b in zip(bv, cv) if NODATA not in (a, b)]
    mask = [i for i, (a, b) in enumerate(zip(bv, cv)) if (a == NODATA) != (b == NODATA)]
    deviations = [i for i, (a, b) in enumerate(zip(bv, cv)) if NODATA not in (a, b) and abs(a - b) > tolerance]
    if cap is None and area in AREAS:
        require(len(errors) == math.prod(area.size), f"{area.name}: lost uncapped coverage")
    metrics = {"tinToNearest": sum(bv[cell] != NODATA for cell in mask),
               "nearestToTin": sum(bv[cell] == NODATA for cell in mask), "differentMask": len(mask),
               "aboveTolerance": len(deviations), "max": max(errors, default=0), "bitwise": bitwise}
    return sorted([*mask, *deviations]), metrics


def verify_faces(args, area, writer, work, ground, comparisons):
    targets = sorted({cell for _, _, _, suspects in comparisons for cell in suspects})
    expected = None
    if args.extended or args.extended_only:
        expected = "tinfour" if area.name == "forest-wide" else "delaunator"
    if not targets and expected is None:
        return
    right = DTM["inspect_faces"](version_info(args, "candidate"), work / "candidate-faces", writer, ground, targets,
                                 expected_backend=expected)
    if not targets:
        return
    left = DTM["inspect_faces"](version_info(args, "baseline"), work / "baseline-faces", writer, ground, targets)
    tolerance = TOLERANCE / area.z_unit_metres
    diagnostics = {}
    for cap, baseline, candidate, suspects in comparisons:
        for cell in suspects:
            position = DTM["grid_center"](writer, cell)
            a, b = left[cell], right[cell]
            require(set(a) != set(b), f"{area.name}, cell {cell}: missing alternate support faces")
            for face, expected in ((a, baseline[cell]), (b, candidate[cell])):
                actual = DTM["face_sample"](face, position, cap)
                require(actual is not None and abs(expected - actual) <= tolerance,
                        f"{area.name}, cell {cell}: output {expected} differs from its support face ({actual})")
            diagnostics[f"{cap}/{cell}"] = {"center": position, "baseline": baseline[cell], "candidate": candidate[cell],
                                            "baselineFace": a, "candidateFace": b}
    (work / "variant-faces.json").write_text(json.dumps(diagnostics, indent=2) + "\n")


def run_variant(args, case, stages, writer, area, points, label, workers, cap, *, complete=False, measure=False):
    engine = "pdal" if label == "pdal" else "java"
    info = version_info(args, label)
    command, target, metadata = DTM["configure"](info, case, stages, writer, engine, workers, cap, complete=complete)
    options = "-Xmx512m"
    if label == "candidate" and args.candidate_java_options:
        options += f" {args.candidate_java_options}"
    env = dict(os.environ, JAVA_OPTS=options)
    timing = None
    if measure:
        timing = DTM["GRID"]["measure"](command, args.execute, args.timing, env)
    else:
        subprocess.run(command, env=env, check=True)
    result = DTM["read_result"](info, target, metadata, area, writer, engine, points, complete=complete)
    return result, timing


def accept(args, area, work, stages, writer, ground, mode, workers, audit_faces):
    points = area.selected if mode == "ground-las" else area.points_read
    labels = ("baseline", "candidate", *(("pdal",) if args.include_pdal else ()))
    work.mkdir()
    references = {}
    comparisons = []
    pdal_comparisons = {label: {} for label in ("baseline", "candidate")}
    for cap in (None, area.cap):
        outputs = {}
        for label in labels:
            case = work / f"{label}-{cap}-tin"
            case.mkdir()
            outputs[label], _ = run_variant(args, case, stages, writer, area, points, label, workers, cap)
            references[label, cap, False] = outputs[label]
            if args.complete and label != "pdal":
                case = work / f"{label}-{cap}-complete"
                case.mkdir()
                result, _ = run_variant(args, case, stages, writer, area, points, label, workers, cap, complete=True)
                references[label, cap, True] = result
                if audit_faces:
                    known = EXPECTED_FILLED.get(area.name) if cap is not None else (0 if area in AREAS else None)
                    DTM["check_complete"](area, writer, ground, outputs[label], result,
                                          expected_filled=known if label == "baseline" else None)
        a, b = outputs["baseline"], outputs["candidate"]
        suspects, metrics = compare_values(args, area, cap, a, b)
        comparisons.append((cap, a[1], b[1], suspects))
        if args.complete:
            bm, cm = (references[label, cap, True] for label in ("baseline", "candidate"))
            metrics["baselineFilledNearest"] = bm[0]["dtm"]["cellsFilledNearest"]
            metrics["candidateFilledNearest"] = cm[0]["dtm"]["cellsFilledNearest"]
            metrics["completeBitwise"] = same_output(bm, cm)
            if not args.allow_mesh_differences or area.name == "forest-wide":
                require(metrics["completeBitwise"], f"{area.name}: completed Float64 samples or metadata differ")
        if args.include_pdal:
            from_pdal = outputs["pdal"]
            for label in pdal_comparisons:
                DTM["compare_stats"](outputs[label][0]["statistics"], from_pdal[0], area.selected)
                pdal_comparisons[label][cap] = (outputs[label][1], from_pdal[1])
        print(f"{area.name} {mode} reader={workers} cap={cap} {json.dumps(metrics)}", flush=True)
    if audit_faces:
        verify_faces(args, area, writer, work, ground, comparisons)
        if args.include_pdal:
            for label, surfaces in pdal_comparisons.items():
                pdal_work = work / f"{label}-pdal"
                pdal_work.mkdir()
                print(f"{area.name}: actual support face audit for {label} versus PDAL", flush=True)
                DTM["compare_surfaces"](version_info(args, label), area, writer, pdal_work, stages, ground, surfaces)
    return references


def compare_inputs(area, reference, other, *, ground=False):
    for key, left in reference.items():
        if key[0] == "pdal":
            continue
        right = other[key]
        if ground:
            # Prepared LAS skips crop and expression, so filters.stats is two stages earlier.
            ground_stats = [{**stat, "stage": stat["stage"] + 2} for stat in right[0]["statistics"]]
            right = ({**right[0], "pointsRead": left[0]["pointsRead"], "statistics": ground_stats}, right[1])
        require(same_output(left, right), f"{area.name}: Java input/reader results differ for {key}")


def benchmark(args, csv_writer, area, work, stages, writer, mode, workers, rounds, references):
    points = area.selected if mode == "ground-las" else area.points_read
    labels = ["baseline", "candidate", *(["pdal"] if args.include_pdal else [])]
    with Path(stages[0]["filename"]).open("rb") as source:
        while source.read(1 << 20):
            pass
    samples = {label: [] for label in labels}
    for round_index in range(rounds):
        order = labels[round_index % len(labels):] + labels[:round_index % len(labels)]
        for position, label in enumerate(order, 1):
            complete = args.complete and label != "pdal"
            expected = references[label, None, complete]
            with tempfile.TemporaryDirectory(dir=work) as folder:
                actual, (wall, user, system, rss) = run_variant(
                    args, Path(folder), stages, writer, area, points, label, workers, None, complete=complete,
                    measure=True)
                require(same_output(actual, expected), f"{area.name}: measured {label} differs from accepted run")
                samples[label].append(wall)
                csv_writer.writerow((args.name, area.name, mode, workers, round_index + 1, position,
                                     label, f"{wall:.9f}", user, system, rss, "complete" if complete else "tin"))
                args.report.flush()
        print(f"{args.name} {area.name} {mode} {workers} {round_index + 1}/{rounds} "
              + " ".join(f"{k}={v[-1]:.3f}" for k, v in samples.items()), flush=True)
    paired = [1 - t / b for t, b in zip(samples["candidate"], samples["baseline"])]
    print(f"{args.name} {area.name} {mode} {workers}: gain={statistics.median(paired):.2%}, "
          f"wins={sum(x > 0 for x in paired)}/{rounds}", flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("pdal", type=Path)
    parser.add_argument("--baseline-cli", required=True, type=Path)
    parser.add_argument("--cli", required=True, type=Path)
    parser.add_argument("--name", required=True)
    parser.add_argument("--csv", required=True, type=Path)
    parser.add_argument("--work", type=Path, default=Path(".cache/experiments/tin-performance"))
    parser.add_argument("--data", type=Path, default=Path(".cache/test-data"))
    parser.add_argument("--ground-dir", type=Path)
    parser.add_argument("--mode", choices=("ground-las", "laz", "both"), default="ground-las")
    parser.add_argument("--include-pdal", action="store_true")
    parser.add_argument("--allow-mesh-differences", action="store_true")
    parser.add_argument("--complete", action="store_true", help="also verify nearest-fill and time complete Java output")
    profiles = parser.add_mutually_exclusive_group()
    profiles.add_argument("--extended", action="store_true", help="also verify and time the three additional profiles")
    profiles.add_argument("--extended-only", action="store_true", help="only verify and time the three additional profiles")
    parser.add_argument("--baseline-faces", type=Path, default=Path("tools/TerrainFaces.java"))
    parser.add_argument("--candidate-faces", type=Path, default=Path("tools/TerrainFaces.java"))
    parser.add_argument("--candidate-java-options")
    args = parser.parse_args()
    args.pdal = args.pdal.resolve()
    require("pdal 2.10.2 " in subprocess.check_output([args.pdal, "--version"], text=True),
            "This comparison requires PDAL 2.10.2")
    args.execute, args.timing = DTM["GRID"]["timing_functions"]()
    args.work = args.work.resolve()
    args.work.mkdir(parents=True, exist_ok=True)
    args.data = args.data.resolve()
    if args.ground_dir is None:
        args.ground_dir = args.work / "input"
    args.ground_dir = args.ground_dir.resolve()
    work = Path(tempfile.mkdtemp(prefix=f"{args.name}-", dir=args.work))
    print(f"Comparison outputs: {work}", flush=True)
    selected_areas = (() if args.extended_only else AREAS) + (HOLDOUTS if args.extended or args.extended_only else ())
    timed_profiles = []
    for area in selected_areas:
        section = work / area.name
        section.mkdir()
        laz, ground_stages, grid, ground = prepare(args, area, section)
        holdout = area in HOLDOUTS
        profiles = []
        if not holdout and args.mode in ("ground-las", "both"):
            profiles.append(("ground-las", 1, ground_stages, ROUNDS))
        if holdout or args.mode in ("laz", "both"):
            profiles.extend((("laz", 1, laz, 0 if holdout else ROUNDS),
                             ("laz", 4, laz, HOLDOUT_ROUNDS if holdout else ROUNDS)))
        verified = {}
        for mode, workers, stages, rounds in profiles:
            # One face audit suffices once both Java readers and the prepared ground LAS reproduce all cells.
            audit_faces = len(profiles) == 1 or (mode, workers) == ("laz", 4)
            references = accept(args, area, section / f"{mode}-{workers}", stages, grid, ground,
                                mode, workers, audit_faces)
            verified[mode, workers] = references
            if rounds:
                timed_profiles.append((area, section, stages, grid, mode, workers, rounds, references))
        if ("laz", 1) in verified:
            parallel = verified["laz", 4]
            compare_inputs(area, parallel, verified["laz", 1])
            if ("ground-las", 1) in verified:
                compare_inputs(area, parallel, verified["ground-las", 1], ground=True)
    args.csv.parent.mkdir(parents=True, exist_ok=True)
    with args.csv.open("w", newline="") as args.report:
        csv_writer = csv.writer(args.report)
        csv_writer.writerow(("variant", "area", "input", "readers", "round", "position", "engine",
                             "wall_s", "user_s", "system_s", "rss_bytes", "raster"))
        for profile in timed_profiles:
            benchmark(args, csv_writer, *profile)


if __name__ == "__main__":
    main()
