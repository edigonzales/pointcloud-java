package pointcloud;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Isolated ordered post-decode Crop/Classification/Stats(Z) experiment. */
public final class BatchParallel {
    public static void main(String[] args) throws Exception {
        int workers = Integer.parseInt(args[0]);
        if (workers < 0 || workers > 4) {
            throw new IllegalArgumentException("Use 0 to 4 workers");
        }
        int batchSize = LasReader.DEFAULT_BATCH_SIZE;
        var total = new Statistics();
        long read = 0;
        try (var reader = new LasReader(Path.of(".cache/test-data/so-2019.laz"), batchSize);
             var writer = new LasWriter(reader, Path.of(args[1]), false);
             var pool = workers == 0 ? null : Executors.newFixedThreadPool(workers)) {
            if (pool == null) {
                var selected = new int[batchSize];
                for (var batch = reader.readBatch(); batch != null; batch = reader.readBatch()) {
                    read += batch.size();
                    append(select(new Storage(batch, selected)), total, writer);
                }
            } else {
                var available = new ArrayDeque<Storage>();
                var inOrder = new ArrayDeque<Future<Selection>>();
                int slots = 2 * workers;
                for (int i = 0; i < slots; i++) {
                    available.addLast(new Storage(new PointBatch(reader.header(), batchSize), new int[batchSize]));
                }
                for (var batch = reader.readBatch(); batch != null; batch = reader.readBatch()) {
                    read += batch.size();
                    if (available.isEmpty()) {
                        var next = inOrder.removeFirst().get();
                        append(next, total, writer);
                        available.addLast(next.storage());
                    }
                    var work = available.removeFirst();
                    work.batch.size = batch.size();
                    System.arraycopy(batch.records.array(), 0, work.batch.records.array(), 0,
                            batch.size() * batch.header.recordLength());
                    inOrder.addLast(pool.submit(() -> select(work)));
                }
                while (!inOrder.isEmpty()) {
                    append(inOrder.removeFirst().get(), total, writer);
                }
            }
            writer.finish();
            if (writer.pointCount() != total.count || read != 14_566_627 || total.count != 195_738) {
                throw new IOException("Batch experiment produced an unexpected point count");
            }
        }
        System.out.printf(java.util.Locale.ROOT, "%d,%d,%.17g,%.17g,%.17g,%.17g%n",
                read, total.count, total.min, total.max, total.mean, total.m2 / (total.count - 1));
    }

    private static Selection select(Storage storage) {
        PointBatch batch = storage.batch;
        var stats = new Statistics();
        int count = 0;
        for (int i = 0; i < batch.size(); i++) {
            if (SelectionBenchmark.matches(batch.x(i), batch.y(i), batch.classification(i))) {
                storage.indices[count++] = i;
                stats.add(batch.z(i));
            }
        }
        return new Selection(storage, count, stats);
    }

    private static void append(Selection selection, Statistics total, LasWriter writer) throws IOException {
        var storage = selection.storage();
        for (int i = 0; i < selection.count; i++) {
            writer.write(storage.batch, storage.indices[i]);
        }
        total.merge(selection.stats);
    }

    private record Storage(PointBatch batch, int[] indices) {}

    private record Selection(Storage storage, int count, Statistics stats) {}

    private static final class Statistics {
        long count;
        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;
        double mean;
        double m2;

        void add(double value) {
            min = Math.min(value, min);
            max = Math.max(value, max);
            double delta = value - mean;
            mean += delta / ++count;
            m2 += delta * (value - mean);
        }

        void merge(Statistics other) {
            if (other.count == 0) {
                return;
            }
            min = Math.min(min, other.min);
            max = Math.max(max, other.max);
            long combined = count + other.count;
            double delta = other.mean - mean;
            m2 += other.m2 + delta * delta * count * other.count / combined;
            mean += delta * other.count / combined;
            count = combined;
        }
    }
}
