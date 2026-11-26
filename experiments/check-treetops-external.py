"""Compare alternate canopy surfaces and external treetop methods on the Solothurn plots."""

import argparse
import csv
import json
import os
from pathlib import Path
import runpy
import subprocess
import tempfile
from typing import NamedTuple

import numpy as np


ROOT = Path(__file__).resolve().parents[1]
EXPERIMENT = runpy.run_path(str(ROOT / "experiments/check-treetops.py"))
GRID = EXPERIMENT["GRID"]
Site = EXPERIMENT["Site"]
require = EXPERIMENT["require"]
run = EXPERIMENT["run"]
KNOWN = EXPERIMENT["SITES"]
BLIND = (Site("blind-steep", 2592110, 1228360, laz="so-2019-height.laz", dem="blind-height.tif"),
         Site("blind-mild", 2594242, 1227657))
VHM_URL = ("https://files.geo.so.ch/ch.so.agi.lidar_2019.ndsm_vegetation/aktuell/"
           "ch.so.agi.lidar_2019.ndsm_vegetation.tif")
NODATA = -9999
RESOLUTION = 0.5
POINT_FIELDS = ("easting", "northing", "z", "height_above_ground")


class RasterGrid(NamedTuple):
    west: float
    north: float
    resolution: float
    width: int
    height: int

    @property
    def south(self):
        return self.north - self.height * self.resolution

    @property
    def east(self):
        return self.west + self.width * self.resolution

    def center(self, row, col):
        return self.west + (col + 0.5) * self.resolution, self.north - (row + 0.5) * self.resolution


def raster_grid(args, raster):
    info = json.loads(subprocess.check_output([args.gdal / "gdalinfo", "-json", raster], text=True))
    crs = subprocess.check_output([args.gdal / "gdalsrsinfo", "-o", "epsg", raster], text=True).strip()
    west, dx, shear_x, north, shear_y, dy = info["geoTransform"]
    require(info["driverShortName"] == "GTiff" and shear_x == shear_y == 0 and dx == -dy and dx > 0
            and crs == "EPSG:2056" and info["coordinateSystem"]["dataAxisToSRSAxisMapping"] == [1, 2],
            f"{raster}: expected a north-up LV95 GeoTIFF")
    bands = info["bands"]
    require(len(bands) == 1 and bands[0]["type"] in ("Float32", "Float64")
            and bands[0].get("noDataValue") == NODATA, f"{raster}: unexpected band or NoData")
    return RasterGrid(west, north, dx, *info["size"])


def read_raster(args, raster, grid):
    size = (grid.width, grid.height)
    case = GRID["GridCase"](raster.name, 0, 0, 0, {}, size)
    profile = GRID["RasterProfile"]("external", 0, ("max",))
    # Avoid placing the temporary ENVI conversion beside the Java reference grids.
    with tempfile.TemporaryDirectory() as folder:
        linked = Path(folder) / raster.name
        linked.symlink_to(raster)
        values, = GRID["read_bands"](args.gdal / "gdal_translate", linked, case, profile)
    array = np.array(values).reshape(grid.height, grid.width)
    require(np.isfinite(array).all(), f"{raster}: invalid floating samples")
    return np.where(array == NODATA, -np.inf, array)


def prepare_vhm(args, grid, output):
    env = dict(os.environ, GDAL_DISABLE_READDIR_ON_OPEN="EMPTY_DIR", GDAL_PAM_ENABLED="NO")
    command = [args.gdal / "gdal_translate", "-q", "-projwin", grid.west, grid.north, grid.east, grid.south,
               "-ovr", "NONE", "/vsicurl/" + VHM_URL, output]
    subprocess.run(list(map(str, command)), check=True, env=env)
    native = raster_grid(args, output)
    require(native == grid._replace(resolution=0.25, width=2 * grid.width, height=2 * grid.height),
            f"{output}: source cells are not aligned 2:1")
    return native


def prepare_points(args, site, dem, folder):
    buffer = 16
    west, south = site.west - buffer, site.south - buffer
    east, north = site.west + site.width * RESOLUTION + buffer, site.south + site.height * RESOLUTION + buffer
    output = folder / "vegetation-hag.las"
    config = folder / "crop.json"
    stages = [
        {"type": "readers.las", "filename": str(args.data / site.laz), "threads": 4},
        {"type": "filters.expression", "expression":
         f"X >= {west} && X < {east} && Y >= {south} && Y < {north}"
         " && Classification >= 3 && Classification <= 5 && Withheld == 0"},
        {"type": "filters.hag_dem", "raster": str(dem), "zero_ground": False, "min_clamp": -1e100},
        {"type": "filters.expression", "expression": "HeightAboveGround >= 5"},
        {"type": "writers.las", "filename": str(output), "forward": "all", "extra_dims": "HeightAboveGround=double"},
    ]
    config.write_text(json.dumps(stages, indent=2) + "\n")
    run([args.pdal, "pipeline", config, "--stream"])
    return output


