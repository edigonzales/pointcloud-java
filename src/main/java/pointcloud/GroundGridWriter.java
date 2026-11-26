package pointcloud;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Locale;

import static pointcloud.GeoTiffOutput.checkInterrupted;

/** Accumulates point statistics by pixel and stages a north-up GeoTIFF for publication. */
final class GroundGridWriter {
    enum Band {
        MIN, MAX, MEAN, COUNT, STDEV;

        String label() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    record Grid(double originX, double originY, double resolution, int width, int height, int windowSize,
                EnumSet<Band> bands, Dimension dimension, double nodata, boolean deflate, double[] heightThresholds) {
        Grid {
            GeoTiffOutput.validateGrid(originX, originY, resolution, width, height);
            bands = bands.clone();
            heightThresholds = heightThresholds.clone();
        }
    }

    private final GeoTiffOutput output;
    private final Grid grid;
    private final long[] counts;
    private final double[] minima;
    private final double[] maxima;
    private final double[] means;
    private final double[] deviations;
    private final double[] countBand;
    private final long[][] thresholdCounts;
    private final double[][] fractionBands;
    private final double[][] outputBands;
    private final double[][] statisticBands;
    private final double east;
    private final double north;
    private long pointsBinned;

    GroundGridWriter(GeoTiffOutput output, Grid grid) {
        this.grid = grid;
        this.output = output;
        int cells = grid.width * grid.height;
        counts = new long[cells];
        minima = grid.bands.contains(Band.MIN) ? new double[cells] : null;
        maxima = grid.bands.contains(Band.MAX) ? new double[cells] : null;
        deviations = grid.bands.contains(Band.STDEV) ? new double[cells] : null;
        means = grid.bands.contains(Band.MEAN) || deviations != null ? new double[cells] : null;
        countBand = grid.bands.contains(Band.COUNT) ? new double[cells] : null;
        thresholdCounts = new long[grid.heightThresholds.length][cells];
        fractionBands = new double[grid.heightThresholds.length][cells];

        var bands = new ArrayList<double[]>(grid.bands.size() + fractionBands.length);
        var statistics = new ArrayList<double[]>(grid.bands.size());
        for (Band band : grid.bands) {
            double[] values = switch (band) {
                case MIN -> minima;
                case MAX -> maxima;
                case MEAN -> means;
                case COUNT -> countBand;
                case STDEV -> deviations;
            };
            bands.add(values);
            if (band != Band.COUNT) {
                statistics.add(values);
            }
        }
        for (double[] fractionBand : fractionBands) {
            bands.add(fractionBand);
        }
        outputBands = bands.toArray(double[][]::new);
        statisticBands = statistics.toArray(double[][]::new);
        east = grid.originX + grid.width * grid.resolution;
        north = grid.originY + grid.height * grid.resolution;
    }

    void add(PointBatch batch, int point) {
        double x = batch.x(point);
        double y = batch.y(point);
        if (x < grid.originX || x >= east || y < grid.originY || y >= north) {
            return;
        }
        int column = Math.min(grid.width - 1, (int) ((x - grid.originX) / grid.resolution));
        int rowFromSouth = Math.min(grid.height - 1, (int) ((y - grid.originY) / grid.resolution));
        int cell = (grid.height - 1 - rowFromSouth) * grid.width + column;
        long count = ++counts[cell];
        pointsBinned++;
        if (statisticBands.length == 0 && thresholdCounts.length == 0) {
            return;
        }
        double value = grid.dimension.value(batch, point);
        // Record only the highest crossed threshold and accumulate lower thresholds when writing.
        int crossed = 0;
        while (crossed < grid.heightThresholds.length && value > grid.heightThresholds[crossed]) {
            crossed++;
        }
        if (crossed > 0) {
            thresholdCounts[crossed - 1][cell]++;
        }
        if (minima != null) {
            minima[cell] = count == 1 ? value : Math.min(minima[cell], value);
        }
        if (maxima != null) {
            maxima[cell] = count == 1 ? value : Math.max(maxima[cell], value);
        }
        if (means != null) {
            double previous = means[cell];
            double mean = count == 1 ? value : updatedMean(previous, value, count);
            means[cell] = mean;
            if (deviations != null && count > 1) {
                deviations[cell] += (value - previous) * (value - mean);
            }
        }
    }

    long pointsBinned() {
        return pointsBinned;
    }

