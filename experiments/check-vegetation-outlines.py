"""Calibrate and evaluate the experimental vegetation outlines with a delivered GeoPackage."""

import argparse
from contextlib import closing
from hashlib import sha256
import json
import math
import os
from pathlib import Path
import sqlite3
from statistics import median
import subprocess
import sys
from tempfile import TemporaryDirectory
import time

import numpy as np
import shapely as geo
from shapely.geometry import shape


DEFAULT_CACHE = Path(".cache/vegetation-outlines")
REFERENCE_LAYER = "futterbaulinien_geometrie"
REGIONS = {
    "calibration": (2610200, 1232400, 2610600, 1232800),
    "seam": (2609900, 1232500, 2610100, 1232700),
    "holdout": (2611200, 1233200, 2611800, 1233800),
}
SEAM_X = 2610000
MODES = ("all", "neighbors")
THINNING_VARIANTS = ("grid", "spacing", "none")
THINNING_SNAPSHOTS = {
    "beforeErosion": "before-erosion.wkt",
    "afterErosion": "after-erosion.wkt",
    "afterDilation": "after-dilation.wkt",
}


class HeapLimitExceeded(RuntimeError):
    pass


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def polygons(geometry):
    if geometry.is_empty:
        return
    if geometry.geom_type == "Polygon":
        yield geometry
    elif geometry.geom_type in ("MultiPolygon", "GeometryCollection"):
        for part in geometry.geoms:
            yield from polygons(part)


def read_layer(args, path, layer, bounds=None):
    command = [args.gdal_bin / "ogr2ogr", "-f", "GeoJSON", "-lco", "COORDINATE_PRECISION=15",
               "/vsistdout/", path, layer]
    if bounds is not None:
        command.extend(("-spat", *map(str, bounds)))
    result = subprocess.check_output(command, text=True)
    shapes = [shape(feature["geometry"]) for feature in json.loads(result)["features"]]
    require(all(g.is_valid for g in shapes), f"Invalid polygon in {path} layer {layer}")
    return shapes


def reference(args, bounds):
    # Spatial selection is a bbox filter. Clip the union in GEOS after loading.
    return geo.union_all(read_layer(args, args.reference, REFERENCE_LAYER, bounds)).intersection(geo.box(*bounds))


def boundary_lines(geometry, bounds):
    inner = geo.box(bounds[0] + 1e-6, bounds[1] + 1e-6, bounds[2] - 1e-6, bounds[3] - 1e-6)
    boundary = geo.union_all([p.boundary for p in polygons(geometry)]).intersection(inner)
    return [line for line in geo.get_parts(boundary)
            if line.geom_type in ("LineString", "LinearRing") and line.length > 0]


def directed_distances(source, target):
    segments = []
    for line in target:
        vertices = geo.get_coordinates(line)
        segments.extend(geo.linestrings(np.stack((vertices[:-1], vertices[1:]), axis=1)))
    tree = geo.STRtree(segments)
    distances = []
    for line in source:
        count = max(1, math.ceil(line.length / 0.5))
        offsets = (np.arange(count) + 0.5) * (line.length / count)
        points = geo.line_interpolate_point(line, offsets)
        _, values = tree.query_nearest(points, all_matches=False, return_distance=True)
        distances.extend(values)
    return distances


def compare(actual, expected, bounds):
    extra, missing = actual.difference(expected), expected.difference(actual)
    shared = actual.intersection(expected).area
    union = shared + extra.area + missing.area
    left, right = boundary_lines(actual, bounds), boundary_lines(expected, bounds)
    errors = directed_distances(left, right) + directed_distances(right, left) if left and right else []
    return {
        "iou": shared / union if union else 1.0,
        "actualM2": actual.area,
        "referenceM2": expected.area,
        "extraM2": extra.area,
        "missingM2": missing.area,
        "differenceM2": extra.area + missing.area,
        "boundarySamples": len(errors),
        "boundaryMedianM": float(np.quantile(errors, 0.5)) if errors else None,
        "boundaryP95M": float(np.quantile(errors, 0.95)) if errors else None,
    }, extra, missing


