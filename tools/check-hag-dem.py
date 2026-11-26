"""Verify HAG over the two full Solothurn point clouds against PDAL 2.10.2."""

import argparse
import csv
import filecmp
import hashlib
import json
import math
import os
from pathlib import Path
import runpy
import statistics
import subprocess
import tempfile
from typing import NamedTuple


GRID = runpy.run_path(str(Path(__file__).with_name("check-ground-grid.py")))
REFERENCE = GRID["REFERENCE"]
require = REFERENCE["require"]
run = REFERENCE["run"]
compare_stats = REFERENCE["compare_stats"]
DEM_URL = "https://files.geo.so.ch/ch.so.agi.lidar_2019.dtm/aktuell/ch.so.agi.lidar_2019.dtm.tif"
ROUNDS = 8


class Area(NamedTuple):
    name: str
    laz: str
    points: int
    west: int
    north: int
    size: tuple = (502, 502)


AREAS = (
    Area("urban", "so-2019-urban.laz", 11_963_266, 2606499, 1229501),
    Area("forest", "so-2019.laz", 14_566_627, 2593999, 1228001),
)


def validate_dem(gdal, area, target, work):
    info = run([gdal / "gdalinfo", "-json", target], capture=True)
    require(info["driverShortName"] == "GTiff" and tuple(info["size"]) == area.size
            and info["geoTransform"] == [area.west, 1, 0, area.north, 0, -1]
            and info["metadata"].get("IMAGE_STRUCTURE", {}).get("COMPRESSION", "NONE") == "NONE",
            f"{target}: unexpected DEM format, geometry or compression")
    crs = subprocess.check_output([gdal / "gdalsrsinfo", "-o", "epsg", target], text=True)
    require(crs.strip() == "EPSG:2056" and info["coordinateSystem"]["dataAxisToSRSAxisMapping"] == [1, 2],
            f"{target}: expected LV95 with easting, northing")
    bands = info["bands"]
    require(len(bands) == 1 and bands[0]["type"] == "Float64", f"{target}: expected one Float64 band")
    with tempfile.TemporaryDirectory(dir=work) as folder:
        linked = Path(folder) / target.name
        linked.symlink_to(target)
        values, = GRID["read_bands"](gdal / "gdal_translate", linked, area,
                                    GRID["RasterProfile"]("hag-dem", 0, ("elevation",)))
    require(all(math.isfinite(value) and value != bands[0].get("noDataValue") for value in values),
            f"{target}: DEM has nonfinite or NoData cells")


def prepare_dem(args, area, work):
    target = args.dem_dir / f"{area.name}.tif"
    if target.exists():
        validate_dem(args.gdal, area, target, work)
    else:
        target.parent.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(dir=target.parent) as folder:
            pending = Path(folder) / target.name
            env = dict(os.environ, GDAL_DISABLE_READDIR_ON_OPEN="EMPTY_DIR", GDAL_PAM_ENABLED="NO")
            window = (area.west, area.north, area.west + area.size[0], area.north - area.size[1])
            subprocess.run([args.gdal / "gdal_translate", "-q", "-of", "GTiff", "-projwin",
                            *map(str, window), "-tr", "1", "1", "-r", "average", "-ovr", "NONE",
                            "-ot", "Float64", "-co", "COMPRESS=NONE", "/vsicurl/" + DEM_URL, pending],
                           env=env, check=True)
            validate_dem(args.gdal, area, pending, work)
            pending.replace(target)
    print(f"{area.name} DEM: {math.prod(area.size)} valid Float64 samples, {target.stat().st_size} B, "
          f"SHA-256 {hashlib.sha256(target.read_bytes()).hexdigest()}", flush=True)
    return target


def configure(args, work, source, dem, engine, workers, *, select=False, dimensions="Z,HeightAboveGround"):
    config, metadata, las = (work / name for name in ("pipeline.json", "metadata.json", "selected.las"))
    stages = [{"type": "readers.las", "filename": str(source)}]
    if dem is not None:
        hag = {"type": "filters.hag_dem", "raster": str(dem), "zero_ground": False}
        if engine == "pdal":
            hag["min_clamp"] = -1e100
        stages.append(hag)
    stages.append({"type": "filters.stats", "dimensions": dimensions})
    if select:
        stages.append({"type": "filters.expression", "expression": "HeightAboveGround > 5"})
        writer = {"type": "writers.las", "filename": str(las), "forward": "all", "compression": False}
        if engine == "java":
            writer["extra_dims"] = "all"
        stages.append(writer)
    else:
        stages.append({"type": "writers.null"})
    config.write_text(json.dumps(stages))
    if engine == "java":
        command = [args.cli, "pipeline", config, "--laz-workers", workers, "--metadata", metadata]
    else:
        command = [args.pdal, "pipeline", config, "--stream", f"--readers.las.threads={workers}",
                   "--metadata", metadata]
    return list(map(str, command)), metadata, las


