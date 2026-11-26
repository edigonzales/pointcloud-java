package pointcloud;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Random;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static pointcloud.TreetopExperiment.detect;
import static pointcloud.TreetopExperiment.offsets;

class TreetopExperimentTest {
    @Test
    void eachSurfaceKeepsOriginalCoordinatesAndHeightOfItsFirstHighestPoint() {
        var chm = new TreetopExperiment.Surface(1, TreetopExperiment.Kind.CHM);
        var dsm = new TreetopExperiment.Surface(1, TreetopExperiment.Kind.DSM);
        for (var surface : new TreetopExperiment.Surface[]{chm, dsm}) {
            surface.add(0, 101, 201, 4200, 642, 18);
            surface.add(0, 103, 203, 4400, 644, 16.25);
            surface.add(0, 107, 207, 4400, 644, 18);
        }
        assertEquals(18d, chm.scores[0]);
        assertEquals(644d, dsm.scores[0]);
        assertEquals(16.25, dsm.heights[0]);
        assertArrayEquals(new int[]{101, 201, 4200}, xyz(chm, 0));
        assertArrayEquals(new int[]{103, 203, 4400}, xyz(dsm, 0));
    }

    @Test
    void resolvesPlateausAndTestsInclusiveDistanceInACircularWindow() throws IOException {
        var grid = TreetopExperiment.Grid.fromCore(20, 30, 34, 18, 0.5);
        var surface = new TreetopExperiment.Surface(grid.cells(), TreetopExperiment.Kind.CHM);
        int northwest = add(grid, surface, 22.25, 33.75, 23, 23);
        add(grid, surface, 22.75, 33.75, 23, 23);
        add(grid, surface, 22.25, 33.25, 23, 23);
        add(grid, surface, 22.75, 33.25, 23, 23);
        add(grid, surface, 24.25, 33.75, 21, 21); // Exactly 2 m east of the highest cell.
        int other = add(grid, surface, 24.25, 31.75, 22, 22); // Outside circular radius of the plateau.
        int distant = add(grid, surface, 34.25, 31.25, 13, 13);
        assertArrayEquals(new int[]{northwest, other, distant}, peaks(grid, surface, "chm-r2"));
        assertArrayEquals(new int[]{northwest, distant}, peaks(grid, surface, "chm-r3"));
    }

    @Test
    void candidateSurfacesCanDisagreeOnSlopedGround() throws IOException {
        var grid = TreetopExperiment.Grid.fromCore(0, 0, 16, 16, 0.5);
        var chm = new TreetopExperiment.Surface(grid.cells(), TreetopExperiment.Kind.CHM);
        var dsm = new TreetopExperiment.Surface(grid.cells(), TreetopExperiment.Kind.DSM);
        int uphill = grid.cell(4.25, 5.25);
        int downhill = grid.cell(3.25, 5.25);
        for (var surface : new TreetopExperiment.Surface[]{chm, dsm}) {
            add(grid, surface, 4.25, 5.25, 646, 21);
            add(grid, surface, 3.25, 5.25, 645, 22);
        }
        assertArrayEquals(new int[]{downhill}, peaks(grid, chm, "chm-r2"));
        assertArrayEquals(new int[]{uphill}, peaks(grid, dsm, "dsm-r2"));
        assertEquals(21d, dsm.heights[uphill]);
    }

    @Test
    void tileHalosPreservePeakOwnershipAcrossAnExactCellBoundary() throws IOException {
        var whole = TreetopExperiment.Grid.fromCore(0, 0, 32, 16, 0.5);
        var west = TreetopExperiment.Grid.fromCore(0, 0, 16, 16, 0.5);
        var east = TreetopExperiment.Grid.fromCore(8, 0, 16, 16, 0.5);
        int[] wholePeaks = borderPeaks(whole);
        int[] westPeaks = borderPeaks(west);
        int[] eastPeaks = borderPeaks(east);
        assertArrayEquals(new int[]{225, 800, 1325}, wholePeaks);
        assertArrayEquals(wholePeaks, IntStream.concat(IntStream.of(westPeaks), IntStream.of(eastPeaks)).toArray());
    }

