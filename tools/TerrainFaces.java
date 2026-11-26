package pointcloud;

import org.tinfour.common.Vertex;

import java.nio.file.Path;

/** Prints the Java support face of each requested pixel for the real-data comparison. */
final class TerrainFaces {
    public static void main(String[] args) throws Exception {
        Path input = Path.of(args[0]);
        double originX = Double.parseDouble(args[1]);
        double originY = Double.parseDouble(args[2]);
        double resolution = Double.parseDouble(args[3]);
        int width = Integer.parseInt(args[4]);
        int height = Integer.parseInt(args[5]);
        var grid = new TerrainWriter.Grid(originX, originY, resolution, width, height,
                Double.POSITIVE_INFINITY, GeoTiffOutput.NODATA, false);
        try (var source = Pipeline.openSequential(input, 65_536);
             var output = new GeoTiffOutput(input, input.resolveSibling("diagnostic.tif"), true,
                     source.header(), source.channel())) {
            var terrain = new TerrainWriter(output, grid, source.header());
            for (var batch = source.reader().readBatch(); batch != null; batch = source.reader().readBatch()) {
                for (int p = 0; p < batch.size(); p++) {
                    terrain.add(batch, p);
                }
            }
            var tin = terrain.triangulate();
            try {
                double west = terrain.localX(originX);
                double south = terrain.localY(originY);
                for (int index = 6; index < args.length; index++) {
                    int cell = Integer.parseInt(args[index]);
                    double x = Math.fma(cell % width, resolution, west + resolution / 2);
                    double y = Math.fma(height - 1 - cell / width, resolution, south + resolution / 2);
                    var face = tin.getNavigator().getContainingTriangle(x, y);
                    System.out.print(cell);
                    if (face != null && !face.isGhost()) {
                        for (Vertex vertex : new Vertex[]{face.getVertexA(), face.getVertexB(), face.getVertexC()}) {
                            System.out.print("," + (originX + (vertex.x - west)) + "," +
                                    (originY + (vertex.y - south)) + "," + terrain.height(vertex));
                        }
                    }
                    System.out.println();
                }
            } finally {
                tin.dispose();
            }
        }
    }
}