def acceptance(args, area, work, source, dem):
    results = {}
    for engine, workers in (("java", 1), ("java", 4), ("pdal", 4)):
        case = work / f"{engine}-{workers}"
        case.mkdir()
        command, metadata, las = configure(args, case, source, dem, engine, workers, select=True)
        run(command)
        results[engine, workers] = json.loads(metadata.read_text()), las
    reference, serial = results["java", 1]
    parallel, parallel_las = results["java", 4]
    pdal, pdal_las = results["pdal", 4]
    stages = pdal["stages"]
    require(reference == parallel and filecmp.cmp(serial, parallel_las, shallow=False),
            f"{area.name}: Java LAS or metadata differs between one and four workers")
    require(reference["pointsRead"] == stages["readers.las"]["count"] == area.points,
            f"{area.name}: input count differs")
    count = reference["pointsSelected"]
    require(0 < count < area.points and reference["pointsWritten"] == count,
            f"{area.name}: unexpected HAG selection count {count}")
    compare_stats(reference["statistics"], stages["filters.stats"]["statistic"], area.points)
    dimensions = reference["statistics"][0]["dimensions"]
    hag = next(d for d in dimensions if d["name"] == "HeightAboveGround")
    require(hag["minimum"] < 0 and hag["maximum"] > 5,
            f"{area.name}: signed HAG range or selection threshold not exercised")
    REFERENCE["compare_las"](serial, pdal_las, count)
    vlrs = REFERENCE["projection_and_other_vlrs"]
    require(vlrs(serial) == vlrs(source), f"{area.name}: Java did not preserve source VLRs")
    summaries = [run([args.pdal, "info", "--summary", las], capture=True)["summary"] for las in (serial, pdal_las)]
    require(summaries[0].get("srs") and summaries[0]["srs"] == summaries[1].get("srs"),
            f"{area.name}: CRS differs between Java and PDAL outputs")
    print(f"{area.name}: Java 1/4 bytewise equal, {count}/{area.points} ordered points have HAG > 5 m, "
          f"signed range {hag['minimum']:.6f}…{hag['maximum']:.6f} m", flush=True)
    for path in (serial, parallel_las, pdal_las):
        path.unlink()
    return dimensions, stages["filters.stats"]["statistic"]


def benchmark(args, profiles):
    execute, timing = GRID["timing_functions"]()
    env = dict(os.environ, JAVA_OPTS="-Xmx512m")
    args.benchmark_csv.parent.mkdir(parents=True, exist_ok=True)
    with args.benchmark_csv.open("w", newline="") as report:
        writer = csv.writer(report)
        writer.writerow(("area", "profile", "round", "position", "engine", "workers", "points",
                         "wall_s", "user_s", "system_s", "rss_bytes", "points_per_s"))
        for control in (False, True):
            variants = (("java", 1, False), ("java", 1, True), ("java", 4, False), ("java", 4, True)) if control else (
                ("java", 1, True), ("pdal", 1, True), ("java", 4, True), ("pdal", 4, True))
            dimensions = "Z" if control else "Z,HeightAboveGround"
            rounds = 5 if control else ROUNDS
            for area, work, source, dem, (java, pdal) in profiles:
                for artifact in (source, dem):
                    with artifact.open("rb") as stream:
                        while stream.read(1 << 20):
                            pass
                for index in range(rounds):
                    start = index % len(variants)
                    order = variants[start:] + variants[:start]
                    samples = []
                    for position, (engine, workers, with_hag) in enumerate(order, 1):
                        label = ("z-with-hag" if with_hag else "z-no-hag") if control else "hag-stats"
                        with tempfile.TemporaryDirectory(dir=work) as folder:
                            command, metadata, _ = configure(args, Path(folder), source, dem if with_hag else None,
                                                              engine, workers, dimensions=dimensions)
                            wall, user, system, rss = GRID["measure"](command, execute, timing, env)
                            result = json.loads(metadata.read_text())
                        if engine == "java":
                            expected = [d for d in java if d["name"] == "Z"] if control else java
                            require((result["pointsRead"], result["pointsSelected"], result["pointsWritten"])
                                    == (area.points, area.points, 0)
                                    and result["statistics"][0]["dimensions"] == expected,
                                    f"{area.name} {label}: timed Java pipeline output differs")
                        else:
                            stages = result["stages"]
                            require(stages["readers.las"]["count"] == area.points
                                    and stages["filters.stats"]["statistic"] == pdal,
                                    f"{area.name}: timed PDAL pipeline output differs")
                        writer.writerow((area.name, label, index + 1, position, engine, workers, area.points,
                                         f"{wall:.9f}", user, system, rss, f"{area.points / wall:.1f}"))
                        report.flush()
                        samples.append(f"{engine}-{workers} {label} {wall:.3f}s")
                    print(f"{area.name} {index + 1}/{rounds}  " + ", ".join(samples), flush=True)


