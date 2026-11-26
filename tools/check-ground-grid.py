"""Compare the Solothurn ground raster with PDAL 2.10.2 and GDAL."""

import argparse
import csv
import json
import math
import os
from pathlib import Path
import platform
import re
import runpy
import shutil
import struct
import subprocess
import tempfile
import time
from typing import NamedTuple


REFERENCE = runpy.run_path(str(Path(__file__).with_name("check-real-data.py")))
compare_stats = REFERENCE["compare_stats"]
require = REFERENCE["require"]
run = REFERENCE["run"]

POINTS_READ = 14_566_627
WIDTH = HEIGHT = 200
NODATA = -9999
BANDS = ("min", "max", "mean", "count")
TRANSFORM = [2594100, 1, 0, 1227800, 0, -1]
ROUNDS = 8


class GridCase(NamedTuple):
    example: str
    selected: int
    binned: int
    measured: int
    filled: dict
    size: tuple = (WIDTH, HEIGHT)
    points_read: int = POINTS_READ


class RasterProfile(NamedTuple):
    name: str
    window: int
    bands: tuple = BANDS
    dimension: str = "Z"
    nodata: float = NODATA
    compression: str = "none"


POLYGON = GridCase("polygon-ground-grid", 143_244, 143_238, 26_114, {0: 0})
RECTANGLE = GridCase("ground-grid-fill", 195_738, 195_715, 35_261, {0: 0, 1: 4_491, 2: 4_735, 5: 4_739})
BASELINE = RasterProfile("window-0", 0)
ADJACENT = RasterProfile("window-1", 1)
RELEASE_ADJACENT = RasterProfile("window-1-none", 1, ("mean", "count", "stdev"), nodata=-7777)
RELEASE_WIDE = RELEASE_ADJACENT._replace(name="window-2-none", window=2)
RELEASE_DEFLATE = RELEASE_WIDE._replace(name="window-2-deflate", compression="deflate")
RELEASE_SINGLE = RELEASE_DEFLATE._replace(name="single-mean", bands=("mean",))
RELEASE_INTENSITY = RasterProfile("intensity", 5, (*BANDS, "stdev"), "Intensity")


def inspect(gdalinfo, gdalsrsinfo, raster, case, profile):
    info = json.loads(subprocess.check_output([gdalinfo, "-json", raster], text=True))
    require(info["driverShortName"] == "GTiff", f"{raster.name}: not a GeoTIFF")
    require(info["metadata"].get("IMAGE_STRUCTURE", {}).get("COMPRESSION", "NONE") == profile.compression.upper(),
            f"{raster.name}: expected compression {profile.compression}")
    require(tuple(info["size"]) == case.size, f"{raster.name}: incorrect grid size")
    transform = [*TRANSFORM[:3], 1227600 + case.size[1], *TRANSFORM[4:]]
    require(info["geoTransform"] == transform, f"{raster.name}: incorrect north-up transform {info['geoTransform']}")
    require(info["metadata"][""].get("AREA_OR_POINT") == "Area", f"{raster.name}: incorrect pixel reference")
    crs = subprocess.check_output([gdalsrsinfo, "-o", "epsg", raster], text=True).strip()
    require(crs == "EPSG:2056" and info["coordinateSystem"]["dataAxisToSRSAxisMapping"] == [1, 2],
            f"{raster.name}: incorrect horizontal CRS or axis mapping")
    require(len(info["bands"]) == len(profile.bands), f"{raster.name}: incorrect band count")
    for band, name in zip(info["bands"], profile.bands):
        require(band.get("description") == name, f"{raster.name}: incorrect band description for {name}")
        require(band["type"] == "Float64" and band.get("noDataValue") == profile.nodata,
                f"{raster.name}: incorrect dtype or NoData in {name} band")


def read_bands(translate, raster, case, profile):
    # Band-sequential ENVI conveys unrounded IEEE doubles and declares byte order.
    raw = raster.with_suffix(".bin")
    subprocess.run([translate, "-q", "-of", "ENVI", "-ot", "Float64", "-co", "INTERLEAVE=BSQ", raster, raw],
                   check=True)
    header = raw.with_suffix(".hdr").read_text()
    byte_order = re.search(r"^byte order\s*=\s*([01])\s*$", header, re.MULTILINE)
    require(byte_order is not None, f"{raster.name}: missing ENVI byte order")
    endian = "<" if byte_order[1] == "0" else ">"
    cells = math.prod(case.size)
    data = raw.read_bytes()
    require(len(data) == len(profile.bands) * cells * 8, f"{raster.name}: unexpected sample data length")
    return tuple(struct.unpack_from(f"{endian}{cells}d", data, index * cells * 8) for index in range(len(profile.bands)))


