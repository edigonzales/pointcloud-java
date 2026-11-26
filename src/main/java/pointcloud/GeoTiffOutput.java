package pointcloud;

import it.geosolutions.imageio.plugins.tiff.BaselineTIFFTagSet;
import it.geosolutions.imageio.plugins.tiff.TIFFDirectory;
import it.geosolutions.imageio.plugins.tiff.TIFFField;
import it.geosolutions.imageio.plugins.tiff.TIFFTag;
import it.geosolutions.imageioimpl.plugins.tiff.TIFFImageWriterSpi;
import org.eclipse.imagen.NotAColorSpace;
import org.geotools.api.referencing.FactoryException;
import org.geotools.api.referencing.crs.CoordinateReferenceSystem;
import org.geotools.api.referencing.crs.ProjectedCRS;
import org.geotools.coverage.grid.io.imageio.geotiff.CRS2GeoTiffMetadataAdapter;
import org.geotools.coverage.grid.io.imageio.geotiff.GeoTiffConstants;
import org.geotools.coverage.grid.io.imageio.geotiff.GeoTiffIIOMetadataEncoder;
import org.geotools.gce.geotiff.GeoTiffWriter;
import org.geotools.referencing.CRS;

import javax.imageio.IIOImage;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriteParam;
import javax.imageio.stream.FileImageOutputStream;
import java.awt.Transparency;
import java.awt.image.BandedSampleModel;
import java.awt.image.BufferedImage;
import java.awt.image.ComponentColorModel;
import java.awt.image.DataBuffer;
import java.awt.image.DataBufferDouble;
import java.awt.image.Raster;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

final class GeoTiffOutput implements AutoCloseable {
    static final double NODATA = -9999;
    static final int MAX_CELLS = 1_000_000;
    private static final int COORDINATE_SYSTEM_WKT = 2112;
    private static final int GEO_KEY_DIRECTORY = 34735;
    private static final int GEO_DOUBLE_PARAMS = 34736;
    private static final int GEO_ASCII_PARAMS = 34737;
    private static final int PROJECTED_TYPE_KEY = 3072;
    private static final int LV95_EPSG = 2056;
    private static final int MAX_PROJECTION_BYTES = 65_535;
    private static final int PENDING_EXTRA_SAMPLES = BaselineTIFFTagSet.TAG_EXTRA_SAMPLES - 1;

    private final Path input;
    private final Path target;
    private final boolean overwrite;
    // The validated direct LV95 path does not need a GeoTools CRS object.
    private final CoordinateReferenceSystem crs;
    private Path temporary;

    GeoTiffOutput(Path input, Path target, boolean overwrite, LasHeader header, FileChannel source)
            throws IOException {
        this.input = input;
        this.target = target.toAbsolutePath();
        this.overwrite = overwrite;
        checkTarget();
        crs = resolveCrs(header, source);
    }

    boolean isProjected() {
        return crs == null || crs instanceof ProjectedCRS;
    }

    static void validateGrid(double originX, double originY, double resolution, int width, int height) {
        if (resolution <= 0 || width <= 0 || height <= 0 || (long) width * height > MAX_CELLS) {
            throw new IllegalArgumentException("grid requires positive resolution and dimensions, with at most "
                    + MAX_CELLS + " cells");
        }
        if (!Double.isFinite(originX + width * resolution) || !Double.isFinite(originY + height * resolution)
                || originX + resolution <= originX || originY + resolution <= originY) {
            throw new IllegalArgumentException("grid extent and cell size must be representable");
        }
    }

    void prepare(double originX, double originY, double resolution, int width, int height,
                 double[][] bands, List<String> names, double nodata, boolean deflate) throws IOException {
        checkInterrupted();
        Files.createDirectories(target.getParent());
        temporary = Files.createTempFile(target.getParent(), ".pointcloud-", ".tif.part");
        write(temporary, crs, originX, originY, resolution, width, height, bands, names, nodata, deflate);
        checkInterrupted();
    }

