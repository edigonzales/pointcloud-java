package pointcloud;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.Strictness;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** A linear PDAL-subset pipeline. Execution state is created afresh for each run. */
public final class Pipeline {
    private static final String BOUND = "\\s*\\[\\s*(" + Expression.NUMBER + ")\\s*,\\s*("
            + Expression.NUMBER + ")\\s*]\\s*";
    private static final Pattern BOX = Pattern.compile("\\s*\\(" + BOUND + "," + BOUND
            + "(?:," + BOUND + ")?\\)\\s*");
    static final Gson JSON = new GsonBuilder().setStrictness(Strictness.STRICT)
            .setPrettyPrinting().serializeNulls().create();

    public record Result(long pointsRead, long pointsSelected, long pointsWritten,
                         List<StageStatistics> statistics) {}

    public record StageStatistics(int stage, List<Statistics> dimensions) {}

    public record Statistics(String name, long count, Double minimum, Double maximum,
                             Double average, Double variance, Double stddev) {}

    private final Path input;
    private final Path output;
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
                case "writers.null" -> {
                    options(writer);
                    yield null;
                }
                default -> throw new IllegalArgumentException("expected writers.las or writers.null");
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
        var filters = new Filter[stages.size() - 2];
        for (int i = 0; i < filters.length; i++) {
            filters[i] = compile(i + 2);
        }
        try (var reader = new LasReader(input, batchSize)) {
            for (var filter : filters) {
                for (var dimension : filter.dimensions) {
                    if (!dimension.available(reader.header().pointFormat())) {
                        throw atStage(filter.stage, filter.type,
                                "dimension " + dimension + " is absent from point format " + reader.header().pointFormat());
                    }
                }
            }
            try (var writer = output == null ? null : new LasWriter(reader, output, overwrite)) {
                long selected = 0;
                for (var batch = reader.readBatch(); batch != null; batch = reader.readBatch()) {
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
                        }
                    }
                }
                if (writer != null) {
                    writer.finish();
                }
                var results = new ArrayList<StageStatistics>();
                for (var filter : filters) {
                    if (filter.stats != null) {
                        results.add(new StageStatistics(filter.stage,
                                filter.stats.stream().map(Accumulator::result).toList()));
                    }
                }
                return new Result(reader.header().pointCount(), selected, writer == null ? 0 : writer.pointCount(),
                        List.copyOf(results));
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
                    options(stage, "bounds", "outside");
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
                    options(stage, "dimensions");
                    var list = new ArrayList<Accumulator>();
                    for (String name : string(stage, "dimensions").split(",", -1)) {
                        var dimension = Dimension.parse(name.strip());
                        if (!dimensions.add(dimension)) {
                            throw new IllegalArgumentException("duplicate statistics dimension " + dimension);
                        }
                        list.add(new Accumulator(dimension));
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

    private record Filter(int stage, String type, EnumSet<Dimension> dimensions,
                          Expression.Selection selection, List<Accumulator> stats) {}

    private static final class Accumulator {
        private final Dimension dimension;
        private long count;
        private double min = Double.MAX_VALUE;
        private double max = -Double.MAX_VALUE;
        private double mean;
        private double m2;

        Accumulator(Dimension dimension) {
            this.dimension = dimension;
        }

        void add(double value) {
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
                    variance == null ? null : Math.sqrt(variance));
        }
    }

    private static Double finiteOrNull(double value) {
        return Double.isFinite(value) ? value : null;
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
