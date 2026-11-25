"""Compare the Solothurn ground raster with PDAL 2.10.2 and GDAL."""

import argparse
import json
import math
from pathlib import Path
import re
import runpy
import shutil
import struct
import subprocess
import tempfile


REFERENCE = runpy.run_path(str(Path(__file__).with_name("check-real-data.py")))
compare_stats = REFERENCE["compare_stats"]
require = REFERENCE["require"]
run = REFERENCE["run"]

POINTS_READ = 14_566_627
POINTS_SELECTED = 143_244
POINTS_BINNED = 143_238
OCCUPIED_CELLS = 26_114
WIDTH = HEIGHT = 200
NODATA = -9999
BANDS = ("min", "max", "mean", "count")
TRANSFORM = [2594100, 1, 0, 1227800, 0, -1]
MEAN_TOLERANCE = 1e-9


def inspect(gdalinfo, gdalsrsinfo, raster):
    info = json.loads(subprocess.check_output([gdalinfo, "-json", raster], text=True))
    require(info["driverShortName"] == "GTiff", f"{raster.name}: not a GeoTIFF")
    require(info["size"] == [WIDTH, HEIGHT], f"{raster.name}: incorrect grid size")
    require(info["geoTransform"] == TRANSFORM, f"{raster.name}: incorrect north-up transform {info['geoTransform']}")
    require(info["metadata"][""].get("AREA_OR_POINT") == "Area", f"{raster.name}: incorrect pixel reference")
    crs = subprocess.check_output([gdalsrsinfo, "-o", "epsg", raster], text=True).strip()
    require(crs == "EPSG:2056" and info["coordinateSystem"]["dataAxisToSRSAxisMapping"] == [1, 2],
            f"{raster.name}: incorrect horizontal CRS or axis mapping")
    require(len(info["bands"]) == len(BANDS), f"{raster.name}: incorrect band count")
    for band, name in zip(info["bands"], BANDS):
        require(band.get("description") == name, f"{raster.name}: incorrect band description for {name}")
        require(band["type"] == "Float64" and band.get("noDataValue") == NODATA,
                f"{raster.name}: incorrect dtype or NoData in {name} band")


def read_bands(translate, raster):
    # Band-sequential ENVI conveys unrounded IEEE doubles and declares byte order.
    raw = raster.with_suffix(".bin")
    subprocess.run([translate, "-q", "-of", "ENVI", "-ot", "Float64", "-co", "INTERLEAVE=BSQ", raster, raw],
                   check=True)
    header = raw.with_suffix(".hdr").read_text()
    byte_order = re.search(r"^byte order\s*=\s*([01])\s*$", header, re.MULTILINE)
    require(byte_order is not None, f"{raster.name}: missing ENVI byte order")
    endian = "<" if byte_order[1] == "0" else ">"
    cells = WIDTH * HEIGHT
    data = raw.read_bytes()
    require(len(data) == len(BANDS) * cells * 8, f"{raster.name}: unexpected sample data length")
    return tuple(struct.unpack_from(f"{endian}{cells}d", data, index * cells * 8) for index in range(len(BANDS)))


def compare_values(java, pdal):
    maximum_error = 0.0
    for index, (actual, expected) in enumerate(zip(zip(*java), zip(*pdal))):
        location = f"row {index // WIDTH}, column {index % WIDTH}"
        require(actual[0] == expected[0] and actual[1] == expected[1] and actual[3] == expected[3],
                f"{location}: min, max or count differ: {actual} (Java), {expected} (PDAL)")
        require(math.isclose(actual[2], expected[2], rel_tol=0.0, abs_tol=MEAN_TOLERANCE),
                f"{location}: mean differs: {actual[2]} (Java), {expected[2]} (PDAL)")
        if actual[3] == 0:
            require(actual[:3] == (NODATA,) * 3, f"{location}: empty cell must have NoData in all height bands")
        maximum_error = max(maximum_error, abs(actual[2] - expected[2]))

    count_band = java[-1]
    require(sum(count_band) == POINTS_BINNED, "Incorrect raster point count")
    require(sum(value > 0 for value in count_band) == OCCUPIED_CELLS, "Incorrect occupied cell count")
    print(f"{POINTS_BINNED:,} points in {OCCUPIED_CELLS:,} occupied cells, "
          f"all {WIDTH * HEIGHT:,} cells agree, max mean error {maximum_error:.3g} m.", flush=True)


