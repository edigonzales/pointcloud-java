/*
 * Copyright 2007-2014, martin isenburg, rapidlasso - fast tools to catch reality
 *
 * This is free software; you can redistribute and/or modify it under the
 * terms of the GNU Lesser General Licence as published by the Free Software
 * Foundation. See the LICENSE.txt file for more information.
 *
 * This software is distributed WITHOUT ANY WARRANTY and without even the
 * implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */
// PointCloud Java: validate chunk tables and layer sizes, and propagate decoder failures.
package com.github.mreutegg.laszip4j.laszip;

import java.io.EOFException;

import static com.github.mreutegg.laszip4j.laszip.LASzip.LASZIP_CODER_ARITHMETIC;
import static com.github.mreutegg.laszip4j.laszip.LASzip.LASZIP_COMPRESSOR_POINTWISE;
import static com.github.mreutegg.laszip4j.laszip.LASzip.LASZIP_COMPRESSOR_LAYERED_CHUNKED;
import static com.github.mreutegg.laszip4j.laszip.MyDefs.U32_MAX;
import static java.lang.Boolean.FALSE;
import static java.lang.Boolean.TRUE;

public class LASreadPoint {

    private ByteStreamIn instream;
    private int num_readers; // unsigned
    private LASreadItem[] readers;
    private LASreadItem[] readers_raw;
    private LASreadItem[] readers_compressed;
    private ArithmeticDecoder dec;
    private boolean layered_las14_compression;
    // used for chunking
    private int chunk_size; // unsigned
    private int chunk_count; // unsigned
    private int current_chunk; // unsigned
    private int number_chunks; // unsigned
    private int tabled_chunks; // unsigned
    private long[] chunk_starts;
    private int[] chunk_totals; // unsigned
    private long pointCount;
    private long pointEnd;
      // used for selective decompression (new LAS 1.4 point types only)
    private int decompress_selective;
    // used for seeking
    private long point_start;
    private int point_size; // unsigned
    private PointDataRecord[] seek_point;
    // used for error and warning reporting
    private String last_error;
    private String last_warning;

    public LASreadPoint(int decompress_selective)
    {
        point_size = 0;
        instream = null;
        num_readers = 0;
        readers = null;
        readers_raw = null;
        readers_compressed = null;
        dec = null;
        layered_las14_compression = false;
        // used for chunking
        chunk_size = U32_MAX;
        chunk_count = 0;
        current_chunk = 0;
        number_chunks = 0;
        tabled_chunks = 0;
        chunk_totals = null;
        chunk_starts = null;
        // used for selective decompression (new LAS 1.4 point types only)
        this.decompress_selective = decompress_selective;
        // used for seeking
        point_start = 0;
        seek_point = null;
        // used for error and warning reporting
        last_error = null;
        last_warning = null;
    }

    public boolean setup(int num_items /*unsigned*/, LASitem[] items) {
        return setup(num_items, items, null);
    }