def tiled(bounds):
    west, south, east, north = bounds
    return [(x, y, min(x + 200, east), min(y + 200, north))
            for y in range(south, north, 200) for x in range(west, east, 200)]


def timed(command, log):
    start = time.perf_counter()
    # Darwin time reports bytes, matching the previous product benchmarks.
    prefix = ["/usr/bin/time", "-lp"] if sys.platform == "darwin" else []
    with log.open("wb") as report:
        result = subprocess.run(prefix + list(map(str, command)), stdout=report, stderr=subprocess.STDOUT)
    output = log.read_text()
    failure = f"{command[0]} exited {result.returncode}. See {log}"
    if result.returncode and "java.lang.OutOfMemoryError" in output:
        raise HeapLimitExceeded(failure)
    require(result.returncode == 0, failure)
    elapsed = time.perf_counter() - start
    counters = {}
    for line in output.splitlines():
        fields = line.split()
        if len(fields) == 5 and fields[1:] == ["maximum", "resident", "set", "size"]:
            counters["peakRssBytes"] = int(fields[0])
        elif len(fields) == 2 and fields[0] in ("user", "sys"):
            counters[fields[0] + "Seconds"] = float(fields[1])
    return {"wallSeconds": elapsed, **counters}


def geometry(args, run, mode, bounds, cores, source, *, thinning=None, snapshots=False, heap="512m"):
    classpath = os.pathsep.join((str(args.classes), str(args.cli.resolve().parents[1] / "lib" / "*")))
    java_home = os.environ.get("JAVA_HOME")
    java = Path(java_home) / "bin/java" if java_home else "java"
    command = [java, f"-Xmx{heap}", "-cp", classpath, "pointcloud.VegetationOutlines"]
    if thinning is not None:
        command.append(f"--thinning={thinning}")
    if snapshots:
        command.append("--snapshots")
    command.extend((mode, run, *bounds))
    for core in cores:
        command.extend((*core, source))
    stats = timed(command, run.with_suffix(".log"))
    outline = geo.from_wkt((run / "outline.wkt").read_text())
    require(outline.is_valid, f"Invalid outline in {run}")
    require(outline.is_empty or outline.geom_type in ("Polygon", "MultiPolygon"),
            f"Unexpected outline geometry type in {run}")
    return outline, {"process": stats, "stages": json.loads((run / "metrics.json").read_text())}


def export(args, run, layers):
    started = time.perf_counter()
    with TemporaryDirectory(dir=run) as directory:
        folder = Path(directory)
        destination = folder / "comparison.gpkg"
        for name, geometry in layers.items():
            # WKT import with a caller-declared LV95 CRS, with no CRS conversion.
            table = folder / f"{name}.csv"
            with table.open("w") as stream:
                stream.write("id,WKT\n")
                for index, polygon in enumerate(polygons(geometry), 1):
                    stream.write(f'{index},"{geo.to_wkt(polygon, rounding_precision=-1)}"\n')
            command = [args.gdal_bin / "ogr2ogr", "-f", "GPKG", "-nlt", "POLYGON",
                       "-a_srs", "EPSG:2056", "-nln", name, "-oo", "GEOM_POSSIBLE_NAMES=WKT",
                       "-oo", "KEEP_GEOM_COLUMNS=NO"]
            if destination.exists():
                command.append("-update")
            subprocess.run([*command, destination, "CSV:" + str(table)], check=True)
        with closing(sqlite3.connect(destination.resolve().as_uri() + "?mode=ro", uri=True)) as package:
            columns = package.execute(
                "SELECT table_name, geometry_type_name, srs_id FROM gpkg_geometry_columns"
            ).fetchall()
            require(set(columns) == {(name, "POLYGON", 2056) for name in layers},
                    f"Unexpected GeoPackage layers: {columns}")
        destination.rename(run / destination.name)
    return time.perf_counter() - started


