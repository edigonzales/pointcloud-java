package pointcloud;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.io.WKTReader;
import org.locationtech.jts.io.WKTWriter;

import java.io.IOException;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static pointcloud.VegetationOutlines.Mode.ALL;
import static pointcloud.VegetationOutlines.Mode.NEIGHBORS;

class VegetationOutlinesTest {
    private static final GeometryFactory GEOMETRY = new GeometryFactory();
    private static final Envelope CORE = new Envelope(0, 20, 0, 20);

    @Test
    void countsTwoDistinctNeighborsIncludingTheClosedRadius() throws Exception {
        var candidates = List.of(point(1, 1), point(2, 1), point(1, 2), point(2, 1),
                point(8, 8), point(8.5, 8), point(8.5, 8), point(15, 15));

        var selected = VegetationOutlines.select(candidates, CORE, NEIGHBORS);

        assertArrayEquals(new Coordinate[]{point(1, 1)}, selected.points());
        assertEquals(8, selected.selected());
        assertEquals(1, selected.kept());
    }

    @Test
    void usesTheExtraMetreForNeighborsWhileRetainingOnlyTheGeometryHalo() throws Exception {
        var core = new Envelope(0, 2, 0, 2);
        var candidates = List.of(point(11.75, .5), point(12.5, .5), point(12.5, 0), point(13, .5));
        var selected = VegetationOutlines.select(candidates, core, NEIGHBORS);
        assertArrayEquals(new Coordinate[]{point(11.75, .5)}, selected.points());
        assertEquals(3, selected.selected());
        assertEquals(3, selected.kept());
        assertEquals(1, selected.haloKept());

        var edgePoints = List.of(point(-10, 0), point(12, 0));
        assertArrayEquals(new Coordinate[]{point(-10, 0)},
                VegetationOutlines.select(edgePoints, core, ALL).points());
    }

    @Test
    void thinsByCellCentreThenLexicalXyRegardlessOfInputOrder() throws Exception {
        var candidates = new ArrayList<>(List.of(point(.1875, .125), point(.0625, .125),
                point(.25, .0), point(.375, .125), point(-.125, .125),
                point(.125, .4375), point(.125, .3125)));

        var selected = VegetationOutlines.select(candidates, CORE, ALL);
        assertEquals(4, selected.points().length);
        var positions = List.of(selected.points());
        assertTrue(positions.containsAll(List.of(point(.0625, .125), point(.375, .125),
                point(-.125, .125), point(.125, .3125))));

        Collections.reverse(candidates);
        assertArrayEquals(selected.points(), VegetationOutlines.select(candidates, CORE, ALL).points());
    }

    @ParameterizedTest
    @ValueSource(doubles = {1.4, 1.5, 1.6})
    void closesOnlySubThresholdCorridorsAndOpenNotches(double gap) throws Exception {
        var west = rectangle(0, 0, 10, 20);
        for (double offsetY : new double[]{0, 8}) {
            var east = rectangle(10 + gap, offsetY, 20 + gap, 20 + offsetY);
            var cover = GEOMETRY.createMultiPolygon(new Polygon[]{west, east});
            var closed = VegetationOutlines.closeOpenings(cover);
            assertTrue(closed.isValid());
            assertEquals(gap < 1.5, covers(closed, 10 + gap / 2, 14), "offset " + offsetY);
        }

        double east = 4 + gap;
        var notch = new WKTReader(GEOMETRY).read("POLYGON ((0 0, 12 0, 12 8, " + east + " 8, "
                + east + " 2, 4 2, 4 8, 0 8, 0 0))");
        var closed = VegetationOutlines.closeOpenings(notch);
        assertTrue(closed.isValid());
        assertEquals(gap < 1.5, covers(closed, 4 + gap / 2, 5));
    }

    @Test
    void closesAnExteriorGapWhilePreservingAnIslandInsideAHole() {
        var shell = rectangle(0, 0, 40, 40);
        var forest = shell.difference(rectangle(10, 10, 26, 26))
                .union(rectangle(14, 14, 22, 22))
                .union(rectangle(41.4, 8, 51.4, 28));

        var closed = VegetationOutlines.closeOpenings(forest);

        assertTrue(closed.isValid());
        assertTrue(covers(closed, 40.7, 18));
        assertTrue(covers(closed, 18, 18));
        assertFalse(covers(closed, 11, 18));
        assertEquals(forest.intersection(shell).getArea(), closed.intersection(shell).getArea(), 1e-9);
    }

