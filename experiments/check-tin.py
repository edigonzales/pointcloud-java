"""Compare isolated Java TIN builds using the product DTM acceptance helpers."""

import argparse
import csv
import json
import math
import os
from pathlib import Path
import runpy
import statistics
import struct
import subprocess
import tempfile
from types import SimpleNamespace

DTM = runpy.run_path(str(Path(__file__).resolve().parents[1] / "tools/check-dtm.py"))
AREAS = DTM["AREAS"]
NODATA = DTM["NODATA"]
TOLERANCE = DTM["TOLERANCE"]
require = DTM["require"]
CELLS = DTM["CELLS"]
SAMPLES = struct.Struct(f"<{CELLS}d")
ROUNDS = 8


def prepare(args, area, work):
    stages = json.loads(Path(f"examples/dtm-solothurn-{area.name}.json").read_text())["pipeline"]
    stages[0]["filename"] = str(args.data / Path(stages[0]["filename"]).name)
    writer = stages.pop()
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
    faces = args.candidate_faces if label == "candidate" else Path("tools/TerrainFaces.java")
    return SimpleNamespace(cli=cli, pdal=args.pdal, gdal=args.pdal.parent, faces_source=faces)


def compare_values(args, area, cap, baseline, candidate):
    bm, bv = baseline
    cm, cv = candidate
    if args.allow_mesh_differences:
        require({k: v for k, v in bm.items() if k != "dtm"} == {k: v for k, v in cm.items() if k != "dtm"},
                f"{area.name}: point metadata differs")
    else:
        require(bm == cm, f"{area.name}: Java metadata differs")
        require(SAMPLES.pack(*bv) == SAMPLES.pack(*cv), f"{area.name}: Float64 samples are not bitwise equal")
    errors = [abs(a - b) for a, b in zip(bv, cv) if NODATA not in (a, b)]
    mask = [i for i, (a, b) in enumerate(zip(bv, cv)) if (a == NODATA) != (b == NODATA)]
    deviations = [i for i, (a, b) in enumerate(zip(bv, cv)) if NODATA not in (a, b) and abs(a - b) > TOLERANCE]
    require(cap is not None or (len(errors) == CELLS and not mask), f"{area.name}: lost uncapped coverage")
    metrics = {"differentMask": len(mask), "aboveTolerance": len(deviations),
               "max": max(errors, default=0), "bitwise": SAMPLES.pack(*bv) == SAMPLES.pack(*cv)}
    return sorted([*mask, *deviations]), metrics


def verify_faces(args, writer, work, ground, comparisons):
    targets = {cell for _, _, _, suspects in comparisons for cell in suspects}
    if not targets:
        return
    diagnostics = {}
    left = DTM["inspect_faces"](version_info(args, "baseline"), work / "baseline-faces", writer, ground, sorted(targets))
    right = DTM["inspect_faces"](version_info(args, "candidate"), work / "candidate-faces", writer, ground, sorted(targets))
    for cap, baseline, candidate, suspects in comparisons:
        for cell in suspects:
            position = DTM["grid_center"](writer, cell)
            a, b = left[cell], right[cell]
            require(len(a) == len(b) == 3 and set(a) != set(b), f"Cell {cell}: missing alternate support faces")
            for face, expected in ((a, baseline[cell]), (b, candidate[cell])):
                actual = DTM["face_sample"](face, position, cap)
                require(actual is not None and abs(expected - actual) <= TOLERANCE,
                        f"Cell {cell}: output differs from its support face")
            diagnostics[f"{cap}/{cell}"] = {"center": position, "baseline": baseline[cell], "candidate": candidate[cell],
                                            "baselineFace": a, "candidateFace": b}
    (work / "variant-faces.json").write_text(json.dumps(diagnostics, indent=2) + "\n")


def run_variant(args, case, stages, writer, area, points, label, workers, cap, measure=False):
    engine = "pdal" if label == "pdal" else "java"
    info = version_info(args, label)
    command, target, metadata = DTM["configure"](info, case, stages, writer, engine, workers, cap)
    options = "-Xmx512m"
    if label == "candidate" and args.candidate_java_options:
        options += f" {args.candidate_java_options}"
    env = dict(os.environ, JAVA_OPTS=options)
    timing = None
    if measure:
        timing = DTM["GRID"]["measure"](command, args.execute, args.timing, env)
    else:
        subprocess.run(command, env=env, check=True)
    result = DTM["read_result"](info, target, metadata, area, writer, engine, points)
    return result, timing


