"""Compare bounded prominence with the existing detector on Solothurn 2019."""

import argparse
import csv
import hashlib
import json
import os
from pathlib import Path
import runpy
import statistics
import tempfile
import time


ROOT = Path(__file__).resolve().parents[1]
BASE = runpy.run_path(str(ROOT / "experiments/check-treetops.py"))
SCORE = runpy.run_path(str(ROOT / "experiments/score-treetops.py"))
SITE = BASE["Site"]
DEVELOPMENT = BASE["SITES"]
BLIND = (SITE("blind-steep", 2592110, 1228360, laz="so-2019-height.laz", dem="blind-height.tif"),
         SITE("blind-mild", 2594242, 1227657))
BASELINE = "dsm-r3"
CONTROLS = ("chm-r3", BASELINE, "chm-gauss-r3", "dsm-gauss-r3")
PROMINENCE = tuple(f"{surface}{preprocess}-prom-{threshold}"
                   for surface in ("chm", "dsm") for preprocess in ("", "-gauss")
                   for threshold in ("025", "050", "075", "100", "150"))
EXTERNAL = ("vhm-r3", "lspikefree-r3", "lidartree-chm", "lidartree-dsm", "li2012")
run = BASE["run"]
require = BASE["require"]


def write_json(path, data):
    path.write_text(json.dumps(data, indent=2, allow_nan=False) + "\n")


def prepare_dem(args, site):
    if site.dem != "forest.tif":
        area = BASE["HAG"]["Area"](Path(site.dem).stem, site.laz, 0,
                                   site.west - 22, site.south + 122, (144, 144))
        BASE["HAG"]["prepare_dem"](args, area, args.work)


def java(args, site, output, workers, *options):
    return BASE["java_command"](args, site, output, workers, *options)


def excluded_area(bounds):
    xs = sorted({x for west, _, east, _ in bounds for x in (west, east)})
    area = 0
    for left, right in zip(xs, xs[1:]):
        previous = float("-inf")
        for south, north in sorted((s, n) for w, s, e, n in bounds if w < right and left < e):
            area += (right - left) * max(0, north - max(south, previous))
            previous = max(previous, north)
    return area


def score_site(references, site, folder, methods):
    bounds = json.loads((references / "exclusions.json").read_text())[site.name]
    outside = SCORE["outside_exclusions"]
    ref = outside(SCORE["points"](references / f"{site.name}.csv"), bounds)
    results = {}
    for method in methods:
        path = folder / f"{method}.csv"
        if not path.exists() and method in EXTERNAL:
            results[method] = None
            continue
        unsupported = folder / f"{method}-unsupported.csv"
        missing = 0
        if unsupported.exists():
            with unsupported.open(newline="") as stream:
                for row in csv.DictReader(stream):
                    x, y = float(row["easting"]), float(row["northing"])
                    missing += not any(w <= x < e and s <= y < n for w, s, e, n in bounds)
        actual = outside(SCORE["points"](path), bounds)
        results[method] = SCORE["score"](actual, ref, missing)
        if method in EXTERNAL:
            results[method]["unsupported"] = missing
    return {"excluded_m2": excluded_area(bounds), "methods": results}


def detector_times(folder, method):
    with (folder / "timings.csv").open(newline="") as stream:
        records = tuple(csv.DictReader(stream))
    preparation = method.split("-prom-")[0] if "-prom-" in method else method.removesuffix("-r3")
    times = {}
    for record in records:
        phase, variant = record["phase"], record["method"]
        if variant in ("", method) or phase == "smooth" and variant == preparation:
            times[phase] = int(record["nanos"]) / 1e9
    return times


def detector_seconds(phases):
    return phases["detector"] + phases.get("smooth", 0) + phases.get("offsets", 0)


