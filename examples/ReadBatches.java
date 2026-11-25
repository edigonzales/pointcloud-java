import java.nio.file.Path;
import pointcloud.LasReader;

class ReadBatches {
    public static void main(String[] args) throws Exception {
        try (var reader = new LasReader(Path.of(args[0]), 2)) {
            long count = 0;
            for (var batch = reader.readBatch(); batch != null; batch = reader.readBatch()) {
                if (count == 0) {
                    System.out.printf("First raw X: %d, world X: %s%n", batch.rawX(0), batch.x(0));
                }
                count += batch.size();
            }
            System.out.printf("Read %d point records%n", count);
        }
    }
}
