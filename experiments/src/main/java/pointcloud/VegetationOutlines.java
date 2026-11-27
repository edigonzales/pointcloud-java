package pointcloud;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CancellationException;
import org.locationtech.jts.algorithm.hull.ConcaveHullOfPolygons;
import org.locationtech.jts.densify.Densifier;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.CoordinateXY;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryCollection;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.geom.PrecisionModel;
import org.locationtech.jts.geom.util.AffineTransformation;
import org.locationtech.jts.operation.overlayng.OverlayNG;
import org.locationtech.jts.operation.overlayng.OverlayNGRobust;
import org.locationtech.jts.simplify.TopologyPreservingSimplifier;

/** Experimental polygonization of LAS records already selected by class and height above ground. */
public final class VegetationOutlines {
    private static final double HALO_METRES = 10;
    private static final double NEIGHBOR_METRES = 1;
    private static final double NEIGHBOR_CELL_METRES = 0.5;
    private static final double GRID_METRES = 0.25;
    private static final double DISK_METRES = 1.4;
    private static final double BUFFER_CELL_METRES = 4;
    private static final int BUFFER_BLOCK_POINTS = 128;
    private static final int MAX_CANDIDATES = 1_000_000;
    private static final int MAX_TILES = 16;
    private static final GeometryFactory LV95 = new GeometryFactory(new PrecisionModel(), 2056);
    private static final Coordinate[] DISK_OFFSETS =
            LV95.createPoint(new CoordinateXY(0, 0)).buffer(DISK_METRES).getCoordinates();
    private static final Comparator<Coordinate> XY = Comparator
            .comparingDouble((Coordinate point) -> point.x)
            .thenComparingDouble(point -> point.y);

    private VegetationOutlines() {}

    enum Mode { ALL, NEIGHBORS }

    record Selection(Coordinate[] points, int selected, int kept, int haloKept) {}

    private record TileMetrics(String las, double[] core, int selected, int kept, int haloKept, int thinned,
            double readSeconds, double selectionSeconds, double geometrySeconds) {}

    private record Metrics(String mode, List<TileMetrics> tiles, double area, int polygons, int vertices,
            double finishSeconds) {}

    public static void main(String[] args) throws IOException {
        if (args.length < 11 || (args.length - 6) % 5 != 0 || (args.length - 6) / 5 > MAX_TILES) {
            throw new IllegalArgumentException("Expected <all|neighbors> <new-output-dir> <eval-xmin> <eval-ymin>"
                    + " <eval-xmax> <eval-ymax> followed by up to " + MAX_TILES
                    + " groups of <xmin> <ymin> <xmax> <ymax> <prepared.las>");
        }
        var mode = switch (args[0]) {
            case "all" -> Mode.ALL;
            case "neighbors" -> Mode.NEIGHBORS;
            default -> throw new IllegalArgumentException("Expected all or neighbors");
        };
        var output = Path.of(args[1]).toAbsolutePath().normalize();
        if (Files.exists(output, LinkOption.NOFOLLOW_LINKS)) {
            throw new FileAlreadyExistsException(output.toString());
        }
        var clip = LV95.toGeometry(bounds(args, 2));
        var partials = new ArrayList<Geometry>();
        var tiles = new ArrayList<TileMetrics>();
        for (int i = 6; i < args.length; i += 5) {
            var core = bounds(args, i);
            if (core.getWidth() > 1000 || core.getHeight() > 1000) {
                throw new IllegalArgumentException("Use cores no larger than 1 km on each axis");
            }
            var las = Path.of(args[i + 4]);
            long startRead = System.nanoTime();
            var candidates = read(las, expanded(core, HALO_METRES + NEIGHBOR_METRES));
            long startSelection = System.nanoTime();
            var selection = select(candidates, core, mode);
            candidates.clear();
            long startGeometry = System.nanoTime();
            var partial = trace(selection.points());
            long done = System.nanoTime();
            partials.add(partial);
            tiles.add(new TileMetrics(las.toString(),
                    new double[]{core.getMinX(), core.getMinY(), core.getMaxX(), core.getMaxY()},
                    selection.selected(), selection.kept(), selection.haloKept(), selection.points().length,
                    seconds(startRead, startSelection), seconds(startSelection, startGeometry),
                    seconds(startGeometry, done)));
        }
        long startFinish = System.nanoTime();
        var outline = finish(partials, clip);
        if (!outline.isValid()) {
            throw new IllegalStateException("Final vegetation outline is invalid");
        }
        outline.normalize();
        var metrics = new Metrics(args[0], tiles, outline.getArea(),
                outline.isEmpty() ? 0 : outline.getNumGeometries(), outline.getNumPoints(),
                seconds(startFinish, System.nanoTime()));
        publish(output, outline, metrics);
    }

