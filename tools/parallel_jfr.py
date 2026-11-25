"""Summarize a PointCloud Java CLI recording produced by JDK 25 JFR.

Java execution samples count visible Java stacks, not CPU nanoseconds. The
top frame is the sampled leaf; inclusive counts use the whole visible stack.
Park durations are nanoseconds. Overlap counts time when at least one worker
was waiting for a job during an ordered main-thread read. Allocation weights
estimate allocation pressure in bytes. CPU load values are fractions. Native
samples include waiting and are reported separately from Java execution.
"""

from collections import Counter, defaultdict
from datetime import datetime, timezone
import json
from pathlib import Path
import re
import subprocess


CODEC = "com.github.mreutegg.laszip4j.laszip."
SYMBOL = CODEC + "ArithmeticDecoder.decodeSymbol"
PARALLEL_WORK = "pointcloud.ParallelLazReader.work"
PARALLEL_DECODE = "pointcloud.ParallelLazReader.decode"
PARALLEL_READ = "pointcloud.ParallelLazReader.readBatch"
PARALLEL_AWAIT = "pointcloud.ParallelLazReader.await"
PARALLEL_CLOSE = "pointcloud.ParallelLazReader.close"
SERIAL_READ = "pointcloud.LasReader.readBatch"
CODEC_READ = CODEC + "LazDecoder.read"
QUEUE_TAKE = "java.util.concurrent.ArrayBlockingQueue.take"
FUTURE_GET = "java.util.concurrent.CompletableFuture.get"
LAYER_PAYLOAD = CODEC + "LASreadPoint.prepareLayerPayload"

EVENTS = (
    "jdk.ExecutionSample", "jdk.NativeMethodSample", "jdk.ThreadPark",
    "jdk.ObjectAllocationSample", "jdk.GarbageCollection", "jdk.GCPhasePause",
    "jdk.ThreadCPULoad", "jdk.DataLoss",
)
CATEGORIES = ("layer_byte_arrays", "point14_records", "rgb_records", "point_batch", "other")
EPOCH = datetime(1970, 1, 1, tzinfo=timezone.utc)
TIMESTAMP = re.compile(r"(.+T\d{2}:\d{2}:\d{2})(?:\.(\d{1,9}))?(Z|[+-]\d{2}:\d{2})")
DURATION = re.compile(r"PT(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)(?:\.(\d{1,9}))?S)?")


def _ns_fraction(value):
    return int((value or "").ljust(9, "0"))


def _timestamp_ns(value):
    timestamp, fraction, offset = TIMESTAMP.fullmatch(value).groups()
    elapsed = datetime.fromisoformat(timestamp + ("+00:00" if offset == "Z" else offset)) - EPOCH
    return (elapsed.days * 86400 + elapsed.seconds) * 1_000_000_000 + _ns_fraction(fraction)


def _duration_ns(value):
    hours, minutes, seconds, fraction = DURATION.fullmatch(value).groups()
    elapsed = (int(hours or 0) * 60 + int(minutes or 0)) * 60 + int(seconds or 0)
    return elapsed * 1_000_000_000 + _ns_fraction(fraction)


def _stack(event):
    frames = (event.get("stackTrace") or {}).get("frames", ())
    return [(frame["method"]["type"]["name"].replace("/", ".") + "." + frame["method"]["name"],
             frame["lineNumber"]) for frame in frames if frame.get("method")]


def _thread(event):
    thread = (event.get("sampledThread") or event.get("eventThread") or {})
    return (thread.get("javaThreadId") or thread.get("osThreadId") or 0,
            thread.get("javaName") or thread.get("osName") or "<unknown>")


def _is_decoder(thread, methods, workers):
    if workers == 1:
        return thread[1] == "main" and SERIAL_READ in methods and CODEC_READ in methods
    return PARALLEL_WORK in methods or PARALLEL_DECODE in methods


