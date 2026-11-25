package pointcloud;

import java.io.PrintStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.concurrent.CountDownLatch;

public final class Main {
    private Main() {}

    public static void main(String[] args) {
        launch(args, Pipeline::openSequential);
    }

    static void launch(String[] args, Pipeline.InputFactory inputs) {
        int command = args.length > 0 && args[0].equals("--diagnostics") ? 1 : 0;
        int status;
        if (command < args.length && args[command].equals("pipeline")) {
            Thread execution = Thread.currentThread();
            var completed = new CountDownLatch(1);
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                if (completed.getCount() != 0) {
                    execution.interrupt();
                    try {
                        completed.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            }, "pointcloud-cancel"));
            try {
                status = run(args, System.out, System.err, inputs);
            } finally {
                completed.countDown();
            }
        } else {
            status = run(args, System.out, System.err, inputs);
        }
        System.exit(status);
    }

    static int run(String[] args, PrintStream out, PrintStream err) {
        return run(args, out, err, Pipeline::openSequential);
    }

    private static int run(String[] args, PrintStream out, PrintStream err, Pipeline.InputFactory inputs) {
        boolean diagnostics = args.length > 0 && args[0].equals("--diagnostics");
        if (diagnostics) {
            args = Arrays.copyOfRange(args, 1, args.length);
        }
        if (args.length == 1) {
            switch (args[0]) {
                case "--help" -> {
                    out.print("""
                            Usage: pointcloud [--diagnostics] <command>
                              --help
                              --version
                              info <input.las|input.laz>
                              pipeline <pipeline.json> [--metadata <file>] [--batch-size <points>] [--laz-workers <1-4>] [--overwrite]
                            """);
                    return 0;
                }
                case "--version" -> {
                    out.println("pointcloud 0.1.0");
                    return 0;
                }
            }
        }
        try {
            if (args.length == 2 && args[0].equals("info")) {
                info(Path.of(args[1]), out);
                return 0;
            }
            if (args.length >= 2 && args[0].equals("pipeline")) {
                pipeline(args, out, inputs);
                return 0;
            }
            throw new IllegalArgumentException("Invalid command. Run pointcloud --help.");
        } catch (IOException | RuntimeException e) {
            err.println(e.getMessage());
            if (diagnostics) {
                e.printStackTrace(err);
            }
            return 2;
        }
    }

    private static void info(Path path, PrintStream out) throws IOException {
        try (var source = FileChannel.open(path)) {
            var header = new LasHeader(source);
            out.printf("LAS 1.%d, point format %d, %s%n", header.versionMinor(), header.pointFormat(),
                    header.compressed() ? "LAZ compressed" : "uncompressed");
            out.printf("Header point count: %d%nRecord length: %d%nPoint data offset: %d%n",
                    header.pointCount(), header.recordLength(), header.pointOffset());
            out.printf("Global encoding: 0x%04x%n", header.globalEncoding());
            for (int axis = 0; axis < 3; axis++) {
                out.printf("%c scale=%s offset=%s header bounds=[%s,%s]%n", "XYZ".charAt(axis),
                        header.scale(axis), header.offset(axis), header.minimum(axis), header.maximum(axis));
            }
            var names = new ArrayList<String>();
            for (var dimension : Dimension.values()) {
                if (dimension.available(header.pointFormat())) {
                    names.add(dimension.name());
                }
            }
            names.addAll(java.util.List.of("Synthetic", "KeyPoint", "Withheld", "ScanDirectionFlag",
                    "EdgeOfFlightLine", header.pointFormat() < 6 ? "ScanAngleRank" : "ScanAngle", "UserData", "PointSourceId"));
            if (header.pointFormat() >= 6) {
                names.addAll(java.util.List.of("Overlap", "ScannerChannel"));
            }
            out.println("Standard dimensions: " + String.join(",", names));
            out.println("Extra bytes per record: " + (header.recordLength() - LasHeader.standardLength(header.pointFormat())));
            LasHeader.RecordVisitor print = record -> {
                out.printf("%s %s/%d (%d bytes)%n", record.extended() ? "EVLR" : "VLR",
                        record.userId(), record.recordId(), record.length());
                if (record.userId().equals("LASF_Projection")) {
                    printProjection(source, record, out);
                }
            };
            header.visitMetadata(source, false, print);
            header.visitMetadata(source, true, print);
        }
    }

    private static void printProjection(FileChannel source, LasHeader.VariableRecord record, PrintStream out)
            throws IOException {
        int size = (int) Math.min(record.length(), 65_535);
        switch (record.recordId()) {
            case 2111, 2112, 34737 -> {
                byte[] bytes = LasHeader.read(source, record.payload(), size).array();
                int end = 0;
                while (end < size && bytes[end] != 0) {
                    end++;
                }
                String label = switch (record.recordId()) {
                    case 2111 -> "Math transform WKT";
                    case 2112 -> "Coordinate system WKT";
                    default -> "GeoTIFF citation";
                };
                String value = Pipeline.JSON.toJson(new String(bytes, 0, end, StandardCharsets.UTF_8));
                out.printf("  %s: %s%s%n", label, value, end == size && size < record.length() ? " (truncated)" : "");
            }
            case 34735 -> {
                var keys = LasHeader.read(source, record.payload(), size);
                if (size < 8 || keys.getChar(0) != 1 || keys.getChar(6) > (size - 8) / 8) {
                    out.println("  GeoTIFF key directory is invalid or exceeds the display limit");
                    return;
                }
                for (int i = 0; i < keys.getChar(6); i++) {
                    int offset = 8 + i * 8;
                    String label = switch (keys.getChar(offset)) {
                        case 2048 -> "Geodetic CRS";
                        case 3072 -> "Projected CRS";
                        case 4096 -> "Vertical CRS";
                        default -> null;
                    };
                    if (label != null && keys.getChar(offset + 2) == 0 && keys.getChar(offset + 4) == 1) {
                        int value = keys.getChar(offset + 6);
                        out.printf("  %s: %s%n", label, value >= 1024 && value < 32767
                                ? "EPSG:" + value : "GeoTIFF code " + value);
                    }
                }
            }
            default -> { }
        }
    }

    private static void pipeline(String[] args, PrintStream out, Pipeline.InputFactory inputs) throws IOException {
        Path config = Path.of(args[1]);
        Path metadata = null;
        boolean overwrite = false;
        int batchSize = LasReader.DEFAULT_BATCH_SIZE;
        var options = new HashSet<String>();
        for (int i = 2; i < args.length; i++) {
            String option = args[i];
            if (!options.add(option)) {
                throw new IllegalArgumentException("Duplicate pipeline option " + option);
            }
            switch (option) {
                case "--metadata" -> metadata = Path.of(value(args, ++i, option)).toAbsolutePath();
                case "--batch-size" -> {
                    batchSize = Integer.parseInt(value(args, ++i, option));
                    if (batchSize < 1) {
                        throw new IllegalArgumentException("--batch-size must be positive");
                    }
                }
                case "--laz-workers" -> {
                    int workers = lazWorkers(value(args, ++i, option));
                    inputs = (path, size) -> ParallelLazReader.open(path, size, workers);
                }
                case "--overwrite" -> overwrite = true;
                default -> throw new IllegalArgumentException("Unknown pipeline option " + option);
            }
        }
        var pipeline = Pipeline.read(config);
        Path target = pipeline.output();
        if (target != null && Files.exists(target) && Files.isSameFile(target, config)) {
            throw new IOException("Point cloud output aliases the pipeline configuration: " + target);
        }
        if (metadata == null) {
            out.println(Pipeline.JSON.toJson(pipeline.run(batchSize, overwrite, inputs, Pipeline::publishOutput)));
        } else {
            runWithMetadata(pipeline, config, metadata, batchSize, overwrite, inputs);
        }
    }

    private static void runWithMetadata(Pipeline pipeline, Path config, Path metadata, int batchSize,
                                        boolean overwrite, Pipeline.InputFactory inputs) throws IOException {
        checkMetadata(metadata, pipeline, config, overwrite);
        if (pipeline.output() != null) {
            Path target = destinationLocation(pipeline.output());
            Path json = destinationLocation(metadata);
            if (target.startsWith(json) || json.startsWith(target)) {
                throw new IOException("Output and metadata destinations must be separate files: " + target + " and " + json);
            }
        }
        Files.createDirectories(metadata.getParent());
        Path temporary = Files.createTempFile(metadata.getParent(), ".pointcloud-meta-", ".part");
        try {
            pipeline.run(batchSize, overwrite, inputs, (result, finish) -> {
                Files.writeString(temporary, Pipeline.JSON.toJson(result) + System.lineSeparator());
                publishWithMetadata(temporary, metadata, pipeline, config, overwrite, finish);
            });
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void publishWithMetadata(Path temporary, Path metadata, Pipeline pipeline, Path config,
                                            boolean overwrite, Pipeline.OutputFinish finish) throws IOException {
        checkMetadata(metadata, pipeline, config, overwrite);
        Path previous = null;
        boolean published = false;
        try {
            if (overwrite && Files.exists(metadata, LinkOption.NOFOLLOW_LINKS)) {
                previous = saveMetadata(metadata);
            }
            if (overwrite) {
                Files.move(temporary, metadata, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } else {
                Files.move(temporary, metadata);
            }
            published = true;
            if (finish != null) {
                // Publishing metadata can make an alias of a previously absent output target visible.
                checkMetadataAliases(metadata, pipeline, config);
                finish.finish();
            }
        } catch (IOException | RuntimeException failure) {
            try {
                if (previous != null) {
                    Files.move(previous, metadata, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } else if (published) {
                    Files.deleteIfExists(metadata);
                }
            } catch (IOException | RuntimeException rollback) {
                failure.addSuppressed(rollback);
            }
            throw failure;
        }
        if (previous != null) {
            Files.delete(previous);
        }
    }

    private static Path saveMetadata(Path metadata) throws IOException {
        Path previous = Files.createTempFile(metadata.getParent(), ".pointcloud-previous-meta-", ".part");
        try {
            return Files.move(metadata, previous, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | RuntimeException failure) {
            try {
                Files.deleteIfExists(previous);
            } catch (IOException | RuntimeException cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    private static void checkMetadata(Path metadata, Pipeline pipeline, Path config, boolean overwrite)
            throws IOException {
        checkMetadataAliases(metadata, pipeline, config);
        if (Files.exists(metadata, LinkOption.NOFOLLOW_LINKS)
                && (!overwrite || Files.isDirectory(metadata, LinkOption.NOFOLLOW_LINKS))) {
            throw new FileAlreadyExistsException(metadata.toString());
        }
    }

    private static void checkMetadataAliases(Path metadata, Pipeline pipeline, Path config) throws IOException {
        for (Path path : new Path[]{pipeline.input(), pipeline.output(), config}) {
            if (path != null && (metadata.normalize().equals(path.toAbsolutePath().normalize())
                    || Files.exists(metadata) && Files.exists(path) && Files.isSameFile(metadata, path))) {
                throw new IOException("Metadata destination aliases a pipeline file: " + metadata);
            }
        }
    }

    private static Path destinationLocation(Path path) throws IOException {
        Path absolute = path.toAbsolutePath();
        Path parent = absolute.getParent();
        while (parent != null && !Files.exists(parent)) {
            parent = parent.getParent();
        }
        return parent == null ? absolute.normalize()
                : parent.toRealPath().resolve(parent.relativize(absolute)).normalize();
    }

    private static int lazWorkers(String value) {
        try {
            int workers = Integer.parseInt(value);
            if (workers >= 1 && workers <= 4) {
                return workers;
            }
        } catch (NumberFormatException ignored) {
            // Use the same option-specific diagnostic for invalid numbers and values outside the range.
        }
        throw new IllegalArgumentException("--laz-workers must be an integer between 1 and 4");
    }

    private static String value(String[] args, int index, String option) {
        if (index >= args.length || args[index].startsWith("--")) {
            throw new IllegalArgumentException(option + " requires a value");
        }
        return args[index];
    }
}
