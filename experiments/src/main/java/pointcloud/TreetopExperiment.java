package pointcloud;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/** Compares tree-peak detectors on LV95 canopy-height and absolute vegetation-height grids. */
public final class TreetopExperiment {
    private static final double MIN_HEIGHT = 5;
    private static final double MAX_RADIUS = 4;

    private TreetopExperiment() {}

    public static void main(String[] args) throws IOException {
        if (args.length < 9) {
            throw new IllegalArgumentException("Use LAS DEM OUT_DIR ORIGIN_X ORIGIN_Y WIDTH HEIGHT RESOLUTION"
                    + " WORKERS [--method METHOD | --prominence] [--rasters]");
        }
        Path input = Path.of(args[0]);
        Path demPath = Path.of(args[1]);
        Path output = Path.of(args[2]);
        int workers = Integer.parseInt(args[8]);
        if (workers != 1 && workers != 4) {
            throw new IllegalArgumentException("Use 1 or 4 LAZ reader workers");
        }
        Variant chosen = null;
        boolean prominence = false;
        boolean rasters = false;
        for (int i = 9; i < args.length; i++) {
            if (args[i].equals("--rasters") && !rasters) {
                rasters = true;
            } else if (args[i].equals("--prominence") && !prominence && chosen == null) {
                prominence = true;
            } else if (args[i].equals("--method") && chosen == null && !prominence && i + 1 < args.length) {
                chosen = Variant.parse(args[++i]);
            } else {
                throw new IllegalArgumentException("Unsupported or duplicate argument: " + args[i]);
            }
        }
        var variants = prominence ? Variant.PROMINENCE : chosen == null ? Variant.ALL : List.of(chosen);
        Grid grid = Grid.fromCore(Double.parseDouble(args[3]), Double.parseDouble(args[4]),
                Integer.parseInt(args[5]), Integer.parseInt(args[6]), Double.parseDouble(args[7]), variants);
        run(input, demPath, output, grid, workers, variants, rasters);
    }

    private static void run(Path input, Path demPath, Path output, Grid grid, int workers,
                            List<Variant> variants, boolean rasters) throws IOException {
        var timings = new ArrayList<String>();
        try (var reader = ParallelLazReader.open(input, LasReader.DEFAULT_BATCH_SIZE, workers)) {
            long start = System.nanoTime();
            HagDem dem = HagDem.load(demPath, 1, reader.header(), reader.channel());
            timings.add("dem,," + elapsed(start));

            start = System.nanoTime();
            Surface chm = rasters || variants.stream().anyMatch(v -> v.kind == Kind.CHM)
                    ? new Surface(grid.cells(), Kind.CHM) : null;
            Surface dsm = rasters || variants.stream().anyMatch(v -> v.kind == Kind.DSM)
                    ? new Surface(grid.cells(), Kind.DSM) : null;
            long pointsBinned = accumulate(reader, dem, grid, chm, dsm);
            timings.add("raster,," + elapsed(start));

            Files.createDirectories(output);
            start = System.nanoTime();
            var offsets = variants.stream().anyMatch(v -> v.prominenceHundredths == 0)
                    ? offsets(grid.resolution()) : null;
            timings.add("offsets,," + elapsed(start));
            double[][] smoothed = new double[Kind.values().length][];
            for (Variant variant : variants) {
                Surface surface = variant.kind == Kind.CHM ? chm : dsm;
                double[] scores = surface.scores;
                if (variant.gaussian) {
                    int slot = variant.kind.ordinal();
                    if (smoothed[slot] == null) {
                        start = System.nanoTime();
                        smoothed[slot] = TreetopProminence.smooth(grid, scores);
                        timings.add("smooth," + variant.surfaceId() + "," + elapsed(start));
                    }
                    scores = smoothed[slot];
                }
                start = System.nanoTime();
                int[] peaks = variant.prominenceHundredths == 0
                        ? detect(grid, scores, surface.heights, variant, offsets)
                        : TreetopProminence.detect(grid, scores, variant.prominenceHundredths / 100.0);
                timings.add("detector," + variant.id() + "," + elapsed(start));
                start = System.nanoTime();
                writePeaks(output.resolve(variant.id() + ".csv"), peaks, surface, reader.header());
                timings.add("write," + variant.id() + "," + elapsed(start));
            }
            if (rasters) {
                writeSurface(input, output, reader, grid, chm);
                writeSurface(input, output, reader, grid, dsm);
                for (Kind kind : Kind.values()) {
                    if (smoothed[kind.ordinal()] != null) {
                        double[] samples = smoothed[kind.ordinal()].clone();
                        for (int cell = 0; cell < samples.length; cell++) {
                            if (samples[cell] == Double.NEGATIVE_INFINITY) {
                                samples[cell] = GeoTiffOutput.NODATA;
                            }
                        }
                        writeRaster(input, output, reader, grid, kind.id() + "-gauss", samples);
                    }
                }
            }
            writeText(output.resolve("timings.csv"), "phase,method,nanos\n" + String.join("\n", timings) + "\n");
            writeText(output.resolve("counts.csv"), "points_read,points_binned,core_cells,halo_cells\n"
                    + reader.header().pointCount() + "," + pointsBinned + "," + grid.coreCells()
                    + "," + (grid.cells() - grid.coreCells()) + "\n");
        }
    }

