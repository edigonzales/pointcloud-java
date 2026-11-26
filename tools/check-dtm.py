"""Verify and benchmark the Solothurn TIN terrain rasters against PDAL 2.10.2."""

import argparse
import csv
from fractions import Fraction
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
    epsg: str = "EPSG:2056"
    z_unit_metres: float = 1.0


AREAS = (
    Area("urban", 11_963_266, 728_768, 728_751, 20),
    Area("forest", 14_566_627, 428_321, 428_313, 5),
)


def configure(args, work, input_stages, writer, engine, workers, cap, *, complete=False):
    target, metadata, config = (work / name for name in ("dtm.tif", "metadata.json", "pipeline.json"))
    if engine == "java":
        output = {**writer, "filename": str(target)}
        if cap is not None:
            output["max_triangle_edge_length"] = cap
        if complete:
            output["fill_nodata"] = "nearest"
        stages = [*input_stages, output]
        command = [args.cli, "pipeline", config, "--laz-workers", workers, "--metadata", metadata]
    else:
        require(not complete, "PDAL is the pure TIN reference")
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


def read_result(args, target, metadata, area, writer, engine, points_read, *, complete=False):
    info = json.loads(subprocess.check_output([args.gdal / "gdalinfo", "-json", target], text=True))
    resolution = writer["resolution"]
    size = (writer["width"], writer["height"])
    cells = math.prod(size)
    transform = [writer["origin_x"], resolution, 0, writer["origin_y"] + size[1] * resolution, 0, -resolution]
    require(info["driverShortName"] == "GTiff" and tuple(info["size"]) == size == area.size
            and info["geoTransform"] == transform, f"{target}: incorrect driver, size or geotransform")
    require(info["metadata"][""].get("AREA_OR_POINT", "Area") == "Area"
            and info["metadata"].get("IMAGE_STRUCTURE", {}).get("COMPRESSION", "NONE") == "NONE",
            f"{target}: incorrect pixel reference or compression")
    crs = subprocess.check_output([args.gdal / "gdalsrsinfo", "-o", "epsg", target], text=True).split()
    axes = info["coordinateSystem"]["dataAxisToSRSAxisMapping"]
    require(area.epsg in crs and axes[:2] == [1, 2] and
            (engine == "pdal" or (axes == [1, 2] and crs == [area.epsg])),
            f"{target}: incorrect CRS or axis order")
    require(len(info["bands"]) == 1, f"{target}: expected exactly one elevation band")
    band = info["bands"][0]
    require(band["type"] == "Float64" and
            ("noDataValue" not in band if complete else band.get("noDataValue") == NODATA),
            f"{target}: incorrect elevation sample type or NoData")
    values, = read_bands(args.gdal / "gdal_translate", target, area, PROFILE)
    require(all(map(math.isfinite, values)), f"{target}: nonfinite raster value")
    covered = cells if complete else sum(z != NODATA for z in values)
    result = json.loads(metadata.read_text())
    if engine == "java":
        require(band.get("description") == "elevation", f"{target}: unexpected band name")
        require((result["pointsRead"], result["pointsSelected"], result["pointsWritten"])
                == (points_read, area.selected, 0) and "pointsBinned" not in result,
                f"{metadata}: incorrect point counts")
        expected_dtm = {"supportPoints": area.support, "cellsCovered": covered, "cellsNoData": cells - covered}
        if complete:
            filled = result["dtm"].get("cellsFilledNearest")
            require(type(filled) is int and 0 <= filled <= cells, f"{metadata}: incorrect nearest count")
            expected_dtm["cellsFilledNearest"] = filled
        require(result["dtm"] == expected_dtm, f"{metadata}: incorrect DTM metadata")
    else:
        result = result["stages"]
        require(result["readers.las"]["count"] == points_read
                and result["filters.stats"]["statistic"][0]["count"] == area.selected,
                f"{metadata}: incorrect read/selected point counts")
        result = result["filters.stats"]["statistic"]
    return result, values


def grid_center(writer, cell):
    col, row = cell % writer["width"], cell // writer["width"]
    resolution = writer["resolution"]
    return (writer["origin_x"] + (col + .5) * resolution,
            writer["origin_y"] + (writer["height"] - row - .5) * resolution)


def packed_samples(values):
    return struct.pack(f"<{len(values)}d", *values)


