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
HEIGHT_TOLERANCE = 1e-9
ROUNDS = 8


class GridCase(NamedTuple):
    example: str
    selected: int
    binned: int
    measured: int
    filled: int


POLYGON = GridCase("polygon-ground-grid", 143_244, 143_238, 26_114, 0)
RECTANGLE = GridCase("ground-grid-fill", 195_738, 195_715, 35_261, 4_491)


def inspect(gdalinfo, gdalsrsinfo, raster):
    info = json.loads(subprocess.check_output([gdalinfo, "-json", raster], text=True))
    require(info["driverShortName"] == "GTiff", f"{raster.name}: not a GeoTIFF")
    require(info["metadata"].get("IMAGE_STRUCTURE", {}).get("COMPRESSION", "NONE") == "NONE",
            f"{raster.name}: expected an uncompressed GeoTIFF")
    require(info["size"] == [WIDTH, HEIGHT], f"{raster.name}: incorrect grid size")
    require(info["geoTransform"] == TRANSFORM, f"{raster.name}: incorrect north-up transform {info['geoTransform']}")
    require(info["metadata"][""].get("AREA_OR_POINT") == "Area", f"{raster.name}: incorrect pixel reference")
    crs = subprocess.check_output([gdalsrsinfo, "-o", "epsg", raster], text=True).strip()
    require(crs == "EPSG:2056" and info["coordinateSystem"]["dataAxisToSRSAxisMapping"] == [1, 2],
            f"{raster.name}: incorrect horizontal CRS or axis mapping")
    require(len(info["bands"]) == len(BANDS), f"{raster.name}: incorrect band count")
    for band, name in zip(info["bands"], BANDS):
        require(band.get("description") == name, f"{raster.name}: incorrect band description for {name}")
        require(band["type"] == "Float64" and band.get("noDataValue") == NODATA,
                f"{raster.name}: incorrect dtype or NoData in {name} band")


def read_bands(translate, raster):
    # Band-sequential ENVI conveys unrounded IEEE doubles and declares byte order.
    raw = raster.with_suffix(".bin")
    subprocess.run([translate, "-q", "-of", "ENVI", "-ot", "Float64", "-co", "INTERLEAVE=BSQ", raster, raw],
                   check=True)
    header = raw.with_suffix(".hdr").read_text()
    byte_order = re.search(r"^byte order\s*=\s*([01])\s*$", header, re.MULTILINE)
    require(byte_order is not None, f"{raster.name}: missing ENVI byte order")
    endian = "<" if byte_order[1] == "0" else ">"
    cells = WIDTH * HEIGHT
    data = raw.read_bytes()
    require(len(data) == len(BANDS) * cells * 8, f"{raster.name}: unexpected sample data length")
    return tuple(struct.unpack_from(f"{endian}{cells}d", data, index * cells * 8) for index in range(len(BANDS)))


def compare_values(java, pdal, case, window_size):
    maximum_error = 0.0
    filled = 0
    for index, (actual, expected) in enumerate(zip(zip(*java), zip(*pdal))):
        location = f"row {index // WIDTH}, column {index % WIDTH}"
        require(actual[3] == expected[3], f"{location}: count differs: {actual[3]} vs {expected[3]}")
        if actual[3] > 0:
            require(actual[:2] == expected[:2], f"{location}: measured extrema differ: {actual} vs {expected}")
        elif actual[2] != NODATA:
            filled += 1
        for band, a, b in zip(BANDS[:3], actual, expected):
            equal = a == b if NODATA in (a, b) else math.isclose(a, b, rel_tol=0.0, abs_tol=HEIGHT_TOLERANCE)
            require(equal, f"{location}: {band} differs: {a} (Java), {b} (PDAL)")
            maximum_error = max(maximum_error, abs(a - b))

    count_band = java[-1]
    require(sum(count_band) == case.binned, "Incorrect raster point count")
    require(sum(value > 0 for value in count_band) == case.measured, "Incorrect measured cell count")
    require(filled == (case.filled if window_size else 0), f"Incorrect filled cell count: {filled}")
    print(f"{case.example}, window {window_size or 0}: {case.binned:,} points in {case.measured:,} measured cells, "
          f"{filled:,} filled and {WIDTH * HEIGHT - case.measured - filled:,} NoData. "
          f"All {WIDTH * HEIGHT:,} cells agree; max height error {maximum_error:.3g} m.", flush=True)


def pipeline_stages(args, case):
    source = (args.data / "so-2019.laz").resolve()
    require(source.is_file(), f"Missing real-data input: {source}")
    stages = json.loads(Path(f"examples/{case.example}.json").read_text())["pipeline"]
    stages[0]["filename"] = str(source)
    return stages


def pipeline_command(args, stages, work, engine, workers, window_size):
    target, metadata, config = (work / name for name in ("grid.tif", "metadata.json", "pipeline.json"))
    writer = {**stages[-1], "filename": str(target)}
    if window_size is not None:
        writer["window_size"] = window_size
    if engine == "pdal":
        writer["type"] = "writers.gdal"
        command = [args.pdal, "pipeline", config, "--stream", f"--readers.las.threads={workers}", "--metadata", metadata]
    else:
        command = [args.cli, "pipeline", config, "--metadata", metadata, "--laz-workers", str(workers)]
    config.write_text(json.dumps({"pipeline": [*stages[:-1], writer]}))
    return list(map(str, command)), target, metadata


