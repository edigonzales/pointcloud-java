package pointcloud;

import org.geotools.api.referencing.FactoryException;
import org.geotools.api.referencing.crs.VerticalCRS;
import org.geotools.api.referencing.datum.PixelInCell;
import org.geotools.coverage.grid.io.AbstractGridFormat;
import org.geotools.coverage.grid.io.OverviewPolicy;
import org.geotools.coverage.grid.io.imageio.geotiff.GeoTiffConstants;
import org.geotools.coverage.util.CoverageUtilities;
import org.geotools.gce.geotiff.GeoTiffReader;
import org.geotools.referencing.CRS;

import javax.imageio.stream.FileImageInputStream;
import java.awt.geom.AffineTransform;
import java.awt.image.RenderedImage;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;

/** One band of a local north-up GeoTIFF for repeated sampling. */
final class HagDem {
    private static final int MAX_CELLS = 4_000_000;

    private final double[] elevations;
    private final int width;
    private final int height;
    private final double west;
    private final double east;
    private final double north;
    private final double south;
    private final double inverseWidth;
    private final double inverseHeight;

    private HagDem(double[] elevations, int width, int height, AffineTransform gridToWorld) {
        this.elevations = elevations;
        this.width = width;
        this.height = height;
        west = gridToWorld.getTranslateX();
        north = gridToWorld.getTranslateY();
        east = west + gridToWorld.getScaleX() * width;
        south = north + gridToWorld.getScaleY() * height;
        inverseWidth = 1 / gridToWorld.getScaleX();
        inverseHeight = -1 / gridToWorld.getScaleY();
    }

    static HagDem load(Path raster, int band, LasHeader header, FileChannel source) throws IOException {
        checkInterrupted();
        if (!Files.isRegularFile(raster)) {
            throw new IOException("DEM is not a regular file: " + raster);
        }
        // Open the file as a stream so the embedded georeferencing takes precedence over sidecars.
        try (var stream = new FileImageInputStream(raster.toFile())) {
            var reader = new GeoTiffReader(stream);
            try {
                var range = reader.getOriginalGridRange();
                int width = range.getSpan(0);
                int height = range.getSpan(1);
                if (width <= 0 || height <= 0 || (long) width * height > MAX_CELLS) {
                    throw new IOException("DEM must contain 1 to " + MAX_CELLS + " cells");
                }
                if (band <= 0 || band > reader.getImageLayout().getSampleModel(null).getNumBands()) {
                    throw new IOException("DEM band " + band + " does not exist");
                }
                var transform = reader.getOriginalGridToWorld(PixelInCell.CELL_CORNER);
                if (!(transform instanceof AffineTransform gridToWorld) || !isNorthUp(gridToWorld, width, height)) {
                    throw new IOException("DEM must have a north-up affine transform");
                }
                checkCrs(reader, header, source);

                var bands = AbstractGridFormat.BANDS.createValue();
                bands.setValue(new int[]{band - 1});
                var overview = AbstractGridFormat.OVERVIEW_POLICY.createValue();
                overview.setValue(OverviewPolicy.IGNORE);
                var rescale = AbstractGridFormat.RESCALE_PIXELS.createValue();
                rescale.setValue(false);
                var coverage = reader.read(bands, overview, rescale);
                try {
                    var noData = CoverageUtilities.getNoDataProperty(coverage);
                    double sentinel = noData == null ? Double.NaN : noData.getAsSingleValue();
                    double[] elevations = copySamples(coverage.getRenderedImage(), width, height, sentinel);
                    return new HagDem(elevations, width, height, gridToWorld);
                } finally {
                    coverage.dispose(true);
                }
            } finally {
                reader.dispose();
            }
        } catch (RuntimeException e) {
            throw new IOException("Cannot read DEM: " + raster, e);
        }
    }

    double elevation(double x, double y) {
        if (!(x >= west && x < east && y <= north && y > south)) {
            return Double.NaN;
        }
        // The coordinate is inside the grid, even if rounding reaches the outer pixel index.
        int column = Math.min(width - 1, (int) ((x - west) * inverseWidth));
        int row = Math.min(height - 1, (int) ((north - y) * inverseHeight));
        return elevations[row * width + column];
    }

