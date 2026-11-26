package pointcloud;

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

import static org.junit.jupiter.api.Assertions.*;

class TerrainWriterTest {
    private static final int[][] TRIANGLE = {{0, 0, 10}, {8, 0, 20}, {0, 8, 30}, {0, 0, 90}, {8, 0, 99}};
    private static final double Z_SCALE = 0.000000001;
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
        var result = pipeline(source, target, "").run(2, false);
        assertEquals(new Pipeline.TerrainStatistics(3, 0, 9), result.dtm());
        assertArrayEquals(new double[]{-9999, -9999, -9999, -9999, -9999, -9999, -9999, -9999, -9999},
                samples(target));
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
    @ValueSource(strings = {",\"max_triangle_edge_length\":0", ",\"nodata\":1e900", ",\"compression\":\"lzw\"",
            ",\"width\":1000001", ",\"window_size\":1", ",\"resolution\":0"})
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

    @Test
    void declinedEofAndCancellationPublishNothing() throws Exception {
        var target = temp.resolve("interrupted.tif");
        var pipeline = pipeline(fixture(TRIANGLE), target, "");
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
        for (int i = 0; i < coordinates.length; i++) {
            int offset = start + i * stride;
            las.putInt(offset, coordinates[i][0]).putInt(offset + 4, coordinates[i][1]).putInt(offset + 8, coordinates[i][2]);
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
        var result = ByteBuffer.allocate(las.capacity() + metadataLength).put(las.array(), 0, start)
                .put(geokeys).put(wktRecord);
        for (byte[] auxiliary : auxiliaryRecords) {
            result.put(auxiliary);
        }
        result.put(las.array(), start, las.capacity() - start);
        return Files.write(Files.createTempFile(temp, "points-", ".las"), result.array());
    }

    private static byte[] projectionRecord(int recordId, byte[] content) {
        var record = ByteBuffer.allocate(54 + content.length).order(ByteOrder.LITTLE_ENDIAN);
        record.position(2).put("LASF_Projection".getBytes(StandardCharsets.US_ASCII));
        record.putChar(18, (char) recordId).putChar(20, (char) content.length).position(54);
        return record.put(content).array();
    }
}
