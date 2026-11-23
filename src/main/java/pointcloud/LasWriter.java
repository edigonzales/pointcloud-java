package pointcloud;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/** Writes selected records from one source. Publish explicitly with finish(). */
public final class LasWriter implements AutoCloseable {
    private final LasReader reader;
    private final LasHeader header;
    private final Path target;
    private final boolean overwrite;
    private final Path temporary;
    private final FileChannel channel;
    private final BufferedOutputStream output;
    private final ByteBuffer outputHeader;
    private final long[] returns = new long[15];
    private final int[] minimum = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE};
    private final int[] maximum = {Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
    private long count;
    private boolean closed;

    public LasWriter(LasReader reader, Path target, boolean overwrite) throws IOException {
        this.reader = reader;
        this.target = target.toAbsolutePath();
        this.overwrite = overwrite;
        header = reader.header();
        if (!target.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".las")) {
            throw new IOException("LAS output filename must end in .las");
        }
        checkTarget();
        Files.createDirectories(this.target.getParent());
        temporary = Files.createTempFile(this.target.getParent(), ".pointcloud-", ".las.part");
        FileChannel opened = null;
        try {
            opened = FileChannel.open(temporary, StandardOpenOption.WRITE);
            channel = opened;
            outputHeader = header.copyHeader();
            outputHeader.put(104, (byte) header.pointFormat());
            channel.position(outputHeader.capacity());
            long vlrs = copyMetadata(false);
            long pointOffset = channel.position();
            if (pointOffset > 0xffff_ffffL) {
                throw new IOException("LAS point data offset exceeds unsigned 32-bit range");
            }
            outputHeader.putInt(96, (int) pointOffset).putInt(100, (int) vlrs);
            output = new BufferedOutputStream(Channels.newOutputStream(channel), 64 << 10);
        } catch (RuntimeException | IOException e) {
            try {
                if (opened != null) {
                    opened.close();
                }
            } catch (IOException close) {
                e.addSuppressed(close);
            }
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException cleanup) {
                e.addSuppressed(cleanup);
            }
            throw e;
        }
    }

    /** Append one point from the source's current batch. */
    public void write(PointBatch batch, int point) throws IOException {
        if (closed || batch.header != header) {
            throw new IOException("Writer requires an open output and a batch from its source");
        }
        int start = batch.start(point);
        output.write(batch.records.array(), start, header.recordLength());
        int returnNumber = batch.returnNumber(point);
        if (returnNumber != 0) {
            returns[returnNumber - 1]++;
        }
        for (int axis = 0; axis < 3; axis++) {
            int raw = batch.records.getInt(start + axis * 4);
            minimum[axis] = Math.min(minimum[axis], raw);
            maximum[axis] = Math.max(maximum[axis], raw);
        }
        count++;
    }

    public long pointCount() {
        return count;
    }

    /** Complete the header and publish the output after verified source EOF. */
    public void finish() throws IOException {
        if (closed || !reader.finished()) {
            throw new IOException("LAS output requires a complete source scan");
        }
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException("LAS output cancelled");
        }
        if (header.versionMinor() == 2 && count > 0xffff_ffffL) {
            throw new IOException("LAS 1.2 output point count exceeds unsigned 32-bit range");
        }
        output.flush();
        if (header.versionMinor() == 4) {
            long evlrOffset = channel.position();
            long evlrs = copyMetadata(true);
            outputHeader.putLong(235, evlrs == 0 ? 0 : evlrOffset);
            outputHeader.putInt(243, (int) evlrs);
            outputHeader.putLong(247, count);
            for (int i = 0; i < returns.length; i++) {
                outputHeader.putLong(255 + i * 8, returns[i]);
            }
        }
        boolean legacy = header.pointFormat() < 6;
        outputHeader.putInt(107, legacy ? (int) count : 0);
        for (int i = 0; i < 5; i++) {
            outputHeader.putInt(111 + i * 4, legacy ? (int) returns[i] : 0);
        }
        for (int axis = 0; axis < 3; axis++) {
            outputHeader.putDouble(179 + axis * 16, count == 0 ? 0 : header.coordinate(axis, maximum[axis]));
            outputHeader.putDouble(187 + axis * 16, count == 0 ? 0 : header.coordinate(axis, minimum[axis]));
        }
        channel.position(0);
        while (outputHeader.hasRemaining()) {
            channel.write(outputHeader);
        }
        output.close();
        checkTarget();
        if (overwrite) {
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } else {
            Files.move(temporary, target);
        }
        closed = true;
    }

    private long copyMetadata(boolean extended) throws IOException {
        var count = new long[1];
        long end = header.visitMetadata(reader.source, extended, record -> {
            if (!record.isPointIndex()) {
                transfer(record.start(), record.payload() - record.start() + record.length());
                count[0]++;
            }
        });
        if (!extended) {
            transfer(end, header.pointOffset() - end);
        }
        return count[0];
    }

    private void transfer(long offset, long remaining) throws IOException {
        while (remaining > 0) {
            long written = reader.source.transferTo(offset, remaining, channel);
            if (written <= 0) {
                throw new IOException("Cannot copy LAS metadata at byte " + offset);
            }
            remaining -= written;
            offset += written;
        }
    }

    private void checkTarget() throws IOException {
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            if (Files.exists(target) && Files.isSameFile(reader.path, target)) {
                throw new IOException("LAS input and output refer to the same file");
            }
            if (!overwrite) {
                throw new FileAlreadyExistsException(target.toString());
            }
        }
    }

    @Override
    public void close() throws IOException {
        if (!closed) {
            closed = true;
            try {
                channel.close();
            } finally {
                Files.deleteIfExists(temporary);
            }
        }
    }
}