    public boolean setup(int num_items /*unsigned*/, LASitem[] items, LASzip laszip)
    {
        int i;

        // is laszip exists then we must use its items
        if (laszip != null)
        {
            if (num_items != laszip.num_items) return FALSE;
            if (items != laszip.items) return FALSE;
        }

        // create entropy decoder (if requested)
        if (dec != null)
        {
            dec = null;
            layered_las14_compression = false;
        }
        if (laszip != null && laszip.compressor != 0)
        {
            switch (laszip.coder)
            {
                case LASZIP_CODER_ARITHMETIC:
                    dec = new ArithmeticDecoder();
                    break;
                default:
                    // entropy decoder not supported
                    return FALSE;
            }
            // maybe layered compression for LAS 1.4 
            layered_las14_compression = (laszip.compressor == LASZIP_COMPRESSOR_LAYERED_CHUNKED);
        }

        // initizalize the readers
        readers = null;
        num_readers = num_items;

        // disable chunking
        chunk_size = (int) U32_MAX;

        // always create the raw readers
        readers_raw = new LASreadItem[num_readers];
        for (i = 0; i < num_readers; i++)
        {
            switch (items[i].type)
            {
                case POINT10:
                    readers_raw[i] = new LASreadItemRaw_POINT10();
                    break;
                case GPSTIME11:
                    readers_raw[i] = new LASreadItemRaw_GPSTIME11();
                    break;
                case RGB12:
                case RGB14:
                    readers_raw[i] = new LASreadItemRaw_RGB12();
                    break;
                case WAVEPACKET13:
                case WAVEPACKET14:
                    readers_raw[i] = new LASreadItemRaw_WAVEPACKET13();
                    break;
                case BYTE:
                case BYTE14:
                    readers_raw[i] = new LASreadItemRaw_BYTE(items[i].size);
                    break;
                case POINT14:
                    readers_raw[i] = new LASreadItemRaw_POINT14();
                    break;
                case RGBNIR14:
                    readers_raw[i] = new LASreadItemRaw_RGBNIR14();
                    break;

                default:
                    return FALSE;
            }
            point_size += items[i].size;
        }

        if (dec != null)
        {
            readers_compressed = new LASreadItem[num_readers];
            // seeks with compressed data need a seek point
            seek_point = new PointDataRecord[num_items];
            for (i = 0; i < num_readers; i++)
            {
                switch (items[i].type)
                {
                    case POINT10:
                        seek_point[i] = new PointDataRecordPoint10();
                        if (items[i].version == 1)
                            readers_compressed[i] = new LASreadItemCompressed_POINT10_v1(dec);
                        else if (items[i].version == 2)
                            readers_compressed[i] = new LASreadItemCompressed_POINT10_v2(dec);
                        else
                            return FALSE;
                        break;
                    case GPSTIME11:
                        seek_point[i] = new PointDataRecordGpsTime();
                        if (items[i].version == 1)
                            readers_compressed[i] = new LASreadItemCompressed_GPSTIME11_v1(dec);
                        else if (items[i].version == 2)
                            readers_compressed[i] = new LASreadItemCompressed_GPSTIME11_v2(dec);
                        else
                            return FALSE;
                        break;
                    case RGB12:
                        seek_point[i] = new PointDataRecordRGB();
                        if (items[i].version == 1)
                            readers_compressed[i] = new LASreadItemCompressed_RGB12_v1(dec);
                        else if (items[i].version == 2)
                            readers_compressed[i] = new LASreadItemCompressed_RGB12_v2(dec);
                        else 
                            return FALSE;
                        break;
                    case RGB14:
                        seek_point[i] = new PointDataRecordRGB();
                        if ((items[i].version == 4) || (items[i].version == 3) || (items[i].version == 2))
                            readers_compressed[i] = new LASreadItemCompressed_RGB14_v3(dec, decompress_selective);
                        else 
                            return FALSE;
                        break;
                    case WAVEPACKET13:
                        seek_point[i] = new PointDataRecordWavepacket();
                        if (items[i].version == 1)
                            readers_compressed[i] = new LASreadItemCompressed_WAVEPACKET13_v1(dec);
                        else
                            return FALSE;
                        break;
                    case WAVEPACKET14:
                        seek_point[i] = new PointDataRecordWavepacket();
                        if ( (items[i].version == 4) || (items[i].version == 3))
                            readers_compressed[i] = new LASreadItemCompressed_WAVEPACKET14_v3(dec, decompress_selective);
                        else
                            return FALSE;
                        break;
                    case BYTE:
                        seek_point[i] = new PointDataRecordBytes(items[i].size);
                        if (items[i].version == 1)
                            readers_compressed[i] = new LASreadItemCompressed_BYTE_v1(dec, items[i].size);
                        else if (items[i].version == 2)
                            readers_compressed[i] = new LASreadItemCompressed_BYTE_v2(dec, items[i].size);
                        else
                            return FALSE;
                        break;
                    case BYTE14:
                        seek_point[i] = new PointDataRecordBytes(items[i].size);
                        if ((items[i].version == 4) || (items[i].version == 3))
                            readers_compressed[i] = new LASreadItemCompressed_BYTE14_v3(dec, items[i].size, decompress_selective);
                        else
                            return FALSE;
                        break;
                    case POINT14:
                        seek_point[i] = new PointDataRecordPoint14();
                        if ((items[i].version == 4) || (items[i].version == 3) || (items[i].version == 2)) // version == 2 from lasproto
                            readers_compressed[i] = new LASreadItemCompressed_POINT14_v3(dec, decompress_selective);
                        else
                            return FALSE;
                        break;
                    case RGBNIR14:
                        seek_point[i] = new PointDataRecordRgbNIR();
                        if ((items[i].version == 4) || (items[i].version == 3) || (items[i].version == 2)) // version == 2 from lasproto
                            readers_compressed[i] = new LASreadItemCompressed_RGBNIR14_v3(dec, decompress_selective);
                        else
                            return FALSE;
                        break;
                    default:
                        return FALSE;
                }
            }
            if (laszip.compressor != LASZIP_COMPRESSOR_POINTWISE)
            {
                if (laszip.chunk_size != 0) chunk_size = laszip.chunk_size;
                number_chunks = (int) U32_MAX;
            }
        }
        return TRUE;
    }

