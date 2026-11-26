package pointcloud;

import it.geosolutions.imageioimpl.plugins.tiff.TIFFImageWriterSpi;
import org.geotools.coverage.grid.io.imageio.geotiff.GeoTiffIIOMetadataEncoder;
import org.geotools.gce.geotiff.GeoTiffWriter;
import org.geotools.referencing.CRS;
import org.geotools.referencing.crs.DefaultCompoundCRS;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.imageio.IIOImage;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.stream.FileImageOutputStream;
import java.awt.image.BufferedImage;
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
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class HagDemTest {
    private static final double X = 2594100;
    private static final double Y = 1227600;
    private static final double NODATA = -77.25;
    private static final String STATISTICS = """
            {"type":"filters.stats","dimensions":"HeightAboveGround"}
            """;
    private static final String SINK = """
            {"type":"writers.null"}
            """;

    @TempDir Path temp;

    @Test
    void usesTheContainingPixelThenFiltersWithoutChangingLasRecords() throws Exception {
        Path input = points(2056, coordinates());
        Path dem = dem(input, NODATA, new double[]{701, 710, 702, 703});
        Path target = temp.resolve("selected.las");
        var pipeline = pipeline(input, hag(dem, ""), """
                {"type":"filters.stats","dimensions":"Z,HeightAboveGround"}
                """, """
                {"type":"filters.expression","expression":"HeightAboveGround > 2 || HeightAboveGround == -1"}
                """, STATISTICS, las(target));
        var result = pipeline.run(2, false);

        assertEquals(5, result.pointsRead());
        assertEquals(3, result.pointsSelected());
        assertEquals(3, result.pointsWritten());
        assertEquals(List.of(3, 5), result.statistics().stream().map(Pipeline.StageStatistics::stage).toList());
        assertStats(result.statistics().get(0).dimensions().get(0), "Z", 5, 700, 719, 706.25);
        assertStats(result.statistics().get(0).dimensions().get(1), "HeightAboveGround", 5, -2, 9, 1.8);
        assertStats(result.statistics().get(1).dimensions().getFirst(), "HeightAboveGround", 3, -1, 9, 11.0 / 3);
        assertOriginalRecords(input, target, 1, 2, 4);
    }

    @Test
    void supportsBandSelectionClampsAndAnExplicitGroundClass() throws Exception {
        Path input = points(2056, coordinates());
        Path dem = dem(input, NODATA, new double[]{701, 710, 702, 703}, new double[]{700.5, 718, 704, 708});
        var clamped = pipeline(input, hag(dem, """
                ,"band":2,"zero_ground":false,"min_clamp":-4.5,"max_clamp":2.5
                """), STATISTICS, SINK).run(1, false);
        assertStats(clamped.statistics().getFirst().dimensions().getFirst(), "HeightAboveGround", 5, -4.5, 2.5, -.7);

        var alternateGround = pipeline(input, hag(dem, ",\"class\":5"), STATISTICS, SINK).run(1, false);
        assertStats(alternateGround.statistics().getFirst().dimensions().getFirst(),
                "HeightAboveGround", 5, -2, 4.25, .85);

        var tooHigh = assertThrows(IOException.class,
                () -> pipeline(input, hag(dem, ",\"band\":3"), SINK).run(2, false));
        assertTrue(tooHigh.getMessage().contains("Stage 2 (filters.hag_dem)"), tooHigh::getMessage);
    }

    @ParameterizedTest
    @ValueSource(strings = {"nodata", "east", "south"})
    void missingTerrainFailsAtomicallyAndCanBeOverridden(String caseName) throws Exception {
        var xyz = coordinates();
        if (caseName.equals("east")) {
            xyz[4][0] = 8;
        }
        if (caseName.equals("south")) {
            xyz[4][1] = 0;
        }
        Path input = points(2056, xyz);
        Path dem = dem(input, NODATA, new double[]{701, NODATA, 702, 703});
        Path target = Files.writeString(temp.resolve("previous.las"), "previous points");
        Path metadata = Files.writeString(temp.resolve("previous.json"), "previous metadata");
        Path config = Files.writeString(temp.resolve("pipeline.json"), config(input, hag(dem, ""), las(target)));
        assertCliFailure(config, metadata, "Stage 2 (filters.hag_dem)");
        assertEquals("previous points", Files.readString(target));
        assertEquals("previous metadata", Files.readString(metadata));
        assertNoPartFiles();

        var overridden = pipeline(input, hag(dem, ",\"nodata_hag\":-12.5"), STATISTICS, SINK).run(2, false);
        assertStats(overridden.statistics().getFirst().dimensions().getFirst(),
                "HeightAboveGround", 5, -12.5, 3, -2.5);

        var coveredOnly = pipeline(input, """
                {"type":"filters.expression","expression":"Z < 719"}
                """, hag(dem, ""), STATISTICS, SINK).run(2, false);
        assertEquals(4, coveredOnly.pointsSelected());
        assertStats(coveredOnly.statistics().getFirst().dimensions().getFirst(),
                "HeightAboveGround", 4, -2, 3, 0);
    }

    @Test
    void acceptsAValidHeightEqualToTheUsualMarkerWithoutANodataTag() throws Exception {
        Path input = points(2056, coordinates());
        Path dem = dem(input, Double.NaN, new double[]{701, -9999, 702, 703});
        var result = pipeline(input, hag(dem, ""), STATISTICS, SINK).run(2, false);
        assertStats(result.statistics().getFirst().dimensions().getFirst(),
                "HeightAboveGround", 5, -2, 10718, 2143.6);
    }

    @Test
    void rejectsMissingOrIncompatibleCrsAndUnreadableTerrain() throws Exception {
        Path input = points(2056, coordinates());
        Path target = temp.resolve("not-published.las");
        Path incompatible = dem(points(32632, coordinates()), NODATA, new double[]{701, 710, 702, 703});
        Path compatible = dem(input, NODATA, new double[]{701, 710, 702, 703});
        var mismatch = assertThrows(IOException.class,
                () -> pipeline(input, hag(incompatible, ""), las(target)).run(2, false));
        assertTrue(mismatch.getMessage().contains("Stage 2 (filters.hag_dem)"), mismatch::getMessage);
        var noCrs = assertThrows(IOException.class, () -> pipeline(LasIoTest.fixture(3, "las"),
                hag(compatible, ""), las(target)).run(2, false));
        assertTrue(noCrs.getMessage().contains("Stage 2 (filters.hag_dem)"), noCrs::getMessage);
        Path unreadable = Files.writeString(temp.resolve("broken.tif"), "not a TIFF");
        var noRaster = assertThrows(IOException.class,
                () -> pipeline(input, hag(unreadable, ""), las(target)).run(2, false));
        assertTrue(noRaster.getMessage().contains("Stage 2 (filters.hag_dem)"), noRaster::getMessage);
        assertFalse(Files.exists(target));
        assertNoPartFiles();
    }

    @Test
    void rejectsDifferentDeclaredVerticalReferences() throws Exception {
        var crs = new DefaultCompoundCRS("LV95 and LN02", CRS.decode("EPSG:2056", true),
                CRS.decode("EPSG:5728", true));
        Path input = points(crs.toWKT(), coordinates());
        Path compatible = verticalDem(5728);
        Path incompatible = verticalDem(5729);
        var result = pipeline(input, hag(compatible, ""), STATISTICS, SINK).run(2, false);
        assertStats(result.statistics().getFirst().dimensions().getFirst(), "HeightAboveGround", 5, -2, 9, 1.8);

        Path target = temp.resolve("vertical-mismatch.las");
        var error = assertThrows(IOException.class,
                () -> pipeline(input, hag(incompatible, ""), las(target)).run(2, false));
        assertTrue(error.getMessage().contains("vertical CRS differs"), error::getMessage);
        assertFalse(Files.exists(target));
        assertNoPartFiles();
    }

    @ParameterizedTest
    @ValueSource(strings = {",\"band\":0", ",\"class\":256", ",\"min_clamp\":1,\"max_clamp\":0",
            ",\"nodata_hag\":1e400", ",\"zero_ground\":2"})
    void rejectsInvalidHagOptions(String options) {
        Path input = temp.resolve("missing.las");
        Path dem = temp.resolve("missing.tif");
        var error = assertThrows(IllegalArgumentException.class,
                () -> pipeline(input, hag(dem, options), SINK).run(2, false));
        assertTrue(error.getMessage().contains("Stage 2 (filters.hag_dem)"), error::getMessage);
    }

    @Test
    void rejectsHeightReferencesBeforeHagAndRepeatedHagStages() {
        Path input = LasIoTest.fixture(3, "las");
        String hag = hag(temp.resolve("missing.tif"), "");
        String expression = """
                {"type":"filters.expression","expression":"HeightAboveGround >= 0"}
                """;
        assertThrows(IllegalArgumentException.class, () -> pipeline(input, STATISTICS, hag, SINK).run(2, false));
        assertThrows(IllegalArgumentException.class, () -> pipeline(input, expression, hag, SINK).run(2, false));
        assertThrows(IllegalArgumentException.class, () -> pipeline(input, hag, hag, SINK).run(2, false));
    }

    @Test
    void protectsTheDemFromOutputAndMetadataAliases() throws Exception {
        Path input = points(2056, coordinates());
        Path dem = dem(input, NODATA, new double[]{701, 710, 702, 703});
        byte[] samples = Files.readAllBytes(dem);
        Path alias = Files.createLink(temp.resolve("same-dem.las"), dem);
        assertThrows(IOException.class, () -> pipeline(input, hag(dem, ""), las(alias)).run(2, true));
        assertArrayEquals(samples, Files.readAllBytes(dem));

        Path target = temp.resolve("not-published.las");
        Path config = Files.writeString(temp.resolve("pipeline.json"), config(input, hag(dem, ""), las(target)));
        Path metadataAlias = Files.createLink(temp.resolve("same-dem.json"), dem);
        assertCliFailure(config, metadataAlias, "alias");
        assertArrayEquals(samples, Files.readAllBytes(dem));
        assertFalse(Files.exists(target));
        assertNoPartFiles();
    }

    @Test
    void interruptionLeavesLasUnpublished() throws Exception {
        Path input = points(2056, coordinates());
        Path dem = dem(input, NODATA, new double[]{701, 710, 702, 703});
        Path target = temp.resolve("cancelled.las");
        var pipeline = pipeline(input, hag(dem, ""), las(target));
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
        assertFalse(Files.exists(target));
        assertNoPartFiles();
    }

    private static int[][] coordinates() {
        // x/y are quarter metres from the origin and z is quarter metres above 700.
        return new int[][]{{0, 8, 21, 2}, {3, 5, 0, 3}, {4, 4, 24, 3}, {5, 1, 4, 3}, {4, 5, 76, 5}};
    }

    private Path points(int epsg, int[][] coordinates) throws Exception {
        return points(CRS.decode("EPSG:" + epsg, true).toWKT(), coordinates);
    }

    private Path points(String crsWkt, int[][] coordinates) throws IOException {
        var las = LasIoTest.bytes(LasIoTest.fixture(8, "las"));
        las.putDouble(131, .25).putDouble(139, .25).putDouble(147, .25);
        las.putDouble(155, X).putDouble(163, Y).putDouble(171, 700);
        int start = las.getInt(96);
        int stride = las.getChar(105);
        for (int i = 0; i < coordinates.length; i++) {
            int offset = start + i * stride;
            var point = coordinates[i];
            las.putInt(offset, point[0]).putInt(offset + 4, point[1]).putInt(offset + 8, point[2]);
            las.put(offset + 16, (byte) point[3]);
        }
        byte[] wkt = (crsWkt + "\0").getBytes(StandardCharsets.UTF_8);
        var projection = ByteBuffer.allocate(60 + wkt.length).order(ByteOrder.LITTLE_ENDIAN);
        projection.position(2).put("LASF_Projection".getBytes(StandardCharsets.US_ASCII));
        projection.putChar(18, (char) 2112).putLong(20, wkt.length).position(60).put(wkt);
        las.putInt(243, las.getInt(243) + 1); // The template already has an EVLR.
        var result = ByteBuffer.allocate(las.capacity() + projection.capacity());
        result.put(las.array()).put(projection.array());
        return Files.write(Files.createTempFile(temp, "points-", ".las"), result.array());
    }

    private Path dem(Path input, double nodata, double[]... bands) throws IOException {
        Path output = Files.createTempFile(temp, "dem-", ".tif");
        try (var source = Pipeline.openSequential(input, 2);
             var writer = new GeoTiffOutput(input, output, true, source.header(), source.channel())) {
            var names = bands.length == 1 ? List.of("elevation") : List.of("elevation", "other");
            writer.prepare(X, Y, 1, 2, 2, bands, names, nodata, true);
            writer.finish();
        }
        return output;
    }

    private Path verticalDem(int verticalCrs) throws IOException {
        Path output = temp.resolve("dem-" + verticalCrs + ".tif");
        var image = new BufferedImage(2, 2, BufferedImage.TYPE_USHORT_GRAY);
        image.getRaster().setSamples(0, 0, 2, 2, 0, new int[]{701, 710, 702, 703});
        var encoder = new GeoTiffIIOMetadataEncoder();
        encoder.addGeoShortParam(1024, 1); // Projected.
        encoder.addGeoShortParam(1025, 1); // PixelIsArea.
        encoder.addGeoShortParam(3072, 2056);
        encoder.addGeoShortParam(4096, verticalCrs);
        encoder.setModelPixelScale(1, 1);
        encoder.setModelTiePoint(0, 0, X, Y + 2);
        var writer = new TIFFImageWriterSpi().createWriterInstance();
        try (var stream = new FileImageOutputStream(output.toFile())) {
            writer.setOutput(stream);
            var params = writer.getDefaultWriteParam();
            var metadata = GeoTiffWriter.createGeoTiffIIOMetadata(
                    writer, ImageTypeSpecifier.createFromRenderedImage(image), encoder, params);
            writer.write(null, new IIOImage(image, null, metadata), params);
        } finally {
            writer.dispose();
        }
        return output;
    }

    private static void assertStats(Pipeline.Statistics actual, String name, long count,
                                    double min, double max, double average) {
        assertEquals(name, actual.name());
        assertEquals(count, actual.count());
        assertEquals(min, actual.minimum());
        assertEquals(max, actual.maximum());
        assertEquals(average, actual.average(), 1e-12);
    }

    private static void assertOriginalRecords(Path source, Path target, int... selected) throws IOException {
        var before = LasIoTest.bytes(source);
        var after = LasIoTest.bytes(target);
        int sourceStart = before.getInt(96);
        int targetStart = after.getInt(96);
        int stride = before.getChar(105);
        assertEquals(stride, after.getChar(105));
        assertEquals(selected.length, after.getLong(247));
        assertArrayEquals(Arrays.copyOfRange(before.array(), before.getChar(94), sourceStart),
                Arrays.copyOfRange(after.array(), after.getChar(94), targetStart));
        assertArrayEquals(Arrays.copyOfRange(before.array(), Math.toIntExact(before.getLong(235)), before.capacity()),
                Arrays.copyOfRange(after.array(), Math.toIntExact(after.getLong(235)), after.capacity()));
        for (int i = 0; i < selected.length; i++) {
            int from = sourceStart + selected[i] * stride;
            int to = targetStart + i * stride;
            assertArrayEquals(Arrays.copyOfRange(before.array(), from, from + stride),
                    Arrays.copyOfRange(after.array(), to, to + stride));
        }
    }

    private static void assertCliFailure(Path config, Path metadata, String message) {
        var stdout = new ByteArrayOutputStream();
        var stderr = new ByteArrayOutputStream();
        assertEquals(2, Main.run(new String[]{"pipeline", config.toString(), "--metadata", metadata.toString(), "--overwrite"},
                new PrintStream(stdout), new PrintStream(stderr)), stderr::toString);
        assertTrue(stderr.toString().contains(message), stderr::toString);
        assertEquals("", stdout.toString());
    }

    private void assertNoPartFiles() throws IOException {
        try (var files = Files.list(temp)) {
            assertTrue(files.noneMatch(path -> path.getFileName().toString().endsWith(".part")));
        }
    }

    private static String hag(Path dem, String options) {
        return """
                {"type":"filters.hag_dem","raster":%s%s}
                """.formatted(Pipeline.JSON.toJson(dem.toString()), options);
    }

    private static String las(Path target) {
        return """
                {"type":"writers.las","filename":%s,"forward":"all","extra_dims":"all"}
                """.formatted(Pipeline.JSON.toJson(target.toString()));
    }

    private static String config(Path input, String... stages) {
        return "[" + Pipeline.JSON.toJson(input.toString()) + "," + String.join(",", stages) + "]";
    }

    private static Pipeline pipeline(Path input, String... stages) {
        return Pipeline.parse(new StringReader(config(input, stages)));
    }
}
