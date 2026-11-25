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
import shutil
import statistics
import struct
import subprocess
import tempfile
import time


REFERENCE = runpy.run_path(str(Path(__file__).with_name("check-real-data.py")))
compare_las = REFERENCE["compare_las"]
compare_stats = REFERENCE["compare_stats"]
projection_and_other_vlrs = REFERENCE["projection_and_other_vlrs"]
read_las_header = REFERENCE["read_las_header"]
require = REFERENCE["require"]

DATA = Path(".cache/test-data")
VARIANTS = {"java-default": 1, "java-4": 4, "pdal-4": 4}
ROUNDS = 3
AB_ROUNDS = 7
READER_ROUNDS = 3
PROFILE_ROUNDS = 5
DEFAULT_CLI = "build/install/pointcloud/bin/pointcloud"
JFR_SETTINGS = (
    "settings=profile,dumponexit=true,jdk.ExecutionSample#period=2ms,"
    "jdk.ThreadCPULoad#period=100ms,jdk.ThreadPark#threshold=100us"
)
BASE_DIMENSIONS = "X,Y,Z,Intensity,Classification"
MODERN_DIMENSIONS = BASE_DIMENSIONS + ",Red,Green,Blue"
RUNTIME_VARIANTS = ("temurin", "native-serial", "native-serial-pgo", "native-g1", "native-g1-pgo")


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
    if expected_bytes is not None:
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


def profile_dataset(env, work, dataset, commands, verify_output, profile_dir):
    recordings = []
    # Keep the JFR filename free of commas and shell quoting. The destination
    # directory supplied by the caller is only used after the recording closes.
    recording = Path(os.path.relpath(work / "profile.jfr"))
    java_opts = env["JAVA_OPTS"]
    settings = f"-XX:StartFlightRecording=filename={recording},{JFR_SETTINGS}"
    profile_env = dict(env, JAVA_OPTS=f"{java_opts} -Xlog:jfr+startup=off {settings}")
    runs = [("java-default", 1)] + [("java-4", iteration) for iteration in range(1, PROFILE_ROUNDS + 1)]

    for variant, iteration in runs:
        execute(commands[variant], profile_env)
        verify_output(variant)
        require(recording.is_file(), f"Missing JFR recording for {dataset} {variant} #{iteration}")
        destination = profile_dir / f"{dataset}-{variant}-{iteration}.jfr"
        shutil.move(recording, destination)
        recordings.append((dataset, variant, iteration, destination))
        print(f"{dataset} {variant} #{iteration}: verified LAS and JSON, JFR: {destination}", flush=True)

    return recordings


def report_samples(dataset, variant, measures, wall_precision=2):
    wall = [elapsed for elapsed, _, _, _ in measures]
    cpu = statistics.median(user + system for _, user, system, _ in measures)
    rss = max(peak for _, _, _, peak in measures)
    print(f"{dataset} {variant}: median {statistics.median(wall):.{wall_precision}f} s "
          f"({min(wall):.{wall_precision}f}–{max(wall):.{wall_precision}f}), "
          f"median CPU {cpu:.2f} s, max RSS {rss / (1 << 20):.2f} MiB", flush=True)