    public boolean init(ByteStreamIn instream)
    {
        if (instream == null) return FALSE;
        this.instream = instream;

        int i;
        for (i = 0; i < num_readers; i++)
        {
            ((LASreadItemRaw)(readers_raw[i])).init(instream);
        }

        if (dec != null)
        {
            chunk_count = chunk_size;
            point_start = 0;
            readers = null;
        }
        else
        {
            point_start = instream.tell();
            readers = readers_raw;
        }

        return TRUE;
    }

    public boolean seek(int current /*unsigned*/, int target /*unsigned*/)
    {
        if (!instream.isSeekable()) return FALSE;
        int delta = 0; // unsigned
        if (dec != null)
        {
            if (point_start == 0)
            {
                init_dec();
                chunk_count = 0;
            }
            if (chunk_starts != null)
            {
                int target_chunk; // unsigned
                if (chunk_totals != null)
                {
                    target_chunk = search_chunk_table(target, 0, number_chunks);
                    chunk_size = chunk_totals[target_chunk+1]-chunk_totals[target_chunk];
                    delta = target - chunk_totals[target_chunk];
                }
                else
                {
                    target_chunk = target/chunk_size;
                    delta = target%chunk_size;
                }
                if (Integer.compareUnsigned(target_chunk, tabled_chunks) >= 0)
                {
                    if (Integer.compareUnsigned(current_chunk, (tabled_chunks-1)) < 0)
                    {
                        dec.done();
                        current_chunk = (tabled_chunks-1);
                        instream.seek(chunk_starts[current_chunk]);
                        init_dec();
                        chunk_count = 0;
                    }
                    delta += (chunk_size*(target_chunk-current_chunk) - chunk_count);
                }
                else if (current_chunk != target_chunk || Integer.compareUnsigned(current, target) > 0)
                {
                    dec.done();
                    current_chunk = target_chunk;
                    instream.seek(chunk_starts[current_chunk]);
                    init_dec();
                    chunk_count = 0;
                }
                else
                {
                    delta = target - current;
                }
            }
            else if (Integer.compareUnsigned(current, target) > 0)
            {
                dec.done();
                instream.seek(point_start);
                init_dec();
                delta = target;
            }
            else if (Integer.compareUnsigned(current, target) < 0)
            {
                delta = target - current;
            }
            while (delta != 0)
            {
                read(seek_point);
                delta--;
            }
        }
        else
        {
            if (current != target)
            {
                instream.seek(point_start+point_size*target);
            }
        }
        return TRUE;
    }