    private static long accumulate(Pipeline.BatchInput reader, HagDem dem, Grid grid, Surface chm, Surface dsm)
            throws IOException {
        var header = reader.header();
        long pointsBinned = 0;
        for (var batch = reader.reader().readBatch(); batch != null; batch = reader.reader().readBatch()) {
            checkInterrupted();
            for (int point = 0; point < batch.size(); point++) {
                int classification = batch.classification(point);
                if (classification < 3 || classification > 5 || Dimension.Withheld.value(batch, point) != 0) {
                    continue;
                }
                int rawX = batch.rawX(point);
                int rawY = batch.rawY(point);
                double x = header.coordinate(0, rawX);
                double y = header.coordinate(1, rawY);
                int cell = grid.cell(x, y);
                if (cell < 0) {
                    continue;
                }
                int rawZ = batch.rawZ(point);
                double z = header.coordinate(2, rawZ);
                double hag = z - dem.elevation(x, y);
                if (!Double.isFinite(hag)) {
                    throw new IOException("No finite height over terrain at " + x + ", " + y);
                }
                if (hag < MIN_HEIGHT) {
                    continue;
                }
                pointsBinned++;
                if (chm != null) {
                    chm.add(cell, rawX, rawY, rawZ, z, hag);
                }
                if (dsm != null) {
                    dsm.add(cell, rawX, rawY, rawZ, z, hag);
                }
            }
        }
        if (!reader.finished().getAsBoolean()) {
            throw new IOException("Point input ended before its declared point count");
        }
        return pointsBinned;
    }

    static int[] detect(Grid grid, Surface surface, Variant variant, Offset[] offsets) throws IOException {
        return detect(grid, surface.scores, surface.heights, variant, offsets);
    }

    private static int[] detect(Grid grid, double[] scores, double[] heights, Variant variant, Offset[] offsets)
            throws IOException {
        int[] peaks = new int[grid.coreCells()];
        int size = 0;
        int width = grid.width();
        int halo = grid.halo();
        for (int row = halo; row < grid.height() - halo; row++) {
            checkInterrupted();
            candidates:
            for (int column = halo; column < width - halo; column++) {
                int cell = row * width + column;
                double centre = scores[cell];
                if (centre == Double.NEGATIVE_INFINITY) {
                    continue;
                }
                double radius = variant.radius(heights[cell]);
                double squaredRadius = radius * radius;
                for (Offset offset : offsets) {
                    if (offset.squaredDistance > squaredRadius) {
                        break;
                    }
                    int neighbour = cell + offset.row * width + offset.column;
                    double neighbourScore = scores[neighbour];
                    if (neighbourScore > centre || neighbourScore == centre && neighbour < cell) {
                        continue candidates;
                    }
                }
                peaks[size++] = cell;
            }
        }
        return Arrays.copyOf(peaks, size);
    }