def core_windows(site, grid):
    dx = (site.west - grid.west) / RESOLUTION
    dy = (grid.north - site.south) / RESOLUTION - site.height
    require(dx.is_integer() and dy.is_integer(), f"{site.name}: core grid does not align with surface")
    row, col = int(dy), int(dx)
    require(min(row, col, grid.height - row - site.height, grid.width - col - site.width) >= 6,
            f"{site.name}: surface needs a 3 m halo")
    return slice(row, row + site.height), slice(col, col + site.width)


def within_core(site, east, north):
    return (site.west <= east < site.west + site.width * RESOLUTION
            and site.south <= north < site.south + site.height * RESOLUTION)


def local_maxima_3m(values, core):
    rows, cols = core
    centre = values[core]
    keep = np.isfinite(centre)
    for dy in range(-6, 7):
        for dx in range(-6, 7):
            if not 0 < dx * dx + dy * dy <= 36:
                continue
            neighbor = values[rows.start + dy:rows.stop + dy, cols.start + dx:cols.stop + dx]
            keep &= (centre > neighbor) if (dy, dx) < (0, 0) else (centre >= neighbor)
    return [(rows.start + int(row), cols.start + int(col)) for row, col in np.argwhere(keep)]


def cell_winners(path, values, kind):
    winners = {}
    with path.open(newline="") as stream:
        for record in csv.DictReader(stream):
            cell = int(record["row"]), int(record["col"])
            winner = tuple(record[field] for field in POINT_FIELDS)
            require(cell not in winners and float(record[kind]) == values[cell]
                    and float(record["height_above_ground"]) >= 5, f"{path}: inconsistent winner in {cell}")
            winners[cell] = winner
    require(len(winners) == int(np.isfinite(values).sum()), f"{path}: incomplete winner coverage")
    return winners


def write_points(path, points, names=POINT_FIELDS):
    with path.open("w", newline="") as stream:
        writer = csv.writer(stream, lineterminator="\n")
        writer.writerow(("tree_id", *names))
        writer.writerows((index, *row) for index, row in enumerate(points, 1))


def raster_points(site, grid, cells, winners, target):
    supported, unsupported = [], []
    require(len(cells) == len(set(cells)), f"{target}: duplicate cells")
    for row, col in sorted(cells):
        centre = grid.center(row, col)
        if not within_core(site, *centre):
            continue
        point = winners.get((row, col))
        if point is None:
            unsupported.append(centre)
        else:
            require(within_core(site, float(point[0]), float(point[1])), f"{target}: peak not in its owning core")
            supported.append(point)
    write_points(target, supported)
    write_points(target.with_name(target.stem + "-unsupported.csv"), unsupported, POINT_FIELDS[:2])
    return {"status": "completed", "supported": len(supported), "unsupported": len(unsupported)}