    void prepare(boolean sourceFinished) throws IOException {
        if (!sourceFinished) {
            throw new IOException("GeoTIFF output requires a complete source scan");
        }
        checkInterrupted();
        finishBands();
        var names = new ArrayList<String>(outputBands.length);
        grid.bands.forEach(band -> names.add(band.label()));
        for (double threshold : grid.heightThresholds) {
            names.add("fraction_above_" + BigDecimal.valueOf(threshold).stripTrailingZeros().toPlainString());
        }
        output.prepare(grid.originX, grid.originY, grid.resolution, grid.width, grid.height,
                outputBands, names, grid.nodata, grid.deflate);
    }

    private void finishBands() throws IOException {
        int width = grid.width;
        int west = width;
        int east = -1;
        int north = grid.height;
        int south = -1;
        int measured = 0;
        for (int cell = 0; cell < counts.length; cell++) {
            if ((cell & 4095) == 0) {
                checkInterrupted();
            }
            long count = counts[cell];
            if (countBand != null) {
                countBand[cell] = count;
                checkSample(countBand[cell], cell);
            }
            if (count == 0) {
                for (var band : statisticBands) {
                    band[cell] = grid.nodata;
                }
                for (var band : fractionBands) {
                    band[cell] = grid.nodata;
                }
                continue;
            }
            long above = 0;
            for (int i = fractionBands.length - 1; i >= 0; i--) {
                above += thresholdCounts[i][cell];
                double fraction = above / (double) count;
                checkSample(fraction, cell);
                fractionBands[i][cell] = fraction;
            }
            measured++;
            int row = cell / width;
            int column = cell % width;
            north = Math.min(north, row);
            south = Math.max(south, row);
            west = Math.min(west, column);
            east = Math.max(east, column);
            if (deviations != null) {
                deviations[cell] = Math.sqrt(deviations[cell] / count);
            }
            for (var band : statisticBands) {
                checkSample(band[cell], cell);
            }
        }
        if (grid.windowSize > 0 && statisticBands.length > 0 && measured > 0 && measured < counts.length) {
            fillGaps(west, east, north, south);
        }
    }

    private void fillGaps(int donorWest, int donorEast, int donorNorth, int donorSouth) throws IOException {
        int width = grid.width;
        int radius = grid.windowSize;
        int firstRow = Math.max(0, donorNorth - radius);
        int lastRow = Math.min(grid.height - 1, donorSouth + radius);
        int firstColumn = Math.max(0, donorWest - radius);
        int lastColumn = Math.min(width - 1, donorEast + radius);
        double[] averages = new double[statisticBands.length];
        for (int row = firstRow; row <= lastRow; row++) {
            checkInterrupted();
            int north = Math.max(donorNorth, row - radius);
            int south = Math.min(donorSouth, row + radius);
            for (int column = firstColumn; column <= lastColumn; column++) {
                int cell = row * width + column;
                if (counts[cell] != 0) {
                    continue;
                }
                int west = Math.max(donorWest, column - radius);
                int east = Math.min(donorEast, column + radius);
                Arrays.fill(averages, 0);
                double weights = 0;
                for (int y = north; y <= south; y++) {
                    checkInterrupted();
                    int dy = Math.abs(y - row);
                    for (int x = west; x <= east; x++) {
                        int neighbor = y * width + x;
                        if ((neighbor & 4095) == 0) {
                            checkInterrupted();
                        }
                        // Counts identify measured cells even after earlier gaps have been filled.
                        if (counts[neighbor] > 0) {
                            int distance = Math.max(dy, Math.abs(x - column));
                            double divisor = 1 + weights * distance;
                            weights += 1.0 / distance;
                            for (int i = 0; i < averages.length; i++) {
                                averages[i] = updatedMean(averages[i], statisticBands[i][neighbor], divisor);
                            }
                        }
                    }
                }
                if (weights > 0) {
                    for (int i = 0; i < averages.length; i++) {
                        checkSample(averages[i], cell);
                        statisticBands[i][cell] = averages[i];
                    }
                }
            }
        }
    }

    private void checkSample(double value, int cell) throws IOException {
        if (value == grid.nodata) {
            throw new IOException("nodata value " + grid.nodata + " collides with valid data at raster cell " + cell);
        }
        if (!Double.isFinite(value)) {
            throw new IOException("Nonfinite value at raster cell " + cell);
        }
    }

    private static double updatedMean(double mean, double value, double count) {
        double delta = value - mean;
        return Double.isFinite(delta) ? mean + delta / count : (mean - mean / count) + value / count;
    }
}
