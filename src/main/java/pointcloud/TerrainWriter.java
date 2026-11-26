package pointcloud;

import org.geotools.api.referencing.crs.ProjectedCRS;
import org.tinfour.common.GeometricOperations;
import org.tinfour.common.IMonitorWithCancellation;
import org.tinfour.common.Thresholds;
import org.tinfour.common.Vertex;
import org.tinfour.semivirtual.SemiVirtualIncrementalTin;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.CancellationException;

import static pointcloud.GeoTiffOutput.checkInterrupted;

/** Builds an XY Delaunay mesh over selected LAS points and samples it at pixel centres. */
final class TerrainWriter {
    private static final int MAX_POINTS = 1_000_000;

    record Grid(double originX, double originY, double resolution, int width, int height,
                double maxEdge, double nodata, boolean deflate) {
        Grid {
            GeoTiffOutput.validateGrid(originX, originY, resolution, width, height);
            if (maxEdge <= 0) {
                throw new IllegalArgumentException("max_triangle_edge_length must be positive");
            }
        }
    }

    private final GeoTiffOutput output;
    private final Grid grid;
    private final LasHeader header;
    private final List<Vertex> vertices = new ArrayList<>();
    private HashSet<Long> seen = new HashSet<>();
    private double[] heights = new double[1024];
    private int selected;
    private int anchorX;
    private int anchorY;

    TerrainWriter(GeoTiffOutput output, Grid grid, LasHeader header) throws IOException {
        if (!(output.crs instanceof ProjectedCRS)) {
            throw new IOException("DTM requires a projected horizontal LAS CRS");
        }
        this.output = output;
        this.grid = grid;
        this.header = header;
    }

    void add(PointBatch batch, int point) throws IOException {
        if ((selected & 4095) == 0) {
            checkInterrupted();
        }
        if (selected == MAX_POINTS) {
            throw new IOException("DTM supports at most " + MAX_POINTS + " selected points");
        }
        int rawX = batch.rawX(point);
        int rawY = batch.rawY(point);
        if (selected++ == 0) {
            anchorX = rawX;
            anchorY = rawY;
        }
        long xy = ((long) rawX << 32) | Integer.toUnsignedLong(rawY);
        if (seen.add(xy)) {
            int index = vertices.size();
            if (index == heights.length) {
                heights = Arrays.copyOf(heights, Math.min(MAX_POINTS, index * 2));
            }
            double x = ((long) rawX - anchorX) * header.scale(0);
            double y = ((long) rawY - anchorY) * header.scale(1);
            if (!Double.isFinite(x) || !Double.isFinite(y)) {
                throw new IOException("Nonfinite local DTM coordinates");
            }
            // Tinfour stores its Z as float. Only XY and the index are used here.
            vertices.add(new Vertex(x, y, 0, index));
            heights[index] = batch.z(point);
        }
    }

    Pipeline.TerrainStatistics prepare(boolean sourceFinished) throws IOException {
        if (!sourceFinished) {
            throw new IOException("GeoTIFF output requires a complete source scan");
        }
        checkInterrupted();
        var samples = new double[grid.width * grid.height];
        Arrays.fill(samples, grid.nodata);
        rasterize(samples);
        int covered = 0;
        for (double value : samples) {
            if (value != grid.nodata) {
                covered++;
            }
        }
        output.prepare(grid.originX, grid.originY, grid.resolution, grid.width, grid.height,
                new double[][]{samples}, List.of("elevation"), grid.nodata, grid.deflate);
        return new Pipeline.TerrainStatistics(vertices.size(), covered, samples.length - covered);
    }

    private void rasterize(double[] samples) throws IOException {
        var tin = triangulate();
        try {
            double centerX = localX(grid.originX) + grid.resolution / 2;
            double centerY = localY(grid.originY) + grid.resolution / 2;
            if (!Double.isFinite(centerX) || !Double.isFinite(centerY)) {
                throw new IOException("Nonfinite local DTM raster origin");
            }
            var geometry = new GeometricOperations(tin.getThresholds());
            int faces = 0;
            for (var triangle : tin.triangles()) {
                if ((++faces & 4095) == 0) {
                    checkInterrupted();
                }
                sampleTriangle(triangle.getVertexA(), triangle.getVertexB(), triangle.getVertexC(),
                        centerX, centerY, geometry, samples);
            }
        } finally {
            tin.dispose();
        }
    }

    SemiVirtualIncrementalTin triangulate() throws IOException {
        seen = null;
        checkInterrupted();
        double minScale = Math.min(header.scale(0), header.scale(1));
        double spacing = Math.min(0.35, minScale * (Thresholds.VERTEX_TOLERANCE_FACTOR_DEFAULT / 4));
        var tin = new SemiVirtualIncrementalTin(spacing);
        try {
            if (vertices.size() < 3 || !tin.add(vertices, TIN_MONITOR)) {
                throw new IOException("DTM requires at least three non-collinear support points");
            }
            checkInterrupted();
            int vertexCount = 0;
            for (var vertex : tin.vertices()) {
                if ((++vertexCount & 4095) == 0) {
                    checkInterrupted();
                }
            }
            if (vertexCount != vertices.size()) {
                throw new IOException("Triangulation merged distinct LAS XY coordinates");
            }
            return tin;
        } catch (CancellationException e) {
            tin.dispose();
            var interrupted = new InterruptedIOException("DTM triangulation cancelled");
            interrupted.initCause(e);
            throw interrupted;
        } catch (IOException | RuntimeException e) {
            tin.dispose();
            throw e;
        }
    }

