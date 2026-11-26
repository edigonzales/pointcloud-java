package pointcloud;

import org.geotools.api.metadata.spatial.PixelOrientation;
import org.geotools.gce.geotiff.GeoTiffReader;
import org.geotools.referencing.CRS;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class GroundGridTest {
    private static final double ORIGIN_X = 2594100;
    private static final double ORIGIN_Y = 1227600;
    private static final double[] EMPTY_CELL = {-9999, -9999, -9999, 0};
    private static final String THRESHOLD_OPTIONS = """
            ,"output_type":"count","dimension":"HeightAboveGround","height_thresholds":[0.5,2,5]
            """;
    private static final String HAG_PERCENTILES = ",\"output_type\":\"count,p50,p90\",\"dimension\":\"HeightAboveGround\"";

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

    @ParameterizedTest
    @ValueSource(ints = {0, 1})
    void writesAnEmptyGridAndReportsZeroBinnedPoints(int windowSize) throws Exception {
        Path target = temp.resolve("empty.tif");
        String config = config(fixture(3), target, """
                {"type":"filters.expression","expression":"Z < 0"},
                """, 2, 2, ",\"window_size\":" + windowSize);
        var result = Pipeline.parse(new StringReader(config)).run(2, false);
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
    void writesMaximumHeightAboveGroundAndCountAfterTheHagFilter() throws Exception {
        Path input = fixture(3, new int[][]{{1, 1, 22}, {3, 3, 30}, {5, 5, 24}, {5, 1, 30}, {7, 1, 32}});
        Path dem = dem(input, 700, 707, 703.125, 713);

        Path target = temp.resolve("canopy.tif");
        String filters = hag(dem) + """
                {"type":"filters.expression","expression":"HeightAboveGround >= 5"},
                """;
        String config = config(input, target, filters, 2, 2,
                ",\"output_type\":\"max,count\",\"dimension\":\"HeightAboveGround\",\"window_size\":0");
        var result = Pipeline.parse(new StringReader(config)).run(2, false);
        assertEquals(5, result.pointsRead());
        assertEquals(3, result.pointsSelected());
        assertEquals(3, result.pointsBinned());
        assertEquals(0, result.pointsWritten());

        var reader = new GeoTiffReader(target.toFile());
        try {
            assertEquals("2056", reader.getMetadata().getGeoKey(3072));
            assertEquals(-9999, reader.getMetadata().getNoData());
            var coverage = reader.read();
            try {
                var raster = coverage.getRenderedImage().getData();
                assertEquals(DataBuffer.TYPE_DOUBLE, raster.getTransferType());
                assertEquals(2, raster.getNumBands());
                assertArrayEquals(new double[]{-9999, 0}, raster.getPixel(0, 0, (double[]) null));
                assertArrayEquals(new double[]{5, 1}, raster.getPixel(1, 0, (double[]) null));
                assertArrayEquals(new double[]{11.875, 2}, raster.getPixel(0, 1, (double[]) null));
                assertArrayEquals(new double[]{-9999, 0}, raster.getPixel(1, 1, (double[]) null));
            } finally {
                coverage.dispose(true);
            }
        } finally {
            reader.dispose();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"", ",\"output_type\":\"p50,p90\""})
    void rejectsHeightAboveGroundRasterWithoutAPrecedingHagFilter(String options) {
        Path target = temp.resolve("missing-hag.tif");
        String config = config(temp.resolve("missing.las"), target, "", 2, 2,
                ",\"dimension\":\"HeightAboveGround\"" + options);
        var error = assertThrows(IllegalArgumentException.class, () -> Pipeline.parse(new StringReader(config)));
        assertEquals("Stage 2 (writers.raster): HeightAboveGround requires a preceding filters.hag_dem stage",
                error.getMessage());
        assertFalse(Files.exists(target));
    }

    @Test
    void computesHeightPercentilesInRequestedOrderAlongsideThresholdFractions() throws Exception {
        Path input = fixture(3, new int[][]{{1, 1, 14}, {5, 5, 10}, {2, 2, -5}, {6, 6, 2}, {3, 3, -5}});
        Path target = temp.resolve("height-percentiles.tif");
        String options = """
                ,"output_type":"p90,count,p50,max","dimension":"HeightAboveGround",
                "height_thresholds":[0.5,5],"window_size":1,"nodata":-77.25
                """;
        String config = config(input, target, hag(dem(input, 700, 700, 700, 700)), 2, 2, options);
        var result = Pipeline.parse(new StringReader(config)).run(2, false);
        assertEquals(5, result.pointsRead());
        assertEquals(5, result.pointsSelected());
        assertEquals(5, result.pointsBinned());
        assertEquals(0, result.pointsWritten());

        double[] filled = {6, 0, -77.25, -77.25, -77.25, -77.25};
        assertPercentileGrid(target, -77.25, List.of("max", "count", "p90", "p50",
                "fraction_above_0.5", "fraction_above_5"), new double[][]{
                filled, {5, 2, 4.6, 3, 1, 0},
                {7, 3, 5.1, -2.5, 1.0 / 3, 1.0 / 3}, filled
        });
    }

    @ParameterizedTest
    @CsvSource({"p50,703", "p90,704.6"})
    void writesOneZPercentileAndPreservesASingleSample(String band, double interpolated) throws Exception {
        Path target = temp.resolve("z-percentile.tif");
        String config = config(fixture(3), target, "", 2, 2, ",\"output_type\":\"" + band + "\"");
        var result = Pipeline.parse(new StringReader(config)).run(2, false);
        assertEquals(3, result.pointsBinned());
        assertPercentileGrid(target, -9999, List.of(band), new double[][]{{-9999}, {710}, {interpolated}, {-9999}});
    }

    @Test
    void writesUnsignedIntensityPercentilesWithDeflate() throws Exception {
        var las = LasIoTest.bytes(fixture(8));
        int points = las.getInt(96);
        int stride = las.getChar(105);
        las.putChar(points + 12, (char) 65_535);
        las.putChar(points + stride + 12, (char) 32_768);
        las.putChar(points + 2 * stride + 12, (char) 0);
        Path input = Files.write(temp.resolve("intensity-percentiles.las"), las.array());
        Path target = temp.resolve("intensity-percentiles.tif");
        String options = ",\"output_type\":\"p90,p50\",\"dimension\":\"Intensity\",\"compression\":\"deflate\"";
        Pipeline.parse(new StringReader(config(input, target, "", 2, 2, options))).run(2, false);
        assertPercentileGrid(target, -9999, List.of("p90", "p50"), new double[][]{
                {-9999, -9999}, {0, 0}, {62258.3, 49151.5}, {-9999, -9999}
        });
    }

    @Test
    void writesNamedPercentileBandsForACompletelyEmptySelection() throws Exception {
        Path target = temp.resolve("empty-percentiles.tif");
        String filter = """
                {"type":"filters.expression","expression":"Z < 0"},
                """;
        String config = config(fixture(3), target, filter, 2, 2, ",\"output_type\":\"count,p50,p90\",\"window_size\":1");
        assertEquals(0, Pipeline.parse(new StringReader(config)).run(2, false).pointsBinned());
        double[] empty = {0, -9999, -9999};
        assertPercentileGrid(target, -9999, List.of("count", "p50", "p90"), new double[][]{empty, empty, empty, empty});
    }

    @ParameterizedTest
    @ValueSource(strings = {"none", "deflate"})
    void writesStrictHeightThresholdFractionsAcrossBatches(String compression) throws Exception {
        Path input = fixture(3, new int[][]{{1, 1, -2}, {5, 5, 10}, {2, 2, 1}, {6, 6, 12}, {3, 3, 4}});
        Path dem = dem(input, 700, 700, 700, 700);
        Path target = temp.resolve("fractions.tif");
        String options = """
                ,"output_type":"count","dimension":"HeightAboveGround",
                "height_thresholds":[-1,0.5,2.0,5e0],"compression":"%s"
                """.formatted(compression);
        String config = config(input, target, hag(dem), 2, 2, options);
        var result = Pipeline.parse(new StringReader(config)).run(2, false);
        assertEquals(5, result.pointsRead());
        assertEquals(5, result.pointsSelected());
        assertEquals(5, result.pointsBinned());
        assertEquals(0, result.pointsWritten());

        var reader = new GeoTiffReader(target.toFile());
        try {
            assertEquals(-9999, reader.getMetadata().getNoData());
            var descriptions = reader.getMetadata().getAsciiTIFFTag("42112");
            assertNotNull(descriptions);
            var expectedNames = List.of("count", "fraction_above_-1", "fraction_above_0.5",
                    "fraction_above_2", "fraction_above_5");
            for (int band = 0; band < expectedNames.size(); band++) {
                assertTrue(descriptions.contains("sample=\"" + band + "\" role=\"description\">"
                        + expectedNames.get(band) + "</Item>"), descriptions);
            }
            var coverage = reader.read();
            try {
                var raster = coverage.getRenderedImage().getData();
                assertEquals(DataBuffer.TYPE_DOUBLE, raster.getTransferType());
                assertEquals(5, raster.getNumBands());
                assertArrayEquals(new double[]{0, -9999, -9999, -9999, -9999},
                        raster.getPixel(0, 0, (double[]) null));
                assertArrayEquals(new double[]{2, 1, 1, 1, .5}, raster.getPixel(1, 0, (double[]) null));
                assertArrayEquals(new double[]{3, 2.0 / 3, 1.0 / 3, 0, 0}, raster.getPixel(0, 1, (double[]) null));
                assertArrayEquals(new double[]{0, -9999, -9999, -9999, -9999},
                        raster.getPixel(1, 1, (double[]) null));
            } finally {
                coverage.dispose(true);
            }
        } finally {
            reader.dispose();
        }
    }

    @Test
    void fillsRegularBandsAndLeavesThresholdFractionsUnobserved() throws Exception {
        Path input = fixture(3, new int[][]{{1, 1, 4}, {2, 2, 2}, {3, 3, 0}, {1, 2, 8}, {1, 3, 12}});
        Path dem = dem(input, 700, 700, 700, 700);
        Path target = temp.resolve("filled-statistics.tif");
        String options = """
                ,"output_type":"count,max","dimension":"HeightAboveGround","height_thresholds":[0.5,2,5],
                "window_size":1,"nodata":-77.25
                """;
        String config = config(input, target, hag(dem), 2, 2, options);
        Pipeline.parse(new StringReader(config)).run(2, false);

        var reader = new GeoTiffReader(target.toFile());
        try {
            assertEquals(-77.25, reader.getMetadata().getNoData());
            var coverage = reader.read();
            try {
                var raster = coverage.getRenderedImage().getData();
                assertArrayEquals(new double[]{6, 5, .8, .4, .2}, raster.getPixel(0, 1, (double[]) null));
                assertArrayEquals(new double[]{6, 0, -77.25, -77.25, -77.25},
                        raster.getPixel(1, 0, (double[]) null));
            } finally {
                coverage.dispose(true);
            }
        } finally {
            reader.dispose();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "1", "[]", "[null]", "[true]", "[\"0.5\"]", "[1e400]", "[2,0.5]",
            "[0.5,0.5]", "[-0.0,0.0]", "[0,1,2,3,4,5,6,7,8]"})
    void rejectsInvalidHeightThresholdLists(String thresholds) {
        Path target = temp.resolve("invalid-thresholds.tif");
        String options = ",\"output_type\":\"count\",\"dimension\":\"HeightAboveGround\",\"height_thresholds\":"
                + thresholds;
        String config = config(temp.resolve("missing.las"), target, hag(temp.resolve("dem.tif")),
                2, 2, options);
        var error = assertThrows(IllegalArgumentException.class, () -> Pipeline.parse(new StringReader(config)));
        assertTrue(error.getMessage().startsWith("Stage 3 (writers.raster): "), error::getMessage);
        assertTrue(error.getMessage().contains("height_thresholds"), error::getMessage);
        assertFalse(Files.exists(target));
    }

    @Test
    void requiresHeightAboveGroundAndCountForThresholds() {
        Path target = temp.resolve("invalid-threshold-mode.tif");
        Path input = temp.resolve("missing.las");
        String hag = hag(temp.resolve("dem.tif"));
        for (String option : List.of(",\"output_type\":\"count\",\"height_thresholds\":[2]",
                ",\"output_type\":\"max\",\"dimension\":\"HeightAboveGround\",\"height_thresholds\":[2]")) {
            String config = config(input, target, hag, 2, 2, option);
            var error = assertThrows(IllegalArgumentException.class, () -> Pipeline.parse(new StringReader(config)));
            assertTrue(error.getMessage().startsWith("Stage 3 (writers.raster): "), error::getMessage);
        }
        assertFalse(Files.exists(target));
    }

    @ParameterizedTest
    @ValueSource(strings = {THRESHOLD_OPTIONS, HAG_PERCENTILES})
    void rejectsHeightThresholdsAndPercentilesWithConfiguredHagFallback(String options) {
        Path target = temp.resolve("invalid-threshold-mode.tif");
        Path dem = temp.resolve("dem.tif");
        String hag = """
                {"type":"filters.hag_dem","raster":%s,"nodata_hag":-99},
                """.formatted(Pipeline.JSON.toJson(dem.toString()));
        String config = config(temp.resolve("missing.las"), target, hag, 2, 2, options);
        var error = assertThrows(IllegalArgumentException.class, () -> Pipeline.parse(new StringReader(config)));
        assertTrue(error.getMessage().startsWith("Stage 3 (writers.raster): "), error::getMessage);
        assertTrue(error.getMessage().contains("nodata_hag"), error::getMessage);
        assertFalse(Files.exists(target));
    }

    @Test
    void thresholdFractionsDetectNodataCollisionWithoutReplacingOutput() throws Exception {
        Path input = fixture(3, new int[][]{{1, 1, -2}, {5, 5, 10}, {2, 2, 1}, {6, 6, 12}, {3, 3, 4}});
        Path dem = dem(input, 700, 700, 700, 700);
        Path target = Files.writeString(temp.resolve("protected-fractions.tif"), "previous output");
        String config = config(input, target, hag(dem), 2, 2, THRESHOLD_OPTIONS + ",\"nodata\":0.5");
        var pipeline = Pipeline.parse(new StringReader(config));
        var error = assertThrows(IOException.class, () -> pipeline.run(2, true));
        assertTrue(error.getMessage().contains("collides with valid data"), error::getMessage);
        assertEquals("previous output", Files.readString(target));
        assertNoPartFiles();
    }

    @Test
    void fillsOnlyGapsAdjacentToMeasuredCells() throws Exception {
        Path input = fixture(3, new int[][]{{0, 0, 4}, {1, 1, 12}, {3, 3, 20}, {8, 8, 40}, {11, 11, 48}});
        Path implicit = temp.resolve("default.tif");
        Path disabled = temp.resolve("disabled.tif");
        Path filled = temp.resolve("filled.tif");
        var baseline = Pipeline.parse(new StringReader(config(input, implicit, "", 5, 5))).run(2, false);
        var zero = Pipeline.parse(new StringReader(config(input, disabled, "", 5, 5,
                """
                ,"window_size":0,"output_type":"count,mean,min,max","dimension":"Z",
                "data_type":"double","nodata":-9999,"compression":"none"
                """))).run(2, false);
        var one = Pipeline.parse(new StringReader(config(input, filled, "", 5, 5, ",\"window_size\":1")))
                .run(2, false);
        assertEquals(baseline, zero);
        assertEquals(baseline, one);
        assertEquals(5, one.pointsBinned());
        assertEquals(-1, Files.mismatch(implicit, disabled));

        double[] measuredA = {702, 710, 706, 3};
        double[] measuredB = {720, 724, 722, 2};
        double[] fromA = {702, 710, 706, 0};
        double[] fromB = {720, 724, 722, 0};
        double[] fromBoth = {711, 717, 714, 0};
        double[][][] expected = {
                {EMPTY_CELL, EMPTY_CELL, EMPTY_CELL, EMPTY_CELL, EMPTY_CELL},
                {EMPTY_CELL, fromB, fromB, fromB, EMPTY_CELL},
                {EMPTY_CELL, fromB, measuredB, fromB, EMPTY_CELL},
                {fromA, fromBoth, fromB, fromB, EMPTY_CELL},
                {measuredA, fromA, EMPTY_CELL, EMPTY_CELL, EMPTY_CELL}
        };
        var reader = new GeoTiffReader(filled.toFile());
        try {
            var raster = reader.read().getRenderedImage().getData();
            for (int row = 0; row < expected.length; row++) {
                for (int column = 0; column < expected[row].length; column++) {
                    assertArrayEquals(expected[row][column], raster.getPixel(column, row, (double[]) null),
                            "row " + row + ", column " + column);
                }
            }
        } finally {
            reader.dispose();
        }
    }

    @ParameterizedTest
    @ValueSource(longs = {2, Long.MAX_VALUE})
    void weightsMeasuredCellsInBothRings(long radius) throws Exception {
        Path input = fixture(3, new int[][]{{0, 0, 4}, {1, 1, 12}, {12, 0, 20}, {16, 0, 200}, {0, 4, 400}});
        Path target = temp.resolve("weighted.tif");
        String config = config(input, target, "", 4, 1,
                ",\"window_size\":" + radius + ",\"output_type\":\"stdev,count,mean\"");
        var result = Pipeline.parse(new StringReader(config)).run(2, false);
        assertEquals(3, result.pointsBinned());

        var reader = new GeoTiffReader(target.toFile());
        try {
            var raster = reader.read().getRenderedImage().getData();
            assertArrayEquals(new double[]{704, 2, 2}, raster.getPixel(0, 0, (double[]) null));
            assertArrayEquals(new double[]{706, 0, 4.0 / 3}, raster.getPixel(1, 0, (double[]) null), 1e-12);
            assertArrayEquals(new double[]{710, 1, 0}, raster.getPixel(3, 0, (double[]) null));
        } finally {
            reader.dispose();
        }
    }

    @Test
    void preservesTheHeightOfASingleDistantDonor() throws Exception {
        Path input = fixture(3, new int[][]{{0, 0, 4}, {200, 0, 0}, {0, 4, 0}, {200, 4, 0}, {0, 8, 0}});
        Path target = temp.resolve("single-donor.tif");
        String config = config(input, target, "", 50, 1, ",\"window_size\":49,\"output_type\":\"mean\"");
        assertEquals(1, Pipeline.parse(new StringReader(config)).run(2, false).pointsBinned());
        var reader = new GeoTiffReader(target.toFile());
        try {
            var raster = reader.read().getRenderedImage().getData();
            assertEquals(702, raster.getSampleDouble(49, 0, 0));
        } finally {
            reader.dispose();
        }
    }

    @Test
    void writesAllIntensityBandsWithLosslessDeflate() throws Exception {
        var las = LasIoTest.bytes(fixture(8));
        int points = las.getInt(96);
        int stride = las.getChar(105);
        las.putChar(points + 12, (char) 65_535);
        las.putChar(points + stride + 12, (char) 32_768);
        las.putChar(points + 2 * stride + 12, (char) 0);
        Path input = Files.write(temp.resolve("intensity.las"), las.array());
        String bands = ",\"output_type\":\"stdev,count,max,mean,min\",\"dimension\":\"Intensity\",\"nodata\":-77.25";
        for (String codec : new String[]{"none", "deflate"}) {
            Path output = temp.resolve(codec + ".tif");
            var config = config(input, output, "", 2, 2, bands + ",\"compression\":\"" + codec + "\"");
            Pipeline.parse(new StringReader(config)).run(2, false);
            var reader = new GeoTiffReader(output.toFile());
            try {
                assertEquals(-77.25, reader.getMetadata().getNoData());
                var raster = reader.read().getRenderedImage().getData();
                assertEquals(DataBuffer.TYPE_DOUBLE, raster.getTransferType());
                assertEquals(5, raster.getNumBands());
                assertArrayEquals(new double[]{32768, 65535, 49151.5, 2, 16383.5},
                        raster.getPixel(0, 1, (double[]) null));
                assertArrayEquals(new double[]{0, 0, 0, 1, 0}, raster.getPixel(1, 0, (double[]) null));
                assertArrayEquals(new double[]{-77.25, -77.25, -77.25, 0, -77.25},
                        raster.getPixel(0, 0, (double[]) null));
            } finally {
                reader.dispose();
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"count", "stdev"})
    void writesSingleBandWithAWindow(String band) throws Exception {
        Path target = temp.resolve("single.tif");
        String config = config(fixture(3), target, "", 2, 2,
                ",\"output_type\":\"" + band + "\",\"window_size\":20,\"compression\":\"deflate\"");
        Pipeline.parse(new StringReader(config)).run(2, false);
        var reader = new GeoTiffReader(target.toFile());
        try {
            var raster = reader.read().getRenderedImage().getData();
            assertEquals(1, raster.getNumBands());
            assertEquals(2, raster.getSampleDouble(0, 1, 0));
            assertEquals(band.equals("count") ? 0 : 1, raster.getSampleDouble(0, 0, 0));
        } finally {
            reader.dispose();
        }
    }

    @ParameterizedTest
    @CsvSource({"mean,706", "mean,714", "p50,706", "p90,723.6"})
    void rejectsNodataCollisionWithoutReplacingOutput(String band, double marker) throws Exception {
        Path input = fixture(3, new int[][]{{0, 0, 4}, {1, 1, 12}, {3, 3, 20}, {8, 8, 40}, {11, 11, 48}});
        Path target = Files.writeString(temp.resolve("protected.tif"), "previous output");
        String config = config(input, target, "", 5, 5,
                ",\"output_type\":\"" + band + "\",\"window_size\":1,\"nodata\":" + marker);
        var pipeline = Pipeline.parse(new StringReader(config));
        var error = assertThrows(IOException.class, () -> pipeline.run(2, true));
        assertTrue(error.getMessage().contains("collides with valid data"), error::getMessage);
        assertEquals("previous output", Files.readString(target));
        assertNoPartFiles();
    }

    @Test
    void rejectsNonfinitePercentileInterpolationWithoutPublishingRaster() throws Exception {
        var las = LasIoTest.bytes(fixture(3, new int[][]{
                {1, 1, Integer.MIN_VALUE}, {3, 3, Integer.MAX_VALUE}, {5, 5, 0}, {8, 0, 0}, {0, 8, 0}
        }));
        las.putDouble(147, 1e308 / Integer.MAX_VALUE).putDouble(171, 0);
        Path input = Files.write(temp.resolve("extreme-percentile.las"), las.array());
        Path target = temp.resolve("extreme-percentile.tif");
        String config = config(input, target, "", 2, 2, ",\"output_type\":\"p50\"");
        var pipeline = Pipeline.parse(new StringReader(config));
        var error = assertThrows(IOException.class, () -> pipeline.run(2, false));
        assertTrue(error.getMessage().contains("Nonfinite value at raster cell"), error::getMessage);
        assertFalse(Files.exists(target));
        assertNoPartFiles();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "\"output_type\":\"all\"", "\"output_type\":\"mean,mean\"", "\"output_type\":\"mean,\"",
            "\"output_type\":\"p50,p50\"", "\"output_type\":\"p90,count,p90\"", "\"output_type\":\"p95\"",
            "\"nodata\":0", "\"nodata\":1e400", "\"dimension\":\"Red\"", "\"compression\":\"lzw\""
    })
    void rejectsInvalidRasterOptions(String option) {
        String config = config(temp.resolve("missing.las"), temp.resolve("grid.tif"), "", 2, 2, "," + option);
        var error = assertThrows(IllegalArgumentException.class, () -> Pipeline.parse(new StringReader(config)));
        assertTrue(error.getMessage().startsWith("Stage 2 (writers.raster): "), error::getMessage);
    }

    @ParameterizedTest
    @ValueSource(strings = {"-1", "1.0", "\"1\"", "true", "null", "9223372036854775808"})
    void rejectsInvalidWindowSizeDuringParsing(String value) {
        Path target = temp.resolve("invalid-window.tif");
        String config = config(temp.resolve("missing.las"), target, "", 2, 2, ",\"window_size\":" + value);
        var error = assertThrows(IllegalArgumentException.class, () -> Pipeline.parse(new StringReader(config)));
        assertTrue(error.getMessage().startsWith("Stage 2 (writers.raster): window_size "), error::getMessage);
        assertFalse(Files.exists(target));
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

    @ParameterizedTest
    @ValueSource(strings = {",\"window_size\":1", THRESHOLD_OPTIONS, HAG_PERCENTILES})
    void discardsStagedRasterWhenInterruptedBeforePublication(String options) throws Exception {
        Path target = temp.resolve("interrupted.tif");
        Path input;
        String filters;
        if (options.contains("HeightAboveGround")) {
            input = fixture(3, new int[][]{{1, 1, 2}, {3, 3, 10}, {4, 4, 20}, {8, 1, 200}, {8, 1, 400}});
            Path dem = dem(input, 700, 700, 700, 700);
            filters = """
                    {"type":"filters.expression","expression":"X < 2594102"},
                    """ + hag(dem);
        } else {
            input = fixture(3);
            filters = "";
        }
        var pipeline = Pipeline.parse(new StringReader(config(input, target, filters, 2, 2, options)));
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

    private Path dem(Path input, double... samples) throws IOException {
        Path dem = temp.resolve("dem.tif");
        try (var source = Pipeline.openSequential(input, 2);
             var writer = new GeoTiffOutput(input, dem, false, source.header(), source.channel())) {
            writer.prepare(ORIGIN_X, ORIGIN_Y, 1, 2, 2, new double[][]{samples},
                    List.of("elevation"), -9999, false);
            writer.finish();
        }
        return dem;
    }

    private static void assertPercentileGrid(Path path, double nodata, List<String> bands, double[][] cells)
            throws IOException {
        var reader = new GeoTiffReader(path.toFile());
        try {
            assertEquals(nodata, reader.getMetadata().getNoData());
            String descriptions = reader.getMetadata().getAsciiTIFFTag("42112");
            assertNotNull(descriptions);
            for (int band = 0; band < bands.size(); band++) {
                assertTrue(descriptions.contains("sample=\"" + band + "\" role=\"description\">"
                        + bands.get(band) + "</Item>"), descriptions);
            }
            var coverage = reader.read();
            try {
                var raster = coverage.getRenderedImage().getData();
                assertEquals(2, raster.getWidth());
                assertEquals(2, raster.getHeight());
                assertEquals(DataBuffer.TYPE_DOUBLE, raster.getTransferType());
                assertEquals(bands.size(), raster.getNumBands());
                for (int cell = 0; cell < cells.length; cell++) {
                    assertArrayEquals(cells[cell], raster.getPixel(cell % 2, cell / 2, (double[]) null), 1e-12,
                            "raster cell " + cell);
                }
            } finally {
                coverage.dispose(true);
            }
        } finally {
            reader.dispose();
        }
    }

    private static String hag(Path dem) {
        return """
                {"type":"filters.hag_dem","raster":%s,"zero_ground":false},
                """.formatted(Pipeline.JSON.toJson(dem.toString()));
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
        return config(input, target, filter, width, height, "");
    }

    private static String config(Path input, Path target, String filter, int width, int height, String writerOptions) {
        return """
                [%s, %s
                 {"type":"writers.raster","filename":%s,"binmode":true,"resolution":1,
                  "origin_x":%s,"origin_y":%s,"width":%d,"height":%d%s}]
                """.formatted(Pipeline.JSON.toJson(input.toString()), filter, Pipeline.JSON.toJson(target.toString()),
                ORIGIN_X, ORIGIN_Y, width, height, writerOptions);
    }

    private Path fixture(int format) throws Exception {
        return fixture(format, new int[][]{{0, 0, 2}, {3, 3, 10}, {4, 4, 20}, {8, 0, 200}, {0, 8, 400}});
    }

    private Path fixture(int format, int[][] coordinates) throws Exception {
        var las = LasIoTest.bytes(LasIoTest.fixture(format, "las"));
        las.putDouble(131, .25).putDouble(139, .25).putDouble(147, .5);
        las.putDouble(155, ORIGIN_X).putDouble(163, ORIGIN_Y).putDouble(171, 700);
        int points = las.getInt(96);
        int stride = las.getChar(105);
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
