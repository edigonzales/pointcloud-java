"""Verify and benchmark the Solothurn TIN terrain rasters against PDAL 2.10.2."""

import argparse
import csv
import json
import math
import mmap
import os
from pathlib import Path
import runpy
import struct
import subprocess
import tempfile
from typing import NamedTuple


GRID = runpy.run_path(str(Path(__file__).with_name("check-ground-grid.py")))
require = GRID["require"]
run = GRID["run"]
read_bands = GRID["read_bands"]
compare_stats = GRID["compare_stats"]
NODATA = -9999
TOLERANCE = 1e-6
SIZE = (200, 200)
CELLS = math.prod(SIZE)
PROFILE = GRID["RasterProfile"]("dtm", 0, ("elevation",))
VARIANTS = (("java", 1), ("pdal", 1), ("java", 4), ("pdal", 4))


class Area(NamedTuple):
    name: str
    points_read: int
    selected: int
    support: int
    cap: int
    size: tuple = SIZE


AREAS = (
    Area("urban", 11_963_266, 728_768, 728_751, 20),
    Area("forest", 14_566_627, 428_321, 428_313, 5),
)


def configure(args, work, input_stages, writer, engine, workers, cap):
    target, metadata, config = (work / name for name in ("dtm.tif", "metadata.json", "pipeline.json"))
    if engine == "java":
        output = {**writer, "filename": str(target)}
        if cap is not None:
            output["max_triangle_edge_length"] = cap
        stages = [*input_stages, output]
        command = [args.cli, "pipeline", config, "--laz-workers", workers, "--metadata", metadata]
    else:
        grid = {k: writer[k] for k in ("origin_x", "origin_y", "resolution", "width", "height")}
        face = {"type": "filters.faceraster", **grid, "nodata": NODATA}
        if cap is not None:
            face["max_triangle_edge_length"] = cap
        stages = [*input_stages, {"type": "filters.delaunay"}, face,
                  {"type": "writers.raster", "filename": str(target), "rasters": "faceraster",
                   "data_type": "double", "nodata": NODATA, "gdalopts": "COMPRESS=NONE"}]
        command = [args.pdal, "pipeline", config, "--nostream", f"--readers.las.threads={workers}",
                   "--metadata", metadata]
    config.write_text(json.dumps(stages))
    return list(map(str, command)), target, metadata


def read_result(args, target, metadata, area, writer, engine, points_read):
    info = json.loads(subprocess.check_output([args.gdal / "gdalinfo", "-json", target], text=True))
    transform = [writer["origin_x"], 1, 0, writer["origin_y"] + SIZE[1], 0, -1]
    require(info["driverShortName"] == "GTiff" and tuple(info["size"]) == SIZE
            and info["geoTransform"] == transform, f"{target}: incorrect driver, size or geotransform")
    require(info["metadata"][""].get("AREA_OR_POINT", "Area") == "Area"
            and info["metadata"].get("IMAGE_STRUCTURE", {}).get("COMPRESSION", "NONE") == "NONE",
            f"{target}: incorrect pixel reference or compression")
    crs = subprocess.check_output([args.gdal / "gdalsrsinfo", "-o", "epsg", target], text=True).strip()
    require(crs == "EPSG:2056" and info["coordinateSystem"]["dataAxisToSRSAxisMapping"] == [1, 2],
            f"{target}: incorrect CRS or axis order")
    require(len(info["bands"]) == 1, f"{target}: expected exactly one elevation band")
    band = info["bands"][0]
    require(band["type"] == "Float64" and band.get("noDataValue") == NODATA,
            f"{target}: incorrect elevation sample type or NoData")
    values, = read_bands(args.gdal / "gdal_translate", target, area, PROFILE)
    require(all(map(math.isfinite, values)), f"{target}: nonfinite raster value")
    covered = sum(z != NODATA for z in values)
    result = json.loads(metadata.read_text())
    if engine == "java":
        require(band.get("description") == "elevation", f"{target}: unexpected band name")
        require((result["pointsRead"], result["pointsSelected"], result["pointsWritten"])
                == (points_read, area.selected, 0) and "pointsBinned" not in result,
                f"{metadata}: incorrect point counts")
        require(result["dtm"] == {"supportPoints": area.support, "cellsCovered": covered,
                                 "cellsNoData": CELLS - covered}, f"{metadata}: incorrect DTM metadata")
    else:
        result = result["stages"]
        require(result["readers.las"]["count"] == points_read
                and result["filters.stats"]["statistic"][0]["count"] == area.selected,
                f"{metadata}: incorrect read/selected point counts")
        result = result["filters.stats"]["statistic"]
    return result, values


