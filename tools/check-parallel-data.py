"""Verify and measure the serial and parallel CLI paths on Solothurn 2014 and Autzen."""

import argparse
import contextlib
import csv
import filecmp
import io
import json
import os
from pathlib import Path
import platform
import re
import runpy
import shlex
import statistics
import struct
import subprocess
import tempfile


REFERENCE = runpy.run_path(str(Path(__file__).with_name("check-real-data.py")))
compare_las = REFERENCE["compare_las"]
compare_stats = REFERENCE["compare_stats"]
projection_and_other_vlrs = REFERENCE["projection_and_other_vlrs"]
read_las_header = REFERENCE["read_las_header"]
require = REFERENCE["require"]

DATA = Path(".cache/test-data")
VARIANTS = {"java-default": 1, "java-4": 4, "pdal-4": 4}
ROUNDS = 3
BASE_DIMENSIONS = "X,Y,Z,Intensity,Classification"


def execute(command, env):
    result = subprocess.run(command, env=env, capture_output=True, text=True)
    require(result.returncode == 0,
            f"Exit {result.returncode}: {shlex.join(command)}\n{result.stdout}{result.stderr}")
    return result


def timing(report):
    times = dict(re.findall(r"^(real|user|sys)\s+([0-9.]+)$", report, re.MULTILINE))
    rss = re.search(r"^\s*(\d+)\s+maximum resident set size$", report, re.MULTILINE)
    require(times.keys() == {"real", "user", "sys"} and rss, f"Missing /usr/bin/time metrics:\n{report}")
    return float(times["real"]), float(times["user"]), float(times["sys"]), int(rss.group(1))


def inspect_input(source, modern, expected_bytes, points):
    require(source.stat().st_size == expected_bytes, f"Unexpected size for {source}")
    with source.open("rb") as stream:
        header = read_las_header(stream)
        version, format_byte, length = (b"\x01\x04", 0x87, 36) if modern else (b"\x01\x02", 0x81, 28)
        require(header[24:26] == version and header[104] == format_byte
                and struct.unpack_from("<H", header, 105)[0] == length, f"Unexpected point format in {source}")
        count = struct.unpack_from("<Q" if modern else "<I", header, 247 if modern else 107)[0]
        require(count == points, f"Unexpected point count in {source}: {count}")
        stream.seek(0)
        while stream.read(1 << 20):
            pass
    return header


def verify(variant, target, metadata, reference_las, reference_metadata, points, selected, stats_count, modern):
    if variant == "pdal-4":
        stages = json.loads(metadata.read_text())["stages"]
        require(stages["readers.las"]["count"] == points, "PDAL input point count differs")
        compare_stats(json.loads(reference_metadata)["statistics"], stages["filters.stats"]["statistic"], stats_count)
        compare_las(reference_las, target, selected, allow_one_ulp_bounds=modern)
    else:
        require(metadata.read_bytes() == reference_metadata, f"{variant}: Java metadata differs")
        require(filecmp.cmp(target, reference_las, shallow=False), f"{variant}: complete Java LAS differs")


def check_spatial_reference(pdal, env, source, serial, native, selected, modern):
    def summary(path):
        return json.loads(execute([pdal, "info", "--summary", "--driver=readers.las", str(path)], env).stdout)["summary"]

    source_srs, java_srs, pdal_srs = (summary(path).get("srs") for path in (source, serial, native))
    require(source_srs == java_srs == pdal_srs and (source_srs is not None) == modern,
            "CRS differs between source and outputs")
    stats = json.loads(execute([pdal, "info", "--stats", str(serial)], env).stdout)["stats"]["statistic"]
    require(stats and all(field["count"] == selected for field in stats), "PDAL did not read all Java output records")
    print("PDAL reopened the Java LAS. Source and output CRS agree.", flush=True)


