"""Measure tiny DTM and full ground-LAS startup with the same JDK and an optional AOT cache."""

import argparse
import csv
from datetime import datetime
import json
import os
from pathlib import Path
import runpy
import re
import statistics
import struct
import subprocess
import tempfile
import time

ROOT = Path(__file__).resolve().parents[1]
DTM = runpy.run_path(str(ROOT / "tools/check-dtm.py"))
require = DTM["require"]
POINT = struct.Struct("<iiiHBBbBH")


def fixture(path, side):
    """Build a 5-point plane or a million-point regular projected LAS in scratch."""
    if side == 0:
        coordinates = [(0, 0), (400, 0), (0, 400), (400, 400), (200, 100)]
        count, extent = len(coordinates), 400
    else:
        coordinates = ((x * 20, y * 20) for y in range(side) for x in range(side))
        count, extent = side * side, 20 * (side - 1)
    keys = struct.pack("<20H", 1, 1, 0, 4, 1024, 0, 1, 1, 1025, 0, 1, 1, 3072, 0, 1, 2056,
                       3076, 0, 1, 9001)
    vlr = struct.pack("<H16sHH32s", 0, b"LASF_Projection", 34735, len(keys), b"GeoKeyDirectoryTag")
    header = bytearray(227)
    origin_x, origin_y = 2606740, 1229150
    header[:4] = b"LASF"
    header[24:26] = b"\x01\x02"
    struct.pack_into("<HIIBH6I", header, 94, len(header), len(header) + len(vlr) + len(keys), 1, 0,
                     POINT.size, count, count, 0, 0, 0, 0)
    struct.pack_into("<12d", header, 131, .01, .01, .01, origin_x, origin_y, 500,
                     origin_x + extent / 100, origin_x, origin_y + extent / 100, origin_y,
                     500 + 2 * extent / 100, 500)
    with path.open("wb") as output:
        output.write(header + vlr + keys)
        for x, y in coordinates:
            output.write(POINT.pack(x, y, x + y, 0, 9, 2, 0, 0, 0))


def configure(work, mode, source):
    if mode in ("tiny", "million"):
        writer = {"type": "writers.dtm", "resolution": 1, "origin_x": 2606740, "origin_y": 1229150,
                  "width": 3 if mode == "tiny" else 200, "height": 3 if mode == "tiny" else 200}
    else:
        writer = json.loads((ROOT / f"examples/dtm-solothurn-{mode}.json").read_text())["pipeline"][-1]
    config, target, metadata = (work / name for name in (f"{mode}.json", f"{mode}.tif", f"{mode}-metadata.json"))
    config.write_text(json.dumps([{"type": "readers.las", "filename": str(source)},
                                  {"type": "filters.stats", "dimensions": "Z"},
                                  {**writer, "filename": str(target)}]))
    return config, target, metadata


def command(args, cp, config, metadata, vm=()):
    return [str(args.jdk / "java"), "-Xmx512m", *vm, "-cp", cp, "pointcloud.Main", "pipeline", str(config),
            "--metadata", str(metadata), "--overwrite"]


def classpath(cli, helper=None):
    jars = sorted((cli.parent.parent / "lib").glob("*.jar"))
    require(jars, f"No installed distribution for {cli}")
    if helper is not None:
        jars.append(helper)
    return os.pathsep.join(map(str, jars))


def assets(args):
    args.work = args.work.resolve()
    args.work.mkdir(parents=True, exist_ok=True)
    args.jdk = (Path(os.environ["JAVA_HOME"]) / "bin").resolve()
    args.execute, args.timing = DTM["GRID"]["timing_functions"]()
    args.env = os.environ.copy()


def stress(args):
    root = Path(tempfile.mkdtemp(prefix="million-", dir=args.work))
    ground = root / "million.las"
    fixture(ground, 1000)
    config, target, meta = configure(root, "million", ground)
    for label, cli in (("semi", args.baseline_cli), ("standard", args.standard_cli)):
        argv = list(map(str, [cli, "pipeline", config, "--metadata", meta, "--overwrite"]))
        wall, user, sys, rss = DTM["GRID"]["measure"](argv, args.execute, args.timing,
                                                    dict(args.env, JAVA_OPTS="-Xmx512m"))
        data = json.loads(meta.read_text())
        require(data["dtm"] == {"supportPoints": 1_000_000, "cellsCovered": 40_000, "cellsNoData": 0},
                "Million-point mesh has missing points or cells")
        area = DTM["Area"]("million", 1_000_000, 1_000_000, 1_000_000, 0)
        samples, = DTM["read_bands"](args.gdal / "gdal_translate", target, area, DTM["PROFILE"])
        for index, sample in enumerate(samples):
            col, row = index % 200, index // 200
            require(abs(sample - (700 + col - row)) <= 1e-9, f"Million-point plane differs at {index}")
        print(f"{label}: {wall:.3f}s wall, {user+sys:.3f}s CPU, {rss} bytes RSS, {data['dtm']}", flush=True)