def measure_ab(env, dataset, commands, verify_output, points, selected, rounds=AB_ROUNDS):
    rows = []
    samples = {workers: {variant: [] for variant in variants} for workers, variants in commands.items()}
    worker_counts = tuple(commands)
    for iteration in range(1, rounds + 1):
        worker_order = worker_counts if iteration % 2 else worker_counts[::-1]
        variant_order = ("baseline", "candidate") if iteration % 2 else ("candidate", "baseline")
        for workers in worker_order:
            for variant in variant_order:
                result = execute(["/usr/bin/time", "-lp", *commands[workers][variant]], env)
                sample = timing(result.stderr)
                verify_output(variant)
                rows.append((dataset, variant, workers, iteration, points, selected, *sample))
                samples[workers][variant].append(sample)
                print(f"{dataset} {workers} workers {iteration}/{rounds} {variant}: {sample[0]:.2f} s", flush=True)

    for workers, variants in samples.items():
        for variant, measures in variants.items():
            report_samples(dataset, f"{workers} workers {variant}", measures)
        pairs = list(zip(variants["baseline"], variants["candidate"]))
        wall = statistics.median(candidate[0] / baseline[0] for baseline, candidate in pairs)
        cpu = statistics.median((candidate[1] + candidate[2]) / (baseline[1] + baseline[2])
                                for baseline, candidate in pairs)
        rss = statistics.median(candidate[3] / baseline[3] for baseline, candidate in pairs)
        wins = sum(candidate[0] < baseline[0] for baseline, candidate in pairs)
        print(f"{dataset} {workers} workers: median paired candidate/baseline ratios "
              f"wall {wall:.3f}x, CPU {cpu:.3f}x, RSS {rss:.3f}x. "
              f"Candidate wall-time wins {wins}/{len(pairs)}.", flush=True)
    return rows


def runtime_commands(runtimes, config, metadata, worker_counts):
    return {
        workers: {
            variant: [str(cli), "pipeline", str(config), "--metadata", str(metadata), "--laz-workers", str(workers)]
            for variant, cli in runtimes.items()
        }
        for workers in worker_counts
    }


def measure_runtimes(env, dataset, commands, verify_output, points, selected):
    rows = []
    worker_counts = tuple(commands)
    variants = tuple(commands[worker_counts[0]])
    samples = {workers: {variant: [] for variant in variants} for workers in worker_counts}
    for index in range(AB_ROUNDS):
        worker_order = worker_counts if index % 2 == 0 else worker_counts[::-1]
        variant_order = variants if index < len(variants) else variants[::-1]
        offset = index % len(variants)
        variant_order = variant_order[offset:] + variant_order[:offset]
        for workers in worker_order:
            for variant in variant_order:
                command = ["/usr/bin/time", "-lp", *commands[workers][variant]]
                started = time.perf_counter_ns()
                result = execute(command, env)
                seconds = (time.perf_counter_ns() - started) / 1_000_000_000
                _, user, system, rss = timing(result.stderr)
                verify_output(variant)
                samples[workers][variant].append((seconds, user, system, rss))
                rows.append((dataset, variant, workers, index + 1, points, selected,
                             f"{seconds:.9f}", user, system, rss))
                print(f"{dataset} {workers} workers {index + 1}/{AB_ROUNDS} {variant}: {seconds:.6f} s", flush=True)
    for workers, by_runtime in samples.items():
        for variant, measures in by_runtime.items():
            report_samples(dataset, f"{workers} workers {variant}", measures, wall_precision=6)
    return rows


def check_runtime_mini(pdal, env, work, runtimes):
    source = Path("src/test/resources/las/format-3.laz")
    with source.open("rb") as stream:
        header = read_las_header(stream)
    points = 5
    require(header[104] == 0x83 and struct.unpack_from("<HI", header, 105) == (34, points),
            "Unexpected point layout for the five-point LAZ fixture")
    config, metadata = work / "pipeline.json", work / "metadata.json"
    config.write_text(json.dumps([
        {"type": "readers.las", "filename": str(source)},
        {"type": "filters.stats", "dimensions": BASE_DIMENSIONS},
        {"type": "writers.null"},
    ]))
    commands = runtime_commands(runtimes, config, metadata, (1,))
    execute(commands[1]["temurin"], env)
    reference = metadata.read_bytes()
    counts = json.loads(reference)
    require((counts["pointsRead"], counts["pointsSelected"], counts["pointsWritten"]) == (points, points, 0),
            "Unexpected mini pipeline counts")
    metadata.unlink()

    def verify_output(variant):
        require(metadata.read_bytes() == reference, f"mini {variant}: Java metadata differs")
        metadata.unlink()

    for variant, command in commands[1].items():
        if variant != "temurin":
            execute(command, env)
            verify_output(variant)
    pdal_command = [pdal, "pipeline", str(config), "--stream", "--readers.las.threads=4", "--metadata", str(metadata)]
    print("Mini PDAL preflight:", shlex.join(pdal_command), flush=True)
    execute(pdal_command, env)
    stages = json.loads(metadata.read_text())["stages"]
    require(stages["readers.las"]["count"] == points, "PDAL mini input point count differs")
    compare_stats(counts["statistics"], stages["filters.stats"]["statistic"], points)
    metadata.unlink()
    return measure_runtimes(env, "mini", commands, verify_output, points, points)