    @Test
    void fillsOnlyHolesThatDisappearWhenBufferedInward() throws Exception {
        var forest = rectangle(0, 0, 60, 30).difference(rectangle(5, 5, 14, 22))
                .difference(rectangle(21, 5, 31, 22)).difference(rectangle(37, 5, 48, 22));

        var filled = VegetationOutlines.fillSmallHoles(forest);

        assertTrue(filled.isValid());
        assertTrue(covers(filled, 10, 13)); // 9 m across
        assertTrue(covers(filled, 26, 13)); // 10 m shrinks to a line
        assertFalse(covers(filled, 43, 13)); // 11 m retains area
        assertEquals(60 * 30 - 11 * 17, filled.getArea(), 1e-12);
    }

    @Test
    void checksTheActualHoleWhenASeparateStandSitsInsideIt() throws Exception {
        var forest = rectangle(0, 0, 40, 40).difference(rectangle(10, 10, 26, 26))
                .union(rectangle(14, 14, 22, 22));

        var filled = VegetationOutlines.fillSmallHoles(forest);

        assertTrue(filled.isValid());
        assertEquals(1600, filled.getArea(), 1e-12);
        assertEquals(1, filled.getNumGeometries());
    }

    @Test
    void keepsNarrowOpenGroundThatIsConnectedToTheExterior() throws Exception {
        var forest = rectangle(0, 0, 30, 30).difference(rectangle(10, 10, 17, 30));

        var result = VegetationOutlines.fillSmallHoles(forest);

        assertTrue(result.isValid());
        assertTrue(forest.equalsTopo(result));
        assertFalse(covers(result, 13, 20));
    }

    @ParameterizedTest
    @ValueSource(doubles = {20, 40})
    void dropsOnlyComponentsStrictlyBelowTheAreaThreshold(double area) throws Exception {
        var components = GEOMETRY.createMultiPolygon(new Polygon[]{
                rectangle(0, 0, area - 0.25, 1), rectangle(0, 3, area, 4), rectangle(0, 6, area + 0.25, 7)
        });

        var kept = VegetationOutlines.minimumArea(components, area);

        assertTrue(kept.isValid());
        assertEquals(2, kept.getNumGeometries());
        assertFalse(covers(kept, .5, .5));
        assertTrue(covers(kept, .5, 3.5));
        assertEquals(2 * area + 0.25, kept.getArea(), 1e-12);
    }

    @Test
    void mergesDuplicateOverlapsBeforeFinalOffsetsAndClipsTheResult() throws Exception {
        var points = new Coordinate[25 * 25];
        for (int i = 0; i < points.length; i++) {
            points[i] = point(.5 * (i % 25), .5 * (i / 25));
        }
        var partial = VegetationOutlines.trace(points);
        var clip = rectangle(2, 2, 10, 10);
        var once = VegetationOutlines.finish(List.of(partial), clip);
        var twice = VegetationOutlines.finish(List.of(partial, partial.copy()), clip);
        var wkt = new WKTWriter();

        assertTrue(once.isValid());
        assertFalse(once.isEmpty());
        assertTrue(clip.covers(once));
        assertTrue(partial.getArea() > once.getArea());
        assertEquals(wkt.write(once.norm()), wkt.write(twice.norm()));
    }

    @Test
    void representsAnUnstockedCoreAsAValidEmptyGeometry() throws Exception {
        var partial = VegetationOutlines.trace(new Coordinate[0]);
        var result = VegetationOutlines.finish(List.of(partial), rectangle(0, 0, 10, 10));

        assertTrue(result.isValid());
        assertTrue(result.isEmpty());
    }

    @Test
    void tileReadFailureLeavesTheOutputUnpublished(@TempDir Path temp) throws Exception {
        Path valid = writeHeightFixture(temp.resolve("vegetation.las"));
        Path unreadable = Files.writeString(temp.resolve("truncated.las"), "LASF");
        Path output = temp.resolve("output");

        assertThrows(IOException.class, () -> VegetationOutlines.main(new String[]{
                "all", output.toString(), "2610200", "1232400", "2610205", "1232405",
                "2610200", "1232400", "2610202", "1232405", valid.toString(),
                "2610202", "1232400", "2610205", "1232405", unreadable.toString()
        }));
        assertFalse(Files.exists(output));
        try (var temporary = Files.list(temp)) {
            assertFalse(temporary.anyMatch(path -> path.getFileName().toString().startsWith(".vegetation-")));
        }
    }