def compare_values(java, pdal, case, profile):
    maximum_error = 0.0
    count_band = java[profile.bands.index("count")] if "count" in profile.bands else None
    expected_filled = case.filled[profile.window]
    tolerance = 1e-9 if profile.dimension == "Z" else 1e-8
    for name, actual, expected in zip(profile.bands, java, pdal):
        if name == "count":
            require(actual == expected, "Count band differs from PDAL")
            require(sum(actual) == case.binned, "Incorrect raster point count")
            require(sum(value > 0 for value in actual) == case.measured, "Incorrect measured cell count")
            continue
        valid = 0
        for index, (a, b) in enumerate(zip(actual, expected)):
            if a != profile.nodata:
                valid += 1
            if profile.nodata in (a, b) or (name in ("min", "max") and count_band is not None and count_band[index] > 0):
                equal = a == b
            else:
                equal = math.isclose(a, b, rel_tol=0.0, abs_tol=tolerance)
            if not equal:
                raise RuntimeError(f"{name}, raster cell {index}: {a} (Java) differs from {b} (PDAL)")
            maximum_error = max(maximum_error, abs(a - b))
        require(valid == case.measured + expected_filled, f"{name}: incorrect valid cell count {valid}")

    cells = math.prod(case.size)
    print(f"{case.example}, {profile.name}: {case.binned:,} points in {case.measured:,} measured cells, "
          f"{expected_filled:,} filled and {cells - case.measured - expected_filled:,} NoData. "
          f"All {cells:,} cells agree; maximum error {maximum_error:.3g} ({profile.dimension}).", flush=True)


def pipeline_stages(args, case, source=None):
    source = (args.data / "so-2019.laz" if source is None else source).resolve()
    require(source.is_file(), f"Missing real-data input: {source}")
    stages = json.loads(Path(f"examples/{case.example}.json").read_text())["pipeline"]
    stages[0]["filename"] = str(source)
    return stages


def pipeline_command(args, stages, work, engine, workers, profile):
    target, metadata, config = (work / name for name in ("grid.tif", "metadata.json", "pipeline.json"))
    writer = {**stages[-1], "filename": str(target), "window_size": profile.window,
              "dimension": profile.dimension, "output_type": ",".join(profile.bands), "nodata": profile.nodata}
    inputs = [{**stage, "dimensions": profile.dimension} if stage["type"] == "filters.stats" else stage
              for stage in stages[:-1]]
    if engine == "pdal":
        writer["type"] = "writers.gdal"
        writer.pop("compression", None)
        if profile.compression == "deflate":
            writer["gdalopts"] = "COMPRESS=DEFLATE"
        command = [args.pdal, "pipeline", config, "--stream", f"--readers.las.threads={workers}", "--metadata", metadata]
    else:
        writer["compression"] = profile.compression
        command = [args.cli, "pipeline", config, "--metadata", metadata, "--laz-workers", str(workers)]
    config.write_text(json.dumps({"pipeline": [*inputs, writer]}))
    return list(map(str, command)), target, metadata


def read_output(geo_tools, target, metadata, case, profile):
    gdalinfo, gdalsrsinfo, translate = geo_tools
    inspect(gdalinfo, gdalsrsinfo, target, case, profile)
    return metadata.read_bytes(), read_bands(translate, target, case, profile)


def pdal_stats(metadata):
    stages = json.loads(metadata)["stages"]
    return stages["readers.las"]["count"], stages["filters.stats"]["statistic"]


def compare_reference(case, profile, actual, reference):
    java = json.loads(actual[0])
    points_read, stats = pdal_stats(reference[0])
    require(java["pointsRead"] == points_read == case.points_read, "Input read count differs")
    require((java["pointsSelected"], java["pointsBinned"], java["pointsWritten"])
            == (case.selected, case.binned, 0), f"Incorrect Java raster point counts: {java}")
    compare_stats(java["statistics"], stats, case.selected)
    compare_values(actual[1], reference[1], case, profile)


def check_grid(args, geo_tools, case, profile):
    stages = pipeline_stages(args, case)
    outputs = []
    for engine, workers in (("java", 1), ("java", 4), ("pdal", 4)):
        with tempfile.TemporaryDirectory(prefix="pointcloud-raster-") as scratch:
            command, target, metadata = pipeline_command(args, stages, Path(scratch), engine, workers, profile)
            run(command)
            outputs.append(read_output(geo_tools, target, metadata, case, profile))
    serial, parallel, reference = outputs
    require(serial == parallel, "Serial and parallel Java raster values or metadata differ")
    compare_reference(case, profile, serial, reference)
    return serial


