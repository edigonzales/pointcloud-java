import com.github.mreutegg.laszip4j.laszip.LazDecoder;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.file.Path;

/** Import regression against the P1 Solothurn 2019 baseline. */
class DecodeTile {
    public static void main(String[] args) throws IOException {
        var record = ByteBuffer.allocate(34);
        var reference = new byte[34];
        var vlr = new byte[52];
        var path = Path.of(args[0]);
        long count = 0, sumX = 0, sumY = 0, sumZ = 0;
        try (var source = new RandomAccessFile(path.toFile(), "r");
             var sample = new RandomAccessFile(args[1], "r")) {
            source.seek(615 - vlr.length);
            source.readFully(vlr);
            sample.seek(509);
            try (var decoder = new LazDecoder(path, 615, 14_566_627, 3, 34, vlr)) {
                while (decoder.read(record)) {
                    sumX += record.getInt(0);
                    sumY += record.getInt(4);
                    sumZ += record.getInt(8);
                    if (++count <= 1024) {
                        sample.readFully(reference);
                        if (!java.util.Arrays.equals(record.array(), reference)) {
                            throw new IOException("Unexpected LAS record at " + count);
                        }
                    }
                    record.clear();
                }
            }
        }
        if (count != 14_566_627 || sumX != 3_778_944_994_702_787L
                || sumY != 1_788_429_997_583_373L || sumZ != 1_078_422_075_172L) {
            throw new IOException("Decoded tile differs from P1");
        }
        System.out.printf("count=%d sumXYZ=%d,%d,%d matchedRecords=1024%n", count, sumX, sumY, sumZ);
    }
}
