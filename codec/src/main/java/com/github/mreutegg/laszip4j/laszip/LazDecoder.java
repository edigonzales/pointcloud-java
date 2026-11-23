package com.github.mreutegg.laszip4j.laszip;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;

/**
 * Internal adapter from the vendored point decoder to little-endian LAS records.
 * A decoder belongs to one sequential reader.
 */
public final class LazDecoder implements AutoCloseable {
    private final ByteStreamIn stream;
    private final LASreadPoint reader = new LASreadPoint(LASzip.LASZIP_DECOMPRESS_SELECTIVE_ALL);
    private final PointDataRecord[] records;
    private final long count;
    private long position;
    private boolean finished;

    public LazDecoder(Path path, long offset, long count, int format, int length, byte[] vlr) throws IOException {
        var zip = new LASzip();
        if (!zip.unpack(vlr, vlr.length) || !zip.check()) {
            throw new IOException("Invalid LASzip VLR: " + zip.get_error());
        }
        if (format < 6 ? zip.compressor != 1 && zip.compressor != 2 : zip.compressor != 3) {
            throw new IOException("Unsupported LAZ compressor " + (int) zip.compressor + " for format " + format);
        }
        var expected = new LASzip();
        if (!expected.setup((byte) format, (char) length, zip.compressor) || expected.num_items != zip.num_items) {
            throw new IOException("LASzip item count does not match the LAS point format");
        }
        for (int i = 0; i < zip.num_items; i++) {
            LASitem item = zip.items[i];
            if (item.type != expected.items[i].type || item.size != expected.items[i].size) {
                throw new IOException("LASzip item " + i + " does not match format " + format);
            }
        }
        if (!reader.setup(zip.num_items, zip.items, zip)) {
            throw new IOException("Unsupported LAZ item codec");
        }
        this.count = count;
        records = new PointDataRecord[zip.num_items];
        var file = new RandomAccessFile(path.toFile(), "r");
        try {
            if (offset < 0 || offset > file.length()) {
                throw new IOException("LAZ point offset outside file");
            }
            stream = new ByteStreamInFile(file);
            stream.seek(offset);
            if (!reader.init(stream)) {
                throw new IOException("Cannot start LAZ decoder");
            }
        } catch (RuntimeException | IOException e) {
            try {
                file.close();
            } catch (IOException close) {
                e.addSuppressed(close);
            }
            throw e;
        }
    }

    /**
     * Append one LAS record to the destination. At the declared end, validate
     * the decoder state before reporting EOF.
     */
    public boolean read(ByteBuffer destination) throws IOException {
        if (position == count) {
            if (!finished && (!reader.check_end() || reader.warning() != null)) {
                throw failure(reader.error() != null ? reader.error() : reader.warning());
            }
            finished = true;
            return false;
        }
        if (!reader.read(records) || reader.warning() != null) {
            throw failure(reader.error() != null ? reader.error() : reader.warning());
        }
        destination.order(ByteOrder.LITTLE_ENDIAN);
        for (PointDataRecord item : records) {
            switch (item) {
                case PointDataRecordPoint10 p -> {
                    putXyzIntensity(destination, p);
                    destination.put(p.Flags).put((byte) p.Classification).put(p.ScanAngleRank)
                            .put((byte) p.UserData).putChar(p.PointSourceID);
                }
                case PointDataRecordPoint14 p -> {
                    putXyzIntensity(destination, p);
                    destination.put(p.ReturnFlags).put(p.ScanFlags).put((byte) p.Classification)
                            .put((byte) p.UserData).putShort(p.ScanAngle).putChar(p.PointSourceID).putLong(p.GPSTime);
                }
                case PointDataRecordGpsTime t -> destination.putLong(t.GPSTime);
                case PointDataRecordRgbNIR c -> destination.putChar(c.R).putChar(c.G).putChar(c.B).putChar(c.NIR);
                case PointDataRecordRGB c -> destination.putChar(c.R).putChar(c.G).putChar(c.B);
                case PointDataRecordBytes bytes -> destination.put(bytes.Bytes);
                default -> throw failure("unsupported point item");
            }
        }
        position++;
        return true;
    }

    private static void putXyzIntensity(ByteBuffer destination, PointDataRecordXYZBase point) {
        destination.putInt(point.X).putInt(point.Y).putInt(point.Z).putChar(point.Intensity);
    }

    private IOException failure(String reason) {
        return new IOException("LAZ decode failed after " + position + " of " + count
                + " points" + (reason == null ? "" : ": " + reason));
    }

    @Override
    public void close() throws IOException {
        reader.done();
        stream.close();
    }
}
