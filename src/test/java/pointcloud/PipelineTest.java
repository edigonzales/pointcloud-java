package pointcloud;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

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

    @ParameterizedTest
    @ValueSource(ints = {0, 5})
    void polygonCropKeepsBoundariesAndPreservesOriginalRecords(int boundaryY) throws IOException {
        var mini = LasIoTest.bytes(LasIoTest.fixture(8, "las"));
        mini.putDouble(131, 1).putDouble(139, 1).putDouble(155, 0).putDouble(163, 0);
        int start = mini.getInt(96);
        int stride = mini.getChar(105);
        int[][] xy = {{1, 1}, {5, 5}, {0, boundaryY}, {-1, 1}, {3, 5}};
        for (int i = 0; i < xy.length; i++) {
            int record = start + i * stride;
            mini.putInt(record, xy[i][0]).putInt(record + 4, xy[i][1]);
        }
        Path source = Files.write(temp.resolve("polygon-input.las"), mini.array());
        String polygon = "POLYGON ((0 0, 10 0, 10 10, 0 10, 0 0), (3 3, 3 7, 7 7, 7 3, 3 3))";
        for (boolean outside : new boolean[]{false, true}) {
            Path output = temp.resolve("polygon-" + outside + ".las");
            var expected = outside ? List.of(1, 3) : List.of(0, 2, 4);
            var result = pipeline(source, """
                    {"type":"filters.crop","polygon":"%s","outside":%s},
                    {"type":"filters.stats","dimensions":"X"},
                    {"type":"writers.las","filename":"%s","forward":"all","extra_dims":"all"}
                    """.formatted(polygon, outside, output)).run(2, false);

            assertEquals(xy.length, result.pointsRead());
            assertEquals(expected.size(), result.pointsSelected());
            assertEquals(expected.size(), result.pointsWritten());
            assertEquals(expected.size(), result.statistics().getFirst().dimensions().getFirst().count());
            var selected = LasIoTest.bytes(output);
            assertEquals(stride, selected.getChar(105));
            int selectedStart = selected.getInt(96);
            for (int i = 0; i < expected.size(); i++) {
                int inputRecord = start + expected.get(i) * stride;
                int outputRecord = selectedStart + i * stride;
                assertArrayEquals(Arrays.copyOfRange(mini.array(), inputRecord, inputRecord + stride),
                        Arrays.copyOfRange(selected.array(), outputRecord, outputRecord + stride));
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "\"polygon\":\"POLYGON ((0 0,\"",
            "\"polygon\":\"POLYGON ((0 0, 1 1, 1 0, 0 1, 0 0))\"",
            "\"polygon\":\"POLYGON EMPTY\"",
            "\"polygon\":\"POINT (0 0)\"",
            "\"polygon\":\"POLYGON Z ((0 0 0, 1 0 0, 0 1 0, 0 0 0))\"",
            "\"polygon\":\"POLYGON ((0 0, 1 0, 0 1, 0 0)) POINT (1 1)\"",
            "\"polygon\":\"POLYGON ((0 0, 1 0, 0 1, 0 0))\",\"bounds\":\"([0,1],[0,1])\"",
            "\"outside\":false"
    })
    void rejectsInvalidPolygonCropOptionsBeforeOpeningInput(String options) {
        var invalid = pipeline(Path.of("absent.las"), """
                {"type":"filters.crop",%s},
                {"type":"writers.null"}
                """.formatted(options));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> invalid.run(2, false))
                .getMessage().startsWith("Stage 2 (filters.crop):"));
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

    @ParameterizedTest
    @ValueSource(ints = {3, 8})
    void selectsEachClassificationFlagAndPreservesRecords(int format) throws IOException {
        var source = classificationFixture(format);
        var original = LasIoTest.bytes(source);
        int originalStart = original.getInt(96);
        int stride = original.getChar(105);
        var flags = List.of("Synthetic", "KeyPoint", "Withheld", "Overlap");
        for (int i = 0; i < flags.size(); i++) {
            var flag = flags.get(i);
            Path output = temp.resolve(flag + ".las");
            var result = pipeline(source, """
                    {"type":"filters.expression","expression":"%s == 1"},
                    {"type":"writers.las","filename":"%s","forward":"all","extra_dims":"all"}
                    """.formatted(flag, output)).run(2, false);
            var expected = i == 3 ? List.of(4) : format == 3 ? List.of(i + 1, 4) : List.of(i + 1);
            assertEquals(expected.size(), result.pointsWritten(), flag);
            var selected = LasIoTest.bytes(output);
            assertEquals(stride, selected.getChar(105));
            for (int j = 0; j < expected.size(); j++) {
                int from = originalStart + expected.get(j) * stride;
                int to = selected.getInt(96) + j * stride;
                assertArrayEquals(Arrays.copyOfRange(original.array(), from, from + stride),
                        Arrays.copyOfRange(selected.array(), to, to + stride), flag);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {3, 8})
    void countsClassesAsExposedAtEachStatisticsStage(int format) throws IOException {
        var pipeline = pipeline(classificationFixture(format), """
                {"type":"filters.stats","dimensions":"Classification,X","count":"Classification"},
                {"type":"filters.expression","expression":"Overlap == 1"},
                {"type":"filters.stats","dimensions":"Classification","count":"Classification"},
                {"type":"filters.stats","dimensions":"X","count":"Classification"},
                {"type":"filters.expression","expression":"Classification < 0"},
                {"type":"filters.stats","dimensions":"Classification","count":"Classification"},
                {"type":"writers.null"}
                """);
        var result = pipeline.run(2, false);
        assertEquals(result, pipeline.run(5, false));
        var before = result.statistics().getFirst().dimensions();
        var expected = format == 3 ? Map.of(0, 1L, 2, 4L) : Map.of(2, 2L, 12, 2L, 255, 1L);
        assertEquals(expected, before.getFirst().bins());
        assertEquals(5, before.getFirst().count());
        assertNull(before.getLast().bins());
        var overlap = result.statistics().get(1).dimensions().getFirst();
        assertEquals(Map.of(format == 3 ? 0 : 12, 1L), overlap.bins());
        assertEquals(1, overlap.count());
        assertNull(result.statistics().get(2).dimensions().getFirst().bins());
        var empty = result.statistics().getLast().dimensions().getFirst();
        assertEquals(Map.of(), empty.bins());
        assertEquals(0, empty.count());
        assertNull(empty.average());
    }

    @Test
    void rejectsOtherCountDimensions() {
        var error = assertThrows(IllegalArgumentException.class, () -> pipeline(LasIoTest.fixture(3, "las"), """
                {"type":"filters.stats","dimensions":"X","count":"X"},
                {"type":"writers.null"}
                """).run(2, false));
        assertTrue(error.getMessage().contains("Stage 2 (filters.stats): count must be 'Classification'"));
    }

    private Path classificationFixture(int format) throws IOException {
        var mini = LasIoTest.bytes(LasIoTest.fixture(format, "las"));
        int start = mini.getInt(96);
        int stride = mini.getChar(105);
        int[] flags = format == 3 ? new int[]{0x02, 0x22, 0x42, 0x82, 0xec}
                : new int[]{0xf0, 0x01, 0x02, 0x04, 0x08};
        int[] modernClasses = {12, 2, 2, 255, 12};
        for (int i = 0; i < flags.length; i++) {
            int record = start + i * stride;
            mini.put(record + 15, (byte) flags[i]);
            if (format == 8) {
                mini.put(record + 16, (byte) modernClasses[i]);
            }
        }
        return Files.write(temp.resolve("classification-" + format + ".las"), mini.array());
    }

    @Test
    void selectsModernLasDimensionsWithoutChangingTheRecord() throws IOException {
        Path output = temp.resolve("modern.las");
        var run = pipeline(LasIoTest.fixture(8, "laz"), """
                {"type":"filters.expression","expression":"!(Red < 65004) && Green == 1196 && Blue == 40008 && Infrared == 62004 && GpsTime == 1000.5 && ReturnNumber == 5 && NumberOfReturns == 9"},
                {"type":"filters.stats","dimensions":"Infrared,GpsTime"},
                {"type":"writers.las","filename":"%s","forward":"all","extra_dims":"all"}
                """.formatted(output)).run(2, false);
        assertEquals(1, run.pointsWritten());
        var stats = run.statistics().getFirst().dimensions();
        assertEquals(62_004, stats.getFirst().average());
        assertEquals(1000.5, stats.getLast().average());
        var source = LasIoTest.bytes(LasIoTest.fixture(8, "las"));
        var selected = LasIoTest.bytes(output);
        int length = source.getChar(105);
        int start = source.getInt(96) + 4 * length;
        int selectedStart = selected.getInt(96);
        assertArrayEquals(Arrays.copyOfRange(source.array(), start, start + length),
                Arrays.copyOfRange(selected.array(), selectedStart, selectedStart + length));
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