def measure_setup(args, profiles):
    jfr = Path(os.environ["JAVA_HOME"]) / "bin" / "jfr" if "JAVA_HOME" in os.environ else "jfr"
    trace = ("-Xmx512m -XX:StartFlightRecording=settings=profile,filename=load.jfr,"
             "jdk.MethodTrace#enabled=true,jdk.MethodTrace#threshold=0,"
             "jdk.MethodTrace#filter=pointcloud.HagDem::load")
    env = dict(os.environ, JAVA_OPTS=trace)
    args.setup_csv.parent.mkdir(parents=True, exist_ok=True)
    with args.setup_csv.open("w", newline="") as report:
        writer = csv.writer(report)
        writer.writerow(("area", "round", "method", "seconds"))
        for area, work, source, dem, _ in profiles:
            durations = []
            for iteration in range(1, 4):
                case = work / f"jfr-{iteration}"
                case.mkdir()
                command, _, _ = configure(args, case, source, dem, "java", 4)
                subprocess.run(command, env=env, cwd=case, check=True)
                events = json.loads(subprocess.check_output(
                    [jfr, "print", "--json", "--events", "jdk.MethodTrace", case / "load.jfr"], text=True))
                values = [event["values"] for event in events["recording"]["events"]]
                matches = [event["duration"] for event in values
                           if event["method"]["type"]["name"] == "pointcloud/HagDem"
                           and event["method"]["name"] == "load"]
                require(len(matches) == 1, f"{case}: expected one trace of HagDem.load")
                duration = float(matches[0].removeprefix("PT").removesuffix("S"))
                durations.append(duration)
                writer.writerow((area.name, iteration, "HagDem.load", f"{duration:.9f}"))
            print(f"{area.name}: median DEM setup {statistics.median(durations):.6f} s (separate JFR runs)", flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("pdal", type=Path)
    parser.add_argument("--cli", type=Path, default=Path("build/install/pointcloud/bin/pointcloud"))
    parser.add_argument("--gdal", type=Path, help="GDAL tool directory (default: alongside PDAL)")
    parser.add_argument("--data", type=Path, default=Path(".cache/test-data"))
    parser.add_argument("--dem-dir", type=Path, default=Path(".cache/hag-dem/dem"))
    parser.add_argument("--work", type=Path, default=Path(".cache/hag-dem"))
    parser.add_argument("--benchmark-csv", type=Path, help="also time full-tile HAG and Java Z control pipelines")
    parser.add_argument("--setup-csv", type=Path, help="also trace DEM load separately after any benchmark")
    args = parser.parse_args()
    args.pdal, args.cli, args.data, args.dem_dir, args.work = (
        path.resolve() for path in (args.pdal, args.cli, args.data, args.dem_dir, args.work))
    args.gdal = args.gdal.resolve() if args.gdal else args.pdal.parent
    require("pdal 2.10.2 " in subprocess.check_output([args.pdal, "--version"], text=True),
            "This comparison requires PDAL 2.10.2")
    args.work.mkdir(parents=True, exist_ok=True)
    work = Path(tempfile.mkdtemp(prefix="run-", dir=args.work))
    print(f"HAG DEM outputs: {work}", flush=True)
    profiles = []
    for area in AREAS:
        section = work / area.name
        section.mkdir()
        source = args.data / area.laz
        require(source.is_file(), f"Missing test data: {source}")
        dem = prepare_dem(args, area, section)
        profiles.append((area, section, source, dem, acceptance(args, area, section, source, dem)))
    if args.benchmark_csv:
        benchmark(args, profiles)
    if args.setup_csv:
        measure_setup(args, profiles)


if __name__ == "__main__":
    main()
