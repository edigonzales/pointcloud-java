"""Compare complete real-data pipelines against an installed PDAL 2.10.2."""

import json
import math
import os
from pathlib import Path
import struct
import subprocess
import sys
import tempfile
import time


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def run(args, *, capture=False):
    started = time.perf_counter()
    result = subprocess.run(args, capture_output=capture, text=True, check=True,
                            env=dict(os.environ, JAVA_OPTS="-Xmx512m"))
    print(f"{time.perf_counter() - started:.2f} s  " + " ".join(map(str, args)), flush=True)
    if capture:
        return json.loads(result.stdout)


def projection_and_other_vlrs(path):
    with path.open("rb") as file:
        header = file.read(227)
        size, _, count = struct.unpack_from("<HII", header, 94)
        file.seek(size)
        records = []
        for _ in range(count):
            _, user, record, size = struct.unpack("<H16sHH", file.read(22))
            file.read(32)
            data = file.read(size)
            user = user.rstrip(b"\0 ")
            if user != b"laszip encoded":
                records.append((user, record, data))
        return records


def read_las_header(file):
    header = file.read(227)
    require(len(header) == 227 and header[:4] == b"LASF", f"Invalid LAS header in {file.name}")
    if header[24:26] == b"\x01\x04":
        header += file.read(148)
        require(len(header) == 375, f"Truncated LAS 1.4 header in {file.name}")
    return header


def compare_las(java, reference, count, *, allow_one_ulp_bounds=False):
    with java.open("rb") as left, reference.open("rb") as right:
        lh, rh = read_las_header(left), read_las_header(right)
        version = lh[24:26]
        require(version == rh[24:26] and version in (b"\x01\x02", b"\x01\x04"), "LAS version differs")
        require(lh[104:131] == rh[104:131], "Format, point length or legacy point counts differ")
        require(lh[104] < 64, "Point comparison requires uncompressed LAS")
        left_quantization, right_quantization = struct.unpack_from("<6d", lh, 131), struct.unpack_from("<6d", rh, 131)
        require(left_quantization == right_quantization,
                f"LAS scale or offset differs: {left_quantization}/{right_quantization}")
        if lh[131:179] != rh[131:179]:
            print(f"LAS scale/offset signed-zero encoding differs (Java/PDAL): {left_quantization}/{right_quantization}")
        left_bounds, right_bounds = struct.unpack_from("<6d", lh, 179), struct.unpack_from("<6d", rh, 179)
        if left_bounds != right_bounds:
            differences = [(axis, a, b) for axis, a, b in zip(("maxX", "minX", "maxY", "minY", "maxZ", "minZ"),
                                                            left_bounds, right_bounds) if a != b]
            require(allow_one_ulp_bounds and version == b"\x01\x04"
                    and all(math.nextafter(a, b) == b for _, a, b in differences),
                    f"LAS header bounds differ (Java/PDAL): {differences}")
            print(f"LAS 1.4 header bounds differ by one ULP (Java/PDAL): {differences}")
        length = struct.unpack_from("<H", lh, 105)[0]
        if version == b"\x01\x04":
            require(lh[247:375] == rh[247:375], "LAS 1.4 point count or returns differ")
            actual = struct.unpack_from("<Q", lh, 247)[0]
            if lh[104] >= 6:
                require(lh[107:131] == bytes(24), "LAS 1.4 legacy count fields must be zero for modern point formats")
        else:
            actual = struct.unpack_from("<I", lh, 107)[0]
        require(actual == count, f"{java}: LAS header count differs from reported output")
        left.seek(struct.unpack_from("<I", lh, 96)[0])
        right.seek(struct.unpack_from("<I", rh, 96)[0])
        mismatches = 0
        first_difference = ""
        for start in range(0, count, 8192):
            size = min(count - start, 8192) * length
            a, b = left.read(size), right.read(size)
            require(len(a) == len(b) == size, "Truncated LAS comparison input")
            if a != b:
                for offset in range(0, size, length):
                    left_point, right_point = a[offset:offset + length], b[offset:offset + length]
                    if left_point != right_point:
                        if mismatches == 0:
                            first_difference = f"; first at {start + offset // length}: {left_point.hex()}/{right_point.hex()}"
                        mismatches += 1
        for file, header in ((left, lh), (right, rh)):
            if version == b"\x01\x04" and struct.unpack_from("<I", header, 243)[0] != 0:
                require(file.tell() == struct.unpack_from("<Q", header, 235)[0], "Invalid EVLR start after point records")
            else:
                require(not file.read(1), "Unexpected bytes after the point records")
    require(mismatches == 0, f"{java}: {mismatches} records differ{first_difference}")
    print(f"{count} complete records, 0 differences, LAS bytes {java.stat().st_size}/{reference.stat().st_size}")


