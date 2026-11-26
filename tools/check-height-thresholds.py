"""Verify and time height-threshold fractions in Riedholz against PDAL 2.10.2."""

import argparse
import csv
import filecmp
import hashlib
import json
import os
from pathlib import Path
import re
import runpy
import statistics
import subprocess
import tempfile
import time
from typing import NamedTuple


HAG = runpy.run_path(str(Path(__file__).with_name("check-hag-dem.py")))
GRID = HAG["GRID"]
require = HAG["require"]
run = HAG["run"]
AREA = HAG["Area"]("riedholz", "so-2019-riedholz.laz", 7_327_607, 2610109, 1232741, (202, 202))
ORIGIN = (2610110, 1232540)
SIZE = (200, 200)
THRESHOLDS = (0.5, 2, 5)
STRESS_THRESHOLDS = (0, 0.5, 1, 2, 3, 5, 10, 20)
NODATA = -9999
POINTS_BINNED = 599_763
EMPTY_CELLS = 244
NUMERATORS = (312_528, 306_984, 280_460)
ROUNDS = 8
JAVA_ENV = dict(os.environ, JAVA_OPTS="-Xmx512m")


class Output(NamedTuple):
    target: Path
    metadata: Path
    commands: tuple
    count_rasters: tuple = ()


def band_names(thresholds):
    return ("count", *(f"fraction_above_{threshold:g}" for threshold in thresholds))


def prepare_run(args, stages, directory, engine, workers, profile="thresholds"):
    target, metadata, config = (directory / name for name in ("grid.tif", "metadata.json", "pipeline.json"))
    inputs = [stage.copy() for stage in stages[:-1]]
    writer = {**stages[-1], "filename": str(target)}
    if engine == "java":
        if profile == "count-only":
            del writer["height_thresholds"]
        elif profile == "stress":
            writer.update(width=1000, height=1000, output_type="min,max,mean,count,stdev",
                          height_thresholds=STRESS_THRESHOLDS)
        config.write_text(json.dumps([*inputs, writer], indent=2) + "\n")
        command = [args.cli, "pipeline", config, "--laz-workers", workers, "--metadata", metadata]
        return Output(target, metadata, (tuple(map(str, command)),))

    hag = next(stage for stage in inputs if stage["type"] == "filters.hag_dem")
    hag["min_clamp"] = -1e100
    thresholds = writer.pop("height_thresholds")
    writer.pop("compression", None)
    writer.update(type="writers.gdal", allow_empty=True, data_type="double", window_size=0,
                  nodata=NODATA, gdalopts="COMPRESS=NONE")
    count_rasters = tuple(directory / f"count-{index}.tif" for index in range(len(thresholds) + 1))
    writers = [{**writer, "filename": str(count_rasters[0])}]
    for threshold, raster in zip(thresholds, count_rasters[1:]):
        writers.append({**writer, "filename": str(raster), "where": f"HeightAboveGround > {threshold:g}"})
    config.write_text(json.dumps([*inputs, *writers], indent=2) + "\n")
    pipeline = [args.pdal, "pipeline", config, "--stream", f"--readers.las.threads={workers}",
                "--metadata", metadata]
    # Sequential where-filtered writers share the reader and HAG calculation. The count rasters
    # are intermediate values. Include GDAL's multi-band output step in the end-to-end timing.
    package = [args.gdal / "gdal", "raster", "calc", "-q", "-i", f"n={count_rasters[0]}"]
    for index, raster in enumerate(count_rasters[1:], start=1):
        package.extend(("-i", f"a{index}={raster}"))
    package.extend(("--calc", "n"))
    for index in range(1, len(count_rasters)):
        package.extend(("--calc", f"n > 0 ? a{index}/n : {NODATA}"))
    package.extend(("--ot", "Float64", f"--nodata={NODATA}", "--co", "COMPRESS=NONE", "-o", target))
    return Output(target, metadata, (tuple(map(str, pipeline)), tuple(map(str, package))), count_rasters)


