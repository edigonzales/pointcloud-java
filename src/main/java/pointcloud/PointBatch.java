package pointcloud;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Objects;

/** A reader-owned, reusable block of complete little-endian LAS records. */
public final class PointBatch {
    final LasHeader header;
    final ByteBuffer records;
    int size;

    PointBatch(LasHeader header, int capacity) {
        this.header = header;
        records = ByteBuffer.allocate(capacity * header.recordLength()).order(ByteOrder.LITTLE_ENDIAN);
    }

    public int size() {
        return size;
    }

    public int capacity() {
        return records.capacity() / header.recordLength();
    }

    public int rawX(int point) {
        return records.getInt(start(point));
    }

    public int rawY(int point) {
        return records.getInt(start(point) + 4);
    }

    public int rawZ(int point) {
        return records.getInt(start(point) + 8);
    }

    public double x(int point) {
        return header.coordinate(0, rawX(point));
    }

    public double y(int point) {
        return header.coordinate(1, rawY(point));
    }

    public double z(int point) {
        return header.coordinate(2, rawZ(point));
    }

    public int intensity(int point) {
        return records.getChar(start(point) + 12);
    }

    public int returnNumber(int point) {
        return records.get(start(point) + 14) & (header.pointFormat() < 6 ? 7 : 15);
    }

    public int classification(int point) {
        int start = start(point);
        return header.pointFormat() < 6 ? records.get(start + 15) & 31
                : Byte.toUnsignedInt(records.get(start + 16));
    }

    public void copyRecord(int point, byte[] destination, int offset) {
        System.arraycopy(records.array(), start(point), destination, offset, header.recordLength());
    }

    int start(int point) {
        return Objects.checkIndex(point, size) * header.recordLength();
    }
}
