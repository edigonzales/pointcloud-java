"""Check LAS 1.4 layer reuse using a small independently compressed fixture."""

import argparse
import filecmp
import json
import os
from pathlib import Path
import runpy
import struct
import subprocess
import tempfile


CHECKS = runpy.run_path(str(Path(__file__).with_name("check-e4-pipeline.py")))
require = CHECKS["require"]
setup = CHECKS["setup"]
point_records = CHECKS["point_records"]
assert_unpublished = CHECKS["assert_unpublished"]

CHUNK_POINTS = 50_000
POINT_COUNT = 2 * CHUNK_POINTS + 3
RECORD_BYTES = 40
LAYER_COUNT = 13  # POINT14 (9), RGBNIR14 (2), two Extra Bytes (2).
RGB_LAYER = 9
NIR_LAYER = 10


def make_las(path):
    fixture = Path("src/test/resources/las/format-8.las").read_bytes()
    width, seeds = point_records(fixture)
    require(width == RECORD_BYTES and len(seeds) == 5 * width, "Unexpected format-8 template")
    point_offset, = struct.unpack_from("<I", fixture, 96)
    evlr_offset, = struct.unpack_from("<Q", fixture, 235)
    header = bytearray(fixture[:point_offset])
    points = bytearray(POINT_COUNT * width)
    returns = [0] * 15
    for index in range(POINT_COUNT):
        # Hold every optional dimension constant in the middle chunk.
        seed = 0 if CHUNK_POINTS <= index < 2 * CHUNK_POINTS else index % 5
        start = index * width
        points[start:start + width] = seeds[seed * width:(seed + 1) * width]
        struct.pack_into("<i", points, start, index)
        returns[(points[start + 14] & 15) - 1] += 1
    scale, = struct.unpack_from("<d", fixture, 131)
    offset, = struct.unpack_from("<d", fixture, 155)
    struct.pack_into("<dd", header, 179, (POINT_COUNT - 1) * scale + offset, offset)
    struct.pack_into("<Q", header, 235, point_offset + len(points))
    struct.pack_into("<16Q", header, 247, POINT_COUNT, *returns)
    with path.open("wb") as stream:
        stream.write(header)
        stream.write(points)
        stream.write(fixture[evlr_offset:])


def layers(data):
    require(data[24:26] == b"\x01\x04" and data[104] == 0x88,
            "Expected compressed LAS 1.4 point format 8")
    width, = struct.unpack_from("<H", data, 105)
    require(width == RECORD_BYTES, "Unexpected point record width")
    point_offset, = struct.unpack_from("<I", data, 96)
    table, = struct.unpack_from("<Q", data, point_offset)
    version, chunks = struct.unpack_from("<II", data, table)
    require((version, chunks) == (0, 3), "Expected a three-chunk LAZ table")
    cursor = point_offset + 8
    result = []
    for points in (CHUNK_POINTS, CHUNK_POINTS, 3):
        count, = struct.unpack_from("<I", data, cursor + width)
        require(count == points, f"Expected {points} points in chunk, got {count}")
        sizes_offset = cursor + width + 4
        sizes = struct.unpack_from(f"<{LAYER_COUNT}I", data, sizes_offset)
        result.append((sizes_offset, sizes))
        cursor = sizes_offset + LAYER_COUNT * 4 + sum(sizes)
    require(cursor == table, "Layer lengths do not cover the point data")
    return result


def cli(launcher, directory, source, workers, env):
    config, las, metadata = setup(directory, source, [])
    result = subprocess.run([
        launcher, "pipeline", config, "--metadata", metadata,
        "--batch-size", str(CHUNK_POINTS), "--laz-workers", str(workers),
    ], env=env, capture_output=True, text=True, timeout=30)
    return result, las, metadata


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("baseline", type=Path, help="installed baseline CLI launcher")
    parser.add_argument("candidate", type=Path, help="installed layer-reuse CLI launcher")
    parser.add_argument("pdal", type=Path, help="PDAL 2.10.2 executable")
    args = parser.parse_args()
    env = dict(os.environ, JAVA_OPTS="-Xmx512m")
    with tempfile.TemporaryDirectory(prefix="layer-reuse-") as scratch:
        work = Path(scratch)
        raw, source = work / "input.las", work / "input.laz"
        make_las(raw)
        subprocess.run([
            args.pdal.resolve(), "translate", raw, source, "--stream",
            "--writers.las.forward=all", "--writers.las.extra_dims=Confidence=uint16",
        ], check=True, timeout=30)

        data = bytearray(source.read_bytes())
        chunk_layers = layers(data)
        rgb = [sizes[RGB_LAYER] for _, sizes in chunk_layers]
        nir = [sizes[NIR_LAYER] for _, sizes in chunk_layers]
        require(nir[1] == 0 and min(nir[0], nir[2]) > 0, f"Expected present/absent/present NIR layers, got {nir}")
        print(f"Fixed chunks {CHUNK_POINTS}, {CHUNK_POINTS}, 3. RGB layer lengths {rgb}, NIR {nir}.", flush=True)

        result, reference, stats = cli(args.baseline.resolve(), work / "baseline", source, 1, env)
        require(result.returncode == 0, result.stderr)
        require(point_records(raw.read_bytes()) == point_records(reference.read_bytes()),
                "Baseline changed uncompressed point records")
        expected = json.loads(stats.read_bytes())
        require(expected["pointsRead"] == expected["pointsWritten"] == POINT_COUNT,
                "Baseline returned unexpected point counts")
        candidate = args.candidate.resolve()
        for workers in (1, 2):
            result, las, metadata = cli(candidate, work / f"candidate-{workers}", source, workers, env)
            require(result.returncode == 0, result.stderr)
            require(filecmp.cmp(reference, las, shallow=False) and stats.read_bytes() == metadata.read_bytes(),
                    f"Candidate workers={workers}: LAS or JSON differs from baseline")
            print(f"workers={workers}: {POINT_COUNT} identical records and byte-identical LAS and JSON", flush=True)

        offset, sizes = chunk_layers[1]
        # Preserve the chunk table and payload boundaries while denying the XY
        # arithmetic decoder its required four initial bytes.
        require(sizes[0] > 3, "Second chunk XY layer is already too short")
        struct.pack_into("<I", data, offset, 3)
        struct.pack_into("<I", data, offset + (LAYER_COUNT - 1) * 4, sizes[-1] + sizes[0] - 3)
        layers(data)
        malformed = work / "short-xy.laz"
        malformed.write_bytes(data)

        for workers in (1, 2):
            result, las, metadata = cli(candidate, work / f"malformed-{workers}", malformed, workers, env)
            require(result.returncode != 0 and f"LAZ decode failed after {CHUNK_POINTS} " in result.stderr
                    and "EOFException" in result.stderr,
                    f"workers={workers}: expected EOF at the second chunk, got {result.returncode}: {result.stderr}")
            assert_unpublished(las, metadata)
            print(f"workers={workers}: rejected short XY at point {CHUNK_POINTS}, exit {result.returncode}, "
                  "no LAS, JSON or temporary output", flush=True)


if __name__ == "__main__":
    main()