    @Test
    void preparedLasRetainsTheTwoMetreEchoAndExcludesOtherClassesAndFlags(@TempDir Path temp) throws Exception {
        Path input = writeHeightFixture(temp.resolve("vegetation.las"));
        Path terrain = temp.resolve("terrain.tif");
        try (var reader = new LasReader(input, 2);
             var output = new GeoTiffOutput(input, terrain, false, reader.header(), reader.source)) {
            output.prepare(2610200, 1232400, 1, 1, 1, new double[][]{{700}}, List.of("elevation"),
                    -9999, false);
            output.finish();
        }
        Path candidate = temp.resolve("candidates.las");
        var pipeline = Pipeline.parse(new StringReader("""
                [
                  {"type":"readers.las","filename":"%s"},
                  {"type":"filters.hag_dem","raster":"%s"},
                  {"type":"filters.expression","expression":"Classification == 3 && Withheld == 0 && HeightAboveGround >= 2.0"},
                  {"type":"writers.las","filename":"%s","forward":"all","extra_dims":"all"}
                ]
                """.formatted(jsonPath(input), jsonPath(terrain), jsonPath(candidate))));

        var result = pipeline.run(2, false);

        assertEquals(5, result.pointsRead());
        assertEquals(2, result.pointsWritten());
        try (var reader = new LasReader(candidate, 2)) {
            var points = reader.readBatch();
            assertEquals(2, points.size());
            assertEquals(8, points.rawZ(0));
            assertEquals(9, points.rawZ(1));
            assertEquals(point(2610200.5, 1232400.25), point(points.x(0), points.y(0)));
        }
    }

    private static Polygon rectangle(double west, double south, double east, double north) {
        return (Polygon) GEOMETRY.toGeometry(new Envelope(west, east, south, north));
    }

    private static Coordinate point(double x, double y) {
        return new Coordinate(x, y);
    }

    private static boolean covers(Geometry geometry, double x, double y) {
        return geometry.covers(GEOMETRY.createPoint(point(x, y)));
    }

    private static String jsonPath(Path path) {
        return path.toString().replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static Path writeHeightFixture(Path path) throws IOException {
        int[][] records = {{1, 1, 7, 3}, {2, 1, 8, 3}, {3, 1, 9, 3}, {2, 2, 9, 0x83}, {2, 3, 9, 6}};
        int headerBytes = 227;
        int recordBytes = 28;
        int[] geoKeys = {1, 1, 0, 2, 1024, 0, 1, 1, 3072, 0, 1, 2056};
        int geoKeyBytes = geoKeys.length * 2;
        int pointOffset = headerBytes + 54 + geoKeyBytes;
        var las = ByteBuffer.allocate(pointOffset + records.length * recordBytes).order(ByteOrder.LITTLE_ENDIAN);
        las.put("LASF".getBytes(StandardCharsets.US_ASCII));
        las.put(24, (byte) 1).put(25, (byte) 2).putChar(94, (char) headerBytes);
        las.putInt(96, pointOffset).putInt(100, 1).put(104, (byte) 1).putChar(105, (char) recordBytes);
        las.putInt(107, records.length).putInt(111, records.length);
        las.putDouble(131, .25).putDouble(139, .25).putDouble(147, .25);
        las.putDouble(155, 2610200).putDouble(163, 1232400).putDouble(171, 700);
        las.putDouble(179, 2610200.75).putDouble(187, 2610200.25);
        las.putDouble(195, 1232400.75).putDouble(203, 1232400.25);
        las.putDouble(211, 702.25).putDouble(219, 701.75);
        las.position(headerBytes + 2).put("LASF_Projection".getBytes(StandardCharsets.US_ASCII));
        las.putChar(headerBytes + 18, (char) 34735).putChar(headerBytes + 20, (char) geoKeyBytes);
        las.position(headerBytes + 54);
        for (int key : geoKeys) {
            las.putChar((char) key);
        }
        for (int index = 0; index < records.length; index++) {
            var record = records[index];
            int offset = pointOffset + index * recordBytes;
            las.putInt(offset, record[0]).putInt(offset + 4, record[1]).putInt(offset + 8, record[2]);
            las.put(offset + 14, (byte) 9).put(offset + 15, (byte) record[3]); // Return 1 of 1.
        }
        return Files.write(path, las.array());
    }
}
