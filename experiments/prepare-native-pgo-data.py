"""Create deterministic training excerpts and a disjoint modern holdout with PDAL."""

import argparse
import hashlib
import json
from pathlib import Path
import struct
import subprocess


TRAIN_POINTS = 2_000_000
AUTZEN_POINTS = 10_653_336
DATA = Path(".cache/test-data")


def digest(path):
    result = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1 << 20), b""):
            result.update(block)
    return result.hexdigest()


def inspect(path, point_format, width, count):
    with path.open("rb") as stream:
        header = stream.read(375)
        if len(header) < 375 or header[:4] != b"LASF" or header[104] != (0x80 | point_format):
            raise RuntimeError(f"Unexpected LAZ point format in {path}")
        actual, = struct.unpack_from("<Q" if point_format >= 6 else "<I", header, 247 if point_format >= 6 else 107)
        if actual != count or struct.unpack_from("<H", header, 105)[0] != width:
            raise RuntimeError(f"Unexpected LAS point count or record size in {path}")
        stream.seek(struct.unpack_from("<I", header, 96)[0])
        table, = struct.unpack("<Q", stream.read(8))
        stream.seek(table)
        version, chunks = struct.unpack("<II", stream.read(8))
        if version != 0 or chunks <= 4:
            raise RuntimeError(f"Expected a valid LAZ table with more than four chunks in {path}")
    return chunks


def srs(pdal, source):
    output = subprocess.check_output([pdal, "info", "--summary", "--driver=readers.las", source], text=True)
    return json.loads(output)["summary"].get("srs")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("pdal", type=Path, help="PDAL 2.10.2 executable")
    parser.add_argument("output_dir", type=Path)
    args = parser.parse_args()
    pdal = args.pdal.resolve()
    version = subprocess.check_output([pdal, "--version"], text=True)
    if not any(line.startswith("pdal 2.10.2 ") for line in version.splitlines()):
        raise RuntimeError(f"Expected PDAL 2.10.2, got {version.strip()}")

    legacy = (DATA / "so-2019.laz").resolve()
    modern = (DATA / "autzen-classified.copc.laz").resolve()
    inspect(legacy, 3, 34, 14_566_627)
    inspect(modern, 7, 36, AUTZEN_POINTS)
    modern_srs = srs(pdal, modern)
    if not modern_srs:
        raise RuntimeError("Expected a CRS in the original Autzen COPC")

    work = args.output_dir.resolve()
    work.mkdir(parents=True, exist_ok=True)
    splits = [
        ("train-so2019.laz", legacy, 0, TRAIN_POINTS, 3, 34),
        ("train-autzen.copc.laz", modern, 0, TRAIN_POINTS, 7, 36),
        ("holdout-autzen.copc.laz", modern, TRAIN_POINTS, AUTZEN_POINTS - TRAIN_POINTS, 7, 36),
    ]
    for name, *_ in splits:
        target = work / name
        if target.exists():
            raise RuntimeError(f"Split already exists: {target}")
    prepared = []
    for name, source, start, count, point_format, width in splits:
        target = work / name
        layered = point_format == 7
        reader = {"type": "readers.las", "filename": str(source), "start": start, "count": count, "threads": 4}
        writer = {"filename": str(target), "forward": "all", "extra_dims": "all"}
        if layered:
            writer.update(type="writers.copc", fixed_seed=True)
        else:
            writer.update(type="writers.las", minor_version=2, dataformat_id=3, compression=True)
        config = target.with_suffix(".json")
        config.write_text(json.dumps([reader, writer], indent=2) + "\n", encoding="utf-8")
        command = [pdal, "pipeline", config]
        if not layered:
            command.append("--stream")
        subprocess.run(command, check=True)
        chunks = inspect(target, point_format, width, count)
        if layered and srs(pdal, target) != modern_srs:
            raise RuntimeError(f"Autzen CRS differs in {target}")
        prepared.append({"file": name, "source": str(source), "start": start, "points": count,
                         "format": point_format, "record_bytes": width, "chunks": chunks,
                         "bytes": target.stat().st_size, "sha256": digest(target)})
        print(f"{name}: {count} points, format {point_format}, {chunks} chunks", flush=True)

    (work / "inputs.json").write_text(json.dumps({"pdal": version.strip(), "splits": prepared}, indent=2) + "\n",
                                     encoding="utf-8")


if __name__ == "__main__":
    main()