def check_dataset(pdal, env, work, dataset, source, expected_bytes, points, step, dimensions):
    modern = dataset == "autzen"
    source_header = inspect_input(source, modern, expected_bytes, points)
    selected = (points + step - 1) // step
    stats_count = selected if modern else points
    target, metadata = work / "out.las", work / "metadata.json"
    config = work / "pipeline.json"
    stats = {"type": "filters.stats", "dimensions": dimensions}
    decimate = {"type": "filters.decimation", "step": step}
    stages = (decimate, stats) if modern else (stats, decimate)
    config.write_text(json.dumps([
        {"type": "readers.las", "filename": str(source)},
        *stages,
        {"type": "writers.las", "filename": str(target), "forward": "all", "extra_dims": "all", "compression": False},
    ]))

    java = ["build/install/pointcloud/bin/pointcloud", "pipeline", str(config), "--metadata", str(metadata)]
    commands = {
        "java-default": java,
        "java-4": [*java, "--laz-workers", "4"],
        "pdal-4": [pdal, "pipeline", str(config), "--stream", "--readers.las.threads=4", "--metadata", str(metadata)],
    }
    print(f"{dataset}: {points:,} input points, {selected:,} expected output points", flush=True)
    execute(commands["java-default"], env)
    reference_las = target.rename(work / "reference.las")
    reference_metadata = metadata.read_bytes()
    baseline = json.loads(reference_metadata)
    require(baseline["pointsRead"] == points and baseline["pointsSelected"] == baseline["pointsWritten"] == selected,
            "Java serial input or output point count differs")
    expected_vlrs = [record for record in projection_and_other_vlrs(source) if record[0] != b"copc"]
    require(projection_and_other_vlrs(reference_las) == expected_vlrs, "Java LAS failed to retain source VLRs")
    with reference_las.open("rb") as stream:
        header = read_las_header(stream)
    require(header[24:26] == source_header[24:26] and header[6:8] == source_header[6:8]
            and header[104] == source_header[104] & 63 and header[105:107] == source_header[105:107]
            and header[131:179] == source_header[131:179], "Java LAS version, encoding, format or scale differs from source")
    if modern:
        require(header[235:247] == bytes(12), "Java LAS retained a COPC hierarchy EVLR")
    metadata.unlink()

    for variant in ("java-4", "pdal-4"):
        print("Preflight:", shlex.join(commands[variant]), flush=True)
        execute(commands[variant], env)
        verify(variant, target, metadata, reference_las, reference_metadata, points, selected, stats_count, modern)
        if variant == "pdal-4":
            check_spatial_reference(pdal, env, source, reference_las, target, selected, modern)
        else:
            print(f"{selected:,} Java point records and all output metadata are byte-identical.", flush=True)
        target.unlink()
        metadata.unlink()

    order = list(VARIANTS)
    samples = {variant: [] for variant in order}
    rows = []
    for repetition in range(1, ROUNDS + 1):
        for variant in order:
            result = execute(["/usr/bin/time", "-lp", *commands[variant]], env)
            sample = timing(result.stderr)
            with contextlib.redirect_stdout(io.StringIO()):
                verify(variant, target, metadata, reference_las, reference_metadata, points, selected, stats_count, modern)
            target.unlink()
            metadata.unlink()
            rows.append((dataset, variant, VARIANTS[variant], repetition, points, selected, *sample))
            samples[variant].append(sample)
            print(f"{dataset} {repetition}/{ROUNDS} {variant}: {sample[0]:.2f} s", flush=True)
        order = order[1:] + order[:1]

    for variant, measures in samples.items():
        wall = [elapsed for elapsed, _, _, _ in measures]
        cpu = statistics.median(user + system for _, user, system, _ in measures)
        rss = max(peak for _, _, _, peak in measures)
        print(f"{dataset} {variant}: median {statistics.median(wall):.2f} s ({min(wall):.2f}–{max(wall):.2f}), "
              f"median CPU {cpu:.2f} s, max RSS {rss / (1 << 20):.2f} MiB", flush=True)
    return rows


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("pdal", help="path to the PDAL 2.10.2 executable")
    parser.add_argument("csv", type=Path, help="destination for the eighteen measured runs")
    args = parser.parse_args()

    require(platform.system() == "Darwin", "This measurement uses macOS /usr/bin/time -lp")
    require("JAVA_HOME" in os.environ, "Set JAVA_HOME to the Java 25 installation used for the experiment")
    env = dict(os.environ, LC_ALL="C", JAVA_OPTS="-Xmx512m")
    version = execute([args.pdal, "--version"], env).stdout
    require(re.search(r"^pdal 2\.10\.2(?:\s|$)", version, re.MULTILINE), f"Expected PDAL 2.10.2:\n{version}")
    print(platform.platform(), flush=True)
    print(execute([str(Path(env["JAVA_HOME"]) / "bin/java"), "-version"], env).stderr.strip(), flush=True)
    print(next(line for line in version.splitlines() if line.startswith("pdal ")), flush=True)

    rows = []
    datasets = [
        ("so-2014", DATA / "so-2014.laz", 74_261_841, 14_347_366, 500, BASE_DIMENSIONS),
        ("autzen", DATA / "autzen-classified.copc.laz", 81_123_042, 10_653_336, 100,
         BASE_DIMENSIONS + ",Red,Green,Blue"),
    ]
    for dataset in datasets:
        with tempfile.TemporaryDirectory(prefix="parallel-check-", dir=DATA.parent) as directory:
            rows.extend(check_dataset(args.pdal, env, Path(directory), *dataset))

    args.csv.parent.mkdir(parents=True, exist_ok=True)
    with args.csv.open("w", newline="") as stream:
        writer = csv.writer(stream, lineterminator="\n")
        writer.writerow(("dataset", "variant", "workers", "iteration", "points", "selected",
                         "seconds", "user_seconds", "system_seconds", "max_rss_bytes"))
        writer.writerows(rows)
    print(f"CSV: {args.csv}")


if __name__ == "__main__":
    main()
