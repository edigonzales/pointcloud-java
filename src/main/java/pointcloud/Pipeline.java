package pointcloud;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.Strictness;
import com.google.gson.TypeAdapter;
import com.google.gson.TypeAdapterFactory;
import com.google.gson.annotations.JsonAdapter;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import org.locationtech.jts.algorithm.locate.IndexedPointInAreaLocator;
import org.locationtech.jts.geom.CoordinateXY;
import org.locationtech.jts.geom.Location;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.io.ParseException;
import org.locationtech.jts.io.WKTFileReader;
import org.locationtech.jts.io.WKTReader;

import java.io.Closeable;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.Reader;
import java.io.StringReader;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;

/** A linear PDAL-subset pipeline. Execution state is created afresh for each run. */
public final class Pipeline {
    private static final String HAG_FILTER = "filters.hag_dem";
    private static final String RASTER_WRITER = "writers.raster";
    private static final String DTM_WRITER = "writers.dtm";
    private static final int MAX_HEIGHT_THRESHOLDS = 8;
    private static final String BOUND = "\\s*\\[\\s*(" + Expression.NUMBER + ")\\s*,\\s*("
            + Expression.NUMBER + ")\\s*]\\s*";
    private static final Pattern BOX = Pattern.compile("\\s*\\(" + BOUND + "," + BOUND
            + "(?:," + BOUND + ")?\\)\\s*");
    static final Gson JSON = new GsonBuilder().setStrictness(Strictness.STRICT)
            .setPrettyPrinting().serializeNulls().create();

    public record Result(long pointsRead, long pointsSelected, long pointsWritten,
                         List<StageStatistics> statistics,
                         @JsonAdapter(value = OmitNull.class, nullSafe = false) Long pointsBinned,
                         @JsonAdapter(value = OmitNull.class, nullSafe = false) TerrainStatistics dtm) {
        public Result(long pointsRead, long pointsSelected, long pointsWritten, List<StageStatistics> statistics) {
            this(pointsRead, pointsSelected, pointsWritten, statistics, null);
        }

        public Result(long pointsRead, long pointsSelected, long pointsWritten,
                      List<StageStatistics> statistics, Long pointsBinned) {
            this(pointsRead, pointsSelected, pointsWritten, statistics, pointsBinned, null);
        }
    }

    public record TerrainStatistics(int supportPoints, int cellsCovered, int cellsNoData,
                                    @JsonAdapter(value = OmitNull.class, nullSafe = false) Integer cellsFilledNearest) {
        public TerrainStatistics(int supportPoints, int cellsCovered, int cellsNoData) {
            this(supportPoints, cellsCovered, cellsNoData, null);
        }
    }

    public record StageStatistics(int stage, List<Statistics> dimensions) {}

    public record Statistics(String name, long count, Double minimum, Double maximum,
                             Double average, Double variance, Double stddev,
                             @JsonAdapter(value = OmitNull.class, nullSafe = false) Map<Integer, Long> bins) {
        public Statistics(String name, long count, Double minimum, Double maximum,
                          Double average, Double variance, Double stddev) {
            this(name, count, minimum, maximum, average, variance, stddev, null);
        }
    }

    /** Omit unrequested metadata without hiding null values of requested statistics. */
    static final class OmitNull implements TypeAdapterFactory {
        @Override
        public <T> TypeAdapter<T> create(Gson gson, TypeToken<T> type) {
            TypeAdapter<T> delegate = gson.getAdapter(type);
            return new TypeAdapter<>() {
                @Override
                public T read(JsonReader in) throws IOException {
                    return delegate.read(in);
                }

                @Override
                public void write(JsonWriter out, T value) throws IOException {
                    if (value != null) {
                        delegate.write(out, value);
                        return;
                    }
                    boolean serializeNulls = out.getSerializeNulls();
                    out.setSerializeNulls(false);
                    try {
                        out.nullValue();
                    } finally {
                        out.setSerializeNulls(serializeNulls);
                    }
                }
            };
        }
    }