def _thread_fields(thread):
    return {"thread_id": thread[0], "thread": thread[1]}


def _execution_summary(samples):
    top = Counter(stack[0] for stack in samples)
    other = [(method, count) for method, count in top.most_common() if method != SYMBOL]
    count = len(samples)
    symbol_top = top[SYMBOL]
    symbol_inclusive = sum(SYMBOL in stack for stack in samples)
    return {
        "samples": count,
        "decode_symbol_top": symbol_top,
        "decode_symbol_inclusive": symbol_inclusive,
        "decode_symbol_top_share": symbol_top / count if count else None,
        "decode_symbol_inclusive_share": symbol_inclusive / count if count else None,
        "top_other_methods": [{"method": method, "samples": count} for method, count in other[:10]],
    }


def _cpu(events, workers):
    worker_threads = defaultdict(list)
    main = []
    missing = 0
    for event in events["jdk.ExecutionSample"]:
        methods = [method for method, _ in _stack(event)]
        if not methods:
            missing += 1
            continue
        thread = _thread(event)
        if thread[1] == "main":
            main.append(methods)
        if _is_decoder(thread, methods, workers):
            worker_threads[thread].append(methods)
    if len(worker_threads) != workers:
        raise ValueError(f"Expected {workers} decoder threads with Java samples, found "
                         f"{len(worker_threads)}: {', '.join(thread[1] for thread in worker_threads)}")
    return {
        "process_java_samples": len(events["jdk.ExecutionSample"]),
        "missing_stack_samples": missing,
        "native_samples": len(events["jdk.NativeMethodSample"]),
        "main": _execution_summary(main),
        "decoder": _execution_summary([sample for samples in worker_threads.values() for sample in samples]),
        "threads": [{**_thread_fields(thread), **_execution_summary(samples)}
                    for thread, samples in sorted(worker_threads.items())],
    }, set(worker_threads)


def _union(intervals):
    merged = []
    for start, end in sorted(intervals):
        if merged and start <= merged[-1][1]:
            merged[-1] = (merged[-1][0], max(end, merged[-1][1]))
        else:
            merged.append((start, end))
    return merged


def _overlap(first, second):
    total = 0
    index = 0
    for start, end in first:
        while index < len(second) and second[index][1] <= start:
            index += 1
        while index < len(second) and second[index][0] < end:
            other_start, other_end = second[index]
            total += min(end, other_end) - max(start, other_start)
            if other_end > end:
                break
            index += 1
    return total


def _elapsed(intervals):
    return sum(end - start for start, end in intervals)


def _parks(events, decoder_threads):
    ordered = []
    queues = {thread: [] for thread in decoder_threads if thread[1] != "main"}
    for event in events["jdk.ThreadPark"]:
        methods = [method for method, _ in _stack(event)]
        if PARALLEL_CLOSE in methods:
            continue
        thread = _thread(event)
        if thread[1] == "main" and all(method in methods for method in (PARALLEL_READ, PARALLEL_AWAIT, FUTURE_GET)):
            intervals = ordered
        elif thread in queues and PARALLEL_WORK in methods and QUEUE_TAKE in methods:
            intervals = queues[thread]
        else:
            continue
        start = _timestamp_ns(event["startTime"])
        intervals.append((start, start + _duration_ns(event["duration"])))

    main_union = _union(ordered)
    idle_union = _union(interval for queue in queues.values() for interval in queue)
    overlap = _overlap(main_union, idle_union)
    main_ns = _elapsed(main_union)
    return {
        "main_ordered_count": len(ordered),
        "main_ordered_ns": main_ns,
        "worker_queue_count": sum(len(queue) for queue in queues.values()),
        "worker_queue_ns": sum(_elapsed(queue) for queue in queues.values()),
        "worker_queue_union_ns": _elapsed(idle_union),
        "overlap_ns": overlap,
        "overlap_share": overlap / main_ns if main_ns else None,
        "worker_threads": [{**_thread_fields(thread), "count": len(queue), "ns": _elapsed(queue),
                            "main_overlap_ns": _overlap(main_union, _union(queue))}
                           for thread, queue in sorted(queues.items())],
    }


