package pointcloud;

import java.lang.foreign.Arena;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;

/** Compare the uncompressed LAS reader with an Arena-backed mapped scan. */
public final class LasIoScan {
    private static final ValueLayout.OfInt INT = ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
    private static final Path SAMPLE = Path.of(".cache/experiments/kernel.las");

    public static void main(String[] args) throws Exception {
        boolean mapped = args[0].equals("mapped");
        long start = System.nanoTime();
        Scan expected = mapped ? mappedScan() : channelScan();
        double first = (System.nanoTime() - start) / 1e6;
        int repeats = 20;
        var times = new double[repeats];
        for (int i = 0; i < repeats; i++) {
            start = System.nanoTime();
            var actual = mapped ? mappedScan() : channelScan();
            times[i] = (System.nanoTime() - start) / 1e6;
            if (!expected.equals(actual)) {
                throw new IllegalStateException("I/O results differ between scans");
            }
        }
        Arrays.sort(times);
        double median = (times[9] + times[10]) / 2;
        System.out.printf(Locale.ROOT, "%d,%d,%d,%d,%.3f,%.3f,%.3f,%.3f%n", expected.count,
                expected.x, expected.y, expected.z, first, times[0], median, times[19]);
    }

    private static Scan channelScan() throws Exception {
        long count = 0, x = 0, y = 0, z = 0;
        try (var reader = new LasReader(SAMPLE, LasReader.DEFAULT_BATCH_SIZE)) {
            for (var batch = reader.readBatch(); batch != null; batch = reader.readBatch()) {
                count += batch.size();
                for (int point = 0; point < batch.size(); point++) {
                    x += batch.rawX(point);
                    y += batch.rawY(point);
                    z += batch.rawZ(point);
                }
            }
        }
        return new Scan(count, x, y, z);
    }

    private static Scan mappedScan() throws Exception {
        long x = 0, y = 0, z = 0;
        try (var channel = FileChannel.open(SAMPLE); var arena = Arena.ofConfined()) {
            var header = new LasHeader(channel);
            long length = header.pointCount() * header.recordLength();
            var segment = channel.map(FileChannel.MapMode.READ_ONLY, header.pointOffset(), length, arena);
            for (long offset = 0; offset < length; offset += header.recordLength()) {
                x += segment.get(INT, offset);
                y += segment.get(INT, offset + 4);
                z += segment.get(INT, offset + 8);
            }
            return new Scan(header.pointCount(), x, y, z);
        }
    }

    private record Scan(long count, long x, long y, long z) {}
}
