"""Verify and time the Riedholz raster percentiles against PDAL 2.10.2."""

import argparse
import csv
import filecmp
import json
import math
import os
from pathlib import Path
import re
import runpy
import statistics
import struct
import subprocess
import tempfile
from typing import NamedTuple


THRESHOLDS = runpy.run_path(str(Path(__file__).with_name("check-height-thresholds.py")))
AREA = THRESHOLDS["AREA"]
GRID = THRESHOLDS["GRID"]
HAG = THRESHOLDS["HAG"]
NODATA = THRESHOLDS["NODATA"]
POINTS_BINNED = THRESHOLDS["POINTS_BINNED"]
EMPTY_CELLS = THRESHOLDS["EMPTY_CELLS"]
SIZE = THRESHOLDS["SIZE"]
JAVA_ENV = THRESHOLDS["JAVA_ENV"]
require = THRESHOLDS["require"]
run = THRESHOLDS["run"]
NAMES = ("count", "p50", "p90")
TOLERANCE = 1e-12
PROFILES = {
    "full-laz": (AREA.points, 8, (("java", 1), ("pdal", 1), ("java", 4), ("pdal", 4))),
    "selected-las": (POINTS_BINNED, 5, (("java", 1), ("pdal", 1))),
}


class Output(NamedTuple):
    target: Path
    metadata: Path
    command: tuple


def write_config(config, stages):
    config.write_text(json.dumps({"pipeline": stages}, indent=2) + "\n")


def prepare_selection(args, work, stages):
    source = Path(stages[0]["filename"])
    target, metadata, config = (work / name for name in ("selected.las", "selection.json", "select-pipeline.json"))
    writer = {"type": "writers.las", "filename": str(target), "forward": "all", "extra_dims": "all"}
    write_config(config, [*stages[:2], writer])
    run([args.cli, "pipeline", config, "--laz-workers", "4", "--metadata", metadata])
    result = json.loads(metadata.read_text())
    require((result["pointsRead"], result["pointsSelected"], result["pointsWritten"])
            == (AREA.points, POINTS_BINNED, POINTS_BINNED), "Unexpected prepared LAS point counts")

    read_header = GRID["REFERENCE"]["read_las_header"]
    with source.open("rb") as original, target.open("rb") as selected:
        source_header, header = read_header(original), read_header(selected)
    offset = struct.unpack_from("<I", header, 96)[0]
    record_length, count = struct.unpack_from("<HI", header, 105)
    require(header[24:26] == source_header[24:26] == b"\x01\x02"
            and header[104] == (source_header[104] & 63)
            and header[105:107] == source_header[105:107]
            and header[131:179] == source_header[131:179]
            and count == POINTS_BINNED and target.stat().st_size == offset + count * record_length,
            "Prepared LAS must contain uncompressed original-format records with unchanged scale and offset")
    print(f"Shared cropped LAS: {count:,} records, {target.stat().st_size:,} B", flush=True)
    return [{**stages[0], "filename": str(target)}, *stages[2:]]


def prepare_run(args, stages, directory, engine, workers):
    target, metadata, config = (directory / name for name in ("grid.tif", "metadata.json", "pipeline.json"))
    inputs = [stage.copy() for stage in stages[:-1]]
    writer = {**stages[-1], "filename": str(target), "window_size": 0, "nodata": NODATA, "data_type": "double"}
    if engine == "java":
        writer["compression"] = "none"
        command = [args.cli, "pipeline", config, "--laz-workers", workers, "--metadata", metadata]
    else:
        hag = next(stage for stage in inputs if stage["type"] == "filters.hag_dem")
        hag["min_clamp"] = -1e100
        writer.pop("compression", None)
        writer.update(type="writers.gdal", gdalopts="COMPRESS=NONE")
        command = [args.pdal, "pipeline", config, "--nostream", f"--readers.las.threads={workers}",
                   "--metadata", metadata]
    write_config(config, [*inputs, writer])
    return Output(target, metadata, tuple(map(str, command)))