def run_case(args, path, mode, bounds, cores, source, expected, *, thinning=None, heap="512m"):
    print(f"{path.name}: {thinning or mode}", flush=True)
    actual, stats = geometry(args, path, mode, bounds, cores, source, thinning=thinning, heap=heap)
    started = time.perf_counter()
    comparison, extra, missing = compare(actual, expected, bounds)
    stats["comparisonSeconds"] = time.perf_counter() - started
    stats["exportSeconds"] = export(args, path, {
        "bestockung": actual, "referenz": expected, "zusaetzlich": extra, "fehlend": missing,
    })
    print(json.dumps(comparison), flush=True)
    return actual, {"comparison": comparison, **stats}


def prepared(manifest, name):
    item = manifest["regions"][name]
    require(tuple(item["bounds"]) == REGIONS[name] and item["coverage"] >= 11,
            f"Unexpected prepared bounds or halo for {name}")
    return Path(item["input"])


def seam_cases(args, work, mode, source, expected):
    bounds = REGIONS["seam"]
    west, south, east, north = bounds
    joint, joint_report = run_case(args, work / "seam-joint", mode, bounds, [bounds], source, expected)
    split, split_report = run_case(args, work / "seam-split", mode, bounds,
                                   [(west, south, SEAM_X, north), (SEAM_X, south, east, north)],
                                   source, expected)
    strip = (SEAM_X - 10, south, SEAM_X + 10, north)
    strip_box = geo.box(*strip)
    return {
        "joint": joint_report,
        "split": split_report,
        "difference": compare(split, joint, bounds)[0],
        "differenceWithin10m": compare(split.intersection(strip_box), joint.intersection(strip_box), strip)[0],
    }


def calibrate(args, work, source, expected):
    bounds = REGIONS["calibration"]
    reports = {}
    for mode in MODES:
        _, reports[mode] = run_case(args, work / f"calibration-{mode}", mode, bounds,
                                     tiled(bounds), source, expected)

    def ranking(mode):
        comparison = reports[mode]["comparison"]
        p95 = comparison["boundaryP95M"]
        return comparison["iou"], -(p95 if p95 is not None else math.inf), mode == "all"

    winner = max(MODES, key=ranking)
    print(f"Selected in Riedholz: {winner}", flush=True)
    return winner, reports


def benchmark(args, work, mode, expected, accepted_metrics, source):
    rows = []
    accepted_wkt = (work / f"calibration-{mode}/outline.wkt").read_bytes()
    for index in range(1, args.benchmark_runs + 1):
        run = work / f"benchmark-{index}"
        run.mkdir()
        # Warm the sole raw LAS needed for the calibration work outside timing.
        with source.open("rb") as stream:
            while stream.read(4 * 1024 * 1024):
                pass
        load_at_start = os.getloadavg()
        # New output paths force fresh terrain generation with already cached source LAS.
        preparation = timed([sys.executable, Path(__file__).with_name("prepare-vegetation-outlines.py"),
                             "--cache", args.cache, "--work-dir", run / "prep",
                             "--pdal", args.pdal, "--cli", args.cli, "--gdal", args.gdal_bin,
                             "--region", "calibration", "--terrain-workers", "3"],
                            run / "prepare.log")
        manifest = json.loads((run / "prep/prepare.json").read_text())
        bounds = REGIONS["calibration"]
        _, report = run_case(args, run / "outline", mode, bounds, tiled(bounds),
                             prepared(manifest, "calibration"), expected)
        require((run / "outline/outline.wkt").read_bytes() == accepted_wkt
                and report["comparison"] == accepted_metrics, f"Benchmark changed the accepted outline in {run}")
        rows.append({"run": index, "loadAtStart": load_at_start, "preparation": preparation, **report})
    return rows