def grid_center(writer, cell):
    return writer["origin_x"] + cell % SIZE[0] + .5, writer["origin_y"] + SIZE[1] - cell // SIZE[0] - .5


def weights(face, x, y):
    a, b, c = face
    dx, dy = x - a[0], y - a[1]
    bx, by = b[0] - a[0], b[1] - a[1]
    cx, cy = c[0] - a[0], c[1] - a[1]
    area = bx * cy - by * cx
    if area == 0:
        return None
    wb, wc = (dx * cy - dy * cx) / area, (bx * dy - by * dx) / area
    wa = 1 - wb - wc
    return (wa, wb, wc) if min(wa, wb, wc) >= -1e-10 else None


def face_sample(face, position, cap):
    w = weights(face, *position)
    if w is None:
        return None
    if cap is not None and any(math.dist(a[:2], b[:2]) > cap
                               for a, b in zip(face, (*face[1:], face[0]))):
        return NODATA
    return math.fsum(wi * v[2] for wi, v in zip(w, face))


def pdal_faces(mesh, targets):
    matches = {cell: [] for cell in targets}
    xyz, indices = struct.Struct("<ddd"), struct.Struct("<BIII")
    with mesh.open("rb") as stream, mmap.mmap(stream.fileno(), 0, access=mmap.ACCESS_READ) as data:
        header = []
        while (line := data.readline().decode("ascii").strip()) != "end_header":
            header.append(line)
        require(header[1] == "format binary_little_endian 1.0"
                and [line for line in header if line.startswith("property ")] ==
                ["property float64 x", "property float64 y", "property float64 z",
                 "property list uint8 uint32 vertex_indices"], "Unexpected PLY encoding")
        vertex_count = int(next(line.split()[-1] for line in header if line.startswith("element vertex ")))
        start = data.tell()
        points = tuple(xyz.iter_unpack(data[start:start + vertex_count * xyz.size]))
        faces = memoryview(data)[start + vertex_count * xyz.size:]
        try:
            for count, a, b, c in indices.iter_unpack(faces):
                require(count == 3, "Unexpected non-triangular PLY face")
                va, vb, vc = points[a], points[b], points[c]
                min_x, max_x = min(va[0], vb[0], vc[0]), max(va[0], vb[0], vc[0])
                min_y, max_y = min(va[1], vb[1], vc[1]), max(va[1], vb[1], vc[1])
                for cell, (x, y) in targets.items():
                    if min_x <= x <= max_x and min_y <= y <= max_y and weights((va, vb, vc), x, y) is not None:
                        matches[cell].append((va, vb, vc))
        finally:
            faces.release()
    return matches


def inspect_faces(args, work, writer, ground, targets, *, diagnostics=False):
    java_home = os.environ.get("JAVA_HOME")
    java_bin = Path(java_home) / "bin" if java_home else Path(".")
    libraries = args.cli.parent.parent / "lib" / "*"
    classes = work / "classes"
    classes.mkdir(parents=True, exist_ok=True)
    run([java_bin / "javac", "-cp", libraries, "-d", classes,
         getattr(args, "faces_source", "tools/TerrainFaces.java")])
    geometry = [writer[k] for k in ("origin_x", "origin_y", "resolution", "width", "height")]
    report = subprocess.check_output([java_bin / "java", "-Xmx512m",
                                      *(["-Dpointcloud.tin.diagnostics=true"] if diagnostics else []),
                                      "-cp", f"{classes}{os.pathsep}{libraries}",
                                      "pointcloud.TerrainFaces", ground, *map(str, geometry), *map(str, targets)],
                                     text=True)
    faces = {}
    for line in report.splitlines():
        cell, *data = line.split(",")
        coordinates = [float(value) for value in data]
        faces[int(cell)] = tuple(tuple(coordinates[i:i + 3]) for i in range(0, len(coordinates), 3))
    return faces