def verify_metadata(output, engine, points_read):
    metadata = json.loads(output.metadata.read_text())
    if engine == "java":
        require((metadata["pointsRead"], metadata["pointsSelected"], metadata["pointsBinned"],
                 metadata["pointsWritten"]) == (points_read, POINTS_BINNED, POINTS_BINNED, 0),
                f"{output.metadata}: Java point counts differ")
    else:
        require(metadata["stages"]["readers.las"]["count"] == points_read,
                f"{output.metadata}: PDAL point count differs")


def compare_samples(actual, expected, path, tolerance=TOLERANCE):
    counts, p50, p90 = actual
    require(sum(counts) == POINTS_BINNED and counts.count(0) == EMPTY_CELLS and max(counts) == 73,
            f"{path}: unexpected Riedholz echo counts or coverage")
    worst = [0.0, 0.0]
    changed = [0, 0]
    for index, (n, lo, hi, reference_n, reference_lo, reference_hi) in enumerate(zip(*actual, *expected)):
        row, col = divmod(index, SIZE[0])
        if n != reference_n or not (n >= 0 and n.is_integer()):
            raise RuntimeError(f"{path}: count at row {row}, column {col}: {n} != {reference_n}")
        if n == 0:
            if not (lo == hi == reference_lo == reference_hi == NODATA):
                raise RuntimeError(f"{path}: expected NoData at row {row}, column {col}")
            continue
        if not (math.isfinite(lo) and math.isfinite(hi) and NODATA not in (lo, hi) and lo <= hi):
            raise RuntimeError(f"{path}: invalid percentiles at row {row}, column {col}: {lo}, {hi}")
        for band, (value, reference) in enumerate(((lo, reference_lo), (hi, reference_hi))):
            difference = abs(value - reference)
            if not difference <= tolerance or reference == NODATA:
                raise RuntimeError(f"{path}: {NAMES[band + 1]} at row {row}, column {col}: {value} != {reference}")
            worst[band] = max(worst[band], difference)
            changed[band] += difference != 0
    return worst, changed


def acceptance(args, work, stages):
    outputs, values, comparisons = {}, {}, {}
    for profile, (points_read, _, variants) in PROFILES.items():
        for engine, workers in variants:
            key = (profile, engine, workers)
            directory = work / profile / f"{engine}-{workers}"
            directory.mkdir(parents=True)
            output = prepare_run(args, stages[profile], directory, engine, workers)
            run(output.command)
            verify_metadata(output, engine, points_read)
            THRESHOLDS["inspect"](args, output.target, NAMES)
            outputs[key] = output
            values[key] = THRESHOLDS["values"](args, output.target, NAMES)

    serial, parallel = (outputs["full-laz", "java", workers] for workers in (1, 4))
    require(filecmp.cmp(serial.target, parallel.target, shallow=False)
            and serial.metadata.read_bytes() == parallel.metadata.read_bytes(),
            "Java GeoTIFF or metadata differs between one and four workers")
    print("Java full LAZ GeoTIFF and JSON metadata are byte-identical with one and four workers.", flush=True)

    reference = values["full-laz", "pdal", 4]
    for key, samples in values.items():
        worst, changed = compare_samples(samples, reference, outputs[key].target)
        comparisons["/".join(map(str, key))] = {"max_abs_difference_m": dict(zip(NAMES[1:], worst)),
                                                "different_cells": dict(zip(NAMES[1:], changed))}
        print(f"{' '.join(map(str, key))}: all 40,000 counts and NoData masks match PDAL, "
              f"max abs difference p50 {worst[0]:.3g} m, p90 {worst[1]:.3g} m", flush=True)
    (work / "acceptance.json").write_text(json.dumps(comparisons, indent=2) + "\n")
    return values


