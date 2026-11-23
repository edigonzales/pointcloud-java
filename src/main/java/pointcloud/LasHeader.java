package pointcloud;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;

/** Original LAS header values. Bounds describe the file header. */
public final class LasHeader {
    private final ByteBuffer bytes;
    private final long fileSize;
    private final long pointCount;
    private final long pointEnd;
    final byte[] laszip;

    LasHeader(FileChannel source) throws IOException {
        fileSize = source.size();
        var base = read(source, 0, 227);
        if (base.getInt(0) != 0x4653414c) {
            throw new IOException("Invalid LASF signature");
        }
        int minor = Byte.toUnsignedInt(base.get(25));
        int recordFormat = Byte.toUnsignedInt(base.get(104));
        int format = recordFormat & 63;
        if (format == 4 || format == 5 || format == 9 || format == 10) {
            throw new IOException("Waveform point format " + format + " is unsupported");
        }
        if (base.get(24) != 1 || (minor == 2 ? format > 3 : minor != 4 || format < 6 || format > 8)) {
            throw new IOException("Unsupported LAS version and point format: "
                    + base.get(24) + "." + minor + " / " + format);
        }
        if ((recordFormat & 64) != 0) {
            throw new IOException("Unsupported LAS compression flag");
        }
        int headerSize = base.getChar(94);
        long pointOffset = uint(base, 96);
        if (headerSize < (minor == 4 ? 375 : 227) || pointOffset < headerSize || pointOffset > fileSize) {
            throw new IOException("Invalid LAS header size or point data offset");
        }
        bytes = headerSize == 227 ? base : read(source, 0, headerSize);
        if (recordLength() < standardLength(format)) {
            throw new IOException("LAS record length is too short for point format " + format);
        }
        for (int axis = 0; axis < 3; axis++) {
            double scale = scale(axis);
            if (!Double.isFinite(scale) || scale <= 0 || !Double.isFinite(offset(axis))
                    || !Double.isFinite(coordinate(axis, Integer.MIN_VALUE))
                    || !Double.isFinite(coordinate(axis, Integer.MAX_VALUE))) {
                throw new IOException("Invalid LAS coordinate scale or offset");
            }
        }
        if (minor == 4 && (bytes.getLong(227) != 0 || (bytes.getChar(6) & 6) != 0)) {
            throw new IOException("Waveform data references are unsupported");
        }
        pointCount = minor == 4 ? bytes.getLong(247) : uint(bytes, 107);
        if (pointCount < 0) {
            throw new IOException("LAS point count exceeds signed 64-bit range");
        }
        long evlrCount = evlrCount();
        long evlrStart = evlrStart();
        if ((evlrCount == 0 && evlrStart != 0)
                || (evlrCount != 0 && (evlrStart < pointOffset || evlrStart > fileSize))) {
            throw new IOException("Invalid LAS EVLR offset");
        }
        pointEnd = evlrCount == 0 ? fileSize : evlrStart;
        if (!compressed() && pointCount > (pointEnd - pointOffset) / recordLength()) {
            throw new EOFException("Truncated LAS point records");
        }
        var metadata = new byte[1][];
        visitMetadata(source, false, vlr -> {
            if (vlr.isLaszip()) {
                if (metadata[0] != null || vlr.length() < 40 || vlr.length() > 64) {
                    throw new IOException("Duplicate or invalid LASzip VLR");
                }
                metadata[0] = read(source, vlr.payload(), (int) vlr.length()).array();
            }
            checkWaveform(vlr);
        });
        visitMetadata(source, true, LasHeader::checkWaveform);
        laszip = metadata[0];
        if (compressed() != (laszip != null)) {
            throw new IOException("LASzip VLR and point format compression flag disagree");
        }
    }

    private static void checkWaveform(VariableRecord vlr) throws IOException {
        if ((vlr.userId().equals("LASF_Spec") && (vlr.recordId() == 65535
                || vlr.recordId() >= 100 && vlr.recordId() <= 354))) {
            throw new IOException("Waveform metadata is unsupported");
        }
    }

