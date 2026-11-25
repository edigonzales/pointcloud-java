package pointcloud;

import com.github.mreutegg.laszip4j.laszip.LazDecoder;
import java.io.Closeable;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Decodes indexed LAZ chunks on worker-local decoders and returns batches in file order. */
final class ParallelLazReader implements Closeable {
    private static final long MAX_CHUNK_BYTES = 8L << 20;

    static Pipeline.BatchInput open(Path path, int batchSize, int workers) throws IOException {
        return workers == 1 ? Pipeline.openSequential(path, batchSize) : open(path, batchSize, workers, true);
    }

    static Pipeline.BatchInput open(Path path, int batchSize, int workers, boolean reuseDecoder)
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
            int capacity;
            LazDecoder.ChunkTable sharedTable;
            try (var index = decoder(channel, header, null)) {
                chunks = index.chunkCount();
                capacity = chunkCapacity(header, index, chunks);
                sharedTable = reuseDecoder ? index.chunkTable() : null;
            }
            if (capacity == 0) {
                channel.close();
                return Pipeline.openSequential(path, batchSize);
            }
            var reader = new ParallelLazReader(channel, header, chunks, capacity, Math.min(workers, chunks), sharedTable);
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

    private static int chunkCapacity(LasHeader header, LazDecoder index, int chunks) {
        if (chunks <= 1) {
            return 0;
        }
        long capacity = 0;
        for (int i = 0; i < chunks; i++) {
            var chunk = index.chunk(i);
            // LAS 1.4 decoders also keep the compressed field layers in arrays until the next chunk.
            if (chunk.points() * header.recordLength() > MAX_CHUNK_BYTES
                    || (header.pointFormat() >= 6 && chunk.end() - chunk.offset() > MAX_CHUNK_BYTES)) {
                return 0;
            }
            capacity = Math.max(capacity, chunk.points());
        }
        return (int) capacity;
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

    private ParallelLazReader(FileChannel channel, LasHeader header, int chunks, int capacity, int workerCount,
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

    private PointBatch readBatch() throws IOException {
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

    private boolean finished() {
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
