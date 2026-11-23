package pointcloud;

import com.github.mreutegg.laszip4j.laszip.LazDecoder;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Ordered experiment on independent LAZ chunks, with optional full record comparison. */
public final class ChunkParallel {
    public static void main(String[] args) throws Exception {
        int workers = Integer.parseInt(args[0]);
        if (workers < 0 || workers > 4) {
            throw new IllegalArgumentException("Use 0 to 4 workers");
        }
        Path input = Path.of(args[1]);
        Path output = Path.of(args[2]);
        boolean verify = args.length == 4 && args[3].equals("--verify");
        if (workers == 0) {
            serial(input, output, verify);
            return;
        }
        try (var channel = FileChannel.open(input)) {
            var header = new LasHeader(channel);
            int chunks;
            int capacity = 0;
            try (var table = decoder(channel, header)) {
                chunks = table.chunkCount();
                if (chunks > 0) {
                    System.out.printf("chunks=%d first=%s last=%s%n", chunks, table.chunk(0), table.chunk(chunks - 1));
                }
                for (int i = 0; i < chunks; i++) {
                    capacity = Math.max(capacity, Math.toIntExact(table.chunk(i).points()));
                }
            }
            if (chunks == 0) {
                System.out.println("Sequential: no chunk locations");
                serial(input, output, verify);
                return;
            }
            if ((long) capacity * header.recordLength() > (8 << 20)) {
                throw new IOException("Chunk exceeds the prototype's eight MiB buffer");
            }
            var read = new long[1];
            try (var writer = new LasWriter(input, channel, header, () -> read[0] == header.pointCount(), output, false);
                 var reference = verify ? new RecordComparison(input) : null;
                 var pool = Executors.newFixedThreadPool(workers)) {
                var pending = new ArrayDeque<Future<PointBatch>>();
                for (int index = 0; index < chunks; index++) {
                    PointBatch batch;
                    if (pending.size() == 2 * workers) {
                        batch = pending.removeFirst().get();
                        append(batch, writer, reference);
                        read[0] += batch.size();
                    } else {
                        batch = new PointBatch(header, capacity);
                    }
                    int chunk = index;
                    pending.addLast(pool.submit(() -> decode(channel, chunk, batch)));
                }
                while (!pending.isEmpty()) {
                    var batch = pending.removeFirst().get();
                    append(batch, writer, reference);
                    read[0] += batch.size();
                }
                finish(writer, reference, read[0], header.pointCount());
            }
        }
    }

    private static void serial(Path input, Path output, boolean verify) throws IOException {
        try (var reader = new LasReader(input, LasReader.DEFAULT_BATCH_SIZE);
             var writer = new LasWriter(reader, output, false);
             var reference = verify ? new RecordComparison(input) : null) {
            long read = 0;
            for (var batch = reader.readBatch(); batch != null; batch = reader.readBatch()) {
                append(batch, writer, reference);
                read += batch.size();
            }
            finish(writer, reference, read, reader.header().pointCount());
        }
    }

    private static LazDecoder decoder(FileChannel file, LasHeader h) throws IOException {
        return new LazDecoder(file, h.pointOffset(), h.pointEnd(), h.pointCount(),
                h.pointFormat(), h.recordLength(), h.laszip);
    }

    private static PointBatch decode(FileChannel source, int index, PointBatch batch) throws IOException {
        try (var decoder = decoder(source, batch.header)) {
            decoder.startChunk(index);
            var chunk = decoder.chunk(index);
            int points = Math.toIntExact(chunk.points());
            batch.records.clear().limit(points * batch.header.recordLength());
            for (int i = 0; i < points; i++) {
                if (!decoder.read(batch.records)) {
                    throw new IOException("Early EOF in chunk " + index);
                }
            }
            if (decoder.read(batch.records)) {
                throw new IOException("Wrong point count in chunk " + index);
            }
            batch.size = points;
            return batch;
        }
    }

    private static void append(PointBatch batch, LasWriter writer, RecordComparison reference) throws IOException {
        if (reference != null) {
            reference.compare(batch);
        }
        for (int point = 0; point < batch.size(); point++) {
            if (SelectionBenchmark.matches(batch.x(point), batch.y(point), batch.classification(point))) {
                writer.write(batch, point);
            }
        }
    }

    private static void finish(LasWriter writer, RecordComparison reference, long read, long expected) throws IOException {
        if (read != expected || reference != null && (read != reference.compared || reference.reader.readBatch() != null)) {
            throw new IOException("Point stream ended at the wrong position");
        }
        writer.finish();
        System.out.printf("read=%d wrote=%d compared=%d%n", read, writer.pointCount(),
                reference == null ? 0 : reference.compared);
    }

    private static final class RecordComparison implements AutoCloseable {
        private final LasReader reader;
        private PointBatch current;
        private int position;
        private long compared;

        RecordComparison(Path input) throws IOException {
            reader = new LasReader(input, LasReader.DEFAULT_BATCH_SIZE);
        }

        void compare(PointBatch batch) throws IOException {
            int length = batch.header.recordLength();
            for (int offset = 0; offset < batch.size();) {
                if (current == null || position == current.size()) {
                    current = reader.readBatch();
                    position = 0;
                }
                if (current == null) {
                    throw new IOException("Sequential reference ended before the chunk stream");
                }
                int points = Math.min(batch.size() - offset, current.size() - position);
                int bytes = points * length;
                int left = offset * length;
                int right = position * length;
                if (Arrays.mismatch(batch.records.array(), left, left + bytes,
                        current.records.array(), right, right + bytes) >= 0) {
                    throw new IOException("Point records differ after " + compared + " points");
                }
                compared += points;
                offset += points;
                position += points;
            }
        }

        @Override
        public void close() throws IOException {
            reader.close();
        }
    }
}
