package com.github.mreutegg.laszip4j.laszip;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;

/**
 * Internal adapter from the vendored point decoder to little-endian LAS records.
 * A decoder belongs to one sequential reader.
 */
public final class LazDecoder implements AutoCloseable {
    private final ByteStreamIn stream;
    private final LASreadPoint reader = new LASreadPoint(LASzip.LASZIP_DECOMPRESS_SELECTIVE_ALL);
    private final PointDataRecord[] records;
    private final long count;
    private long limit;
    private long position;
    private boolean finished;

    public LazDecoder(FileChannel source, long offset, long end, long count, int format, int length, byte[] vlr)
            throws IOException {
        var zip = new LASzip();
        if (!zip.unpack(vlr, vlr.length) || !zip.check()) {
            throw new IOException("Invalid LASzip VLR: " + zip.get_error());
        }
        if (zip.compressor != (format < 6 ? 2 : 3)) {
            throw new IOException("Unsupported LAZ compressor " + (int) zip.compressor + " for format " + format);
        }
        if ((zip.options & 1) != 0 || zip.number_of_special_evlrs > 0) {
            throw new IOException("Unsupported LAZ compatibility mode or special EVLR reference");
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
            if (item.version != (format < 6 ? 2 : 3)) {
                throw new IOException("Unsupported LAZ item version " + (int) item.version + " at item " + i);
            }
        }
        if (!reader.setup(zip.num_items, zip.items, zip)) {
            throw new IOException("Unsupported LAZ item codec");
        }
        this.count = count;
        limit = count;
        records = new PointDataRecord[zip.num_items];
        stream = new ByteStreamInFile(source, end);
        try {
            if (!stream.seek(offset) || !reader.init(stream)) {
                throw new IOException("Cannot start LAZ decoder");
            }
            reader.initialiseChunks(count, end);
        } catch (RuntimeException | IOException e) {
            try {
                stream.close();
            } catch (IOException close) {
                e.addSuppressed(close);
            }
            throw new IOException("Invalid LAZ point stream: " + e.getMessage(), e);
        }
    }

    public record Chunk(long offset, long end, long firstPoint, long points) {}

    /** Zero chunks means the file is empty or has no random-access table. */
    public int chunkCount() {
        return reader.chunkCount();
    }

    public Chunk chunk(int index) {
        java.util.Objects.checkIndex(index, chunkCount());
        long first = reader.chunkFirstPoint(index);
        return new Chunk(reader.chunkOffset(index), reader.chunkOffset(index + 1),
                first, reader.chunkFirstPoint(index + 1) - first);
    }

    /** Decode one table-defined chunk, starting at its uncompressed seed point. */
    public void startChunk(int index) {
        var chunk = chunk(index);
        reader.startChunk(index);
        position = chunk.firstPoint;
        limit = position + chunk.points;
        finished = false;
    }

    /**
     * Append one LAS record to the destination. At the declared end, validate
     * the decoder state before reporting EOF.
     */
    public boolean read(ByteBuffer destination) throws IOException {
        if (position == limit) {
            if (!finished && !reader.check_end()) {
                throw failure(reader.error());
            }
            finished = true;
            return false;
        }
        if (!reader.read(records)) {
            throw failure(reader.error());
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