def check_grid(args, gdalinfo, gdalsrsinfo, translate):
    source = (args.data / "so-2019.laz").resolve()
    require(source.is_file(), f"Missing real-data input: {source}")
    pipeline = json.loads(Path("examples/polygon-ground-grid.json").read_text())
    pipeline["pipeline"][0]["filename"] = str(source)
    with tempfile.TemporaryDirectory(prefix="pointcloud-raster-") as scratch:
        work = Path(scratch)
        config = work / "pipeline.json"
        outputs = []
        for variant in ("serial", "parallel", "pdal"):
            target, metadata = work / f"{variant}.tif", work / f"{variant}.json"
            pipeline["pipeline"][-1]["filename"] = str(target)
            config.write_text(json.dumps(pipeline))
            if variant == "pdal":
                run([args.pdal, "pipeline", config, "--stream", "--readers.las.threads=4", "--metadata", metadata])
            else:
                workers = "1" if variant == "serial" else "4"
                run([args.cli, "pipeline", config, "--metadata", metadata, "--laz-workers", workers])
            inspect(gdalinfo, gdalsrsinfo, target)
            outputs.append((metadata, read_bands(translate, target)))

        (serial_json, serial), (parallel_json, parallel), (pdal_json, pdal) = outputs
        require(serial == parallel, "Serial and parallel Java raster values differ")
        require(serial_json.read_bytes() == parallel_json.read_bytes(), "Serial and parallel Java metadata differ")
        stats = json.loads(serial_json.read_text())
        native = json.loads(pdal_json.read_text())["stages"]
        require(stats["pointsRead"] == native["readers.las"]["count"] == POINTS_READ, "Input read count differs")
        require((stats["pointsSelected"], stats["pointsBinned"], stats["pointsWritten"])
                == (POINTS_SELECTED, POINTS_BINNED, 0), f"Incorrect Java raster point counts: {stats}")
        compare_stats(stats["statistics"], native["filters.stats"]["statistic"], POINTS_SELECTED)
        compare_values(serial, pdal)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("pdal", nargs="?", default="pdal", help="PDAL 2.10.2 executable")
    parser.add_argument("--cli", type=Path, default=Path("build/install/pointcloud/bin/pointcloud"))
    parser.add_argument("--data", type=Path, default=Path(".cache/test-data"), help="directory of cached inputs")
    parser.add_argument("--gdal-bin", type=Path, help="GDAL tools directory (default: next to PDAL or on PATH)")
    args = parser.parse_args()
    pdal = shutil.which(args.pdal)
    if pdal is None:
        parser.error(f"PDAL not found: {args.pdal}")
    args.pdal = pdal
    version = subprocess.check_output([pdal, "--version"], text=True)
    require(re.search(r"^pdal 2\.10\.2(?:\s|$)", version, re.MULTILINE), f"Expected PDAL 2.10.2:\n{version}")
    tools = []
    for name in ("gdalinfo", "gdalsrsinfo", "gdal_translate"):
        sibling = (args.gdal_bin or Path(pdal).parent) / name
        executable = shutil.which(str(sibling))
        if executable is None and args.gdal_bin is None:
            executable = shutil.which(name)
        if executable is None:
            parser.error(f"GDAL tool not found: {name}")
        tools.append(executable)
    check_grid(args, *tools)


if __name__ == "__main__":
    main()
