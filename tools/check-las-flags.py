"""Compare LAS flag selection and class counts with PDAL 2.10.2."""

import argparse
from decimal import Decimal
import filecmp
import json
from pathlib import Path
import re
import runpy
import subprocess
import tempfile


REFERENCE = runpy.run_path(str(Path(__file__).with_name("check-real-data.py")))
compare_stats = REFERENCE["compare_stats"]
require = REFERENCE["require"]
run = REFERENCE["run"]

DATASETS = (
    ("so-2019.laz", 14_566_627, "([2594100,2594300],[1227600,1227800])", 0),
    ("autzen-classified.copc.laz", 10_653_336, "([637200,637400],[852800,853000])", 1),
)
DIMENSIONS = "Classification,Synthetic,KeyPoint,Withheld,Overlap"
EXPRESSION = "Withheld == 0 && Synthetic == 0 && (Classification == 2 || KeyPoint == 1 || Overlap == 1)"


def text_writer(target):
    return {"type": "writers.text", "filename": str(target),
            "order": "X,Y,Z,Intensity,ReturnNumber,NumberOfReturns," + DIMENSIONS,
            "keep_unspecified": False, "precision": 12}


def compare_stage(java, pdal, count):
    compare_stats([java], pdal, count)
    actual = next(item["bins"] for item in java["dimensions"] if item["name"] == "Classification")
    expected = next(item["bins"] for item in pdal if item["name"] == "Classification")
    normalized = {}
    for key, value in expected.items():
        code = Decimal(key)
        require(code == code.to_integral_value() and 0 <= code <= 255, f"Invalid PDAL classification {key}")
        normalized[str(int(code))] = value
    require(actual == normalized and sum(actual.values()) == count,
            f"Classification bins differ: {actual} (Java), {normalized} (PDAL)")
    require(list(actual) == sorted(actual, key=int), f"Java bins out of order: {actual}")
    print(f"Classification bins for {count:,} points: {actual}", flush=True)


def check_dataset(cli, pdal, data, filename, point_count, bounds, key_point):
    source = (data / filename).resolve()
    require(source.is_file(), f"Missing real-data input: {source}")
    stats = {"type": "filters.stats", "dimensions": DIMENSIONS, "count": "Classification"}
    stages = [
        {"type": "readers.las", "filename": str(source)},
        stats,
        {"type": "filters.crop", "bounds": bounds},
        {"type": "filters.expression", "expression": EXPRESSION},
        stats,
    ]
    print(f"{source.name}: {EXPRESSION}, bounds {bounds}", flush=True)
    with tempfile.TemporaryDirectory(prefix="pointcloud-flags-") as scratch:
        work = Path(scratch)
        pipeline = work / "pipeline.json"
        outputs = []
        for workers in (1, 4):
            las, metadata = work / f"java-{workers}.las", work / f"java-{workers}.json"
            pipeline.write_text(json.dumps(stages + [
                {"type": "writers.las", "filename": str(las), "forward": "all",
                 "extra_dims": "all", "compression": False},
            ]))
            run([cli, "pipeline", pipeline, "--metadata", metadata, "--laz-workers", str(workers)])
            outputs.append((las, metadata))

        (serial_las, serial_json), (parallel_las, parallel_json) = outputs
        require(filecmp.cmp(serial_las, parallel_las, shallow=False), "Serial and parallel Java LAS differ")
        require(serial_json.read_bytes() == parallel_json.read_bytes(), "Serial and parallel Java metadata differ")

        pdal_json = work / "pdal.json"
        pdal_csv = work / "pdal.csv"
        pipeline.write_text(json.dumps(stages + [text_writer(pdal_csv)]))
        run([pdal, "pipeline", pipeline, "--stream", "--readers.las.threads=4", "--metadata", pdal_json])

        java = json.loads(serial_json.read_text())
        native = json.loads(pdal_json.read_text())["stages"]
        require(java["pointsRead"] == native["readers.las"]["count"] == point_count, "Input read count differs")
        selected = java["pointsSelected"]
        require(0 < selected == java["pointsWritten"] < point_count, f"Unexpected selection: {selected}")
        require(len(java["statistics"]) == len(native["filters.stats"]) == 2, "Expected two statistics stages")
        for actual, expected, count in zip(java["statistics"], native["filters.stats"], (point_count, selected)):
            compare_stage(actual, expected["statistic"], count)

        key_point_stats = next(item for item in java["statistics"][1]["dimensions"] if item["name"] == "KeyPoint")
        require(key_point_stats["maximum"] == key_point, "Unexpected KeyPoint flag in the selection")

        java_csv = work / "java.csv"
        pipeline.write_text(json.dumps([{"type": "readers.las", "filename": str(serial_las)}, text_writer(java_csv)]))
        run([pdal, "pipeline", pipeline, "--stream"])
        require(filecmp.cmp(java_csv, pdal_csv, shallow=False),
                "Selected point coordinates, attributes, flags or order differ from PDAL")
        print(f"{selected:,} selected records in file order match PDAL. Java LAS and JSON are byte-identical.",
              flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("pdal", nargs="?", default="pdal", help="PDAL 2.10.2 executable")
    parser.add_argument("--cli", type=Path, default=Path("build/install/pointcloud/bin/pointcloud"))
    parser.add_argument("--data", type=Path, default=Path(".cache/test-data"), help="directory of cached inputs")
    args = parser.parse_args()
    version = subprocess.check_output([args.pdal, "--version"], text=True)
    require(re.search(r"^pdal 2\.10\.2(?:\s|$)", version, re.MULTILINE), f"Expected PDAL 2.10.2:\n{version}")
    for dataset in DATASETS:
        check_dataset(args.cli, args.pdal, args.data, *dataset)


if __name__ == "__main__":
    main()
