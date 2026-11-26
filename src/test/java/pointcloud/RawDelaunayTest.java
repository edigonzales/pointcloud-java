package pointcloud;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.math.BigInteger;
import java.util.BitSet;

import static org.junit.jupiter.api.Assertions.*;

class RawDelaunayTest {
    @Test
    void boundsExactArithmeticToTheApplicableScaleAndRawSpan() {
        assertTrue(RawDelaunay.supports(.01, .01, 35_000, 35_000));
        assertFalse(RawDelaunay.supports(.01, .01, 35_001, 35_000));
        assertFalse(RawDelaunay.supports(.01, .01, 35_000, 35_001));
        assertFalse(RawDelaunay.supports(.01, .02, 0, 0));
        assertFalse(RawDelaunay.supports(0, 0, 0, 0));
        assertFalse(RawDelaunay.supports(-.01, -.01, 0, 0));
        assertFalse(RawDelaunay.supports(Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, 0, 0));
        assertFalse(RawDelaunay.supports(.01, .01, (long) Integer.MAX_VALUE - Integer.MIN_VALUE, 8));
    }

    @Test
    void keepsEveryCocircularAndCollinearBoundaryPointOnAnIntegerGrid() throws Exception {
        int[] x = new int[81], y = new int[x.length];
        for (int i = 0; i < x.length; i++) {
            int position = (i * 17) % x.length;
            x[i] = Integer.MIN_VALUE + position % 9 * 1_000;
            y[i] = Integer.MAX_VALUE - position / 9 * 1_000;
        }
        var mesh = new RawDelaunay(x, y, x.length);
        assertEquals(3 * 2 * 8 * 8, mesh.edges);
        verifyMesh(mesh, x.length);
    }

    @Test
    void computesTheFullSpanInCircleDeterminantExactly() throws Exception {
        int e = Integer.MAX_VALUE, w = e - 35_000;
        int s = Integer.MIN_VALUE, n = s + 35_000;
        int[] x = {w, e, w, e, w + 1};
        int[] y = {s, s, n, n, s + 1};
        var mesh = new RawDelaunay(x, y, x.length);
        assertEquals(0, mesh.inCircle(0, 1, 2, 3));
        assertTrue(mesh.inCircle(0, 1, 2, 4) > 0);
        for (int p = 0; p < x.length; p++) {
            for (int a = 0; a < x.length; a++) {
                for (int b = 0; b < x.length; b++) {
                    for (int c = 0; c < x.length; c++) {
                        assertEquals(determinant(x, y, a, b, c, p), mesh.inCircle(a, b, c, p));
                    }
                }
            }
        }
        verifyMesh(mesh, x.length);
    }

    @Test
    void rejectsCollinearSupportAndChecksInterruption() {
        int[] x = {0, 1, 2};
        int[] y = {0, 1, 2};
        var failure = assertThrows(IOException.class, () -> new RawDelaunay(x, y, x.length));
        assertTrue(failure.getMessage().contains("non-collinear"));
        Thread.currentThread().interrupt();
        try {
            assertThrows(InterruptedIOException.class, () -> new RawDelaunay(x, y, x.length));
        } finally {
            Thread.interrupted();
        }
    }

    private static long determinant(int[] x, int[] y, int a, int b, int c, int p) {
        BigInteger ax = delta(x, a, p), ay = delta(y, a, p);
        BigInteger bx = delta(x, b, p), by = delta(y, b, p);
        BigInteger cx = delta(x, c, p), cy = delta(y, c, p);
        return ax.multiply(by.multiply(norm(cx, cy)).subtract(cy.multiply(norm(bx, by))))
                .subtract(ay.multiply(bx.multiply(norm(cx, cy)).subtract(cx.multiply(norm(bx, by)))))
                .add(norm(ax, ay).multiply(bx.multiply(cy).subtract(cx.multiply(by)))).longValueExact();
    }

    private static BigInteger delta(int[] axis, int a, int b) {
        return BigInteger.valueOf((long) axis[a] - axis[b]);
    }

    private static BigInteger norm(BigInteger x, BigInteger y) {
        return x.multiply(x).add(y.multiply(y));
    }

    private static void verifyMesh(RawDelaunay mesh, int points) {
        var present = new BitSet(points);
        for (int i = 0; i < mesh.edges; i++) {
            int next = i % 3 == 2 ? i - 2 : i + 1;
            int apex = i % 3 == 0 ? i + 2 : i - 1;
            assertTrue(mesh.orientation(mesh.triangles[i], mesh.triangles[next], mesh.triangles[apex]) < 0);
            present.set(mesh.triangles[i]);
            int opposite = mesh.halfEdges[i];
            if (opposite >= 0) {
                int oppositeNext = opposite % 3 == 2 ? opposite - 2 : opposite + 1;
                int oppositeApex = opposite % 3 == 0 ? opposite + 2 : opposite - 1;
                assertEquals(i, mesh.halfEdges[opposite]);
                assertEquals(mesh.triangles[i], mesh.triangles[oppositeNext]);
                assertEquals(mesh.triangles[next], mesh.triangles[opposite]);
                assertTrue(mesh.inCircle(mesh.triangles[i], mesh.triangles[next], mesh.triangles[apex],
                        mesh.triangles[oppositeApex]) >= 0);
            }
        }
        assertEquals(points, present.cardinality());
    }
}