def check_filling(baseline, filled):
    require(baseline[0] == filled[0], "Filling changes point metadata")
    measured = baseline[1]
    require(measured[3] == filled[1][3], "Filling changes counts")
    for index, count in enumerate(measured[3]):
        if count > 0:
            require(all(a[index] == b[index] for a, b in zip(measured[:3], filled[1][:3])),
                    f"Filling changes measured cell {index}")

    # Withheld cells are ten pixels apart, so none can serve as a donor to another.
    heights, counts = measured[2:]
    withheld = 0
    errors = []
    for row in range(5, HEIGHT - 1, 10):
        for column in range(5, WIDTH - 1, 10):
            cell = row * WIDTH + column
            if counts[cell] == 0:
                continue
            withheld += 1
            donors = [heights[cell + dy * WIDTH + dx]
                      for dy in (-1, 0, 1) for dx in (-1, 0, 1)
                      if (dx or dy) and counts[cell + dy * WIDTH + dx] > 0]
            if donors:
                errors.append(math.fsum(donors) / len(donors) - heights[cell])
    require(errors, "No withheld cells reconstructed")
    mae = math.fsum(map(abs, errors)) / len(errors)
    rmse = math.sqrt(math.fsum(error * error for error in errors) / len(errors))
    print(f"Withheld mean elevations: {len(errors)}/{withheld} reconstructed ({len(errors) / withheld:.1%}), "
          f"MAE {mae:.4f} m, RMSE {rmse:.4f} m.", flush=True)


def timing_functions():
    require(platform.system() == "Darwin", "The benchmark uses macOS /usr/bin/time -lp")
    timing_tools = runpy.run_path(str(Path(__file__).with_name("check-parallel-data.py")))
    return timing_tools["execute"], timing_tools["timing"]


def measure(command, execute, timing, env):
    started = time.perf_counter_ns()
    result = execute(["/usr/bin/time", "-lp", *command], env)
    elapsed = (time.perf_counter_ns() - started) / 1e9
    _, user, system, rss = timing(result.stderr)
    return elapsed, user, system, rss


def benchmark(args, geo_tools):
    execute, timing = timing_functions()
    stages = pipeline_stages(args, RECTANGLE)
    profiles = (RELEASE_ADJACENT, RELEASE_WIDE, RELEASE_DEFLATE) if args.release else (BASELINE, ADJACENT)
    engines = [("java", 1), ("pdal", 1), ("java", 4), ("pdal", 4)]
    variants = [(engine, workers, profile) for profile in profiles for engine, workers in engines]
    env = dict(os.environ, JAVA_OPTS="-Xmx512m")
    outputs = {}
    rows = []
    with tempfile.TemporaryDirectory(prefix="pointcloud-raster-") as scratch:
        work = Path(scratch)
        for engine, workers, profile in variants:
            command, target, metadata = pipeline_command(
                args, stages, Path(tempfile.mkdtemp(dir=work)), engine, workers, profile)
            run(command)
            outputs[engine, workers, profile] = read_output(geo_tools, target, metadata, RECTANGLE, profile)
        for profile in profiles:
            serial = outputs["java", 1, profile]
            require(serial == outputs["java", 4, profile], "Serial and parallel Java raster values or metadata differ")
            for workers in (1, 4):
                compare_reference(RECTANGLE, profile, serial, outputs["pdal", workers, profile])

        for index in range(ROUNDS):
            order = variants[index:] + variants[:index]
            if args.release:
                profile_offset = index % len(profiles)
                engine_offset = index % len(engines)
                order = [(engine, workers, profile)
                         for profile in profiles[profile_offset:] + profiles[:profile_offset]
                         for engine, workers in engines[engine_offset:] + engines[:engine_offset]]
            for position, (engine, workers, profile) in enumerate(order, start=1):
                with tempfile.TemporaryDirectory(dir=work) as path:
                    command, target, metadata = pipeline_command(args, stages, Path(path), engine, workers, profile)
                    elapsed, user, system, rss = measure(command, execute, timing, env)
                    actual_json, bands = read_output(geo_tools, target, metadata, RECTANGLE, profile)
                    expected_json, expected_bands = outputs[engine, workers, profile]
                    require(bands == expected_bands, "Measured run raster differs from its verified warm-up")
                    if engine == "pdal":
                        require(pdal_stats(actual_json) == pdal_stats(expected_json), "Measured run PDAL stats differ")
                    else:
                        require(actual_json == expected_json, "Measured run Java metadata differs")
                    row = (index + 1, position, engine, workers, profile.window, f"{elapsed:.9f}",
                           user, system, rss, target.stat().st_size, metadata.stat().st_size)
                    rows.append((*row, profile.name, profile.compression) if args.release else row)
                    print(f"Round {index + 1}/{ROUNDS}: {engine} {workers} readers, {profile.name}, "
                          f"{elapsed:.4f} s", flush=True)
    with args.benchmark_csv.open("w", newline="") as file:
        writer = csv.writer(file, lineterminator="\n")
        fields = ("round", "position", "engine", "reader_workers", "window_size",
                  "wall_s", "user_s", "system_s", "peak_rss_bytes", "tiff_bytes", "metadata_bytes")
        writer.writerow((*fields, "profile", "compression") if args.release else fields)
        writer.writerows(rows)


