package pointcloud;

import org.tinfour.common.GeometricOperations;
import org.tinfour.common.Thresholds;
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
            var raw = terrain.triangulateRaw();
            var tin = raw == null ? terrain.triangulate() : null;
            try {
                if (Boolean.getBoolean("pointcloud.tin.diagnostics")) {
                    System.err.println("DTM backend: " + (raw == null ? "tinfour" : "delaunator"));
                    if (tin != null) {
                        tin.printDiagnostics(System.err);
                    }
                }
                var navigator = tin == null ? null : tin.getNavigator();
                var geometry = raw == null ? null : new GeometricOperations(new Thresholds(terrain.tinSpacing()));
                double west = terrain.localX(originX);
                double south = terrain.localY(originY);
                for (int index = 6; index < args.length; index++) {
                    int cell = Integer.parseInt(args[index]);
                    double x = Math.fma(cell % width, resolution, west + resolution / 2);
                    double y = Math.fma(height - 1 - cell / width, resolution, south + resolution / 2);
                    Vertex[] vertices;
                    if (raw != null) {
                        vertices = locateRawFace(terrain, raw, geometry, x, y);
                    } else {
                        var face = navigator.getContainingTriangle(x, y);
                        vertices = face == null || face.isGhost() ? null
                                : new Vertex[]{face.getVertexA(), face.getVertexB(), face.getVertexC()};
                    }
                    System.out.print(cell);
                    if (vertices != null) {
                        for (Vertex vertex : vertices) {
                            System.out.print("," + (originX + (vertex.x - west)) + "," +
                                    (originY + (vertex.y - south)) + "," + terrain.height(vertex));
                        }
                    }
                    System.out.println();
                }
            } finally {
                if (tin != null) {
                    tin.dispose();
                }
            }
        }
    }

    private static Vertex[] locateRawFace(TerrainWriter terrain, RawDelaunay raw, GeometricOperations geometry,
                                          double x, double y) {
        for (int edge = 0; edge < raw.edges; edge += 3) {
            Vertex a = terrain.vertex(raw.triangles[edge]);
            Vertex b = terrain.vertex(raw.triangles[edge + 1]);
            Vertex c = terrain.vertex(raw.triangles[edge + 2]);
            if (x < Math.min(a.x, Math.min(b.x, c.x)) || x > Math.max(a.x, Math.max(b.x, c.x))
                    || y < Math.min(a.y, Math.min(b.y, c.y)) || y > Math.max(a.y, Math.max(b.y, c.y))) {
                continue;
            }
            double area = geometry.orientation(a.x, a.y, b.x, b.y, c.x, c.y);
            if (area != 0
                    && geometry.orientation(b.x, b.y, c.x, c.y, x, y) / area >= 0
                    && geometry.orientation(c.x, c.y, a.x, a.y, x, y) / area >= 0
                    && geometry.orientation(a.x, a.y, b.x, b.y, x, y) / area >= 0) {
                return new Vertex[]{a, b, c};
            }
        }
        return null;
    }
}
