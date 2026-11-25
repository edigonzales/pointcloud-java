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
import java.io.Reader;
import java.io.StringReader;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;

/** A linear PDAL-subset pipeline. Execution state is created afresh for each run. */
public final class Pipeline {
    private static final String RASTER_WRITER = "writers.raster";
    private static final String BOUND = "\\s*\\[\\s*(" + Expression.NUMBER + ")\\s*,\\s*("
            + Expression.NUMBER + ")\\s*]\\s*";
    private static final Pattern BOX = Pattern.compile("\\s*\\(" + BOUND + "," + BOUND
            + "(?:," + BOUND + ")?\\)\\s*");
    static final Gson JSON = new GsonBuilder().setStrictness(Strictness.STRICT)
            .setPrettyPrinting().serializeNulls().create();

    public record Result(long pointsRead, long pointsSelected, long pointsWritten,
                         List<StageStatistics> statistics,
                         @JsonAdapter(value = OmitNull.class, nullSafe = false) Long pointsBinned) {
        public Result(long pointsRead, long pointsSelected, long pointsWritten, List<StageStatistics> statistics) {
            this(pointsRead, pointsSelected, pointsWritten, statistics, null);
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
    private final GroundGridWriter.Grid rasterGrid;
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
        try {
            rasterGrid = type.equals(RASTER_WRITER) ? parseRasterGrid(writer) : null;
            output = switch (type) {
                case "writers.las" -> {
                    options(writer, "filename", "forward", "compression", "extra_dims");
                    exact(writer, "forward", "all");
                    exact(writer, "extra_dims", "all");
                    if (bool(writer, "compression", false)) {
                        throw new IllegalArgumentException("compression must be false");
                    }
                    yield filename(string(writer, "filename"), false);
                }
                case RASTER_WRITER -> {
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
                default -> throw new IllegalArgumentException("expected writers.las, " + RASTER_WRITER + " or writers.null");
            };
        } catch (IllegalArgumentException e) {
            throw atStage(position, type, e.getMessage());
        }
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

    private GroundGridWriter openRaster(BatchInput reader, boolean overwrite) throws IOException {
        if (rasterGrid == null) {
            return null;
        }
        try {
            return new GroundGridWriter(input, output, overwrite, rasterGrid, reader.header(), reader.channel());
        } catch (IOException e) {
            throw new IOException("Stage " + stages.size() + " (" + RASTER_WRITER + "): " + e.getMessage(), e);
        }
    }

    Result run(int batchSize, boolean overwrite, InputFactory inputs, ResultOutput resultOutput) throws IOException {
        var filters = new Filter[stages.size() - 2];
        for (int i = 0; i < filters.length; i++) {
            filters[i] = compile(i + 2);
        }
        try (var reader = inputs.open(input, batchSize)) {
            for (var filter : filters) {
                for (var dimension : filter.dimensions) {
                    if (!dimension.available(reader.header().pointFormat())) {
                        throw atStage(filter.stage, filter.type,
                                "dimension " + dimension + " is absent from point format " + reader.header().pointFormat());
                    }
                }
            }
            try (var writer = output == null || rasterGrid != null ? null
                    : new LasWriter(input, reader.channel(), reader.header(), reader.finished(), output, overwrite);
                 var raster = openRaster(reader, overwrite)) {
                long selected = 0;
                for (var batch = reader.reader().readBatch(); batch != null; batch = reader.reader().readBatch()) {
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
                        }
                    }
                }
                var results = new ArrayList<StageStatistics>();
                for (var filter : filters) {
                    if (filter.stats != null) {
                        results.add(new StageStatistics(filter.stage,
                                filter.stats.stream().map(Accumulator::result).toList()));
                    }
                }
                OutputFinish finish = writer == null ? null : writer::finish;
                Long pointsBinned = null;
                if (raster != null) {
                    raster.prepare(reader.finished().getAsBoolean());
                    pointsBinned = raster.pointsBinned();
                    finish = raster::finish;
                }
                var result = new Result(reader.header().pointCount(), selected, writer == null ? 0 : writer.pointCount(),
                        List.copyOf(results), pointsBinned);
                resultOutput.publish(result, finish);
                return result;
            }
        }
    }

    private Filter compile(int position) {
        JsonObject stage = stageObject(stages.get(position - 1), position);
        String type = type(stage, position);
        var dimensions = EnumSet.noneOf(Dimension.class);
        try {
            switch (type) {
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
                "output_type", "dimension", "data_type", "nodata");
        if (!bool(stage, "binmode", false)) {
            throw new IllegalArgumentException("binmode must be true");
        }
        var types = string(stage, "output_type").split(",", -1);
        var bands = new HashSet<String>();
        for (String type : types) {
            bands.add(type.strip());
        }
        if (types.length != 4 || !bands.equals(Set.of("min", "max", "mean", "count"))) {
            throw new IllegalArgumentException("output_type must list min, max, mean and count once each");
        }
        if (stage.has("dimension")) {
            exact(stage, "dimension", "Z");
        }
        if (stage.has("data_type")) {
            exact(stage, "data_type", "double");
        }
        if (stage.has("nodata") && number(stage, "nodata") != GeoTiffOutput.NODATA) {
            throw new IllegalArgumentException("nodata must be -9999");
        }
        long width = integer(stage, "width", 0);
        long height = integer(stage, "height", 0);
        if (width > GroundGridWriter.Grid.MAX_CELLS || height > GroundGridWriter.Grid.MAX_CELLS) {
            throw new IllegalArgumentException("grid may have at most " + GroundGridWriter.Grid.MAX_CELLS + " cells");
        }
        return new GroundGridWriter.Grid(number(stage, "origin_x"), number(stage, "origin_y"),
                number(stage, "resolution"), (int) width, (int) height);
    }

    private static double number(JsonObject stage, String key) {
        JsonElement value = stage.get(key);
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
