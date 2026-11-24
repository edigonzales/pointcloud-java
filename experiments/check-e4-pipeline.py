"""Check LAZ pipeline parity from the repo root after experiments:installDist or installDist."""

import argparse
import json
import os
from pathlib import Path
import signal
import struct
import subprocess
import tempfile
import time


FIXTURES = Path("src/test/resources/las")
MODES = ("serial", "per-chunk", "reused")


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def setup(work, source, filters):
    work.mkdir()
    config = work / "pipeline.json"
    las, metadata = work / "result.las", work / "metadata.json"
    writer = {"type": "writers.las", "filename": str(las), "forward": "all",
              "extra_dims": "all", "compression": False}
    config.write_text(json.dumps([str(source), *filters, writer]))
    return config, las, metadata


def point_records(las):
    start = struct.unpack_from("<I", las, 96)[0]
    width = struct.unpack_from("<H", las, 105)[0]
    count = struct.unpack_from("<Q" if las[25] == 4 else "<I", las, 247 if las[25] == 4 else 107)[0]
    records = las[start:start + count * width]
    require(len(records) == count * width, "Truncated point record data in LAS output")
    return width, records


def assert_unpublished(las, metadata):
    require(not las.exists() and not metadata.exists() and not any(las.parent.glob("*.part")),
            f"{las.parent.name}: partial output remains")


def check_modes(command, modes, scratch, source, filters, read, selected, stage_counts):
    reference = None
    for mode in modes:
        config, las, metadata = setup(scratch / f"{source.name}-{mode}", source, filters)
        subprocess.run(command(mode, config, metadata), check=True, timeout=30)
        result = json.loads(metadata.read_text())
        require((result["pointsRead"], result["pointsSelected"], result["pointsWritten"]) ==
                (read, selected, selected), f"{source.name} {mode}: unexpected point counts")
        actual_counts = [(stage["stage"], {value["name"]: value["count"] for value in stage["dimensions"]})
                         for stage in result["statistics"]]
        require(actual_counts == [(stage, {"X": count, "Intensity": count}) for stage, count in stage_counts.items()],
                f"{source.name} {mode}: incorrect statistics counts or stage order")
        require(not any(las.parent.glob("*.part")), f"{source.name} {mode}: temporary output remains")
        actual = las.read_bytes(), result
        if reference is None:
            reference = actual
        else:
            require(actual[0] == reference[0], f"{source.name} {mode}: LAS differs from serial output")
            require(actual[1] == reference[1], f"{source.name} {mode}: statistics differ from serial output")
    print(f"{source.name}: {selected} output records and statistics identical across {', '.join(modes)}", flush=True)
    return reference[0]


def check_corruption(command, modes, scratch):
    fixture = bytearray((FIXTURES / "format-8.laz").read_bytes())
    start = struct.unpack_from("<I", fixture, 96)[0]
    width = struct.unpack_from("<H", fixture, 105)[0]
    # Keep the chunk table intact and invalidate the first layer in its payload.
    struct.pack_into("<I", fixture, start + 8 + width + 4, 0x7fff_ffff)
    source = scratch / "bad-layer.laz"
    source.write_bytes(fixture)
    for mode in modes:
        config, las, metadata = setup(scratch / f"corrupt-{mode}", source, [])
        result = subprocess.run(command(mode, config, metadata), capture_output=True, text=True, timeout=30)
        require(result.returncode != 0 and "layer payload" in result.stderr,
                f"{mode}: expected layered LAZ decoding failure, got {result.returncode}: {result.stderr}")
        assert_unpublished(las, metadata)
        print(f"{mode}: corrupt chunk rejected with exit {result.returncode}; no output published", flush=True)


def check_cancel(command, parallel_modes, scratch, source):
    require(source.is_file(), f"Cancellation fixture missing: {source}; run ./gradlew downloadTestData")
    for mode in parallel_modes:
        config, las, metadata = setup(scratch / f"cancel-{mode}", source, [])
        with subprocess.Popen(command(mode, config, metadata), stdout=subprocess.PIPE,
                              stderr=subprocess.PIPE, text=True) as process:
            try:
                deadline = time.monotonic() + 8
                while process.poll() is None and not any(las.parent.glob("*.las.part")) and time.monotonic() < deadline:
                    time.sleep(0.005)
                require(any(las.parent.glob("*.las.part")), f"{mode}: no active LAS output to interrupt")
                process.send_signal(signal.SIGTERM)
                _, errors = process.communicate(timeout=8)
                require(process.returncode in (128 + signal.SIGTERM, -signal.SIGTERM),
                        f"{mode}: unexpected cancellation exit {process.returncode}: {errors}")
                assert_unpublished(las, metadata)
                print(f"{mode}: SIGTERM exit {process.returncode}; no output published", flush=True)
            finally:
                if process.poll() is None:
                    process.kill()
                    process.wait()


def generate_fixture(pdal, config, pipeline):
    config.write_text(json.dumps(pipeline))
    subprocess.run([pdal, "pipeline", config], check=True, timeout=30)


