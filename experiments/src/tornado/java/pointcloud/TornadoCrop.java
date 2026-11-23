package pointcloud;

import java.util.Arrays;
import uk.ac.manchester.tornado.api.TaskGraph;
import uk.ac.manchester.tornado.api.TornadoExecutionPlan;
import uk.ac.manchester.tornado.api.annotations.Parallel;
import uk.ac.manchester.tornado.api.enums.DataTransferMode;
import uk.ac.manchester.tornado.api.enums.ProfilerMode;
import uk.ac.manchester.tornado.api.enums.TornadoDeviceType;
import uk.ac.manchester.tornado.api.types.arrays.IntArray;

/** The E2 crop and classification kernel on raw centimetre coordinates. */
public final class TornadoCrop {
    public static void select(IntArray x, IntArray y, IntArray classification, IntArray mask) {
        for (@Parallel int i = 0; i < x.getSize(); i++) {
            int xx = x.get(i);
            int yy = y.get(i);
            mask.set(i, matchesRaw(xx, yy, classification.get(i)) ? 1 : 0);
        }
    }

    private static boolean matchesRaw(int x, int y, int classification) {
        return x >= 259_410_000 && x <= 259_430_000
                && y >= 122_760_000 && y <= 122_780_000 && classification == 2;
    }

    public static void main(String[] args) throws Exception {
        long discovery = System.nanoTime();
        var device = TornadoExecutionPlan.getDevice(0, 0);
        discovery = System.nanoTime() - discovery;
        if (device.getDeviceType() != TornadoDeviceType.GPU) {
            throw new IllegalStateException("The selected TornadoVM device is not a GPU: " + device);
        }
        System.err.printf("backend=%s device=%s type=%s platform=%s physical=%s unified_memory_api=%s discovery_ns=%d%n",
                device.getTornadoVMBackend(), device.getDeviceName(), device.getDeviceType(),
                device.getPlatformName(), device.getPhysicalDevice().getDeviceName(),
                device.isUnifiedMemorySupported(), discovery);
        if (args.length == 1 && args[0].equals("--device")) {
            return;
        }

        var sample = new SelectionBenchmark();
        sample.setUp();
        var batch = sample.batch;
        int size = batch.size();
        int stride = batch.header.recordLength();
        if (batch.header.scale(0) != 0.01 || batch.header.scale(1) != 0.01
                || batch.header.offset(0) != 0 || batch.header.offset(1) != 0) {
            throw new IllegalStateException("Integer crop requires centimetre coordinates with zero offset");
        }
        byte[] records = batch.records.array();
        byte[] scalar = new byte[size * stride];
        byte[] compacted = new byte[size * stride];
        int selected = scalar(sample, records, scalar, stride);
        if (selected != 1730) {
            throw new IllegalStateException("Unexpected E2 result count");
        }

        for (int i = -5; i < 7; i++) {
            long begin = System.nanoTime();
            int actual = scalar(sample, records, compacted, stride);
            long elapsed = System.nanoTime() - begin;
            if (actual != selected || Arrays.mismatch(scalar, 0, selected * stride,
                    compacted, 0, selected * stride) >= 0) {
                throw new IllegalStateException("Scalar results differ");
            }
            if (i >= 0) {
                System.err.printf("scalar iteration=%d points=%d time_ns=%d%n", i + 1, size, elapsed);
            }
        }

        long start = System.nanoTime();
        var x = new IntArray(size);
        var y = new IntArray(size);
        var classification = new IntArray(size);
        var mask = new IntArray(size);
        for (int i = 0; i < size; i++) {
            x.set(i, batch.rawX(i));
            y.set(i, batch.rawY(i));
            classification.set(i, batch.classification(i));
        }
        long hostPreparation = System.nanoTime() - start;
        for (int i = 0; i < size; i++) {
            if (matchesRaw(x.get(i), y.get(i), classification.get(i))
                    != SelectionBenchmark.matches(sample.x[i], sample.y[i], sample.classification[i])) {
                throw new IllegalStateException("Integer conversion changes the crop at point " + i);
            }
        }

        start = System.nanoTime();
        var graph = new TaskGraph("crop")
                .transferToDevice(DataTransferMode.EVERY_EXECUTION, x, y, classification)
                .task("select", TornadoCrop::select, x, y, classification, mask)
                .transferToHost(DataTransferMode.EVERY_EXECUTION, mask)
                .snapshot();
        try (var plan = new TornadoExecutionPlan(graph).withDevice(device)
                .withStrictFloatingPoint().withProfiler(ProfilerMode.SILENT)) {
            long setup = System.nanoTime() - start;
            System.err.printf("points=%d selected=%d input_bytes=%d mask_bytes=%d preparation_ns=%d setup_ns=%d%n",
                    size, selected, 3L * size * Integer.BYTES, (long) size * Integer.BYTES, hostPreparation, setup);
            System.out.println("run,execute_ns,compile_ns,kernel_ns,write_ns,read_ns,copy_in_bytes,copy_out_bytes,compact_ns");
            for (int run = 1; run <= 11; run++) {
                long begin = System.nanoTime();
                var result = plan.execute();
                long execute = System.nanoTime() - begin;
                var profile = result.getProfilerResult();
                begin = System.nanoTime();
                int actual = 0;
                for (int i = 0; i < size; i++) {
                    if (mask.get(i) == 1) {
                        System.arraycopy(records, i * stride, compacted, actual++ * stride, stride);
                    }
                }
                long compact = System.nanoTime() - begin;
                if (actual != selected || Arrays.mismatch(scalar, 0, selected * stride,
                        compacted, 0, selected * stride) >= 0) {
                    throw new IllegalStateException("GPU compaction differs from scalar output");
                }
                for (int i = 0; i < size; i++) {
                    boolean expected = SelectionBenchmark.matches(sample.x[i], sample.y[i], sample.classification[i]);
                    if (mask.get(i) != (expected ? 1 : 0)) {
                        throw new IllegalStateException("GPU mask differs at point " + i);
                    }
                }
                if (profile.getDeviceKernelTime() <= 0) {
                    throw new IllegalStateException("No GPU kernel time reported: " + profile.getProfileLog());
                }
                System.out.printf("%d,%d,%d,%d,%d,%d,%d,%d,%d%n", run, execute, profile.getCompileTime(),
                        profile.getDeviceKernelTime(), profile.getDeviceWriteTime(), profile.getDeviceReadTime(),
                        profile.getTotalBytesCopyIn(), profile.getTotalBytesCopyOut(), compact);
            }
        }
    }

    private static int scalar(SelectionBenchmark sample, byte[] records, byte[] packed, int stride) {
        int count = 0;
        for (int i = 0; i < sample.x.length; i++) {
            if (SelectionBenchmark.matches(sample.x[i], sample.y[i], sample.classification[i])) {
                System.arraycopy(records, i * stride, packed, count++ * stride, stride);
            }
        }
        return count;
    }
}