    private static Envelope bounds(String[] args, int index) {
        double minX = Double.parseDouble(args[index]);
        double minY = Double.parseDouble(args[index + 1]);
        double maxX = Double.parseDouble(args[index + 2]);
        double maxY = Double.parseDouble(args[index + 3]);
        if (!(Double.isFinite(minX) && Double.isFinite(minY) && Double.isFinite(maxX)
                && Double.isFinite(maxY) && minX < maxX && minY < maxY)) {
            throw new IllegalArgumentException("Expected finite increasing bounds");
        }
        return new Envelope(minX, maxX, minY, maxY);
    }

    private static List<Coordinate> read(Path las, Envelope bounds) throws IOException {
        var candidates = new ArrayList<Coordinate>();
        try (var reader = new LasReader(las, LasReader.DEFAULT_BATCH_SIZE)) {
            for (var batch = reader.readBatch(); batch != null; batch = reader.readBatch()) {
                for (int i = 0; i < batch.size(); i++) {
                    double x = batch.x(i);
                    double y = batch.y(i);
                    if (!Double.isFinite(x) || !Double.isFinite(y)) {
                        throw new IOException("Nonfinite coordinate in " + las);
                    }
                    if (inside(x, y, bounds)) {
                        if (candidates.size() == MAX_CANDIDATES) {
                            throw new IOException("Candidate limit exceeded in " + las + ", use a smaller core");
                        }
                        candidates.add(new CoordinateXY(x, y));
                    }
                }
            }
        }
        return candidates;
    }

    static Selection select(List<Coordinate> highPoints, Envelope core, Mode mode) {
        var neighborBounds = expanded(core, HALO_METRES + NEIGHBOR_METRES);
        var geometryBounds = expanded(core, HALO_METRES);
        var points = highPoints.stream().filter(point -> inside(point.x, point.y, neighborBounds))
                .map(point -> point.x == 0 || point.y == 0
                        ? new CoordinateXY(point.x == 0 ? 0 : point.x, point.y == 0 ? 0 : point.y) : point)
                .toArray(Coordinate[]::new);
        int selected = points.length;
        Arrays.sort(points, XY);

        // Keep run lengths for record counts while the neighbour index only sees distinct positions.
        var duplicates = new int[selected];
        int unique = 0;
        for (int first = 0; first < selected;) {
            var point = points[first];
            int end = first + 1;
            while (end < selected && point.equals2D(points[end])) {
                end++;
            }
            points[unique] = point;
            duplicates[unique++] = end - first;
            first = end;
        }

        var heads = new HashMap<Long, Integer>();
        var next = new int[mode == Mode.NEIGHBORS ? unique : 0];
        if (mode == Mode.NEIGHBORS) {
            for (int i = 0; i < unique; i++) {
                var point = points[i];
                long cell = cellKey(index(point.x, NEIGHBOR_CELL_METRES), index(point.y, NEIGHBOR_CELL_METRES));
                var previous = heads.put(cell, i);
                next[i] = previous == null ? -1 : previous;
            }
        }

        var representatives = new HashMap<Long, Coordinate>();
        int kept = 0;
        int haloKept = 0;
        for (int i = 0; i < unique; i++) {
            if ((i & 4095) == 0) {
                checkInterrupted();
            }
            if (mode == Mode.NEIGHBORS && !hasNeighbors(points, i, heads, next)) {
                continue;
            }
            kept += duplicates[i];
            var point = points[i];
            if (!inside(point.x, point.y, geometryBounds)) {
                continue;
            }
            haloKept += duplicates[i];
            int cellX = index(point.x, GRID_METRES);
            int cellY = index(point.y, GRID_METRES);
            long cell = cellKey(cellX, cellY);
            var previous = representatives.get(cell);
            // XY sort already puts the lower X, then Y, first for an exact distance tie.
            if (previous == null || centerDistanceSquared(point, cellX, cellY)
                    < centerDistanceSquared(previous, cellX, cellY)) {
                representatives.put(cell, point);
            }
        }
        var thinned = representatives.values().toArray(Coordinate[]::new);
        Arrays.sort(thinned, XY);
        return new Selection(thinned, selected, kept, haloKept);
    }