    double localX(double x) {
        return Math.fma(-(double) anchorX, header.scale(0), x - header.offset(0));
    }

    double localY(double y) {
        return Math.fma(-(double) anchorY, header.scale(1), y - header.offset(1));
    }

    double height(Vertex vertex) {
        return heights[vertex.getIndex()];
    }

    private void sampleTriangle(Vertex a, Vertex b, Vertex c, double centerX, double centerY,
                                GeometricOperations geometry, double[] samples) throws IOException {
        double minX = Math.min(a.x, Math.min(b.x, c.x));
        double maxX = Math.max(a.x, Math.max(b.x, c.x));
        double minY = Math.min(a.y, Math.min(b.y, c.y));
        double maxY = Math.max(a.y, Math.max(b.y, c.y));
        double resolution = grid.resolution;
        if (maxX < centerX || minX > Math.fma(grid.width - 1, resolution, centerX)) {
            return;
        }
        int bottom = Math.max(0, (int) Math.ceil(Math.nextDown((minY - centerY) / resolution)));
        int top = Math.min(grid.height - 1, (int) Math.floor(Math.nextUp((maxY - centerY) / resolution)));
        if (bottom > top || (Double.isFinite(grid.maxEdge)
                && (edgeLength(a, b) > grid.maxEdge || edgeLength(b, c) > grid.maxEdge
                || edgeLength(c, a) > grid.maxEdge))) {
            return;
        }
        double area = geometry.orientation(a.x, a.y, b.x, b.y, c.x, c.y);
        if (area == 0) {
            return;
        }
        if (!Double.isFinite(area)) {
            throw new IOException("Nonfinite DTM triangle area");
        }
        double za = height(a);
        double zb = height(b);
        double zc = height(c);
        double db = zb - za;
        double dc = zc - za;
        for (int row = bottom; row <= top; row++) {
            if ((row & 1023) == 0) {
                checkInterrupted();
            }
            double y = Math.fma(row, resolution, centerY);
            double ab = crossing(a, b, y);
            double bc = crossing(b, c, y);
            double ca = crossing(c, a, y);
            double left = Math.min(Math.min(ab, bc), ca);
            double right = Math.max(Math.max(validCrossing(ab), validCrossing(bc)), validCrossing(ca));
            // Round the span outwards. Orientation decides containment at its edges.
            int first = Math.max(0, (int) Math.floor((left - centerX) / resolution));
            int last = Math.min(grid.width - 1, (int) Math.ceil((right - centerX) / resolution));
            int start = (grid.height - 1 - row) * grid.width;
            for (int col = first; col <= last; col++) {
                int cell = start + col;
                if ((col & 4095) == 0) {
                    checkInterrupted();
                }
                if (samples[cell] != grid.nodata) {
                    continue;
                }
                double x = Math.fma(col, resolution, centerX);
                double wa = geometry.orientation(b.x, b.y, c.x, c.y, x, y) / area;
                double wb = geometry.orientation(c.x, c.y, a.x, a.y, x, y) / area;
                double wc = geometry.orientation(a.x, a.y, b.x, b.y, x, y) / area;
                if (wa >= 0 && wb >= 0 && wc >= 0) {
                    double sum = wa + wb + wc;
                    double height = Double.isFinite(db) && Double.isFinite(dc)
                            ? Math.fma(wc / sum, dc, Math.fma(wb / sum, db, za))
                            : (wa / sum) * za + (wb / sum) * zb + (wc / sum) * zc;
                    if (!Double.isFinite(height) || height == grid.nodata) {
                        throw new IOException("Nonfinite DTM height or nodata collision at raster cell " + cell);
                    }
                    samples[cell] = height;
                }
            }
        }
    }

    private static double crossing(Vertex a, Vertex b, double y) {
        if (a.y == b.y || y < Math.min(a.y, b.y) || y > Math.max(a.y, b.y)) {
            return Double.POSITIVE_INFINITY;
        }
        return Math.fma((y - a.y) / (b.y - a.y), b.x - a.x, a.x);
    }

    private static double validCrossing(double value) {
        return value == Double.POSITIVE_INFINITY ? Double.NEGATIVE_INFINITY : value;
    }

    private static double edgeLength(Vertex a, Vertex b) {
        return Math.hypot(a.x - b.x, a.y - b.y);
    }

    private static final IMonitorWithCancellation TIN_MONITOR = new IMonitorWithCancellation() {
        @Override public int getReportingIntervalInPercent() { return 1; }
        @Override public boolean isCanceled() { return Thread.currentThread().isInterrupted(); }
        @Override public void reportProgress(int progress) {
            // The semi-virtual bulk insert reports progress but does not poll isCanceled().
            if (isCanceled()) {
                throw new CancellationException();
            }
        }
        @Override public void reportDone() {}
        @Override public void postMessage(String message) {}
    };
}
