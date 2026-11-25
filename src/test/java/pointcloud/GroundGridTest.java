package pointcloud;

import org.geotools.api.metadata.spatial.PixelOrientation;
import org.geotools.gce.geotiff.GeoTiffReader;
import org.geotools.referencing.CRS;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.awt.image.DataBuffer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class GroundGridTest {
    private static final double ORIGIN_X = 2594100;
    private static final double ORIGIN_Y = 1227600;
    private static final double[] EMPTY_CELL = {-9999, -9999, -9999, 0};

    @TempDir Path temp;

    @ParameterizedTest
    @ValueSource(ints = {3, 8})
    void binsScaledPointsOnTheExpectedSideOfEachGridBoundary(int format) throws Exception {
        Path input = fixture(format);
        Path target = temp.resolve("grid.tif");
        var result = parse(input, target, "").run(2, false);
        assertEquals(5, result.pointsRead());
        assertEquals(5, result.pointsSelected());
        assertEquals(3, result.pointsBinned());
        assertEquals(0, result.pointsWritten());

        var reader = new GeoTiffReader(target.toFile());
        try {
            assertEquals("2056", reader.getMetadata().getGeoKey(3072));
            assertEquals("1", reader.getMetadata().getGeoKey(1025)); // PixelIsArea
            assertEquals(-9999, reader.getMetadata().getNoData());
            var coverage = reader.read();
            var gridToCrs = coverage.getGridGeometry().getGridToCRS2D(PixelOrientation.UPPER_LEFT);
            double[] corners = new double[4];
            gridToCrs.transform(new double[]{0, 0, 2, 2}, 0, corners, 0, 2);
            assertArrayEquals(new double[]{ORIGIN_X, ORIGIN_Y + 2, ORIGIN_X + 2, ORIGIN_Y}, corners);
            var raster = coverage.getRenderedImage().getData();
            assertEquals(2, raster.getWidth());
            assertEquals(2, raster.getHeight());
            assertEquals(4, raster.getNumBands());
            assertEquals(DataBuffer.TYPE_DOUBLE, raster.getTransferType());
            assertArrayEquals(EMPTY_CELL, raster.getPixel(0, 0, (double[]) null));
            assertArrayEquals(new double[]{710, 710, 710, 1}, raster.getPixel(1, 0, (double[]) null));
            assertArrayEquals(new double[]{701, 705, 703, 2}, raster.getPixel(0, 1, (double[]) null));
            assertArrayEquals(EMPTY_CELL, raster.getPixel(1, 1, (double[]) null));
        } finally {
            reader.dispose();
        }
    }

    @Test
    void writesAnEmptyGridAndReportsZeroBinnedPoints() throws Exception {
        Path target = temp.resolve("empty.tif");
        var result = parse(fixture(3), target, """
                {"type":"filters.expression","expression":"Z < 0"},
                """).run(2, false);
        assertEquals(5, result.pointsRead());
        assertEquals(0, result.pointsSelected());
        assertEquals(0, result.pointsBinned());

        var reader = new GeoTiffReader(target.toFile());
        try {
            var raster = reader.read().getRenderedImage().getData();
            for (int y = 0; y < 2; y++) {
                for (int x = 0; x < 2; x++) {
                    assertArrayEquals(EMPTY_CELL, raster.getPixel(x, y, (double[]) null));
                }
            }
        } finally {
            reader.dispose();
        }
    }

    @Test
    void rejectsMissingCrsWithoutPublishingRaster() throws IOException {
        Path target = temp.resolve("missing-crs.tif");
        var pipeline = parse(LasIoTest.fixture(3, "las"), target, "");
        var error = assertThrows(IOException.class, () -> pipeline.run(2, false));
        assertTrue(error.getMessage().contains("Stage 2 (writers.raster): Missing LAS CRS"), error::getMessage);
        assertFalse(Files.exists(target));
        assertNoPartFiles();
    }

    @Test
    void rejectsOldRasterWriterNameDuringParsing() {
        Path target = temp.resolve("grid.tif");
        String config = config(temp.resolve("missing.las"), target, "", 2, 2)
                .replace("\"writers.raster\"", "\"writers.gdal\"");
        var error = assertThrows(IllegalArgumentException.class, () -> Pipeline.parse(new StringReader(config)));
        assertEquals("Stage 2 (writers.gdal): change the stage type to writers.raster", error.getMessage());
        assertFalse(Files.exists(target));
    }

    @Test
    void rejectsAnOversizedGridDuringParsing() {
        Path target = temp.resolve("oversized.tif");
        String config = config(LasIoTest.fixture(3, "las"), target, "", 1001, 1000);
        var error = assertThrows(IllegalArgumentException.class, () -> Pipeline.parse(new StringReader(config)));
        assertTrue(error.getMessage().contains("at most 1000000 cells"), error::getMessage);
        assertFalse(Files.exists(target));
    }

    @Test
    void discardsStagedRasterWhenInterruptedBeforePublication() throws Exception {
        Path target = temp.resolve("interrupted.tif");
        var pipeline = parse(fixture(3), target, "");
        assertThrows(InterruptedIOException.class, () -> pipeline.run(2, false, Pipeline::openSequential,
                (result, finish) -> {
                    assertFalse(Files.exists(target));
                    assertEquals(3, result.pointsBinned());
                    Thread.currentThread().interrupt();
                    try {
                        finish.finish();
                    } finally {
                        Thread.interrupted();
                    }
                }));
        assertFalse(Files.exists(target));
        assertNoPartFiles();
    }

    @Test
    void failedRasterPublicationRestoresPreviousMetadata() throws Exception {
        Path target = Files.createDirectory(temp.resolve("occupied.tif"));
        Path previous = Files.writeString(target.resolve("keep"), "previous output");
        Path metadata = Files.writeString(temp.resolve("metadata.json"), "previous metadata");
        Path config = Files.writeString(temp.resolve("pipeline.json"), config(fixture(3), target, "", 2, 2));
        var output = new ByteArrayOutputStream();
        var errors = new ByteArrayOutputStream();
        assertEquals(2, Main.run(new String[]{"pipeline", config.toString(), "--metadata", metadata.toString(),
                        "--overwrite"}, new PrintStream(output), new PrintStream(errors)));
        assertTrue(errors.toString().contains(".tif.part"), errors::toString);
        assertEquals("", output.toString());
        assertEquals("previous metadata", Files.readString(metadata));
        assertEquals("previous output", Files.readString(previous));
        assertNoPartFiles();
    }

    private void assertNoPartFiles() throws IOException {
        try (var entries = Files.list(temp)) {
            assertFalse(entries.anyMatch(path -> path.getFileName().toString().endsWith(".part")));
        }
    }

    private static Pipeline parse(Path input, Path target, String filter) {
        return Pipeline.parse(new StringReader(config(input, target, filter, 2, 2)));
    }

    private static String config(Path input, Path target, String filter, int width, int height) {
        return """
                [%s, %s
                 {"type":"writers.raster","filename":%s,"binmode":true,"resolution":1,
                  "origin_x":%s,"origin_y":%s,"width":%d,"height":%d,
                  "output_type":"count,mean,min,max","dimension":"Z","data_type":"double","nodata":-9999}]
                """.formatted(Pipeline.JSON.toJson(input.toString()), filter, Pipeline.JSON.toJson(target.toString()),
                ORIGIN_X, ORIGIN_Y, width, height);
    }

    private Path fixture(int format) throws Exception {
        var las = LasIoTest.bytes(LasIoTest.fixture(format, "las"));
        las.putDouble(131, .25).putDouble(139, .25).putDouble(147, .5);
        las.putDouble(155, ORIGIN_X).putDouble(163, ORIGIN_Y).putDouble(171, 700);
        int points = las.getInt(96);
        int stride = las.getChar(105);
        int[][] coordinates = {{0, 0, 2}, {3, 3, 10}, {4, 4, 20}, {8, 0, 200}, {0, 8, 400}};
        for (int i = 0; i < coordinates.length; i++) {
            int offset = points + i * stride;
            las.putInt(offset, coordinates[i][0]).putInt(offset + 4, coordinates[i][1]).putInt(offset + 8, coordinates[i][2]);
        }
        if (format == 3) {
            var keys = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);
            for (int value : new int[]{1, 1, 0, 2, 1024, 0, 1, 1, 3072, 0, 1, 2056}) {
                keys.putChar((char) value);
            }
            return addProjectionRecord(las, 34735, keys.array(), false);
        }
        var wkt = (CRS.decode("EPSG:2056", true).toWKT() + "\0").getBytes(StandardCharsets.UTF_8);
        return addProjectionRecord(las, 2112, wkt, true);
    }

    private Path addProjectionRecord(ByteBuffer las, int id, byte[] payload, boolean extended) throws IOException {
        int headerSize = extended ? 60 : 54;
        var record = ByteBuffer.allocate(headerSize + payload.length).order(ByteOrder.LITTLE_ENDIAN);
        record.position(2).put("LASF_Projection".getBytes(StandardCharsets.US_ASCII));
        record.putChar(18, (char) id);
        int start;
        if (extended) {
            start = las.capacity();
            record.putLong(20, payload.length);
            if (las.getInt(243) == 0) {
                las.putLong(235, start);
            }
            las.putInt(243, las.getInt(243) + 1);
        } else {
            start = las.getInt(96);
            record.putChar(20, (char) payload.length);
            las.putInt(96, start + record.capacity()).putInt(100, las.getInt(100) + 1);
        }
        record.position(headerSize).put(payload);
        return Files.write(temp.resolve(id + ".las"), ByteBuffer.allocate(las.capacity() + record.capacity())
                .put(las.array(), 0, start).put(record.array()).put(las.array(), start, las.capacity() - start).array());
    }
}
