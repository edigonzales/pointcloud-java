package pointcloud;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/** Measures repeated whole-file runs in one JVM, separately from process startup. */
public final class RuntimeWarmup {
    public static void main(String[] args) throws Exception {
        var reference = Path.of(".cache/experiments/e1-baseline.las");
        var metadata = Files.readString(Path.of(".cache/experiments/e1-baseline.json"));
        var target = Path.of("build/results/ground-crop.las");
        for (int run = 1; run <= 4; run++) {
            var bytes = new ByteArrayOutputStream();
            try (var output = new PrintStream(bytes, false, StandardCharsets.UTF_8)) {
                long started = System.nanoTime();
                int status = Main.run(new String[]{"pipeline", "examples/ground-crop.json"}, output, System.err);
                double seconds = (System.nanoTime() - started) / 1e9;
                if (status != 0 || !metadata.equals(bytes.toString(StandardCharsets.UTF_8))
                        || Files.mismatch(reference, target) != -1) {
                    throw new IllegalStateException("Ground-Crop output differs in scan " + run);
                }
                Files.delete(target);
                System.out.printf(Locale.ROOT, "%d,%.6f%n", run, seconds);
            }
        }
    }
}
