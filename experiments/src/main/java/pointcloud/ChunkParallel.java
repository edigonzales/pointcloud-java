package pointcloud;

import com.github.mreutegg.laszip4j.laszip.LazDecoder;
import java.io.Closeable;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Ordered experiment on independent LAZ chunks, with optional full record comparison. */
public final class ChunkParallel {
    public static void main(String[] args) throws Exception {
        if (args.length == 4 && args[0].equals("pipeline")) {
            Pipeline.InputFactory inputs = switch (args[1]) {
                case "serial" -> Pipeline::openSequential;
                case "per-chunk" -> (path, batchSize) -> open(path, batchSize, 4, false);
                case "reused" -> (path, batchSize) -> open(path, batchSize, 4, true);
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
        try (var source = open(input, LasReader.DEFAULT_BATCH_SIZE, workers, false);
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

    private static Pipeline.BatchInput open(Path path, int batchSize, int workers, boolean reuseDecoder)
            throws IOException {
        if (workers == 0) {
            return Pipeline.openSequential(path, batchSize);
        }
        var channel = FileChannel.open(path);
        try {
            var header = new LasHeader(channel);
            if (!header.compressed()) {
                channel.close();
                return Pipeline.openSequential(path, batchSize);
            }
            int chunks;
            long capacity = 0;
            LazDecoder.ChunkTable sharedTable;
            try (var index = decoder(channel, header, null)) {
                chunks = index.chunkCount();
                sharedTable = reuseDecoder ? index.chunkTable() : null;
                for (int i = 0; i < chunks; i++) {
                    capacity = Math.max(capacity, index.chunk(i).points());
                }
            }
            if (chunks == 0) {
                channel.close();
                return Pipeline.openSequential(path, batchSize);
            }
            if (capacity * header.recordLength() > (8 << 20)) {
                throw new IOException("Chunk exceeds the prototype's eight MiB buffer");
            }
            var reader = new OrderedChunks(channel, header, chunks, (int) capacity, workers, sharedTable);
            return new Pipeline.BatchInput(header, channel, reader::readBatch, reader::finished, reader);
        } catch (IOException | RuntimeException | Error failure) {
            try {
                channel.close();
            } catch (IOException close) {
                failure.addSuppressed(close);
            }
            throw failure;
        }
    }

    private static LazDecoder decoder(FileChannel file, LasHeader h, LazDecoder.ChunkTable table) throws IOException {
        return new LazDecoder(file, h.pointOffset(), h.pointEnd(), h.pointCount(),
                h.pointFormat(), h.recordLength(), h.laszip, table);
    }

    private static void decode(LazDecoder decoder, int index, PointBatch batch) throws IOException {
        decoder.startChunk(index);
        int points = Math.toIntExact(decoder.chunk(index).points());
        batch.records.clear().limit(points * batch.header.recordLength());
        for (int i = 0; i < points; i++) {
            if ((i & 1023) == 0 && Thread.currentThread().isInterrupted()) {
                throw new InterruptedIOException("LAZ chunk decoding cancelled");
            }
            if (!decoder.read(batch.records)) {
                throw new IOException("Early EOF in chunk " + index);
            }
        }
        if (decoder.read(batch.records)) {
            throw new IOException("Wrong point count in chunk " + index);
        }
        batch.size = points;
    }

    private record Job(int index, PointBatch batch, CompletableFuture<PointBatch> result) {}

    private static final class OrderedChunks implements Closeable {
        private final FileChannel channel;
        private final LasHeader header;
        private final int chunks;
        private final int capacity;
        private final ExecutorService pool;
        private final ArrayList<ArrayBlockingQueue<Job>> queues = new ArrayList<>();
        private final ArrayList<Future<Void>> workers = new ArrayList<>();
        private final ArrayDeque<Future<PointBatch>> pending = new ArrayDeque<>();
        private PointBatch current;
        private int next;
        private long read;
        private boolean finished;
        private IOException failure;

        OrderedChunks(FileChannel channel, LasHeader header, int chunks, int capacity, int workerCount,
                      LazDecoder.ChunkTable sharedTable) {
            this.channel = channel;
            this.header = header;
            this.chunks = chunks;
            this.capacity = capacity;
            pool = Executors.newFixedThreadPool(workerCount);
            try {
                for (int i = 0; i < workerCount; i++) {
                    var queue = new ArrayBlockingQueue<Job>(2);
                    queues.add(queue);
                    workers.add(pool.submit(() -> {
                        work(queue, sharedTable);
                        return null;
                    }));
                }
            } catch (RuntimeException | Error failure) {
                pool.shutdownNow();
                pool.close();
                throw failure;
            }
        }

        private void work(ArrayBlockingQueue<Job> queue, LazDecoder.ChunkTable sharedTable) throws IOException {
            LazDecoder workerDecoder = null;
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    Job job = queue.take();
                    try {
                        if (sharedTable == null) {
                            try (var chunkDecoder = decoder(channel, header, null)) {
                                decode(chunkDecoder, job.index(), job.batch());
                            }
                        } else {
                            if (workerDecoder == null) {
                                workerDecoder = decoder(channel, header, sharedTable);
                            }
                            decode(workerDecoder, job.index(), job.batch());
                        }
                        job.result().complete(job.batch());
                    } catch (IOException | RuntimeException | Error failed) {
                        job.result().completeExceptionally(failed);
                        return;
                    }
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                if (workerDecoder != null) {
                    workerDecoder.close();
                }
            }
        }

        private void submit(PointBatch batch) {
            // Two buffers per worker. A consumed slot returns to the same worker.
            var job = new Job(next, batch, new CompletableFuture<>());
            queues.get(next % queues.size()).add(job);
            pending.addLast(job.result());
            next++;
        }

        PointBatch readBatch() throws IOException {
            if (failure != null) {
                throw new IOException("LAZ chunk reader cannot continue after a read failure", failure);
            }
            try {
                if (Thread.currentThread().isInterrupted()) {
                    throw new InterruptedIOException("LAZ chunk reading cancelled");
                }
                if (next == 0) {
                    int window = Math.min(2 * queues.size(), chunks);
                    for (int i = 0; i < window; i++) {
                        submit(new PointBatch(header, capacity));
                    }
                } else if (next < chunks) {
                    submit(current);
                }
                if (pending.isEmpty()) {
                    if (read != header.pointCount()) {
                        throw new IOException("Point stream ended at the wrong position");
                    }
                    finished = true;
                    return null;
                }
                current = await(pending.removeFirst());
                read += current.size();
                return current;
            } catch (IOException failed) {
                failure = failed;
                throw failed;
            }
        }

        boolean finished() {
            return finished;
        }

        @Override
        public void close() throws IOException {
            try (channel; pool) {
                pool.shutdownNow();
            }
            for (var worker : workers) {
                await(worker);
            }
        }

        private static <T> T await(Future<T> result) throws IOException {
            try {
                return result.get();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new InterruptedIOException("LAZ chunk reading cancelled");
            } catch (ExecutionException failure) {
                Throwable cause = failure.getCause();
                throw new IOException("LAZ chunk processing failed: " + cause.getMessage(), cause);
            }
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