def nearest_heights(ground, writer, cells):
    """Independent full point scan for a small set of real raster cells."""
    with ground.open("rb") as stream, mmap.mmap(stream.fileno(), 0, access=mmap.ACCESS_READ) as data:
        header = GRID["REFERENCE"]["read_las_header"](stream)
        require(header[104] < 64, "Nearest proof requires uncompressed ground LAS")
        point_offset, = struct.unpack_from("<I", header, 96)
        record_size, = struct.unpack_from("<H", header, 105)
        count, = struct.unpack_from("<Q" if header[25] == 4 else "<I", header, 247 if header[25] == 4 else 107)
        sx, sy, sz, ox, oy, oz = struct.unpack_from("<6d", header, 131)

        # Work on the native decimal lattice for the real profiles. The integer metric
        # avoids cancellation at map coordinates such as 2.6 million metres.
        scale_x, scale_y, offset_x, offset_y = map(lambda value: Fraction(str(value)), (sx, sy, ox, oy))
        resolution, origin_x, origin_y = (Fraction(str(writer[k])) for k in ("resolution", "origin_x", "origin_y"))
        width, height = writer["width"], writer["height"]
        centers = [(origin_x + Fraction(2 * (cell % width) + 1, 2) * resolution - offset_x,
                    origin_y + Fraction(2 * (height - cell // width) - 1, 2) * resolution - offset_y)
                   for cell in cells]
        denominator = math.lcm(scale_x.denominator, scale_y.denominator,
                               *(value.denominator for center in centers for value in center))
        centers = [(int(x * denominator), int(y * denominator)) for x, y in centers]
        scale_x, scale_y = int(scale_x * denominator), int(scale_y * denominator)
        xyz = struct.Struct("<iii")
        best = [None] * len(centers)
        for offset in range(point_offset, point_offset + count * record_size, record_size):
            x, y, z = xyz.unpack_from(data, offset)
            px, py = x * scale_x, y * scale_y
            for index, (cx, cy) in enumerate(centers):
                dx, dy = px - cx, py - cy
                distance = dx * dx + dy * dy
                winner = best[index]
                if winner is None or distance < winner[0]:
                    best[index] = (distance, x, y, z, False)
                elif distance == winner[0] and not winner[4] and (x, y) != winner[1:3]:
                    best[index] = (*winner[:4], True)
    require(all(winner is not None for winner in best), "No source point for nearest proof")
    # Fixtures exercise exact ties. These real checks use uniquely closest XY so
    # rounding the scaled Double point locations cannot decide a nominal tie.
    return {cell: winner[3] * sz + oz for cell, winner in zip(cells, best) if not winner[4]}


def check_complete(area, writer, ground, tin, complete, *, expected_filled=None):
    tin_metadata, tin_values = tin
    complete_metadata, complete_values = complete
    require({k: v for k, v in tin_metadata.items() if k != "dtm"}
            == {k: v for k, v in complete_metadata.items() if k != "dtm"},
            f"{area.name}: completing DTM changed point metadata")
    original = packed_samples(tin_values)
    filled = packed_samples(complete_values)
    missing = []
    for index, value in enumerate(tin_values):
        if value == NODATA:
            missing.append(index)
        else:
            offset = index * 8
            require(original[offset:offset + 8] == filled[offset:offset + 8],
                    f"{area.name}: completing DTM changed TIN cell {index}")
    count = len(missing)
    require(complete_metadata["dtm"]["cellsFilledNearest"] == count,
            f"{area.name}: nearest count differs from original NoData count {count}")
    if expected_filled is not None:
        require(count == expected_filled, f"{area.name}: expected {expected_filled} nearest cells, found {count}")
    checked = 0
    if count:
        tested = min(count, 13)
        targets = [missing[i * (count - 1) // max(1, tested - 1)] for i in range(tested)]
        proof = nearest_heights(ground, writer, targets)
        require(proof, f"{area.name}: no sampled nearest cell with a unique closest XY")
        for cell, height in proof.items():
            require(filled[cell * 8:(cell + 1) * 8] == struct.pack("<d", height),
                    f"{area.name}: cell {cell} nearest height {complete_values[cell]} differs from {height}")
        checked = len(proof)
    print(f"{area.name}: {count} nearest cells, {checked} checked by direct search, no NoData cells", flush=True)
    return count


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
    if not face:
        return NODATA
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
    tolerance = TOLERANCE / area.z_unit_metres
    suspect = set()
    reports = {}
    for cap, (java, pdal) in results.items():
        errors = sorted(abs(a - b) for a, b in zip(java, pdal) if NODATA not in (a, b))
        require(errors, f"{area.name}: no shared elevation cells")
        mask = [i for i, (a, b) in enumerate(zip(java, pdal)) if (a == NODATA) != (b == NODATA)]
        differences = [i for i, (a, b) in enumerate(zip(java, pdal))
                       if NODATA not in (a, b) and abs(a - b) > tolerance]
        if cap is None and area in AREAS:
            require(len(errors) == math.prod(area.size), f"{area.name}: expected full coverage without cap")
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
            sample = face_sample(left, position, cap)
            require(sample is not None and abs(sample - java[cell]) <= tolerance,
                    f"{area.name}, cell {cell}: Java value differs from its support face")
            for right in reference_faces[cell] or ((),):
                reference = face_sample(right, position, cap)
                if reference is not None and abs(reference - pdal[cell]) <= tolerance and set(left) != set(right):
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
            require(result1 == result4 and packed_samples(values1) == packed_samples(values4),
                    f"{area.name}: {engine} 1/4 worker results differ")
        java, pdal = outputs["java", 4], outputs["pdal", 4]
        compare_stats(java[0]["statistics"], pdal[0], area.selected)
        accepted[cap] = (java[1], pdal[1])
        if args.complete:
            completed = []
            for workers in (1, 4):
                case = work / f"complete-{workers}-cap-{cap}"
                case.mkdir()
                command, target, metadata = configure(args, case, stages, writer, "java", workers, cap, complete=True)
                run(command)
                completed.append(read_result(args, target, metadata, area, writer, "java",
                                             area.points_read, complete=True))
            require(completed[0][0] == completed[1][0] and
                    packed_samples(completed[0][1]) == packed_samples(completed[1][1]),
                    f"{area.name}: complete 1/4 worker results differ")
            check_complete(area, writer, ground, java, completed[1],
                           expected_filled=0 if cap is None else {"urban": 7_227, "forest": 249}[area.name])
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
    parser.add_argument("--complete", action="store_true", help="also verify the Java nearest fallback")
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