    public boolean read(PointDataRecord[] pointRecords)
    {
        int i; // unsigned
        MutableInteger context = new MutableInteger(0);

        try
        {
            if (dec != null)
            {
                if (chunk_count == chunk_size)
                {
                    if (point_start != 0)
                    {
                        dec.done();
                        current_chunk++;
                        // check integrity
                        if (current_chunk < tabled_chunks)
                        {
                            long here = instream.tell();
                            if (chunk_starts[current_chunk] != here)
                            {
                                // previous chunk was corrupt
                                current_chunk--;
                                throw new RuntimeException("4711");
                            }
                        }
                    }
                    init_dec();
                    if (chunk_totals != null) {
                        chunk_size = chunk_totals[current_chunk + 1] - chunk_totals[current_chunk];
                    }
                    chunk_count = 0;
                }
                chunk_count++;

                if (readers != null)
                {
                    for (i = 0; i < num_readers; i++)
                    {
                        pointRecords[i] = readers[i].read(context);
                    }
                }
                else
                {
                    for (i = 0; i < num_readers; i++)
                    {
                        pointRecords[i] = readers_raw[i].read(context);
                    }
                    if (layered_las14_compression)
                    {
                        // for layered compression 'dec' only hands over the stream
                        dec.setByteStreamIn(instream);
                        long declaredPoints = Integer.toUnsignedLong(instream.get32bitsLE());
                        long expectedPoints = chunk_totals != null ? Integer.toUnsignedLong(chunk_size)
                                : Math.min(Integer.toUnsignedLong(chunk_size),
                                        pointCount - (long) current_chunk * Integer.toUnsignedLong(chunk_size));
                        if (declaredPoints != expectedPoints) {
                            throw new IllegalArgumentException("invalid layered chunk point count " + declaredPoints);
                        }
                        long layerBytes = 0;
                        for (i = 0; i < num_readers; i++) {
                            layerBytes += ((LASreadItemCompressed) readers_compressed[i]).chunk_sizes();
                        }
                        long end = chunk_starts == null ? pointEnd : chunk_starts[current_chunk + 1];
                        long available = end - instream.tell();
                        if (layerBytes > (64L << 20) || layerBytes > available
                                || chunk_starts != null && layerBytes != available) {
                            throw new IllegalArgumentException("invalid or oversized LAZ layer payload");
                        }
                        for (i = 0; i < num_readers; i++)
                        {
                            ((LASreadItemCompressed)(readers_compressed[i])).init(pointRecords[i], context);
                        }
                    }
                    else
                    {
                        for (i = 0; i < num_readers; i++)
                        {
                            ((LASreadItemCompressed)(readers_compressed[i])).init(pointRecords[i], context);
                        }
                        dec.init(instream);
                    }
            
                    readers = readers_compressed;
                }
            }
            else
            {
                for (i = 0; i < num_readers; i++)
                {
                    pointRecords[i] = readers[i].read(context);
                }
            }
        }
        catch (Exception exception)
        {
            // report error
            if (exception instanceof EOFException)
            {
                // end-of-file
                if (dec != null)
                {
                    last_error = String.format("end-of-file during chunk with index %d", current_chunk);
                }
                else
                {
                    last_error = "end-of-file";
                }
            }
            else
            {
                // decompression error
                last_error = "corrupt LAZ chunk " + current_chunk + ": " + exception.getMessage();
                // if we know where the next chunk starts ...
                if ((current_chunk+1) < tabled_chunks)
                {
                    // ... try to seek to the next chunk
                    instream.seek(chunk_starts[(current_chunk+1)]);
                    // ... ready for next read()
                    chunk_count = chunk_size;
                }
            }
            return FALSE;
        }
        return TRUE;
    }

