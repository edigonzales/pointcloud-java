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
import java.util.HashSet;
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
import org.locationtech.jts.operation.buffer.BufferOp;
import org.locationtech.jts.operation.buffer.BufferParameters;
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
    private static final double EARLY_CLOSE_METRES = 0.25;
    private static final double BUFFER_CELL_METRES = 4;
    private static final int BUFFER_BLOCK_POINTS = 128;
    private static final int MAX_CANDIDATES = 1_000_000;
    private static final int MAX_REGION_CANDIDATES = 2_000_000;
    private static final double CENTIMETRE = 0.01;
    private static final int SPACING_CM = 25;
    private static final int MAX_TILES = 16;
    private static final String BEFORE_EROSION = "before-erosion.wkt";
    private static final String AFTER_EROSION = "after-erosion.wkt";
    private static final String AFTER_DILATION = "after-dilation.wkt";
    private static final GeometryFactory LV95 = new GeometryFactory(new PrecisionModel(), 2056);
    private static final Coordinate[] DISK_OFFSETS =
            LV95.createPoint(new CoordinateXY(0, 0)).buffer(DISK_METRES).getCoordinates();
    private static final Comparator<Coordinate> XY = Comparator
            .comparingDouble((Coordinate point) -> point.x)
            .thenComparingDouble(point -> point.y);

    private VegetationOutlines() {}

    enum Mode { ALL, NEIGHBORS }

    enum Thinning { GRID, SPACING, NONE }

    record Selection(Coordinate[] points, int selected, int kept, int haloKept) {}

    record SpacingSelection(long[] positions, int unique) {}

    private record SpacingMetrics(int selected, int unique, int retained, double readSeconds,
            double selectionSeconds) {}

    private record RegionSelection(SpacingSelection selection, double offsetX, double offsetY,
            int[] tileSelected, int[] tileHaloKept, SpacingMetrics metrics) {}

    private record TileMetrics(String las, double[] core, int selected, int kept, int haloKept, int thinned,
            double readSeconds, double selectionSeconds, double geometrySeconds) {}

    private record Metrics(String mode, List<TileMetrics> tiles, double area, int polygons, int vertices,
            double finishSeconds, String thinning, SpacingMetrics spacing, boolean earlyClose) {}

    private record Snapshots(Geometry beforeErosion, Geometry afterErosion, Geometry afterDilation) {}

    public static void main(String[] args) throws IOException {
        String thinning = null;
        boolean snapshots = false;
        boolean earlyClose = false;
        int prefix = 0;
        while (prefix < args.length && args[prefix].startsWith("--")) {
            String option = args[prefix++];
            if (option.equals("--snapshots") && !snapshots) {
                snapshots = true;
            } else if (option.equals("--early-close") && !earlyClose) {
                earlyClose = true;
            } else if (option.startsWith("--thinning=") && thinning == null) {
                thinning = switch (option) {
                    case "--thinning=grid" -> "grid";
                    case "--thinning=spacing" -> "spacing";
                    case "--thinning=none" -> "none";
                    default -> throw new IllegalArgumentException("Use --thinning=grid|spacing|none");
                };
            } else {
                throw new IllegalArgumentException("Unknown or repeated option " + option);
            }
        }
        if (prefix != 0) {
            args = Arrays.copyOfRange(args, prefix, args.length);
        }
        if (thinning == null) {
            thinning = "grid";
        }
        if (args.length < 11 || (args.length - 6) % 5 != 0 || (args.length - 6) / 5 > MAX_TILES) {
            throw new IllegalArgumentException("Expected [--thinning=grid|spacing|none] [--snapshots] [--early-close]"
                    + " <all|neighbors> <new-output-dir> <eval-xmin> <eval-ymin>"
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
        var evaluation = bounds(args, 2);
        var clip = LV95.toGeometry(evaluation);
        RegionSelection region = null;
        if (mode == Mode.NEIGHBORS && !thinning.equals("grid")) {
            throw new IllegalArgumentException("Thinning comparison requires all mode");
        }
        if (thinning.equals("spacing")) {
            region = prepareSpacing(args, evaluation);
        }
        var partials = new ArrayList<Geometry>();
        var tiles = new ArrayList<TileMetrics>();
        // Keep openings whole through the hull and width test, including at internal tile cuts.
        var earlyPoints = earlyClose ? new HashSet<Coordinate>() : null;
        for (int i = 6; i < args.length; i += 5) {
            var core = bounds(args, i);
            if (core.getWidth() > 1000 || core.getHeight() > 1000) {
                throw new IllegalArgumentException("Use cores no larger than 1 km on each axis");
            }
            var las = Path.of(args[i + 4]);
            long startRead = System.nanoTime();
            long startSelection;
            Selection selection;
            if (region == null) {
                var candidates = read(las, expanded(core, HALO_METRES + NEIGHBOR_METRES));
                startSelection = System.nanoTime();
                selection = thinning.equals("none")
                        ? select(candidates, core, mode, Thinning.NONE) : select(candidates, core, mode);
                candidates.clear();
            } else {
                startSelection = startRead;
                int tile = (i - 6) / 5;
                int count = region.tileSelected()[tile];
                var positions = spacingPoints(region.selection(), core, region.offsetX(), region.offsetY());
                selection = new Selection(positions, count, count, region.tileHaloKept()[tile]);
            }
            long startGeometry = System.nanoTime();
            if (earlyPoints == null) {
                partials.add(trace(selection.points()));
            } else {
                for (var point : selection.points()) {
                    if (earlyPoints.add(point) && earlyPoints.size() > MAX_REGION_CANDIDATES) {
                        throw new IllegalArgumentException("Early closing exceeds 2,000,000 distinct XY positions");
                    }
                }
            }
            long done = System.nanoTime();
            tiles.add(new TileMetrics(las.toString(),
                    new double[]{core.getMinX(), core.getMinY(), core.getMaxX(), core.getMaxY()},
                    selection.selected(), selection.kept(), selection.haloKept(), selection.points().length,
                    seconds(startRead, startSelection), seconds(startSelection, startGeometry),
                    seconds(startGeometry, done)));
        }
        long startFinish = System.nanoTime();
        if (earlyPoints != null) {
            checkInterrupted();
            var points = earlyPoints.toArray(Coordinate[]::new);
            earlyPoints.clear();
            partials.add(trace(points, true));
        }
        var outline = finish(partials, clip);
        if (!outline.isValid()) {
            throw new IllegalStateException("Final vegetation outline is invalid");
        }
        outline.normalize();
        var metrics = new Metrics(args[0], tiles, outline.getArea(),
                outline.isEmpty() ? 0 : outline.getNumGeometries(), outline.getNumPoints(),
                seconds(startFinish, System.nanoTime()), thinning, region == null ? null : region.metrics(), earlyClose);
        Snapshots recorded = null;
        if (snapshots) {
            // Preserve the accepted grid computation. Replay the two buffers on its
            // already fixed tile geometries for recording, after computing the outline.
            var dissolved = OverlayNGRobust.union(partials, clip.getFactory());
            var eroded = dissolved.buffer(-2.5);
            recorded = new Snapshots(dissolved, eroded, eroded.buffer(0.7));
        }
        publish(output, outline, metrics, recorded);
    }

    private static RegionSelection prepareSpacing(String[] args, Envelope evaluation) throws IOException {
        int tiles = (args.length - 6) / 5;
        var source = Path.of(args[10]).toAbsolutePath().normalize();
        var geometryBounds = new Envelope[tiles];
        var candidateBounds = new Envelope[tiles];
        for (int tile = 0; tile < tiles; tile++) {
            int index = 6 + tile * 5;
            var core = bounds(args, index);
            if (!evaluation.covers(core) || core.getWidth() > 1000 || core.getHeight() > 1000) {
                throw new IllegalArgumentException("Spacing cores must be within the evaluation bounds and at most 1 km");
            }
            if (!source.equals(Path.of(args[index + 4]).toAbsolutePath().normalize())) {
                throw new IllegalArgumentException("Spacing requires the same prepared LAS for all cores");
            }
            geometryBounds[tile] = expanded(core, HALO_METRES);
            candidateBounds[tile] = expanded(core, HALO_METRES + NEIGHBOR_METRES);
        }

        var selected = new int[tiles];
        var haloKept = new int[tiles];
        var regionBounds = expanded(evaluation, HALO_METRES);
        int count = 0;
        long startRead = System.nanoTime();
        LasHeader header;
        long[] positions;
        try (var reader = new LasReader(source, LasReader.DEFAULT_BATCH_SIZE)) {
            header = reader.header();
            if (header.scale(0) != CENTIMETRE || header.scale(1) != CENTIMETRE) {
                throw new IOException("Spacing requires a prepared LAS with exact centimetre XY scales");
            }
            positions = new long[(int) Math.min(MAX_REGION_CANDIDATES, header.pointCount())];
            for (var batch = reader.readBatch(); batch != null; batch = reader.readBatch()) {
                checkInterrupted();
                for (int i = 0; i < batch.size(); i++) {
                    double x = batch.x(i);
                    double y = batch.y(i);
                    if (inside(x, y, regionBounds)) {
                        if (count == MAX_REGION_CANDIDATES) {
                            throw new IOException("Spacing candidate limit exceeded in " + source);
                        }
                        positions[count++] = packPosition(batch.rawX(i), batch.rawY(i));
                    }
                    for (int tile = 0; tile < tiles; tile++) {
                        if (inside(x, y, candidateBounds[tile]) && ++selected[tile] > MAX_CANDIDATES) {
                            throw new IOException("Candidate limit exceeded in " + source + ", use a smaller core");
                        }
                        if (inside(x, y, geometryBounds[tile])) {
                            haloKept[tile]++;
                        }
                    }
                }
            }
        }
        long startSelection = System.nanoTime();
        var spacing = spacingSelection(positions, count);
        var metrics = new SpacingMetrics(count, spacing.unique(), spacing.positions().length,
                seconds(startRead, startSelection), seconds(startSelection, System.nanoTime()));
        return new RegionSelection(spacing, header.offset(0), header.offset(1), selected, haloKept, metrics);
    }

    static long packPosition(int x, int y) {
        // Signed sort of the packed keys preserves signed X, then signed Y.
        return ((long) x << 32) | Integer.toUnsignedLong(y ^ Integer.MIN_VALUE);
    }

    static SpacingSelection spacingSelection(long[] positions, int length) {
        Arrays.sort(positions, 0, length);
        var heads = new HashMap<Long, Integer>();
        var next = new int[length];
        long previous = 0;
        int unique = 0;
        int retained = 0;
        for (int i = 0; i < length; i++) {
            if ((i & 4095) == 0) {
                checkInterrupted();
            }
            long position = positions[i];
            if (i != 0 && position == previous) {
                continue;
            }
            previous = position;
            unique++;
            int x = (int) (position >> 32);
            int y = (int) position ^ Integer.MIN_VALUE;
            int cellX = Math.floorDiv(x, SPACING_CM);
            int cellY = Math.floorDiv(y, SPACING_CM);
            if (!tooClose(x, y, cellX, cellY, positions, heads, next)) {
                positions[retained] = position;
                var former = heads.put(cellKey(cellX, cellY), retained);
                next[retained++] = former == null ? -1 : former;
            }
        }
        return new SpacingSelection(Arrays.copyOf(positions, retained), unique);
    }

    private static boolean tooClose(int x, int y, int cellX, int cellY, long[] positions,
            Map<Long, Integer> heads, int[] next) {
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                int neighbor = heads.getOrDefault(cellKey(cellX + dx, cellY + dy), -1);
                while (neighbor >= 0) {
                    long point = positions[neighbor];
                    // Subtraction fits in int because candidates occupy this cell or a neighboring cell.
                    int deltaX = x - (int) (point >> 32);
                    int deltaY = y - ((int) point ^ Integer.MIN_VALUE);
                    if (deltaX * deltaX + deltaY * deltaY < SPACING_CM * SPACING_CM) {
                        return true;
                    }
                    neighbor = next[neighbor];
                }
            }
        }
        return false;
    }

    static Coordinate[] spacingPoints(SpacingSelection selection, Envelope core, double offsetX, double offsetY) {
        var bounds = expanded(core, HALO_METRES);
        var points = new ArrayList<Coordinate>();
        var positions = selection.positions();
        for (int i = 0; i < positions.length; i++) {
            if ((i & 4095) == 0) {
                checkInterrupted();
            }
            long position = positions[i];
            double x = (int) (position >> 32) * CENTIMETRE + offsetX;
            double y = ((int) position ^ Integer.MIN_VALUE) * CENTIMETRE + offsetY;
            if (inside(x, y, bounds)) {
                points.add(new CoordinateXY(x == 0 ? 0 : x, y == 0 ? 0 : y));
            }
        }
        return points.toArray(Coordinate[]::new);
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

    static Selection select(List<Coordinate> highPoints, Envelope core, Mode mode, Thinning thinning) {
        if (thinning == Thinning.GRID) {
            return select(highPoints, core, mode);
        }
        if (mode != Mode.ALL || thinning != Thinning.NONE) {
            throw new IllegalArgumentException("Unthinned selection requires all mode");
        }
        var geometryBounds = expanded(core, HALO_METRES);
        var candidateBounds = expanded(core, HALO_METRES + NEIGHBOR_METRES);
        var points = new ArrayList<Coordinate>();
        int selected = 0;
        for (int i = 0; i < highPoints.size(); i++) {
            if ((i & 4095) == 0) {
                checkInterrupted();
            }
            var point = highPoints.get(i);
            if (inside(point.x, point.y, candidateBounds)) {
                selected++;
            }
            if (inside(point.x, point.y, geometryBounds)) {
                points.add(point.x == 0 || point.y == 0
                        ? new CoordinateXY(point.x == 0 ? 0 : point.x, point.y == 0 ? 0 : point.y) : point);
            }
        }
        points.sort(XY);
        int unique = 0;
        int haloKept = points.size();
        for (int i = 0; i < haloKept; i++) {
            var point = points.get(i);
            if (unique == 0 || !point.equals2D(points.get(unique - 1))) {
                points.set(unique++, point);
            }
        }
        return new Selection(points.subList(0, unique).toArray(Coordinate[]::new), selected, selected, haloKept);
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
        return trace(points, false);
    }

    static Geometry trace(Coordinate[] points, boolean earlyClose) {
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
        var outline = backBuffer(closeOpenings(coverage), earlyClose);
        outline = fillSmallHoles(outline);
        outline = minimumArea(TopologyPreservingSimplifier.simplify(outline, 0.10), 20);
        return AffineTransformation.translationInstance(originX, originY).transform(outline);
    }

    static Geometry backBuffer(Geometry cover, boolean earlyClose) {
        if (earlyClose) {
            // A simplified edge can move a nearly 10 m opening across the later 5 m buffer test.
            var parameters = new BufferParameters();
            parameters.setSimplifyFactor(0);
            var expanded = BufferOp.bufferOp(cover, EARLY_CLOSE_METRES, parameters);
            checkInterrupted();
            return BufferOp.bufferOp(expanded, -(DISK_METRES + EARLY_CLOSE_METRES), parameters);
        }
        return cover.buffer(-DISK_METRES);
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

    private static void publish(Path output, Geometry result, Metrics metrics, Snapshots snapshots) throws IOException {
        checkInterrupted();
        Files.createDirectories(output.getParent());
        var staged = Files.createTempDirectory(output.getParent(), ".vegetation-");
        try {
            Files.writeString(staged.resolve("outline.wkt"), result.toText() + "\n");
            Files.writeString(staged.resolve("metrics.json"), Pipeline.JSON.toJson(metrics) + "\n");
            if (snapshots != null) {
                Files.writeString(staged.resolve(BEFORE_EROSION), snapshots.beforeErosion().toText() + "\n");
                Files.writeString(staged.resolve(AFTER_EROSION), snapshots.afterErosion().toText() + "\n");
                Files.writeString(staged.resolve(AFTER_DILATION), snapshots.afterDilation().toText() + "\n");
            }
            checkInterrupted();
            Files.move(staged, output, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(staged.resolve("outline.wkt"));
            Files.deleteIfExists(staged.resolve("metrics.json"));
            Files.deleteIfExists(staged.resolve(BEFORE_EROSION));
            Files.deleteIfExists(staged.resolve(AFTER_EROSION));
            Files.deleteIfExists(staged.resolve(AFTER_DILATION));
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
