package pointcloud;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;

/** Ordered experiment on independent LAZ chunks, with optional full record comparison. */
public final class ChunkParallel {
    public static void main(String[] args) throws Exception {
        if (args.length == 4 && args[0].equals("pipeline")) {
            Pipeline.InputFactory inputs = switch (args[1]) {
                case "serial" -> Pipeline::openSequential;
                case "per-chunk" -> (path, batchSize) -> ParallelLazReader.open(path, batchSize, 4, false);
                case "reused" -> (path, batchSize) -> ParallelLazReader.open(path, batchSize, 4);
                default -> throw new IllegalArgumentException("Expected serial, per-chunk or reused");
            };
            Main.launch(new String[]{"pipeline", args[2], "--metadata", args[3]}, inputs);
            return;
        }
        int workers = Integer.parseInt(args[0]);
        if (workers < 0 || workers > 4) {
            throw new IllegalArgumentException("Use 0 to 4 workers");
        }
        Path input = Path.of(args[1]);
        Path output = Path.of(args[2]);
        boolean verify = args.length == 4 && args[3].equals("--verify");
        try (var source = ParallelLazReader.open(input, LasReader.DEFAULT_BATCH_SIZE, workers, false);
             var writer = new LasWriter(input, source.channel(), source.header(), source.finished(), output, false);
             var reference = verify ? new RecordComparison(input) : null) {
            long read = 0;
            for (var batch = source.reader().readBatch(); batch != null; batch = source.reader().readBatch()) {
                append(batch, writer, reference);
                read += batch.size();
            }
            finish(writer, reference, read, source.header().pointCount());
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
