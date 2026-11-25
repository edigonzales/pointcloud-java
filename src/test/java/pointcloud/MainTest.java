package pointcloud;

import com.google.gson.JsonParser;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
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
                  pipeline <pipeline.json> [--metadata <file>] [--batch-size <points>] [--laz-workers <1-4>] [--overwrite]
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
    void rejectsInvalidLazWorkers(@TempDir Path temp) throws IOException {
        Path target = temp.resolve("result.las");
        Path config = writePipeline(temp, target);
        Path metadata = temp.resolve("stats.json");

        for (String workers : List.of("0", "5", "two")) {
            assertTrue(assertPipelineError(config, metadata, "--laz-workers", workers)
                    .contains("--laz-workers must be an integer between 1 and 4"));
        }
        assertTrue(assertPipelineError(config, metadata, "--laz-workers").contains("--laz-workers requires a value"));
        assertTrue(assertPipelineError(config, metadata, "--laz-workers", "1", "--laz-workers", "4")
                .contains("Duplicate pipeline option --laz-workers"));
        assertFalse(Files.exists(target));
        assertFalse(Files.exists(metadata));
    }

    @Test
    void parallelLazPipelineMatchesDefaultAndSingleWorker(@TempDir Path temp) throws IOException {
        Path source = Path.of("src/test/resources/las/chunks.laz");
        for (String mode : List.of("default", "1", "4")) {
            Path target = temp.resolve(mode + ".las");
            Path metadata = temp.resolve(mode + ".json");
            Path config = writePipeline(temp.resolve("pipeline-" + mode + ".json"), source, target, """
                    {"type":"filters.expression","expression":"X >= 2594000.01"},
                    {"type":"filters.decimation","step":24999,"offset":1,"limit":50000},
                    {"type":"filters.stats","dimensions":"X,Intensity"},
                    """);
            List<String> options = mode.equals("default") ? List.of() : List.of("--laz-workers", mode);
            var output = new ByteArrayOutputStream();
            var errors = new ByteArrayOutputStream();
            assertEquals(0, Main.run(pipelineArguments(config, metadata, options),
                    new PrintStream(output), new PrintStream(errors)), errors::toString);
            assertEquals("", output.toString());
            assertEquals("", errors.toString());
        }

        Path serial = temp.resolve("default.las");
        String stats = Files.readString(temp.resolve("default.json"));
        var result = Pipeline.JSON.fromJson(stats, Pipeline.Result.class);
        assertEquals(50_003, result.pointsRead());
        assertEquals(3, result.pointsSelected());
        assertEquals(3, result.pointsWritten());
        assertEquals(List.of(3L, 3L), result.statistics().getFirst().dimensions().stream()
                .map(Pipeline.Statistics::count).toList());
        var records = LasIoTest.bytes(serial);
        int start = records.getInt(96);
        int width = records.getChar(105);
        assertEquals(List.of(2, 25_001, 50_000), List.of(records.getInt(start), records.getInt(start + width),
                records.getInt(start + 2 * width)));
        for (String workers : List.of("1", "4")) {
            assertEquals(-1, Files.mismatch(serial, temp.resolve(workers + ".las")));
            assertEquals(stats, Files.readString(temp.resolve(workers + ".json")));
        }
    }

    @Test
    void writesRequestedBinsWithoutChangingOtherMetadataFields(@TempDir Path temp) throws IOException {
        var input = LasIoTest.fixture(8, "las");
        Path config = Files.writeString(temp.resolve("pipeline.json"), """
                [%s,
                 {"type":"filters.stats","dimensions":"X,Classification","count":"Classification"},
                 {"type":"filters.expression","expression":"Classification < 0"},
                 {"type":"filters.stats","dimensions":"Classification","count":"Classification"},
                 {"type":"writers.null"}]
                """.formatted(Pipeline.JSON.toJson(input.toString())));
        var output = new ByteArrayOutputStream();
        var errors = new ByteArrayOutputStream();
        assertEquals(0, Main.run(new String[]{"pipeline", config.toString()},
                new PrintStream(output), new PrintStream(errors)), errors::toString);
        var dimensions = JsonParser.parseString(output.toString()).getAsJsonObject().getAsJsonArray("statistics");
        var stats = dimensions.get(0).getAsJsonObject().getAsJsonArray("dimensions");
        assertFalse(stats.get(0).getAsJsonObject().has("bins"));
        var bins = stats.get(1).getAsJsonObject().getAsJsonObject("bins");
        assertEquals(List.of("1", "2", "5", "6"), List.copyOf(bins.keySet()));
        assertEquals(2, bins.get("2").getAsLong());
        var empty = dimensions.get(1).getAsJsonObject().getAsJsonArray("dimensions").get(0).getAsJsonObject();
        assertTrue(empty.get("average").isJsonNull());
        assertTrue(empty.getAsJsonObject("bins").isEmpty());

        Path metadata = temp.resolve("statistics.json");
        assertEquals(0, Main.run(pipelineArguments(config, metadata, List.of()),
                new PrintStream(new ByteArrayOutputStream()), new PrintStream(errors)), errors::toString);
        assertEquals(output.toString(), Files.readString(metadata));
    }

    @Test
    void parallelLazFailureDiscardsLasAndMetadata(@TempDir Path temp) throws IOException {
        var bytes = LasIoTest.bytes(Path.of("src/test/resources/las/chunks.laz"));
        // Both chunks remain indexed, but the second cannot end at the declared boundary after two points.
        Path input = Files.write(temp.resolve("bad-count.laz"), bytes.putInt(107, 50_002).array());
        Path target = temp.resolve("result.las");
        Path metadata = temp.resolve("metadata.json");
        Path config = writePipeline(temp.resolve("pipeline.json"), input, target, "");
        String error = assertPipelineError(config, metadata, "--laz-workers", "4");
        assertTrue(error.contains("LAZ chunk processing failed") && error.contains("corrupt"), error);
        assertFalse(Files.exists(target));
        assertFalse(Files.exists(metadata));
        try (var entries = Files.list(temp)) {
            assertFalse(entries.anyMatch(path -> path.getFileName().toString().endsWith(".part")));
        }
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

    @Test
    void infoShowsGeoKeysAndExtendedWkt(@TempDir Path temp) throws IOException {
        Path legacy = LasIoTest.fixture(3, "las");
        var geoKeys = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);
        for (int value : new int[]{1, 1, 0, 1, 3072, 0, 1, 2056}) {
            geoKeys.putChar((char) value);
        }
        Path geotiff = addProjectionRecord(temp, legacy, 34735, geoKeys.array(), false);
        var wkt = "GEOGCS[\"WGS 84\",AUTHORITY[\"EPSG\",\"4326\"]]\0".getBytes(StandardCharsets.UTF_8);
        Path wktLas = addProjectionRecord(temp, LasIoTest.fixture(8, "las"), 2112, wkt, true);
        var output = new ByteArrayOutputStream();
        var errors = new ByteArrayOutputStream();
        for (Path source : new Path[]{geotiff, wktLas}) {
            assertEquals(0, Main.run(new String[]{"info", source.toString()},
                    new PrintStream(output), new PrintStream(errors)));
        }
        assertTrue(output.toString().contains("Projected CRS: EPSG:2056"));
        assertTrue(output.toString().contains("EVLR LASF_Projection/2112"));
        assertTrue(output.toString().contains("GEOGCS["));
        assertTrue(output.toString().contains("4326"));
        assertFalse(output.toString().contains("\0"));
        assertEquals("", errors.toString());
    }

    @Test
    void refusesLasOutputThatAliasesPipelineConfiguration(@TempDir Path temp) throws IOException {
        Path config = temp.resolve("pipeline.las");
        writePipeline(config, LasIoTest.fixture(3, "laz"), config, "");
        String before = Files.readString(config);
        var output = new ByteArrayOutputStream();
        var errors = new ByteArrayOutputStream();
        assertEquals(2, Main.run(new String[]{"pipeline", config.toString(), "--overwrite"},
                new PrintStream(output), new PrintStream(errors)));
        assertTrue(errors.toString().contains("aliases the pipeline configuration"), errors::toString);
        assertEquals(before, Files.readString(config));
    }

    private static Path addProjectionRecord(Path temp, Path path, int id, byte[] payload, boolean extended) throws IOException {
        var source = LasIoTest.bytes(path);
        int size = (extended ? 60 : 54) + payload.length;
        var record = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
        record.position(2).put("LASF_Projection".getBytes(StandardCharsets.US_ASCII));
        record.putChar(18, (char) id);
        int start;
        if (extended) {
            record.putLong(20, payload.length).position(60);
            start = source.capacity();
            source.putInt(243, source.getInt(243) + 1);
        } else {
            record.putChar(20, (char) payload.length).position(54);
            start = source.getInt(96);
            source.putInt(96, start + size).putInt(100, source.getInt(100) + 1);
        }
        record.put(payload);
        return Files.write(temp.resolve(id + ".las"), ByteBuffer.allocate(source.capacity() + size)
                .put(source.array(), 0, start).put(record.array())
                .put(source.array(), start, source.capacity() - start).array());
    }

    private static Path writePipeline(Path directory, Path target) throws IOException {
        return writePipeline(directory.resolve("pipeline.json"), LasIoTest.fixture(3, "laz"), target, "");
    }

    private static Path writePipeline(Path config, Path input, Path target, String filters) throws IOException {
        return Files.writeString(config, """
                [%s, %s{"type":"writers.las","filename":%s,"forward":"all","extra_dims":"all"}]
                """.formatted(Pipeline.JSON.toJson(input.toString()), filters,
                Pipeline.JSON.toJson(target.toString())));
    }

    private static String assertPipelineError(Path config, Path metadata, String... options) {
        var output = new ByteArrayOutputStream();
        var errors = new ByteArrayOutputStream();
        assertEquals(2, Main.run(pipelineArguments(config, metadata, List.of(options)),
                new PrintStream(output), new PrintStream(errors)));
        assertEquals("", output.toString());
        assertFalse(errors.toString().isBlank());
        return errors.toString();
    }

    private static String[] pipelineArguments(Path config, Path metadata, List<String> options) {
        var args = new ArrayList<>(List.of("pipeline", config.toString(), "--metadata", metadata.toString()));
        args.addAll(options);
        return args.toArray(String[]::new);
    }
}