def stress(args, geo_tools):
    execute, timing = timing_functions()
    size = 1000
    count = size * size // 2
    case = GridCase("checkerboard", count, count, count, {1: count, 20: count}, (size, size), count)
    env = dict(os.environ, JAVA_OPTS="-Xmx512m")
    with tempfile.TemporaryDirectory(prefix="pointcloud-raster-stress-") as scratch:
        work = Path(scratch)
        coordinates, source, config = (work / name for name in ("points.csv", "points.las", "make-las.json"))
        with coordinates.open("w") as data:
            data.write("X,Y,Z\n")
            for y in range(size):
                for x in range(y % 2, size, 2):
                    data.write(f"{2594100 + x}.5,{1227600 + y}.5,{650 + ((x + 2*y) % 50) / 10:.2f}\n")
        config.write_text(json.dumps([
            {"type": "readers.text", "filename": str(coordinates), "override_srs": "EPSG:2056"},
            {"type": "writers.las", "filename": str(source), "scale_x": 0.01, "scale_y": 0.01, "scale_z": 0.01},
        ]))
        run([args.pdal, "pipeline", config, "--stream"])
        stages = pipeline_stages(args, RECTANGLE, source)
        del stages[1:3]
        stages[-1].update(width=size, height=size)
        for window in (1, 20):
            profile = RELEASE_ADJACENT._replace(name=f"stress-window-{window}", window=window)
            outputs = []
            for engine in ("java", "pdal"):
                path = Path(tempfile.mkdtemp(dir=work))
                command, target, metadata = pipeline_command(args, stages, path, engine, 1, profile)
                wall, user, system, rss = measure(command, execute, timing, env)
                outputs.append(read_output(geo_tools, target, metadata, case, profile))
                print(f"{profile.name} {engine}: {wall:.3f} s wall, {user + system:.2f} s CPU, "
                      f"{rss / (1 << 20):.2f} MiB RSS, {target.stat().st_size} TIFF bytes.", flush=True)
            compare_reference(case, profile, *outputs)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("pdal", nargs="?", default="pdal", help="PDAL 2.10.2 executable")
    parser.add_argument("--cli", type=Path, default=Path("build/install/pointcloud/bin/pointcloud"))
    parser.add_argument("--data", type=Path, default=Path(".cache/test-data"), help="directory of cached inputs")
    parser.add_argument("--gdal-bin", type=Path, help="GDAL tools directory (default: next to PDAL or on PATH)")
    parser.add_argument("--release", action="store_true", help="select the expanded raster release cases or benchmark")
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--benchmark-csv", type=Path, help="run the verified macOS benchmark and write sample CSV")
    mode.add_argument("--stress", action="store_true", help="compare large synthetic filled rasters on macOS")
    args = parser.parse_args()
    pdal = shutil.which(args.pdal)
    if pdal is None:
        parser.error(f"PDAL not found: {args.pdal}")
    args.pdal = pdal
    version = subprocess.check_output([pdal, "--version"], text=True)
    require(re.search(r"^pdal 2\.10\.2(?:\s|$)", version, re.MULTILINE), f"Expected PDAL 2.10.2:\n{version}")
    tools = []
    for name in ("gdalinfo", "gdalsrsinfo", "gdal_translate"):
        sibling = (args.gdal_bin or Path(pdal).parent) / name
        executable = shutil.which(str(sibling))
        if executable is None and args.gdal_bin is None:
            executable = shutil.which(name)
        if executable is None:
            parser.error(f"GDAL tool not found: {name}")
        tools.append(executable)
    if args.benchmark_csv:
        benchmark(args, tools)
    elif args.stress:
        stress(args, tools)
    elif args.release:
        plain = check_grid(args, tools, RECTANGLE, RELEASE_WIDE)
        compressed = check_grid(args, tools, RECTANGLE, RELEASE_DEFLATE)
        require(plain == compressed, "Deflate changes raster values or JSON metadata")
        single = check_grid(args, tools, RECTANGLE._replace(example="ground-grid-height"), RELEASE_SINGLE)
        require(single == (compressed[0], compressed[1][:1]), "Single-band output changes mean")
        check_grid(args, tools, RECTANGLE._replace(example="ground-grid-intensity"), RELEASE_INTENSITY)
    else:
        check_grid(args, tools, POLYGON, BASELINE)
        baseline = check_grid(args, tools, RECTANGLE, BASELINE)
        filled = check_grid(args, tools, RECTANGLE, ADJACENT)
        check_filling(baseline, filled)


if __name__ == "__main__":
    main()