    private final Path input;
    private final Path output;
    private final HagStage hag;
    private final boolean appendHeightAboveGround;
    private final GroundGridWriter.Grid rasterGrid;
    private final TerrainWriter.Grid terrainGrid;
    private final JsonArray stages;

    private Pipeline(JsonArray stages) {
        if (stages.size() < 2) {
            throw new IllegalArgumentException("a pipeline requires a reader and a terminal writer");
        }
        this.stages = stages;
        JsonElement first = stages.get(0);
        if (first.isJsonPrimitive() && first.getAsJsonPrimitive().isString()) {
            input = filename(first.getAsString(), true);
        } else {
            JsonObject stage = stageObject(first, 1);
            String type = type(stage, 1);
            try {
                if (!type.equals("readers.las")) {
                    throw new IllegalArgumentException("the first stage must be readers.las");
                }
                options(stage, "filename");
                input = filename(string(stage, "filename"), true);
            } catch (IllegalArgumentException e) {
                throw atStage(1, type, e.getMessage());
            }
        }
        int position = stages.size();
        JsonObject writer = stageObject(stages.get(position - 1), position);
        String type = type(writer, position);
        boolean appendHeight = false;
        try {
            rasterGrid = type.equals(RASTER_WRITER) ? parseRasterGrid(writer) : null;
            terrainGrid = type.equals(DTM_WRITER) ? parseTerrainGrid(writer) : null;
            output = switch (type) {
                case "writers.las" -> {
                    options(writer, "filename", "forward", "compression", "extra_dims");
                    exact(writer, "forward", "all");
                    appendHeight = switch (string(writer, "extra_dims")) {
                        case "all" -> false;
                        case "HeightAboveGround=double", "HeightAboveGround=float64" -> true;
                        default -> throw new IllegalArgumentException(
                                "extra_dims must be 'all', 'HeightAboveGround=double' or 'HeightAboveGround=float64'");
                    };
                    if (bool(writer, "compression", false)) {
                        throw new IllegalArgumentException("compression must be false");
                    }
                    yield filename(string(writer, "filename"), false);
                }
                case RASTER_WRITER, DTM_WRITER -> {
                    String name = string(writer, "filename");
                    String lower = name.toLowerCase(Locale.ROOT);
                    if (!lower.endsWith(".tif") && !lower.endsWith(".tiff")) {
                        throw new IllegalArgumentException("filename must end in .tif or .tiff");
                    }
                    yield Path.of(name);
                }
                case "writers.null" -> {
                    options(writer);
                    yield null;
                }
                case "writers.gdal" -> throw new IllegalArgumentException("change the stage type to " + RASTER_WRITER);
                default -> throw new IllegalArgumentException(
                        "expected writers.las, " + RASTER_WRITER + ", " + DTM_WRITER + " or writers.null");
            };
        } catch (IllegalArgumentException e) {
            throw atStage(position, type, e.getMessage());
        }
        HagStage found = null;
        for (int i = 1; i < position - 1; i++) {
            if (stages.get(i) instanceof JsonObject stage
                    && HAG_FILTER.equals(type(stage, i + 1))) {
                if (found != null) {
                    throw atStage(i + 1, HAG_FILTER, "only one filters.hag_dem stage is supported");
                }
                found = parseHag(stage, i + 1);
            }
        }
        hag = found;
        if (appendHeight && hag == null) {
            throw atStage(position, type, "HeightAboveGround export requires a preceding " + HAG_FILTER + " stage");
        }
        if (rasterGrid != null && rasterGrid.dimension() == Dimension.HeightAboveGround) {
            if (hag == null) {
                throw atStage(position, type, "HeightAboveGround requires a preceding " + HAG_FILTER + " stage");
            }
            if (rasterGrid.heightThresholds().length > 0 && Double.isFinite(hag.missing())) {
                throw atStage(position, type, "height_thresholds cannot be used with nodata_hag");
            }
        }
        appendHeightAboveGround = appendHeight;
    }

