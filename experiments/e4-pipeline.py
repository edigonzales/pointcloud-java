"""Compare four complete Ground-Crop pipelines on macOS from the repository root."""

import argparse
import contextlib
import csv
import io
import json
import os
from pathlib import Path
import platform
import re
import runpy
import shlex
import statistics
import subprocess
import sys
import tempfile


POINTS = 14_566_627
SELECTED = 195_738
ROUNDS = 5
VARIANTS = {"serial": 1, "per-chunk": 4, "reused": 4, "pdal-4": 4}

reference = runpy.run_path(str(Path(__file__).resolve().parents[1] / "tools/check-real-data.py"))
compare_las = reference["compare_las"]
compare_stats = reference["compare_stats"]
require = reference["require"]


def run(command, env):
    result = subprocess.run(command, env=env, capture_output=True, text=True)
    require(result.returncode == 0,
            f"Exit {result.returncode}: {shlex.join(command)}\n{result.stdout}{result.stderr}")
    return result


def timing(report):
    times = dict(re.findall(r"^(real|user|sys)\s+([0-9.]+)$", report, re.MULTILINE))
    rss = re.search(r"^\s*(\d+)\s+maximum resident set size$", report, re.MULTILINE)
    require(times.keys() == {"real", "user", "sys"} and rss, f"Missing /usr/bin/time metrics:\n{report}")
    return float(times["real"]), float(times["user"]), float(times["sys"]), int(rss.group(1))


def verify(variant, reference_las, reference_metadata, target, metadata):
    if variant == "pdal-4":
        stages = json.loads(metadata.read_text())["stages"]
        require(stages["readers.las"]["count"] == POINTS, "PDAL input point count differs")
        compare_stats(json.loads(reference_metadata)["statistics"], stages["filters.stats"]["statistic"], SELECTED)
    else:
        require(metadata.read_bytes() == reference_metadata, f"{variant}: Java metadata differs")
    compare_las(reference_las, target, SELECTED)
    target.unlink()
    metadata.unlink()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("pdal", help="path to the PDAL 2.10.2 executable")
    parser.add_argument("csv", type=Path, help="destination for the twenty measured runs")
    args = parser.parse_args()

    require(platform.system() == "Darwin", "This experiment uses macOS /usr/bin/time -lp")
    require("JAVA_HOME" in os.environ, "Set JAVA_HOME to the Java 25 installation used for the experiment")
    java = str(Path(os.environ["JAVA_HOME"]) / "bin/java")
    env = dict(os.environ, LC_ALL="C")
    pdal_version = run([args.pdal, "--version"], env).stdout
    require(re.search(r"^pdal 2\.10\.2(?:\s|$)", pdal_version, re.MULTILINE),
            f"Expected PDAL 2.10.2:\n{pdal_version}")
    print(platform.platform())
    print(run([java, "-version"], env).stderr.strip())
    print(next(line for line in pdal_version.splitlines() if line.startswith("pdal ")))

    exports = [f"{key}={env[key]}" for key in ("JAVA_HOME", "PROJ_DATA") if key in env]
    print("Reproduce from repository root:", shlex.join(["env", *exports, "python3", *sys.argv]))

    pipeline = json.loads(Path("examples/ground-crop.json").read_text())
    source = Path(pipeline["pipeline"][0]["filename"])
    with source.open("rb") as stream:
        while stream.read(1 << 20):
            pass

    cache = Path(".cache/experiments")
    cache.mkdir(parents=True, exist_ok=True)
    samples = {variant: [] for variant in VARIANTS}
    rows = []
    with tempfile.TemporaryDirectory(prefix="e4-pipeline-", dir=cache) as directory:
        work = Path(directory)
        target = work / "ground-crop.las"
        metadata = work / "metadata.json"
        config = work / "pipeline.json"
        pipeline["pipeline"][-1]["filename"] = str(target)
        config.write_text(json.dumps(pipeline))

        classpath = "experiments/build/install/experiments/lib/*"
        entry = [java, "-Xmx512m", "-cp", classpath, "pointcloud.ChunkParallel", "pipeline"]
        commands = {variant: [*entry, variant, str(config), str(metadata)]
                    for variant in ("serial", "per-chunk", "reused")}
        commands["pdal-4"] = [args.pdal, "pipeline", str(config), "--stream", "--readers.las.threads=4",
                              "--metadata", str(metadata)]

        print("Serial reference:", shlex.join(commands["serial"]), flush=True)
        run(commands["serial"], env)
        reference_las = target.rename(work / "reference.las")
        reference_metadata = metadata.read_bytes()
        baseline = json.loads(reference_metadata)
        require(baseline["pointsRead"] == POINTS and baseline["pointsSelected"] == SELECTED
                and baseline["pointsWritten"] == SELECTED, "Unexpected Java reference counts")
        metadata.unlink()

        for variant in ("per-chunk", "reused", "pdal-4"):
            print("Preflight:", shlex.join(commands[variant]), flush=True)
            run(commands[variant], env)
            verify(variant, reference_las, reference_metadata, target, metadata)

        order = list(VARIANTS)
        for iteration in range(1, ROUNDS + 1):
            for variant in order:
                result = run(["/usr/bin/time", "-lp", *commands[variant]], env)
                metrics = timing(result.stderr)
                with contextlib.redirect_stdout(io.StringIO()):
                    verify(variant, reference_las, reference_metadata, target, metadata)
                rows.append((variant, VARIANTS[variant], iteration, POINTS, *metrics))
                samples[variant].append(metrics)
                print(f"{iteration}/{ROUNDS} {variant}: {metrics[0]:.2f} s", flush=True)
            order = order[1:] + order[:1]

    args.csv.parent.mkdir(parents=True, exist_ok=True)
    with args.csv.open("w", newline="") as stream:
        writer = csv.writer(stream, lineterminator="\n")
        writer.writerow(("variant", "workers", "iteration", "points", "seconds",
                         "user_seconds", "system_seconds", "max_rss_bytes"))
        writer.writerows(rows)

    for variant, runs in samples.items():
        times = [elapsed for elapsed, _, _, _ in runs]
        cpu = statistics.median(user + system for _, user, system, _ in runs)
        peak_rss = max(rss for _, _, _, rss in runs)
        print(f"{variant}: median {statistics.median(times):.2f} s ({min(times):.2f}–{max(times):.2f}), "
              f"median CPU {cpu:.2f} s, max RSS {peak_rss / (1 << 20):.2f} MiB")
    print(f"CSV: {args.csv}")


if __name__ == "__main__":
    main()
