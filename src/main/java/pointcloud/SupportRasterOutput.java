package pointcloud;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Publishes a prepared DTM and support raster with elevation as the last commit step. */
final class SupportRasterOutput {
    private SupportRasterOutput() { }

    static void finish(GeoTiffOutput support, Pipeline.OutputFinish elevation) throws IOException {
        GeoTiffOutput.checkInterrupted();
        Path target = support.target();
        if (Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new FileAlreadyExistsException(target.toString());
        }
        Path previous = null;
        boolean published = false;
        try {
            if (support.overwrite() && Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                previous = savePrevious(target);
            }
            support.publishPrepared();
            published = true;
            elevation.finish();
        } catch (IOException | RuntimeException failure) {
            try {
                if (previous != null) {
                    Files.move(previous, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } else if (published && (support.overwrite() || Files.exists(target)
                        && Files.isSameFile(support.temporary(), target))) {
                    Files.deleteIfExists(target);
                }
            } catch (IOException | RuntimeException rollback) {
                failure.addSuppressed(rollback);
            }
            throw failure;
        }

        if (previous != null) {
            try {
                Files.deleteIfExists(previous);
            } catch (IOException cleanup) {
                // Both rasters have committed. A cleanup failure must not roll back the metadata alone.
                previous.toFile().deleteOnExit();
            }
        }
    }

    private static Path savePrevious(Path target) throws IOException {
        Path previous = Files.createTempFile(target.getParent(), ".pointcloud-previous-support-", ".part");
        try {
            return Files.move(target, previous, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | RuntimeException failure) {
            try {
                Files.deleteIfExists(previous);
            } catch (IOException | RuntimeException cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }
}