    @Test
    void emptyCellsAreNotCandidatesAndHaloCountsAgainstGridLimit() throws IOException {
        var tiny = TreetopExperiment.Grid.fromCore(5, 10, 1, 1, 0.5);
        var surface = new TreetopExperiment.Surface(tiny.cells(), TreetopExperiment.Kind.DSM);
        assertArrayEquals(new int[0], peaks(tiny, surface, "dsm-adaptive"));
        assertEquals(-1, tiny.cell(tiny.originX() + tiny.width() * tiny.resolution(), tiny.originY()));
        assertThrows(IllegalArgumentException.class,
                () -> TreetopExperiment.Grid.fromCore(0, 0, 1000, 1000, 0.5));
    }

    @Test
    void optimizedDetectorMatchesCompleteReferenceScan() throws IOException {
        var grid = TreetopExperiment.Grid.fromCore(0, 0, 41, 38, 0.5);
        var random = new Random(3169);
        for (var kind : TreetopExperiment.Kind.values()) {
            var surface = new TreetopExperiment.Surface(grid.cells(), kind);
            for (int cell = 0; cell < grid.cells(); cell++) {
                if (random.nextBoolean()) {
                    surface.add(cell, cell, cell, cell, 600 + random.nextInt(36), 5 + random.nextInt(36));
                }
            }
            for (var variant : TreetopExperiment.Variant.ALL) {
                if (variant.kind() == kind) {
                    assertArrayEquals(reference(grid, surface, variant), peaks(grid, surface, variant.id()),
                            variant.id());
                }
            }
        }
    }

    @Test
    void refusesToPublishFromUnreadableInput(@TempDir Path directory) throws IOException {
        Path input = Files.write(directory.resolve("broken.las"), new byte[64]);
        Path output = directory.resolve("output");
        assertThrows(IOException.class, () -> TreetopExperiment.main(new String[]{input.toString(),
                directory.resolve("dem.tif").toString(), output.toString(), "0", "0", "2", "2", "0.5", "1"}));
        assertFalse(Files.exists(output));
    }

    private static int[] xyz(TreetopExperiment.Surface surface, int cell) {
        return new int[]{surface.rawX[cell], surface.rawY[cell], surface.rawZ[cell]};
    }

    private static int add(TreetopExperiment.Grid grid, TreetopExperiment.Surface surface, double x, double y,
                           double elevation, double hag) {
        int cell = grid.cell(x, y);
        surface.add(cell, (int) (x * 100), (int) (y * 100), (int) (elevation * 100), elevation, hag);
        return cell;
    }

    private static int[] borderPeaks(TreetopExperiment.Grid grid) throws IOException {
        var surface = new TreetopExperiment.Surface(grid.cells(), TreetopExperiment.Kind.CHM);
        for (double[] point : new double[][]{{2.25, 32}, {7.75, 24}, {8, 26}, {13.25, 22}}) {
            if (grid.cell(point[0], 4.25) >= 0) {
                add(grid, surface, point[0], 4.25, point[1], point[1]);
            }
        }
        return IntStream.of(peaks(grid, surface, "chm-r3")).map(cell -> surface.rawX[cell]).toArray();
    }

    private static int[] peaks(TreetopExperiment.Grid grid, TreetopExperiment.Surface surface, String variant)
            throws IOException {
        return detect(grid, surface, TreetopExperiment.Variant.parse(variant), offsets(grid.resolution()));
    }

    private static int[] reference(TreetopExperiment.Grid grid, TreetopExperiment.Surface surface,
                                   TreetopExperiment.Variant variant) {
        int width = grid.width();
        int halo = grid.halo();
        var peaks = new ArrayList<Integer>();
        for (int row = halo; row < grid.height() - halo; row++) {
            for (int column = halo; column < width - halo; column++) {
                int cell = row * width + column;
                if (!Double.isFinite(surface.scores[cell])) {
                    continue;
                }
                double height = surface.heights[cell];
                double radius = variant.fixedRadius() == 0
                        ? Math.min(4, Math.max(1.5, 0.12 * height)) : variant.fixedRadius();
                boolean peak = true;
                for (int other = 0; other < grid.cells(); other++) {
                    double dx = (other % width - column) * grid.resolution();
                    double dy = (other / width - row) * grid.resolution();
                    if (Math.hypot(dx, dy) <= radius && (surface.scores[other] > surface.scores[cell]
                            || surface.scores[other] == surface.scores[cell] && other < cell)) {
                        peak = false;
                    }
                }
                if (peak) {
                    peaks.add(cell);
                }
            }
        }
        return peaks.stream().mapToInt(Integer::intValue).toArray();
    }
}