    public static Pipeline read(Path path) throws IOException {
        try (var reader = Files.newBufferedReader(path)) {
            return parse(reader);
        }
    }

    public static Pipeline parse(Reader reader) {
        JsonElement root = JSON.fromJson(reader, JsonElement.class);
        if (root != null && root.isJsonObject() && root.getAsJsonObject().size() == 1) {
            root = root.getAsJsonObject().get("pipeline");
        }
        if (root == null || !root.isJsonArray()) {
            throw new IllegalArgumentException("expected a pipeline array or an object containing only 'pipeline'");
        }
        return new Pipeline(root.getAsJsonArray());
    }

    public Path input() {
        return input;
    }

    public Path output() {
        return output;
    }

    Path demInput() {
        return hag == null ? null : hag.raster;
    }

    public Result run(int batchSize, boolean overwrite) throws IOException {
        return run(batchSize, overwrite, Pipeline::openSequential, Pipeline::publishOutput);
    }

    @FunctionalInterface
    interface ResultOutput {
        void publish(Result result, OutputFinish output) throws IOException;
    }

    @FunctionalInterface
    interface OutputFinish {
        void finish() throws IOException;
    }

    @FunctionalInterface
    interface BatchReader {
        PointBatch readBatch() throws IOException;
    }

    @FunctionalInterface
    interface InputFactory {
        BatchInput open(Path path, int batchSize) throws IOException;
    }

    record BatchInput(LasHeader header, FileChannel channel, BatchReader reader, BooleanSupplier finished,
                      Closeable source) implements Closeable {
        @Override
        public void close() throws IOException {
            source.close();
        }
    }

    static BatchInput openSequential(Path path, int batchSize) throws IOException {
        var reader = new LasReader(path, batchSize);
        return new BatchInput(reader.header(), reader.source, reader::readBatch, reader::finished, reader::close);
    }

    static void publishOutput(Result result, OutputFinish output) throws IOException {
        if (output != null) {
            output.finish();
        }
    }

    private GeoTiffOutput openTiff(BatchInput reader, boolean overwrite) throws IOException {
        if (rasterGrid == null && terrainGrid == null) {
            return null;
        }
        String type = terrainGrid == null ? RASTER_WRITER : DTM_WRITER;
        try {
            return new GeoTiffOutput(input, output, overwrite, reader.header(), reader.channel());
        } catch (IOException e) {
            throw new IOException("Stage " + stages.size() + " (" + type + "): " + e.getMessage(), e);
        }
    }