def trace_methods(args, cp, config, meta, root, label, methods):
    trace = root / f"{label}.jfr"
    options = (f"-XX:StartFlightRecording=settings=profile,filename={trace},"
               "jdk.MethodTrace#enabled=true,jdk.MethodTrace#threshold=0,"
               f"jdk.MethodTrace#filter={';'.join(methods)}")
    subprocess.run(command(args, cp, config, meta, [options]), stdout=subprocess.DEVNULL, check=True)
    raw = subprocess.check_output([args.jdk / "jfr", "print", "--json", "--events", "jdk.MethodTrace", trace], text=True)
    return {f"{event['values']['method']['type']['name'].split('/')[-1]}::{event['values']['method']['name']}":
            event["values"] for event in json.loads(raw)["recording"]["events"]}


TIFF_METHODS = ["pointcloud.GeoTiffOutput::<init>", "pointcloud.GeoTiffOutput::prepare"]


def seconds(event):
    return float(event["duration"].removeprefix("PT").removesuffix("S"))


def instant(event):
    stamp = re.sub(r"(\.\d{6})\d+(?=[+-])", r"\1", event["startTime"])
    return datetime.fromisoformat(stamp).timestamp()


def phases(args):
    root = Path(tempfile.mkdtemp(prefix="phases-", dir=args.work))
    cp = classpath(args.phase_cli.resolve())
    methods = [*TIFF_METHODS, "pointcloud.TerrainWriter::prepare", "pointcloud.TerrainWriter::rasterize",
               "org.tinfour.semivirtual.SemiVirtualIncrementalTin::add",
               "org.tinfour.standard.IncrementalTin::add"]
    if args.fill_nearest:
        methods.append("pointcloud.TerrainWriter::fillNearest")
    for area in ("urban", "forest"):
        config, _, meta = configure(root, area, args.ground_dir.resolve() / area / "ground.las")
        if args.fill_nearest:
            stages = json.loads(config.read_text())
            stages[-1].update(fill_nodata="nearest", max_triangle_edge_length=20 if area == "urban" else 5)
            config.write_text(json.dumps(stages))
        rounds = []
        for index in range(3):
            events = trace_methods(args, cp, config, meta, root, f"{area}-{index}", methods)
            writer = events["GeoTiffOutput::<init>"]
            prepare = events["TerrainWriter::prepare"]
            scan = instant(prepare) - instant(writer) - seconds(writer)
            build = sum(seconds(events[key]) for key in ("SemiVirtualIncrementalTin::add", "IncrementalTin::add")
                        if key in events)
            rounds.append({"crs": seconds(writer), "scan": scan, "insert": build,
                           "mesh_and_raster": seconds(events["TerrainWriter::rasterize"]),
                           "tiff": seconds(events["GeoTiffOutput::prepare"])})
            if args.fill_nearest:
                rounds[-1]["nearest"] = seconds(events["TerrainWriter::fillNearest"])
        print(area, {key: statistics.median(row[key] for row in rounds) for key in rounds[0]}, flush=True)


