package pointcloud;

import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import jdk.incubator.vector.DoubleVector;
import jdk.incubator.vector.IntVector;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;
import org.openjdk.jmh.annotations.*;

@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 300, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 5, time = 300, timeUnit = TimeUnit.MILLISECONDS)
@Fork(value = 2, jvmArgsAppend = {"-Xmx512m", "--add-modules=jdk.incubator.vector"})
public class VectorBenchmark {
    private static final VectorSpecies<Double> XY = DoubleVector.SPECIES_128;
    private static final VectorSpecies<Integer> CLASSES = IntVector.SPECIES_64;
    private double[] x;
    private double[] y;
    private int[] classification;
    private int stride;
    private byte[] records;
    private byte[] compacted;
    private double[] distances;

    @Setup
    public void setup() throws Exception {
        var sample = new SelectionBenchmark();
        sample.setUp();
        x = sample.x;
        y = sample.y;
        classification = sample.classification;
        stride = sample.batch.header.recordLength();
        records = sample.batch.records.array();
        compacted = new byte[x.length * stride];
        distances = new double[x.length];
        verify(x.length);

        var tail = new VectorBenchmark();
        tail.x = new double[]{2_594_100, 2_594_099.99, 2_594_300};
        tail.y = new double[]{1_227_600, 1_227_700, 1_227_800};
        tail.classification = new int[]{2, 2, 2};
        tail.stride = 1;
        tail.records = new byte[]{1, 2, 3};
        tail.compacted = new byte[3];
        tail.distances = new double[3];
        for (int length = XY.length() - 1; length <= XY.length() + 1; length++) {
            tail.verify(length);
        }
    }

    @Benchmark
    public int selectScalar() {
        return select(x.length, false);
    }

    @Benchmark
    public int selectVector() {
        return select(x.length, true);
    }

    @Benchmark
    public double distanceScalar() {
        return distance(x.length, false);
    }

    @Benchmark
    public double distanceVector() {
        return distance(x.length, true);
    }

    private int select(int length, boolean vector) {
        int count = 0;
        int i = 0;
        if (vector) {
            for (; i < XY.loopBound(length); i += XY.length()) {
                var xs = DoubleVector.fromArray(XY, x, i);
                var ys = DoubleVector.fromArray(XY, y, i);
                var cs = IntVector.fromArray(CLASSES, classification, i);
                var mask = xs.compare(VectorOperators.GE, 2_594_100).and(xs.compare(VectorOperators.LE, 2_594_300))
                        .and(ys.compare(VectorOperators.GE, 1_227_600)).and(ys.compare(VectorOperators.LE, 1_227_800))
                        .and(cs.eq(2).cast(XY));
                for (long bits = mask.toLong(); bits != 0; bits &= bits - 1) {
                    int point = i + Long.numberOfTrailingZeros(bits);
                    System.arraycopy(records, point * stride, compacted, count++ * stride, stride);
                }
            }
        }
        for (; i < length; i++) {
            if (SelectionBenchmark.matches(x[i], y[i], classification[i])) {
                System.arraycopy(records, i * stride, compacted, count++ * stride, stride);
            }
        }
        return count;
    }

    private double distance(int length, boolean vector) {
        int i = 0;
        if (vector) {
            for (; i < XY.loopBound(length); i += XY.length()) {
                var dx = DoubleVector.fromArray(XY, x, i).sub(2_594_200);
                var dy = DoubleVector.fromArray(XY, y, i).sub(1_227_700);
                dx.mul(dx).add(dy.mul(dy)).intoArray(distances, i);
            }
        }
        for (; i < length; i++) {
            double dx = x[i] - 2_594_200;
            double dy = y[i] - 1_227_700;
            distances[i] = dx * dx + dy * dy;
        }
        return distances[length - 1];
    }

    private void verify(int length) {
        int count = select(length, false);
        byte[] expected = Arrays.copyOf(compacted, count * stride);
        if (select(length, true) != count
                || Arrays.mismatch(expected, 0, expected.length, compacted, 0, expected.length) >= 0) {
            throw new IllegalStateException("Vector selection differs at length " + length);
        }
        distance(length, false);
        double[] scalar = Arrays.copyOf(distances, length);
        distance(length, true);
        if (Arrays.mismatch(scalar, 0, length, distances, 0, length) >= 0) {
            throw new IllegalStateException("Vector distances differ at length " + length);
        }
    }
}
