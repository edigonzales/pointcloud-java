set -euo pipefail
work=.cache/experiments
bench_cp='experiments/build/install/experiments/lib/*'
mkdir -p "$work"
cli=build/install/pointcloud/bin/pointcloud
if [[ ! -f "$work/e1-baseline.las" ]]; then
  JAVA_OPTS=-Xmx512m "$cli" pipeline examples/ground-crop.json > "$work/e1-baseline.json"
  mv build/results/ground-crop.las "$work/e1-baseline.las"
fi
if [[ ! -f "$work/kernel.las" ]]; then
  JAVA_OPTS=-Xmx512m "$cli" pipeline examples/decimate.json > "$work/decimate-meta.json"
  mv build/results/decimate.las "$work/kernel.las"
fi
"$JAVA_HOME/bin/java" --add-modules jdk.incubator.vector -cp "$bench_cp" org.openjdk.jmh.Main VectorBenchmark -prof gc -rf json -rff "$work/e2-jmh.json" > "$work/e2-jmh.log"
printf 'workers_or_cli,run,points,seconds,max_rss_bytes\n' > "$work/e3.csv"
for workers in 0 1 2 4; do
  for run in 1 2 3; do
    /usr/bin/time -lp "$JAVA_HOME/bin/java" -Xmx512m -cp "$bench_cp" pointcloud.BatchParallel "$workers" "$work/e3-output.las" > "$work/e3-$workers-$run.out" 2> "$work/e3-$workers-$run.time"
    cmp "$work/e3-output.las" "$work/e1-baseline.las"
    cmp "$work/e3-$workers-$run.out" "$work/e3-0-1.out"
    rm "$work/e3-output.las"
    awk -v row="$workers,$run,14566627" '/^real /{s=$2} /maximum resident set size/{r=$1} END {print row "," s "," r}' "$work/e3-$workers-$run.time" | tee -a "$work/e3.csv"
  done
done
python3 - <<'PY'
import json
from pathlib import Path

pipeline = json.loads(Path("examples/ground-crop.json").read_text())
pipeline["pipeline"][3]["dimensions"] = "Z"
pipeline["pipeline"][4]["filename"] = ".cache/experiments/e3-output.las"
Path(".cache/experiments/e3-cli.json").write_text(json.dumps(pipeline))
PY
for run in 1 2 3; do
  JAVA_OPTS=-Xmx512m /usr/bin/time -lp "$cli" pipeline "$work/e3-cli.json" > "$work/e3-cli-$run.out" 2> "$work/e3-cli-$run.time"
  cmp "$work/e3-output.las" "$work/e1-baseline.las"
  rm "$work/e3-output.las"
  awk -v row="cli,$run,14566627" '/^real /{s=$2} /maximum resident set size/{r=$1} END {print row "," s "," r}' "$work/e3-cli-$run.time" | tee -a "$work/e3.csv"
done
python3 - <<'PY'
import json
import math
from pathlib import Path

work = Path(".cache/experiments")
_, selected, minimum, maximum, mean, variance = (work / "e3-0-1.out").read_text().strip().split(",")
for path in work.glob("e3-cli-*.out"):
    result = json.loads(path.read_text())
    stats = result["statistics"][0]["dimensions"][0]
    if (stats["count"] != int(selected) or stats["minimum"] != float(minimum)
            or stats["maximum"] != float(maximum) or not math.isclose(stats["average"], float(mean), rel_tol=5.1e-10)
            or not math.isclose(stats["variance"], float(variance), rel_tol=5.1e-10)):
        raise ValueError(f"Batch statistics differ from {path}")
print("Batch statistics match the CLI")
PY
printf 'variant,run,points,raw_x_sum,raw_y_sum,raw_z_sum,first_ms,min_ms,median_ms,max_ms,process_seconds,max_rss_bytes\n' > "$work/e5.csv"
for variant in channel mapped; do
  for run in 1 2 3; do
    /usr/bin/time -lp "$JAVA_HOME/bin/java" -Xmx512m -cp "$bench_cp" pointcloud.LasIoScan "$variant" > "$work/e5-$variant-$run.out" 2> "$work/e5-$variant-$run.time"
    diff <(cut -d, -f1-4 "$work/e5-channel-1.out") <(cut -d, -f1-4 "$work/e5-$variant-$run.out")
    awk -v row="$variant,$run,$(cat "$work/e5-$variant-$run.out")" '/^real /{s=$2} /maximum resident set size/{r=$1} END {print row "," s "," r}' "$work/e5-$variant-$run.time" | tee -a "$work/e5.csv"
  done
done