def compare_surfaces(args, area, writer, work, input_stages, ground, results):
    suspect = set()
    reports = {}
    for cap, (java, pdal) in results.items():
        errors = sorted(abs(a - b) for a, b in zip(java, pdal) if NODATA not in (a, b))
        require(errors, f"{area.name}: no shared elevation cells")
        mask = [i for i, (a, b) in enumerate(zip(java, pdal)) if (a == NODATA) != (b == NODATA)]
        differences = [i for i, (a, b) in enumerate(zip(java, pdal))
                       if NODATA not in (a, b) and abs(a - b) > TOLERANCE]
        if cap is None:
            require(len(errors) == CELLS, f"{area.name}: expected full coverage without cap")
        metrics = {"validJava": sum(a != NODATA for a in java), "validPdal": sum(b != NODATA for b in pdal),
                   "differentMask": len(mask), "aboveTolerance": len(differences),
                   "mae": math.fsum(errors) / len(errors),
                   "rmse": math.sqrt(math.fsum(error * error for error in errors) / len(errors)),
                   "p95": errors[math.ceil(.95 * len(errors)) - 1], "max": errors[-1]}
        print(f"{area.name}, edge limit {cap}: {json.dumps(metrics)}", flush=True)
        reports[cap] = (metrics, sorted([*mask, *differences]))
        suspect.update(mask + differences)
    if not suspect:
        return
    targets = {cell: grid_center(writer, cell) for cell in sorted(suspect)}
    java_faces = inspect_faces(args, work, writer, ground, targets)
    ply = work / "pdal.ply"
    config = work / "pdal-faces.json"
    config.write_text(json.dumps([*input_stages, {"type": "filters.delaunay"},
                                  {"type": "writers.ply", "filename": str(ply), "faces": True,
                                   "storage_mode": "little endian", "dims": "X,Y,Z"}]))
    run([args.pdal, "pipeline", config, "--nostream", "--readers.las.threads=4"])
    reference_faces = pdal_faces(ply, targets)
    ply.unlink()
    diagnostics = {}
    for cap, (_, cells) in reports.items():
        java, pdal = results[cap]
        for cell in cells:
            position, left = targets[cell], java_faces[cell]
            require(len(left) == 3, f"{area.name}, cell {cell}: no Java face")
            sample = face_sample(left, position, cap)
            require(sample is not None and abs(sample - java[cell]) <= TOLERANCE,
                    f"{area.name}, cell {cell}: Java value differs from its support face")
            for right in reference_faces[cell]:
                reference = face_sample(right, position, cap)
                if reference is not None and abs(reference - pdal[cell]) <= TOLERANCE and set(left) != set(right):
                    diagnostics[f"{cap}/{cell}"] = {"center": position, "java": java[cell], "pdal": pdal[cell],
                                                      "javaFace": left, "pdalFace": right}
                    break
            else:
                raise RuntimeError(f"{area.name}, cell {cell}: no alternate PDAL support face explains difference")
    (work / "differences.json").write_text(json.dumps(diagnostics, indent=2) + "\n")
    print(f"{area.name}: all {len(suspect)} differing cells reproduced from their actual support faces.", flush=True)


def hillshade(args, target, picture, *, detail=False):
    shaded = picture.with_suffix(".tif")
    run([args.gdal / "gdaldem", "hillshade", "-q", "-compute_edges", "-az", "315", "-alt", "45",
         target, shaded])
    window = ("-srcwin", "20", "40", "120", "120", "-outsize", "360", "360") if detail else ()
    run([args.gdal / "gdal_translate", "-q", "-of", "PNG", *window, shaded, picture])
    shaded.unlink()


def check_area(args, area):
    work = args.work / area.name
    work.mkdir(parents=True)
    stages = json.loads(Path(f"examples/dtm-solothurn-{area.name}.json").read_text())["pipeline"]
    stages[0]["filename"] = str(args.data / Path(stages[0]["filename"]).name)
    writer = stages.pop()
    ground = work / "ground.las"
    prefilter = work / "prefilter.json"
    prefilter.write_text(json.dumps([*stages[:-1], {"type": "writers.las", "filename": str(ground),
                                                  "forward": "all", "extra_dims": "all"}]))
    run([args.cli, "pipeline", prefilter, "--metadata", work / "ground.json"])
    require(json.loads((work / "ground.json").read_text())["pointsWritten"] == area.selected,
            f"{area.name}: incorrect number in shared ground LAS")
    accepted = {}
    for cap in (None, area.cap):
        outputs = {}
        for engine, workers in VARIANTS:
            case = work / f"{engine}-{workers}-cap-{cap}"
            case.mkdir()
            command, target, metadata = configure(args, case, stages, writer, engine, workers, cap)
            run(command)
            outputs[engine, workers] = read_result(args, target, metadata, area, writer, engine, area.points_read)
            if workers == 4:
                hillshade(args, target, case / "hillshade.png")
                if area.name == "urban":
                    hillshade(args, target, case / "buildings.png", detail=True)
        for engine in ("java", "pdal"):
            result1, values1 = outputs[engine, 1]
            result4, values4 = outputs[engine, 4]
            require(result1 == result4 and struct.pack(f"<{CELLS}d", *values1) == struct.pack(f"<{CELLS}d", *values4),
                    f"{area.name}: {engine} 1/4 worker results differ")
        java, pdal = outputs["java", 4], outputs["pdal", 4]
        compare_stats(java[0]["statistics"], pdal[0], area.selected)
        accepted[cap] = (java[1], pdal[1])
    compare_surfaces(args, area, writer, work, stages, ground, accepted)
    return stages, writer, ground, accepted[None]


