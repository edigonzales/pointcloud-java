"""Exercise the exact percentile point and cell limits with the installed Java CLI."""

import argparse
import json
import os
from pathlib import Path
import runpy
import struct
import subprocess
import tempfile


GRID = runpy.run_path(str(Path(__file__).with_name("check-ground-grid.py")))
require = GRID["require"]
LIMIT = 4_000_000
SIDE = 1000
ORIGIN = (2594100, 1227600)
SCALE = (0.5, 0.5, 1.0)
BANDS = ("count", "p50", "p90")


def make_las(path, concentrated):
    count = LIMIT + int(concentrated)
    last_xy = 0.5 if concentrated else SIDE - 0.5
    maximum_z = LIMIT if concentrated else 30
    keys = struct.pack("<12H", 1, 1, 0, 2, 1024, 0, 1, 1, 3072, 0, 1, 2056)
    vlr = struct.pack("<H16sHH32s", 0, b"LASF_Projection", 34735, len(keys), b"GeoKeyDirectoryTag") + keys
    header = bytearray(227)
    header[:4] = b"LASF"
    header[24:26] = bytes((1, 2))
    struct.pack_into("<HIIBH", header, 94, len(header), len(header) + len(vlr), 1, 0, 20)
    struct.pack_into("<6I", header, 107, count, count, 0, 0, 0, 0)
    struct.pack_into("<6d", header, 131, *SCALE, *ORIGIN, 0.0)
    struct.pack_into("<6d", header, 179, ORIGIN[0] + last_xy, ORIGIN[0] + 0.5,
                     ORIGIN[1] + last_xy, ORIGIN[1] + 0.5, maximum_z, 0.0)
    record = struct.Struct("<iiiHBBbBH")
    with path.open("wb") as output:
        output.write(header)
        output.write(vlr)
        for start in range(0, count, 32768):
            size = min(count - start, 32768)
            block = bytearray(size * record.size)
            for offset in range(size):
                index = start + offset
                if concentrated:
                    x, y = 1, 1
                    z = (index * 97) % LIMIT if index < LIMIT else LIMIT
                else:
                    y, x = divmod(index // 4, SIDE)
                    x, y, z = 2 * x + 1, 2 * y + 1, (index % 4) * 10
                record.pack_into(block, offset * record.size, x, y, z, 0, 9, 2, 0, 0, 0)
            output.write(block)


def command(cli, work, source, name, size, expression=None):
    config, target, metadata = (work / f"{name}.{extension}" for extension in ("json", "tif", "metadata.json"))
    stages = [{"type": "readers.las", "filename": str(source)}]
    if expression:
        stages.append({"type": "filters.expression", "expression": expression})
    stages.append({"type": "writers.raster", "filename": str(target), "binmode": True, "resolution": 1,
                   "origin_x": ORIGIN[0], "origin_y": ORIGIN[1], "width": size[0], "height": size[1],
                   "output_type": ",".join(BANDS)})
    config.write_text(json.dumps(stages))
    return [cli, "pipeline", config, "--metadata", metadata], target, metadata


def verify_limit(args, work, source, concentrated, execute, timing, env):
    name = "concentrated" if concentrated else "million-cells"
    size = (1, 1) if concentrated else (SIDE, SIDE)
    expression = f"Z < {LIMIT}" if concentrated else None
    launch, target, metadata = command(args.cli, work, source, name, size, expression)
    wall, user, system, rss = GRID["measure"](list(map(str, launch)), execute, timing, env)
    point_counts = json.loads(metadata.read_text())
    require((point_counts["pointsRead"], point_counts["pointsSelected"], point_counts["pointsBinned"])
            == (LIMIT + int(concentrated), LIMIT, LIMIT), f"{name}: wrong point counts")
    cells = size[0] * size[1]
    case = GRID["GridCase"](name, LIMIT, LIMIT, cells, {0: 0}, size)
    profile = GRID["RasterProfile"](name, 0, BANDS)
    bands = GRID["read_bands"](args.pdal.with_name("gdal_translate"), target, case, profile)
    expected = (LIMIT, 1_999_999.5, 3_599_999.1) if concentrated else (4, 15, 27)
    for band, value in zip(bands, expected):
        require(all(abs(sample - value) < 1e-9 for sample in band), f"{name}: wrong value for {value}")
    print(f"{name}: {LIMIT:,} points, {cells:,} cells, -Xmx512m, {wall:.3f} s wall, "
          f"{user + system:.2f} s CPU, {rss / (1 << 20):.2f} MiB peak RSS", flush=True)


def reject(args, work, source, name, size, reason, env):
    launch, target, metadata = command(args.cli, work, source, name, size)
    result = subprocess.run(launch, capture_output=True, text=True, env=env)
    require(result.returncode != 0 and reason in result.stderr, result.stderr)
    require(not target.exists() and not metadata.exists() and not list(work.glob("*.part")),
            f"{name}: output left behind")
    print(f"{name}: {result.stderr.strip()}", flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("pdal", type=Path, help="installed PDAL executable alongside GDAL tools")
    parser.add_argument("--cli", type=Path, default=Path("build/install/pointcloud/bin/pointcloud"))
    args = parser.parse_args()
    execute, timing = GRID["timing_functions"]()
    env = dict(os.environ, JAVA_OPTS="-Xmx512m")
    with tempfile.TemporaryDirectory(prefix="pointcloud-percentile-memory-") as scratch:
        work = Path(scratch)
        for concentrated in (False, True):
            source = work / "points.las"
            make_las(source, concentrated)
            verify_limit(args, work, source, concentrated, execute, timing, env)
            if concentrated:
                reject(args, work, source, "over-point-limit", (1, 1), "4000000 binned points", env)
            else:
                reject(args, work, source, "over-cell-limit", (SIDE + 1, SIDE), "1000000 cells", env)


if __name__ == "__main__":
    main()