def check_wide_chunk(command, modes, scratch, pdal, stats):
    wide = scratch / "wide.laz"
    points = 100_003
    generate_fixture(pdal, scratch / "generate-wide.json", [
        {"type": "readers.faux", "bounds": "([2594000,2595000],[1227500,1227501],[700,701])",
         "count": points, "mode": "ramp"},
        {"type": "filters.ferry", "dimensions": ",".join(f"X=>Extra{index}" for index in range(18))},
        {"type": "writers.las", "filename": str(wide), "minor_version": 4, "dataformat_id": 6,
         "compression": True, "extra_dims": "all"}
    ])
    with wide.open("rb") as stream:
        header = stream.read(107)
    width = struct.unpack_from("<H", header, 105)[0]
    require(width * 50_000 > (8 << 20), f"Expected a chunk over 8 MiB, fixture record width was {width}")
    print(f"{wide.name}: {width} bytes per record, {width * 50_000} decoded bytes per full chunk", flush=True)
    check_modes(command, modes, scratch, wide, [{"type": "filters.decimation", "step": 10_000}, stats],
                points, 11, {3: 11})


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--product", action="store_true", help="check the installed pointcloud CLI and --laz-workers 4")
    parser.add_argument("--pdal", type=Path, help="PDAL 2.10.2 binary to generate additional LAZ fixtures")
    parser.add_argument("--cancel-source", type=Path, default=Path(".cache/test-data/so-2019.laz"),
                        help="LAZ tile long enough to interrupt (default: %(default)s)")
    args = parser.parse_args()
    launcher = Path(os.environ["JAVA_HOME"]) / "bin/java" if "JAVA_HOME" in os.environ else "java"
    java = [launcher, "-Xmx512m", "-cp", "experiments/build/install/experiments/lib/*",
            "pointcloud.ChunkParallel", "pipeline"]
    modes = ("default", "workers-4") if args.product else MODES

    def command(mode, config, metadata):
        if args.product:
            cmd = ["env", "JAVA_OPTS=-Xmx512m", "build/install/pointcloud/bin/pointcloud", "pipeline", str(config),
                   "--metadata", str(metadata)]
            return [*cmd, "--laz-workers", "4"] if mode == "workers-4" else cmd
        return [*java, mode, config, metadata]

    stats = {"type": "filters.stats", "dimensions": "X,Intensity"}
    with tempfile.TemporaryDirectory(prefix="e4-pipeline-") as directory:
        scratch = Path(directory)
        fixed = FIXTURES / "chunks.laz"
        filters = [stats, {"type": "filters.expression", "expression": "X >= 2594000.01"},
                   {"type": "filters.decimation", "step": 3, "offset": 1, "limit": 50_000}, stats]
        expected = range(2, 50_001, 3)
        counts = {2: 50_003, 5: len(expected)}
        output = check_modes(command, modes, scratch, fixed, filters, 50_003, len(expected), counts)
        width, records = point_records(output)
        raw_x = [struct.unpack_from("<i", records, index)[0] for index in range(0, len(records), width)]
        require(raw_x == list(expected), "Incorrect output record order across the fixed-size chunk boundary")

        no_table = bytearray(fixed.read_bytes())
        start = struct.unpack_from("<I", no_table, 96)[0]
        table = struct.unpack_from("<Q", no_table, start)[0]
        # A fixed-size chunk stream can explicitly point to the locator instead of a table.
        struct.pack_into("<Q", no_table, start, start)
        fallback = scratch / "fixed-no-table.laz"
        fallback.write_bytes(no_table[:table])
        require(check_modes(command, modes, scratch, fallback, filters, 50_003, len(expected), counts)
                == output, "Sequential fallback with no chunk table changed the LAS output")

        modern = check_modes(command, modes, scratch, FIXTURES / "format-8.laz", [stats], 5, 5, {2: 5})
        require(point_records(modern) == point_records((FIXTURES / "format-8.las").read_bytes()),
                "Layered fixture fields, including NIR or Extra Bytes, changed")
        if args.product:
            uncompressed = check_modes(command, modes, scratch, FIXTURES / "format-8.las", [stats], 5, 5, {2: 5})
            require(point_records(uncompressed) == point_records(modern), "Uncompressed LAS fallback changed records")
        if args.pdal:
            adaptive = scratch / "adaptive.copc.laz"
            adaptive_points = 200_003
            generate_fixture(args.pdal, scratch / "generate-adaptive.json", [
                {"type": "readers.faux", "bounds": "([2594000,2596000],[1227500,1227501],[700,701])",
                 "count": adaptive_points, "mode": "ramp"},
                {"type": "writers.copc", "filename": str(adaptive), "fixed_seed": True}
            ])
            data = adaptive.read_bytes()
            start = struct.unpack_from("<I", data, 96)[0]
            table = struct.unpack_from("<Q", data, start)[0]
            chunks = struct.unpack_from("<I", data, table + 4)[0]
            require(chunks > 4, f"Expected more adaptive LAZ chunks than workers, got {chunks} chunk(s)")
            print(f"{adaptive.name}: {chunks} chunks for four workers", flush=True)
            check_modes(command, modes, scratch, adaptive, [stats], adaptive_points, adaptive_points,
                        {2: adaptive_points})
            if args.product:
                check_wide_chunk(command, modes, scratch, args.pdal, stats)
        check_corruption(command, modes, scratch)
        check_cancel(command, modes[1:], scratch, args.cancel_source)


if __name__ == "__main__":
    main()
