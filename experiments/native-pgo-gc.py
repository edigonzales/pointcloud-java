"""Collect GC pause summaries separately from the Native Image benchmark."""

import argparse
import csv
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile


NATIVE_IMAGES = ("native-serial", "native-serial-pgo", "native-g1", "native-g1-pgo")
GC_ID = re.compile(r"\bGC\((\d+)\)")
PAUSE = re.compile(r"\bGC\((\d+)\)\s+Pause\b.*?\s(\d+(?:\.\d+)?)\s*(ns|us|µs|ms|s)\s*$")
MILLISECONDS = {"ns": 1e-6, "us": 1e-3, "µs": 1e-3, "ms": 1, "s": 1000}
POINTS = 8_653_336
SELECTED = (POINTS + 99) // 100


def pause_metrics(log):
    text = log.read_text()
    events = [match for line in text.splitlines() if (match := PAUSE.search(line))]
    durations = [float(match[2]) * MILLISECONDS[match[3]] for match in events]
    return (len(set(GC_ID.findall(text))), len({match[1] for match in events}), len(durations),
            sum(durations), max(durations, default=0))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("experiment_dir", type=Path, help="contains the holdout COPC and the Native Image binaries")
    args = parser.parse_args()
    experiment = args.experiment_dir.resolve()
    out = experiment / "gc"
    out.mkdir(exist_ok=False)

    classpath = os.pathsep.join(str(jar.resolve()) for jar in sorted(Path("build/install/pointcloud/lib").glob("*.jar")))
    java = Path(os.environ["JAVA_HOME"]) / "bin/java"
    commands = {"temurin": [java, "-Xmx512m", "-XX:+UseG1GC", "-Xlog:gc", "-cp", classpath, "pointcloud.Main"]}
    for name in NATIVE_IMAGES:
        binary = experiment / name
        if binary.is_file():
            commands[name] = [binary, "-Xmx512m", "-XX:+PrintGC"]
    if not {"native-serial", "native-serial-pgo"} <= commands.keys():
        raise RuntimeError("Expected both Native Serial builds")

    rows = []
    with tempfile.TemporaryDirectory(prefix="gc-pipeline-", dir=experiment) as scratch:
        work = Path(scratch)
        target, metadata = work / "holdout.las", work / "metadata.json"
        pipeline = work / "pipeline.json"
        pipeline.write_text(json.dumps([
            {"type": "readers.las", "filename": str(experiment / "holdout-autzen.copc.laz")},
            {"type": "filters.decimation", "step": 100},
            {"type": "filters.stats", "dimensions": "X,Y,Z,Intensity,Classification,Red,Green,Blue"},
            {"type": "writers.las", "filename": str(target), "forward": "all",
             "compression": False, "extra_dims": "all"},
        ]), encoding="utf-8")
        for iteration in range(1, 4):
            for name, command in commands.items():
                log = out / f"{name}-{iteration}.log"
                with log.open("w", encoding="utf-8") as stream:
                    subprocess.run([*command, "pipeline", pipeline, "--metadata", metadata, "--laz-workers", "4"],
                                   stdout=stream, stderr=subprocess.STDOUT, check=True)
                stats = json.loads(metadata.read_text())
                if (stats["pointsRead"], stats["pointsSelected"], stats["pointsWritten"]) != (POINTS, SELECTED, SELECTED):
                    raise RuntimeError(f"Incorrect point counts during {name} GC run {iteration}")
                target.unlink()
                metadata.unlink()
                ids, stopped, pauses, total, maximum = pause_metrics(log)
                rows.append((name, iteration, ids, stopped, pauses, f"{total:.6f}", f"{maximum:.6f}"))
                print(f"{name} {iteration}: {ids} GC IDs, {pauses} pauses, {total:.3f} ms total, "
                      f"{maximum:.3f} ms max", flush=True)
    with (out / "pauses.csv").open("w", newline="", encoding="utf-8") as stream:
        writer = csv.writer(stream, lineterminator="\n")
        writer.writerow(("variant", "iteration", "gc_ids", "gc_ids_with_pauses", "pauses",
                         "total_pause_ms", "max_pause_ms"))
        writer.writerows(rows)


if __name__ == "__main__":
    main()
