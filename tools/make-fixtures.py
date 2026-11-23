"""Create small PointCloud Java LAS fixtures. Pass the PDAL executable as argv[1]."""

from pathlib import Path
import struct
import subprocess
import sys
import tempfile


def vlr(user, record, payload, extended=False):
    length = "Q" if extended else "H"
    return struct.pack(f"<H16sH{length}32s", 0, user, record, len(payload), b"Fixture") + payload


directory = Path("src/test/resources/las")
directory.mkdir(parents=True, exist_ok=True)
scales = (0.01, 0.02, 0.001)
offsets = (2594000., 1227500., 700.)
for fmt, length in [(0, 20), (1, 28), (2, 26), (3, 34), (6, 30), (7, 36), (8, 40)]:
    modern = fmt >= 6
    points = bytearray()
    returns = [0] * 15
    coordinates = [[], [], []]
    for i, classification in enumerate([2, 6, 5, 2, 1]):
        position = (i * 200 - 200, -i * 70, i * i * 40 - 15)
        for values, value, scale, offset in zip(coordinates, position, scales, offsets):
            values.append(value * scale + offset)
        intensity = [65535, 20, 105, 35000, 0][i]
        ret = i + 1
        returns[ret - 1] += 1
        points += struct.pack("<iiiH", *position, intensity)
        if modern:
            points += struct.pack("<BBBBhHd", ret | 9 << 4, 0xC0 | (i % 4) << 4 | (i & 7),
                                  classification, 200 + i, -900 + i * 20, 60000 + i, 1000. + i / 8)
        else:
            points += struct.pack("<BBbBH", ret | 5 << 3 | 0xC0, classification | (i & 7) << 5,
                                  -22 + i, 200 + i, 60000 + i)
            if fmt in (1, 3):
                points += struct.pack("<d", 1000. + i / 8)
        if fmt in (2, 3, 7, 8):
            points += struct.pack("<HHH", 65000 + i, 1200 - i, 40000 + i * 2)
        if fmt == 8:
            points += struct.pack("<HH", 62000 + i, 50000 + i * 2)
    extra = bytearray(192)
    extra[2] = 3  # LAS Extra Bytes unsigned short.
    extra[4:14] = b"Confidence"
    records = vlr(b"fixture", 801, b"opaque VLR")
    extended = b""
    if fmt == 8:
        records += vlr(b"LASF_Spec", 4, extra)
        extended = vlr(b"fixture", 802, b"opaque EVLR", extended=True)
    header = bytearray(375 if modern else 227)
    header[0:4] = b"LASF"
    struct.pack_into("<H", header, 6, 17 if modern else 1)
    header[24:26] = bytes((1, 4 if modern else 2))
    header[58:73] = b"Pointcloud test"
    start = len(header) + len(records)
    struct.pack_into("<HIIBH", header, 94, len(header), start, 2 if fmt == 8 else 1, fmt, length)
    struct.pack_into("<6d", header, 131, *scales, *offsets)
    for i, values in enumerate(coordinates):
        struct.pack_into("<dd", header, 179 + i * 16, max(values), min(values))
    if modern:
        struct.pack_into("<QIQ15Q", header, 235, start + len(points) if extended else 0,
                         bool(extended), 5, *returns)
    else:
        struct.pack_into("<6I", header, 107, 5, *returns[:5])
    las = directory / f"format-{fmt}.las"
    laz = las.with_suffix(".laz")
    las.write_bytes(header + records + points + extended)
    extra_dims = "Confidence=uint16" if fmt == 8 else "all"
    subprocess.run([sys.argv[1], "translate", str(las), str(laz), "--stream",
                    "--writers.las.forward=all", f"--writers.las.extra_dims={extra_dims}"], check=True)

# Two LazPerf chunks with a short final chunk. Raw X is the point's index.
with tempfile.TemporaryDirectory() as scratch:
    las = Path(scratch) / "chunks.las"
    base = bytearray((directory / "format-3.las").read_bytes())
    start = struct.unpack_from("<I", base, 96)[0]
    record = base[start:start + 34]
    count = 50_003
    struct.pack_into("<6I", base, 107, count, count, 0, 0, 0, 0)
    struct.pack_into("<dd", base, 179, offsets[0] + (count - 1) * scales[0], offsets[0])
    with las.open("wb") as output:
        output.write(base[:start])
        for index in range(count):
            struct.pack_into("<i", record, 0, index)
            output.write(record)
    subprocess.run([sys.argv[1], "translate", str(las), str(directory / "chunks.laz"),
                    "--stream", "--writers.las.forward=all"], check=True)