def thinning_focus(args, baseline, expected):
    package = baseline / "calibration-all/comparison.gpkg"
    missing = sorted(read_layer(args, package, "fehlend"), key=lambda g: -g.area)[:2]
    require(len(missing) == 2, "The baseline needs both Riedholz omission polygons")
    actual = geo.union_all(read_layer(args, package, "bestockung"))
    delivered = geo.union_all(read_layer(args, package, "referenz"))
    require(delivered.equals(expected), "The Riedholz reference differs from the baseline package")

    def interior_holes(geometry):
        return [geo.Polygon(ring).difference(geometry)
                for polygon in polygons(geometry) for ring in polygon.interiors]

    probe = geo.union_all(missing)
    holes = interior_holes(actual)
    require(holes, "The baseline is missing the Riedholz opening")
    opening = max(holes, key=lambda g: g.intersection(probe).area)
    holes = interior_holes(delivered)
    require(holes, "The delivered reference is missing the Riedholz opening")
    delivered_opening = max(holes, key=lambda g: g.intersection(opening).area)
    require(probe.difference(opening).area < 1e-6 and delivered_opening.intersection(opening).area > 0,
            "The baseline omissions or delivered opening differ from the Riedholz diagnostics")
    return {"fehlflaeche_1_grid": missing[0], "fehlflaeche_2_grid": missing[1],
            "lichtung_flotron": delivered_opening}


def thinning_case(args, path, variant, bounds, cores, source, expected, focus, heap):
    print(f"{variant} {path.name} (-Xmx{heap})", flush=True)
    actual, stats = geometry(args, path, "all", bounds, cores, source,
                             thinning=variant, snapshots=True, heap=heap)
    started = time.perf_counter()
    clip = geo.box(*bounds)
    snapshots = {}
    for stage, filename in THINNING_SNAPSHOTS.items():
        outline = geo.from_wkt((path / filename).read_text())
        require(outline.is_valid, f"Invalid {stage} geometry in {path}")
        snapshots[stage] = outline.intersection(clip)
    comparison, extra, missing = compare(actual, expected, bounds)
    before, after = snapshots["beforeErosion"], snapshots["afterErosion"]
    coverage = {
        name: {"areaM2": target.area, **{
            stage + "M2": outline.intersection(target).area
            for stage, outline in {**snapshots, "final": actual}.items()
        }} for name, target in focus.items()
    }
    stats["comparisonSeconds"] = time.perf_counter() - started
    stats["exportSeconds"] = export(args, path, {
        "bestockung": actual, "referenz": expected, "zusaetzlich": extra, "fehlend": missing,
        "vor_erosion": before, "nach_erosion": after, "nach_aufweitung": snapshots["afterDilation"],
        **focus,
    })
    return actual, {
        "comparison": comparison,
        "coverageM2": {stage: outline.area for stage, outline in snapshots.items()},
        "erosionLossM2": before.difference(after).area,
        "fixedProbes": coverage,
        **stats,
    }


