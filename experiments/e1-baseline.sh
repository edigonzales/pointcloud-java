set -euo pipefail
work=.cache/experiments
mkdir -p "$work" build/results
export JAVA_OPTS=-Xmx512m
cli=build/install/pointcloud/bin/pointcloud
pdal=${1:-pdal}
batchcp='experiments/build/install/experiments/lib/*'
csv="$work/e1.csv"
printf 'variant,batch,workers,iteration,points,seconds,max_rss_bytes\n' > "$csv"
measure() {
    local variant=$1 batch=$2 workers=$3 iteration=$4 points=$5
    shift 5
    /usr/bin/time -lp "$@" > "$work/e1-stdout" 2> "$work/e1-time"
    awk -v row="$variant,$batch,$workers,$iteration,$points" '
        /^real /{seconds=$2}
        /maximum resident set size/{rss=$1}
        END { printf "%s,%s,%s\n",row,seconds,rss }' "$work/e1-time" | tee -a "$csv"
}
for run in 1 2 3; do measure header 0 1 "$run" 0 "$cli" info .cache/test-data/so-2019.laz; done
for batch in codec 32768 131072 240000; do
    for run in 1 2 3; do
        measure scan "$batch" 1 "$run" 14566627 "$JAVA_HOME/bin/java" -Xmx512m -cp "$batchcp" pointcloud.DecodeScan "$batch"
    done
done
for batch in 32768 131072 240000; do
    for run in 1 2 3; do
        measure java-scalar "$batch" 1 "$run" 14566627 "$cli" pipeline examples/ground-crop.json --batch-size "$batch"
        if [[ "$batch" == 32768 && "$run" == 1 ]]; then
            mv build/results/ground-crop.las "$work/e1-baseline.las"
            cp "$work/e1-stdout" "$work/e1-baseline.json"
        else
            cmp build/results/ground-crop.las "$work/e1-baseline.las"
            cmp "$work/e1-stdout" "$work/e1-baseline.json"
            rm build/results/ground-crop.las
        fi
    done
done
for run in 1 2 3; do
    measure pdal-stream 10000 7 "$run" 14566627 "$pdal" pipeline examples/ground-crop.json --stream
    if [[ "$run" == 1 ]]; then
        mv build/results/ground-crop.las "$work/e1-pdal.las"
    else
        cmp build/results/ground-crop.las "$work/e1-pdal.las"
        rm build/results/ground-crop.las
    fi
done
"$cli" pipeline examples/decimate.json > "$work/decimate-meta.json"
mv build/results/decimate.las "$work/kernel.las"
experiments/build/install/experiments/bin/experiments 'SelectionBenchmark' -prof gc -rf json -rff "$work/e1-jmh.json" > "$work/e1-jmh.log"
JAVA_OPTS="-Xmx512m -Xlog:jfr+startup=off -XX:StartFlightRecording=filename=$work/e1-profile.jfr,settings=profile,dumponexit=true" \
    "$cli" pipeline examples/ground-crop.json > "$work/e1-profile.json"
cmp build/results/ground-crop.las "$work/e1-baseline.las"
cmp "$work/e1-profile.json" "$work/e1-baseline.json"
rm build/results/ground-crop.las
"$JAVA_HOME/bin/jfr" view allocation-by-site "$work/e1-profile.jfr"