def read_output(geo_tools, target, metadata):
    gdalinfo, gdalsrsinfo, translate = geo_tools
    inspect(gdalinfo, gdalsrsinfo, target)
    return metadata.read_bytes(), read_bands(translate, target)


def pdal_stats(metadata):
    stages = json.loads(metadata)["stages"]
    return stages["readers.las"]["count"], stages["filters.stats"]["statistic"]


def compare_outputs(case, window_size, serial, parallel, reference):
    require(serial == parallel, "Serial and parallel Java raster values or metadata differ")
    java = json.loads(serial[0])
    points_read, stats = pdal_stats(reference[0])
    require(java["pointsRead"] == points_read == POINTS_READ, "Input read count differs")
    require((java["pointsSelected"], java["pointsBinned"], java["pointsWritten"])
            == (case.selected, case.binned, 0), f"Incorrect Java raster point counts: {java}")
    compare_stats(java["statistics"], stats, case.selected)
    compare_values(serial[1], reference[1], case, window_size)


def check_grid(args, geo_tools, case, window_size=None):
    stages = pipeline_stages(args, case)
    outputs = []
    for engine, workers in (("java", 1), ("java", 4), ("pdal", 4)):
        with tempfile.TemporaryDirectory(prefix="pointcloud-raster-") as scratch:
            command, target, metadata = pipeline_command(args, stages, Path(scratch), engine, workers, window_size)
            run(command)
            outputs.append(read_output(geo_tools, target, metadata))
    compare_outputs(case, window_size, *outputs)
    return outputs[0]


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


def benchmark(args, geo_tools):
    require(platform.system() == "Darwin", "The benchmark uses macOS /usr/bin/time -lp")
    timing_tools = runpy.run_path(str(Path(__file__).with_name("check-parallel-data.py")))
    execute, timing = timing_tools["execute"], timing_tools["timing"]
    stages = pipeline_stages(args, RECTANGLE)
    variants = [(engine, workers, window) for window in (0, 1)
                for workers in (1, 4) for engine in ("java", "pdal")]
    env = dict(os.environ, JAVA_OPTS="-Xmx512m")
    outputs = {}
    rows = []
    with tempfile.TemporaryDirectory(prefix="pointcloud-raster-") as scratch:
        work = Path(scratch)
        for engine, workers, window in variants:
            command, target, metadata = pipeline_command(
                args, stages, Path(tempfile.mkdtemp(dir=work)), engine, workers, window)
            run(command)
            outputs[engine, workers, window] = read_output(geo_tools, target, metadata)
        for window in (0, 1):
            for workers in (1, 4):
                compare_outputs(RECTANGLE, window, outputs["java", 1, window],
                                outputs["java", 4, window], outputs["pdal", workers, window])

        for index in range(ROUNDS):
            order = variants[index:] + variants[:index]
            for position, (engine, workers, window) in enumerate(order, start=1):
                with tempfile.TemporaryDirectory(dir=work) as path:
                    command, target, metadata = pipeline_command(args, stages, Path(path), engine, workers, window)
                    started = time.perf_counter_ns()
                    result = execute(["/usr/bin/time", "-lp", *command], env)
                    elapsed = (time.perf_counter_ns() - started) / 1e9
                    _, user, system, rss = timing(result.stderr)
                    actual_json, bands = read_output(geo_tools, target, metadata)
                    expected_json, expected_bands = outputs[engine, workers, window]
                    require(bands == expected_bands, "Measured run raster differs from its verified warm-up")
                    if engine == "pdal":
                        require(pdal_stats(actual_json) == pdal_stats(expected_json), "Measured run PDAL stats differ")
                    else:
                        require(actual_json == expected_json, "Measured run Java metadata differs")
                    rows.append((index + 1, position, engine, workers, window, f"{elapsed:.9f}",
                                 user, system, rss, target.stat().st_size, metadata.stat().st_size))
                    print(f"Round {index + 1}/{ROUNDS}: {engine} {workers} readers, "
                          f"window {window}, {elapsed:.4f} s", flush=True)
    with args.benchmark_csv.open("w", newline="") as file:
        writer = csv.writer(file, lineterminator="\n")
        writer.writerow(("round", "position", "engine", "reader_workers", "window_size",
                         "wall_s", "user_s", "system_s", "peak_rss_bytes", "tiff_bytes", "metadata_bytes"))
        writer.writerows(rows)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("pdal", nargs="?", default="pdal", help="PDAL 2.10.2 executable")
    parser.add_argument("--cli", type=Path, default=Path("build/install/pointcloud/bin/pointcloud"))
    parser.add_argument("--data", type=Path, default=Path(".cache/test-data"), help="directory of cached inputs")
    parser.add_argument("--gdal-bin", type=Path, help="GDAL tools directory (default: next to PDAL or on PATH)")
    parser.add_argument("--benchmark-csv", type=Path, help="run the verified macOS benchmark and write sample CSV")
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
    else:
        check_grid(args, tools, POLYGON)
        baseline = check_grid(args, tools, RECTANGLE, 0)
        filled = check_grid(args, tools, RECTANGLE, 1)
        check_filling(baseline, filled)


if __name__ == "__main__":
    main()
