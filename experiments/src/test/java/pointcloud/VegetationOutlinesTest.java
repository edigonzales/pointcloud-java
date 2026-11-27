package pointcloud;

import com.google.gson.JsonObject;
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
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static pointcloud.VegetationOutlines.Mode.ALL;
import static pointcloud.VegetationOutlines.Mode.NEIGHBORS;
import static pointcloud.VegetationOutlines.Thinning.GRID;
import static pointcloud.VegetationOutlines.Thinning.NONE;
import static pointcloud.VegetationOutlines.packPosition;

class VegetationOutlinesTest {
    private static final GeometryFactory GEOMETRY = new GeometryFactory();
    private static final Envelope CORE = new Envelope(0, 20, 0, 20);

    @Test
    void countsTwoDistinctNeighborsIncludingTheClosedRadius() throws Exception {
        var candidates = List.of(point(1, 1), point(2, 1), point(1, 2), point(2, 1),
                point(8, 8), point(8.5, 8), point(8.5, 8), point(15, 15));

        var selected = VegetationOutlines.select(candidates, CORE, NEIGHBORS, GRID);

        assertArrayEquals(new Coordinate[]{point(1, 1)}, selected.points());
        assertEquals(8, selected.selected());
        assertEquals(1, selected.kept());
    }

    @Test
    void usesTheExtraMetreForNeighborsWhileRetainingOnlyTheGeometryHalo() throws Exception {
        var core = new Envelope(0, 2, 0, 2);
        var candidates = List.of(point(11.75, .5), point(12.5, .5), point(12.5, 0), point(13, .5));
        var selected = VegetationOutlines.select(candidates, core, NEIGHBORS, GRID);
        assertArrayEquals(new Coordinate[]{point(11.75, .5)}, selected.points());
        assertEquals(3, selected.selected());
        assertEquals(3, selected.kept());
        assertEquals(1, selected.haloKept());

        var edgePoints = List.of(point(-10, 0), point(12, 0));
        assertArrayEquals(new Coordinate[]{point(-10, 0)},
                VegetationOutlines.select(edgePoints, core, ALL, GRID).points());
    }

    @Test
    void thinsByCellCentreThenLexicalXyRegardlessOfInputOrder() throws Exception {
        var candidates = new ArrayList<>(List.of(point(.1875, .125), point(.0625, .125),
                point(.25, .0), point(.375, .125), point(-.125, .125),
                point(.125, .4375), point(.125, .3125)));

        var selected = VegetationOutlines.select(candidates, CORE, ALL, GRID);
        assertEquals(4, selected.points().length);
        var positions = List.of(selected.points());
        assertTrue(positions.containsAll(List.of(point(.0625, .125), point(.375, .125),
                point(-.125, .125), point(.125, .3125))));

        Collections.reverse(candidates);
        assertArrayEquals(selected.points(), VegetationOutlines.select(candidates, CORE, ALL, GRID).points());
    }

    @Test
    void noThinningKeepsEveryDistinctPositionIncludingCloseReturns() {
        var candidates = List.of(point(.25, .125), point(.24, .125), point(.24, .125));

        var selected = VegetationOutlines.select(candidates, CORE, ALL, NONE);

        assertEquals(3, selected.selected());
        assertArrayEquals(new Coordinate[]{point(.24, .125), point(.25, .125)}, selected.points());
    }

    @Test
    void spacingRejectsCloseReturnsAcrossBinsAndKeepsTheExactCutoff() {
        long origin = packPosition(24, 24);
        long closeAcrossBins = packPosition(25, 25);
        long diagonalCutoff = packPosition(39, 44); // 15² + 20² = 25² cm.
        long firstAxial = packPosition(49, 0);
        long axialCutoff = packPosition(74, 0);
        long[] points = {axialCutoff, closeAcrossBins, firstAxial, packPosition(73, 0), diagonalCutoff, origin};

        var selected = VegetationOutlines.spacingSelection(points, points.length);

        assertEquals(6, selected.unique());
        assertArrayEquals(new long[]{origin, diagonalCutoff, firstAxial, axialCutoff}, selected.positions());
    }

    @Test
    void spacingEliminatesDuplicatesAndRetainsTheSameXyForInputPermutations() {
        long first = packPosition(-1, -28);
        long second = packPosition(-1, -3);
        long third = packPosition(24, 10);
        var candidates = new ArrayList<>(List.of(first, second, third, first, packPosition(-1, -2),
                packPosition(8, -6), third));

        for (int seed = 0; seed < 8; seed++) {
            Collections.shuffle(candidates, new Random(seed));
            long[] packed = candidates.stream().mapToLong(Long::longValue).toArray();
            var selected = VegetationOutlines.spacingSelection(packed, packed.length);

            assertEquals(5, selected.unique());
            assertArrayEquals(new long[]{first, second, third}, selected.positions());
        }
    }