    private static boolean isNorthUp(AffineTransform transform, int width, int height) {
        double scaleX = transform.getScaleX();
        double scaleY = transform.getScaleY();
        double west = transform.getTranslateX();
        double north = transform.getTranslateY();
        double east = west + scaleX * width;
        double south = north + scaleY * height;
        return scaleX > 0 && scaleY < 0 && transform.getShearX() == 0 && transform.getShearY() == 0
                && west + scaleX > west && north + scaleY < north
                && Double.isFinite(east) && Double.isFinite(south)
                && Double.isFinite(1 / scaleX) && Double.isFinite(1 / scaleY);
    }

    private static void checkCrs(GeoTiffReader reader, LasHeader header, FileChannel source) throws IOException {
        var metadata = reader.getMetadata();
        var demCrs = reader.getCoordinateReferenceSystem();
        var demHorizontal = CRS.getHorizontalCRS(demCrs);
        if (metadata == null || metadata.getGeoKey(GeoTiffConstants.GTModelTypeGeoKey) == null
                || demHorizontal == null || reader.getGroundControlPoints() != null) {
            throw new IOException("Missing GeoTIFF CRS or unsupported georeferencing");
        }
        var lasCrs = GeoTiffOutput.samplingCrs(header, source);
        if (!CRS.equalsIgnoreMetadata(CRS.getHorizontalCRS(lasCrs), demHorizontal)) {
            throw new IOException("DEM horizontal CRS differs from LAS horizontal CRS");
        }
        var lasVertical = CRS.getVerticalCRS(lasCrs);
        VerticalCRS demVertical = CRS.getVerticalCRS(demCrs);
        // The GeoTools projected GeoTIFF path keeps the horizontal CRS only.
        String verticalKey = metadata.getGeoKey(GeoTiffOutput.VERTICAL_TYPE_KEY);
        if (verticalKey != null && !verticalKey.equals("0")) {
            try {
                demVertical = GeoTiffOutput.verticalCrs(Integer.parseInt(verticalKey));
            } catch (FactoryException | NumberFormatException e) {
                throw new IOException("Cannot resolve DEM vertical CRS GeoKey: " + verticalKey, e);
            }
        }
        if (lasVertical != null && demVertical != null && !CRS.equalsIgnoreMetadata(lasVertical, demVertical)) {
            throw new IOException("DEM vertical CRS differs from LAS vertical CRS");
        }
    }

    private static double[] copySamples(RenderedImage image, int width, int height, double noData) throws IOException {
        if (image.getWidth() != width || image.getHeight() != height || image.getMinX() != 0 || image.getMinY() != 0
                || image.getSampleModel().getNumBands() != 1) {
            throw new IOException("DEM band does not match the original grid");
        }
        double[] elevations = new double[width * height];
        double[] scanline = new double[Math.min(image.getTileWidth(), width)];
        int lastTileY = image.getMinTileY() + image.getNumYTiles();
        int lastTileX = image.getMinTileX() + image.getNumXTiles();
        for (int tileY = image.getMinTileY(); tileY < lastTileY; tileY++) {
            for (int tileX = image.getMinTileX(); tileX < lastTileX; tileX++) {
                checkInterrupted();
                var tile = image.getTile(tileX, tileY);
                int left = Math.max(0, tile.getMinX());
                int right = Math.min(width, tile.getMinX() + tile.getWidth());
                int bottom = Math.min(height, tile.getMinY() + tile.getHeight());
                for (int y = Math.max(0, tile.getMinY()); y < bottom; y++) {
                    tile.getSamples(left, y, right - left, 1, 0, scanline);
                    int start = y * width + left;
                    for (int i = 0; i < right - left; i++) {
                        double value = scanline[i];
                        elevations[start + i] = Double.isFinite(value) && value != noData ? value : Double.NaN;
                    }
                }
            }
        }
        checkInterrupted();
        return elevations;
    }

    private static void checkInterrupted() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException("DEM loading interrupted");
        }
    }
}
