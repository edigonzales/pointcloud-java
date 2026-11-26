package pointcloud;

import it.geosolutions.imageioimpl.plugins.tiff.TIFFImageWriterSpi;
import org.geotools.coverage.grid.io.imageio.geotiff.GeoTiffIIOMetadataEncoder;
import org.geotools.gce.geotiff.GeoTiffWriter;
import org.geotools.referencing.CRS;
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
    private static final String HAG_DOUBLE = "HeightAboveGround=double";
    private static final int DESCRIPTOR_BYTES = 192;
    // WKT1 emitted by PDAL/GDAL for a_srs=EPSG:2056.
    private static final String PDAL_LV95 = """
            PROJCS["CH1903+ / LV95",
            GEOGCS["CH1903+",
              DATUM["CH1903+",SPHEROID["Bessel 1841",6377397.155,299.1528128,AUTHORITY["EPSG","7004"]],
                AUTHORITY["EPSG","6150"]],
              PRIMEM["Greenwich",0,AUTHORITY["EPSG","8901"]],
              UNIT["degree",0.0174532925199433,AUTHORITY["EPSG","9122"]],AUTHORITY["EPSG","4150"]],
            PROJECTION["Hotine_Oblique_Mercator_Azimuth_Center"],
            PARAMETER["latitude_of_center",46.9524055555556],
            PARAMETER["longitude_of_center",7.43958333333333],
            PARAMETER["azimuth",90],
            PARAMETER["rectified_grid_angle",90],
            PARAMETER["scale_factor",1],
            PARAMETER["false_easting",2600000],
            PARAMETER["false_northing",1200000],
            UNIT["metre",1,AUTHORITY["EPSG","9001"]],
            AXIS["Easting",EAST],AXIS["Northing",NORTH],AUTHORITY["EPSG","2056"]]
            """;
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
    void appendsHagAfterExistingAttributesAndRetainsVlrAndEvlrContents() throws Exception {
        Path input = appendOpaqueTail(points(2056, coordinates()), 256);
        Path dem = dem(input, NODATA, new double[]{701, 710, 702, 703});
        Path target = temp.resolve("hag.las");
        var result = pipeline(input, hag(dem, ""), """
                {"type":"filters.expression","expression":"HeightAboveGround > 2 || HeightAboveGround == -1"}
                """, las(target, HAG_DOUBLE)).run(2, false);
        assertEquals(3, result.pointsWritten());

        var before = LasIoTest.bytes(input);
        var after = LasIoTest.bytes(target);
        assertEquals(4, after.get(25));
        assertEquals(8, after.get(104));
        assertEquals(before.getInt(100), after.getInt(100));
        assertEquals(before.getInt(96) + 3 * DESCRIPTOR_BYTES, after.getInt(96));
        assertEquals(3, after.getLong(247));
        assertEquals(before.getInt(243), after.getInt(243));
        assertEquals(after.getInt(96) + 3L * after.getChar(105), after.getLong(235));
        assertForwardedEvlrs(before, after);
        int firstVlr = before.getChar(94);
        int firstVlrEnd = firstVlr + 54 + before.getChar(firstVlr + 20);
        assertArrayEquals(Arrays.copyOfRange(before.array(), firstVlr, firstVlrEnd),
                Arrays.copyOfRange(after.array(), firstVlr, firstVlrEnd));

        var descriptors = extraBytes(after);
        assertEquals(4 * DESCRIPTOR_BYTES, descriptors.capacity());
        var oldDescriptor = extraBytes(before);
        assertArrayEquals(oldDescriptor.array(), Arrays.copyOf(descriptors.array(), oldDescriptor.capacity()));
        assertDescriptor(descriptors, 1, 0, 255, null);
        assertDescriptor(descriptors, 2, 0, 1, null);
        assertDescriptor(descriptors, 3, 10, 0, "HeightAboveGround");
        assertHagRecords(before, after, new double[]{-1, 3, 9}, 1, 2, 4);

        Path copy = temp.resolve("forwarded.las");
        assertEquals(3, pipeline(target, las(copy)).run(1, false).pointsWritten());
        assertEquals(-1, Files.mismatch(target, copy));
    }

    @Test
    void createsALegacyDescriptorVlrWithTheFloat64AliasAndPreservesZeroPointLayout() throws Exception {
        Path input = appendOpaqueTail(legacyPoints(), 1);
        Path dem = dem(input, NODATA, new double[]{701, 710, 702, 703});
        String alias = "HeightAboveGround=float64";
        Path target = temp.resolve("legacy-hag.las");
        assertEquals(5, pipeline(input, hag(dem, ""), las(target, alias)).run(2, false).pointsWritten());
        var before = LasIoTest.bytes(input);
        var after = LasIoTest.bytes(target);
        assertEquals(2, after.get(25));
        assertEquals(3, after.get(104));
        assertEquals(5, after.getInt(107));
        assertEquals(before.getInt(100) + 1, after.getInt(100));
        assertEquals(before.getInt(96) + 54 + 2 * DESCRIPTOR_BYTES, after.getInt(96));
        var descriptors = extraBytes(after);
        assertEquals(2 * DESCRIPTOR_BYTES, descriptors.capacity());
        assertDescriptor(descriptors, 0, 0, 1, null);
        assertDescriptor(descriptors, 1, 10, 0, "HeightAboveGround");
        assertHagRecords(before, after, new double[]{0, -1, 3, -2, 9}, 0, 1, 2, 3, 4);

        Path empty = temp.resolve("empty-hag.las");
        assertEquals(0, pipeline(input, hag(dem, ""), """
                {"type":"filters.expression","expression":"HeightAboveGround > 100"}
                """, las(empty, alias)).run(2, false).pointsWritten());
        var writtenEmpty = LasIoTest.bytes(empty);
        assertEquals(0, writtenEmpty.getInt(107));
        assertEquals(after.getChar(105), writtenEmpty.getChar(105));
        assertEquals(writtenEmpty.getInt(96), writtenEmpty.capacity());
        assertArrayEquals(descriptors.array(), extraBytes(writtenEmpty).array());
    }

    @ParameterizedTest
    @ValueSource(strings = {"bad-payload", "duplicate-vlr", "extra-bytes-evlr", "existing-hag", "over-described",
            "reserved-type", "zero-width", "record-overflow", "vlr-overflow"})
    void rejectsInvalidExtraBytesLayoutsWithoutReplacingExistingFiles(String layout) throws Exception {
        Path input = legacyPoints();
        String failure = switch (layout) {
            case "bad-payload" -> {
                input = addExtraBytesVlr(input, new byte[]{1});
                yield "Extra Bytes VLR payload length";
            }
            case "duplicate-vlr" -> {
                input = appendOpaqueTail(input, 1);
                input = addExtraBytesVlr(input, descriptor(1, "first"));
                input = addExtraBytesVlr(input, descriptor(1, "second"));
                yield "Multiple LAS Extra Bytes VLRs";
            }
            case "extra-bytes-evlr" -> {
                var las = fixtureWithCoordinates(7, coordinates());
                byte[] wkt = projectionWkt(CRS.decode("EPSG:2056", true).toWKT());
                input = addEvlr(las, "LASF_Projection", 2112, wkt);
                input = appendOpaqueTail(input, 1);
                input = addEvlr(LasIoTest.bytes(input), "LASF_Spec", 4, descriptor(1, "Other"));
                yield "Extra Bytes in an EVLR";
            }
            case "existing-hag" -> {
                input = appendOpaqueTail(input, Double.BYTES);
                input = addExtraBytesVlr(input, descriptor(10, "HeightAboveGround"));
                yield "already contain HeightAboveGround";
            }
            case "over-described" -> {
                input = addExtraBytesVlr(input, descriptor(10, "Other"));
                yield "descriptors exceed the point record";
            }
            case "reserved-type" -> {
                input = appendOpaqueTail(input, 1);
                input = addExtraBytesVlr(input, descriptor(31, "Reserved"));
                yield "descriptor type: 31";
            }
            case "zero-width" -> {
                input = addExtraBytesVlr(input, descriptor(0, "Empty"));
                yield "descriptor has zero width";
            }
            case "record-overflow" -> {
                input = appendOpaqueTail(input, 65530 - 34);
                yield "point record length exceeds unsigned 16-bit range";
            }
            case "vlr-overflow" -> {
                int descriptors = 65535 / DESCRIPTOR_BYTES;
                input = appendOpaqueTail(input, descriptors);
                var payload = ByteBuffer.allocate(descriptors * DESCRIPTOR_BYTES);
                for (int i = 0; i < descriptors; i++) {
                    payload.put(descriptor(1, "Extra" + i));
                }
                input = addExtraBytesVlr(input, payload.array());
                yield "VLR payload exceeds unsigned 16-bit range";
            }
            default -> throw new AssertionError(layout);
        };
        Path dem = dem(input, NODATA, new double[]{701, 710, 702, 703});
        Path target = Files.writeString(temp.resolve("existing.las"), "original point output");
        Path metadata = Files.writeString(temp.resolve("existing.json"), "original metadata");
        Path config = Files.writeString(temp.resolve("pipeline.json"),
                config(input, hag(dem, ""), las(target, HAG_DOUBLE)));
        assertCliFailure(config, metadata, failure);
        assertEquals("original point output", Files.readString(target));
        assertEquals("original metadata", Files.readString(metadata));
        assertNoPartFiles();
    }

    @Test
    void rejectsNewHagExtraFieldWithoutHagAndOtherExtraDimensions() {
        Path input = LasIoTest.fixture(3, "las");
        Path target = temp.resolve("undeclared-hag.las");
        assertThrows(IllegalArgumentException.class, () -> pipeline(input, las(target, HAG_DOUBLE)).run(1, false));
        assertThrows(IllegalArgumentException.class, () -> pipeline(input, las(target, "HeightAboveGround=float32")));
        assertFalse(Files.exists(target));
    }

    @Test
    void missingDemValueAfterWrittenRecordsDoesNotPublishAppendedHag() throws Exception {
        Path input = points(2056, coordinates());
        Path dem = dem(input, NODATA, new double[]{701, NODATA, 702, 703});
        Path target = Files.writeString(temp.resolve("previous.las"), "previous points");
        Path metadata = Files.writeString(temp.resolve("previous.json"), "previous metadata");
        Path config = Files.writeString(temp.resolve("pipeline.json"),
                config(input, hag(dem, ""), las(target, HAG_DOUBLE)));
        assertCliFailure(config, metadata, "Stage 2 (filters.hag_dem)");
        assertEquals("previous points", Files.readString(target));
        assertEquals("previous metadata", Files.readString(metadata));
        assertNoPartFiles();
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

    @ParameterizedTest
    @ValueSource(strings = {"pdal", "canonical-datum-shift", "eight-ulps"})
    void acceptsEquivalentPdalLv95WktAndWritesComputedHeight(String variant) throws Exception {
        String wkt = switch (variant) {
            case "pdal" -> PDAL_LV95;
            case "canonical-datum-shift" -> withTowgs84("674.374,15.056,405.346,0,0,0,0");
            case "eight-ulps" -> longitudeAtUlps(latitudeAtUlps(PDAL_LV95, 8), -8);
            default -> throw new AssertionError(variant);
        };
        var las = fixtureWithCoordinates(8, coordinates());
        Path input = addVlr(las, "LASF_Projection", 2112, projectionWkt(wkt));
        Path dem = dem(legacyPoints(), NODATA, new double[]{701, 710, 702, 703});
        Path output = temp.resolve("pdal-lv95-hag.las");
        var result = pipeline(input, hag(dem, ""), STATISTICS, las(output, HAG_DOUBLE)).run(2, false);
        assertEquals(5, result.pointsWritten());
        assertStats(result.statistics().getFirst().dimensions().getFirst(), "HeightAboveGround", 5, -2, 9, 1.8);
        assertHagRecords(LasIoTest.bytes(input), LasIoTest.bytes(output), new double[]{0, -1, 3, -2, 9},
                0, 1, 2, 3, 4);
    }

    @ParameterizedTest
    @ValueSource(strings = {"false-easting", "latitude-nine-ulps", "longitude-nine-ulps", "axis-order",
            "axis-direction", "unit", "method", "base-id", "datum-id", "datum-name", "datum-shift"})
    void rejectsChangedPdalLv95WktDespiteTheSameProjectedEpsgCode(String change) throws Exception {
        String changed = switch (change) {
            case "false-easting" -> PDAL_LV95.replace("2600000]", "2600001]");
            case "latitude-nine-ulps" -> latitudeAtUlps(PDAL_LV95, 9);
            case "longitude-nine-ulps" -> longitudeAtUlps(PDAL_LV95, -9);
            case "axis-order" -> PDAL_LV95.replace(
                    "AXIS[\"Easting\",EAST],AXIS[\"Northing\",NORTH]", "AXIS[\"Northing\",NORTH],AXIS[\"Easting\",EAST]");
            case "axis-direction" -> PDAL_LV95.replace("AXIS[\"Easting\",EAST]", "AXIS[\"Westing\",WEST]");
            case "unit" -> PDAL_LV95.replace("\"metre\",1,AUTHORITY[\"EPSG\",\"9001\"]",
                    "\"foot\",0.3048,AUTHORITY[\"EPSG\",\"9002\"]");
            case "method" -> PDAL_LV95.replace("Hotine_Oblique_Mercator_Azimuth_Center", "Hotine_Oblique_Mercator");
            case "base-id" -> PDAL_LV95.replace("\"4150\"", "\"4149\"");
            case "datum-id" -> PDAL_LV95.replace("\"6150\"", "\"6149\"");
            case "datum-name" -> PDAL_LV95.replace("DATUM[\"CH1903+\"", "DATUM[\"CH1903\"");
            case "datum-shift" -> withTowgs84("100,200,300,0,0,0,0");
            default -> throw new AssertionError(change);
        };
        assertNotEquals(PDAL_LV95, changed);
        Path input = points(changed, coordinates());
        Path dem = dem(legacyPoints(), NODATA, new double[]{701, 710, 702, 703});
        Path target = temp.resolve("crs-mismatch.las");
        var error = assertThrows(IOException.class, () -> pipeline(input, hag(dem, ""), las(target)).run(2, false));
        assertTrue(error.getMessage().contains("Stage 2 (filters.hag_dem)"), error::getMessage);
        assertTrue(error.getMessage().contains("horizontal CRS differs"), error::getMessage);
        assertFalse(Files.exists(target));
        assertNoPartFiles();
    }

    private static String withTowgs84(String parameters) {
        String datumAuthority = "AUTHORITY[\"EPSG\",\"6150\"]";
        return PDAL_LV95.replace(datumAuthority, "TOWGS84[" + parameters + "]," + datumAuthority);
    }

    private static String latitudeAtUlps(String wkt, int ulps) {
        double canonical = 46.952405555555565; // GeoTools EPSG:2056 projection centre.
        return wkt.replace("46.9524055555556", Double.toString(canonical + ulps * Math.ulp(canonical)));
    }

    private static String longitudeAtUlps(String wkt, int ulps) {
        double canonical = 7.439583333333333;
        return wkt.replace("7.43958333333333", Double.toString(canonical + ulps * Math.ulp(canonical)));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void rejectsDifferentDeclaredVerticalReferences(boolean pdalWkt) throws Exception {
        String horizontal = pdalWkt ? PDAL_LV95 : CRS.decode("EPSG:2056", true).toWKT();
        String vertical = CRS.decode("EPSG:5728", true).toWKT();
        Path input = points("""
                COMPD_CS["LV95 and LN02",%s,%s]
                """.formatted(horizontal, vertical), coordinates());
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

    @ParameterizedTest
    @ValueSource(strings = {"all", HAG_DOUBLE})
    void interruptionLeavesLasUnpublished(String extraDimensions) throws Exception {
        Path input = points(2056, coordinates());
        Path dem = dem(input, NODATA, new double[]{701, 710, 702, 703});
        Path target = temp.resolve("cancelled.las");
        var pipeline = pipeline(input, hag(dem, ""), las(target, extraDimensions));
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
        return addEvlr(fixtureWithCoordinates(8, coordinates), "LASF_Projection", 2112, projectionWkt(crsWkt));
    }

    private static byte[] projectionWkt(String crsWkt) {
        return (crsWkt + "\0").getBytes(StandardCharsets.UTF_8);
    }

    private Path addEvlr(ByteBuffer las, String user, int id, byte[] contents) throws IOException {
        var record = ByteBuffer.allocate(60 + contents.length).order(ByteOrder.LITTLE_ENDIAN);
        record.position(2).put(user.getBytes(StandardCharsets.US_ASCII));
        record.putChar(18, (char) id).putLong(20, contents.length).position(60).put(contents);
        if (las.getInt(243) == 0) {
            las.putLong(235, las.capacity());
        }
        las.putInt(243, las.getInt(243) + 1);
        var result = ByteBuffer.allocate(las.capacity() + record.capacity()).put(las.array()).put(record.array());
        return Files.write(Files.createTempFile(temp, "points-", ".las"), result.array());
    }

    private Path legacyPoints() throws IOException {
        var las = fixtureWithCoordinates(3, coordinates());
        var keys = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);
        for (int value : new int[]{1, 1, 0, 2, 1024, 0, 1, 1, 3072, 0, 1, 2056}) {
            keys.putChar((char) value);
        }
        return addVlr(las, "LASF_Projection", 34735, keys.array());
    }

    private static ByteBuffer fixtureWithCoordinates(int format, int[][] coordinates) throws IOException {
        var las = LasIoTest.bytes(LasIoTest.fixture(format, "las"));
        las.putDouble(131, .25).putDouble(139, .25).putDouble(147, .25);
        las.putDouble(155, X).putDouble(163, Y).putDouble(171, 700);
        int start = las.getInt(96);
        int stride = las.getChar(105);
        int classificationByte = format < 6 ? 15 : 16;
        for (int i = 0; i < coordinates.length; i++) {
            int offset = start + i * stride;
            var point = coordinates[i];
            las.putInt(offset, point[0]).putInt(offset + 4, point[1]).putInt(offset + 8, point[2]);
            int oldFlags = format < 6 ? las.get(offset + classificationByte) & 0xe0 : 0;
            las.put(offset + classificationByte, (byte) (oldFlags | point[3]));
        }
        return las;
    }

    private Path addExtraBytesVlr(Path input, byte[] contents) throws IOException {
        return addVlr(LasIoTest.bytes(input), "LASF_Spec", 4, contents);
    }

    private Path addVlr(ByteBuffer las, String user, int id, byte[] contents) throws IOException {
        var vlr = ByteBuffer.allocate(54 + contents.length).order(ByteOrder.LITTLE_ENDIAN);
        vlr.position(2).put(user.getBytes(StandardCharsets.US_ASCII));
        vlr.putChar(18, (char) id).putChar(20, (char) contents.length).position(54).put(contents);
        int start = las.getInt(96);
        las.putInt(96, start + vlr.capacity()).putInt(100, las.getInt(100) + 1);
        if (las.get(25) == 4 && las.getInt(243) != 0) {
            las.putLong(235, las.getLong(235) + vlr.capacity());
        }
        byte[] bytes = ByteBuffer.allocate(las.capacity() + vlr.capacity())
                .put(las.array(), 0, start).put(vlr.array())
                .put(las.array(), start, las.capacity() - start).array();
        return Files.write(Files.createTempFile(temp, "points-", ".las"), bytes);
    }

    private Path appendOpaqueTail(Path input, int size) throws IOException {
        var las = LasIoTest.bytes(input);
        int start = las.getInt(96);
        int stride = las.getChar(105);
        boolean modern = las.get(25) == 4;
        int pointCount = modern ? (int) las.getLong(247) : las.getInt(107);
        las.putChar(105, (char) (stride + size));
        if (modern && las.getInt(243) != 0) {
            las.putLong(235, las.getLong(235) + pointCount * size);
        }
        var bytes = ByteBuffer.allocate(las.capacity() + pointCount * size).put(las.array(), 0, start);
        for (int i = 0; i < pointCount; i++) {
            bytes.put(las.array(), start + i * stride, stride);
            for (int j = 0; j < size; j++) {
                bytes.put((byte) (i + j));
            }
        }
        int pointEnd = start + pointCount * stride;
        bytes.put(las.array(), pointEnd, las.capacity() - pointEnd);
        return Files.write(Files.createTempFile(temp, "opaque-", ".las"), bytes.array());
    }

    private static byte[] descriptor(int type, String name) {
        var descriptor = ByteBuffer.allocate(DESCRIPTOR_BYTES);
        descriptor.put(2, (byte) type).position(4).put(name.getBytes(StandardCharsets.US_ASCII));
        return descriptor.array();
    }

    private static ByteBuffer extraBytes(ByteBuffer las) {
        int offset = las.getChar(94);
        ByteBuffer descriptors = null;
        for (int i = 0; i < las.getInt(100); i++) {
            int length = las.getChar(offset + 20);
            if (las.getChar(offset + 18) == 4 && "LASF_Spec".equals(ascii(las, offset + 2, 16))) {
                assertNull(descriptors, "duplicate Extra Bytes VLR");
                descriptors = ByteBuffer.wrap(Arrays.copyOfRange(las.array(), offset + 54, offset + 54 + length));
            }
            offset += 54 + length;
        }
        assertNotNull(descriptors, "missing Extra Bytes VLR");
        return descriptors;
    }

    private static String ascii(ByteBuffer bytes, int start, int limit) {
        int length = 0;
        while (length < limit && bytes.get(start + length) != 0) {
            length++;
        }
        return new String(bytes.array(), start, length, StandardCharsets.US_ASCII);
    }

    private static void assertDescriptor(ByteBuffer descriptors, int index, int type, int options, String name) {
        int offset = index * DESCRIPTOR_BYTES;
        assertEquals(type, Byte.toUnsignedInt(descriptors.get(offset + 2)));
        assertEquals(options, Byte.toUnsignedInt(descriptors.get(offset + 3)));
        if (name != null) {
            assertEquals(name, ascii(descriptors, offset + 4, 32));
        }
    }

    private static void assertHagRecords(ByteBuffer before, ByteBuffer after, double[] heights, int... selected) {
        int sourceStart = before.getInt(96);
        int targetStart = after.getInt(96);
        int sourceStride = before.getChar(105);
        int targetStride = after.getChar(105);
        assertEquals(sourceStride + Double.BYTES, targetStride);
        for (int i = 0; i < selected.length; i++) {
            int from = sourceStart + selected[i] * sourceStride;
            int to = targetStart + i * targetStride;
            assertArrayEquals(Arrays.copyOfRange(before.array(), from, from + sourceStride),
                    Arrays.copyOfRange(after.array(), to, to + sourceStride));
            assertEquals(Double.doubleToRawLongBits(heights[i]), after.getLong(to + sourceStride));
        }
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
        assertForwardedEvlrs(before, after);
        for (int i = 0; i < selected.length; i++) {
            int from = sourceStart + selected[i] * stride;
            int to = targetStart + i * stride;
            assertArrayEquals(Arrays.copyOfRange(before.array(), from, from + stride),
                    Arrays.copyOfRange(after.array(), to, to + stride));
        }
    }

    private static void assertForwardedEvlrs(ByteBuffer before, ByteBuffer after) {
        assertArrayEquals(Arrays.copyOfRange(before.array(), Math.toIntExact(before.getLong(235)), before.capacity()),
                Arrays.copyOfRange(after.array(), Math.toIntExact(after.getLong(235)), after.capacity()));
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
        return las(target, "all");
    }

    private static String las(Path target, String extraDimensions) {
        return """
                {"type":"writers.las","filename":%s,"forward":"all","extra_dims":%s}
                """.formatted(Pipeline.JSON.toJson(target.toString()), Pipeline.JSON.toJson(extraDimensions));
    }

    private static String config(Path input, String... stages) {
        return "[" + Pipeline.JSON.toJson(input.toString()) + "," + String.join(",", stages) + "]";
    }

    private static Pipeline pipeline(Path input, String... stages) {
        return Pipeline.parse(new StringReader(config(input, stages)));
    }
}
