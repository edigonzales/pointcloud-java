package pointcloud;

import java.io.IOException;
import java.util.Arrays;

/** Bounded saddle-prominence filtering on a north-up grid. */
final class TreetopProminence {
    static final double SEARCH_RADIUS = 6;
    static final double GAUSSIAN_RADIUS = 1.5;
    private static final double GAUSSIAN_SIGMA = 0.5;

    private TreetopProminence() {}

    static double[] smooth(TreetopExperiment.Grid grid, double[] scores) throws IOException {
        int radius = (int) Math.floor(GAUSSIAN_RADIUS / grid.resolution());
        if (radius == 0) {
            return scores;
        }
        double[] weights = new double[radius + 1];
        for (int offset = 0; offset <= radius; offset++) {
            double sigmaUnits = offset * grid.resolution() / GAUSSIAN_SIGMA;
            weights[offset] = Math.exp(-0.5 * sigmaUnits * sigmaUnits);
        }

        int width = grid.width();
        int height = grid.height();
        double[] rowMeans = new double[scores.length];
        double[] rowWeights = new double[scores.length];
        for (int row = 0; row < height; row++) {
            TreetopExperiment.checkInterrupted();
            int rowStart = row * width;
            for (int column = 0; column < width; column++) {
                int cell = rowStart + column;
                double anchor = 0;
                double weightSum = 0;
                double differences = 0;
                for (int x = Math.max(0, column - radius); x <= Math.min(width - 1, column + radius); x++) {
                    double score = scores[rowStart + x];
                    if (!Double.isFinite(score)) {
                        continue;
                    }
                    if (weightSum == 0) {
                        anchor = score;
                    }
                    double weight = weights[Math.abs(x - column)];
                    differences += (score - anchor) * weight;
                    weightSum += weight;
                }
                if (weightSum > 0) {
                    rowMeans[cell] = anchor + differences / weightSum;
                    rowWeights[cell] = weightSum;
                }
            }
        }

        double[] result = new double[scores.length];
        for (int row = 0; row < height; row++) {
            TreetopExperiment.checkInterrupted();
            for (int column = 0; column < width; column++) {
                int cell = row * width + column;
                double centre = scores[cell];
                if (!Double.isFinite(centre)) {
                    result[cell] = centre;
                    continue;
                }
                double weightSum = 0;
                double differences = 0;
                for (int y = Math.max(0, row - radius); y <= Math.min(height - 1, row + radius); y++) {
                    int rowCell = y * width + column;
                    double weight = rowWeights[rowCell] * weights[Math.abs(y - row)];
                    differences += (rowMeans[rowCell] - centre) * weight;
                    weightSum += weight;
                }
                // Centring the sums preserves an exactly flat plateau across different masks.
                result[cell] = centre + differences / weightSum;
            }
        }
        return result;
    }

    static int[] detect(TreetopExperiment.Grid grid, double[] scores, double threshold) throws IOException {
        int width = grid.width();
        int halo = grid.halo();
        int[] neighbours = neighbourSteps(width);
        var search = new SearchWindow(width, grid.resolution());
        int[] peaks = new int[grid.coreCells()];
        int size = 0;
        for (int row = halo; row < grid.height() - halo; row++) {
            TreetopExperiment.checkInterrupted();
            candidates:
            for (int column = halo; column < width - halo; column++) {
                int cell = row * width + column;
                double score = scores[cell];
                if (!Double.isFinite(score)) {
                    continue;
                }
                int support = 0;
                for (int step : neighbours) {
                    int neighbour = cell + step;
                    double other = scores[neighbour];
                    if (!Double.isFinite(other)) {
                        continue;
                    }
                    if (outranks(other, neighbour, score, cell)) {
                        continue candidates;
                    }
                    support++;
                }
                if (support >= 3 && !search.reachesHigher(scores, cell, threshold)) {
                    peaks[size++] = cell;
                }
            }
        }
        return Arrays.copyOf(peaks, size);
    }

    private static boolean outranks(double score, int cell, double centreScore, int centre) {
        return score > centreScore || score == centreScore && cell < centre;
    }

    private static int[] neighbourSteps(int stride) {
        return new int[]{-stride - 1, -stride, -stride + 1, -1, 1, stride - 1, stride, stride + 1};
    }

    private static final class SearchWindow {
        private final int centre;
        private final int[] rasterOffsets;
        private final int[] neighbours;
        private final int[] visited;
        private final int[] queue;
        private int generation;

        SearchWindow(int rasterWidth, double resolution) {
            int radius = (int) Math.floor(SEARCH_RADIUS / resolution);
            int diameter = 2 * radius + 3;
            centre = (radius + 1) * (diameter + 1);
            int cells = diameter * diameter;
            rasterOffsets = new int[cells];
            neighbours = neighbourSteps(diameter);
            visited = new int[cells];
            queue = new int[cells];
            // The blocked outer ring makes every neighbour lookup safe without a boundary test.
            Arrays.fill(visited, Integer.MAX_VALUE);
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dx = -radius; dx <= radius; dx++) {
                    double x = dx * resolution;
                    double y = dy * resolution;
                    if (x * x + y * y <= SEARCH_RADIUS * SEARCH_RADIUS) {
                        int local = centre + dy * diameter + dx;
                        visited[local] = 0;
                        rasterOffsets[local] = dy * rasterWidth + dx;
                    }
                }
            }
        }

        boolean reachesHigher(double[] scores, int cell, double threshold) {
            double peak = scores[cell];
            double lowerBound = peak - threshold;
            int stamp = ++generation;
            int head = 0;
            int tail = 1;
            visited[centre] = stamp;
            queue[0] = centre;
            while (head < tail) {
                int offset = queue[head++];
                for (int step : neighbours) {
                    int next = offset + step;
                    if (visited[next] >= stamp) {
                        continue;
                    }
                    visited[next] = stamp;
                    int candidate = cell + rasterOffsets[next];
                    double score = scores[candidate];
                    if (!(score > lowerBound) || !Double.isFinite(score)) {
                        continue;
                    }
                    if (outranks(score, candidate, peak, cell)) {
                        return true;
                    }
                    queue[tail++] = next;
                }
            }
            return false;
        }
    }
}
