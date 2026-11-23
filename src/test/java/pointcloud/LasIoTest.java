package pointcloud;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class LasIoTest {
    @TempDir Path tempDir;

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3, 6, 7, 8})
    void decodesAndWritesCompleteOrderedRecords(int format) throws IOException {
        Path las = fixture(format, "las");
        ByteBuffer original = bytes(las);
        int recordStart = original.getInt(96);
        int length = original.getChar(105);
        for (String extension : new String[]{"las", "laz"}) {
            Path input = fixture(format, extension);
            Path output = tempDir.resolve("selected-" + extension + ".las");
            try (var reader = new LasReader(input, 2);
                 var writer = new LasWriter(reader, output, false)) {
                var header = reader.header();
                assertEquals(format, header.pointFormat());
                assertEquals(5, header.pointCount());
                assertEquals(length, header.recordLength());
                assertEquals(extension.equals("laz"), header.compressed());
                int index = 0;
                byte[] record = new byte[length];
                for (var batch = reader.readBatch(); batch != null; batch = reader.readBatch()) {
                    assertEquals(index == 4 ? 1 : 2, batch.size());
                    for (int i = 0; i < batch.size(); i++, index++) {
                        batch.copyRecord(i, record, 0);
                        int start = recordStart + index * length;
                        assertArrayEquals(Arrays.copyOfRange(original.array(), start, start + length), record,
                                "format " + format + " " + extension + " point " + index);
                        if (index == 0) {
                            assertEquals(-200, batch.rawX(i));
                            assertEquals(2593998.0, batch.x(i));
                            assertEquals(65535, batch.intensity(i));
                            assertEquals(2, batch.classification(i));
                        }
                        if (index == 1 || index == 3) {
                            writer.write(batch, i);
                        }
                    }
                }
                assertEquals(5, index);
                assertFalse(Files.exists(output));
                writer.finish();
            }
            ByteBuffer written = bytes(output);
            int start = written.getInt(96);
            assertEquals(format, written.get(104));
            assertEquals(length, written.getChar(105));
            assertEquals(0.02, written.getDouble(139));
            assertEquals(1227500.0, written.getDouble(163));
            assertEquals(2594000.0, written.getDouble(187));
            assertEquals(2594004.0, written.getDouble(179));
            assertEquals(1227495.8, written.getDouble(203));
            assertEquals(1227498.6, written.getDouble(195));
            assertEquals(700.025, written.getDouble(219));
            assertEquals(700.345, written.getDouble(211));
            if (format < 6) {
                assertEquals(2, written.getInt(107));
                assertEquals(1, written.getInt(115));
                assertEquals(1, written.getInt(123));
            } else {
                assertEquals(0, written.getInt(107));
                for (int i = 0; i < 5; i++) {
                    assertEquals(0, written.getInt(111 + i * 4));
                }
                assertEquals(2, written.getLong(247));
                assertEquals(1, written.getLong(263));
                assertEquals(1, written.getLong(279));
            }
            assertArrayEquals(Arrays.copyOfRange(original.array(), recordStart + length, recordStart + 2 * length),
                    Arrays.copyOfRange(written.array(), start, start + length));
            assertArrayEquals(Arrays.copyOfRange(original.array(), recordStart + 3 * length, recordStart + 4 * length),
                    Arrays.copyOfRange(written.array(), start + length, start + 2 * length));
        }
    }

    @Test
    void keepsOpaqueMetadataAndExtraBytesDescriptors() throws IOException {
        Path input = fixture(8, "las");
        Path output = tempDir.resolve("one.las");
        try (var reader = new LasReader(input, 1); var writer = new LasWriter(reader, output, false)) {
            writer.write(reader.readBatch(), 0);
            while (reader.readBatch() != null) {
                // Validate the remainder of the source.
            }
            writer.finish();
        }
        var original = bytes(input);
        var written = bytes(output);
        int firstVlr = original.getChar(94);
        int points = written.getInt(96);
        assertEquals(original.getInt(96), points);
        assertEquals(2, written.getInt(100));
        assertArrayEquals(Arrays.copyOfRange(original.array(), firstVlr, points),
                Arrays.copyOfRange(written.array(), firstVlr, points));
        assertEquals(1, written.getLong(247));
        assertEquals(1, written.getInt(243));
        assertEquals(points + 40, written.getLong(235));
        assertEquals(written.getDouble(179), written.getDouble(187));
        assertArrayEquals(Arrays.copyOfRange(original.array(), (int) original.getLong(235), original.capacity()),
                Arrays.copyOfRange(written.array(), (int) written.getLong(235), written.capacity()));
    }

    @Test
    void writesAndReadsAnEmptyLas() throws IOException {
        Path output = tempDir.resolve("empty.las");
        try (var reader = new LasReader(fixture(3, "laz"), 2); var writer = new LasWriter(reader, output, false)) {
            while (reader.readBatch() != null) {
                // An empty selection still verifies the entire input.
            }
            writer.finish();
        }
        try (var reader = new LasReader(output, 1)) {
            assertEquals(0, reader.header().pointCount());
            assertEquals(0.0, reader.header().minimum(0));
            assertEquals(0.0, reader.header().maximum(0));
            assertNull(reader.readBatch());
        }
    }

    @Test
    void rejectsTruncationInvalidSizesAndUnsupportedWaveforms() throws IOException {
        Path input = tempDir.resolve("broken.las");
        var base = bytes(fixture(3, "las"));
        Files.write(input, Arrays.copyOf(base.array(), 120));
        assertBadInput(input, "Unexpected end");
        Files.write(input, Arrays.copyOf(base.array(), base.capacity() - 1));
        assertBadInput(input, "Truncated LAS point records");
        Files.write(input, base.putInt(107, -1).array());
        assertBadInput(input, "Truncated LAS point records");
        Files.write(input, base.putInt(107, 5).putChar(105, (char) 1).array());
        assertBadInput(input, "record length");
        Files.write(input, base.put(104, (byte) 5).array());
        assertBadInput(input, "Waveform point format 5");
        Files.write(input, base.putInt(0, 0).array());
        assertBadInput(input, "Invalid LASF");
    }

    @Test
    void rejectsCorruptChunkTablesInsteadOfReturningAnEmptyStream() throws IOException {
        Path input = tempDir.resolve("broken.laz");
        var base = bytes(fixture(3, "laz"));
        int chunkTable = (int) base.getLong(base.getInt(96));
        Files.write(input, Arrays.copyOf(base.array(), chunkTable));
        assertBadInput(input, "chunk table");
        Files.write(input, base.putInt(chunkTable + 4, -1).array());
        assertBadInput(input, "chunk table");
    }

    @Test
    void rejectsAnOversizedLayerBeforeAllocationAndDiscardsTheOutput() throws IOException {
        Path input = tempDir.resolve("broken.laz");
        Path output = tempDir.resolve("broken.las");
        var base = bytes(fixture(8, "laz"));
        int firstLayerLength = base.getInt(96) + 8 + base.getChar(105) + 4;
        Files.write(input, base.putInt(firstLayerLength, Integer.MAX_VALUE).array());
        try (var reader = new LasReader(input, 1); var writer = new LasWriter(reader, output, false)) {
            assertTrue(assertThrows(IOException.class, reader::readBatch).getMessage().contains("layer payload"));
        }
        try (var files = Files.list(tempDir)) {
            assertEquals(1, files.count());
        }
    }

    @Test
    void abortedWriteAndExistingOrAliasedTargetsAreSafe() throws IOException {
        Path input = Files.copy(fixture(3, "las"), tempDir.resolve("input.las"));
        Path alias = Files.createSymbolicLink(tempDir.resolve("alias.las"), input);
        Path target = tempDir.resolve("output.las");
        try (var reader = new LasReader(input, 1)) {
            assertThrows(IOException.class, () -> new LasWriter(reader, alias, true));
            try (var writer = new LasWriter(reader, target, false)) {
                writer.write(reader.readBatch(), 0);
                assertThrows(IOException.class, writer::finish);
            }
            assertFalse(Files.exists(target));
            Files.writeString(target, "keep");
            assertThrows(IOException.class, () -> new LasWriter(reader, target, false));
            assertEquals("keep", Files.readString(target));
        }
        try (var paths = Files.list(tempDir)) {
            assertEquals(3, paths.count());
        }
    }

    private static void assertBadInput(Path path, String reason) {
        IOException failure = assertThrows(IOException.class, () -> {
            try (var reader = new LasReader(path, 1)) {
                while (reader.readBatch() != null) {
                    // Read until the structural error is reported.
                }
            }
        });
        assertTrue(failure.getMessage().contains(reason), failure::getMessage);
    }

    static Path fixture(int format, String extension) {
        return Path.of("src/test/resources/las/format-" + format + "." + extension);
    }

    static ByteBuffer bytes(Path path) throws IOException {
        return ByteBuffer.wrap(Files.readAllBytes(path)).order(ByteOrder.LITTLE_ENDIAN);
    }
}