    private static boolean hasNeighbors(Coordinate[] points, int index, Map<Long, Integer> heads, int[] next) {
        var point = points[index];
        int x = index(point.x, NEIGHBOR_CELL_METRES);
        int y = index(point.y, NEIGHBOR_CELL_METRES);
        int head = heads.get(cellKey(x, y));
        // The diagonal of a half-metre cell is shorter than the search radius.
        // Answer dense cells in constant time even when nearby cells have many points.
        if (next[head] >= 0 && next[next[head]] >= 0) {
            return true;
        }
        int count = 0;
        for (int dy = -2; dy <= 2; dy++) {
            for (int dx = -2; dx <= 2; dx++) {
                int neighbor = heads.getOrDefault(cellKey(x + dx, y + dy), -1);
                while (neighbor >= 0) {
                    var candidate = points[neighbor];
                    double offsetX = candidate.x - point.x;
                    double offsetY = candidate.y - point.y;
                    if (neighbor != index && offsetX * offsetX + offsetY * offsetY
                            <= NEIGHBOR_METRES * NEIGHBOR_METRES && ++count == 2) {
                        return true;
                    }
                    neighbor = next[neighbor];
                }
            }
        }
        return false;
    }

    private static int index(double coordinate, double spacing) {
        double index = Math.floor(coordinate / spacing);
        if (index < Integer.MIN_VALUE + 2.0 || index > Integer.MAX_VALUE - 2.0) {
            throw new IllegalArgumentException("Coordinate outside supported grid");
        }
        return (int) index;
    }

    private static long cellKey(int x, int y) {
        return ((long) x << 32) | Integer.toUnsignedLong(y);
    }

    private static double centerDistanceSquared(Coordinate point, int x, int y) {
        double dx = point.x - (x + 0.5) * GRID_METRES;
        double dy = point.y - (y + 0.5) * GRID_METRES;
        return dx * dx + dy * dy;
    }

    private static boolean inside(double x, double y, Envelope box) {
        return x >= box.getMinX() && x < box.getMaxX() && y >= box.getMinY() && y < box.getMaxY();
    }

    private static Envelope expanded(Envelope box, double width) {
        var expanded = new Envelope(box);
        expanded.expandBy(width);
        return expanded;
    }

    static Geometry trace(Coordinate[] points) {
        if (points.length == 0) {
            return LV95.createPolygon();
        }
        // Translate to metre-sized local coordinates so the strict gap limit is not rounded at LV95 magnitude.
        var ordered = points.clone();
        Arrays.sort(ordered, XY);
        double originX = Math.floor(ordered[0].x);
        double originY = Math.floor(ordered[0].y);
        var groups = new TreeMap<Long, List<Coordinate>>();
        for (var point : ordered) {
            long cell = cellKey(index(point.x, BUFFER_CELL_METRES), index(point.y, BUFFER_CELL_METRES));
            groups.computeIfAbsent(cell, ignored -> new ArrayList<>()).add(point);
        }
        // A dense MultiPoint buffer nodes all overlapping circles at once. Dissolving bounded
        // spatial groups first lets the polygon union discard covered edges at each level.
        var blocks = new ArrayList<Geometry>();
        for (var group : groups.values()) {
            for (int offset = 0; offset < group.size(); offset += BUFFER_BLOCK_POINTS) {
                checkInterrupted();
                int end = Math.min(offset + BUFFER_BLOCK_POINTS, group.size());
                var disks = new ArrayList<Geometry>(end - offset);
                for (int i = offset; i < end; i++) {
                    var point = group.get(i);
                    disks.add(disk(point.x - originX, point.y - originY));
                }
                blocks.add(OverlayNGRobust.union(disks, LV95));
            }
        }
        var coverage = OverlayNGRobust.union(blocks, LV95);
        var outline = closeOpenings(coverage).buffer(-DISK_METRES);
        outline = fillSmallHoles(outline);
        outline = minimumArea(TopologyPreservingSimplifier.simplify(outline, 0.10), 20);
        return AffineTransformation.translationInstance(originX, originY).transform(outline);
    }

    private static Geometry disk(double x, double y) {
        var ring = new Coordinate[DISK_OFFSETS.length];
        for (int i = 0; i < ring.length; i++) {
            var offset = DISK_OFFSETS[i];
            ring[i] = new CoordinateXY(x + offset.x, y + offset.y);
        }
        return LV95.createPolygon(ring);
    }

