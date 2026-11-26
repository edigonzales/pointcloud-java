"""Compare and time the Solothurn treetop experiment on frozen reference plots."""

import argparse
from collections import defaultdict
import csv
import json
import math
import os
from pathlib import Path
import runpy
import statistics
import subprocess
import tempfile
import time
from typing import NamedTuple


ROOT = Path(__file__).resolve().parents[1]
HAG = runpy.run_path(str(ROOT / "tools/check-hag-dem.py"))
GRID = HAG["GRID"]
require = HAG["require"]
run = HAG["run"]
SURFACES = {"chm": "HeightAboveGround", "dsm": "Z"}
METHODS = tuple(f"{surface}-{radius}" for surface in SURFACES for radius in ("r2", "r3", "r4", "adaptive"))
NODATA = -9999.0
RESOLUTION = 0.5
HALO = 8


class Site(NamedTuple):
    name: str
    west: int
    south: int
    width: int = 200
    height: int = 200
    laz: str = "so-2019.laz"
    dem: str = "forest.tif"

    def grid(self):
        return (self.west - HALO * RESOLUTION, self.south - HALO * RESOLUTION,
                self.width + 2 * HALO, self.height + 2 * HALO)


SITES = (
    Site("cal-flat", 2594140, 1227520),
    Site("cal-steep", 2594020, 1227760),
    Site("test-flat", 2594380, 1227640),
    Site("test-steep", 2594380, 1227880),
    Site("test-other", 2592150, 1228150, laz="so-2019-height.laz", dem="height.tif"),
)
TIMING_SITE = Site("480-m", 2594010, 1227510, 960, 960)


def prepare_height_dem(args):
    area = HAG["Area"]("height", "so-2019-height.laz", 0, 2592145, 1228255, (110, 110))
    HAG["prepare_dem"](args, area, args.work)


def java_command(args, site, folder, workers, *options):
    source = args.data / site.laz
    dem = args.forest_dem if site.dem == "forest.tif" else args.dem_dir / site.dem
    require(source.is_file() and dem.is_file(), f"Missing LAZ or DEM for {site.name}: {source}, {dem}")
    return list(map(str, (args.java, "-Xmx512m", "-cp", args.classpath, "pointcloud.TreetopExperiment",
                         source, dem, folder, site.west, site.south, site.width, site.height,
                         RESOLUTION, workers, *options)))


def generate(args):
    prepare_height_dem(args)
    for site in SITES:
        folder = args.work / "comparison" / site.name
        run(java_command(args, site, folder, 4, "--rasters"))
        print(f"{site.name}: generated {len(METHODS)} methods and both surfaces in {folder}", flush=True)


def pdal_grid(args, site, folder, surface):
    origin_x, origin_y, width, height = site.grid()
    east, north = origin_x + RESOLUTION * width, origin_y + RESOLUTION * height
    dem = args.forest_dem if site.dem == "forest.tif" else args.dem_dir / site.dem
    target, config = (folder / f"pdal-{surface}.{extension}" for extension in ("tif", "json"))
    stages = [
        {"type": "readers.las", "filename": str(args.data / site.laz), "threads": 4},
        {"type": "filters.expression",
         "expression": f"X >= {origin_x} && X < {east} && Y >= {origin_y} && Y < {north}"
                       " && Classification >= 3 && Classification <= 5 && Withheld == 0"},
        {"type": "filters.hag_dem", "raster": str(dem), "zero_ground": False, "min_clamp": -1e100},
        {"type": "filters.expression", "expression": "HeightAboveGround >= 5"},
        {"type": "writers.gdal", "filename": str(target), "resolution": RESOLUTION, "binmode": True,
         "origin_x": origin_x, "origin_y": origin_y, "width": width, "height": height,
         "data_type": "double", "dimension": SURFACES[surface], "output_type": "max",
         "window_size": 0, "nodata": NODATA, "allow_empty": True},
    ]
    config.write_text(json.dumps(stages))
    run([args.pdal, "pipeline", config, "--stream"])
    return target


def read_grid(args, site, path):
    west, south, width, height = site.grid()
    info = json.loads(subprocess.check_output([args.gdal / "gdalinfo", "-json", path], text=True))
    require(info["driverShortName"] == "GTiff" and info["size"] == [width, height]
            and info["geoTransform"] == [west, RESOLUTION, 0, south + RESOLUTION * height, 0, -RESOLUTION],
            f"{path}: incorrect grid geometry")
    crs = subprocess.check_output([args.gdal / "gdalsrsinfo", "-o", "epsg", path], text=True).strip()
    require(crs == "EPSG:2056" and info["coordinateSystem"]["dataAxisToSRSAxisMapping"] == [1, 2],
            f"{path}: incorrect CRS or axis mapping")
    bands = info["bands"]
    require(len(bands) == 1 and bands[0]["type"] == "Float64" and bands[0].get("noDataValue") == NODATA,
            f"{path}: incorrect band count, type or NoData")
    case = GRID["GridCase"](site.name, 0, 0, 0, {}, (width, height))
    profile = GRID["RasterProfile"]("treetops", 0, ("max",))
    values, = GRID["read_bands"](args.gdal / "gdal_translate", path, case, profile)
    require(all(math.isfinite(value) for value in values), f"{path}: unexpected non-finite sample")
    return values