    void finish() throws IOException {
        checkInterrupted();
        checkTarget();
        if (overwrite) {
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } else {
            Files.createLink(target, temporary);
            Files.delete(temporary);
        }
        temporary = null;
    }

    private void checkTarget() throws IOException {
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            if (Files.exists(target) && Files.isSameFile(input, target)) {
                throw new IOException("Point input and GeoTIFF output refer to the same file");
            }
            if (!overwrite) {
                throw new FileAlreadyExistsException(target.toString());
            }
        }
    }

    static void checkInterrupted() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException("GeoTIFF output cancelled");
        }
    }

    @Override
    public void close() throws IOException {
        if (temporary != null) {
            Files.deleteIfExists(temporary);
        }
    }

    private static CoordinateReferenceSystem resolveCrs(LasHeader header, FileChannel source) throws IOException {
        var records = new LasHeader.VariableRecord[2];
        long[] auxiliaryLengths = {-1, -1};
        LasHeader.RecordVisitor findProjection = record -> {
            if (!record.userId().equals("LASF_Projection")) {
                return;
            }
            if (record.recordId() == GEO_DOUBLE_PARAMS || record.recordId() == GEO_ASCII_PARAMS) {
                int index = record.recordId() == GEO_DOUBLE_PARAMS ? 0 : 1;
                // Missing and ambiguous references both disqualify the direct path.
                auxiliaryLengths[index] = auxiliaryLengths[index] == -1 ? record.length() : -2;
                return;
            }
            int slot = switch (record.recordId()) {
                case COORDINATE_SYSTEM_WKT -> 0;
                case GEO_KEY_DIRECTORY -> 1;
                default -> -1;
            };
            if (slot >= 0) {
                if (records[slot] != null) {
                    throw new IOException("Duplicate LAS projection record " + record.recordId());
                }
                records[slot] = record;
            }
        };
        header.visitMetadata(source, false, findProjection);
        header.visitMetadata(source, true, findProjection);

        try {
            CoordinateReferenceSystem crs;
            if (records[0] != null) {
                byte[] bytes = LasHeader.read(source, records[0].payload(), projectionSize(records[0])).array();
                int end = 0;
                while (end < bytes.length && bytes[end] != 0) {
                    end++;
                }
                crs = CRS.parseWKT(new String(bytes, 0, end, StandardCharsets.UTF_8));
            } else if (records[1] != null) {
                var directory = geoKeyDirectory(source, records[1]);
                if (isDirectLv95(directory, auxiliaryLengths[0], auxiliaryLengths[1])) {
                    return null;
                }
                crs = CRS.decode("EPSG:" + epsgCode(directory), true);
            } else {
                throw new IOException("Missing LAS CRS for GeoTIFF output");
            }
            var horizontal = CRS.getHorizontalCRS(crs);
            if (horizontal == null) {
                throw new IOException("LAS CRS has no horizontal component for GeoTIFF output");
            }
            return horizontal;
        } catch (FactoryException e) {
            throw new IOException("Cannot resolve LAS CRS for GeoTIFF output: " + e.getMessage(), e);
        }
    }

    private static int projectionSize(LasHeader.VariableRecord record) throws IOException {
        if (record.length() == 0 || record.length() > MAX_PROJECTION_BYTES) {
            throw new IOException("Invalid size of LAS projection record " + record.recordId());
        }
        return (int) record.length();
    }

    private static ByteBuffer geoKeyDirectory(FileChannel source, LasHeader.VariableRecord record) throws IOException {
        int size = projectionSize(record);
        var directory = LasHeader.read(source, record.payload(), size);
        if (size < 8 || directory.getChar(0) != 1 || directory.getChar(6) > (size - 8) / 8) {
            throw new IOException("Invalid LAS GeoKey directory");
        }
        return directory;
    }

    private static boolean isDirectLv95(ByteBuffer directory, long doubleBytes, long asciiBytes) {
        int count = directory.getChar(6);
        if (directory.getChar(2) != 1 || directory.getChar(4) != 0 || directory.capacity() != 8 + count * 8) {
            return false;
        }
        boolean model = false;
        boolean projection = false;
        int previous = 0;
        for (int i = 0; i < count; i++) {
            int offset = 8 + i * 8;
            int key = directory.getChar(offset);
            int location = directory.getChar(offset + 2);
            int length = directory.getChar(offset + 4);
            int value = directory.getChar(offset + 6);
            if (key <= previous) {
                return false;
            }
            previous = key;
            boolean direct = location == 0 && length == 1;
            switch (key) {
                case GeoTiffConstants.GTModelTypeGeoKey -> model = direct && value == 1;
                case PROJECTED_TYPE_KEY -> projection = direct && value == LV95_EPSG;
                default -> {
                    // The cantonal headers also carry citation, angular unit and datum shift keys.
                    boolean supported = switch (key) {
                        case 1025 -> direct && value == GeoTiffConstants.RasterPixelIsArea;
                        case 1026, 2049 -> location == GEO_ASCII_PARAMS && length > 0
                                && value + length <= asciiBytes;
                        case 2054 -> direct && value == 9102;
                        case 2062 -> location == GEO_DOUBLE_PARAMS && length == 3
                                && doubleBytes % Double.BYTES == 0 && value + length <= doubleBytes / Double.BYTES;
                        case 3076 -> direct && value == 9001;
                        default -> false;
                    };
                    if (!supported) {
                        return false;
                    }
                }
            }
        }
        return model && projection;
    }

    private static int epsgCode(ByteBuffer directory) throws IOException {
        int geodetic = 0;
        int projected = 0;
        for (int i = 0; i < directory.getChar(6); i++) {
            int offset = 8 + 8 * i;
            int key = directory.getChar(offset);
            if (key == 2048 || key == PROJECTED_TYPE_KEY) {
                int value = directory.getChar(offset + 2) == 0 && directory.getChar(offset + 4) == 1
                        ? directory.getChar(offset + 6) : -1;
                if (key == PROJECTED_TYPE_KEY) {
                    projected = value;
                } else {
                    geodetic = value;
                }
            }
        }
        int code = projected == 0 ? geodetic : projected;
        if (code < 1024 || code >= 32767) {
            throw new IOException("LAS GeoKey directory has no supported horizontal EPSG code");
        }
        return code;
    }

    /** Encode a north-up raster without copying the sample arrays. */
    private static void write(Path target, CoordinateReferenceSystem crs, double originX, double originY,
                              double resolution, int width, int height, double[][] bands,
                              List<String> bandNames, double nodata, boolean deflate) throws IOException {
        var sampleModel = new BandedSampleModel(DataBuffer.TYPE_DOUBLE, width, height, bands.length);
        var data = new DataBufferDouble(bands, width * height);
        var raster = Raster.createWritableRaster(sampleModel, data, null);
        var colors = new ComponentColorModel(new NotAColorSpace(bands.length), false, false,
                Transparency.OPAQUE, DataBuffer.TYPE_DOUBLE);
        var image = new BufferedImage(colors, raster, false, null);

        GeoTiffIIOMetadataEncoder encoder;
        if (crs == null) {
            encoder = new GeoTiffIIOMetadataEncoder();
            encoder.addGeoShortParam(GeoTiffConstants.GTModelTypeGeoKey, 1);
            encoder.addGeoShortParam(PROJECTED_TYPE_KEY, LV95_EPSG);
        } else {
            encoder = new CRS2GeoTiffMetadataAdapter(crs).parseCoordinateReferenceSystem();
        }
        encoder.addGeoShortParam(GeoTiffConstants.GTRasterTypeGeoKey, GeoTiffConstants.RasterPixelIsArea);
        encoder.setModelPixelScale(resolution, resolution);
        encoder.setModelTiePoint(0, 0, originX, originY + height * resolution);
        encoder.setNoData(nodata);

        // The GeoTools metadata plus explicit numeric extra-sample tags follow Hop's GeoTiffOutput.
        var writer = new TIFFImageWriterSpi().createWriterInstance();
        try (var stream = new FileImageOutputStream(target.toFile())) {
            var params = writer.getDefaultWriteParam();
            if (deflate) {
                params.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                params.setCompressionType("Deflate");
            }
            var metadata = GeoTiffWriter.createGeoTiffIIOMetadata(
                    writer, ImageTypeSpecifier.createFromRenderedImage(image), encoder, params);
            var directory = TIFFDirectory.createFromMetadata(metadata);
            var tags = BaselineTIFFTagSet.getInstance();
            // The first band is grayscale and the rest are independent numeric samples.
            directory.addTIFFField(new TIFFField(tags.getTag(BaselineTIFFTagSet.TAG_PHOTOMETRIC_INTERPRETATION),
                    BaselineTIFFTagSet.PHOTOMETRIC_INTERPRETATION_BLACK_IS_ZERO));
            // The TIFF writer drops ExtraSamples for opaque images. Reserve the adjacent tag
            // while writing so declaring the numeric bands preserves IFD order.
            if (bands.length > 1) {
                var pending = new TIFFTag("PendingExtraSamples", PENDING_EXTRA_SAMPLES, 1 << TIFFTag.TIFF_SHORT);
                directory.addTIFFField(new TIFFField(pending, TIFFTag.TIFF_SHORT, bands.length - 1,
                        new char[bands.length - 1]));
            }
            directory.addTIFFField(new TIFFField(new TIFFTag("GDAL_METADATA", 42112, 1 << TIFFTag.TIFF_ASCII),
                    TIFFTag.TIFF_ASCII, 1, new String[]{bandDescriptions(bandNames)}));
            writer.setOutput(stream);
            writer.write(null, new IIOImage(image, null, directory.getAsMetadata()), params);
        } finally {
            writer.dispose();
        }
        if (bands.length > 1) {
            declareExtraSamples(target, bands.length - 1);
        }
    }

    private static String bandDescriptions(List<String> names) {
        var xml = new StringBuilder("<GDALMetadata>\n");
        for (int i = 0; i < names.size(); i++) {
            xml.append("  <Item name=\"DESCRIPTION\" sample=\"").append(i).append("\" role=\"description\">")
                    .append(names.get(i)).append("</Item>\n");
        }
        return xml.append("</GDALMetadata>\n").toString();
    }

    private static void declareExtraSamples(Path target, int count) throws IOException {
        try (var stream = new FileImageOutputStream(target.toFile())) {
            int byteOrder = stream.readUnsignedShort();
            if (byteOrder != 0x4949 && byteOrder != 0x4d4d) {
                throw new IOException("Invalid GeoTIFF byte order");
            }
            stream.setByteOrder(byteOrder == 0x4949 ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN);
            if (stream.readUnsignedShort() != 42) {
                throw new IOException("Expected classic TIFF for the bounded raster");
            }
            long ifdOffset = stream.readUnsignedInt();
            stream.seek(ifdOffset);
            int entries = stream.readUnsignedShort();
            for (int i = 0; i < entries; i++) {
                long position = ifdOffset + 2 + i * 12L;
                stream.seek(position);
                if (stream.readUnsignedShort() == PENDING_EXTRA_SAMPLES) {
                    if (stream.readUnsignedShort() != TIFFTag.TIFF_SHORT || stream.readUnsignedInt() != count) {
                        throw new IOException("Unexpected GeoTIFF numeric sample tag");
                    }
                    stream.seek(position);
                    stream.writeShort(BaselineTIFFTagSet.TAG_EXTRA_SAMPLES);
                    return;
                }
            }
            throw new IOException("Missing GeoTIFF numeric sample tag");
        }
    }
}