def benchmark(args, datasets):
    execute, timing = GRID["timing_functions"]()
    env = dict(os.environ, JAVA_OPTS="-Xmx512m")
    args.benchmark_csv.parent.mkdir(parents=True, exist_ok=True)
    with args.benchmark_csv.open("w", newline="") as report:
        csv_writer = csv.writer(report)
        csv_writer.writerow(("area", "input", "round", "position", "engine", "reader_workers", "read_points",
                             "wall_s", "user_s", "system_s", "rss_bytes", "tiff_bytes", "metadata_bytes"))
        for area, (stages, writer, ground, accepted) in zip(AREAS, datasets):
            ground_stages = [{"type": "readers.las", "filename": str(ground)}, stages[-1]]
            for mode, profile, variants, rounds, points in (
                    ("laz", stages, VARIANTS, 8, area.points_read),
                    ("ground-las", ground_stages, VARIANTS[:2], 5, area.selected)):
                if mode == "ground-las":
                    for variant in variants:
                        with tempfile.TemporaryDirectory(dir=args.work) as folder:
                            command, target, metadata = configure(args, Path(folder), profile, writer, *variant, None)
                            run(command)
                            _, actual = read_result(args, target, metadata, area, writer, variant[0], points)
                            require(actual == accepted[0 if variant[0] == "java" else 1],
                                    f"{area.name}: ground LAS raster differs from accepted result")
                with Path(profile[0]["filename"]).open("rb") as source:
                    while source.read(1 << 20):
                        pass
                for index in range(rounds):
                    offset = index % len(variants)
                    order = variants[offset:] + variants[:offset]
                    for position, (engine, workers) in enumerate(order, 1):
                        with tempfile.TemporaryDirectory(dir=args.work) as folder:
                            command, target, metadata = configure(args, Path(folder), profile, writer,
                                                                   engine, workers, None)
                            elapsed, user, system, rss = GRID["measure"](command, execute, timing, env)
                            _, actual = read_result(args, target, metadata, area, writer, engine, points)
                            require(actual == accepted[0 if engine == "java" else 1],
                                    f"{area.name}: timed raster differs from accepted result")
                            csv_writer.writerow((area.name, mode, index + 1, position, engine, workers, points,
                                                 f"{elapsed:.9f}", user, system, rss,
                                                 target.stat().st_size, metadata.stat().st_size))
                            report.flush()
                            print(f"{area.name} {mode} {index + 1}/{rounds} {engine}-{workers}: {elapsed:.3f} s",
                                  flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("pdal", type=Path)
    parser.add_argument("--cli", type=Path, default=Path("build/install/pointcloud/bin/pointcloud"))
    parser.add_argument("--data", type=Path, default=Path(".cache/test-data"))
    parser.add_argument("--gdal", type=Path, help="directory containing the GDAL CLI tools (default: alongside PDAL)")
    parser.add_argument("--output", type=Path, default=Path(".cache/dtm-acceptance"))
    parser.add_argument("--benchmark-csv", type=Path, help="also run the M1 comparison and write measurements")
    args = parser.parse_args()
    args.pdal = args.pdal.resolve()
    args.gdal = args.gdal or args.pdal.parent
    args.output.mkdir(parents=True, exist_ok=True)
    args.work = Path(tempfile.mkdtemp(prefix="run-", dir=args.output))
    print(f"DTM outputs: {args.work}", flush=True)
    require("pdal 2.10.2 " in subprocess.check_output([args.pdal, "--version"], text=True),
            "This comparison requires PDAL 2.10.2")
    accepted = [check_area(args, area) for area in AREAS]
    if args.benchmark_csv:
        benchmark(args, accepted)


if __name__ == "__main__":
    main()
