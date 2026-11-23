package pointcloud;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PipelineTest {
    @TempDir Path temp;

    @Test
    void runsAllExamplesOnLocalInputs() throws IOException {
        var mini = LasIoTest.bytes(LasIoTest.fixture(3, "las"));
        mini.putDouble(155, 2594200).putDouble(163, 1227700);
        Path source = Files.write(temp.resolve("mini.las"), mini.array());
        for (String example : List.of("ground-crop", "stats", "decimate")) {
            Path output = temp.resolve(example + ".las");
            String text = Files.readString(Path.of("examples", example + ".json"))
                    .replace(".cache/test-data/so-2019.laz", source.toString())
                    .replace("build/results/" + example + ".las", output.toString());
            var run = Pipeline.parse(new StringReader(text)).run(2, false);
            assertEquals(5, run.pointsRead());
            switch (example) {
                case "ground-crop" -> {
                    assertEquals(2, run.pointsWritten());
                    assertEquals(4, run.statistics().getFirst().stage());
                    assertEquals(2, run.statistics().getFirst().dimensions().getFirst().count());
                    assertEquals(List.of(-200, 400), rawX(output));
                }
                case "stats" -> {
                    assertEquals(5, run.pointsSelected());
                    assertEquals(0, run.pointsWritten());
                    assertEquals(5, run.statistics().getFirst().dimensions().size());
                }
                case "decimate" -> assertEquals(List.of(-200), rawX(output));
            }
        }
    }

    @Test
    void cropAndExpressionAreInclusiveAndCollectStatisticsAtTheirPositions() throws IOException {
        var pipeline = pipeline(LasIoTest.fixture(3, "laz"), """
                {"type":"filters.stats","dimensions":"X"},
                {"type":"filters.crop","bounds":"([2593998,2594004],[1227495.8,1227500],[699.985,700.345])"},
                {"type":"filters.expression","expression":"Classification == 2 || Classification == 6 && !(Intensity < +2.1e1)"},
                {"type":"filters.stats","dimensions":"X,Intensity"},
                {"type":"writers.null"}
                """);
        var result = pipeline.run(1, false);
        assertEquals(result, pipeline.run(4, false));
        assertEquals(2, result.pointsSelected());
        var before = result.statistics().getFirst();
        var after = result.statistics().getLast();
        assertEquals(2, before.stage());
        assertEquals(5, after.stage());
        assertEquals(5, before.dimensions().getFirst().count());
        assertEquals(10, before.dimensions().getFirst().variance());
        var x = after.dimensions().getFirst();
        assertEquals(2594001, x.average());
        assertEquals(2593998, x.minimum());
        assertEquals(2594004, x.maximum());
        assertEquals(18, x.variance());
        assertEquals(Math.sqrt(18), x.stddev());
        assertEquals(65535, after.dimensions().getLast().maximum());

        var outside = pipeline(LasIoTest.fixture(3, "las"), """
                {"type":"filters.crop","bounds":"([2593998,2594004],[1227495.8,1227500])","outside":true},
                {"type":"filters.stats","dimensions":"X"},
                {"type":"writers.null"}
                """).run(5, false);
        assertEquals(1, outside.pointsSelected());
        var one = outside.statistics().getFirst().dimensions().getFirst();
        assertEquals(2594006, one.average());
        assertNull(one.variance());
        assertNull(one.stddev());
    }

    @Test
    void decimatesTheFilteredStreamAcrossBatchesAndChunks() throws IOException {
        Path output = temp.resolve("decimate.las");
        Path input = Path.of("src/test/resources/las/chunks.laz");
        String stages = """
                {"type":"filters.expression","expression":"X >= 2594000.01"},
                {"type":"filters.decimation","step":24999,"offset":1,"limit":50000},
                {"type":"writers.las","filename":"%s","forward":"all","extra_dims":"all"}
                """.formatted(output);
        var pipeline = pipeline(input, stages);
        var first = pipeline.run(7, false);
        assertEquals(50_003, first.pointsRead());
        assertEquals(3, first.pointsWritten());
        assertEquals(List.of(2, 25_001, 50_000), rawX(output));
        assertEquals(first, pipeline.run(32_768, true));
        assertEquals(List.of(2, 25_001, 50_000), rawX(output));
        assertEquals(2, pipeline(input, stages.replace("50000", "49999")).run(13, true).pointsWritten());
        assertEquals(List.of(2, 25_001), rawX(output));
    }

    @Test
    void validatesOptionsBeforeOpeningTheInputAndRejectsMissingDimensions() {
        var unknown = pipeline(Path.of("absent.las"), """
                {"type":"filters.crop","bounds":"([0,1],[0,1])","x":5},
                {"type":"writers.null"}
                """);
        assertTrue(assertThrows(IllegalArgumentException.class, () -> unknown.run(1, false))
                .getMessage().contains("Stage 2 (filters.crop): unsupported option x"));
        var missing = pipeline(LasIoTest.fixture(0, "las"), """
                {"type":"filters.expression","expression":"GpsTime > 1"},
                {"type":"writers.null"}
                """);
        assertTrue(assertThrows(IllegalArgumentException.class, () -> missing.run(1, false))
                .getMessage().contains("GpsTime is absent"));
        var comparison = pipeline(Path.of("absent.las"), """
                {"type":"filters.expression","expression":"X < Y"},
                {"type":"writers.null"}
                """);
        assertTrue(assertThrows(IllegalArgumentException.class, () -> comparison.run(1, false))
                .getMessage().contains("Stage 2 (filters.expression)"));
    }

    @Test
    void statisticsHandleSmallSpreadsAndAnEmptySelection() throws IOException {
        var mini = LasIoTest.bytes(LasIoTest.fixture(3, "las"));
        int start = mini.getInt(96);
        int stride = mini.getChar(105);
        for (int i = 0; i < 5; i++) {
            mini.putInt(start + i * stride, i);
        }
        var source = Files.write(temp.resolve("spread.las"), mini.array());
        var run = pipeline(source, """
                {"type":"filters.stats","dimensions":"X"},
                {"type":"filters.expression","expression":"Intensity < 0"},
                {"type":"filters.stats","dimensions":"X"},
                {"type":"writers.null"}
                """).run(3, false);
        assertEquals(0.00025, run.statistics().getFirst().dimensions().getFirst().variance(), 1e-10);
        var empty = run.statistics().getLast().dimensions().getFirst();
        assertEquals(0, empty.count());
        assertNull(empty.minimum());
        assertNull(empty.average());
        assertNull(empty.variance());
    }

    @Test
    void nonFiniteGpsValuesParticipateInTheCount() throws IOException {
        var mini = LasIoTest.bytes(LasIoTest.fixture(3, "las"));
        int start = mini.getInt(96);
        for (double value : new double[]{Double.NaN, Double.POSITIVE_INFINITY}) {
            mini.putDouble(start + 20, value);
            var source = Files.write(temp.resolve("gps.las"), mini.array());
            var run = pipeline(source, """
                    {"type":"filters.stats","dimensions":"GpsTime"},
                    {"type":"writers.null"}
                    """).run(2, false);
            var gps = run.statistics().getFirst().dimensions().getFirst();
            assertEquals(5, gps.count());
            assertEquals(1000.125, gps.minimum());
            assertEquals(Double.isNaN(value) ? 1000.5 : null, gps.maximum());
            assertNull(gps.average());
            assertNull(gps.variance());
            assertNull(gps.stddev());
        }
    }

    private static Pipeline pipeline(Path input, String remaining) {
        return Pipeline.parse(new StringReader("[\"" + input + "\", " + remaining + "]"));
    }

    private static List<Integer> rawX(Path path) throws IOException {
        try (var reader = new LasReader(path, 2)) {
            var values = new java.util.ArrayList<Integer>();
            for (var batch = reader.readBatch(); batch != null; batch = reader.readBatch()) {
                for (int i = 0; i < batch.size(); i++) {
                    values.add(batch.rawX(i));
                }
            }
            return values;
        }
    }
}
