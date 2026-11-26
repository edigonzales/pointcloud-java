package pointcloud;

import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.index.strtree.AbstractNode;
import org.locationtech.jts.index.strtree.Boundable;
import org.locationtech.jts.index.strtree.ItemBoundable;
import org.locationtech.jts.index.strtree.STRtree;
import org.tinfour.common.GeometricOperations;
import org.tinfour.common.IMonitorWithCancellation;
import org.tinfour.common.Thresholds;
import org.tinfour.common.Vertex;
import org.tinfour.semivirtual.SemiVirtualIncrementalTin;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;
import java.util.concurrent.CancellationException;

import static pointcloud.GeoTiffOutput.checkInterrupted;

/** Builds an XY Delaunay mesh over selected LAS points and samples it at pixel centres. */
final class TerrainWriter {
    private static final int MAX_POINTS = 1_000_000;

    record Grid(double originX, double originY, double resolution, int width, int height,
                double maxEdge, double nodata, boolean deflate, boolean fillNearest) {
        Grid(double originX, double originY, double resolution, int width, int height,
             double maxEdge, double nodata, boolean deflate) {
            this(originX, originY, resolution, width, height, maxEdge, nodata, deflate, false);
        }

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
    private LongKeys seen = new LongKeys();
    private double[] heights = new double[1024];
    private int[] rawXs;
    private int[] rawYs;
    private int minRawX = Integer.MAX_VALUE;
    private int maxRawX = Integer.MIN_VALUE;
    private int minRawY = Integer.MAX_VALUE;
    private int maxRawY = Integer.MIN_VALUE;
    private int selected;
    private int anchorX;
    private int anchorY;

    TerrainWriter(GeoTiffOutput output, Grid grid, LasHeader header) throws IOException {
        if (!output.isProjected()) {
            throw new IOException("DTM requires a projected horizontal LAS CRS");
        }
        this.output = output;
        this.grid = grid;
        this.header = header;
        if (RawDelaunay.supports(header.scale(0), header.scale(1), 0, 0)) {
            rawXs = new int[heights.length];
            rawYs = new int[heights.length];
        }
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
        if (seen.add(mixXY(xy))) {
            int index = vertices.size();
            if (index == heights.length) {
                heights = Arrays.copyOf(heights, Math.min(MAX_POINTS, index * 2));
            }
            rememberRawCoordinates(index, rawX, rawY);
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

    private void rememberRawCoordinates(int index, int x, int y) {
        if (rawXs == null) {
            return;
        }
        minRawX = Math.min(minRawX, x);
        maxRawX = Math.max(maxRawX, x);
        minRawY = Math.min(minRawY, y);
        maxRawY = Math.max(maxRawY, y);
        if ((long) maxRawX - minRawX > RawDelaunay.MAX_SPAN || (long) maxRawY - minRawY > RawDelaunay.MAX_SPAN) {
            rawXs = null;
            rawYs = null;
            return;
        }
        if (index == rawXs.length) {
            rawXs = Arrays.copyOf(rawXs, heights.length);
            rawYs = Arrays.copyOf(rawYs, heights.length);
        }
        rawXs[index] = x;
        rawYs[index] = y;
    }

    // SplitMix64's bijective finalizer mixes both packed coordinate halves before hashing.
    private static long mixXY(long value) {
        value = (value ^ (value >>> 30)) * 0xbf58476d1ce4e5b9L;
        value = (value ^ (value >>> 27)) * 0x94d049bb133111ebL;
        return value ^ (value >>> 31);
    }

    Pipeline.TerrainStatistics prepare(boolean sourceFinished) throws IOException {
        if (!sourceFinished) {
            throw new IOException("GeoTIFF output requires a complete source scan");
        }
        checkInterrupted();
        if (grid.fillNearest && vertices.isEmpty()) {
            throw new IOException("fill_nodata requires at least one selected support point");
        }
        double nodata = grid.fillNearest ? Double.NaN : grid.nodata;
        var samples = new double[grid.width * grid.height];
        Arrays.fill(samples, nodata);
        rasterize(samples);
        Integer filledNearest = null;
        int covered = 0;
        if (grid.fillNearest) {
            filledNearest = fillNearest(samples);
            covered = samples.length;
        } else {
            for (double value : samples) {
                if (value != nodata) {
                    covered++;
                }
            }
        }
        output.prepare(grid.originX, grid.originY, grid.resolution, grid.width, grid.height,
                new double[][]{samples}, List.of("elevation"), nodata, grid.deflate);
        return new Pipeline.TerrainStatistics(vertices.size(), covered, samples.length - covered, filledNearest);
    }

    private void rasterize(double[] samples) throws IOException {
        double centerX = localX(grid.originX) + grid.resolution / 2;
        double centerY = localY(grid.originY) + grid.resolution / 2;
        if (!Double.isFinite(centerX) || !Double.isFinite(centerY)) {
            throw new IOException("Nonfinite local DTM raster origin");
        }
        var visited = new BitSet(vertices.size());
        var raw = triangulateRaw();
        if (raw != null) {
            rawXs = null;
            rawYs = null;
            if (raw.edges == 0) {
                return;
            }
            var geometry = new GeometricOperations(new Thresholds(tinSpacing()));
            for (int edge = 0; edge < raw.edges; edge += 3) {
                if ((edge & 4095) == 0) {
                    checkInterrupted();
                }
                rasterizeFace(visited, vertex(raw.triangles[edge]), vertex(raw.triangles[edge + 1]),
                        vertex(raw.triangles[edge + 2]), centerX, centerY, geometry, samples);
            }
        } else {
            var tin = triangulate(false);
            if (tin == null) {
                return;
            }
            try {
                var geometry = new GeometricOperations(tin.getThresholds());
                int faces = 0;
                for (var triangle : tin.triangles()) {
                    if ((++faces & 4095) == 0) {
                        checkInterrupted();
                    }
                    rasterizeFace(visited, triangle.getVertexA(), triangle.getVertexB(), triangle.getVertexC(),
                            centerX, centerY, geometry, samples);
                }
            } finally {
                tin.dispose();
            }
        }
        if (visited.cardinality() != vertices.size()) {
            throw new IOException("Triangulation merged distinct LAS XY coordinates");
        }
    }

    private void rasterizeFace(BitSet visited, Vertex a, Vertex b, Vertex c, double centerX, double centerY,
                               GeometricOperations geometry, double[] samples) throws IOException {
        markSupport(visited, a);
        markSupport(visited, b);
        markSupport(visited, c);
        sampleTriangle(a, b, c, centerX, centerY, geometry, samples);
    }

    RawDelaunay triangulateRaw() throws IOException {
        seen = null;
        return rawXs == null ? null : new RawDelaunay(rawXs, rawYs, vertices.size(), grid.fillNearest);
    }

    Vertex vertex(int index) {
        return vertices.get(index);
    }

    private int fillNearest(double[] samples) throws IOException {
        NearestGround nearest = null;
        double centerX = localX(grid.originX) + grid.resolution / 2;
        double centerY = localY(grid.originY) + grid.resolution / 2;
        int filled = 0;
        for (int cell = 0; cell < samples.length; cell++) {
            if ((cell & 4095) == 0) {
                checkInterrupted();
            }
            if (Double.isNaN(samples[cell])) {
                double x = Math.fma(cell % grid.width, grid.resolution, centerX);
                double y = Math.fma(grid.height - 1 - cell / grid.width, grid.resolution, centerY);
                if (!Double.isFinite(x) || !Double.isFinite(y)) {
                    throw new IOException("Nonfinite local DTM raster cell " + cell);
                }
                if (nearest == null) {
                    nearest = new NearestGround(vertices);
                }
                samples[cell] = height(nearest.find(x, y));
                filled++;
            }
            if (!Double.isFinite(samples[cell])) {
                throw new IOException("Nonfinite DTM height at raster cell " + cell);
            }
        }
        return filled;
    }

    /** Searches the packed tree, preserving file order when two support points are equally near. */
    private static final class NearestGround {
        private static final double SAFE_COORDINATE = Double.MAX_VALUE / 4;
        private final AbstractNode root;
        private final boolean wideIndex;
        private Vertex best;
        private double bestDistance;
        private double x;
        private double y;
        private double scale;
        private int visited;

        NearestGround(List<Vertex> vertices) throws IOException {
            var index = new STRtree();
            int count = 0;
            for (var vertex : vertices) {
                if ((count++ & 4095) == 0) {
                    checkInterrupted();
                }
                index.insert(new Envelope(vertex.x, vertex.x, vertex.y, vertex.y), vertex);
            }
            root = index.getRoot();
            checkInterrupted();
            var bounds = (Envelope) root.getBounds();
            wideIndex = Math.max(Math.abs(bounds.getMinX()), Math.abs(bounds.getMaxX())) > SAFE_COORDINATE
                    || Math.max(Math.abs(bounds.getMinY()), Math.abs(bounds.getMaxY())) > SAFE_COORDINATE;
        }

        Vertex find(double centerX, double centerY) throws IOException {
            // A common power-of-two scale keeps subtraction and hypot finite for extreme inputs.
            scale = wideIndex || Math.abs(centerX) > SAFE_COORDINATE || Math.abs(centerY) > SAFE_COORDINATE ? 0.25 : 1;
            x = centerX * scale;
            y = centerY * scale;
            // Nearby cells often share a donor. Use the previous one as an initial upper bound.
            bestDistance = best == null ? Double.POSITIVE_INFINITY : Math.hypot(best.x * scale - x, best.y * scale - y);
            search(root);
            return best;
        }

        private void search(Boundable boundable) throws IOException {
            if ((++visited & 4095) == 0) {
                checkInterrupted();
            }
            var bounds = (Envelope) boundable.getBounds();
            double dx = Math.max(0, Math.max(bounds.getMinX() * scale - x, x - bounds.getMaxX() * scale));
            double dy = Math.max(0, Math.max(bounds.getMinY() * scale - y, y - bounds.getMaxY() * scale));
            double distance = Math.hypot(dx, dy);
            if (distance > bestDistance) {
                return;
            }
            if (boundable instanceof ItemBoundable item) {
                var vertex = (Vertex) item.getItem();
                if (best == null || distance < bestDistance || vertex.getIndex() < best.getIndex()) {
                    best = vertex;
                    bestDistance = distance;
                }
            } else {
                for (Object child : ((AbstractNode) boundable).getChildBoundables()) {
                    search((Boundable) child);
                }
            }
        }
    }

    private void markSupport(BitSet visited, Vertex vertex) throws IOException {
        int index = vertex.getIndex();
        if (index < 0 || index >= vertices.size() || vertices.get(index) != vertex) {
            throw new IOException("Triangulation merged distinct LAS XY coordinates");
        }
        visited.set(index);
    }

    SemiVirtualIncrementalTin triangulate() throws IOException {
        return triangulate(true);
    }

    private SemiVirtualIncrementalTin triangulate(boolean validateVertices) throws IOException {
        seen = null;
        checkInterrupted();
        var tin = new SemiVirtualIncrementalTin(tinSpacing());
        try {
            if (vertices.size() < 3 || !tin.add(vertices, TIN_MONITOR)) {
                checkInterrupted();
                if (grid.fillNearest && !validateVertices) {
                    tin.dispose();
                    return null;
                }
                throw new IOException("DTM requires at least three non-collinear support points");
            }
            checkInterrupted();
            if (validateVertices) {
                int vertexCount = 0;
                for (var vertex : tin.vertices()) {
                    if ((++vertexCount & 4095) == 0) {
                        checkInterrupted();
                    }
                }
                if (vertexCount != vertices.size()) {
                    throw new IOException("Triangulation merged distinct LAS XY coordinates");
                }
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

    double tinSpacing() {
        double minScale = Math.min(header.scale(0), header.scale(1));
        return Math.min(0.35, minScale * (Thresholds.VERTEX_TOLERANCE_FACTOR_DEFAULT / 4));
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
                if (grid.fillNearest ? !Double.isNaN(samples[cell]) : samples[cell] != grid.nodata) {
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
                    if (!Double.isFinite(height) || (!grid.fillNearest && height == grid.nodata)) {
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
            // The bulk insert reports progress but does not poll isCanceled().
            if (isCanceled()) {
                throw new CancellationException();
            }
        }
        @Override public void reportDone() {}
        @Override public void postMessage(String message) {}
    };

    /** Stores the mixed key itself so resizing can reuse its bucket hash. */
    static final class LongKeys {
        private long[] table = new long[16];
        private int count;
        private boolean hasZero;

        boolean add(long key) {
            // The mix is bijective and maps zero to zero. Only the original zero needs a separate slot.
            if (key == 0) {
                if (hasZero) {
                    return false;
                }
                hasZero = true;
                return true;
            }
            int slot = findSlot(table, key);
            if (table[slot] == key) {
                return false;
            }
            if (count == table.length / 2) {
                var expanded = new long[table.length * 2];
                for (long existing : table) {
                    if (existing != 0) {
                        expanded[findSlot(expanded, existing)] = existing;
                    }
                }
                table = expanded;
                slot = findSlot(table, key);
            }
            table[slot] = key;
            count++;
            return true;
        }

        private static int findSlot(long[] slots, long key) {
            int mask = slots.length - 1;
            int slot = (int) key & mask;
            while (slots[slot] != 0 && slots[slot] != key) {
                slot = (slot + 1) & mask;
            }
            return slot;
        }
    }
}
