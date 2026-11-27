"""Prepare height-filtered SwissSURFACE3D LAS for the vegetation-outline probe."""

import argparse
from collections import Counter
from concurrent.futures import ThreadPoolExecutor
from contextlib import ExitStack
from dataclasses import dataclass
from hashlib import sha256
import json
import math
from mmap import ACCESS_READ, mmap
import os
from pathlib import Path
import shutil
import struct
import subprocess
import tempfile
import time
from urllib.request import Request, urlopen
from zipfile import ZipFile


CRS = "EPSG:2056+5728"
GROUND_SIZE = 200
GROUND_MARGIN = 25
DEM_RESOLUTION = 0.5
COVERAGE = 11
MAX_GROUND_POINTS = 1_000_000
VEGETATION_CLASS = 3
MINIMUM_HAG = 2.0
OUTPUT_OFFSET = (2600000, 1200000, 0)
RECORD_BYTES = 28


@dataclass(frozen=True)
class Bounds:
    west: float
    south: float
    east: float
    north: float

    def coordinates(self):
        return (self.west, self.south, self.east, self.north)

    def expand(self, margin):
        return Bounds(self.west - margin, self.south - margin, self.east + margin, self.north + margin)

    def intersection_area(self, other):
        return (max(0, min(self.east, other.east) - max(self.west, other.west))
                * max(0, min(self.north, other.north) - max(self.south, other.south)))

    def expression(self):
        return (f"X >= {self.west} && X < {self.east} && Y >= {self.south} && Y < {self.north}")

    def grid(self):
        return (round((self.east - self.west) / DEM_RESOLUTION),
                round((self.north - self.south) / DEM_RESOLUTION))

    def jobs(self):
        for south in range(math.floor(self.south / GROUND_SIZE) * GROUND_SIZE, math.ceil(self.north), GROUND_SIZE):
            for west in range(math.floor(self.west / GROUND_SIZE) * GROUND_SIZE, math.ceil(self.east), GROUND_SIZE):
                yield Bounds(west, south, west + GROUND_SIZE, south + GROUND_SIZE)


@dataclass(frozen=True)
class Asset:
    tile: str
    archive_size: int
    archive_sha256: str
    points: int

    @property
    def url(self):
        name = f"swisssurface3d_2023_{self.tile}"
        return f"https://data.geo.admin.ch/ch.swisstopo.swisssurface3d/{name}/{name}_2056_5728.las.zip"

    @property
    def bounds(self):
        west, south = (int(part) * 1000 for part in self.tile.split("-"))
        return Bounds(west, south, west + 1000, south + 1000)

    @property
    def las_size(self):
        return 227 + self.points * 28


ASSETS = (
    Asset("2609-1232", 199_231_346, "2a5a4db6e817e8dbe6620386d05977af75f91a6bc92f39101ded8d2998045019", 18_453_702),
    Asset("2610-1232", 153_492_296, "1bd4717dd0e026ce1456589804ed2972924f87b2c8d911e6aa114156fbbd71fe", 15_058_216),
    Asset("2611-1233", 141_050_788, "858e4d6abf4c44b0c0d0f0a602f06b3ddde132604b5a5addcc919d96c8239d7d", 13_871_301),
)

REGIONS = {
    "calibration": Bounds(2610200, 1232400, 2610600, 1232800),
    "seam": Bounds(2609900, 1232500, 2610100, 1232700),
    "holdout": Bounds(2611200, 1233200, 2611800, 1233800),
}


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def run(command, input_text=None):
    result = subprocess.run([str(arg) for arg in command], input=input_text, text=True, capture_output=True)
    if result.returncode:
        raise RuntimeError(f"{' '.join(map(str, command))}\n{result.stdout}{result.stderr}")
    return result.stdout


def file_hash(path):
    with path.open("rb") as stream:
        return sha256_stream(stream)


def sha256_stream(stream):
    digest = sha256()
    while chunk := stream.read(1024 * 1024):
        digest.update(chunk)
    return digest.hexdigest()


