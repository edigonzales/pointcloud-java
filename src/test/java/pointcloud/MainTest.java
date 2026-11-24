package pointcloud;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MainTest {
    @Test
    void helpAndVersionGoToStandardOutput() {
        var output = new ByteArrayOutputStream();
        var errors = new ByteArrayOutputStream();
        assertEquals(0, Main.run(new String[]{"--help"}, new PrintStream(output), new PrintStream(errors)));
        assertEquals("""
                Usage: pointcloud [--diagnostics] <command>
                  --help
                  --version
                  info <input.las|input.laz>
                  pipeline <pipeline.json> [--metadata <file>] [--batch-size <points>] [--overwrite]
                """, output.toString().replace(System.lineSeparator(), "\n"));
        output.reset();
        assertEquals(0, Main.run(new String[]{"--version"}, new PrintStream(output), new PrintStream(errors)));
        assertEquals("pointcloud 0.1.0" + System.lineSeparator(), output.toString());
        assertEquals("", errors.toString());
    }

    @Test
    void invalidCommandReportsAnError() {
        var output = new ByteArrayOutputStream();
        var errors = new ByteArrayOutputStream();
        assertEquals(2, Main.run(new String[]{"--hulp"}, new PrintStream(output), new PrintStream(errors)));
        assertEquals("", output.toString());
        assertEquals("Invalid command. Run pointcloud --help." + System.lineSeparator(), errors.toString());
    }

    @Test
    void metadataFailureLeavesPointOutputUnpublished(@TempDir Path temp) throws IOException {
        Path target = temp.resolve("result.las");
        Path config = writePipeline(temp, target);
        Path invalidParent = Files.writeString(temp.resolve("regular-file"), "not a directory");
        Path metadata = invalidParent.resolve("stats.json");

        assertPipelineError(config, metadata);
        assertFalse(Files.exists(target));

        Path previous = LasIoTest.fixture(8, "las");
        Files.copy(previous, target);
        assertPipelineError(config, metadata, "--overwrite");
        assertEquals(-1, Files.mismatch(previous, target));
        assertPipelineError(config, Files.createDirectory(temp.resolve("metadata-directory")), "--overwrite");
        assertEquals(-1, Files.mismatch(previous, target));
        try (var entries = Files.list(temp)) {
            assertFalse(entries.anyMatch(path -> path.getFileName().toString().endsWith(".part")));
        }
    }

    @Test
    void refusesNestedOutputPathsBeforeCreatingDirectories(@TempDir Path temp) throws IOException {
        Path target = temp.resolve("result.las");
        assertPipelineError(writePipeline(temp, target), target.resolve("stats.json"));
        assertFalse(Files.exists(target));

        Path metadata = temp.resolve("stats.json");
        assertPipelineError(writePipeline(temp, metadata.resolve("result.las")), metadata);
        assertFalse(Files.exists(metadata));
    }

    @Test
    void pointOutputFailureRestoresPreviousMetadata(@TempDir Path temp) throws IOException {
        Path target = Files.createDirectory(temp.resolve("result.las"));
        Path config = writePipeline(temp, target);
        Path metadata = Files.writeString(temp.resolve("stats.json"), "previous metadata");

        assertPipelineError(config, metadata, "--overwrite");
        assertEquals("previous metadata", Files.readString(metadata));
        assertTrue(Files.isDirectory(target));
        try (var entries = Files.list(temp)) {
            assertFalse(entries.anyMatch(path -> path.getFileName().toString().endsWith(".part")));
        }
    }

    private static Path writePipeline(Path directory, Path target) throws IOException {
        return Files.writeString(directory.resolve("pipeline.json"), """
                [%s, {"type":"writers.las","filename":%s,"forward":"all","extra_dims":"all"}]
                """.formatted(Pipeline.JSON.toJson(LasIoTest.fixture(3, "laz").toString()),
                Pipeline.JSON.toJson(target.toString())));
    }

    private static void assertPipelineError(Path config, Path metadata, String... options) {
        var args = new ArrayList<>(List.of("pipeline", config.toString(), "--metadata", metadata.toString()));
        args.addAll(List.of(options));
        var output = new ByteArrayOutputStream();
        var errors = new ByteArrayOutputStream();
        assertEquals(2, Main.run(args.toArray(String[]::new), new PrintStream(output), new PrintStream(errors)));
        assertEquals("", output.toString());
        assertFalse(errors.toString().isBlank());
    }
}