    static Geometry closeOpenings(Geometry cover) {
        checkInterrupted();
        if (cover.isEmpty()) {
            return cover;
        }
        var polygons = new ArrayList<Polygon>();
        addLargePolygons(cover, 0, polygons);
        if (polygons.stream().noneMatch(polygon -> polygon.getNumInteriorRing() > 0)) {
            return hull(cover);
        }
        // JTS triangulates the space between exterior rings. An island inside another
        // polygon's hole yields nested rings in that triangulation frame. Temporarily
        // dissolve the shells and then restore the original empty space, retaining islands.
        var factory = cover.getFactory();
        var shells = OverlayNGRobust.union(
                polygons.stream().<Geometry>map(polygon -> factory.createPolygon(polygon.getExteriorRing())).toList(),
                factory);
        var holes = OverlayNGRobust.overlay(shells, cover, OverlayNG.DIFFERENCE);
        return OverlayNGRobust.overlay(hull(shells), holes, OverlayNG.DIFFERENCE);
    }

    private static Geometry hull(Geometry cover) {
        var densified = Densifier.densify(cover, GRID_METRES);
        // The proxy uses edges between polygon vertices. Densification makes long aligned boundaries comparable.
        return ConcaveHullOfPolygons.concaveHullByLength(densified, Math.nextDown(1.5), false, true);
    }

    static Geometry fillSmallHoles(Geometry cover) {
        if (cover.isEmpty()) {
            return cover;
        }
        var envelope = cover.getEnvelopeInternal();
        var frame = cover.getFactory().toGeometry(expanded(envelope, 1));
        var complement = OverlayNGRobust.overlay(frame, cover, OverlayNG.DIFFERENCE);
        var filled = new ArrayList<Geometry>();
        filled.add(cover);
        for (int i = 0; i < complement.getNumGeometries(); i++) {
            checkInterrupted();
            var hole = complement.getGeometryN(i);
            // The outer component reaches the enclosing frame. Bounded components may contain vegetation islands.
            if (envelope.covers(hole.getEnvelopeInternal()) && hole.buffer(-5).isEmpty()) {
                filled.add(hole);
            }
        }
        return filled.size() == 1 ? cover : OverlayNGRobust.union(filled, cover.getFactory());
    }

    static Geometry finish(List<Geometry> partials, Geometry clip) {
        checkInterrupted();
        var dissolved = OverlayNGRobust.union(partials, clip.getFactory());
        var insideEdge = dissolved.buffer(-2.5).buffer(0.7);
        var simplified = TopologyPreservingSimplifier.simplify(insideEdge, 0.15);
        var clipped = OverlayNGRobust.overlay(simplified, clip, OverlayNG.INTERSECTION);
        return minimumArea(clipped, 40);
    }

    static Geometry minimumArea(Geometry cover, double threshold) {
        var polygons = new ArrayList<Polygon>();
        addLargePolygons(cover, threshold, polygons);
        var factory = cover.getFactory();
        if (polygons.isEmpty()) {
            return factory.createPolygon();
        }
        if (polygons.size() == 1) {
            return polygons.getFirst();
        }
        return factory.createMultiPolygon(polygons.toArray(Polygon[]::new));
    }

    private static void addLargePolygons(Geometry geometry, double threshold, List<Polygon> polygons) {
        if (geometry instanceof Polygon polygon && !polygon.isEmpty() && polygon.getArea() >= threshold) {
            polygons.add(polygon);
        } else if (geometry instanceof GeometryCollection collection) {
            for (int i = 0; i < collection.getNumGeometries(); i++) {
                addLargePolygons(collection.getGeometryN(i), threshold, polygons);
            }
        }
    }

    private static void publish(Path output, Geometry result, Metrics metrics) throws IOException {
        checkInterrupted();
        Files.createDirectories(output.getParent());
        var staged = Files.createTempDirectory(output.getParent(), ".vegetation-");
        try {
            Files.writeString(staged.resolve("outline.wkt"), result.toText() + "\n");
            Files.writeString(staged.resolve("metrics.json"), Pipeline.JSON.toJson(metrics) + "\n");
            checkInterrupted();
            Files.move(staged, output, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(staged.resolve("outline.wkt"));
            Files.deleteIfExists(staged.resolve("metrics.json"));
            Files.deleteIfExists(staged);
        }
    }

    private static void checkInterrupted() {
        if (Thread.currentThread().isInterrupted()) {
            throw new CancellationException("Vegetation processing cancelled");
        }
    }

    private static double seconds(long start, long end) {
        return (end - start) / 1_000_000_000.0;
    }
}