def source_las(asset, directory):
    directory.mkdir(parents=True, exist_ok=True)
    archive = directory / f"{asset.tile}.las.zip"
    target = directory / f"{asset.tile}.las"
    receipt = directory / f"{asset.tile}.las.sha256"
    with tempfile.TemporaryDirectory(prefix=f".{asset.tile}-", dir=directory) as scratch:
        work = Path(scratch)
        if not archive.exists():
            request = Request(asset.url, headers={"Accept-Encoding": "identity"})
            pending = work / archive.name
            with urlopen(request, timeout=60) as response, pending.open("wb") as output:
                require(response.status == 200, f"{asset.tile}: unexpected HTTP {response.status}")
                shutil.copyfileobj(response, output, length=1024 * 1024)
            validate_archive(asset, pending)
            pending.replace(archive)
        else:
            validate_archive(asset, archive)
        if not (target.is_file() and receipt.is_file() and file_hash(target) == receipt.read_text().strip()):
            with ZipFile(archive) as package:
                members = package.infolist()
                require(len(members) == 1 and members[0].filename == asset.tile.replace("-", "_") + ".las"
                        and members[0].file_size == asset.las_size,
                        f"{asset.tile}: unexpected LAS member or size")
                pending = work / target.name
                with package.open(members[0]) as input_file, pending.open("wb") as output:
                    shutil.copyfileobj(input_file, output, length=1024 * 1024)
            validate_source_las(asset, pending)
            checksum = work / receipt.name
            checksum.write_text(file_hash(pending) + "\n")
            pending.replace(target)
            checksum.replace(receipt)
        validate_source_las(asset, target)
    return target


def validate_archive(asset, path):
    require(path.stat().st_size == asset.archive_size, f"{path}: unexpected archive byte count")
    require(file_hash(path) == asset.archive_sha256, f"{path}: archive differs from the official SHA-256")


def validate_source_las(asset, path):
    with path.open("rb") as source:
        header = source.read(227)
    require(len(header) == 227 and header[:4] == b"LASF" and header[24:26] == bytes([1, 2]),
            f"{path}: expected LAS 1.2")
    require(struct.unpack_from("<HIIBHI", header, 94) == (227, 227, 0, 1, 28, asset.points)
            and path.stat().st_size == asset.las_size, f"{path}: unexpected uncompressed source layout")
    x_max, x_min, y_max, y_min = struct.unpack_from("<4d", header, 179)
    require(struct.unpack_from("<6d", header, 131) == (0.01, 0.01, 0.01, asset.bounds.west, asset.bounds.south, 0)
            and (x_min, y_min, x_max, y_max) == (asset.bounds.west, asset.bounds.south,
                                                 asset.bounds.east - 0.01, asset.bounds.north - 0.01),
            f"{path}: unexpected LAS scale, offset or coverage")


def point_count(path):
    with path.open("rb") as data:
        header = data.read(227)
        require(len(header) == 227 and header[:4] == b"LASF", f"{path}: incomplete LAS output")
        offset = struct.unpack_from("<I", header, 96)[0]
        require(227 < offset <= 65_536, f"{path}: missing or oversized CRS metadata")
        variable_records = data.read(offset - 227)
    require(header[24:26] == bytes([1, 2]) and header[104] == 1
            and struct.unpack_from("<H", header, 105)[0] == 28, f"{path}: unexpected output LAS layout")
    require(struct.unpack_from("<6d", header, 131) == (0.01, 0.01, 0.01, *OUTPUT_OFFSET),
            f"{path}: unexpected output LAS coordinate lattice")
    count = struct.unpack_from("<I", header, 107)[0]
    require(path.stat().st_size == offset + count * 28,
            f"{path}: incomplete point data or missing CRS metadata")
    cursor = 0
    keys = {}
    for _ in range(struct.unpack_from("<I", header, 100)[0]):
        require(cursor + 54 <= len(variable_records), f"{path}: truncated VLR")
        _, user, identifier, length = struct.unpack_from("<H16sHH", variable_records, cursor)
        cursor += 54
        require(cursor + length <= len(variable_records), f"{path}: truncated VLR payload")
        if user.rstrip(b"\x00") == b"LASF_Projection" and identifier == 34735:
            require(length >= 8 and length % 8 == 0, f"{path}: malformed GeoKey directory")
            for position in range(cursor + 8, cursor + length, 8):
                key, tag, size, value = struct.unpack_from("<4H", variable_records, position)
                if tag == 0 and size == 1:
                    keys[key] = value
        cursor += length
    require(keys.get(3072) == 2056 and keys.get(4096) == 5728, f"{path}: missing LV95 / LN02 GeoKeys")
    return count


def aligned_record(records, record, cursor, data_start):
    while (hit := records.find(record, cursor)) >= 0:
        if (hit - data_start) % RECORD_BYTES == 0:
            return hit
        cursor = hit + 1
    return -1


