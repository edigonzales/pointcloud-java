"""Calibrate and evaluate the experimental vegetation outlines with a delivered GeoPackage."""

import argparse
from contextlib import closing
import json
import math
import os
from pathlib import Path
import sqlite3
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


def reference(args, bounds):
    # Spatial selection is a bbox filter. Clip the union in GEOS after loading.
    result = subprocess.check_output(
        [args.gdal_bin / "ogr2ogr", "-f", "GeoJSON", "-lco", "COORDINATE_PRECISION=15",
         "/vsistdout/", args.reference, REFERENCE_LAYER, "-spat", *map(str, bounds)], text=True)
    shapes = [shape(feature["geometry"]) for feature in json.loads(result)["features"]]
    require(all(g.is_valid for g in shapes), f"Invalid delivered polygon intersecting {bounds}")
    return geo.union_all(shapes).intersection(geo.box(*bounds))


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
    require(result.returncode == 0, f"{command[0]} exited {result.returncode}. See {log}")
    elapsed = time.perf_counter() - start
    counters = {}
    for line in log.read_text().splitlines():
        fields = line.split()
        if len(fields) == 5 and fields[1:] == ["maximum", "resident", "set", "size"]:
            counters["peakRssBytes"] = int(fields[0])
        elif len(fields) == 2 and fields[0] in ("user", "sys"):
            counters[fields[0] + "Seconds"] = float(fields[1])
    return {"wallSeconds": elapsed, **counters}


def geometry(args, run, mode, bounds, cores, source):
    classpath = os.pathsep.join((str(args.classes), str(args.cli.resolve().parents[1] / "lib" / "*")))
    java_home = os.environ.get("JAVA_HOME")
    java = Path(java_home) / "bin/java" if java_home else "java"
    command = [java, "-Xmx512m", "-cp", classpath, "pointcloud.VegetationOutlines", mode, run, *bounds]
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


def run_case(args, path, mode, bounds, cores, source, expected):
    print(f"{path.name}: {mode}", flush=True)
    actual, stats = geometry(args, path, mode, bounds, cores, source)
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
    args = parser.parse_args()
    args.gdal_bin = args.gdal_bin or args.pdal.resolve().parent
    require(args.benchmark_runs >= 0, "Negative benchmark run count")
    args.output.mkdir(parents=True)
    manifest = json.loads(args.manifest.read_text())
    record = {
        "reference": str(args.reference.resolve()),
        "prepared": str(args.manifest.resolve()),
        "python": sys.version.split()[0],
        "shapely": geo.__version__,
        "geos": geo.geos_version_string,
    }
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
