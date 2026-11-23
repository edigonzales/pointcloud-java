import pointcloud.LasReader;
import pointcloud.LasWriter;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Path;

/** Import regression against the P1 Solothurn 2019 baseline. */
class DecodeTile {
    public static void main(String[] args) throws IOException {
        var record = new byte[34];
        var reference = new byte[34];
        long count = 0, sumX = 0, sumY = 0, sumZ = 0;
        try (var reader = new LasReader(Path.of(args[0]), LasReader.DEFAULT_BATCH_SIZE);
             var sample = new RandomAccessFile(args[1], "r");
             var writer = args.length > 2 ? new LasWriter(reader, Path.of(args[2]), false) : null) {
            sample.seek(509);
            for (var batch = reader.readBatch(); batch != null; batch = reader.readBatch()) {
                for (int point = 0; point < batch.size(); point++) {
                    sumX += batch.rawX(point);
                    sumY += batch.rawY(point);
                    sumZ += batch.rawZ(point);
                    if (++count <= 1024) {
                        sample.readFully(reference);
                        batch.copyRecord(point, record, 0);
                        if (!java.util.Arrays.equals(record, reference)) {
                            throw new IOException("Unexpected LAS record at " + count);
                        }
                        if (writer != null) {
                            writer.write(batch, point);
                        }
                    }
                }
            }
            if (writer != null) {
                writer.finish();
            }
        }
        if (count != 14_566_627 || sumX != 3_778_944_994_702_787L
                || sumY != 1_788_429_997_583_373L || sumZ != 1_078_422_075_172L) {
            throw new IOException("Decoded tile differs from P1");
        }
        System.out.printf("count=%d sumXYZ=%d,%d,%d matchedRecords=1024%n", count, sumX, sumY, sumZ);
    }
}
