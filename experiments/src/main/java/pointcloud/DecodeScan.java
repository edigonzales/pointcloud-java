package pointcloud;

import com.github.mreutegg.laszip4j.laszip.LazDecoder;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;

/** Measure the codec record path or the same scan through reusable batches. */
public final class DecodeScan {
    public static void main(String[] args) throws Exception {
        Path input = Path.of(".cache/test-data/so-2019.laz");
        long count = 0, x = 0, y = 0, z = 0;
        if (args[0].equals("codec")) {
            try (var channel = FileChannel.open(input)) {
                var header = new LasHeader(channel);
                var record = ByteBuffer.allocate(header.recordLength());
                try (var decoder = new LazDecoder(channel, header.pointOffset(), header.pointEnd(),
                        header.pointCount(), header.pointFormat(), header.recordLength(), header.laszip)) {
                    while (decoder.read(record)) {
                        count++;
                        x += record.getInt(0);
                        y += record.getInt(4);
                        z += record.getInt(8);
                        record.clear();
                    }
                }
            }
        } else {
            try (var reader = new LasReader(input, Integer.parseInt(args[0]))) {
                for (var batch = reader.readBatch(); batch != null; batch = reader.readBatch()) {
                    count += batch.size();
                    for (int point = 0; point < batch.size(); point++) {
                        x += batch.rawX(point);
                        y += batch.rawY(point);
                        z += batch.rawZ(point);
                    }
                }
            }
        }
        if (count != 14_566_627 || x != 3_778_944_994_702_787L
                || y != 1_788_429_997_583_373L || z != 1_078_422_075_172L) {
            throw new IllegalStateException("Decoded tile differs from P1");
        }
        System.out.printf("%d,%d,%d,%d%n", count, x, y, z);
    }
}