def compare_grids(args, site, folder, surface):
    java = read_grid(args, site, folder / f"{surface}.tif")
    pdal = read_grid(args, site, pdal_grid(args, site, folder, surface))
    masks = sum((a == NODATA) != (b == NODATA) for a, b in zip(java, pdal))
    errors = [abs(a - b) for a, b in zip(java, pdal) if a != NODATA and b != NODATA]
    difference = max(errors, default=0.0)
    require(masks == 0 and difference <= 1e-9,
            f"{site.name} {surface}: {masks} differing NoData cells, max error {difference:.9g} m")
    summary = {"compared_cells": len(java), "valid_cells": len(errors), "nodata_cells": java.count(NODATA),
               "nodata_differences": masks, "maximum_error_m": difference}
    print(f"{site.name} {surface}: {summary}", flush=True)
    return java, summary


def output_rows(path):
    rows = []
    with path.open(newline="") as stream:
        reader = csv.reader(stream)
        require(next(reader) == ["tree_id", "easting", "northing", "z", "height_above_ground"],
                f"{path}: unexpected CSV columns")
        for index, row in enumerate(reader, 1):
            require(len(row) == 5 and row[0] == str(index), f"{path}: invalid treetop record {row}")
            rows.append(tuple(row[1:]))
    return rows


def position(site, point):
    west, south, width, height = site.grid()
    col = math.floor((float(point[0]) - west) / RESOLUTION)
    row = height - 1 - math.floor((float(point[1]) - south) / RESOLUTION)
    return row, col


def equal_neighbour(values, width, height, cell, radius):
    row, col = divmod(cell, width)
    for dy in range(-radius, radius + 1):
        for dx in range(-radius, radius + 1):
            if 0 < dx * dx + dy * dy <= radius * radius and 0 <= row + dy < height and 0 <= col + dx < width:
                if values[cell + dy * width + dx] == values[cell]:
                    return True
    return False