def compare_stats(java, reference, expected_count):
    actual = {d["name"]: d for d in java[0]["dimensions"]} if java else {}
    expected = {d["name"]: d for d in reference}
    require(actual.keys() == expected.keys(), "Statistics dimensions differ")
    errors = dict.fromkeys(("minimum", "maximum", "average", "variance", "stddev"), 0.0)
    for name, dimension in actual.items():
        pr = expected[name]
        require(dimension["count"] == pr["count"] == expected_count, f"{name}: statistics count differs")
        for field in errors:
            a, b = dimension[field], pr[field]
            require(math.isclose(a, b, rel_tol=5.1e-10, abs_tol=1e-10), f"{name}.{field}: {a} differs from {b}")
            errors[field] = max(errors[field], abs(a - b))
    if actual:
        print("Maximum absolute statistics differences:", errors)


def check_cancel(cli, work):
    pipeline = json.loads(Path("examples/ground-crop.json").read_text())
    target = work / "interrupted.las"
    pipeline["pipeline"][-1]["filename"] = str(target)
    config = work / "interrupted.json"
    config.write_text(json.dumps(pipeline))
    metadata = work / "interrupted-metadata.json"
    started = time.perf_counter()
    with subprocess.Popen([cli, "pipeline", config, "--metadata", metadata],
                          stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                          env=dict(os.environ, JAVA_OPTS="-Xmx512m")) as process:
        try:
            deadline = time.monotonic() + 8
            while process.poll() is None and not any(work.glob("*.las.part")) and time.monotonic() < deadline:
                time.sleep(0.005)
            require(any(work.glob("*.las.part")), "No active temporary output to interrupt")
            process.terminate()
            process.communicate(timeout=8)
            require(process.returncode != 0, "Interrupted CLI reported success")
            require(not target.exists() and not metadata.exists() and not any(work.glob("*.part")),
                    "Cancelled CLI left output files")
            print(f"SIGTERM, CLI exit {process.returncode}, no target or temporary output, "
                  f"{time.perf_counter() - started:.2f} s")
        finally:
            if process.poll() is None:
                process.kill()
                process.wait()


def main():
    pdal = sys.argv[1] if len(sys.argv) > 1 else "pdal"
    cli = "build/install/pointcloud/bin/pointcloud"
    run([pdal, "--version"])
    result_dir = Path("build/results")
    result_dir.mkdir(parents=True, exist_ok=True)
    for name in ("ground-crop", "decimate", "so-2014"):
        target = result_dir / f"{name}.las"
        require(not os.path.lexists(target), f"Comparison target already exists: {target}")
    with tempfile.TemporaryDirectory(prefix="pdal-check-", dir=".cache") as scratch:
        work = Path(scratch)
        secondary = work / "so-2014.json"
        secondary.write_text(json.dumps([
            ".cache/test-data/so-2014.laz",
            {"type": "filters.stats", "dimensions": "X,Y,Z,Intensity,Classification"},
            {"type": "filters.decimation", "step": 500},
            {"type": "writers.las", "filename": str(result_dir / "so-2014.las"),
             "forward": "all", "extra_dims": "all", "compression": False}
        ]))
        for name in ("ground-crop", "stats", "decimate", "so-2014"):
            year = 2014 if name == "so-2014" else 2019
            input_count = 14_347_366 if year == 2014 else 14_566_627
            pipeline = secondary if year == 2014 else Path("examples") / f"{name}.json"
            target = result_dir / f"{name}.las"
            java_json, pdal_json = work / "java.json", work / "pdal.json"
            java_las, pdal_las = work / "java.las", work / "pdal.las"
            java_json.unlink(missing_ok=True)
            run([cli, "pipeline", pipeline, "--metadata", java_json])
            if name != "stats":
                target.rename(java_las)
            pdal_json.unlink(missing_ok=True)
            run([pdal, "pipeline", pipeline, "--stream", "--metadata", pdal_json])
            java = json.loads(java_json.read_text())
            stages = json.loads(pdal_json.read_text())["stages"]
            require(java["pointsRead"] == stages["readers.las"]["count"] == input_count, "Input point count differs")
            count = java["pointsSelected"]
            expected = {"ground-crop": 195_738, "stats": input_count, "decimate": 128_909, "so-2014": 28_695}[name]
            require(count == expected, f"{name}: unexpected selection count {count}")
            compare_stats(java["statistics"], stages.get("filters.stats", {}).get("statistic", []),
                          input_count if year == 2014 else count)
            if name != "stats":
                target.rename(pdal_las)
                require(java["pointsWritten"] == count, "Output count differs")
                compare_las(java_las, pdal_las, count)
                source_vlrs = projection_and_other_vlrs(Path(f".cache/test-data/so-{year}.laz"))
                require(projection_and_other_vlrs(java_las) == source_vlrs, "Java VLR payloads differ from the input")
                info = run([pdal, "info", "--stats", java_las], capture=True)
                require(all(s["count"] == count for s in info["stats"]["statistic"]), "PDAL read count differs")
                a = run([pdal, "info", "--summary", java_las], capture=True)["summary"]
                b = run([pdal, "info", "--summary", pdal_las], capture=True)["summary"]
                require(a.get("srs") == b.get("srs") and (a.get("srs") is not None) == (year == 2019),
                        "CRS differs between outputs")
                java_las.unlink()
                pdal_las.unlink()
            else:
                require(java["pointsWritten"] == 0, "Null writer wrote points")
        check_cancel(cli, work)


if __name__ == "__main__":
    main()