def inspect(args, path, names, *, size=SIZE, named=True):
    info = json.loads(subprocess.check_output([args.gdal / "gdalinfo", "-json", path], text=True))
    crs = subprocess.check_output([args.gdal / "gdalsrsinfo", "-o", "epsg", path], text=True).strip()
    require(info["driverShortName"] == "GTiff" and tuple(info["size"]) == size
            and info["geoTransform"] == [ORIGIN[0], 1, 0, ORIGIN[1] + size[1], 0, -1]
            and info["metadata"][""].get("AREA_OR_POINT") == "Area"
            and info["metadata"].get("IMAGE_STRUCTURE", {}).get("COMPRESSION", "NONE") == "NONE",
            f"{path}: unexpected GeoTIFF format, grid or compression")
    require(crs == "EPSG:2056" and info["coordinateSystem"]["dataAxisToSRSAxisMapping"] == [1, 2],
            f"{path}: expected LV95 with easting, northing")
    bands = info["bands"]
    require(len(bands) == len(names), f"{path}: expected {len(names)} bands")
    for name, band in zip(names, bands):
        require(band["type"] == "Float64" and band.get("noDataValue") == NODATA
                and (not named or band.get("description") == name), f"{path}: incorrect band {name}")


def values(args, path, names):
    case = GRID["GridCase"](AREA.name, POINTS_BINNED, POINTS_BINNED, SIZE[0] * SIZE[1] - EMPTY_CELLS, {0: 0}, SIZE)
    profile = GRID["RasterProfile"]("height-thresholds", 0, names)
    return GRID["read_bands"](args.gdal / "gdal_translate", path, case, profile)


def verify_metadata(output, engine):
    metadata = json.loads(output.metadata.read_text())
    if engine == "java":
        require((metadata["pointsRead"], metadata["pointsSelected"], metadata["pointsBinned"],
                 metadata["pointsWritten"]) == (AREA.points, POINTS_BINNED, POINTS_BINNED, 0),
                f"{output.metadata}: Java point counts differ")
    else:
        stages = metadata["stages"]
        require(stages["readers.las"]["count"] == AREA.points
                and len(stages["writers.gdal"]) == len(output.count_rasters),
                f"{output.metadata}: expected one reader and one writer per count raster")


def verify_samples(actual, expected, names, path):
    for name, observed_band, reference_band in zip(names, actual, expected):
        if observed_band != reference_band:
            for cell, (observed, reference) in enumerate(zip(observed_band, reference_band)):
                if observed != reference:
                    row, column = divmod(cell, SIZE[0])
                    raise RuntimeError(f"{path}: {name} at row {row}, column {column}: {observed} != {reference}")


def verify_pdal_counts(args, pdal):
    counts = []
    for raster in pdal.count_rasters:
        inspect(args, raster, ("count",))
        band, = values(args, raster, ("count",))
        require(all(n >= 0 and n.is_integer() for n in band), f"{raster}: invalid count values")
        counts.append(band)
    denominator, *numerators = counts
    require(sum(denominator) == POINTS_BINNED and denominator.count(0) == EMPTY_CELLS,
            "Unexpected Riedholz point count or coverage")
    require(tuple(map(sum, numerators)) == NUMERATORS
            and all(n >= a >= b >= c for n, a, b, c in zip(*counts)), "Unexpected Riedholz threshold counts")
    return (denominator, *(tuple(a / n if n else NODATA for a, n in zip(band, denominator)) for band in numerators))