def verify_records(args, inputs, selection, cropped, output, terrain, count):
    samples = {i * (count - 1) // 16 for i in range(17)} if count else set()
    sampled_coordinates = []
    with ExitStack() as stack:
        def mapped(path):
            stream = stack.enter_context(path.open("rb"))
            return stack.enter_context(mmap(stream.fileno(), 0, access=ACCESS_READ))

        source = mapped(cropped)
        target = mapped(output)
        records_start = struct.unpack_from("<I", source, 96)[0]
        output_start = struct.unpack_from("<I", target, 96)[0]
        cursor = records_start
        originals = {}
        for index in range(count):
            start = output_start + index * RECORD_BYTES
            record = target[start:start + RECORD_BYTES]
            hit = aligned_record(source, record, cursor, records_start)
            require(hit >= 0, f"{output}: original LAS record bytes/order changed at point {index}")
            cursor = hit + RECORD_BYTES
            if index not in samples:
                continue
            x_raw, y_raw, z_raw = struct.unpack_from("<3i", record)
            x_cm = x_raw + OUTPUT_OFFSET[0] * 100
            y_cm = y_raw + OUTPUT_OFFSET[1] * 100
            x, y = x_cm / 100, y_cm / 100
            require(selection.west <= x < selection.east and selection.south <= y < selection.north,
                    f"{output}: selected point outside requested coverage")
            require(record[15] & 159 == VEGETATION_CLASS, f"{output}: unexpected vegetation class or withheld flag")
            asset, = (asset for asset in inputs if asset.bounds.west <= x < asset.bounds.east
                      and asset.bounds.south <= y < asset.bounds.north)
            if asset not in originals:
                originals[asset] = [mapped(inputs[asset]), 227]
            original, start_at = originals[asset]
            original_record = struct.pack("<3i", x_cm - asset.bounds.west * 100,
                                          y_cm - asset.bounds.south * 100, z_raw) + record[12:]
            original_hit = aligned_record(original, original_record, start_at, 227)
            require(original_hit >= 0, f"{output}: source coordinates, attributes or order changed at point {index}")
            originals[asset][1] = original_hit + RECORD_BYTES
            sampled_coordinates.append((x, y, z_raw / 100))

    if sampled_coordinates:
        locations = "".join(f"{x:.2f} {y:.2f}\n" for x, y, _ in sampled_coordinates)
        elevations = run([args.gdal / "gdallocationinfo", "-valonly", "-geoloc", terrain], locations).split()
        require(len(elevations) == len(sampled_coordinates), f"{terrain}: HAG sample is outside the DEM")
        require(all(z - float(dem_z) >= MINIMUM_HAG - 1e-10
                    for (_, _, z), dem_z in zip(sampled_coordinates, elevations)),
                f"{output}: selected point is below the height threshold")
    return len(samples)


def write_json(path, data):
    temporary = path.with_suffix(path.suffix + ".part")
    temporary.write_text(json.dumps(data, indent=2) + "\n")
    temporary.replace(path)


def pdal_select(pdal, inputs, expression, output):
    require(inputs, f"{output}: no point source intersects the selection")
    readers = [{"type": "readers.las", "filename": str(path), "tag": f"s{i}"}
               for i, path in enumerate(inputs)]
    stages = list(readers)
    if len(readers) > 1:
        stages += [{"type": "filters.merge", "inputs": [entry["tag"] for entry in readers]}]
    stages += [
        {"type": "filters.expression", "expression": expression},
        {"type": "writers.las", "filename": str(output), "minor_version": 2, "dataformat_id": 1,
         "compression": False, "a_srs": CRS, "forward": "all",
         "scale_x": 0.01, "scale_y": 0.01, "scale_z": 0.01,
         **dict(zip(("offset_x", "offset_y", "offset_z"), OUTPUT_OFFSET))},
    ]
    config = output.with_suffix(".pdal.json")
    write_json(config, stages)
    run([pdal, "pipeline", config, "--stream"])
    return point_count(output)


def java_pipeline(cli, source, stages, output):
    config = output.with_suffix(".pipeline.json")
    metadata = output.with_suffix(".metadata.json")
    write_json(config, [str(source), *stages])
    run([cli, "pipeline", config, "--metadata", metadata])
    return json.loads(metadata.read_text())


def source_paths(inputs, bounds):
    return [path for asset, path in inputs.items() if asset.bounds.intersection_area(bounds) > 0]


def dtm_tiles(args, inputs, job, directory):
    name = "_".join(str(round(coordinate / DEM_RESOLUTION)) for coordinate in job.coordinates())
    boundary = job.expand(GROUND_MARGIN)
    terrain = directory / f"{name}.tif"
    support = directory / f"{name}-support.tif"
    with tempfile.TemporaryDirectory(prefix=f"{name}-", dir=directory) as scratch:
        ground = Path(scratch) / "ground.las"
        selected = pdal_select(args.pdal, source_paths(inputs, boundary),
                               f"Classification == 2 && Withheld == 0 && {boundary.expression()}", ground)
        if selected > MAX_GROUND_POINTS:
            require(job.east - job.west >= 2 * DEM_RESOLUTION and job.north - job.south >= 2 * DEM_RESOLUTION,
                    f"{job}: cannot split ground rectangle within the point limit")
            x = job.west + math.floor((job.east - job.west) / (2 * DEM_RESOLUTION)) * DEM_RESOLUTION
            y = job.south + math.floor((job.north - job.south) / (2 * DEM_RESOLUTION)) * DEM_RESOLUTION
            children = (Bounds(job.west, job.south, x, y), Bounds(x, job.south, job.east, y),
                        Bounds(job.west, y, x, job.north), Bounds(x, y, job.east, job.north))
            return [part for child in children for part in dtm_tiles(args, inputs, child, directory)]
        require(selected > 0, f"{job}: no ground support")
        width, height = job.grid()
        stats = java_pipeline(args.cli, ground, [{"type": "writers.dtm", "filename": str(terrain),
                               "support_filename": str(support), "resolution": DEM_RESOLUTION,
                               "origin_x": job.west, "origin_y": job.south, "width": width, "height": height,
                               "fill_nodata": "nearest"}], terrain)
        require(stats["pointsSelected"] == selected and stats["dtm"]["cellsNoData"] == 0,
                f"{terrain}: ground point count or completeness differs")
    return [(terrain, support, selected)]


def mosaic(args, paths, bounds, output, dtype):
    virtual = output.with_suffix(".vrt")
    run([args.gdal / "gdalbuildvrt", "-q", "-resolution", "user", "-tr", DEM_RESOLUTION, DEM_RESOLUTION,
         "-te", *bounds.coordinates(), virtual, *paths])
    run([args.gdal / "gdal_translate", "-q", "-of", "GTiff", "-ot", dtype, virtual, output])
    virtual.unlink()
    info = json.loads(run([args.gdal / "gdalinfo", "-json", output]))
    require(info["size"] == list(bounds.grid())
            and info["geoTransform"] == [bounds.west, DEM_RESOLUTION, 0, bounds.north, 0, -DEM_RESOLUTION]
            and len(info["bands"]) == 1 and info["bands"][0]["type"] == dtype,
            f"{output}: terrain mosaic is not aligned")


def prepare_region(args, name, bounds, inputs, terrain_cache, jobs_directory, region):
    started = time.perf_counter()
    selection = bounds.expand(COVERAGE)
    # HAG includes the north raster edge and excludes its south raster edge.
    sampling = selection.expand(DEM_RESOLUTION)
    needed = sampling.expand(GROUND_MARGIN)
    surface = (needed.east - needed.west) * (needed.north - needed.south)
    require(sum(asset.bounds.intersection_area(needed) for asset in inputs) == surface,
            f"{name}: required ground support or vegetation halo extends beyond the source data")
    jobs = list(sampling.jobs())
    missing_jobs = [job for job in jobs if job not in terrain_cache]
    with ThreadPoolExecutor(max_workers=args.terrain_workers) as pool:
        pending = {job: pool.submit(dtm_tiles, args, inputs, job, jobs_directory) for job in missing_jobs}
        for job, result in pending.items():
            terrain_cache[job] = result.result()
    parts = [part for job in jobs for part in terrain_cache[job]]
    dem, support, target = (region / entry for entry in ("terrain.tif", "support.tif", "vegetation.las"))
    mosaic(args, [height for height, _, _ in parts], sampling, dem, "Float64")
    mosaic(args, [mask for _, mask, _ in parts], sampling, support, "Byte")
    terrain_seconds = time.perf_counter() - started
    with tempfile.TemporaryDirectory(prefix=".selection-", dir=region) as scratch:
        raw = Path(scratch) / "vegetation.las"
        vegetation = f"Classification == {VEGETATION_CLASS} && Withheld == 0"
        raw_count = pdal_select(args.pdal, source_paths(inputs, selection),
                                f"{vegetation} && {selection.expression()}", raw)
        crop_seconds = time.perf_counter() - started - terrain_seconds
        metadata = java_pipeline(args.cli, raw, [
            {"type": "filters.expression", "expression": vegetation},
            {"type": "filters.hag_dem", "raster": str(dem)},
            {"type": "filters.expression", "expression": f"HeightAboveGround >= {MINIMUM_HAG}"},
            {"type": "filters.stats", "dimensions": "HeightAboveGround"},
            {"type": "writers.las", "filename": str(target), "forward": "all", "extra_dims": "all"},
        ], target)
        hag_seconds = time.perf_counter() - started - terrain_seconds - crop_seconds
        selected = point_count(target)
        require(metadata["pointsRead"] == raw_count and metadata["pointsWritten"] == selected,
                f"{target}: HAG point selection counts differ")
        verified = verify_records(args, inputs, selection, raw, target, dem, selected)
        support_raw = Path(scratch) / "support.bin"
        run([args.gdal / "gdal_translate", "-q", "-of", "ENVI", support, support_raw])
        codes = Counter(support_raw.read_bytes())
        require(set(codes) <= {1, 2} and sum(codes.values()) == math.prod(sampling.grid()),
                f"{name}: support mask has a gap or unexpected code")
    print(f"{name}: {selected:,}/{raw_count:,} points after HAG, {len(parts)} terrain grids, "
          f"{time.perf_counter() - started:.2f} s", flush=True)
    return {"bounds": bounds.coordinates(), "input": str(target), "coverage": COVERAGE, "points": selected,
            "vegetationPoints": raw_count, "terrain": str(dem), "support": str(support),
            "verifiedSelectedRecords": selected, "verifiedOriginalSamples": verified,
            "nearestCells": codes[2], "terrainJobs": len(parts), "terrainInputPoints": sum(p[2] for p in parts),
            "newTerrainJobs": len(missing_jobs),
            "terrainSeconds": terrain_seconds, "cropSeconds": crop_seconds, "hagSeconds": hag_seconds,
            "wallSeconds": time.perf_counter() - started, "hag": metadata}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--pdal", type=Path, required=True)
    parser.add_argument("--cli", type=Path, default=Path("build/install/pointcloud/bin/pointcloud"))
    parser.add_argument("--gdal", type=Path, help="directory with GDAL CLI tools (default: alongside PDAL)")
    parser.add_argument("--cache", type=Path, default=Path(".cache/vegetation-outlines"))
    parser.add_argument("--work-dir", type=Path, help="directory holding each fresh run and its prepare.json")
    parser.add_argument("--region", choices=REGIONS, action="append", help="prepare only the selected regions")
    parser.add_argument("--terrain-workers", type=int, choices=(1, 2, 3), default=1,
                        help="parallel single-reader terrain jobs (default: 1)")
    args = parser.parse_args()
    args.pdal = args.pdal.resolve()
    args.cli = args.cli.resolve()
    args.gdal = (args.gdal or args.pdal.parent).resolve()
    args.cache = args.cache.resolve()
    os.environ.setdefault("JAVA_OPTS", "-Xmx512m")
    root = (args.work_dir or args.cache).resolve()
    root.mkdir(parents=True, exist_ok=True)
    regions = args.region or list(REGIONS)
    require(len(set(regions)) == len(regions), "duplicate region")
    required = [REGIONS[name].expand(COVERAGE + DEM_RESOLUTION + GROUND_MARGIN) for name in regions]
    source_started = time.perf_counter()
    inputs = {asset: source_las(asset, args.cache / "source") for asset in ASSETS
              if any(asset.bounds.intersection_area(window) for window in required)
              or any(asset.bounds.intersection_area(job.expand(GROUND_MARGIN))
                     for name in regions for job in REGIONS[name].expand(COVERAGE + DEM_RESOLUTION).jobs())}
    source_seconds = time.perf_counter() - source_started
    case = Path(tempfile.mkdtemp(prefix="prepared-", dir=root))
    result = {"sources": {asset.tile: {"url": asset.url, "archiveBytes": asset.archive_size,
                                      "archiveSha256": asset.archive_sha256, "points": asset.points,
                                      "input": str(path)} for asset, path in inputs.items()},
              "sourceSeconds": source_seconds, "regions": {}, "terrainResolution": DEM_RESOLUTION,
              "terrainWorkers": args.terrain_workers, "loadBefore": os.getloadavg(),
              "groundSupportMargin": GROUND_MARGIN, "vegetationClass": VEGETATION_CLASS,
              "minimumHag": MINIMUM_HAG}
    started = time.perf_counter()
    with tempfile.TemporaryDirectory(prefix=".ground-tiles-", dir=case) as scratch:
        terrain_cache = {}
        for name in regions:
            region = case / name
            region.mkdir()
            result["regions"][name] = prepare_region(args, name, REGIONS[name], inputs, terrain_cache,
                                                      Path(scratch), region)
    result["wallSeconds"] = time.perf_counter() - started
    result["loadAfter"] = os.getloadavg()
    manifest = root / "prepare.json"
    write_json(manifest, result)
    print(manifest)


if __name__ == "__main__":
    main()