    static Offset[] offsets(double resolution) {
        int extent = (int) Math.floor(MAX_RADIUS / resolution);
        var offsets = new ArrayList<Offset>();
        double squaredRadius = MAX_RADIUS * MAX_RADIUS;
        for (int row = -extent; row <= extent; row++) {
            for (int column = -extent; column <= extent; column++) {
                double dx = column * resolution;
                double dy = row * resolution;
                double squaredDistance = dx * dx + dy * dy;
                if (squaredDistance > 0 && squaredDistance <= squaredRadius) {
                    offsets.add(new Offset(row, column, squaredDistance));
                }
            }
        }
        offsets.sort(Comparator.comparingDouble(Offset::squaredDistance));
        return offsets.toArray(Offset[]::new);
    }

    private static void writePeaks(Path path, int[] peaks, Surface surface, LasHeader header) throws IOException {
        try (var writer = Files.newBufferedWriter(path, StandardOpenOption.CREATE_NEW)) {
            writer.write("tree_id,easting,northing,z,height_above_ground\n");
            for (int i = 0; i < peaks.length; i++) {
                checkInterrupted();
                writer.write(Integer.toString(i + 1));
                writePoint(writer, peaks[i], surface, header);
            }
        }
    }

    private static void writeSurface(Path input, Path output, Pipeline.BatchInput reader, Grid grid, Surface surface)
            throws IOException {
        String prefix = surface.kind.id();
        double[] samples = surface.scores.clone();
        try (var points = Files.newBufferedWriter(output.resolve(prefix + "-cells.csv"), StandardOpenOption.CREATE_NEW)) {
            points.write("row,col,easting,northing,z,height_above_ground\n");
            for (int cell = 0; cell < samples.length; cell++) {
                if (samples[cell] == Double.NEGATIVE_INFINITY) {
                    samples[cell] = GeoTiffOutput.NODATA;
                } else {
                    points.write((cell / grid.width()) + "," + (cell % grid.width()));
                    writePoint(points, cell, surface, reader.header());
                }
            }
        }
        writeRaster(input, output, reader, grid, prefix, samples);
    }

    private static void writeRaster(Path input, Path output, Pipeline.BatchInput reader, Grid grid,
                                    String prefix, double[] samples) throws IOException {
        try (var tiff = new GeoTiffOutput(input, output.resolve(prefix + ".tif"), false,
                reader.header(), reader.channel())) {
            tiff.prepare(grid.originX(), grid.originY(), grid.resolution(), grid.width(), grid.height(),
                    new double[][]{samples}, List.of(prefix), GeoTiffOutput.NODATA, false);
            tiff.finish();
        }
    }

    private static void writePoint(BufferedWriter writer, int cell, Surface surface, LasHeader header) throws IOException {
        writer.write("," + header.coordinate(0, surface.rawX[cell]) + "," + header.coordinate(1, surface.rawY[cell])
                + "," + header.coordinate(2, surface.rawZ[cell]) + "," + surface.heights[cell] + "\n");
    }

    private static void writeText(Path path, String contents) throws IOException {
        Files.writeString(path, contents, StandardOpenOption.CREATE_NEW);
    }

