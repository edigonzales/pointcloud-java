"""Measure the short pipeline and whole tile on the four verified run paths."""

import csv
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import time


def main():
    work = Path(".cache/experiments/p9")
    work.mkdir(parents=True, exist_ok=True)
    product_cp = os.pathsep.join(str(p) for p in sorted(Path("build/install/pointcloud/lib").glob("*.jar")))
    java25 = str(Path(os.environ["JAVA_HOME"], "bin", "java"))
    java27 = sys.argv[1]
    entry = ["-cp", product_cp, "pointcloud.Main"]
    runtimes = {
        "temurin25": [java25, "-Xmx512m", *entry],
        "temurin27": [java27, "-Xmx512m", *entry],
        "aot25": [java25, "-Xmx512m", "-XX:AOTMode=on", f"-XX:AOTCache={work / 'ground.aot'}", *entry],
        "native": [str(work / "pointcloud-native"), "-Xmx512m"],
    }
    short = work / "mini.json"
    stages = json.loads(Path("examples/stats.json").read_text())
    stages[0] = "src/test/resources/las/format-3.laz"
    short.write_text(json.dumps(stages))
    ground = Path("build/results/ground-crop.las")
    if os.path.lexists(ground):
        raise RuntimeError(f"Comparison target already exists: {ground}")
    reference = {"ground": json.loads(Path(".cache/experiments/e1-baseline.json").read_text())}
    with (work / "runtimes.csv").open("w", newline="") as output:
        writer = csv.writer(output)
        writer.writerow(["runtime", "job", "run", "points", "seconds", "max_rss_bytes"])
        commands = list(runtimes.items())
        for job, path, points, repeats in [
            ("mini", short, 5, 7),
            ("ground", Path("examples/ground-crop.json"), 14_566_627, 3),
        ]:
            for iteration in range(1, repeats + 1):
                first = (iteration - 1) % len(commands)
                for mode, command in commands[first:] + commands[:first]:
                    started = time.perf_counter()
                    run = subprocess.run(["/usr/bin/time", "-lp", *command, "pipeline", str(path)],
                                         capture_output=True, text=True)
                    elapsed = time.perf_counter() - started
                    if run.returncode:
                        raise RuntimeError(run.stderr)
                    actual = json.loads(run.stdout)
                    expected = reference.setdefault(job, actual)
                    if actual != expected or actual["pointsRead"] != points:
                        raise RuntimeError(f"Metadata differs for {mode} {job}")
                    if job == "ground":
                        subprocess.run(["cmp", str(ground), ".cache/experiments/e1-baseline.las"], check=True)
                        ground.unlink()
                    rss = re.search(r"^\s*(\d+)\s+maximum resident set size", run.stderr, re.MULTILINE)
                    writer.writerow([mode, job, iteration, points, f"{elapsed:.6f}", rss[1]])
                    output.flush()
                    print(mode, job, iteration, f"{elapsed:.6f}", rss[1], flush=True)


if __name__ == "__main__":
    main()