def thinning_benchmark(args, work, source, expected, reference_wkt, heap):
    bounds = REGIONS["calibration"]
    bounds_cores = tiled(bounds)
    work.mkdir()
    rows = []
    for index in range(3):
        order = (*THINNING_VARIANTS[index:], *THINNING_VARIANTS[:index])
        for variant in order:
            # Rewarm the sole prepared LAS outside the timed JVM, as for the existing benchmark.
            with source.open("rb") as stream:
                while stream.read(4 * 1024 * 1024):
                    pass
            path = work / f"round-{index + 1}-{variant}"
            load_at_start = os.getloadavg()
            _, stats = run_case(args, path, "all", bounds, bounds_cores, source, expected,
                                thinning=variant, heap=heap)
            require((path / "outline.wkt").read_bytes() == reference_wkt[variant],
                    f"Snapshot recording or repeated execution changed the outline in {path}")
            stages = stats["stages"]
            regional = stages.get("spacing") or {}
            stats["pointReadSeconds"] = regional.get("readSeconds", 0) + sum(
                tile["readSeconds"] for tile in stages["tiles"])
            stats["pointSelectionSeconds"] = regional.get("selectionSeconds", 0) + sum(
                tile["selectionSeconds"] for tile in stages["tiles"])
            stats["geometrySeconds"] = stages["finishSeconds"] + sum(
                tile["geometrySeconds"] for tile in stages["tiles"])
            rows.append({"round": index + 1, "variant": variant, "loadAtStart": load_at_start, **stats})
    summary = {}
    for variant in THINNING_VARIANTS:
        runs = [row for row in rows if row["variant"] == variant]
        walls = [row["process"]["wallSeconds"] for row in runs]
        rss = [row["process"]["peakRssBytes"] for row in runs if "peakRssBytes" in row["process"]]
        summary[variant] = {
            "wallMedianSeconds": median(walls), "wallMinSeconds": min(walls), "wallMaxSeconds": max(walls),
            "pointReadMedianSeconds": median(row["pointReadSeconds"] for row in runs),
            "pointSelectionMedianSeconds": median(row["pointSelectionSeconds"] for row in runs),
            "geometryMedianSeconds": median(row["geometrySeconds"] for row in runs),
            "exportMedianSeconds": median(row["exportSeconds"] for row in runs),
            "peakRssMaxBytes": max(rss) if rss else None,
        }
    return {"runs": rows, "summary": summary}


def thinning_profile(args, work, manifest, expected, baseline, focus, heap):
    seam = REGIONS["seam"]
    west, south, east, north = seam
    strip = (SEAM_X - 10, south, SEAM_X + 10, north)
    strip_box = geo.box(*strip)
    cases = {
        "calibration-all": ("calibration", tiled(REGIONS["calibration"])),
        "holdout": ("holdout", tiled(REGIONS["holdout"])),
        "seam-joint": ("seam", [seam]),
        "seam-split": ("seam", [(west, south, SEAM_X, north), (SEAM_X, south, east, north)]),
    }
    reports = {variant: {} for variant in THINNING_VARIANTS}
    seam_outlines = {variant: {} for variant in THINNING_VARIANTS}
    reference_wkt = {}
    for variant in THINNING_VARIANTS:
        (work / variant).mkdir(parents=True)
    for case, (region, cores) in cases.items():
        for variant in THINNING_VARIANTS:
            path = work / variant / case
            actual, reports[variant][case] = thinning_case(args, path, variant, REGIONS[region], cores,
                                                           prepared(manifest, region), expected[region],
                                                           focus if region == "calibration" else {}, heap)
            if region == "seam":
                seam_outlines[variant][case] = actual
            if variant == "grid" or region == "calibration":
                output_wkt = (path / "outline.wkt").read_bytes()
                if variant == "grid":
                    require(output_wkt == (baseline / case / "outline.wkt").read_bytes(),
                            f"Grid baseline changed in {case}")
                if region == "calibration":
                    reference_wkt[variant] = output_wkt
    for variant, outlines in seam_outlines.items():
        joint, split = outlines["seam-joint"], outlines["seam-split"]
        reports[variant]["seamDifference"] = compare(split, joint, seam)[0]
        reports[variant]["seamDifferenceWithin10m"] = compare(split.intersection(strip_box),
                                                                 joint.intersection(strip_box), strip)[0]
    benchmark = thinning_benchmark(args, work / "benchmark", prepared(manifest, "calibration"),
                                   expected["calibration"], reference_wkt, heap)
    return reports, benchmark


