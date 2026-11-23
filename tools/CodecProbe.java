package com.github.mreutegg.laszip4j.laslib;

import com.github.mreutegg.laszip4j.LASReader;
import com.github.mreutegg.laszip4j.laszip.LASzip;
import java.io.File;
import java.io.IOException;

/**
 * P1 baseline against the unmodified upstream checkout. The optional LAS
 * output contains the first 1,024 points, while the scan always goes to EOF.
 */
public class CodecProbe {
    public static void main(String[] args) throws IOException {
        File source = new File(args[1]);
        if (args[0].equals("public")) {
            var reader = new LASReader(source);
            var header = reader.getHeader();
            long extended = header.getNumberOfPointRecords();
            long expected = extended != 0 ? extended : Integer.toUnsignedLong(header.getLegacyNumberOfPointRecords());
            long count = 0, sumX = 0, sumY = 0, sumZ = 0;
            try (var points = reader.getCloseablePoints()) {
                for (var point : points) {
                    count++;
                    sumX += point.getX();
                    sumY += point.getY();
                    sumZ += point.getZ();
                }
            }
            System.out.printf("extended=%d expected=%d count=%d sumXYZ=%d,%d,%d%n",
                    extended, expected, count, sumX, sumY, sumZ);
            if (count != expected) {
                throw new IOException("Point stream ended before the header count");
            }
            return;
        }

        try (var reader = new LASreaderLAS()) {
            if (!reader.open(source.getPath(), 65536, false, LASzip.LASZIP_DECOMPRESS_SELECTIVE_ALL)) {
                throw new IOException("Cannot open " + source);
            }
            var h = reader.header;
            System.out.printf("LAS %d.%d format=%d length=%d offset=%d VLRs=%d%n",
                    h.version_major, h.version_minor, h.point_data_format, (int) h.point_data_record_length,
                    h.offset_to_point_data, h.number_of_variable_length_records);
            System.out.printf("scale=[%s,%s,%s] offset=[%s,%s,%s]%n", h.x_scale_factor,
                    h.y_scale_factor, h.z_scale_factor, h.x_offset, h.y_offset, h.z_offset);
            System.out.printf("headerBounds=[%s,%s,%s,%s,%s,%s]%n", h.min_x, h.max_x,
                    h.min_y, h.max_y, h.min_z, h.max_z);
            LASwriterLAS writer = args.length == 3 ? new LASwriterLAS() : null;
            long count = 0, sumX = 0, sumY = 0, sumZ = 0, bytes = 0;
            try {
                if (writer != null) {
                    if (!writer.open(args[2], h, LASzip.LASZIP_COMPRESSOR_NONE, 0, 0, 65536)) {
                        throw new IOException("Cannot open " + args[2]);
                    }
                    writer.inventory = new LASinventory();
                }
                while (reader.read_point()) {
                    var point = reader.point;
                    count++;
                    sumX += point.getX();
                    sumY += point.getY();
                    sumZ += point.getZ();
                    if (writer != null && count <= 1024) {
                        if (!writer.write_point(point)) {
                            throw new IOException("Cannot write point " + count);
                        }
                        writer.update_inventory(point);
                    }
                }
                System.out.printf("expected=%d count=%d endPosition=%d sumXYZ=%d,%d,%d%n",
                        reader.npoints, count, reader.p_count, sumX, sumY, sumZ);
                if (count != reader.npoints || reader.p_count != count) {
                    throw new IOException("Invalid point stream termination");
                }
                if (writer != null && !writer.update_header(h, true)) {
                    throw new IOException("Cannot close LAS header");
                }
            } finally {
                if (writer != null) {
                    bytes = writer.close();
                }
            }
            if (writer != null) {
                System.out.printf("written=%d bytes=%d%n", Math.min(1024, count), bytes);
            }
        }
    }
}
