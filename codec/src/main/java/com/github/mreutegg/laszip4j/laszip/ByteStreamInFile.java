/*
 * Copyright 2016 Marcel Reutegger
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
// PointCloud Java: deterministic mapping lifetime and long file offsets.
package com.github.mreutegg.laszip4j.laszip;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.channels.FileChannel;

import static java.nio.ByteOrder.BIG_ENDIAN;
import static java.nio.ByteOrder.LITTLE_ENDIAN;

final class ByteStreamInFile extends ByteStreamIn {
    private final Arena arena;
    private final MemorySegment mapped;
    private long position;

    ByteStreamInFile(FileChannel channel, long end) throws IOException {
        arena = Arena.ofConfined();
        try {
            mapped = channel.map(FileChannel.MapMode.READ_ONLY, 0, end, arena);
        } catch (IOException | RuntimeException e) {
            arena.close();
            throw e;
        }
    }

    @Override
    public byte getByte() {
        return mapped.get(ValueLayout.JAVA_BYTE, position++);
    }

    @Override
    public void getBytes(byte[] bytes, int count) {
        MemorySegment.copy(mapped, position, MemorySegment.ofArray(bytes), 0, count);
        position += count;
    }

    @Override
    public char get16bitsLE() {
        char value = mapped.get(ValueLayout.JAVA_CHAR_UNALIGNED.withOrder(LITTLE_ENDIAN), position);
        position += 2;
        return value;
    }

    @Override
    public int get32bitsLE() {
        int value = mapped.get(ValueLayout.JAVA_INT_UNALIGNED.withOrder(LITTLE_ENDIAN), position);
        position += 4;
        return value;
    }

    @Override
    public long get64bitsLE() {
        long value = mapped.get(ValueLayout.JAVA_LONG_UNALIGNED.withOrder(LITTLE_ENDIAN), position);
        position += 8;
        return value;
    }

    @Override
    public char get16bitsBE() {
        char value = mapped.get(ValueLayout.JAVA_CHAR_UNALIGNED.withOrder(BIG_ENDIAN), position);
        position += 2;
        return value;
    }

    @Override
    public int get32bitsBE() {
        int value = mapped.get(ValueLayout.JAVA_INT_UNALIGNED.withOrder(BIG_ENDIAN), position);
        position += 4;
        return value;
    }

    @Override
    public long get64bitsBE() {
        long value = mapped.get(ValueLayout.JAVA_LONG_UNALIGNED.withOrder(BIG_ENDIAN), position);
        position += 8;
        return value;
    }

    @Override
    public boolean isSeekable() {
        return true;
    }

    @Override
    public long tell() {
        return position;
    }

    @Override
    public boolean seek(long offset) {
        if (offset < 0 || offset > mapped.byteSize()) {
            return false;
        }
        position = offset;
        return true;
    }

    @Override
    public boolean seekEnd(long distance) {
        return distance >= 0 && seek(mapped.byteSize() - distance);
    }

    @Override
    public void close() {
        if (arena.scope().isAlive()) {
            arena.close();
        }
    }
}
