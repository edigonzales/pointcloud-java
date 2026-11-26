package pointcloud;

/** Runs the same DTM entrypoint in one JVM for startup diagnosis and AOT training. */
public final class TinRuntimeWarmup {
    public static void main(String[] args) {
        int repetitions = Integer.parseInt(args[0]);
        for (int run = 0; run < repetitions; run++) {
            for (int i = 1; i < args.length; i += 2) {
                long started = System.nanoTime();
                int status = Main.run(new String[]{"pipeline", args[i], "--metadata", args[i + 1], "--overwrite"},
                        System.out, System.err);
                if (status != 0) {
                    throw new IllegalStateException("DTM run exited with " + status);
                }
                System.out.printf(java.util.Locale.ROOT, "%d,%d,%.6f%n", run + 1, (i + 1) / 2,
                        (System.nanoTime() - started) / 1e9);
            }
        }
    }
}