def _allocation_category(object_class, methods):
    if object_class == "[B" and (LAYER_PAYLOAD in methods or any(
            method.startswith(CODEC + "LASreadItemCompressed_") and method.endswith("14_v3.init")
            for method in methods)):
        return "layer_byte_arrays"
    if object_class == CODEC + "PointDataRecordPoint14":
        return "point14_records"
    if object_class in (CODEC + "PointDataRecordRGB", CODEC + "PointDataRecordRgbNIR"):
        return "rgb_records"
    if object_class == "pointcloud.PointBatch" or "pointcloud.PointBatch.<init>" in methods:
        return "point_batch"
    return "other"


def _allocation(events):
    counts, weights = Counter(), Counter()
    sites = Counter()
    threads = defaultdict(Counter)
    for event in events["jdk.ObjectAllocationSample"]:
        thread = _thread(event)
        stack = _stack(event)
        object_class = (event.get("objectClass") or {}).get("name", "<unknown>").replace("/", ".")
        category = _allocation_category(object_class, [method for method, _ in stack])
        site, line = next(((method, line) for method, line in stack if method.startswith((CODEC, "pointcloud."))),
                          stack[0] if stack else ("<unknown>", -1))
        weight = event["weight"]
        counts[category] += 1
        weights[category] += weight
        threads[thread][category] += weight
        sites[thread, category, object_class, site, line] += weight
    return {
        "samples": sum(counts.values()),
        "weighted_bytes": sum(weights.values()),
        "categories": {category: {"samples": counts[category], "weighted_bytes": weights[category]}
                       for category in CATEGORIES},
        "threads": [{**_thread_fields(thread), "weighted_bytes": sum(categories.values()),
                     "weighted_bytes_by_category": {category: categories[category] for category in CATEGORIES}}
                    for thread, categories in sorted(threads.items())],
        "top_sites": [{**_thread_fields(thread), "category": category, "object_class": object_class,
                       "method": site, "line": line, "weighted_bytes": weight}
                      for (thread, category, object_class, site, line), weight in sites.most_common(12)],
    }


def _gc(events):
    collections = events["jdk.GarbageCollection"]
    pauses = events["jdk.GCPhasePause"]
    return {
        "collections": len(collections),
        "collection_ns": sum(_duration_ns(event["duration"]) for event in collections),
        "pauses": len(pauses),
        "pause_ns": sum(_duration_ns(event["duration"]) for event in pauses),
    }


def _load(events, decoder_threads):
    return {
        "threads": [{**_thread_fields(_thread(event)),
                     **{key: event[key] for key in ("startTime", "user", "system")}}
                    for event in events["jdk.ThreadCPULoad"]
                    if _thread(event) in decoder_threads or _thread(event)[1] == "main"],
    }


def analyze(jfr: Path, recording: Path, workers: int) -> dict:
    """Return a JSON-serializable summary for the requested active decoder count."""
    if workers < 1:
        raise ValueError("Expected at least one decoder")
    result = subprocess.run([jfr, "print", "--json", "--stack-depth", "64", "--events", ",".join(EVENTS), recording],
                            check=True, capture_output=True, text=True)
    events = defaultdict(list)
    for event in json.loads(result.stdout)["recording"]["events"]:
        events[event["type"]].append(event["values"])
    cpu, decoder_threads = _cpu(events, workers)
    return {
        "recording": str(recording),
        "expected_workers": workers,
        "data_loss_bytes": sum(event["amount"] for event in events["jdk.DataLoss"]),
        "cpu": cpu,
        "parks": _parks(events, decoder_threads),
        "allocation": _allocation(events),
        "gc": _gc(events),
        "load": _load(events, decoder_threads),
    }
