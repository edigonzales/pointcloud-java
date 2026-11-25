"""Compare all output point records from two installed CLIs in source order."""

import argparse
import hashlib
import json
from pathlib import Path
import runpy
import struct
import tempfile


REFERENCE = runpy.run_path(str(Path(__file__).with_name("check-real-data.py")))
read_las_header = REFERENCE["read_las_header"]
require = REFERENCE["require"]
run = REFERENCE["run"]


def layout(header):
    record_size, count = struct.unpack_from("<HI", header, 105)
    if header[24:26] == b"\x01\x04":
        count = struct.unpack_from("<Q", header, 247)[0] or count
    return header[104] & 63, record_size, count


def record_digest(path, expected):
    with path.open("rb") as file:
        header = read_las_header(file)
        require(header[104] < 64 and layout(header) == expected, f"Unexpected output point layout: {path}")
        file.seek(struct.unpack_from("<I", header, 96)[0])
        _, record_size, count = expected
        remaining = record_size * count
        digest = hashlib.sha256()
        while remaining:
            data = file.read(min(remaining, 1 << 20))
            require(data, f"Truncated output point records: {path}")
            digest.update(data)
            remaining -= len(data)
        return digest.hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("baseline", type=Path, help="installed baseline CLI launcher")
    parser.add_argument("candidate", type=Path, help="installed candidate CLI launcher")
    parser.add_argument("sources", type=Path, nargs="+", help="input LAS/LAZ files")
    parser.add_argument("--additional-candidate", action="append", default=[], type=Path,
                        help="additional CLI launcher to compare to the same baseline")
    args = parser.parse_args()
    launchers = [("baseline", args.baseline), ("candidate", args.candidate)]
    launchers.extend((f"candidate-{index}", cli) for index, cli in enumerate(args.additional_candidate, 2))

    with tempfile.TemporaryDirectory(prefix="full-records-", dir=".cache") as directory:
        work = Path(directory)
        config, target = work / "pipeline.json", work / "out.las"
        for source in args.sources:
            with source.open("rb") as file:
                expected = layout(read_las_header(file))
            config.write_text(json.dumps([
                {"type": "readers.las", "filename": str(source.resolve())},
                {"type": "writers.las", "filename": str(target.resolve()), "compression": False,
                 "forward": "all", "extra_dims": "all"},
            ]), encoding="utf-8")

            baseline_digest = None
            for workers in (1, 4):
                for name, cli in launchers:
                    run([cli.resolve(), "pipeline", config, "--laz-workers", str(workers)])
                    digest = record_digest(target, expected)
                    target.unlink()
                    if baseline_digest is None:
                        baseline_digest = digest
                    require(digest == baseline_digest, f"Point records differ for {source}, {name}, workers={workers}")
                    print(f"{source.name} {name} workers={workers}: {expected[2]:,} records, sha256 {digest}", flush=True)


if __name__ == "__main__":
    main()