def benchmark(args, work, stages, accepted):
    execute, timing = GRID["timing_functions"]()
    measure = GRID["measure"]
    print(f"System before timed runs: {os.cpu_count()} logical CPUs, load averages {os.getloadavg()}", flush=True)
    times = {variant: [] for variant in accepted}
    args.benchmark_csv.parent.mkdir(parents=True, exist_ok=True)
    with args.benchmark_csv.open("w", newline="") as file:
        writer = csv.writer(file, lineterminator="\n")
        writer.writerow(("profile", "round", "position", "engine", "reader_workers", "points_read", "wall_s",
                         "user_s", "system_s", "peak_rss_bytes", "points_per_s", "tiff_bytes", "metadata_bytes",
                         "loadavg_1m_before", "loadavg_1m_after"))
        for profile, (points_read, rounds, variants) in PROFILES.items():
            inputs = stages[profile]
            for path in (Path(inputs[0]["filename"]), Path(inputs[-2]["raster"])):
                with path.open("rb") as stream:
                    while stream.read(1 << 20):
                        pass
            for index in range(rounds):
                start = index % len(variants)
                order = variants[start:] + variants[:start]
                for position, (engine, workers) in enumerate(order, start=1):
                    key = (profile, engine, workers)
                    with tempfile.TemporaryDirectory(dir=work) as directory:
                        output = prepare_run(args, inputs, Path(directory), engine, workers)
                        load_before = os.getloadavg()[0]
                        wall, user, system, rss = measure(output.command, execute, timing, JAVA_ENV)
                        load_after = os.getloadavg()[0]
                        verify_metadata(output, engine, points_read)
                        values = THRESHOLDS["values"](args, output.target, NAMES)
                        compare_samples(values, accepted[key], output.target, tolerance=0.0)
                        writer.writerow((profile, index + 1, position, engine, workers, points_read, f"{wall:.9f}",
                                         user, system, rss, f"{points_read / wall:.1f}", output.target.stat().st_size,
                                         output.metadata.stat().st_size, f"{load_before:.3f}", f"{load_after:.3f}"))
                        file.flush()
                        times[key].append(wall)
                        print(f"{profile} round {index + 1}/{rounds}: {engine} {workers} readers, {wall:.4f} s "
                              f"(1m load {load_before:.2f} -> {load_after:.2f})", flush=True)
    for key, walls in times.items():
        print(f"{' '.join(map(str, key))}: median {statistics.median(walls):.4f} s, "
              f"range {min(walls):.4f}..{max(walls):.4f} s", flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("pdal", type=Path, help="PDAL 2.10.2 executable")
    parser.add_argument("--cli", type=Path, default=Path("build/install/pointcloud/bin/pointcloud"))
    parser.add_argument("--gdal", type=Path, help="GDAL tool directory (default: alongside PDAL)")
    parser.add_argument("--data", type=Path, default=Path(".cache/test-data"))
    parser.add_argument("--dem-dir", type=Path, default=Path(".cache/height-thresholds/dem"))
    parser.add_argument("--work", type=Path, default=Path(".cache/raster-percentiles"))
    parser.add_argument("--benchmark-csv", type=Path, help="also run 32 full-LAZ and 10 cropped-LAS timed runs")
    args = parser.parse_args()
    args.pdal, args.cli, args.data, args.dem_dir, args.work = (
        path.resolve() for path in (args.pdal, args.cli, args.data, args.dem_dir, args.work))
    args.gdal = args.gdal.resolve() if args.gdal else args.pdal.parent
    version = subprocess.check_output([args.pdal, "--version"], text=True)
    require(re.search(r"^pdal 2\.10\.2(?:\s|$)", version, re.MULTILINE), f"Expected PDAL 2.10.2: {version}")
    source = args.data / AREA.laz
    require(source.is_file() and source.stat().st_size == 30_303_122, f"Missing or incomplete LAZ: {source}")
    args.work.mkdir(parents=True, exist_ok=True)
    dem = HAG["prepare_dem"](args, AREA, args.work)

    example = Path(__file__).resolve().parents[1] / "examples/raster-percentiles-riedholz.json"
    inputs = json.loads(example.read_text())["pipeline"]
    inputs[0]["filename"] = str(source)
    next(stage for stage in inputs if stage["type"] == "filters.hag_dem")["raster"] = str(dem)
    work = Path(tempfile.mkdtemp(prefix="run-", dir=args.work))
    print(f"Riedholz percentile outputs: {work}", flush=True)
    stages = {"full-laz": inputs, "selected-las": prepare_selection(args, work, inputs)}
    accepted = acceptance(args, work, stages)
    if args.benchmark_csv:
        benchmark(args, work, stages, accepted)


if __name__ == "__main__":
    main()
