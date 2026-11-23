package pointcloud;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.*;

@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 300, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 5, time = 300, timeUnit = TimeUnit.MILLISECONDS)
@Fork(value = 2, jvmArgsAppend = "-Xmx512m")
public class SelectionBenchmark {
    PointBatch batch;
    double[] x;
    double[] y;
    int[] classification;
    int[] indices;

    @Setup
    public void setUp() throws Exception {
        try (var reader = new LasReader(Path.of(".cache/experiments/kernel.las"), 131_072)) {
            batch = reader.readBatch();
            if (batch == null || batch.size() != 128_909) {
                throw new IllegalStateException("Unexpected kernel input length");
            }
        }
        x = new double[batch.size()];
        y = new double[batch.size()];
        classification = new int[batch.size()];
        indices = new int[batch.size()];
        populateColumns();
        int count = recordsSelection();
        int[] selected = Arrays.copyOf(indices, count);
        if (columnsSelection() != count || Arrays.mismatch(selected, 0, count, indices, 0, count) >= 0) {
            throw new IllegalStateException("Column and record selections differ");
        }
    }

    static boolean matches(double x, double y, int classification) {
        return x >= 2_594_100 && x <= 2_594_300 && y >= 1_227_600 && y <= 1_227_800 && classification == 2;
    }

    @Benchmark
    public int recordsSelection() {
        int count = 0;
        for (int i = 0; i < batch.size(); i++) {
            if (matches(batch.x(i), batch.y(i), batch.classification(i))) {
                indices[count++] = i;
            }
        }
        return count;
    }

    @Benchmark
    public int columnsSelection() {
        int count = 0;
        for (int i = 0; i < x.length; i++) {
            if (matches(x[i], y[i], classification[i])) {
                indices[count++] = i;
            }
        }
        return count;
    }

    @Benchmark
    public int populateColumns() {
        for (int i = 0; i < batch.size(); i++) {
            x[i] = batch.x(i);
            y[i] = batch.y(i);
            classification[i] = batch.classification(i);
        }
        return classification[classification.length - 1];
    }
}
