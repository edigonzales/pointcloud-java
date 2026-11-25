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

/** Accumulates Z by pixel and stages a north-up GeoTIFF for publication. */
final class GroundGridWriter implements AutoCloseable {
    record Grid(double originX, double originY, double resolution, int width, int height, boolean fillGaps) {
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
        }
    }

    private final Path input;
    private final Path target;
    private final boolean overwrite;
    private final Grid grid;
    private final CoordinateReferenceSystem crs;
    private final long[] counts;
    private final double[][] bands;
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
        bands = new double[4][cells];
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
        double z = batch.z(point);
        long count = ++counts[cell];
        pointsBinned++;
        if (count == 1) {
            bands[0][cell] = z;
            bands[1][cell] = z;
            bands[2][cell] = z;
            return;
        }
        bands[0][cell] = Math.min(bands[0][cell], z);
        bands[1][cell] = Math.max(bands[1][cell], z);
        bands[2][cell] = updatedMean(bands[2][cell], z, count);
    }

    long pointsBinned() {
        return pointsBinned;
    }

    void prepare(boolean sourceFinished) throws IOException {
        if (!sourceFinished) {
            throw new IOException("GeoTIFF output requires a complete source scan");
        }
        checkInterrupted();
        for (int cell = 0; cell < counts.length; cell++) {
            if (counts[cell] == 0) {
                bands[0][cell] = GeoTiffOutput.NODATA;
                bands[1][cell] = GeoTiffOutput.NODATA;
                bands[2][cell] = GeoTiffOutput.NODATA;
            }
            bands[3][cell] = counts[cell];
        }
        if (grid.fillGaps) {
            fillGaps();
            checkInterrupted();
        }
        Files.createDirectories(target.getParent());
        temporary = Files.createTempFile(target.getParent(), ".pointcloud-", ".tif.part");
        GeoTiffOutput.write(temporary, crs, grid.originX, grid.originY, grid.resolution, grid.width, grid.height, bands);
        checkInterrupted();
    }

    private void fillGaps() throws InterruptedIOException {
        double[] minima = bands[0];
        double[] maxima = bands[1];
        double[] means = bands[2];
        int width = grid.width;
        for (int row = 0; row < grid.height; row++) {
            checkInterrupted();
            int north = Math.max(0, row - 1);
            int south = Math.min(grid.height - 1, row + 1);
            for (int column = 0; column < width; column++) {
                int cell = row * width + column;
                if (counts[cell] != 0) {
                    continue;
                }
                int west = Math.max(0, column - 1);
                int east = Math.min(width - 1, column + 1);
                double min = 0;
                double max = 0;
                double mean = 0;
                int neighbors = 0;
                for (int y = north; y <= south; y++) {
                    for (int x = west; x <= east; x++) {
                        int neighbor = y * width + x;
                        // Counts identify measured cells even after earlier gaps have been filled.
                        if (counts[neighbor] > 0) {
                            neighbors++;
                            min = updatedMean(min, minima[neighbor], neighbors);
                            max = updatedMean(max, maxima[neighbor], neighbors);
                            mean = updatedMean(mean, means[neighbor], neighbors);
                        }
                    }
                }
                if (neighbors > 0) {
                    minima[cell] = min;
                    maxima[cell] = max;
                    means[cell] = mean;
                }
            }
        }
    }

    private static double updatedMean(double mean, double value, long count) {
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