def compare_thinning(args, manifest, record):
    baseline = args.baseline_dir or args.cache / "acceptance"
    expected = {region: reference(args, bounds) for region, bounds in REGIONS.items()}
    focus = thinning_focus(args, baseline, expected["calibration"])
    record["baselineDirectory"] = str(baseline.resolve())
    inputs = {}
    for region in REGIONS:
        source = prepared(manifest, region)
        digest = sha256()
        with source.open("rb") as stream:
            while block := stream.read(4 * 1024 * 1024):
                digest.update(block)
        inputs[region] = {"path": str(source.resolve()), "sha256": digest.hexdigest()}
    record["inputLas"] = inputs
    record["heapProfiles"] = []
    for heap in ("512m", "1g"):
        attempt = {"maxHeap": heap, "directory": heap}
        record["heapProfiles"].append(attempt)
        work = args.output / heap
        try:
            comparisons, benchmark = thinning_profile(args, work, manifest, expected, baseline, focus, heap)
        except HeapLimitExceeded as failure:
            attempt.update({"status": "outOfMemory", "error": str(failure)})
            (args.output / "report.json").write_text(json.dumps(record, indent=2) + "\n")
            if heap == "1g":
                raise
            print("Heap limit exceeded at 512m. Repeating the entire comparison at 1g.", flush=True)
            continue
        attempt["status"] = "complete"
        record.update({"maxHeap": heap, "variants": comparisons, "benchmark": benchmark})
        (args.output / "report.json").write_text(json.dumps(record, indent=2) + "\n")
        return


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", type=Path, default=DEFAULT_CACHE / "prepare.json")
    parser.add_argument("--reference", type=Path, required=True)
    parser.add_argument("--pdal", type=Path, required=True)
    parser.add_argument("--gdal-bin", type=Path)
    parser.add_argument("--cli", type=Path, default=Path("build/install/pointcloud/bin/pointcloud"))
    parser.add_argument("--classes", type=Path, default=Path("experiments/build/classes/java/main"))
    parser.add_argument("--cache", type=Path, default=DEFAULT_CACHE)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--benchmark-runs", type=int, default=0)
    parser.add_argument("--compare-thinning", action="store_true",
                        help="compare grid, minimum spacing and no thinning with three fresh JVM runs each")
    parser.add_argument("--baseline-dir", type=Path, help="accepted baseline, defaults to <cache>/acceptance")
    args = parser.parse_args()
    args.gdal_bin = args.gdal_bin or args.pdal.resolve().parent
    require(args.benchmark_runs >= 0, "Negative benchmark run count")
    require(not args.compare_thinning or args.benchmark_runs == 0,
            "The thinning comparison has its own fixed benchmark rounds")
    args.output.mkdir(parents=True)
    manifest = json.loads(args.manifest.read_text())
    record = {
        "reference": str(args.reference.resolve()),
        "prepared": str(args.manifest.resolve()),
        "python": sys.version.split()[0],
        "shapely": geo.__version__,
        "geos": geo.geos_version_string,
    }
    if args.compare_thinning:
        compare_thinning(args, manifest, record)
        return
    expected = reference(args, REGIONS["calibration"])
    mode, record["calibration"] = calibrate(args, args.output, prepared(manifest, "calibration"), expected)
    record["selected"] = mode
    # Persist the choice before even reading the independent geometries.
    (args.output / "calibration.json").write_text(json.dumps(record, indent=2) + "\n")
    record["seam"] = seam_cases(args, args.output, mode, prepared(manifest, "seam"),
                                reference(args, REGIONS["seam"]))
    holdout = REGIONS["holdout"]
    _, record["holdout"] = run_case(args, args.output / "holdout", mode, holdout, tiled(holdout),
                                    prepared(manifest, "holdout"), reference(args, holdout))
    (args.output / "report.json").write_text(json.dumps(record, indent=2) + "\n")
    if args.benchmark_runs:
        source = Path(manifest["sources"]["2610-1232"]["input"])
        record["benchmark"] = benchmark(args, args.output, mode, expected,
                                        record["calibration"][mode]["comparison"], source)
        (args.output / "report.json").write_text(json.dumps(record, indent=2) + "\n")


if __name__ == "__main__":
    main()