def develop(args):
    reference = ROOT / "experiments/treetop-reference"
    results, times = {}, {method: [] for method in PROMINENCE}
    for site in DEVELOPMENT:
        prepare_dem(args, site)
        folder = args.work / "comparison" / site.name
        run(java(args, site, folder, 4, "--prominence", "--rasters"))
        results[site.name] = score_site(reference, site, folder, CONTROLS + PROMINENCE)
        for method in PROMINENCE:
            times[method].append(detector_seconds(detector_times(folder, method)))
    means = {method: statistics.mean(site["methods"][method]["f1"] for site in results.values())
             for method in CONTROLS + PROMINENCE}
    best = max(means[method] for method in PROMINENCE)
    winner = min((method for method in PROMINENCE if best - means[method] <= 0.01 + 1e-12),
                 key=lambda method: ("-gauss-" in method, statistics.median(times[method]), method))
    nomination = {"winner": winner, "mean_f1": means,
                  "median_detector_s": {method: statistics.median(times[method]) for method in PROMINENCE},
                  "sites": results}
    write_json(args.work / "nomination.json", nomination)
    print(f"Frozen nomination: {winner}, mean F1 {means[winner]:.4f}", flush=True)


def winner(args):
    method = json.loads((args.work / "nomination.json").read_text())["winner"]
    require(method in PROMINENCE, "The nomination must identify a planned prominence method")
    return method


def pooled(sites, method):
    values = [site["methods"][method] for site in sites.values()]
    refs, detections, matches = (sum(value[key] for value in values)
                                for key in ("reference", "detections", "matches"))
    return {"reference": refs, "detections": detections, "matches": matches,
            "precision": matches / detections if detections else 0.0,
            "recall": matches / refs if refs else 0.0,
            "f1": 2 * matches / (detections + refs) if detections + refs else 0.0}


def holdout(args):
    chosen = winner(args)
    reference = args.blind_reference
    digests = {path.name: hashlib.sha256(path.read_bytes()).hexdigest()
               for path in (reference / "exclusions.json", *(reference / f"{s.name}.csv" for s in BLIND))}
    results = {}
    for site in BLIND:
        prepare_dem(args, site)
        folder = args.work / "holdout" / site.name
        for method in (BASELINE, chosen):
            run(java(args, site, folder / method, 4, "--method", method))
            output = folder / f"{method}.csv"
            output.write_bytes((folder / method / output.name).read_bytes())
        results[site.name] = score_site(reference, site, folder, (BASELINE, chosen))
    totals = {method: pooled(results, method) for method in (BASELINE, chosen)}
    quality = totals[chosen]
    passed = quality["precision"] >= 0.8 and quality["recall"] >= 0.8 and all(
        row["methods"][chosen]["reference"] >= 30 and row["methods"][chosen]["f1"] >= 0.75
        for row in results.values())
    report = {"winner": chosen, "passed": passed, "references_sha256": digests, "pooled": totals, "sites": results}
    write_json(args.work / "holdout.json", report)
    print(json.dumps(report, indent=2), flush=True)


def external(args):
    reference = ROOT / "experiments/treetop-reference"
    results = {site.name: score_site(reference, site, args.work / "external" / site.name, EXTERNAL)
               for site in DEVELOPMENT}
    write_json(args.work / "external-quality.json", results)


