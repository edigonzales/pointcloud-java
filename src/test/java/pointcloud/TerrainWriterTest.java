package pointcloud;

import org.geotools.api.metadata.spatial.PixelOrientation;
import org.geotools.gce.geotiff.GeoTiffReader;
import org.geotools.referencing.CRS;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.awt.image.DataBuffer;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class TerrainWriterTest {
    private static final int[][] TRIANGLE = {{0, 0, 10}, {8, 0, 20}, {0, 8, 30}, {0, 0, 90}, {8, 0, 99}};
    private static final double Z_SCALE = 0.000000001;
    private static final String FILL_NEAREST = ",\"fill_nodata\":\"nearest\"";
    @TempDir Path temp;

    @Test
    void samplesPlaneAndHullEdgeAtCellCentresWithDoublePrecision() throws Exception {
        var target = temp.resolve("plane.tif");
        var result = pipeline(fixture(TRIANGLE), target, ",\"nodata\":-17,\"compression\":\"deflate\"")
                .run(2, false);
        assertEquals(5, result.pointsRead());
        assertEquals(5, result.pointsSelected());
        assertEquals(0, result.pointsWritten());
        assertNull(result.pointsBinned());
        assertEquals(new Pipeline.TerrainStatistics(3, 3, 6), result.dtm());
        assertArrayEquals(new double[]{
                -17, -17, -17,
                z(27.5), -17, -17,
                z(17.5), z(22.5), -17
        }, samples(target), 1e-12);
    }

    @Test
    void preservesPixelBitsAndMetadataWithDirectLv95Keys() throws Exception {
        var fast = temp.resolve("direct-lv95.tif");
        var fallback = temp.resolve("fallback-lv95.tif");
        // GeoTIFF 1.1 directories are handled by the existing fallback.
        var directKeys = new int[]{1, 1, 0, 3, 1024, 0, 1, 1, 3072, 0, 1, 2056, 3076, 0, 1, 9001};
        var fallbackKeys = directKeys.clone();
        fallbackKeys[2] = 1;
        assertEquals(pipeline(fixture(TRIANGLE, directKeys, null), fast, "").run(2, false),
                pipeline(fixture(TRIANGLE, fallbackKeys, null), fallback, "").run(2, false));
        assertArrayEquals(samples(fast), samples(fallback));
        assertEquals(-1, Files.mismatch(fast, fallback));
    }

    @Test
    void preservesOutputWithCantonalGeoKeyReferencesAndTheirFallback() throws Exception {
        int[] keys = {1, 1, 0, 8, 1024, 0, 1, 1, 1025, 0, 1, 1, 1026, 34737, 15, 0,
                2049, 34737, 8, 15, 2054, 0, 1, 9102, 2062, 34736, 3, 0, 3072, 0, 1, 2056,
                3076, 0, 1, 9001};
        var citation = projectionRecord(34737, "CH1903+ / LV95|CH1903+|\0".getBytes(StandardCharsets.US_ASCII));
        var parameters = projectionRecord(34736, new byte[3 * Double.BYTES]);
        var valid = temp.resolve("cantonal-geokeys.tif");
        var missing = temp.resolve("missing-geokey-parameter-record.tif");
        var outOfRange = temp.resolve("invalid-geokey-parameter-range.tif");
        var expected = pipeline(fixture(TRIANGLE, keys, null, parameters, citation), valid, "").run(2, false);
        assertEquals(expected, pipeline(fixture(TRIANGLE, keys, null, citation), missing, "").run(2, false));
        keys[27] = 1; // The datum shift can no longer read three doubles starting at index 1.
        assertEquals(expected, pipeline(fixture(TRIANGLE, keys, null, parameters, citation), outOfRange, "")
                .run(2, false));
        assertEquals(-1, Files.mismatch(valid, missing));
        assertEquals(-1, Files.mismatch(valid, outOfRange));
    }

    @Test
    void wktStillTakesPriorityOverDirectKeys() throws Exception {
        var wkt = CRS.decode("EPSG:4326", true).toWKT();
        var target = temp.resolve("wkt-first.tif");
        var source = fixture(TRIANGLE, new int[]{1, 1, 0, 2, 1024, 0, 1, 1, 3072, 0, 1, 2056}, wkt);
        var error = assertThrows(IOException.class, () -> pipeline(source, target, "").run(2, false));
        assertTrue(error.getMessage().contains("requires a projected horizontal LAS CRS"));
        assertNoPartialOutput(target);
    }

    @Test
    void resolvesOtherProjectedCrsThroughGeoTools() throws Exception {
        var target = temp.resolve("other-projection.tif");
        var source = fixture(TRIANGLE,
                new int[]{1, 1, 0, 3, 1024, 0, 1, 1, 3072, 0, 1, 2056, 3072, 0, 1, 32632}, null);
        assertEquals(3, pipeline(source, target, "").run(2, false).dtm().cellsCovered());
        assertEquals(3, java.util.Arrays.stream(samples(target, 32632)).filter(z -> z != -9999).count());
    }

    @Test
    void geographicKeysStillFailForTerrain() throws Exception {
        var target = temp.resolve("unprojected.tif");
        var source = fixture(TRIANGLE, new int[]{1, 1, 0, 2, 1024, 0, 1, 2, 2048, 0, 1, 4326}, null);
        var error = assertThrows(IOException.class, () -> pipeline(source, target, "").run(2, false));
        assertTrue(error.getMessage().contains("requires a projected horizontal LAS CRS"));
        assertNoPartialOutput(target);
    }

    @Test
    void rejectsUnsupportedAndTruncatedProjectionKeys() throws Exception {
        var target = temp.resolve("invalid-projection.tif");
        int[] keys = {1, 1, 0, 2, 1024, 0, 1, 1, 3072, 0, 1, 32767};
        var unsupported = fixture(TRIANGLE, keys, null);
        var error = assertThrows(IOException.class, () -> pipeline(unsupported, target, "").run(2, false));
        assertTrue(error.getMessage().contains("no supported horizontal EPSG code"));
        keys[3] = 3;
        var truncated = fixture(TRIANGLE, keys, null);
        error = assertThrows(IOException.class, () -> pipeline(truncated, target, "").run(2, false));
        assertTrue(error.getMessage().contains("Invalid LAS GeoKey directory"));
        keys[3] = 2;
        keys[11] = 1024; // EPSG operation method ID, not a CRS.
        var unknown = fixture(TRIANGLE, keys, null);
        error = assertThrows(IOException.class, () -> pipeline(unknown, target, "").run(2, false));
        assertTrue(error.getMessage().contains("Cannot resolve LAS CRS"));
        assertNoPartialOutput(target);
    }

    @Test
    void resolvesCocircularAndSharedEdgesIndependentlyOfBatchSize() throws Exception {
        var source = fixture(new int[][]{{0, 0, 0}, {8, 0, 0}, {0, 8, 0}, {8, 8, 80}, {8, 8, 7}});
        var single = temp.resolve("single.tif");
        var batched = temp.resolve("batched.tif");
        var one = pipeline(source, single, "").run(1, false);
        var two = pipeline(source, batched, "").run(4, false);
        assertEquals(one, two);
        assertEquals(new Pipeline.TerrainStatistics(4, 4, 5), two.dtm());
        double[] heights = samples(single);
        assertArrayEquals(heights, samples(batched));
        for (int cell : new int[]{3, 4, 6, 7}) {
            assertTrue(heights[cell] >= 700 && heights[cell] <= z(80));
        }
    }

    @Test
    void keepsFirstZeroKeyAcrossBatchesWithSignedCoordinates() throws Exception {
        var source = fixture(new int[][]{{-8, 0, 10}, {16, 0, 20}, {0, 0, 40}, {0, 16, 30}, {0, 0, 99}});
        var target = temp.resolve("signed-xy.tif");
        var result = pipeline(source, target, "").run(2, false);
        assertEquals(5, result.pointsSelected());
        assertEquals(4, result.dtm().supportPoints());
        assertEquals(z(36.25), samples(target)[6], 1e-12);
    }

    @Test
    void primitiveKeysDistinguishCollisionsAndKeepEntriesAcrossResizes() {
        var keys = new TerrainWriter.LongKeys();
        assertTrue(keys.add(0));
        assertFalse(keys.add(0));
        // All these mixed keys begin in slot 1, even after the first several resizes.
        for (long i = -4096; i <= 4096; i++) {
            assertTrue(keys.add((i << 16) | 1), "insert " + i);
        }
        for (long i = 4096; i >= -4096; i--) {
            assertFalse(keys.add((i << 16) | 1), "duplicate " + i);
        }
        assertFalse(keys.add(0));
        assertTrue(keys.add(Long.MIN_VALUE));
        assertTrue(keys.add(Long.MAX_VALUE));
    }

    @Test
    void retainsSupportEvenWhenEveryTriangleIsOutsideTheRaster() throws Exception {
        var source = fixture(new int[][]{{20, 20, 10}, {28, 20, 20}, {20, 28, 30}, {20, 20, 90}, {28, 20, 99}});
        var target = temp.resolve("outside.tif");
        var support = temp.resolve("outside-support.tif");
        var result = pipeline(source, target, supportOption(support)).run(2, false);
        assertEquals(new Pipeline.TerrainStatistics(3, 0, 9), result.dtm());
        assertArrayEquals(new double[]{-9999, -9999, -9999, -9999, -9999, -9999, -9999, -9999, -9999},
                samples(target));
        assertArrayEquals(new int[9], supportSamples(support));
    }

    @Test
    void acceptsEdgesEqualToLimitAndRejectsLongerEdges() throws Exception {
        var source = fixture(TRIANGLE);
        double limit = Math.hypot(2, 2);
        var exact = pipeline(source, temp.resolve("exact.tif"), ",\"max_triangle_edge_length\":" + limit);
        var rejected = temp.resolve("rejected.tif");
        var shorter = pipeline(source, rejected, ",\"max_triangle_edge_length\":" + Math.nextDown(limit));
        assertEquals(new Pipeline.TerrainStatistics(3, 3, 6), exact.run(2, false).dtm());
        assertEquals(new Pipeline.TerrainStatistics(3, 0, 9), shorter.run(2, false).dtm());
        assertArrayEquals(new double[]{-9999, -9999, -9999, -9999, -9999, -9999, -9999, -9999, -9999},
                samples(rejected));
    }

    @ParameterizedTest
    @ValueSource(ints = {35_000, 35_001})
    void dispatchesAtTheSpanBoundaryOfSelectedLasPoints(int span) throws Exception {
        var input = fixture(new int[][]{{0, 0, 0}, {0, 8, 8}, {span, 0, span}, {0, 0, 99}, {0, 8, 99}});
        var target = temp.resolve("span.tif");
        var support = temp.resolve("span-support.tif");
        try (var source = Pipeline.openSequential(input, 2);
             var output = new GeoTiffOutput(input, target, false, source.header(), source.channel())) {
            var grid = new TerrainWriter.Grid(2606740, 1229150, 1, 3, 3, Double.POSITIVE_INFINITY,
                    GeoTiffOutput.NODATA, false);
            var writer = new TerrainWriter(output, grid, source.header());
            for (var batch = source.reader().readBatch(); batch != null; batch = source.reader().readBatch()) {
                for (int point = 0; point < batch.size(); point++) {
                    writer.add(batch, point);
                }
            }
            if (span == 35_000) {
                var raw = writer.triangulateRaw();
                assertNotNull(raw);
                assertEquals(3, raw.edges);
            } else {
                assertNull(writer.triangulateRaw());
            }
        }

        var result = pipeline(input, target, supportOption(support)).run(2, false);
        assertEquals(new Pipeline.TerrainStatistics(3, 6, 3), result.dtm());
        assertArrayEquals(new double[]{
                -9999, -9999, -9999,
                z(8), z(12), z(16),
                z(4), z(8), z(12)
        }, samples(target), 1e-12);
        assertArrayEquals(new int[]{0, 0, 0, 1, 1, 1, 1, 1, 1}, supportSamples(support));
    }

    @Test
    void fillsTheCappedCocircularMeshWithoutChangingItsRetainedTriangle() throws Exception {
        // Both diagonals are Delaunay. Only AC is shorter than the edge limit.
        var source = fixture(new int[][]{{11, 7, 10}, {10, 10, 20}, {3, 11, 30}, {2, 4, 40}});
        var open = temp.resolve("cocircular.tif");
        var capped = temp.resolve("cocircular-capped.tif");
        var filled = temp.resolve("cocircular-filled.tif");
        String cap = ",\"max_triangle_edge_length\":2.3";
        assertEquals(new Pipeline.TerrainStatistics(4, 3, 6), pipeline(source, open, "").run(2, false).dtm());
        assertEquals(new Pipeline.TerrainStatistics(4, 2, 7), pipeline(source, capped, cap).run(2, false).dtm());
        assertEquals(new Pipeline.TerrainStatistics(4, 9, 0, 7),
                pipeline(source, filled, cap + FILL_NEAREST).run(2, false).dtm());
        var original = samples(capped);
        var complete = samples(filled);
        assertArrayEquals(new double[]{-9999, z(24), z(20), -9999, -9999, -9999, -9999, -9999, -9999},
                original, 1e-12);
        assertArrayEquals(new double[]{z(30), original[1], original[2], z(40), z(40), z(10), z(40), z(40), z(10)},
                complete);
    }

    @ParameterizedTest
    @ValueSource(strings = {"none", "deflate"})
    void fillsOnlyCellsExcludedByTheTriangleEdgeLimit(String compression) throws Exception {
        // Three metres tall. The two left faces have shorter diagonals than the two right faces.
        var source = fixture(new int[][]{{0, 0, 0}, {0, 12, 12}, {4, 0, 4}, {4, 12, 16},
                {12, 0, 12}, {12, 12, 24}});
        var pure = temp.resolve("capped.tif");
        var complete = temp.resolve("capped-complete.tif");
        var pureWithMask = temp.resolve("capped-with-mask.tif");
        var completeWithMask = temp.resolve("capped-complete-with-mask.tif");
        var pureMask = temp.resolve("capped-support.tif");
        var completeMask = temp.resolve("capped-complete-support.tif");
        String cap = ",\"max_triangle_edge_length\":3.3,\"compression\":\"" + compression + "\"";
        var pureResult = pipeline(source, pure, cap).run(2, false);
        var completeResult = pipeline(source, complete, cap + FILL_NEAREST).run(2, false);
        assertEquals(new Pipeline.TerrainStatistics(6, 3, 6), pureResult.dtm());
        assertEquals(new Pipeline.TerrainStatistics(6, 9, 0, 6), completeResult.dtm());
        assertEquals(pureResult, pipeline(source, pureWithMask, cap + supportOption(pureMask)).run(2, false));
        assertEquals(completeResult, pipeline(source, completeWithMask, cap + FILL_NEAREST
                + supportOption(completeMask)).run(2, false));
        assertEquals(-1, Files.mismatch(pure, pureWithMask));
        assertEquals(-1, Files.mismatch(complete, completeWithMask));
        assertArrayEquals(new int[]{1, 0, 0, 1, 0, 0, 1, 0, 0}, supportSamples(pureMask));
        assertArrayEquals(new int[]{1, 2, 2, 1, 2, 2, 1, 2, 2}, supportSamples(completeMask));

        double[] original = samples(pure);
        double[] filled = samples(complete);
        for (int row = 0; row < 3; row++) {
            assertEquals(Double.doubleToRawLongBits(original[row * 3]), Double.doubleToRawLongBits(filled[row * 3]));
        }
        assertArrayEquals(new double[]{z(12), z(16), z(24), z(8), z(4), z(12), z(4), z(4), z(12)}, filled, 1e-12);
    }

    @Test
    void fillsAnEntireGridOutsideTheSupportHull() throws Exception {
        var source = fixture(new int[][]{{20, 20, 10}, {28, 20, 20}, {20, 28, 30}});
        var target = temp.resolve("complete-outside.tif");
        var support = temp.resolve("complete-outside-support.tif");
        assertEquals(new Pipeline.TerrainStatistics(3, 9, 0, 9),
                pipeline(source, target, FILL_NEAREST + supportOption(support)).run(2, false).dtm());
        var expected = new double[9];
        Arrays.fill(expected, z(10));
        assertArrayEquals(expected, samples(target));
        assertArrayEquals(new int[]{2, 2, 2, 2, 2, 2, 2, 2, 2}, supportSamples(support));
    }

    @Test
    void leavesAnAlreadyCompleteTinUnchanged() throws Exception {
        var source = fixture(new int[][]{{0, 0, 0}, {0, 12, 12}, {12, 0, 12}, {12, 12, 24}});
        var pure = temp.resolve("full-tin.tif");
        var complete = temp.resolve("full-tin-complete.tif");
        var support = temp.resolve("full-tin-support.tif");
        assertEquals(new Pipeline.TerrainStatistics(4, 9, 0), pipeline(source, pure, "").run(2, false).dtm());
        assertEquals(new Pipeline.TerrainStatistics(4, 9, 0, 0),
                pipeline(source, complete, FILL_NEAREST + supportOption(support)).run(2, false).dtm());
        assertArrayEquals(samples(pure), samples(complete));
        assertArrayEquals(new int[]{1, 1, 1, 1, 1, 1, 1, 1, 1}, supportSamples(support));
    }

    @Test
    void picksFirstEquidistantSupportEvenWhenItsHeightMatchesLegacyNoData() throws Exception {
        // The first column is equidistant from both supports, with the higher X stored first.
        var source = fixture(new int[][]{{4, 2, 0}, {0, 2, 90}});
        var target = temp.resolve("nearest-tie.tif");
        var support = temp.resolve("nearest-tie-support.tif");
        assertEquals(new Pipeline.TerrainStatistics(2, 9, 0, 9),
                pipeline(source, target, FILL_NEAREST + ",\"nodata\":700" + supportOption(support)).run(1, false).dtm());
        var expected = new double[9];
        Arrays.fill(expected, 700);
        assertArrayEquals(expected, samples(target));
        assertArrayEquals(new int[]{2, 2, 2, 2, 2, 2, 2, 2, 2}, supportSamples(support));
        var reader = new GeoTiffReader(target.toFile());
        try {
            assertFalse(reader.getMetadata().hasNoData());
        } finally {
            reader.dispose();
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3})
    void fillsFromSparseOrCollinearSupport(int count) throws Exception {
        int[][] points = {{0, 0, 10}, {4, 0, 20}, {8, 0, 30}};
        var source = fixture(Arrays.copyOf(points, count));
        var target = temp.resolve("sparse-complete.tif");
        assertEquals(new Pipeline.TerrainStatistics(count, 9, 0, 9),
                pipeline(source, target, FILL_NEAREST).run(1, false).dtm());
        double a = z(10);
        double b = z(count >= 2 ? 20 : 10);
        double c = z(count == 3 ? 30 : count == 2 ? 20 : 10);
        assertArrayEquals(new double[]{a, b, c, a, b, c, a, b, c}, samples(target));
    }

    @Test
    void failsWithoutPublishingWhenThereAreNoSelectedPointsToFillFrom() throws Exception {
        var source = fixture(new int[0][]);
        var target = temp.resolve("empty-complete.tif");
        var error = assertThrows(IOException.class, () -> pipeline(source, target, FILL_NEAREST).run(2, false));
        assertTrue(error.getMessage().contains("requires at least one selected support point"), error::getMessage);
        assertNoPartialOutput(target);
    }

    @ParameterizedTest
    @ValueSource(strings = {",\"max_triangle_edge_length\":0", ",\"nodata\":1e900", ",\"compression\":\"lzw\"",
            ",\"width\":1000001", ",\"window_size\":1", ",\"resolution\":0",
            ",\"fill_nodata\":\"idw\"", ",\"fill_nodata\":true"})
    void rejectsInvalidStageOptions(String options) {
        var error = assertThrows(IllegalArgumentException.class, () -> pipeline(temp.resolve("input.las"),
                temp.resolve("output.tif"), options));
        assertTrue(error.getMessage().startsWith("Stage 2 (writers.dtm): "), error::getMessage);
    }

    @Test
    void rejectsCollinearSupportAndNoDataCollisions() throws Exception {
        var target = temp.resolve("invalid.tif");
        var line = fixture(new int[][]{{0, 0, 1}, {1, 1, 1}, {2, 2, 1}, {4, 4, 1}, {8, 8, 1}});
        var error = assertThrows(IOException.class, () -> pipeline(line, target, "").run(2, false));
        assertTrue(error.getMessage().contains("non-collinear"));
        var plane = fixture(new int[][]{{0, 0, 0}, {8, 0, 0}, {0, 8, 0}, {0, 0, 0}, {8, 0, 0}});
        error = assertThrows(IOException.class, () -> pipeline(plane, target, ",\"nodata\":700").run(2, false));
        assertTrue(error.getMessage().contains("nodata collision"));
        assertNoPartialOutput(target);
    }

    @Test
    void limitsSelectedRowsEvenWhenTheyShareCoordinates() throws Exception {
        var target = temp.resolve("too-many.tif");
        var error = assertThrows(IOException.class, () -> pipeline(fixture(TRIANGLE), target, "").run(5, false,
                (path, size) -> {
                    var source = Pipeline.openSequential(path, size);
                    PointBatch points = source.reader().readBatch();
                    return new Pipeline.BatchInput(source.header(), source.channel(), () -> points, () -> false, source);
                }, Pipeline::publishOutput));
        assertTrue(error.getMessage().contains("1000000 selected points"));
        assertNoPartialOutput(target);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", FILL_NEAREST})
    void declinedEofAndCancellationPublishNothing(String options) throws Exception {
        var target = temp.resolve("interrupted.tif");
        var pipeline = pipeline(fixture(TRIANGLE), target, options);
        assertThrows(IOException.class, () -> pipeline.run(2, false, (path, size) -> {
            var source = Pipeline.openSequential(path, size);
            return new Pipeline.BatchInput(source.header(), source.channel(), source.reader(), () -> false, source);
        }, Pipeline::publishOutput));
        assertNoPartialOutput(target);

        try {
            assertThrows(InterruptedIOException.class, () -> pipeline.run(2, false, (path, size) -> {
                var source = Pipeline.openSequential(path, size);
                return new Pipeline.BatchInput(source.header(), source.channel(), source.reader(), () -> {
                    Thread.currentThread().interrupt();
                    return true;
                }, source);
            }, Pipeline::publishOutput));
        } finally {
            Thread.interrupted();
        }
        assertNoPartialOutput(target);

        assertThrows(InterruptedIOException.class, () -> pipeline.run(2, false, Pipeline::openSequential,
                (result, finish) -> {
                    Thread.currentThread().interrupt();
                    try {
                        finish.finish();
                    } finally {
                        Thread.interrupted();
                    }
                }));
        assertNoPartialOutput(target);
    }

    private void assertNoPartialOutput(Path target) throws IOException {
        assertFalse(Files.exists(target));
        try (var files = Files.list(temp)) {
            assertTrue(files.noneMatch(path -> path.getFileName().toString().endsWith(".part")));
        }
    }

    private static double z(double raw) {
        return 700 + raw * Z_SCALE;
    }

    private static double[] samples(Path target) throws Exception {
        return samples(target, 2056);
    }

    private static double[] samples(Path target, int epsg) throws Exception {
        var reader = new GeoTiffReader(target.toFile());
        try {
            var coverage = reader.read();
            var raster = coverage.getRenderedImage().getData();
            assertEquals(epsg, CRS.lookupEpsgCode(coverage.getCoordinateReferenceSystem2D(), true));
            assertEquals(DataBuffer.TYPE_DOUBLE, raster.getDataBuffer().getDataType());
            assertEquals(1, raster.getNumBands());
            return raster.getSamples(0, 0, 3, 3, 0, (double[]) null);
        } finally {
            reader.dispose();
        }
    }

    private static String supportOption(Path target) {
        return ",\"support_filename\":\"" + target + "\"";
    }

    private static int[] supportSamples(Path target) throws Exception {
        var reader = new GeoTiffReader(target.toFile());
        try {
            var metadata = reader.getMetadata();
            assertFalse(metadata.hasNoData());
            assertEquals("1", metadata.getGeoKey(1025)); // PixelIsArea
            var descriptions = metadata.getAsciiTIFFTag("42112");
            assertNotNull(descriptions);
            assertTrue(descriptions.contains("sample=\"0\" role=\"description\">support</Item>"), descriptions);
            var coverage = reader.read();
            try {
                assertEquals(2056, CRS.lookupEpsgCode(coverage.getCoordinateReferenceSystem2D(), true));
                var gridToCrs = coverage.getGridGeometry().getGridToCRS2D(PixelOrientation.UPPER_LEFT);
                double[] corners = new double[4];
                gridToCrs.transform(new double[]{0, 0, 3, 3}, 0, corners, 0, 2);
                assertArrayEquals(new double[]{2606740, 1229153, 2606743, 1229150}, corners);
                var raster = coverage.getRenderedImage().getData();
                assertEquals(3, raster.getWidth());
                assertEquals(3, raster.getHeight());
                assertEquals(1, raster.getNumBands());
                assertEquals(DataBuffer.TYPE_BYTE, raster.getTransferType());
                return raster.getSamples(0, 0, 3, 3, 0, (int[]) null);
            } finally {
                coverage.dispose(true);
            }
        } finally {
            reader.dispose();
        }
    }

    private static Pipeline pipeline(Path input, Path target, String options) {
        return Pipeline.parse(new StringReader("""
                [
                  "%s",
                  {"type":"writers.dtm","filename":"%s","resolution":1,
                   "origin_x":2606740,"origin_y":1229150,"width":3,"height":3%s}
                ]
                """.formatted(input, target, options)));
    }

    private Path fixture(int[][] coordinates) throws IOException {
        return fixture(coordinates, new int[]{1, 1, 0, 2, 1024, 0, 1, 1, 3072, 0, 1, 2056}, null);
    }

    private Path fixture(int[][] coordinates, int[] keys, String wkt, byte[]... auxiliaryRecords) throws IOException {
        var las = LasIoTest.bytes(LasIoTest.fixture(3, "las"));
        las.putDouble(131, .25).putDouble(139, .25).putDouble(147, Z_SCALE);
        las.putDouble(155, 2606740).putDouble(163, 1229150).putDouble(171, 700);
        int start = las.getInt(96);
        int stride = las.getChar(105);
        int templateCount = las.getInt(107);
        var points = ByteBuffer.allocate(coordinates.length * stride).order(ByteOrder.LITTLE_ENDIAN);
        int[] returnCounts = new int[5];
        for (int i = 0; i < coordinates.length; i++) {
            int offset = points.position();
            points.put(las.array(), start + (i % templateCount) * stride, stride);
            points.putInt(offset, coordinates[i][0]).putInt(offset + 4, coordinates[i][1])
                    .putInt(offset + 8, coordinates[i][2]);
            int returnNumber = points.get(offset + 14) & 7;
            returnCounts[returnNumber - 1]++;
        }
        las.putInt(107, coordinates.length);
        for (int i = 0; i < returnCounts.length; i++) {
            las.putInt(111 + i * Integer.BYTES, returnCounts[i]);
        }
        var keyBytes = ByteBuffer.allocate(keys.length * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (int value : keys) {
            keyBytes.putChar((char) value);
        }
        var geokeys = projectionRecord(34735, keyBytes.array());
        var wktRecord = wkt == null ? new byte[0] : projectionRecord(2112, (wkt + "\0").getBytes(StandardCharsets.UTF_8));
        int metadataLength = geokeys.length + wktRecord.length;
        for (byte[] auxiliary : auxiliaryRecords) {
            metadataLength += auxiliary.length;
        }
        las.putInt(96, start + metadataLength)
                .putInt(100, las.getInt(100) + (wkt == null ? 1 : 2) + auxiliaryRecords.length);
        var result = ByteBuffer.allocate(start + metadataLength + points.capacity()).put(las.array(), 0, start)
                .put(geokeys).put(wktRecord);
        for (byte[] auxiliary : auxiliaryRecords) {
            result.put(auxiliary);
        }
        result.put(points.array());
        return Files.write(Files.createTempFile(temp, "points-", ".las"), result.array());
    }

    private static byte[] projectionRecord(int recordId, byte[] content) {
        var record = ByteBuffer.allocate(54 + content.length).order(ByteOrder.LITTLE_ENDIAN);
        record.position(2).put("LASF_Projection".getBytes(StandardCharsets.US_ASCII));
        record.putChar(18, (char) recordId).putChar(20, (char) content.length).position(54);
        return record.put(content).array();
    }
}
