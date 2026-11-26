"""Score tree detections against independently marked apexes."""

import argparse
import csv
import json
from pathlib import Path
import statistics

import numpy as np
from scipy.optimize import linear_sum_assignment
from scipy.spatial.distance import cdist


CALIBRATION = ("cal-flat", "cal-steep")
HOLDOUT = ("test-flat", "test-steep", "test-other")
METHODS = tuple(f"{surface}-{radius}" for surface in ("chm", "dsm")
                for radius in ("r2", "r3", "r4", "adaptive"))
MATCH_DISTANCE = 2


def points(path):
    with path.open(newline="") as stream:
        reader = csv.DictReader(stream)
        fields = ("easting", "northing", "height_above_ground")
        if not set(fields).issubset(reader.fieldnames or []):
            raise ValueError(f"{path}: expected {', '.join(fields)}")
        coordinates = np.array([[float(row[field]) for field in fields] for row in reader]).reshape(-1, 3)
        if not np.isfinite(coordinates).all():
            raise ValueError(f"{path}: nonfinite coordinate or height")
        return coordinates


def outside_exclusions(coordinates, bounds):
    excluded = np.zeros(len(coordinates), dtype=bool)
    for west, south, east, north in bounds:
        x, y = coordinates[:, :2].T
        excluded |= (west <= x) & (x < east) & (south <= y) & (y < north)
    return coordinates[~excluded]


def score(actual, reference):
    distance = cdist(reference[:, :2], actual[:, :2])
    # One dummy column per reference. An additional match always saves more
    # than rearranging all valid matches could add to the total distance.
    penalty = (min(len(actual), len(reference)) + 1) * MATCH_DISTANCE
    cost = np.full((len(reference), len(actual) + len(reference)), penalty, dtype=float)
    cost[:, :len(actual)] = np.where(distance <= MATCH_DISTANCE, distance, 2 * penalty)
    refs, detections = linear_sum_assignment(cost)
    matched = detections < len(actual)
    refs, detections = refs[matched], detections[matched]
    matches = len(refs)
    offsets = distance[refs, detections]
    height_errors = actual[detections, 2] - reference[refs, 2]
    return {"reference": len(reference), "detections": len(actual), "matches": matches,
            "precision": matches / len(actual) if len(actual) else 0.0,
            "recall": matches / len(reference) if len(reference) else 0.0,
            "f1": 2 * matches / (len(actual) + len(reference)) if len(actual) + len(reference) else 0.0,
            "xy_mean_m": float(offsets.mean()) if matches else None,
            "xy_max_m": float(offsets.max()) if matches else None,
            "hag_bias_m": float(height_errors.mean()) if matches else None,
            "hag_mae_m": float(np.abs(height_errors).mean()) if matches else None}


def site_results(reference_dir, runs, sites, methods):
    with (reference_dir / "exclusions.json").open() as stream:
        exclusions = json.load(stream)
    results = {}
    for site in sites:
        bounds = exclusions[site]
        reference = outside_exclusions(points(reference_dir / f"{site}.csv"), bounds)
        excluded_area = sum((east - west) * (north - south) for west, south, east, north in bounds)
        results[site] = {"excluded_m2": excluded_area,
                         "methods": {
                             method: score(outside_exclusions(points(runs / site / f"{method}.csv"), bounds),
                                           reference)
                             for method in methods}}
    return results


def calibrate(references, runs):
    results = site_results(references, runs, CALIBRATION, METHODS)
    mean_f1 = {method: statistics.mean(results[site]["methods"][method]["f1"] for site in CALIBRATION)
               for method in METHODS}
    timings = {method: [] for method in METHODS}
    for site in CALIBRATION:
        with (runs / site / "timings.csv").open(newline="") as stream:
            for row in csv.DictReader(stream):
                if row["phase"] == "detector":
                    timings[row["method"]].append(int(row["nanos"]))
    best = max(mean_f1.values())
    winner = min((method for method in METHODS if best - mean_f1[method] <= 0.01 + 1e-12),
                 key=lambda method: (method.endswith("adaptive"), statistics.median(timings[method]), method))
    return {"winner": winner, "mean_f1": mean_f1, "sites": results}


def evaluate(references, runs, winner):
    results = site_results(references, runs, HOLDOUT, (winner,))
    values = [result["methods"][winner] for result in results.values()]
    matches = sum(value["matches"] for value in values)
    detections = sum(value["detections"] for value in values)
    reference = sum(value["reference"] for value in values)
    precision = matches / detections if detections else 0.0
    recall = matches / reference if reference else 0.0
    passed = precision >= 0.8 and recall >= 0.8 and all(
        value["reference"] >= 30 and value["f1"] >= 0.75 for value in values)
    return {"winner": winner, "precision": precision, "recall": recall,
            "f1": 2 * matches / (detections + reference) if detections + reference else 0.0,
            "passed": passed, "sites": results}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("references", type=Path, help="CSV reference apexes and exclusions.json")
    parser.add_argument("runs", type=Path, help="one subdirectory per site")
    command = parser.add_subparsers(dest="phase", required=True)
    command.add_parser("calibrate")
    test = command.add_parser("evaluate")
    test.add_argument("nomination", type=Path, help="JSON emitted by calibrate before opening holdout results")
    args = parser.parse_args()
    if args.phase == "calibrate":
        result = calibrate(args.references, args.runs)
    else:
        with args.nomination.open() as stream:
            result = evaluate(args.references, args.runs, json.load(stream)["winner"])
    print(json.dumps(result, indent=2, allow_nan=False))


if __name__ == "__main__":
    main()