    static void checkInterrupted() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException("Tree detection cancelled");
        }
    }

    private static long elapsed(long start) {
        return System.nanoTime() - start;
    }

    enum Kind {
        CHM, DSM;

        String id() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    record Variant(Kind kind, int fixedRadius, int prominenceHundredths, boolean gaussian) {
        static final List<Variant> ALL = List.of(new Variant(Kind.CHM, 2), new Variant(Kind.CHM, 3),
                new Variant(Kind.CHM, 4), new Variant(Kind.CHM, 0), new Variant(Kind.DSM, 2),
                new Variant(Kind.DSM, 3), new Variant(Kind.DSM, 4), new Variant(Kind.DSM, 0));
        static final List<Variant> PROMINENCE = prominenceVariants();

        Variant(Kind kind, int fixedRadius) {
            this(kind, fixedRadius, 0, false);
        }

        private static List<Variant> prominenceVariants() {
            var variants = new ArrayList<Variant>();
            for (Kind kind : Kind.values()) {
                for (boolean smooth : new boolean[]{false, true}) {
                    variants.add(new Variant(kind, 3, 0, smooth));
                    for (int threshold : new int[]{25, 50, 75, 100, 150}) {
                        variants.add(new Variant(kind, 0, threshold, smooth));
                    }
                }
            }
            return List.copyOf(variants);
        }

        static Variant parse(String id) {
            return Stream.concat(ALL.stream(), PROMINENCE.stream()).filter(variant -> variant.id().equals(id)).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Unknown tree detection method: " + id));
        }

        String id() {
            if (prominenceHundredths != 0) {
                return surfaceId() + String.format(Locale.ROOT, "-prom-%03d", prominenceHundredths);
            }
            return surfaceId() + (fixedRadius == 0 ? "-adaptive" : "-r" + fixedRadius);
        }

        String surfaceId() {
            return kind.id() + (gaussian ? "-gauss" : "");
        }

        double radius(double height) {
            return fixedRadius == 0 ? Math.clamp(0.12 * height, 1.5, MAX_RADIUS) : fixedRadius;
        }
    }

    record Offset(int row, int column, double squaredDistance) {}

    record Grid(double originX, double originY, double resolution, int width, int height, int halo) {
        static Grid fromCore(double x, double y, int width, int height, double resolution) {
            return fromCore(x, y, width, height, resolution, Variant.ALL);
        }

        static Grid fromCore(double x, double y, int width, int height, double resolution, List<Variant> variants) {
            GeoTiffOutput.validateGrid(x, y, resolution, width, height);
            double border = 0;
            for (Variant variant : variants) {
                double searchRadius = variant.prominenceHundredths == 0 ? MAX_RADIUS : TreetopProminence.SEARCH_RADIUS;
                double smoothing = variant.gaussian ? Math.ceil(TreetopProminence.GAUSSIAN_RADIUS / resolution) : 0;
                border = Math.max(border, Math.ceil(searchRadius / resolution) + smoothing);
            }
            if (border >= GeoTiffOutput.MAX_CELLS) {
                throw new IllegalArgumentException("Tree detection resolution requires too many halo cells");
            }
            int halo = (int) border;
            double expandedX = x - halo * resolution;
            double expandedY = y - halo * resolution;
            int expandedWidth = width + 2 * halo;
            int expandedHeight = height + 2 * halo;
            GeoTiffOutput.validateGrid(expandedX, expandedY, resolution, expandedWidth, expandedHeight);
            return new Grid(expandedX, expandedY, resolution, expandedWidth, expandedHeight, halo);
        }

        int cells() {
            return width * height;
        }

        int coreCells() {
            return (width - 2 * halo) * (height - 2 * halo);
        }

        int cell(double x, double y) {
            if (!(x >= originX && x < originX + width * resolution
                    && y >= originY && y < originY + height * resolution)) {
                return -1;
            }
            int column = Math.min(width - 1, (int) ((x - originX) / resolution));
            int rowFromSouth = Math.min(height - 1, (int) ((y - originY) / resolution));
            return (height - 1 - rowFromSouth) * width + column;
        }
    }

    static final class Surface {
        final Kind kind;
        final double[] scores;
        final double[] heights;
        final int[] rawX;
        final int[] rawY;
        final int[] rawZ;

        Surface(int cells, Kind kind) {
            this.kind = kind;
            scores = new double[cells];
            Arrays.fill(scores, Double.NEGATIVE_INFINITY);
            heights = kind == Kind.CHM ? scores : new double[cells];
            rawX = new int[cells];
            rawY = new int[cells];
            rawZ = new int[cells];
        }

        void add(int cell, int x, int y, int z, double absoluteHeight, double heightAboveGround) {
            double value = kind == Kind.CHM ? heightAboveGround : absoluteHeight;
            if (value > scores[cell]) {
                scores[cell] = value;
                heights[cell] = heightAboveGround;
                rawX[cell] = x;
                rawY[cell] = y;
                rawZ[cell] = z;
            }
        }
    }
}
