package pointcloud;

import org.geotools.api.referencing.crs.CoordinateReferenceSystem;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.channels.FileChannel;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Locale;

/** Accumulates point statistics by pixel and stages a north-up GeoTIFF for publication. */
final class GroundGridWriter implements AutoCloseable {
    enum Band {
        MIN, MAX, MEAN, COUNT, STDEV;

        String label() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    record Grid(double originX, double originY, double resolution, int width, int height, int windowSize,
                EnumSet<Band> bands, Dimension dimension, double nodata, boolean deflate) {
        static final int MAX_CELLS = 1_000_000;

        Grid {
            if (resolution <= 0 || width <= 0 || height <= 0 || (long) width * height > MAX_CELLS) {
                throw new IllegalArgumentException("grid requires positive resolution and dimensions, with at most "
                        + MAX_CELLS + " cells");
            }
            if (!Double.isFinite(originX + width * resolution) || !Double.isFinite(originY + height * resolution)
                    || originX + resolution <= originX || originY + resolution <= originY) {
                throw new IllegalArgumentException("grid extent and cell size must be representable");
            }
            bands = bands.clone();
        }
    }

    private final Path input;
    private final Path target;
    private final boolean overwrite;
    private final Grid grid;
    private final CoordinateReferenceSystem crs;
    private final long[] counts;
    private final double[] minima;
    private final double[] maxima;
    private final double[] means;
    private final double[] deviations;
    private final double[] countBand;
    private final double[][] outputBands;
    private final double[][] statisticBands;
    private final double east;
    private final double north;
    private long pointsBinned;
    private Path temporary;

    GroundGridWriter(Path input, Path target, boolean overwrite, Grid grid, LasHeader header, FileChannel source)
            throws IOException {
        this.input = input;
        this.target = target.toAbsolutePath();
        this.overwrite = overwrite;
        this.grid = grid;
        checkTarget();
        crs = GeoTiffOutput.resolveCrs(header, source);
        int cells = grid.width * grid.height;
        counts = new long[cells];
        minima = grid.bands.contains(Band.MIN) ? new double[cells] : null;
        maxima = grid.bands.contains(Band.MAX) ? new double[cells] : null;
        deviations = grid.bands.contains(Band.STDEV) ? new double[cells] : null;
        means = grid.bands.contains(Band.MEAN) || deviations != null ? new double[cells] : null;
        countBand = grid.bands.contains(Band.COUNT) ? new double[cells] : null;

        var output = new ArrayList<double[]>(grid.bands.size());
        var statistics = new ArrayList<double[]>(grid.bands.size());
        for (Band band : grid.bands) {
            double[] values = switch (band) {
                case MIN -> minima;
                case MAX -> maxima;
                case MEAN -> means;
                case COUNT -> countBand;
                case STDEV -> deviations;
            };
            output.add(values);
            if (band != Band.COUNT) {
                statistics.add(values);
            }
        }
        outputBands = output.toArray(double[][]::new);
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
        if (statisticBands.length == 0) {
            return;
        }
        double value = grid.dimension.value(batch, point);
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
        checkInterrupted();
        Files.createDirectories(target.getParent());
        temporary = Files.createTempFile(target.getParent(), ".pointcloud-", ".tif.part");
        var names = grid.bands.stream().map(Band::label).toList();
        GeoTiffOutput.write(temporary, crs, grid.originX, grid.originY, grid.resolution, grid.width, grid.height,
                outputBands, names, grid.nodata, grid.deflate);
        checkInterrupted();
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
                continue;
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

    void finish() throws IOException {
        checkInterrupted();
        checkTarget();
        if (overwrite) {
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } else {
            Files.createLink(target, temporary);
            Files.delete(temporary);
        }
        temporary = null;
    }

    private void checkTarget() throws IOException {
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            if (Files.exists(target) && Files.isSameFile(input, target)) {
                throw new IOException("Point input and GeoTIFF output refer to the same file");
            }
            if (!overwrite) {
                throw new FileAlreadyExistsException(target.toString());
            }
        }
    }

    private static void checkInterrupted() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException("GeoTIFF output cancelled");
        }
    }

    @Override
    public void close() throws IOException {
        if (temporary != null) {
            Files.deleteIfExists(temporary);
        }
    }
}