    static int standardLength(int format) {
        return switch (format) {
            case 0 -> 20;
            case 1 -> 28;
            case 2 -> 26;
            case 3 -> 34;
            case 6 -> 30;
            case 7 -> 36;
            case 8 -> 38;
            default -> throw new IllegalArgumentException("Unsupported LAS point format " + format);
        };
    }

    public int versionMinor() {
        return Byte.toUnsignedInt(bytes.get(25));
    }

    public int pointFormat() {
        return bytes.get(104) & 63;
    }

    public int recordLength() {
        return bytes.getChar(105);
    }

    public boolean compressed() {
        return (bytes.get(104) & 128) != 0;
    }

    public long pointCount() {
        return pointCount;
    }

    public double scale(int axis) {
        return bytes.getDouble(131 + axis * 8);
    }

    public double offset(int axis) {
        return bytes.getDouble(155 + axis * 8);
    }

    public double minimum(int axis) {
        return bytes.getDouble(187 + axis * 16);
    }

    public double maximum(int axis) {
        return bytes.getDouble(179 + axis * 16);
    }

    public double coordinate(int axis, int raw) {
        return raw * scale(axis) + offset(axis);
    }

    public long pointOffset() {
        return uint(bytes, 96);
    }

    long pointEnd() {
        return pointEnd;
    }

    private long evlrStart() {
        return versionMinor() == 4 ? bytes.getLong(235) : 0;
    }

    private long evlrCount() {
        return versionMinor() == 4 ? uint(bytes, 243) : 0;
    }

    ByteBuffer copyHeader() {
        return ByteBuffer.wrap(bytes.array().clone()).order(ByteOrder.LITTLE_ENDIAN);
    }

    /** Visit metadata in file order. Payloads stay on disk. */
    long visitMetadata(FileChannel source, boolean extended, RecordVisitor visitor) throws IOException {
        int baseSize = extended ? 60 : 54;
        long start = extended ? evlrStart() : bytes.capacity();
        long count = extended ? evlrCount() : uint(bytes, 100);
        long end = extended ? fileSize : pointOffset();
        if (count > (end - start) / baseSize) {
            throw new IOException("Invalid LAS variable record count");
        }
        var header = ByteBuffer.allocate(baseSize).order(ByteOrder.LITTLE_ENDIAN);
        for (long i = 0; i < count; i++) {
            if (end - start < baseSize) {
                throw new IOException("Truncated LAS variable record header at byte " + start);
            }
            readFully(source, start, header.clear());
            long length = extended ? header.getLong(20) : header.getChar(20);
            long payload = start + baseSize;
            if (length < 0 || length > end - payload) {
                throw new IOException("Invalid LAS variable record length at byte " + start);
            }
            int userLength = 0;
            while (userLength < 16 && header.get(2 + userLength) != 0) {
                userLength++;
            }
            var user = new String(header.array(), 2, userLength, StandardCharsets.US_ASCII).stripTrailing();
            visitor.accept(new VariableRecord(user, header.getChar(18), start, payload, length, extended));
            start = payload + length;
        }
        return start;
    }

    record VariableRecord(String userId, int recordId, long start, long payload, long length, boolean extended) {
        boolean isLaszip() {
            return !extended && userId.equals("laszip encoded") && recordId == 22204;
        }

        boolean isPointIndex() {
            return userId.equalsIgnoreCase("copc") || userId.equalsIgnoreCase("laszip encoded")
                    || userId.equalsIgnoreCase("lastools tile") || userId.equalsIgnoreCase("LAX")
                    || userId.equalsIgnoreCase("LAStools") && (recordId == 10 || recordId == 20);
        }
    }

    interface RecordVisitor {
        void accept(VariableRecord vlr) throws IOException;
    }

    static ByteBuffer read(FileChannel source, long position, int size) throws IOException {
        return readFully(source, position, ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN));
    }

    static ByteBuffer readFully(FileChannel source, long position, ByteBuffer target) throws IOException {
        int start = target.position();
        while (target.hasRemaining()) {
            int read = source.read(target, position);
            if (read < 0) {
                throw new EOFException("Unexpected end of LAS file at byte " + position);
            }
            position += read;
        }
        return target.flip().position(start);
    }

    static long uint(ByteBuffer source, int offset) {
        return Integer.toUnsignedLong(source.getInt(offset));
    }
}