def verify(args):
    chosen = winner(args)
    site = next(site for site in DEVELOPMENT if site.name == "test-steep")
    root = args.work / "verification"
    root.mkdir(exist_ok=True)
    filename = f"{chosen}.csv"
    left = site._replace(width=site.width // 2)
    right = left._replace(west=site.west + left.width // 2)
    for area, name, workers in ((site, "serial", 1), (site, "parallel", 4), (left, "west", 1), (right, "east", 1)):
        run(java(args, area, root / name, workers, "--method", chosen))
    parallel, serial = (root / run_name / filename for run_name in ("parallel", "serial"))
    require(parallel.read_bytes() == serial.read_bytes(), "One and four workers produced different CSVs")
    read_rows = BASE["output_rows"]
    rows = read_rows(serial)
    parts = read_rows(root / "west" / filename) + read_rows(root / "east" / filename)
    require(sorted(parts, key=lambda p: BASE["position"](site, p)) == rows,
            "Split cores changed the original XYZ/HAG rows or order")
    result = {"method": chosen, "site": site.name, "rows": len(rows), "serial_parallel_byte_equal": True,
              "split_core_records_equal": True}
    write_json(root / "verification.json", result)
    print(result, flush=True)


def benchmark(args):
    chosen = winner(args)
    site = BASE["TIMING_SITE"]
    folder = Path(tempfile.mkdtemp(prefix="benchmark-", dir=args.work))
    execute, read_time = BASE["GRID"]["timing_functions"]()
    expected = {}
    points_read = {}
    for method in (BASELINE, chosen):
        target = folder / f"warm-{method}"
        run(java(args, site, target, 4, "--method", method))
        counts = target / "counts.csv"
        expected[method] = ((target / f"{method}.csv").read_bytes(), counts.read_bytes())
        with counts.open(newline="") as stream:
            points_read[method] = int(next(csv.DictReader(stream))["points_read"])
    rotation = [(BASELINE, 1), (chosen, 4), (chosen, 1), (BASELINE, 4)]
    rows = []
    for index in range(8):
        start = index % len(rotation)
        for method, workers in rotation[start:] + rotation[:start]:
            target = folder / f"round-{index + 1}-{method}-{workers}"
            started = time.perf_counter()
            result = execute(["/usr/bin/time", "-lp", *java(args, site, target, workers, "--method", method)], os.environ)
            wall = time.perf_counter() - started
            _, user, sys, rss = read_time(result.stderr)
            require(((target / f"{method}.csv").read_bytes(), (target / "counts.csv").read_bytes()) == expected[method],
                    f"{target}: CSV or counts differ")
            phases = detector_times(target, method)
            detection = detector_seconds(phases)
            row = {"round": index + 1, "method": method, "workers": workers, "wall_s": wall, "cpu_s": user + sys,
                   "rss_bytes": rss, "points_per_s": points_read[method] / wall,
                   "dem_s": phases["dem"], "raster_s": phases["raster"],
                   "smooth_s": phases.get("smooth", 0), "search_s": detection,
                   "search_share": detection / wall, "write_s": phases["write"]}
            rows.append(row)
            print(row, flush=True)
    with (folder / "samples.csv").open("w", newline="") as stream:
        writer = csv.DictWriter(stream, fieldnames=list(rows[0]), lineterminator="\n")
        writer.writeheader()
        writer.writerows(rows)
    summary = {}
    for method, workers in rotation:
        samples = [row for row in rows if row["workers"] == workers and row["method"] == method]
        summary[f"{method}-{workers}"] = {
            key: statistics.median(row[key] for row in samples)
            for key in samples[0] if key not in {"round", "method", "workers"}}
    candidate = summary[f"{chosen}-4"]
    report = {"winner": chosen, "passed": candidate["search_s"] <= 0.5 and candidate["search_share"] <= 0.2,
              "medians": summary, "results": str(folder)}
    write_json(folder / "summary.json", report)
    print(json.dumps(report, indent=2), flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("pdal", type=Path)
    parser.add_argument("phase", choices=("develop", "external", "holdout", "verify", "benchmark"))
    parser.add_argument("--java", type=Path, default=Path(os.environ.get("JAVA_HOME", "/usr")) / "bin/java")
    parser.add_argument("--data", type=Path, default=ROOT / ".cache/test-data")
    parser.add_argument("--forest-dem", type=Path, default=ROOT / ".cache/hag-dem/dem/forest.tif")
    parser.add_argument("--work", type=Path, default=ROOT / ".cache/treetops-prominence")
    parser.add_argument("--blind-reference", type=Path, default=ROOT / "experiments/treetop-prominence-reference")
    args = parser.parse_args()
    for attr in ("pdal", "java", "data", "forest_dem", "work", "blind_reference"):
        setattr(args, attr, getattr(args, attr).resolve())
    args.work.mkdir(parents=True, exist_ok=True)
    args.gdal = args.pdal.parent
    args.dem_dir = args.work / "dem"
    args.classpath = os.pathsep.join(map(str, (ROOT / "experiments/build/classes/java/main",
                                               ROOT / "build/install/pointcloud/lib/*")))
    {"develop": develop, "external": external, "holdout": holdout, "verify": verify,
     "benchmark": benchmark}[args.phase](args)


if __name__ == "__main__":
    main()
