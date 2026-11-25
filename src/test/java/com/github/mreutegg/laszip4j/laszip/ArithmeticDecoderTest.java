package com.github.mreutegg.laszip4j.laszip;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArithmeticDecoderTest {
    @ParameterizedTest
    @ValueSource(ints = {16, 17, 2048})
    void roundTripsUniformSymbolsAcrossModelUpdates(int alphabet) {
        int[] expected = new int[2048];
        for (int i = 0; i < expected.length; i++) {
            expected[i] = (5 * i + 3) % alphabet;
        }
        assertRoundTrip(alphabet, expected, false);
    }

    @ParameterizedTest
    @ValueSource(ints = {16, 17, 32, 33, 64, 128, 256, 512, 2048})
    void roundTripsSkewedSymbolsAcrossModelRescaling(int alphabet) {
        int[] expected = new int[70_000];
        for (int i = 0; i < expected.length; i += 4096) {
            expected[i] = i % 8192 == 0 ? alphabet - 1 : alphabet / 2;
        }
        assertRoundTrip(alphabet, expected, true);
    }

    @ParameterizedTest
    @ValueSource(ints = {16, 17, 32, 33, 64, 128, 256, 512, 2048})
    void decodesExactBoundariesAndUnsignedValues(int alphabet) {
        var model = new ArithmeticModel(alphabet, false);
        model.init(null);
        int symbol = alphabet / 2 + 1;
        long scale = 0xffff_ffffL >>> ArithmeticModel.DM__LengthShift;
        long boundary = scale * model.u_distribution[symbol];

        assertFirstSymbol(alphabet, boundary - 1, symbol - 1);
        assertFirstSymbol(alphabet, boundary, symbol);
        assertFirstSymbol(alphabet, boundary + 1, symbol);
        assertFirstSymbol(alphabet, 0x7fff_ffffL, alphabet / 2);
        assertFirstSymbol(alphabet, 0x8000_0000L, alphabet / 2);
        assertFirstSymbol(alphabet, 0xffff_fffeL, alphabet - 1);
    }

    @Test
    void preservesTheUnscaledUpperBoundOfTheLastSymbol() {
        // After the raw bits and a binary symbol, length is 0x0100_3fff and
        // value is length - 1. The 2048-symbol lookup uses the sentinel bucket.
        var input = input(0x01ff_fffeL);
        var decoder = new ArithmeticDecoder();
        decoder.init(input);
        assertEquals(0, decoder.readBits(7));
        assertEquals(1, decoder.decodeSymbol(model(decoder, 2)));
        assertEquals(4, input.tell());

        assertEquals(2047, decoder.decodeSymbol(model(decoder, 2048)));
        assertEquals(6, input.tell());
        assertEquals(1, decoder.readBit());
    }

    @ParameterizedTest
    @CsvSource({"17, 5", "32, 5", "33, 5", "64, 5", "128, 5", "256, 5", "512, 6", "2048, 6"})
    void decodesLargestValidLookupQuotient(int alphabet, int cursorAfterSymbol) {
        var input = input(0x7fff_fffeL);
        var decoder = new ArithmeticDecoder();
        decoder.init(input);
        assertEquals(0, decoder.readBit());
        assertEquals(127, decoder.decodeSymbol(model(decoder, 128)));
        assertEquals(4, input.tell());

        // The length is 0x0100_7eff and the value is length - 1.
        // Dividing the value by (length >>> 15) gives 32,831.
        assertEquals(alphabet - 1, decoder.decodeSymbol(model(decoder, alphabet)));
        assertEquals(cursorAfterSymbol, input.tell());
        assertEquals(1, decoder.readBit());
    }

    private static void assertRoundTrip(int alphabet, int[] expected, boolean expectRescale) {
        var output = new EncodedBytes();
        var encoder = new ArithmeticEncoder();
        encoder.init(output);
        var encoderModel = encoder.createSymbolModel(alphabet);
        encoder.initSymbolModel(encoderModel);
        for (int symbol : expected) {
            encoder.encodeSymbol(encoderModel, symbol);
        }
        encoder.done();

        byte[] bytes = output.bytes.toByteArray();
        var decoder = new ArithmeticDecoder();
        decoder.init(new ByteStreamInArray(bytes, bytes.length));
        var decoderModel = model(decoder, alphabet);
        boolean rescaled = false;
        int[] actual = new int[expected.length];
        for (int i = 0; i < actual.length; i++) {
            int previousTotal = decoderModel.u_total_count;
            actual[i] = decoder.decodeSymbol(decoderModel);
            rescaled |= decoderModel.u_total_count < previousTotal;
        }
        assertArrayEquals(expected, actual);
        assertTrue(decoderModel.u_total_count > alphabet);
        assertEquals(expectRescale, rescaled);
    }

    private static void assertFirstSymbol(int alphabet, long value, int expected) {
        var decoder = new ArithmeticDecoder();
        decoder.init(input(value));
        assertEquals(expected, decoder.decodeSymbol(model(decoder, alphabet)),
                () -> "code value 0x" + Long.toHexString(value));
    }

    private static ArithmeticModel model(ArithmeticDecoder decoder, int alphabet) {
        var model = decoder.createSymbolModel(alphabet);
        decoder.initSymbolModel(model);
        return model;
    }

    private static ByteStreamInArray input(long value) {
        byte[] bytes = ByteBuffer.allocate(8).putInt((int) value).array();
        return new ByteStreamInArray(bytes, bytes.length);
    }

    private static final class EncodedBytes extends ByteStreamOut {
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();

        @Override
        public boolean putByte(byte value) {
            bytes.write(value);
            return true;
        }

        @Override
        public boolean putBytes(byte[] values, int length) {
            bytes.write(values, 0, length);
            return true;
        }

        @Override public boolean put16bitsLE(char value) { throw new UnsupportedOperationException(); }
        @Override public boolean put16bitsLE(short value) { throw new UnsupportedOperationException(); }
        @Override public boolean put32bitsLE(int value) { throw new UnsupportedOperationException(); }
        @Override public boolean put64bitsLE(long value) { throw new UnsupportedOperationException(); }
        @Override boolean put16bitsBE(char value) { throw new UnsupportedOperationException(); }
        @Override boolean put32bitsBE(int value) { throw new UnsupportedOperationException(); }
        @Override boolean put64bitsBE(long value) { throw new UnsupportedOperationException(); }
        @Override public boolean isSeekable() { return false; }
        @Override public long tell() { return bytes.size(); }
        @Override public boolean seek(long position) { throw new UnsupportedOperationException(); }
        @Override public boolean seekEnd() { throw new UnsupportedOperationException(); }
        @Override public void close() {}
    }
}