    Result run(int batchSize, boolean overwrite, InputFactory inputs, ResultOutput resultOutput) throws IOException {
        var filters = new Filter[stages.size() - 2];
        for (int i = 0; i < filters.length; i++) {
            var filter = compile(i + 2);
            if (filter.dimensions.contains(Dimension.HeightAboveGround)
                    && (hag == null || filter.stage < hag.position)) {
                throw atStage(filter.stage, filter.type, "HeightAboveGround requires a preceding " + HAG_FILTER + " stage");
            }
            filters[i] = filter;
        }
        checkDemOutputAlias();
        try (var reader = inputs.open(input, batchSize)) {
            for (var filter : filters) {
                for (var dimension : filter.dimensions) {
                    if (!dimension.available(reader.header().pointFormat())) {
                        throw atStage(filter.stage, filter.type,
                                "dimension " + dimension + " is absent from point format " + reader.header().pointFormat());
                    }
                }
            }
            if (hag != null) {
                int index = hag.position - 2;
                var filter = filters[index];
                filters[index] = new Filter(filter.stage, filter.type, filter.dimensions, hag.open(reader), null);
            }
            try (var tiff = openTiff(reader, overwrite);
                 var writer = output == null || tiff != null ? null
                         : new LasWriter(input, reader.channel(), reader.header(), reader.finished(), output,
                               overwrite, appendHeightAboveGround)) {
                var raster = rasterGrid == null ? null : new GroundGridWriter(tiff, rasterGrid);
                var terrain = terrainGrid == null ? null : new TerrainWriter(tiff, terrainGrid, reader.header());
                long selected = 0;
                double[] heights = null;
                for (var batch = reader.reader().readBatch(); batch != null; batch = reader.reader().readBatch()) {
                    if (hag != null) {
                        hag.checkInterrupted();
                        if (heights == null || heights.length < batch.size()) {
                            heights = new double[batch.capacity()];
                        }
                        batch.heightAboveGround = heights;
                    }
                    points:
                    for (int point = 0; point < batch.size(); point++) {
                        for (var filter : filters) {
                            if (!filter.selection.keep(batch, point)) {
                                continue points;
                            }
                        }
                        selected++;
                        if (writer != null) {
                            writer.write(batch, point);
                        } else if (raster != null) {
                            raster.add(batch, point);
                        } else if (terrain != null) {
                            terrain.add(batch, point);
                        }
                    }
                    batch.heightAboveGround = null;
                }
                if (hag != null) {
                    hag.checkInterrupted();
                }
                var results = new ArrayList<StageStatistics>();
                for (var filter : filters) {
                    if (filter.stats != null) {
                        results.add(new StageStatistics(filter.stage,
                                filter.stats.stream().map(Accumulator::result).toList()));
                    }
                }
                Long pointsBinned = null;
                if (raster != null) {
                    raster.prepare(reader.finished().getAsBoolean());
                    pointsBinned = raster.pointsBinned();
                }
                var dtm = terrain == null ? null : terrain.prepare(reader.finished().getAsBoolean());
                OutputFinish finish = tiff != null ? tiff::finish : writer != null ? writer::finish : null;
                if (hag != null && finish != null) {
                    OutputFinish publish = finish;
                    finish = () -> {
                        checkDemOutputAlias();
                        publish.finish();
                    };
                }
                var result = new Result(reader.header().pointCount(), selected, writer == null ? 0 : writer.pointCount(),
                        List.copyOf(results), pointsBinned, dtm);
                resultOutput.publish(result, finish);
                return result;
            }
        }
    }

    private void checkDemOutputAlias() throws IOException {
        if (hag != null && output != null
                && (hag.raster.toAbsolutePath().normalize().equals(output.toAbsolutePath().normalize())
                    || Files.exists(hag.raster) && Files.exists(output) && Files.isSameFile(hag.raster, output))) {
            throw hag.failure("output destination aliases the input DEM: " + output, null);
        }
    }

    private Filter compile(int position) {
        JsonObject stage = stageObject(stages.get(position - 1), position);
        String type = type(stage, position);
        var dimensions = EnumSet.noneOf(Dimension.class);
        try {
            switch (type) {
                case HAG_FILTER -> {
                    // The action is bound after the input header is available for CRS checks.
                    return new Filter(position, type, dimensions, null, null);
                }
                case "filters.crop" -> {
                    options(stage, "bounds", "polygon", "outside");
                    return new Filter(position, type, dimensions, crop(stage), null);
                }
                case "filters.expression" -> {
                    options(stage, "expression");
                    var selection = Expression.parse(string(stage, "expression"), dimensions);
                    return new Filter(position, type, dimensions, selection, null);
                }
                case "filters.decimation" -> {
                    options(stage, "step", "offset", "limit");
                    long step = integer(stage, "step", 1);
                    if (step == 0) {
                        throw new IllegalArgumentException("step must be at least 1");
                    }
                    long offset = integer(stage, "offset", 0);
                    long limit = integer(stage, "limit", Long.MAX_VALUE);
                    return new Filter(position, type, dimensions, new Expression.Selection() {
                        private long index;

                        @Override
                        public boolean keep(PointBatch batch, int point) {
                            long current = index++;
                            return current >= offset && current < limit && (current - offset) % step == 0;
                        }
                    }, null);
                }
                case "filters.stats" -> {
                    options(stage, "dimensions", "count");
                    boolean countClassification = stage.has("count");
                    if (countClassification) {
                        exact(stage, "count", "Classification");
                    }
                    var list = new ArrayList<Accumulator>();
                    for (String name : string(stage, "dimensions").split(",", -1)) {
                        var dimension = Dimension.parse(name.strip());
                        if (!dimensions.add(dimension)) {
                            throw new IllegalArgumentException("duplicate statistics dimension " + dimension);
                        }
                        list.add(new Accumulator(dimension, countClassification && dimension == Dimension.Classification));
                    }
                    var accumulators = list.toArray(Accumulator[]::new);
                    return new Filter(position, type, dimensions, (batch, point) -> {
                        for (var accumulator : accumulators) {
                            accumulator.add(accumulator.dimension.value(batch, point));
                        }
                        return true;
                    }, list);
                }
                default -> throw new IllegalArgumentException("unsupported filter type");
            }
        } catch (IllegalArgumentException e) {
            throw atStage(position, type, e.getMessage());
        }
    }

