set -euo pipefail
work=.cache/experiments
bench_cp='experiments/build/install/experiments/lib/*'
mkdir -p "$work"
if [[ ! -f "$work/e1-baseline.las" ]]; then
  JAVA_OPTS=-Xmx512m build/install/pointcloud/bin/pointcloud pipeline examples/ground-crop.json > "$work/e1-baseline.json"
  mv build/results/ground-crop.las "$work/e1-baseline.las"
fi
printf 'workers,run,points,seconds,max_rss_bytes\n' > "$work/e4.csv"
for workers in 0 2 4; do
  for run in 1 2 3; do
    /usr/bin/time -lp "$JAVA_HOME/bin/java" -Xmx512m -cp "$bench_cp" pointcloud.ChunkParallel \
      "$workers" .cache/test-data/so-2019.laz "$work/e4-output.las" > "$work/e4-$workers-$run.out" 2> "$work/e4-$workers-$run.time"
    cmp "$work/e4-output.las" "$work/e1-baseline.las"
    rm "$work/e4-output.las"
    awk -v row="$workers,$run,14566627" '/^real /{s=$2} /maximum resident set size/{r=$1} END {print row "," s "," r}' "$work/e4-$workers-$run.time" | tee -a "$work/e4.csv"
  done
done