def profile(args, csv_writer, area, work, stages, writer, ground, mode, workers):
    points = area.selected if mode == "ground-las" else area.points_read
    labels = ["baseline", "candidate", *(["pdal"] if args.include_pdal else [])]
    references = {}
    comparisons = []
    candidate_outputs = {}
    for cap in (None, area.cap):
        outputs = {}
        for label in labels:
            case = work / f"{mode}-{workers}-{label}-{cap}"
            case.mkdir()
            outputs[label], _ = run_variant(args, case, stages, writer, area, points, label, workers, cap)
        if cap is None:
            references = outputs
        a, b = outputs["baseline"], outputs["candidate"]
        suspects, metrics = compare_values(args, area, cap, a, b)
        comparisons.append((cap, a[1], b[1], suspects))
        candidate_outputs[cap] = b[0], SAMPLES.pack(*b[1])
        if args.include_pdal:
            from_pdal = outputs["pdal"]
            DTM["compare_stats"](b[0]["statistics"], from_pdal[0], area.selected)
            pdal_mask = sum((x == NODATA) != (y == NODATA) for x, y in zip(b[1], from_pdal[1]))
            pdal_errors = [abs(x - y) for x, y in zip(b[1], from_pdal[1]) if NODATA not in (x, y)]
            metrics["pdalMask"] = pdal_mask
            metrics["pdalAboveTolerance"] = sum(z > TOLERANCE for z in pdal_errors)
            metrics["pdalMax"] = max(pdal_errors, default=0)
            if a != b:
                # Reuse the established triangle audit for changed meshes against PDAL.
                pdal_work = case / "audit"
                pdal_work.mkdir()
                DTM["compare_surfaces"](version_info(args, "candidate"), area, writer, pdal_work, stages,
                                        ground, {cap: (b[1], from_pdal[1])})
        print(f"{area.name} {mode} reader={workers} cap={cap} {json.dumps(metrics)}", flush=True)
    verify_faces(args, writer, work / f"{mode}-{workers}", ground, comparisons)
    with Path(stages[0]["filename"]).open("rb") as source:
        while source.read(1 << 20):
            pass
    samples = {label: [] for label in labels}
    for round_index in range(ROUNDS):
        order = labels[round_index % len(labels):] + labels[:round_index % len(labels)]
        for position, label in enumerate(order, 1):
            with tempfile.TemporaryDirectory(dir=work) as folder:
                actual, (wall, user, sys, rss) = run_variant(
                    args, Path(folder), stages, writer, area, points, label, workers, None, measure=True)
                expected = references[label]
                require(actual[0] == expected[0] and SAMPLES.pack(*actual[1]) == SAMPLES.pack(*expected[1]),
                        f"{area.name}: measured {label} differs from accepted run")
                samples[label].append(wall)
                csv_writer.writerow((args.name, area.name, mode, workers, round_index + 1, position,
                                     label, f"{wall:.9f}", user, sys, rss))
                args.report.flush()
        print(f"{args.name} {area.name} {mode} {workers} {round_index + 1}/{ROUNDS} "
              + " ".join(f"{k}={v[-1]:.3f}" for k, v in samples.items()), flush=True)
    paired = [1 - t / b for t, b in zip(samples["candidate"], samples["baseline"])]
    print(f"{args.name} {area.name} {mode} {workers}: gain={statistics.median(paired):.2%}, "
          f"wins={sum(x > 0 for x in paired)}/{ROUNDS}", flush=True)
    return candidate_outputs


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
    parser.add_argument("--candidate-faces", type=Path, default=Path("tools/TerrainFaces.java"))
    parser.add_argument("--candidate-java-options")
    args = parser.parse_args()
    args.pdal = args.pdal.resolve()
    require("pdal 2.10.2 " in subprocess.check_output([args.pdal, "--version"], text=True),
            "This comparison requires PDAL 2.10.2")
    args.execute, args.timing = DTM["GRID"]["timing_functions"]()
    args.work.mkdir(parents=True, exist_ok=True)
    if args.ground_dir is None:
        args.ground_dir = args.work / "input"
    args.ground_dir = args.ground_dir.resolve()
    work = Path(tempfile.mkdtemp(prefix=f"{args.name}-", dir=args.work))
    print(f"Comparison outputs: {work}", flush=True)
    args.csv.parent.mkdir(parents=True, exist_ok=True)
    with args.csv.open("w", newline="") as args.report:
        writer = csv.writer(args.report)
        writer.writerow(("variant", "area", "input", "readers", "round", "position", "engine",
                         "wall_s", "user_s", "system_s", "rss_bytes"))
        for area in AREAS:
            section = work / area.name
            section.mkdir()
            laz, ground_stages, grid, ground = prepare(args, area, section)
            if args.mode in ("ground-las", "both"):
                profile(args, writer, area, section, ground_stages, grid, ground, "ground-las", 1)
            if args.mode in ("laz", "both"):
                serial = profile(args, writer, area, section, laz, grid, ground, "laz", 1)
                parallel = profile(args, writer, area, section, laz, grid, ground, "laz", 4)
                require(serial == parallel, f"{area.name}: candidate 1/4-reader results differ")


if __name__ == "__main__":
    main()
