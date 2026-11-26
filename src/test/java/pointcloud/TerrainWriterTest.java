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
        var reader = new GeoTiffReader(target.toFile());
        try {
            var coverage = reader.read();
            var raster = coverage.getRenderedImage().getData();
            assertEquals(2056, CRS.lookupEpsgCode(coverage.getCoordinateReferenceSystem2D(), true));
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
        var las = LasIoTest.bytes(LasIoTest.fixture(3, "las"));
        las.putDouble(131, .25).putDouble(139, .25).putDouble(147, Z_SCALE);
        las.putDouble(155, 2606740).putDouble(163, 1229150).putDouble(171, 700);
        int start = las.getInt(96);
        int stride = las.getChar(105);
        for (int i = 0; i < coordinates.length; i++) {
            int offset = start + i * stride;
            las.putInt(offset, coordinates[i][0]).putInt(offset + 4, coordinates[i][1]).putInt(offset + 8, coordinates[i][2]);
        }
        var geokeys = ByteBuffer.allocate(54 + 24).order(ByteOrder.LITTLE_ENDIAN);
        geokeys.position(2).put("LASF_Projection".getBytes(StandardCharsets.US_ASCII));
        geokeys.putChar(18, (char) 34735).putChar(20, (char) 24).position(54);
        for (int value : new int[]{1, 1, 0, 2, 1024, 0, 1, 1, 3072, 0, 1, 2056}) {
            geokeys.putChar((char) value);
        }
        las.putInt(96, start + geokeys.capacity()).putInt(100, las.getInt(100) + 1);
        return Files.write(Files.createTempFile(temp, "points-", ".las"),
                ByteBuffer.allocate(las.capacity() + geokeys.capacity()).put(las.array(), 0, start)
                        .put(geokeys.array()).put(las.array(), start, las.capacity() - start).array());
    }
}