def compare_site(args, site, output):
    java = args.surfaces / site.name
    dem = args.forest_dem if site.dem == "forest.tif" else args.work / "dem" / site.dem
    require(dem.is_file(), f"{site.name}: missing terrain at {dem}")
    grid = raster_grid(args, java / "chm.tif")
    require(grid.resolution == RESOLUTION and raster_grid(args, java / "dsm.tif") == grid,
            f"{site.name}: Java surfaces must share a 0.5 m grid")
    core = core_windows(site, grid)
    winners, raw = {}, {}
    for surface, field in (("chm", "height_above_ground"), ("dsm", "z")):
        raw[surface] = read_raster(args, java / f"{surface}.tif", grid)
        winners[surface] = cell_winners(java / f"{surface}-cells.csv", raw[surface], field)
        reference = EXPERIMENT["output_rows"](java / f"{surface}-r3.csv")
        cells = local_maxima_3m(raw[surface], core)
        require([winners[surface][cell] for cell in cells] == reference,
                f"{site.name}: circular 3 m control differs from Java ({surface})")

    rscript = ["Rscript", "--vanilla", ROOT / "experiments/check-treetops-external.R"]
    env = dict(os.environ, R_LIBS_USER=os.pathsep.join(str(lib) for lib in args.r_library), RGL_USE_NULL="TRUE",
               OMP_NUM_THREADS="1", VECLIB_MAXIMUM_THREADS="1")
    result = {}
    with tempfile.TemporaryDirectory(dir=output) as folder:
        temp = Path(folder)
        vhm25 = temp / "vhm25.tif"
        native = prepare_vhm(args, grid, vhm25)
        points = prepare_points(args, site, dem, temp)
        subprocess.run([*rscript, "surfaces", java, vhm25, dem, points, output], env=env, check=True)
        native_values = read_raster(args, vhm25, native)
        aggregate = native_values.reshape(grid.height, 2, grid.width, 2).max(axis=(1, 3))
        result["surfaces"] = {}
        for name in ("vhm", "lspikefree"):
            surface = output / f"{name}.tif"
            require(raster_grid(args, surface) == grid, f"{surface}: grid does not align with Java")
            values = read_raster(args, surface, grid)
            if name == "vhm":
                require(np.array_equal(values, aggregate), f"{surface}: wrong 2×2 maximum or NoData mask")
            values = np.where(values >= 5, values, -np.inf)
            available = np.isfinite(values)
            valid_raw = np.isfinite(raw["chm"])
            result["surfaces"][name] = {"valid_cells_above_5m": int(available.sum()),
                                         "different_mask_cells": int((available != valid_raw).sum())}
            method = f"{name}-r3"
            result[method] = raster_points(site, grid, local_maxima_3m(values, core), winners["chm"],
                                           output / f"{method}.csv")
        with (output / "lspikefree-input.csv").open(newline="") as stream:
            density = next(csv.DictReader(stream))
            result["surfaces"]["lspikefree"].update(first_returns=int(density["first_returns"]),
                                                   nominal_spacing_m=float(density["spacing_m"]))

        with (output / "lidartree-cells.csv").open(newline="") as stream:
            reported = [(record["method"], int(record["cell"]) - 1) for record in csv.DictReader(stream)]
        require(all(0 <= cell < grid.width * grid.height for _, cell in reported), "lidaRtRee returned invalid cells")
        for method in ("lidartree-chm", "lidartree-dsm"):
            cells = [divmod(cell, grid.width) for reported_method, cell in reported if reported_method == method]
            result[method] = raster_points(site, grid, cells, winners[method.split("-")[1]],
                                           output / f"{method}.csv")

        apexes, counts = temp / "li2012-apexes.csv", temp / "li2012-counts.csv"
        try:
            subprocess.run([*rscript, "li2012", points, apexes, counts], env=env, check=True, timeout=120)
        except subprocess.TimeoutExpired:
            result["li2012"] = {"status": "timeout", "seconds": 120}
        else:
            with apexes.open(newline="") as stream:
                accepted = [tuple(record[field] for field in POINT_FIELDS) for record in csv.DictReader(stream)
                            if within_core(site, float(record["easting"]), float(record["northing"]))]
            with counts.open(newline="") as stream:
                details = {name: int(value) for name, value in next(csv.DictReader(stream)).items()}
            write_points(output / "li2012.csv", sorted(accepted, key=lambda row: (-float(row[1]), float(row[0]))))
            result["li2012"] = {"status": "completed", "supported": len(accepted), "unsupported": 0, **details}
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("pdal", type=Path)
    parser.add_argument("--data", type=Path, default=ROOT / ".cache/test-data")
    parser.add_argument("--work", type=Path, default=ROOT / ".cache/treetops-prominence")
    parser.add_argument("--surfaces", type=Path)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--forest-dem", type=Path, default=ROOT / ".cache/hag-dem/dem/forest.tif")
    parser.add_argument("--r-library", type=Path, action="append", help="repeat for each isolated R library")
    parser.add_argument("--site", action="append", choices=[site.name for site in KNOWN + BLIND],
                        help="repeat to select plots; default covers development plots only")
    args = parser.parse_args()
    for attr in ("pdal", "data", "work", "forest_dem"):
        setattr(args, attr, getattr(args, attr).resolve())
    args.gdal = args.pdal.parent
    args.surfaces = (args.surfaces or args.work / "comparison").resolve()
    args.output = (args.output or args.work / "external").resolve()
    args.r_library = [lib.resolve() for lib in (args.r_library or [args.work / "r-library",
                                                                  ROOT / ".cache/treetops/r-library"])]
    names = set(args.site or (site.name for site in KNOWN))
    args.output.mkdir(parents=True, exist_ok=True)
    for site in KNOWN + BLIND:
        if site.name not in names:
            continue
        # Publish one complete experiment result per plot. Reproduction uses a fresh output directory.
        with tempfile.TemporaryDirectory(dir=args.output) as temp:
            pending = Path(temp) / site.name
            pending.mkdir()
            summary = compare_site(args, site, pending)
            (pending / "external.json").write_text(json.dumps(summary, indent=2) + "\n")
            pending.rename(args.output / site.name)
        print(f"{site.name}: {json.dumps(summary)}", flush=True)


if __name__ == "__main__":
    main()