    public boolean check_end()
    {
        if (readers == readers_compressed)
        {
            if (dec != null)
            {
                dec.done();
                current_chunk++;
                // check integrity
                if (current_chunk < tabled_chunks)
                {
                    long here = instream.tell();
                    if (chunk_starts[current_chunk] != here)
                    {
                        // last chunk was corrupt
                        last_error = String.format("chunk with index %d of %d is corrupt", current_chunk, tabled_chunks);
                        return FALSE;
                    }
                }
            }
        }
        return TRUE;
    }

    public boolean done()
    {
        instream = null;
        return TRUE;
    }

    void init_dec() {
        point_start = instream.tell();
        readers = null;
    }

    void initialiseChunks(long count, long end) {
        pointCount = count;
        pointEnd = end;
        if (number_chunks != U32_MAX) {
            return;
        }
        long locator = instream.tell();
        long table = instream.get64bitsLE();
        long first = instream.tell();
        boolean adaptive = chunk_size == U32_MAX;
        if (table == locator && !adaptive) {
            // Fixed chunks can also be decoded sequentially without a table.
            number_chunks = 0;
            return;
        }
        long tableEnd = end;
        if (table == -1) {
            tableEnd -= 8;
            if (tableEnd < first || !instream.seek(tableEnd)) {
                throw new IllegalArgumentException("missing LAZ chunk table locator");
            }
            table = instream.get64bitsLE();
        }
        if (table < first || table > tableEnd - 8 || !instream.seek(table)
                || instream.get32bitsLE() != 0) {
            throw new IllegalArgumentException("invalid LAZ chunk table offset or version");
        }
        long chunks = Integer.toUnsignedLong(instream.get32bitsLE());
        long step = Integer.toUnsignedLong(chunk_size);
        long expected = count == 0 ? 0 : 1 + (count - 1) / step;
        if (chunks > 1_000_000 || (adaptive ? chunks > count || count != 0 && chunks == 0 : chunks != expected)) {
            throw new IllegalArgumentException("invalid or oversized LAZ chunk table: " + chunks + " chunks");
        }
        number_chunks = (int) chunks;
        chunk_starts = new long[number_chunks + 1];
        chunk_starts[0] = first;
        if (adaptive) {
            chunk_totals = new int[number_chunks + 1];
        }
        if (number_chunks != 0) {
            dec.init(instream);
            var integers = new IntegerCompressor(dec, 32, 2);
            integers.initDecompressor();
            int previousPoints = 0, previousBytes = 0;
            long totalPoints = 0;
            for (int i = 1; i <= number_chunks; i++) {
                if (adaptive) {
                    previousPoints = integers.decompress(previousPoints, 0);
                    totalPoints += Integer.toUnsignedLong(previousPoints);
                    if (previousPoints == 0 || totalPoints > count || totalPoints > 0xffff_ffffL) {
                        throw new IllegalArgumentException("invalid adaptive LAZ chunk point count");
                    }
                    chunk_totals[i] = (int) totalPoints;
                }
                previousBytes = integers.decompress(previousBytes, 1);
                long bytes = Integer.toUnsignedLong(previousBytes);
                if (bytes == 0 || bytes > table - chunk_starts[i - 1]) {
                    throw new IllegalArgumentException("invalid LAZ chunk length");
                }
                chunk_starts[i] = chunk_starts[i - 1] + bytes;
            }
            dec.done();
            if (adaptive && totalPoints != count) {
                throw new IllegalArgumentException("LAZ chunk point totals do not match the header");
            }
        }
        if (instream.tell() > tableEnd || chunk_starts[number_chunks] != table) {
            throw new IllegalArgumentException("invalid LAZ chunk table end");
        }
        tabled_chunks = number_chunks + 1;
        instream.seek(first);
    }

    int search_chunk_table(int index, int lower, int upper)
    {
        if (lower + 1 == upper) return lower;
        int mid = (lower+upper)/2;
        if (Integer.compareUnsigned(index, chunk_totals[mid]) >= 0)
            return search_chunk_table(index, mid, upper);
        else
            return search_chunk_table(index, lower, mid);
    }

    public String error() { return last_error; };
    public String warning() { return last_warning; };
}
