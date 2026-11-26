package pointcloud;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Locale;

/** Extra Bytes VLR for LAS output with an appended HeightAboveGround value. */
final class LasExtraBytes {
    static final String HAG_NAME = "HeightAboveGround";
    static final int VALUE_BYTES = Double.BYTES;
    private static final String USER_ID = "LASF_Spec";
    private static final int RECORD_ID = 4;
    private static final int HEADER_BYTES = 54;
    private static final int DESCRIPTOR_BYTES = 192;
    private static final int MAX_OPAQUE_WIDTH = 255;
    private static final int MAX_VLR_PAYLOAD = 0xffff;
    private static final long MAX_POINT_OFFSET = 0xffff_ffffL;
    private static final int[] SCALAR_WIDTHS = {1, 1, 2, 2, 4, 4, 8, 8, 4, 8};

    final long existingStart;
    final byte[] vlr;

    private LasExtraBytes(long existingStart, byte[] vlr) {
        this.existingStart = existingStart;
        this.vlr = vlr;
    }

    static LasExtraBytes appendHeightAboveGround(LasHeader header, FileChannel source) throws IOException {
        if (header.recordLength() > 0xffff - VALUE_BYTES) {
            throw new IOException("LAS point record length exceeds unsigned 16-bit range with " + HAG_NAME);
        }

        var records = new LasHeader.VariableRecord[1];
        var droppedBytes = new long[1];
        header.visitMetadata(source, false, record -> {
            if (record.isPointIndex()) {
                droppedBytes[0] += record.payload() - record.start() + record.length();
            }
            if (isExtraBytes(record)) {
                if (records[0] != null) {
                    throw new IOException("Multiple LAS Extra Bytes VLRs");
                }
                records[0] = record;
            }
        });
        header.visitMetadata(source, true, record -> {
            if (isExtraBytes(record)) {
                throw new IOException("LAS Extra Bytes in an EVLR are unsupported");
            }
        });

        var existing = records[0];
        int tail = header.recordLength() - LasHeader.standardLength(header.pointFormat());
        int existingPayload = 0;
        ByteBuffer original = null;
        var names = new HashSet<String>();
        if (existing != null) {
            if (existing.length() == 0 || existing.length() % DESCRIPTOR_BYTES != 0) {
                throw new IOException("Invalid LAS Extra Bytes VLR payload length: " + existing.length());
            }
            existingPayload = (int) existing.length();
            original = LasHeader.read(source, existing.start(), HEADER_BYTES + existingPayload);
            for (int start = HEADER_BYTES; start < original.limit(); start += DESCRIPTOR_BYTES) {
                String name = descriptorName(original, start);
                if (HAG_NAME.equalsIgnoreCase(name)) {
                    throw new IOException("LAS Extra Bytes already contain " + HAG_NAME);
                }
                names.add(name.toLowerCase(Locale.ROOT));
                int width = descriptorWidth(original, start);
                if (width > tail) {
                    throw new IOException("LAS Extra Bytes descriptors exceed the point record's extra bytes");
                }
                tail -= width;
            }
        }

        int opaqueDescriptors = (tail + MAX_OPAQUE_WIDTH - 1) / MAX_OPAQUE_WIDTH;
        int payload = existingPayload + (opaqueDescriptors + 1) * DESCRIPTOR_BYTES;
        if (payload > MAX_VLR_PAYLOAD) {
            throw new IOException("LAS Extra Bytes VLR payload exceeds unsigned 16-bit range with " + HAG_NAME);
        }
        int originalBytes = original == null ? 0 : original.capacity();
        int vlrBytes = HEADER_BYTES + payload;
        long outputOffset = header.pointOffset() - droppedBytes[0] + vlrBytes - originalBytes;
        if (outputOffset > MAX_POINT_OFFSET) {
            throw new IOException("LAS point data offset exceeds unsigned 32-bit range");
        }

        var result = ByteBuffer.allocate(vlrBytes).order(ByteOrder.LITTLE_ENDIAN);
        if (original == null) {
            result.position(2).put(USER_ID.getBytes(StandardCharsets.US_ASCII));
            result.putChar(18, (char) RECORD_ID);
            result.position(22).put("Extra Bytes Record".getBytes(StandardCharsets.US_ASCII));
        } else {
            result.put(original);
        }
        result.putChar(20, (char) payload);
        result.position(HEADER_BYTES + existingPayload);
        int suffix = 0;
        while (tail > 0) {
            String name;
            do {
                name = "UndocumentedExtraBytes" + ++suffix;
            } while (!names.add(name.toLowerCase(Locale.ROOT)));
            int width = Math.min(tail, MAX_OPAQUE_WIDTH);
            putDescriptor(result, name, 0, width);
            tail -= width;
        }
        putDescriptor(result, HAG_NAME, 10, 0);
        return new LasExtraBytes(existing == null ? -1 : existing.start(), result.array());
    }

    private static boolean isExtraBytes(LasHeader.VariableRecord record) {
        return record.recordId() == RECORD_ID && record.userId().equals(USER_ID);
    }

    private static String descriptorName(ByteBuffer descriptors, int start) {
        int end = start + 4;
        while (end < start + 36 && descriptors.get(end) != 0) {
            end++;
        }
        return new String(descriptors.array(), start + 4, end - start - 4, StandardCharsets.US_ASCII).stripTrailing();
    }

    private static int descriptorWidth(ByteBuffer descriptors, int start) throws IOException {
        int type = Byte.toUnsignedInt(descriptors.get(start + 2));
        if (type == 0) {
            int width = Byte.toUnsignedInt(descriptors.get(start + 3));
            if (width == 0) {
                throw new IOException("LAS Extra Bytes type 0 descriptor has zero width");
            }
            return width;
        }
        if (type > 30) {
            throw new IOException("Invalid LAS Extra Bytes descriptor type: " + type);
        }
        // Older LAS revisions also used types 11–30 for two- and three-element arrays.
        int scalar = (type - 1) % SCALAR_WIDTHS.length;
        int elements = (type - 1) / SCALAR_WIDTHS.length + 1;
        return SCALAR_WIDTHS[scalar] * elements;
    }

    private static void putDescriptor(ByteBuffer target, String name, int type, int options) {
        int start = target.position();
        target.put(start + 2, (byte) type);
        target.put(start + 3, (byte) options);
        target.position(start + 4).put(name.getBytes(StandardCharsets.US_ASCII));
        target.position(start + DESCRIPTOR_BYTES);
    }
}