    private static HagStage parseHag(JsonObject stage, int position) {
        try {
            options(stage, "raster", "band", "zero_ground", "class", "min_clamp", "max_clamp", "nodata_hag");
            var raster = Path.of(string(stage, "raster"));
            long band = integer(stage, "band", 1);
            if (band < 1 || band > Integer.MAX_VALUE) {
                throw new IllegalArgumentException("band must be a positive 32-bit integer");
            }
            long groundClass = integer(stage, "class", 2);
            if (groundClass > 255) {
                throw new IllegalArgumentException("class must be in [0, 255]");
            }
            double minimum = stage.has("min_clamp") ? number(stage, "min_clamp") : Double.NEGATIVE_INFINITY;
            double maximum = stage.has("max_clamp") ? number(stage, "max_clamp") : Double.POSITIVE_INFINITY;
            if (minimum > maximum) {
                throw new IllegalArgumentException("min_clamp must not exceed max_clamp");
            }
            double missing = stage.has("nodata_hag") ? number(stage, "nodata_hag") : Double.NaN;
            return new HagStage(position, raster, (int) band, bool(stage, "zero_ground", true),
                    (int) groundClass, minimum, maximum, missing);
        } catch (IllegalArgumentException e) {
            throw atStage(position, HAG_FILTER, e.getMessage());
        }
    }