def compare_lidr(site, folder, surfaces):
    observed = defaultdict(set)
    with (folder / "lidr.csv").open(newline="") as stream:
        for row in csv.DictReader(stream):
            observed[row["method"]].add(int(row["cell"]) - 1)
    _, _, width, height = site.grid()
    result = {}
    for surface in surfaces:
        for radius in (2, 3, 4):
            method = f"{surface}-r{radius}"
            rows = [position(site, point) for point in output_rows(folder / f"{method}.csv")]
            java = {row * width + col for row, col in rows}
            require(len(java) == len(rows), f"{site.name} {method}: multiple emitted tips in one cell")
            lidr = {cell for cell in observed[method]
                    if HALO <= cell // width < height - HALO and HALO <= cell % width < width - HALO}
            differences = java ^ lidr
            radius_cells = round(radius / RESOLUTION)
            non_ties = sorted(cell for cell in differences
                              if not equal_neighbour(surfaces[surface], width, height, cell, radius_cells))
            require(not non_ties,
                    f"{site.name} {method}: {len(non_ties)} differences outside equal-score neighbourhoods: {non_ties[:10]}")
            result[method] = {"java": len(java), "lidr": len(lidr), "tie_differences": len(differences)}
            print(f"{site.name} {method}: lidR {result[method]}", flush=True)
    return result


def verify_tiled_and_parallel(args, folder):
    site = SITES[0]
    parallel = folder / site.name
    serial = folder / "serial"
    west = site._replace(width=site.width // 2)
    east = west._replace(west=site.west + round(west.width * RESOLUTION))
    left, right = folder / "split-west", folder / "split-east"
    for area, target in ((site, serial), (west, left), (east, right)):
        run(java_command(args, area, target, 1))
    matches = {}
    for method in METHODS:
        filename = f"{method}.csv"
        require((parallel / filename).read_bytes() == (serial / filename).read_bytes(),
                f"{method}: serial and parallel CSV differ")
        parts = output_rows(left / filename) + output_rows(right / filename)
        require(sorted(parts, key=lambda point: position(site, point)) == output_rows(serial / filename),
                f"{method}: tiling changes tip coordinates, heights or row order")
        matches[method] = len(parts)
    print(f"{site.name}: serial/parallel CSVs are byte-identical; tiled tip records: {matches}", flush=True)
    return matches


def verify(args):
    folder = args.work / "comparison"
    run([args.pdal, "--version"])
    run([args.gdal / "gdalinfo", "--version"])
    subprocess.run(["Rscript", "--vanilla", ROOT / "experiments/check-treetops-lidr.R",
                    *(folder / site.name for site in SITES)],
                   env=dict(os.environ, R_LIBS_USER=str(args.r_library), RGL_USE_NULL="TRUE"), check=True)
    checks = {}
    for site in SITES:
        outputs = folder / site.name
        surfaces, pdal = {}, {}
        for surface in SURFACES:
            surfaces[surface], pdal[surface] = compare_grids(args, site, outputs, surface)
        checks[site.name] = {"pdal": pdal, "lidr": compare_lidr(site, outputs, surfaces)}
    checks["tiled_and_parallel"] = verify_tiled_and_parallel(args, folder)
    (folder / "verification.json").write_text(json.dumps(checks, indent=2) + "\n")


def timings(folder):
    with (folder / "timings.csv").open(newline="") as stream:
        return {row["phase"]: int(row["nanos"]) / 1e9 for row in csv.DictReader(stream)}


def benchmark(args):
    execute, read_time = GRID["timing_functions"]()
    site, method = TIMING_SITE, args.benchmark
    folder = Path(tempfile.mkdtemp(prefix=f"benchmark-{method}-", dir=args.work))
    warmup = folder / "untimed"
    run(java_command(args, site, warmup, 4, "--method", method))
    filename = f"{method}.csv"
    reference = (warmup / filename).read_bytes()
    counts = (warmup / "counts.csv").read_bytes()
    rows = []
    for round_number in range(1, 9):
        workers_order = (1, 4) if round_number % 2 else (4, 1)
        for workers in workers_order:
            target = folder / f"round-{round_number}-{workers}"
            command = java_command(args, site, target, workers, "--method", method)
            started = time.perf_counter()
            result = execute(["/usr/bin/time", "-lp", *command], os.environ)
            wall = time.perf_counter() - started
            _, user, sys, rss = read_time(result.stderr)
            require((target / filename).read_bytes() == reference and (target / "counts.csv").read_bytes() == counts,
                    f"{target}: CSV or counts differ from untimed run")
            phases = timings(target)
            offsets = phases["offsets"]
            detector = phases["detector"] + offsets
            sample = {"round": round_number, "workers": workers, "wall_s": wall,
                      "cpu_s": user + sys, "peak_rss_bytes": rss,
                      "dem_s": phases["dem"], "raster_s": phases["raster"],
                      "offsets_s": offsets, "detector_s": detector, "write_s": phases["write"],
                      "detector_share": detector / wall}
            rows.append(sample)
            print(sample, flush=True)
    with (folder / "samples.csv").open("w", newline="") as stream:
        writer = csv.DictWriter(stream, fieldnames=list(rows[0]), lineterminator="\n")
        writer.writeheader()
        writer.writerows(rows)
    summary = {}
    for workers in (1, 4):
        series = [row for row in rows if row["workers"] == workers]
        summary[workers] = {field: statistics.median(row[field] for row in series)
                            for field in series[0] if field not in ("round", "workers")}
        summary[workers]["wall_min_s"] = min(row["wall_s"] for row in series)
        summary[workers]["wall_max_s"] = max(row["wall_s"] for row in series)
        summary[workers]["max_peak_rss_bytes"] = max(row["peak_rss_bytes"] for row in series)
    summary["gate"] = summary[4]["detector_s"] <= 0.5 and summary[4]["detector_share"] <= 0.2
    (folder / "summary.json").write_text(json.dumps(summary, indent=2) + "\n")
    print(f"{folder}\n{json.dumps(summary, indent=2)}", flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("pdal", type=Path)
    parser.add_argument("--java", type=Path, default=Path(os.environ.get("JAVA_HOME", "/usr")) / "bin/java")
    parser.add_argument("--data", type=Path, default=ROOT / ".cache/test-data")
    parser.add_argument("--forest-dem", type=Path, default=ROOT / ".cache/hag-dem/dem/forest.tif")
    parser.add_argument("--work", type=Path, default=ROOT / ".cache/treetops")
    parser.add_argument("--r-library", type=Path)
    action = parser.add_mutually_exclusive_group(required=True)
    action.add_argument("--generate", action="store_true", help="run the eight variants on the frozen plots")
    action.add_argument("--verify", action="store_true", help="verify completed --generate runs with PDAL and lidR")
    action.add_argument("--benchmark", choices=METHODS, help="time a nominated method on the 480 m core")
    args = parser.parse_args()
    for attr in ("pdal", "java", "data", "forest_dem", "work"):
        setattr(args, attr, getattr(args, attr).resolve())
    args.work.mkdir(parents=True, exist_ok=True)
    args.gdal = args.pdal.parent
    args.dem_dir = args.work / "dem"
    args.r_library = (args.r_library or args.work / "r-library").resolve()
    args.classpath = os.pathsep.join(map(str, (ROOT / "experiments/build/classes/java/main",
                                               ROOT / "build/install/pointcloud/lib/*")))
    if args.generate:
        generate(args)
    elif args.verify:
        verify(args)
    else:
        benchmark(args)


if __name__ == "__main__":
    main()