    @Test
    void overlappingTileHalosUseTheSameGloballySpacedPositionsAtTheSeam() {
        int seam = 2610000;
        int south = 1232500;
        long[] points = {packPosition(-1100, 500), packPosition(-2, 500), packPosition(0, 500),
                packPosition(23, 500), packPosition(48, 500), packPosition(1100, 500)};
        var selection = VegetationOutlines.spacingSelection(points, points.length);
        var west = VegetationOutlines.spacingPoints(selection, new Envelope(seam - 20, seam, south, south + 20),
                seam, south);
        var east = VegetationOutlines.spacingPoints(selection, new Envelope(seam, seam + 20, south, south + 20),
                seam, south);
        var joint = VegetationOutlines.spacingPoints(selection,
                new Envelope(seam - 20, seam + 20, south, south + 20), seam, south);
        var seamPoints = List.of(point(seam - .02, south + 5), point(seam + .23, south + 5),
                point(seam + .48, south + 5));

        assertEquals(List.of(point(seam - 11, south + 5), seamPoints.get(0), seamPoints.get(1),
                seamPoints.get(2)), List.of(west));
        assertEquals(List.of(seamPoints.get(0), seamPoints.get(1), seamPoints.get(2),
                point(seam + 11, south + 5)), List.of(east));
        assertArrayEquals(new Coordinate[]{west[0], west[1], west[2], west[3], east[3]}, joint);
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
    void alphaUsesTheClosedCircumradiusCutoff() {
        // A 3-4-5 right triangle scaled by 1/32. Its circumradius is exactly 5/64.
        var triangle = GEOMETRY.createPolygon(new Coordinate[]{
                point(0, 0), point(3.0 / 32, 0), point(0, 4.0 / 32), point(0, 0)});
        double alpha = 5.0 / 64;

        assertTrue(VegetationOutlines.alphaHull(triangle, Math.nextDown(alpha), false).isEmpty());
        assertTrue(VegetationOutlines.alphaHull(triangle, alpha, false).equalsTopo(triangle));
        assertTrue(VegetationOutlines.alphaHull(triangle, Math.nextDown(alpha), true).equalsTopo(triangle));
    }

    @ParameterizedTest
    @ValueSource(doubles = {1.49, 1.51})
    void alphaBridgesTheNarrowerGapAtPointSevenSixMetres(double gap) {
        var stock = GEOMETRY.createMultiPolygon(new Polygon[]{
                rectangle(0, 0, 10, 10), rectangle(10 + gap, 0, 20 + gap, 10)});
        double centre = 10 + gap / 2;

        assertFalse(covers(VegetationOutlines.alphaHull(stock, .75, true), centre, 5));
        var replaced = VegetationOutlines.alphaHull(stock, .76, false);
        var filled = VegetationOutlines.alphaHull(stock, .76, true);

        assertTrue(replaced.isValid());
        assertTrue(filled.isValid());
        assertEquals(gap < 1.5, covers(replaced, centre, 5));
        assertEquals(gap < 1.5, covers(filled, centre, 5));
        assertFalse(covers(replaced, 5, 5));
        assertTrue(filled.covers(stock));
    }

    @Test
    void alphaFillsAnInternalGapAndKeepsABroadClearingAroundAnIsland() {
        var clip = rectangle(0, 0, 64, 64);
        var clearing = rectangle(14, 14, 50, 50);
        var island = rectangle(24, 24, 40, 40);
        var narrowGap = rectangle(5, 16, 6.49, 28);
        var stock = clip.difference(clearing).difference(narrowGap).union(island);
        var hull = VegetationOutlines.alphaHull(stock, .76, true);

        assertTrue(hull.isValid());
        assertTrue(covers(hull, 5.75, 22));
        assertFalse(covers(hull, 18, 32));

        var partial = VegetationOutlines.fillSmallHoles(VegetationOutlines.backBuffer(hull, false));
        var outline = VegetationOutlines.finish(List.of(partial), clip);
        assertTrue(outline.isValid());
        var open = assertInstanceOf(Polygon.class, clearing.difference(outline));
        assertEquals(1, open.getNumInteriorRing());
        assertTrue(covers(open, 18, 32));
        assertTrue(covers(open, 46, 32));
        assertTrue(covers(outline, 32, 32));
    }

    @ParameterizedTest
    @ValueSource(doubles = {0.49, 0.51})
    void earlyClosingBridgesOnlyTheNarrowerInternalGap(double gap) {
        var stock = rectangle(0, 0, 30, 30).difference(rectangle(12, 5, 12 + gap, 25));
        var hull = VegetationOutlines.closeOpenings(stock);
        double gapCentre = 12 + gap / 2;

        assertFalse(covers(hull, gapCentre, 15));
        assertFalse(covers(VegetationOutlines.backBuffer(hull, false), gapCentre, 15));
        var closed = VegetationOutlines.backBuffer(hull, true);

        assertTrue(closed.isValid());
        assertEquals(gap < 0.5, covers(closed, gapCentre, 15));
    }

    @Test
    void earlyClosingPreservesBoundaryDetailThatDecidesTheFiveMetreTest() throws Exception {
        var hole = new WKTReader(GEOMETRY).read(
                "POLYGON ((0 0, 7.197 0, 7.201 6, 7.201 14, 7.197 20, 0 20, 0 0))");
        var stock = rectangle(-20, -20, 20, 30).difference(hole);
        var hull = VegetationOutlines.closeOpenings(stock);
        var backed = VegetationOutlines.backBuffer(hull, true);

        // Simplifying the input to the early buffers creates a false remnant at the cutoff.
        assertFalse(covers(backed, 3.6, 10));
        var filled = VegetationOutlines.fillSmallHoles(backed);
        assertEquals(0, assertInstanceOf(Polygon.class, filled).getNumInteriorRing());
        assertTrue(covers(filled, 3.6, 10));
    }

    @Test
    void earlyClosingKeepsTheOpenAreaAroundAVegetationIslandConnected() {
        var clip = rectangle(0, 0, 64, 64);
        var clearing = rectangle(14, 14, 50, 50);
        var island = rectangle(24, 24, 40, 40);
        var hull = VegetationOutlines.closeOpenings(clip.difference(clearing).union(island));
        var partial = VegetationOutlines.fillSmallHoles(VegetationOutlines.backBuffer(hull, true));
        var outline = VegetationOutlines.finish(List.of(partial), clip);

        assertTrue(outline.isValid());
        assertTrue(covers(outline, 5, 32));
        assertTrue(covers(outline, 32, 32));
        var open = assertInstanceOf(Polygon.class, clearing.difference(outline));
        assertEquals(1, open.getNumInteriorRing());
        assertTrue(covers(open, 18, 32));
        assertTrue(covers(open, 46, 32));
    }

    @Test
    void earlyClosingBridgesANarrowExtensionAcrossATileSeam(@TempDir Path temp) throws Exception {
        int seam = 2610200;
        int south = 1232400;
        double scale = 0.005;
        var records = new ArrayList<int[]>();
        for (int x = -40; x <= 40; x++) {
            double east = 0.5 * x + (x < 0 ? -0.125 : 0.125);
            for (int y = 0; y <= 80; y++) {
                double north = 0.5 * y + 0.125;
                double halfWidth = north < 23 ? 8 : 1.625;
                if (north < 8 || north > 33 || Math.abs(east) >= halfWidth) {
                    records.add(new int[]{(int) Math.round(east / scale), (int) Math.round(north / scale), 400, 3});
                }
            }
        }
        String las = writeLas(temp.resolve("seam.las"), scale, records.toArray(int[][]::new)).toString();
        Path baseline = temp.resolve("baseline");
        Path joint = temp.resolve("joint");
        Path split = temp.resolve("split");
        VegetationOutlines.main(new String[]{"all", baseline.toString(), "2610175", "1232400", "2610225", "1232445",
                "2610175", "1232400", "2610225", "1232445", las});
        VegetationOutlines.main(new String[]{"--early-close", "all", joint.toString(),
                "2610175", "1232400", "2610225", "1232445", "2610175", "1232400", "2610225", "1232445", las});
        VegetationOutlines.main(new String[]{"--early-close", "all", split.toString(),
                "2610175", "1232400", "2610225", "1232445", "2610175", "1232400", "2610200", "1232445", las,
                "2610200", "1232400", "2610225", "1232445", las});

        var metrics = Pipeline.JSON.fromJson(Files.readString(split.resolve("metrics.json")), JsonObject.class);
        var tiles = metrics.getAsJsonArray("tiles");
        assertEquals(2, tiles.size());
        int westCount = tiles.get(0).getAsJsonObject().get("thinned").getAsInt();
        int eastCount = tiles.get(1).getAsJsonObject().get("thinned").getAsInt();
        assertTrue(westCount < records.size() && eastCount < records.size());
        assertTrue(westCount + eastCount > records.size());
        assertEquals(-1, Files.mismatch(joint.resolve("outline.wkt"), split.resolve("outline.wkt")));
        var reader = new WKTReader(GEOMETRY);
        assertFalse(covers(reader.read(Files.readString(baseline.resolve("outline.wkt"))), seam, south + 29));
        var outline = reader.read(Files.readString(joint.resolve("outline.wkt")));
        assertTrue(outline.isValid());
        assertTrue(covers(outline, seam, south + 29));
        assertFalse(covers(outline, seam, south + 15));
    }

    @ParameterizedTest
    @ValueSource(strings = {"--alpha-replace=0.76", "--alpha-fill=0.76"})
    void alphaProducesByteIdenticalOutlinesAcrossATileSeam(String flag, @TempDir Path temp) throws Exception {
        var records = new ArrayList<int[]>();
        for (int x = -2000; x <= 2000; x += 50) {
            for (int y = 300; y <= 1600; y += 50) {
                records.add(new int[]{x, y, 210, 3});
            }
        }
        String las = writeLas(temp.resolve("alpha-seam.las"), .01, records.toArray(int[][]::new)).toString();
        Path joint = temp.resolve("joint");
        Path split = temp.resolve("split");
        VegetationOutlines.main(new String[]{flag, "--snapshots", "all", joint.toString(),
                "2610175", "1232400", "2610225", "1232420", "2610175", "1232400", "2610225", "1232420", las});
        VegetationOutlines.main(new String[]{flag, "--snapshots", "all", split.toString(),
                "2610175", "1232400", "2610225", "1232420", "2610175", "1232400", "2610200", "1232420", las,
                "2610200", "1232400", "2610225", "1232420", las});

        var metrics = Pipeline.JSON.fromJson(Files.readString(split.resolve("metrics.json")), JsonObject.class);
        var tiles = metrics.getAsJsonArray("tiles");
        assertEquals(2, tiles.size());
        int westCount = tiles.get(0).getAsJsonObject().get("thinned").getAsInt();
        int eastCount = tiles.get(1).getAsJsonObject().get("thinned").getAsInt();
        assertTrue(westCount < records.size() && eastCount < records.size());
        assertTrue(westCount + eastCount > records.size());
        for (String filename : List.of("alpha-hull.wkt", "alpha-back-buffer.wkt", "outline.wkt")) {
            assertEquals(-1, Files.mismatch(joint.resolve(filename), split.resolve(filename)), filename);
        }
        var reader = new WKTReader(GEOMETRY);
        var hull = reader.read(Files.readString(joint.resolve("alpha-hull.wkt")));
        assertTrue(hull.isValid());
        assertFalse(hull.isEmpty());
        if (flag.startsWith("--alpha-fill=")) {
            var outline = reader.read(Files.readString(joint.resolve("outline.wkt")));
            assertTrue(covers(outline, 2610200, 1232410));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"--alpha-fill=0", "--alpha-fill=-1", "--alpha-replace=NaN",
            "--alpha-fill=Infinity", "--alpha-fill=0.75 --alpha-replace=0.75",
            "--early-close --alpha-fill=0.75", "--alpha-replace=0.75 --early-close"})
    void rejectsInvalidOrConflictingAlphaOptions(String flags, @TempDir Path temp) throws Exception {
        Path input = writeHeightFixture(temp.resolve("vegetation.las"));
        Path output = temp.resolve("output");
        var args = new ArrayList<>(List.of(flags.split(" ")));
        args.addAll(List.of("all", output.toString(), "2610200", "1232400", "2610205", "1232405",
                "2610200", "1232400", "2610205", "1232405", input.toString()));

        assertThrows(IllegalArgumentException.class, () -> VegetationOutlines.main(args.toArray(String[]::new)));
        assertFalse(Files.exists(output));
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
    void snapshotsLeaveTheFinalOutlineByteIdenticalForEachTestedVariant(@TempDir Path temp) throws Exception {
        Path input = writeStandFixture(temp.resolve("stand.las"));
        Path defaultOutput = temp.resolve("default");
        Path gridSnapshots = temp.resolve("grid-snapshots");
        Path spacingOutput = temp.resolve("spacing");
        Path spacingSnapshots = temp.resolve("spacing-snapshots");
        Path earlyOutput = temp.resolve("early-closing");
        Path earlySnapshots = temp.resolve("early-closing-snapshots");

        traceStand(input, defaultOutput);
        traceStand(input, gridSnapshots, "--thinning=grid", "--snapshots");
        traceStand(input, spacingOutput, "--thinning=spacing");
        traceStand(input, spacingSnapshots, "--thinning=spacing", "--snapshots");
        traceStand(input, earlyOutput, "--early-close");
        traceStand(input, earlySnapshots, "--early-close", "--snapshots");

        for (var outputs : List.of(List.of(defaultOutput, gridSnapshots), List.of(spacingOutput, spacingSnapshots),
                List.of(earlyOutput, earlySnapshots))) {
            Path plain = outputs.getFirst();
            Path traced = outputs.getLast();
            assertEquals(-1, Files.mismatch(plain.resolve("outline.wkt"), traced.resolve("outline.wkt")));
            assertFalse(Files.exists(plain.resolve("before-erosion.wkt")));

            var reader = new WKTReader(GEOMETRY);
            assertFalse(reader.read(Files.readString(traced.resolve("outline.wkt"))).isEmpty());
            var before = reader.read(Files.readString(traced.resolve("before-erosion.wkt")));
            var eroded = reader.read(Files.readString(traced.resolve("after-erosion.wkt")));
            var dilated = reader.read(Files.readString(traced.resolve("after-dilation.wkt")));
            assertTrue(before.isValid());
            assertTrue(eroded.isValid());
            assertTrue(dilated.isValid());
            assertTrue(before.getArea() > dilated.getArea() && dilated.getArea() > eroded.getArea());
        }
        var metrics = Pipeline.JSON.fromJson(Files.readString(spacingOutput.resolve("metrics.json")), JsonObject.class);
        var spacing = metrics.getAsJsonObject("spacing");
        assertEquals(628, spacing.get("selected").getAsInt());
        assertEquals(627, spacing.get("unique").getAsInt());
        assertEquals(626, spacing.get("retained").getAsInt());
        var early = Pipeline.JSON.fromJson(Files.readString(earlyOutput.resolve("metrics.json")), JsonObject.class);
        assertTrue(early.get("earlyClose").getAsBoolean());
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

    private static void traceStand(Path input, Path output, String... flags) throws IOException, InterruptedException {
        // A fresh JVM matches normal CLI use and makes the symmetric fixture repeatable across runs.
        String command = ProcessHandle.current().info().command().orElseThrow();
        String classpath = System.getProperty("pointcloud.experiments.classpath", System.getProperty("java.class.path"));
        var args = new ArrayList<>(List.of(command, "-cp", classpath, VegetationOutlines.class.getName()));
        args.addAll(List.of(flags));
        args.addAll(List.of("all", output.toString(), "2610200", "1232400", "2610220", "1232420",
                "2610200", "1232400", "2610220", "1232420", input.toString()));
        var process = new ProcessBuilder(args).redirectErrorStream(true).start();
        try (var log = process.getInputStream()) {
            var message = new String(log.readAllBytes(), StandardCharsets.UTF_8);
            assertEquals(0, process.waitFor(), message);
        }
    }

    private static Path writeStandFixture(Path path) throws IOException {
        var records = new ArrayList<int[]>();
        for (int x = 200; x <= 1400; x += 50) {
            for (int y = 200; y <= 1400; y += 50) {
                records.add(new int[]{x, y, 210, 3});
            }
        }
        records.add(new int[]{200, 200, 210, 3});
        records.add(new int[]{224, 200, 210, 3});
        records.add(new int[]{225, 200, 210, 3});
        return writeLas(path, .01, records.toArray(int[][]::new));
    }

    private static Path writeHeightFixture(Path path) throws IOException {
        int[][] records = {{1, 1, 7, 3}, {2, 1, 8, 3}, {3, 1, 9, 3}, {2, 2, 9, 0x83}, {2, 3, 9, 6}};
        return writeLas(path, .25, records);
    }

    private static Path writeLas(Path path, double scale, int[][] records) throws IOException {
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
        double[] origin = {2610200, 1232400, 700};
        for (int axis = 0; axis < origin.length; axis++) {
            int coordinate = axis;
            var extent = Arrays.stream(records).mapToInt(record -> record[coordinate]).summaryStatistics();
            las.putDouble(131 + 8 * axis, scale).putDouble(155 + 8 * axis, origin[axis]);
            las.putDouble(179 + 16 * axis, origin[axis] + scale * extent.getMax());
            las.putDouble(187 + 16 * axis, origin[axis] + scale * extent.getMin());
        }
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