    private record HagStage(int position, Path raster, int band, boolean zeroGround, int groundClass,
                            double minimum, double maximum, double missing) {
        Expression.Selection open(BatchInput input) throws IOException {
            HagDem dem;
            try {
                dem = HagDem.load(raster, band, input.header(), input.channel());
            } catch (InterruptedIOException e) {
                throw e;
            } catch (IOException e) {
                throw failure(e.getMessage(), e);
            }
            return (batch, point) -> {
                double value = 0;
                if (!zeroGround || Dimension.Classification.value(batch, point) != groundClass) {
                    double x = batch.x(point);
                    double y = batch.y(point);
                    double elevation = dem.elevation(x, y);
                    if (Double.isNaN(elevation)) {
                        if (Double.isNaN(missing)) {
                            throw failure("no DEM elevation at XY (" + x + ", " + y + ")", null);
                        }
                        value = missing;
                    } else {
                        double difference = batch.z(point) - elevation;
                        if (!Double.isFinite(difference)) {
                            throw failure("nonfinite height above ground at XY (" + x + ", " + y + ")", null);
                        }
                        value = Math.clamp(difference, minimum, maximum);
                    }
                }
                batch.heightAboveGround[point] = value;
                return true;
            };
        }

        IOException failure(String message, Throwable cause) {
            return new IOException("Stage " + position + " (" + HAG_FILTER + "): " + message, cause);
        }

        void checkInterrupted() throws InterruptedIOException {
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedIOException("Stage " + position + " (" + HAG_FILTER + "): processing cancelled");
            }
        }
    }

    private static Expression.Selection crop(JsonObject stage) {
        boolean hasPolygon = stage.has("polygon");
        if (stage.has("bounds") == hasPolygon) {
            throw new IllegalArgumentException("specify exactly one of bounds or polygon");
        }
        if (hasPolygon) {
            return polygonCrop(string(stage, "polygon"), bool(stage, "outside", false));
        }
        var box = BOX.matcher(string(stage, "bounds"));
        if (!box.matches()) {
            throw new IllegalArgumentException("bounds must be one PDAL 2D or 3D box");
        }
        double minX = Expression.finite(box.group(1));
        double maxX = Expression.finite(box.group(2));
        double minY = Expression.finite(box.group(3));
        double maxY = Expression.finite(box.group(4));
        boolean hasZ = box.group(5) != null;
        double minZ = hasZ ? Expression.finite(box.group(5)) : 0;
        double maxZ = hasZ ? Expression.finite(box.group(6)) : 0;
        if (minX > maxX || minY > maxY || minZ > maxZ) {
            throw new IllegalArgumentException("bounds minimum exceeds maximum");
        }
        boolean outside = bool(stage, "outside", false);
        return (batch, point) -> {
            double x = batch.x(point);
            double y = batch.y(point);
            boolean inside = minX <= x && x <= maxX && minY <= y && y <= maxY;
            if (inside && hasZ) {
                double z = batch.z(point);
                inside = minZ <= z && z <= maxZ;
            }
            return inside != outside;
        };
    }

    private static Expression.Selection polygonCrop(String wkt, boolean outside) {
        var reader = new WKTReader();
        reader.setIsOldJtsCoordinateSyntaxAllowed(false);
        // Reading a second item also detects trailing input after the first geometry.
        var input = new WKTFileReader(new StringReader(wkt), reader);
        input.setLimit(2);
        List<?> geometries;
        try {
            geometries = input.read();
        } catch (IOException | ParseException e) {
            throw new IllegalArgumentException("invalid polygon WKT: " + e.getMessage(), e);
        }
        if (geometries.size() != 1 || !(geometries.getFirst() instanceof Polygon polygon)
                || polygon.isEmpty() || polygon.getExteriorRing().getCoordinateSequence().getDimension() != 2
                || !polygon.isValid()) {
            throw new IllegalArgumentException("polygon must be one valid nonempty 2D WKT POLYGON");
        }

        var locator = new IndexedPointInAreaLocator(polygon);
        var coordinate = new CoordinateXY();
        return (batch, point) -> {
            coordinate.x = batch.x(point);
            coordinate.y = batch.y(point);
            return (locator.locate(coordinate) != Location.EXTERIOR) != outside;
        };
    }

    private record Filter(int stage, String type, EnumSet<Dimension> dimensions,
                          Expression.Selection selection, List<Accumulator> stats) {}

    private static final class Accumulator {
        private final Dimension dimension;
        private final long[] bins;
        private long count;
        private double min = Double.MAX_VALUE;
        private double max = -Double.MAX_VALUE;
        private double mean;
        private double m2;

        Accumulator(Dimension dimension, boolean countClassification) {
            this.dimension = dimension;
            this.bins = countClassification ? new long[256] : null;
        }

        void add(double value) {
            if (bins != null) {
                bins[(int) value]++;
            }
            if (value < min) {
                min = value;
            }
            if (value > max) {
                max = value;
            }
            long previous = count++;
            double delta = value - mean;
            double step = delta / count;
            mean += step;
            m2 += delta * step * previous;
        }

        Statistics result() {
            Double variance = count > 1 ? finiteOrNull(m2 / (count - 1)) : null;
            return new Statistics(dimension.name(), count,
                    count == 0 ? null : finiteOrNull(min), count == 0 ? null : finiteOrNull(max),
                    count == 0 ? null : finiteOrNull(mean), variance,
                    variance == null ? null : Math.sqrt(variance), binCounts());
        }

        private Map<Integer, Long> binCounts() {
            if (bins == null) {
                return null;
            }
            var counts = new LinkedHashMap<Integer, Long>();
            for (int code = 0; code < bins.length; code++) {
                if (bins[code] != 0) {
                    counts.put(code, bins[code]);
                }
            }
            return Collections.unmodifiableMap(counts);
        }
    }

    private static Double finiteOrNull(double value) {
        return Double.isFinite(value) ? value : null;
    }

    private static GroundGridWriter.Grid parseRasterGrid(JsonObject stage) {
        options(stage, "filename", "binmode", "resolution", "origin_x", "origin_y", "width", "height",
                "output_type", "dimension", "data_type", "nodata", "window_size", "compression", "height_thresholds");
        if (!bool(stage, "binmode", false)) {
            throw new IllegalArgumentException("binmode must be true");
        }
        var bands = EnumSet.of(GroundGridWriter.Band.MIN, GroundGridWriter.Band.MAX,
                GroundGridWriter.Band.MEAN, GroundGridWriter.Band.COUNT);
        if (stage.has("output_type")) {
            bands.clear();
            for (String type : string(stage, "output_type").split(",", -1)) {
                var band = switch (type.strip()) {
                    case "min" -> GroundGridWriter.Band.MIN;
                    case "max" -> GroundGridWriter.Band.MAX;
                    case "mean" -> GroundGridWriter.Band.MEAN;
                    case "count" -> GroundGridWriter.Band.COUNT;
                    case "stdev" -> GroundGridWriter.Band.STDEV;
                    default -> throw new IllegalArgumentException("unsupported output_type: " + type);
                };
                if (!bands.add(band)) {
                    throw new IllegalArgumentException("duplicate output_type: " + type);
                }
            }
        }
        var dimension = switch (stage.has("dimension") ? string(stage, "dimension") : "Z") {
            case "Z" -> Dimension.Z;
            case "Intensity" -> Dimension.Intensity;
            case "HeightAboveGround" -> Dimension.HeightAboveGround;
            default -> throw new IllegalArgumentException("dimension must be 'Z', 'Intensity' or 'HeightAboveGround'");
        };
        double[] heightThresholds = parseHeightThresholds(stage.get("height_thresholds"));
        if (heightThresholds.length > 0) {
            if (dimension != Dimension.HeightAboveGround) {
                throw new IllegalArgumentException("height_thresholds requires dimension 'HeightAboveGround'");
            }
            if (!bands.contains(GroundGridWriter.Band.COUNT)) {
                throw new IllegalArgumentException("height_thresholds requires the 'count' band");
            }
        }
        if (stage.has("data_type")) {
            exact(stage, "data_type", "double");
        }
        double nodata = nodata(stage);
        if (nodata == 0 && bands.contains(GroundGridWriter.Band.COUNT)) {
            throw new IllegalArgumentException("nodata must be nonzero when count is selected");
        }
        long windowSize = integer(stage, "window_size", 0);
        int width = gridDimension(stage, "width");
        int height = gridDimension(stage, "height");
        int effectiveWindow = (int) Math.min(windowSize, Math.max(width, height) - 1);
        return new GroundGridWriter.Grid(number(stage, "origin_x"), number(stage, "origin_y"),
                number(stage, "resolution"), width, height, effectiveWindow, bands, dimension, nodata,
                deflate(stage), heightThresholds);
    }

    private static double[] parseHeightThresholds(JsonElement value) {
        if (value == null) {
            return new double[0];
        }
        if (!(value instanceof JsonArray values) || values.isEmpty() || values.size() > MAX_HEIGHT_THRESHOLDS) {
            throw new IllegalArgumentException("height_thresholds must be an array with 1 to "
                    + MAX_HEIGHT_THRESHOLDS + " numbers");
        }
        var thresholds = new double[values.size()];
        double previous = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < thresholds.length; i++) {
            double threshold = number(values.get(i), "height_thresholds[" + i + "]");
            if (threshold <= previous) {
                throw new IllegalArgumentException("height_thresholds must be strictly increasing");
            }
            thresholds[i] = threshold == 0 ? 0 : threshold;
            previous = threshold;
        }
        return thresholds;
    }

    private static TerrainWriter.Grid parseTerrainGrid(JsonObject stage) {
        options(stage, "filename", "resolution", "origin_x", "origin_y", "width", "height",
                "max_triangle_edge_length", "nodata", "compression", "fill_nodata");
        if (stage.has("fill_nodata")) {
            exact(stage, "fill_nodata", "nearest");
        }
        double maxEdge = stage.has("max_triangle_edge_length") ? number(stage, "max_triangle_edge_length")
                : Double.POSITIVE_INFINITY;
        return new TerrainWriter.Grid(number(stage, "origin_x"), number(stage, "origin_y"),
                number(stage, "resolution"), gridDimension(stage, "width"), gridDimension(stage, "height"),
                maxEdge, nodata(stage), deflate(stage), stage.has("fill_nodata"));
    }

    private static int gridDimension(JsonObject stage, String key) {
        long value = integer(stage, key, 0);
        if (value > GeoTiffOutput.MAX_CELLS) {
            throw new IllegalArgumentException("grid may have at most " + GeoTiffOutput.MAX_CELLS + " cells");
        }
        return (int) value;
    }

    private static double nodata(JsonObject stage) {
        return stage.has("nodata") ? number(stage, "nodata") : GeoTiffOutput.NODATA;
    }

    private static boolean deflate(JsonObject stage) {
        return switch (stage.has("compression") ? string(stage, "compression") : "none") {
            case "none" -> false;
            case "deflate" -> true;
            default -> throw new IllegalArgumentException("compression must be 'none' or 'deflate'");
        };
    }

    private static double number(JsonObject stage, String key) {
        return number(stage.get(key), key);
    }

    private static double number(JsonElement value, String key) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()
                || !Double.isFinite(value.getAsDouble())) {
            throw new IllegalArgumentException(key + " must be a finite number");
        }
        return value.getAsDouble();
    }

    private static JsonObject stageObject(JsonElement value, int position) {
        if (!value.isJsonObject()) {
            throw atStage(position, "unknown", "expected a stage object");
        }
        return value.getAsJsonObject();
    }

    private static String type(JsonObject stage, int position) {
        try {
            return string(stage, "type");
        } catch (IllegalArgumentException e) {
            throw atStage(position, "unknown", e.getMessage());
        }
    }

    private static void options(JsonObject stage, String... allowed) {
        Set<String> names = Set.of(allowed);
        for (String option : stage.keySet()) {
            if (!option.equals("type") && !names.contains(option)) {
                throw new IllegalArgumentException("unsupported option " + option);
            }
        }
    }

    private static String string(JsonObject stage, String key) {
        JsonElement value = stage.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()
                || value.getAsString().isBlank()) {
            throw new IllegalArgumentException(key + " must be a nonempty string");
        }
        return value.getAsString();
    }

    private static void exact(JsonObject stage, String key, String expected) {
        if (!string(stage, key).equals(expected)) {
            throw new IllegalArgumentException(key + " must be '" + expected + "'");
        }
    }

    private static boolean bool(JsonObject stage, String key, boolean fallback) {
        if (!stage.has(key)) {
            return fallback;
        }
        JsonElement value = stage.get(key);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) {
            throw new IllegalArgumentException(key + " must be a boolean");
        }
        return value.getAsBoolean();
    }

    private static long integer(JsonObject stage, String key, long fallback) {
        if (!stage.has(key)) {
            return fallback;
        }
        JsonElement value = stage.get(key);
        if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber() && value.getAsString().matches("[0-9]+")) {
            try {
                return Long.parseLong(value.getAsString());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(key + " exceeds signed 64-bit range");
            }
        }
        throw new IllegalArgumentException(key + " must be a nonnegative integer");
    }

    private static Path filename(String name, boolean source) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (!(lower.endsWith(".las") || source && lower.endsWith(".laz"))) {
            throw new IllegalArgumentException("filename must end in " + (source ? ".las or .laz" : ".las"));
        }
        return Path.of(name);
    }

    private static IllegalArgumentException atStage(int position, String type, String error) {
        return new IllegalArgumentException("Stage " + position + " (" + type + "): " + error);
    }
}
