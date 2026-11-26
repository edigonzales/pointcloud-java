package pointcloud;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Random;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TreetopProminenceTest {
    @Test
    void thresholdIncludesExactDropAndNoDataDisconnectsTheSaddle() throws IOException {
        var grid = grid(0, 0, 12, 7, 1);
        double[] scores = emptyScores(grid);
        int low = put(grid, scores, 2, 2, 12);
        int high = put(grid, scores, 6, 2, 15);
        for (int x = 1; x <= 7; x++) {
            put(grid, scores, x, 1, 8);
        }
        for (int x = 3; x <= 5; x++) {
            put(grid, scores, x, 2, 11.5);
        }
        assertArrayEquals(new int[]{low, high}, TreetopProminence.detect(grid, scores, 0.50));
        assertArrayEquals(new int[]{high}, TreetopProminence.detect(grid, scores, 0.75));
        put(grid, scores, 4, 2, Double.NEGATIVE_INFINITY);
        assertArrayEquals(new int[]{low, high}, TreetopProminence.detect(grid, scores, 0.75));
    }

    @Test
    void countsTheSixMetreBoundaryAndIgnoresAPathLeavingTheCircle() throws IOException {
        var grid = grid(0, 0, 13, 6, 1);
        double[] scores = emptyScores(grid);
        int low = put(grid, scores, 0, 2, 12);
        put(grid, scores, 0, 1, 8);
        put(grid, scores, 1, 1, 8);
        for (int x = 1; x < 7; x++) {
            put(grid, scores, x, 2, 11.75);
        }
        put(grid, scores, 7, 2, 15);
        assertTrue(contains(TreetopProminence.detect(grid, scores, 0.5), low));
        put(grid, scores, 6, 2, 15);
        assertFalse(contains(TreetopProminence.detect(grid, scores, 0.5), low));

        Arrays.fill(scores, Double.NEGATIVE_INFINITY);
        put(grid, scores, 0, 2, 12);
        put(grid, scores, 0, 1, 8);
        put(grid, scores, 1, 1, 8);
        for (int[] cell : new int[][]{{1, 3}, {2, 4}, {3, 5}, {4, 5}, {5, 5},
                {6, 5}, {7, 4}, {7, 3}, {7, 2}, {6, 1}}) {
            put(grid, scores, cell[0], cell[1], 11.75);
        }
        put(grid, scores, 5, 1, 15);
        assertTrue(contains(TreetopProminence.detect(grid, scores, 0.5), low));
    }

    @Test
    void equalPlateauChoosesTheNorthwestAndSparseSpikesAreIgnored() throws IOException {
        var grid = grid(0, 0, 20, 10, 1);
        double[] scores = emptyScores(grid);
        int northwest = put(grid, scores, 4, 4, 20);
        put(grid, scores, 5, 4, 20);
        put(grid, scores, 4, 3, 20);
        put(grid, scores, 5, 3, 20);
        put(grid, scores, 15, 6, 35);
        put(grid, scores, 15, 5, 28);
        put(grid, scores, 16, 6, 28);
        assertArrayEquals(new int[]{northwest}, TreetopProminence.detect(grid, scores, 0.5));
    }

    @Test
    void smoothingRenormalizesAcrossMissingValuesAndKeepsOriginalMask() throws IOException {
        var grid = grid(0, 0, 10, 10, 0.5);
        double[] scores = emptyScores(grid);
        int centre = put(grid, scores, 1.5, 1.5, 17.3);
        int side = put(grid, scores, 2.0, 1.5, 11.3);
        int diagonal = put(grid, scores, 2.0, 2.0, 14.3);
        double w = Math.exp(-0.5);
        double expected = (17.3 + 11.3 * w + 14.3 * w * w) / (1 + w + w * w);
        double[] smoothed = TreetopProminence.smooth(grid, scores);
        assertEquals(expected, smoothed[centre], 1e-12);
        for (int cell = 0; cell < scores.length; cell++) {
            assertEquals(Double.isFinite(scores[cell]), Double.isFinite(smoothed[cell]));
        }
        scores[side] = 17.3;
        scores[diagonal] = 17.3;
        assertArrayEquals(scores, TreetopProminence.smooth(grid, scores));
    }

    @Test
    void matchesIndependentWidestPathOracleAcrossSurfacesAndThresholds() throws IOException {
        var grid = grid(0, 0, 16, 16, 0.5);
        double[] scores = emptyScores(grid);
        var random = new Random(744);
        for (int cell = 0; cell < scores.length; cell++) {
            if (random.nextInt(5) != 0) {
                scores[cell] = 20 + random.nextInt(12) * 0.25;
            }
        }
        for (double[] raster : new double[][]{scores, TreetopProminence.smooth(grid, scores)}) {
            for (double threshold : new double[]{0.25, 0.5, 0.75, 1.0, 1.5}) {
                assertArrayEquals(reference(grid, raster, threshold), TreetopProminence.detect(grid, raster, threshold));
            }
        }
    }

    @Test
    void splitGaussianCoresMatchWholeAndHaloCountsTowardsLimit() throws IOException {
        var full = grid(0, 0, 48, 40, 0.5);
        var west = grid(0, 0, 24, 40, 0.5);
        var east = grid(12, 0, 24, 40, 0.5);
        assertEquals(15, full.halo());
        assertEquals(12, TreetopExperiment.Grid.fromCore(0, 0, 48, 40, 0.5,
                List.of(TreetopExperiment.Variant.parse("chm-prom-050"))).halo());
        Comparator<Pixel> order = Comparator.comparingInt(Pixel::y).reversed().thenComparingInt(Pixel::x);
        var unsplit = gridPeaks(full);
        assertFalse(unsplit.isEmpty());
        assertEquals(unsplit, Stream.concat(gridPeaks(west).stream(), gridPeaks(east).stream()).sorted(order).toList());
        assertThrows(IllegalArgumentException.class, () -> grid(0, 0, 980, 980, 0.5));
    }

    private static TreetopExperiment.Grid grid(double x, double y, int width, int height, double resolution) {
        return TreetopExperiment.Grid.fromCore(x, y, width, height, resolution,
                List.of(TreetopExperiment.Variant.parse("chm-gauss-prom-050")));
    }

    private static double[] emptyScores(TreetopExperiment.Grid grid) {
        double[] scores = new double[grid.cells()];
        Arrays.fill(scores, Double.NEGATIVE_INFINITY);
        return scores;
    }

    private static int put(TreetopExperiment.Grid grid, double[] scores, double x, double y, double score) {
        int cell = grid.cell(x, y);
        scores[cell] = score;
        return cell;
    }

    private static boolean contains(int[] values, int expected) {
        return Arrays.stream(values).anyMatch(cell -> cell == expected);
    }

    private record Pixel(int x, int y) {}

    private static Pixel pixel(TreetopExperiment.Grid grid, int cell) {
        int x = (int) (grid.originX() * 2) + cell % grid.width();
        int y = (int) (grid.originY() * 2) + grid.height() - 1 - cell / grid.width();
        return new Pixel(x, y);
    }

    private static List<Pixel> gridPeaks(TreetopExperiment.Grid grid) throws IOException {
        double[] scores = emptyScores(grid);
        for (int cell = 0; cell < scores.length; cell++) {
            var pixel = pixel(grid, cell);
            if (Math.floorMod(pixel.x() * 931 + pixel.y() * 997, 7) != 0) {
                scores[cell] = 15 + Math.sin(pixel.x() * 0.51) + Math.cos(pixel.y() * 0.77);
            }
        }
        return Arrays.stream(TreetopProminence.detect(grid, TreetopProminence.smooth(grid, scores), 0.5))
                .mapToObj(cell -> pixel(grid, cell)).toList();
    }

    private static int[] reference(TreetopExperiment.Grid grid, double[] scores, double threshold) {
        int width = grid.width();
        int halo = grid.halo();
        var peaks = new ArrayList<Integer>();
        for (int row = halo; row < grid.height() - halo; row++) {
            for (int column = halo; column < width - halo; column++) {
                int cell = row * width + column;
                if (!Double.isFinite(scores[cell])) {
                    continue;
                }
                var support = nearby(grid, scores, cell, Math.sqrt(2) * grid.resolution());
                if (support.size() >= 3 && support.stream().noneMatch(other -> superior(scores, other, cell))
                        && saddle(grid, scores, cell) <= scores[cell] - threshold) {
                    peaks.add(cell);
                }
            }
        }
        return peaks.stream().mapToInt(Integer::intValue).toArray();
    }

    private record Step(int cell, double saddle) {}

    private static double saddle(TreetopExperiment.Grid grid, double[] scores, int peak) {
        // Maximise the bottleneck score of each entire path, independently of the tested threshold.
        var queue = new PriorityQueue<>(Comparator.comparingDouble(Step::saddle).reversed());
        double[] reached = emptyScores(grid);
        reached[peak] = scores[peak];
        queue.add(new Step(peak, scores[peak]));
        while (!queue.isEmpty()) {
            Step step = queue.remove();
            if (superior(scores, step.cell(), peak)) {
                return step.saddle();
            }
            for (int next : nearby(grid, scores, step.cell(), Math.sqrt(2) * grid.resolution())) {
                if (distance(grid, next, peak) <= 6) {
                    double level = Math.min(step.saddle(), scores[next]);
                    if (level > reached[next]) {
                        reached[next] = level;
                        queue.add(new Step(next, level));
                    }
                }
            }
        }
        return Double.NEGATIVE_INFINITY;
    }

    private static List<Integer> nearby(TreetopExperiment.Grid grid, double[] scores, int cell, double radius) {
        int row = cell / grid.width();
        int column = cell % grid.width();
        var result = new ArrayList<Integer>(8);
        for (int y = Math.max(0, row - 1); y <= Math.min(grid.height() - 1, row + 1); y++) {
            for (int x = Math.max(0, column - 1); x <= Math.min(grid.width() - 1, column + 1); x++) {
                int other = y * grid.width() + x;
                if (other != cell && Double.isFinite(scores[other]) && distance(grid, other, cell) <= radius) {
                    result.add(other);
                }
            }
        }
        return result;
    }

    private static double distance(TreetopExperiment.Grid grid, int one, int other) {
        int width = grid.width();
        return grid.resolution() * Math.hypot(one % width - other % width, one / width - other / width);
    }

    private static boolean superior(double[] scores, int cell, int peak) {
        return scores[cell] > scores[peak] || scores[cell] == scores[peak] && cell < peak;
    }
}