def acceptance(args, work, stages):
    outputs = {}
    names = band_names(THRESHOLDS)
    for engine, workers in (("java", 1), ("java", 4), ("pdal", 4)):
        directory = work / f"{engine}-{workers}"
        directory.mkdir()
        output = prepare_run(args, stages, directory, engine, workers)
        for command in output.commands:
            run(command)
        verify_metadata(output, engine)
        inspect(args, output.target, names, named=engine == "java")
        outputs[engine, workers] = output

    serial, parallel, pdal = outputs["java", 1], outputs["java", 4], outputs["pdal", 4]
    require(filecmp.cmp(serial.target, parallel.target, shallow=False)
            and serial.metadata.read_bytes() == parallel.metadata.read_bytes(),
            "Java GeoTIFF or metadata differs between one and four workers")
    expected = verify_pdal_counts(args, pdal)
    for output in (serial, pdal):
        verify_samples(values(args, output.target, names), expected, names, output.target)
    print(f"{AREA.name}: Java 1/4 GeoTIFF and metadata are byte-identical. All 40,000 cells agree exactly with "
          f"PDAL 2.10.2: {POINTS_BINNED:,} echoes, {EMPTY_CELLS} empty cells, above thresholds {NUMERATORS}.",
          flush=True)
    return expected, serial.metadata.read_bytes()


def verify_benchmark_output(args, output, engine, profile, expected, java_metadata):
    verify_metadata(output, engine)
    if engine == "java":
        require(output.metadata.read_bytes() == java_metadata, "Timed Java metadata differs from acceptance")
    names = ("count",) if profile == "count-only" else band_names(THRESHOLDS)
    verify_samples(values(args, output.target, names), expected[:len(names)], names, output.target)


def benchmark(args, work, stages, expected, java_metadata):
    execute, timing = GRID["timing_functions"]()
    measure = GRID["measure"]
    variants = (("java", 1, "thresholds"), ("pdal", 1, "thresholds"),
                ("java", 4, "thresholds"), ("pdal", 4, "thresholds"))
    control = ("java", 4, "count-only")
    for path in (Path(stages[0]["filename"]), Path(next(stage["raster"] for stage in stages
                                                      if stage["type"] == "filters.hag_dem"))):
        with path.open("rb") as stream:
            while stream.read(1 << 20):
                pass
    with tempfile.TemporaryDirectory(dir=work) as scratch:
        for engine, workers, profile in (*variants, control):
            directory = Path(tempfile.mkdtemp(dir=scratch))
            output = prepare_run(args, stages, directory, engine, workers, profile)
            for command in output.commands:
                run(command)
            verify_benchmark_output(args, output, engine, profile, expected, java_metadata)

    print(f"System before timed runs: {os.cpu_count()} logical CPUs, load averages {os.getloadavg()}", flush=True)
    args.benchmark_csv.parent.mkdir(parents=True, exist_ok=True)
    timings = {variant: [] for variant in (*variants, control)}
    with args.benchmark_csv.open("w", newline="") as file:
        csv_writer = csv.writer(file, lineterminator="\n")
        csv_writer.writerow(("round", "position", "engine", "reader_workers", "profile", "points_read",
                             "wall_s", "pipeline_wall_s", "packaging_wall_s", "user_s", "system_s",
                             "peak_rss_bytes", "points_per_s", "tiff_bytes", "metadata_bytes",
                             "loadavg_1m_before", "loadavg_1m_after"))
        for index in range(ROUNDS):
            start = index % len(variants)
            order = list(variants[start:] + variants[:start])
            java_parallel = order.index(("java", 4, "thresholds"))
            order.insert(java_parallel + index % 2, control)
            for position, variant in enumerate(order, start=1):
                engine, workers, profile = variant
                with tempfile.TemporaryDirectory(dir=work) as directory:
                    output = prepare_run(args, stages, Path(directory), *variant)
                    load_before = os.getloadavg()[0]
                    start_ns = time.perf_counter_ns()
                    metrics = [measure(command, execute, timing, JAVA_ENV) for command in output.commands]
                    wall = (time.perf_counter_ns() - start_ns) / 1e9
                    load_after = os.getloadavg()[0]
                    walls, users, systems, rss = zip(*metrics)
                    verify_benchmark_output(args, output, engine, profile, expected, java_metadata)
                    row = (index + 1, position, engine, workers, profile, AREA.points, f"{wall:.9f}",
                           f"{walls[0]:.9f}", f"{sum(walls[1:]):.9f}", sum(users), sum(systems), max(rss),
                           f"{AREA.points / wall:.1f}", output.target.stat().st_size, output.metadata.stat().st_size,
                           f"{load_before:.3f}", f"{load_after:.3f}")
                    csv_writer.writerow(row)
                    file.flush()
                    timings[variant].append(wall)
                    print(f"Round {index + 1}/{ROUNDS}: {engine} {workers} readers, {profile}, {wall:.4f} s "
                          f"(1m load {load_before:.2f} -> {load_after:.2f})", flush=True)
    for (engine, workers, profile), walls in timings.items():
        print(f"{engine} {workers} {profile}: median {statistics.median(walls):.4f} s, "
              f"range {min(walls):.4f}..{max(walls):.4f} s", flush=True)