def runtime(args):
    root = Path(tempfile.mkdtemp(prefix="runtime-", dir=args.work))
    ground = root / "tiny.las"
    fixture(ground, 0)
    cases = {name: configure(root, name, source) for name, source in (
        ("tiny", ground), ("urban", args.ground_dir.resolve() / "urban/ground.las"),
        ("forest", args.ground_dir.resolve() / "forest/ground.las"))}
    classes = root / "classes"
    jar = root / "warmup.jar"
    cp = classpath(args.baseline_cli.resolve(), jar)
    geokey_cp = classpath(args.geokey_cli.resolve(), jar)
    subprocess.run([args.jdk / "javac", "-cp", cp, "-d", classes, ROOT / "experiments/TinRuntimeWarmup.java"], check=True)
    subprocess.run([args.jdk / "jar", "--create", "--file", jar, "-C", classes, "."], check=True)
    aot_config, cache = root / "tin.aotconfig", root / "tin.aot"
    helper = [args.jdk / "java", "-Xmx512m", "-cp", cp, "pointcloud.TinRuntimeWarmup"]
    _, tiny_tiff, tiny_meta = cases["tiny"]
    tiny_config = cases["tiny"][0]

    expected = {}
    for name, (config, target, meta) in cases.items():
        subprocess.run(command(args, cp, config, meta), check=True)
        expected[name] = (target.read_bytes(), meta.read_bytes())

    for label, class_path in (("baseline", cp), ("geokey", geokey_cp)):
        events = trace_methods(args, class_path, tiny_config, tiny_meta, root, label, TIFF_METHODS)
        print(label, "tiny JFR", {key: seconds(event) for key, event in events.items()}, flush=True)
    for index in range(3):
        output = subprocess.check_output([*helper, "4", tiny_config, tiny_meta], text=True)
        print(f"warm JVM {index + 1}\n{output.strip()}", flush=True)
        require(expected["tiny"] == (tiny_tiff.read_bytes(), tiny_meta.read_bytes()), "Warm DTM changed")

    training = [obj for config, _, meta in cases.values() for obj in (config, meta)]
    for label, argv in (
        ("record", [*helper[:2], "-XX:AOTMode=record", f"-XX:AOTConfiguration={aot_config}", *helper[2:], "1", *training]),
        ("create", [args.jdk / "java", "-Xmx512m", "-XX:AOTMode=create", f"-XX:AOTConfiguration={aot_config}",
                    f"-XX:AOTCache={cache}", "-cp", cp]),
    ):
        started = time.perf_counter()
        with (root / f"training-{label}.log").open("w") as log:
            subprocess.run(argv, stdout=log, stderr=subprocess.STDOUT, check=True)
        print(f"AOT {label}: {time.perf_counter() - started:.3f}s", flush=True)

    aot = ["-XX:AOTMode=on", f"-XX:AOTCache={cache}"]
    log = subprocess.run(command(args, cp, tiny_config, tiny_meta, [*aot, "-Xlog:aot=info"]),
                         check=True, capture_output=True, text=True)
    (root / "aot-use.log").write_text(log.stdout + log.stderr)
    print(f"AOT cache: {cache.stat().st_size} bytes, log: {root}/aot-use.log", flush=True)

    with args.csv.open("w", newline="") as file:
        out = csv.writer(file)
        out.writerow(("case", "round", "engine", "wall_s", "user_s", "system_s", "rss_bytes"))
        for name, (config, target, meta) in cases.items():
            variants = [("baseline", cp, []), ("aot", cp, aot)]
            if name == "tiny":
                variants.append(("geokey", geokey_cp, []))
            series = {key: [] for key, _, _ in variants}
            for index in range(8):
                offset = index % len(variants)
                for label, class_path, options in variants[offset:] + variants[:offset]:
                    wall, user, sys, rss = DTM["GRID"]["measure"](command(args, class_path, config, meta, options),
                                                                 args.execute, args.timing, args.env)
                    require(expected[name] == (target.read_bytes(), meta.read_bytes()), f"{name}: {label} changed output")
                    series[label].append(wall)
                    out.writerow((name, index + 1, label, f"{wall:.9f}", user, sys, rss))
                file.flush()
            print(name, {label: round(statistics.median(values), 4) for label, values in series.items()}, flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--baseline-cli", required=True, type=Path)
    parser.add_argument("--geokey-cli", type=Path)
    parser.add_argument("--standard-cli", type=Path)
    parser.add_argument("--phase-cli", type=Path)
    parser.add_argument("--fill-nearest", action="store_true", help="trace capped, complete DTM runs")
    parser.add_argument("--ground-dir", type=Path)
    parser.add_argument("--gdal", type=Path, default=Path("."))
    parser.add_argument("--work", type=Path, required=True)
    parser.add_argument("--csv", type=Path)
    args = parser.parse_args()
    assets(args)
    if args.phase_cli:
        require(args.ground_dir, "Phase measurements need the common ground LAS")
        phases(args)
    elif args.standard_cli:
        stress(args)
    else:
        require(args.geokey_cli and args.ground_dir and args.csv, "Runtime comparison needs geokey, ground and csv paths")
        runtime(args)


if __name__ == "__main__":
    main()