def check_reader_ab(env, work, dataset, source, expected_bytes, points, ab):
    inspect_input(source, True, expected_bytes, points)
    config, metadata = work / "pipeline.json", work / "metadata.json"
    config.write_text(json.dumps([
        {"type": "readers.las", "filename": str(source)},
        {"type": "writers.null"},
    ]))
    commands = {variant: [str(cli), "pipeline", str(config), "--laz-workers", "4", "--metadata", str(metadata)]
                for variant, cli in zip(("baseline", "candidate"), ab)}
    execute(commands["baseline"], env)
    reference = metadata.read_bytes()
    require(json.loads(reference) == {"pointsRead": points, "pointsSelected": points, "pointsWritten": 0,
                                     "statistics": []}, "Unexpected reader-only baseline metadata")
    metadata.unlink()

    def verify_output(variant):
        require(metadata.read_bytes() == reference, f"{variant}: reader-only metadata differs")
        metadata.unlink()

    execute(commands["candidate"], env)
    verify_output("candidate")
    return measure_ab(env, dataset, {4: commands}, verify_output, points, points, READER_ROUNDS)


def check_dataset(pdal, env, work, dataset, source, expected_bytes, points, step, dimensions, modern, profile_dir,
                  ab=None, cli=DEFAULT_CLI, runtimes=None):
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

    def java_command(cli):
        return [str(cli), "pipeline", str(config), "--metadata", str(metadata)]

    pdal_command = [pdal, "pipeline", str(config), "--stream", "--readers.las.threads=4", "--metadata", str(metadata)]
    if runtimes is not None:
        runtime_jobs = runtime_commands(runtimes, config, metadata, (1, 4))
        reference_command = runtime_jobs[1]["temurin"]
        preflight = [(f"{variant}-{workers}", command)
                     for workers, jobs in runtime_jobs.items() for variant, command in jobs.items()
                     if (workers, variant) != (1, "temurin")]
    elif ab is None:
        java = java_command(cli)
        commands = {"java-default": java, "java-4": [*java, "--laz-workers", "4"], "pdal-4": pdal_command}
        reference_command = commands["java-default"]
        preflight = [("java-4", commands["java-4"])]
    else:
        ab_commands = {
            workers: {variant: [*java_command(cli), "--laz-workers", str(workers)]
                      for variant, cli in zip(("baseline", "candidate"), ab)}
            for workers in (1, 4)
        }
        reference_command = ab_commands[1]["baseline"]
        preflight = [("baseline-4", ab_commands[4]["baseline"]),
                     ("candidate-1", ab_commands[1]["candidate"]), ("candidate-4", ab_commands[4]["candidate"])]
    preflight.append(("pdal-4", pdal_command))
    print(f"{dataset}: {points:,} input points, {selected:,} expected output points", flush=True)
    execute(reference_command, env)
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

    for variant, command in preflight:
        print("Preflight:", shlex.join(command), flush=True)
        execute(command, env)
        verify(variant, target, metadata, reference_las, reference_metadata, points, selected, stats_count, modern)
        if variant == "pdal-4":
            check_spatial_reference(pdal, env, source, reference_las, target, selected, modern)
        else:
            print(f"{selected:,} Java point records and all output metadata are byte-identical.", flush=True)
        target.unlink()
        metadata.unlink()

    def verify_output(variant):
        with contextlib.redirect_stdout(io.StringIO()):
            verify(variant, target, metadata, reference_las, reference_metadata, points, selected, stats_count, modern)
        target.unlink()
        metadata.unlink()

    if profile_dir is not None:
        return profile_dataset(env, work, dataset, commands, verify_output, profile_dir)
    if runtimes is not None:
        return measure_runtimes(env, dataset, runtime_jobs, verify_output, points, selected)
    if ab is not None:
        return measure_ab(env, dataset, ab_commands, verify_output, points, selected)

    order = list(VARIANTS)
    samples = {variant: [] for variant in order}
    rows = []
    for repetition in range(1, ROUNDS + 1):
        for variant in order:
            result = execute(["/usr/bin/time", "-lp", *commands[variant]], env)
            sample = timing(result.stderr)
            verify_output(variant)
            rows.append((dataset, variant, VARIANTS[variant], repetition, points, selected, *sample))
            samples[variant].append(sample)
            print(f"{dataset} {repetition}/{ROUNDS} {variant}: {sample[0]:.2f} s", flush=True)
        order = order[1:] + order[:1]

    for variant, measures in samples.items():
        report_samples(dataset, variant, measures)
    return rows


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("pdal", help="path to the PDAL 2.10.2 executable")
    output = parser.add_mutually_exclusive_group(required=True)
    output.add_argument("csv", nargs="?", type=Path, help="CSV destination for unprofiled runs")
    output.add_argument("--profile-dir", type=Path, help="empty destination for verified JFR recordings and summary")
    comparison = parser.add_mutually_exclusive_group()
    comparison.add_argument("--ab", nargs=2, type=Path, metavar=("BASELINE_CLI", "CANDIDATE_CLI"),
                            help="compare two installed CLIs in unprofiled A/B runs")
    comparison.add_argument("--runtimes", nargs="+", metavar="NAME=PATH",
                            help="compare temurin, native-serial, native-serial-pgo, and optionally "
                                 "native-g1 and native-g1-pgo")
    parser.add_argument("--modern-holdout", type=Path, help="with --runtimes, also measure the Autzen holdout COPC")
    parser.add_argument("--reader-null", action="store_true",
                        help="with --ab, use three pairs on Autzen with four workers and writers.null")
    parser.add_argument("--profile-cli", type=Path, help="with --profile-dir, profile this installed CLI")
    args = parser.parse_args()
    if args.reader_null and args.ab is None:
        parser.error("--reader-null requires --ab")
    if args.profile_cli is not None and args.profile_dir is None:
        parser.error("--profile-cli requires --profile-dir")
    if args.modern_holdout is not None and args.runtimes is None:
        parser.error("--modern-holdout requires --runtimes")
    if args.ab is not None:
        if args.csv is None:
            parser.error("--ab requires a CSV destination")
        args.ab = tuple(path.resolve() for path in args.ab)
        for executable in args.ab:
            require(executable.is_file() and os.access(executable, os.X_OK), f"Missing executable CLI: {executable}")
        print(f"Baseline CLI: {args.ab[0]}\nCandidate CLI: {args.ab[1]}", flush=True)
    runtimes = None
    if args.runtimes is not None:
        if args.csv is None:
            parser.error("--runtimes requires a CSV destination")
        paths = {}
        for entry in args.runtimes:
            variant, separator, path = entry.partition("=")
            if not separator or not path or variant not in RUNTIME_VARIANTS or variant in paths:
                parser.error(f"Expected a distinct runtime NAME=PATH, got {entry!r}")
            executable = Path(path).resolve()
            if not executable.is_file() or not os.access(executable, os.X_OK):
                parser.error(f"Missing executable CLI for {variant}: {executable}")
            paths[variant] = executable
        if paths.keys() not in (set(RUNTIME_VARIANTS[:3]), set(RUNTIME_VARIANTS)):
            parser.error("--runtimes requires temurin, native-serial, native-serial-pgo "
                         "and either both G1 variants or neither")
        runtimes = {variant: paths[variant] for variant in RUNTIME_VARIANTS if variant in paths}
        for variant, executable in runtimes.items():
            print(f"{variant}: {executable}", flush=True)

    require(platform.system() == "Darwin", "This measurement uses macOS /usr/bin/time -lp")
    require("JAVA_HOME" in os.environ, "Set JAVA_HOME to the Java 25 installation used for the experiment")
    env = dict(os.environ, LC_ALL="C", JAVA_OPTS="-Xmx512m")
    version = execute([args.pdal, "--version"], env).stdout
    require(re.search(r"^pdal 2\.10\.2(?:\s|$)", version, re.MULTILINE), f"Expected PDAL 2.10.2:\n{version}")
    java_bin = Path(env["JAVA_HOME"]) / "bin"
    system = platform.platform()
    java_version = execute([str(java_bin / "java"), "-version"], env).stderr.strip()
    pdal_version = next(line for line in version.splitlines() if line.startswith("pdal "))
    print(system, java_version, pdal_version, sep="\n", flush=True)

    rows = []
    datasets = [
        ("so-2014", DATA / "so-2014.laz", 74_261_841, 14_347_366, 500, BASE_DIMENSIONS, False),
        ("autzen", DATA / "autzen-classified.copc.laz", 81_123_042, 10_653_336, 100, MODERN_DIMENSIONS, True),
    ]
    if args.modern_holdout is not None:
        source = args.modern_holdout.resolve()
        require(source.is_file(), f"Missing modern holdout: {source}")
        print(f"Autzen holdout: {source} ({source.stat().st_size:,} bytes)", flush=True)
        datasets.append(("autzen-holdout", source, None, 8_653_336, 100, MODERN_DIMENSIONS, True))
    if args.profile_dir is not None:
        jfr = java_bin / "jfr"
        require(os.access(jfr, os.X_OK), f"Missing executable JFR tool: {jfr}")
        args.profile_dir.mkdir(parents=True, exist_ok=True)
        require(not any(args.profile_dir.iterdir()), f"Choose an empty JFR output directory: {args.profile_dir}")
    cli = args.profile_cli.resolve() if args.profile_cli is not None else DEFAULT_CLI
    if args.profile_cli is not None:
        require(cli.is_file() and os.access(cli, os.X_OK), f"Missing executable CLI: {cli}")
        print(f"Profile CLI: {cli}", flush=True)
    if args.reader_null:
        dataset, source, size, points, *_ = datasets[-1]
        with tempfile.TemporaryDirectory(prefix="parallel-check-", dir=DATA.parent) as directory:
            rows = check_reader_ab(env, Path(directory), dataset, source, size, points, args.ab)
    else:
        if runtimes is not None:
            with tempfile.TemporaryDirectory(prefix="parallel-check-", dir=DATA.parent) as directory:
                rows.extend(check_runtime_mini(args.pdal, env, Path(directory), runtimes))
        for dataset in datasets:
            with tempfile.TemporaryDirectory(prefix="parallel-check-", dir=DATA.parent) as directory:
                rows.extend(check_dataset(args.pdal, env, Path(directory), *dataset, args.profile_dir, args.ab, cli,
                                          runtimes))

    if args.profile_dir is not None:
        from parallel_jfr import analyze

        summaries = []
        for dataset, variant, iteration, recording in rows:
            summary = analyze(jfr, recording, VARIANTS[variant])
            summaries.append(dict(dataset=dataset, variant=variant, iteration=iteration, **summary))
        report = args.profile_dir / "summary.json"
        report.write_text(json.dumps({
            "platform": system, "java": java_version, "pdal": pdal_version,
            "cli": str(cli), "java_opts": env["JAVA_OPTS"], "jfr_settings": JFR_SETTINGS, "runs": summaries,
        }, indent=2) + "\n", encoding="utf-8")
        print(f"JFR summary: {report}")
        return

    args.csv.parent.mkdir(parents=True, exist_ok=True)
    with args.csv.open("w", newline="") as stream:
        writer = csv.writer(stream, lineterminator="\n")
        writer.writerow(("dataset", "variant", "workers", "iteration", "points", "selected",
                         "seconds", "user_seconds", "system_seconds", "max_rss_bytes"))
        writer.writerows(rows)
    print(f"CSV: {args.csv}")


if __name__ == "__main__":
    main()