def stress(args, work, stages):
    execute, timing = GRID["timing_functions"]()
    output = prepare_run(args, stages, work, "java", 4, "stress")
    wall, user, system, rss = GRID["measure"](output.commands[0], execute, timing, JAVA_ENV)
    names = ("min", "max", "mean", "count", "stdev", *band_names(STRESS_THRESHOLDS)[1:])
    inspect(args, output.target, names, size=(1000, 1000))
    verify_metadata(output, "java")
    print(f"1,000,000 cells, 8 thresholds and 5 standard bands under -Xmx512m: "
          f"{wall:.3f} s wall, {user + system:.2f} s CPU, {rss / (1 << 20):.2f} MiB RSS, "
          f"{output.target.stat().st_size:,} GeoTIFF bytes", flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("pdal", type=Path, help="PDAL 2.10.2 executable")
    parser.add_argument("--cli", type=Path, default=Path("build/install/pointcloud/bin/pointcloud"))
    parser.add_argument("--gdal", type=Path, help="GDAL tool directory (default: alongside PDAL)")
    parser.add_argument("--data", type=Path, default=Path(".cache/test-data"))
    parser.add_argument("--dem-dir", type=Path, default=Path(".cache/height-thresholds/dem"))
    parser.add_argument("--work", type=Path, default=Path(".cache/height-thresholds"))
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--benchmark-csv", type=Path, help="also run the paired macOS timing comparison")
    mode.add_argument("--stress", action="store_true", help="check the maximum grid and band count under -Xmx512m")
    mode.add_argument("--prepare-dem", action="store_true", help="only prepare the official 1 m Riedholz DEM")
    args = parser.parse_args()
    args.pdal, args.cli, args.data, args.dem_dir, args.work = (
        path.resolve() for path in (args.pdal, args.cli, args.data, args.dem_dir, args.work))
    args.gdal = args.gdal.resolve() if args.gdal else args.pdal.parent
    version = subprocess.check_output([args.pdal, "--version"], text=True)
    require(re.search(r"^pdal 2\.10\.2(?:\s|$)", version, re.MULTILINE), f"Expected PDAL 2.10.2: {version}")
    args.work.mkdir(parents=True, exist_ok=True)
    dem = HAG["prepare_dem"](args, AREA, args.work)
    if args.prepare_dem:
        return

    source = args.data / AREA.laz
    require(source.is_file() and source.stat().st_size == 30_303_122, f"Missing or incomplete LAZ: {source}")
    print(f"{source.name}: SHA-256 {hashlib.sha256(source.read_bytes()).hexdigest()}", flush=True)
    example = Path(__file__).resolve().parents[1] / "examples/height-thresholds-riedholz.json"
    stages = json.loads(example.read_text())["pipeline"]
    stages[0]["filename"] = str(source)
    next(stage for stage in stages if stage["type"] == "filters.hag_dem")["raster"] = str(dem)
    work = Path(tempfile.mkdtemp(prefix="run-", dir=args.work))
    print(f"Riedholz outputs: {work}", flush=True)
    if args.stress:
        stress(args, work, stages)
    else:
        expected, metadata = acceptance(args, work, stages)
        if args.benchmark_csv:
            benchmark(args, work, stages, expected, metadata)


if __name__ == "__main__":
    main()
