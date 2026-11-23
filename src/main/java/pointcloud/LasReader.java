package pointcloud;

import com.github.mreutegg.laszip4j.laszip.LazDecoder;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.channels.FileChannel;
import java.nio.file.Path;

/** Sequential reader for a local LAS or LAZ file. Confined to the opening thread. */
public final class LasReader implements AutoCloseable {
    public static final int DEFAULT_BATCH_SIZE = 32_768;
    final Path path;
    final FileChannel source;
    private final LasHeader header;
    private final LazDecoder decoder;
    private final PointBatch batch;
    private long position;
    private boolean finished;

    public LasReader(Path path, int batchSize) throws IOException {
        if (batchSize < 1) {
            throw new IllegalArgumentException("Batch size must be positive");
        }
        this.path = path;
        source = FileChannel.open(path);
        try {
            header = new LasHeader(source);
            batch = new PointBatch(header, Math.min(batchSize, (8 << 20) / header.recordLength()));
            decoder = header.compressed() ? new LazDecoder(source, header.pointOffset(), header.pointEnd(),
                    header.pointCount(), header.pointFormat(), header.recordLength(), header.laszip) : null;
        } catch (IOException | RuntimeException e) {
            try {
                source.close();
            } catch (IOException close) {
                e.addSuppressed(close);
            }
            throw e;
        }
    }

    public LasHeader header() {
        return header;
    }

    /** Returns a batch valid until the next read, or null after verified EOF. */
    public PointBatch readBatch() throws IOException {
        if (!source.isOpen()) {
            throw new IOException("LAS reader is closed");
        }
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException("LAS read cancelled");
        }
        int size = (int) Math.min(header.pointCount() - position, batch.capacity());
        batch.size = 0;
        batch.records.clear().limit(size * header.recordLength());
        if (decoder == null) {
            LasHeader.readFully(source, header.pointOffset() + position * header.recordLength(), batch.records);
        } else {
            for (int i = 0; i < size; i++) {
                decoder.read(batch.records);
            }
        }
        position += size;
        if (!finished && position == header.pointCount()) {
            if (decoder != null) {
                decoder.read(batch.records);
            }
            finished = true;
        }
        batch.size = size;
        return size == 0 ? null : batch;
    }

    boolean finished() {
        return finished;
    }

    @Override
    public void close() throws IOException {
        try (source; decoder) {
            // Close the LAZ mapping before its file channel.
        }
    }
}
