/*
 * Adapted from Mapbox Delaunator v5.0.1.
 *
 * ISC License
 *
 * Copyright (c) 2021, Mapbox
 *
 * Permission to use, copy, modify, and/or distribute this software for any purpose
 * with or without fee is hereby granted, provided that the above copyright notice
 * and this permission notice appear in all copies.
 *
 * THE SOFTWARE IS PROVIDED "AS IS" AND THE AUTHOR DISCLAIMS ALL WARRANTIES WITH
 * REGARD TO THIS SOFTWARE INCLUDING ALL IMPLIED WARRANTIES OF MERCHANTABILITY AND
 * FITNESS. IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR ANY SPECIAL, DIRECT,
 * INDIRECT, OR CONSEQUENTIAL DAMAGES OR ANY DAMAGES WHATSOEVER RESULTING FROM LOSS
 * OF USE, DATA OR PROFITS, WHETHER IN AN ACTION OF CONTRACT, NEGLIGENCE OR OTHER
 * TORTIOUS ACTION, ARISING OUT OF OR IN CONNECTION WITH THE USE OR PERFORMANCE OF
 * THIS SOFTWARE.
 */
package pointcloud;

import java.io.IOException;
import java.util.Arrays;

import static pointcloud.GeoTiffOutput.checkInterrupted;

/** Experimental radial sweep over distinct LAS points on an equal-scale, bounded XY grid. */
final class RawDelaunay {
    static final int MAX_SPAN = 35_000;

    final int[] triangles;
    final int[] halfEdges;
    int edges;

    private final int[] x;
    private final int[] y;
    private final int[] hullPrevious;
    private final int[] hullNext;
    private final int[] hullEdge;
    private final int[] hullHash;
    private int[] edgeStack = new int[64];
    private int hullStart;
    private double centerX;
    private double centerY;

    RawDelaunay(int[] x, int[] y, int size) throws IOException {
        this(x, y, size, false);
    }

    RawDelaunay(int[] x, int[] y, int size, boolean allowDegenerate) throws IOException {
        checkInterrupted();
        this.x = x;
        this.y = y;
        int a = -1;
        int b = -1;
        int c = -1;
        if (size >= 3) {
            int minX = x[0], maxX = x[0];
            int minY = y[0], maxY = y[0];
            for (int i = 1; i < size; i++) {
                if ((i & 4095) == 0) {
                    checkInterrupted();
                }
                minX = Math.min(minX, x[i]);
                maxX = Math.max(maxX, x[i]);
                minY = Math.min(minY, y[i]);
                maxY = Math.max(maxY, y[i]);
            }
            if (!supports(1, 1, (long) maxX - minX, (long) maxY - minY)) {
                throw new IllegalArgumentException("Raw Delaunator coordinate span exceeds " + MAX_SPAN);
            }
            a = nearest(size, (minX + (double) maxX) * .5, (minY + (double) maxY) * .5, -1);
            b = nearest(size, x[a], y[a], a);
            c = thirdSeed(size, a, b);
        }
        if (c == -1 && (!allowDegenerate || size == 0)) {
            throw new IOException("DTM requires at least three non-collinear support points");
        }
        int hullCapacity = c == -1 ? 0 : size;
        triangles = new int[c == -1 ? 0 : (2 * size - 5) * 3];
        halfEdges = new int[triangles.length];
        hullPrevious = new int[hullCapacity];
        hullNext = new int[hullCapacity];
        hullEdge = new int[hullCapacity];
        hullHash = new int[(int) Math.ceil(Math.sqrt(hullCapacity))];
        if (c != -1) {
            build(size, a, b, c);
        }
    }

    static boolean supports(double xScale, double yScale, long xSpan, long ySpan) {
        return xScale == yScale && xScale > 0 && Double.isFinite(xScale)
                && xSpan <= MAX_SPAN && ySpan <= MAX_SPAN;
    }

    private void build(int size, int a, int b, int c) throws IOException {
        // The advancing hull and inscribed-circle test use clockwise faces.
        if (orientation(a, b, c) > 0) {
            int swap = b;
            b = c;
            c = swap;
        }

        long bx = (long) x[b] - x[a], by = (long) y[b] - y[a];
        long cx = (long) x[c] - x[a], cy = (long) y[c] - y[a];
        long b2 = bx * bx + by * by, c2 = cx * cx + cy * cy;
        long denominator = 2 * (by * cx - bx * cy);
        long numeratorX = by * c2 - cy * b2, numeratorY = cx * b2 - bx * c2;
        centerX = x[a] + numeratorX / (double) denominator;
        centerY = y[a] + numeratorY / (double) denominator;

        int[] order = radialOrder(size, a, denominator, numeratorX, numeratorY);
        hullStart = a;
        hullNext[a] = hullPrevious[c] = b;
        hullNext[b] = hullPrevious[a] = c;
        hullNext[c] = hullPrevious[b] = a;
        hullEdge[a] = 0;
        hullEdge[b] = 1;
        hullEdge[c] = 2;
        Arrays.fill(hullHash, -1);
        hullHash[bucket(a)] = a;
        hullHash[bucket(b)] = b;
        hullHash[bucket(c)] = c;
        addFace(a, b, c, -1, -1, -1);

        for (int j = 0; j < size; j++) {
            if ((j & 4095) == 0) {
                checkInterrupted();
            }
            int point = order[j];
            if (point != a && point != b && point != c) {
                insert(point);
            }
        }
    }

    private int nearest(int size, double centerX, double centerY, int exclude) throws IOException {
        int nearest = -1;
        double min = Double.POSITIVE_INFINITY;
        for (int i = 0; i < size; i++) {
            if ((i & 4095) == 0) {
                checkInterrupted();
            }
            if (i != exclude) {
                double dx = x[i] - centerX, dy = y[i] - centerY;
                double distance = dx * dx + dy * dy;
                if (distance < min) {
                    min = distance;
                    nearest = i;
                }
            }
        }
        return nearest;
    }

    private int thirdSeed(int size, int a, int b) throws IOException {
        int best = -1;
        long bestNumerator = 0, bestDenominator = 1;
        long bx = (long) x[b] - x[a], by = (long) y[b] - y[a];
        for (int i = 0; i < size; i++) {
            if ((i & 4095) == 0) {
                checkInterrupted();
            }
            long cx = (long) x[i] - x[a], cy = (long) y[i] - y[a];
            long denominator = Math.abs(bx * cy - by * cx);
            if (denominator != 0) {
                // The circle center moves along the perpendicular bisector of ab.
                // Its distance from that midpoint is proportional to this quotient.
                // b is nearest to a, so the numerator is nonnegative. Cross products
                // of the quotients fit in a long: (4 * span²) * span².
                long numerator = cx * cx + cy * cy - bx * cx - by * cy;
                if (best == -1 || numerator * bestDenominator < bestNumerator * denominator) {
                    best = i;
                    bestNumerator = numerator;
                    bestDenominator = denominator;
                }
            }
        }
        return best;
    }

    private int[] radialOrder(int size, int seed, long denominator, long numeratorX, long numeratorY)
            throws IOException {
        int[] order = new int[size];
        int[] high = new int[size];
        long[] low = new long[size];
        for (int i = 0; i < size; i++) {
            if ((i & 4095) == 0) {
                checkInterrupted();
            }
            order[i] = i;
            long dx = (long) x[i] - x[seed], dy = (long) y[i] - y[seed];
            // Compare D * |p - c|² after dropping the common D * |c - seed|² term.
            // Signed 128-bit sums preserve radial ordering even for thin seed triangles.
            long norm = denominator * (dx * dx + dy * dy); // <= 2.45e9²
            long mx = -2 * numeratorX, my = -2 * numeratorY;
            long termX = dx * mx, termY = dy * my;
            long upper = Math.multiplyHigh(dx, mx) + Math.multiplyHigh(dy, my);
            long sum = termX + termY;
            if (Long.compareUnsigned(sum, termX) < 0) {
                upper++;
            }
            long rank = sum + norm;
            if (Long.compareUnsigned(rank, sum) < 0) {
                upper++;
            }
            low[i] = rank;
            high[i] = (int) upper;
        }
        sort(order, high, low, 0, size - 1);
        return order;
    }

    private static void sort(int[] order, int[] high, long[] low, int first, int last) throws IOException {
        while (first < last) {
            checkInterrupted();
            int pivot = order[(first + last) >>> 1];
            int left = first;
            int right = last;
            while (left <= right) {
                while (compareRadial(order[left], pivot, high, low) < 0) {
                    left++;
                }
                while (compareRadial(order[right], pivot, high, low) > 0) {
                    right--;
                }
                if (left <= right) {
                    int swap = order[left];
                    order[left++] = order[right];
                    order[right--] = swap;
                }
            }
            // Recurse only into the smaller partition to bound the stack.
            if (right - first < last - left) {
                sort(order, high, low, first, right);
                first = left;
            } else {
                sort(order, high, low, left, last);
                last = right;
            }
        }
    }

    private static int compareRadial(int a, int b, int[] high, long[] low) {
        int upper = Integer.compare(high[a], high[b]);
        if (upper != 0) {
            return upper;
        }
        int lower = Long.compareUnsigned(low[a], low[b]);
        return lower != 0 ? lower : Integer.compare(a, b);
    }

    private int bucket(int point) {
        double dx = x[point] - centerX, dy = y[point] - centerY;
        double ratio = dx / (Math.abs(dx) + Math.abs(dy));
        double angle = (dy > 0 ? 3 - ratio : 1 + ratio) * .25;
        return (int) (angle * hullHash.length) % hullHash.length;
    }

    private void insert(int point) throws IOException {
        int start = hullStart;
        int key = bucket(point);
        for (int j = 0; j < hullHash.length; j++) {
            int hint = hullHash[(key + j) % hullHash.length];
            if (hint >= 0 && hint != hullNext[hint]) {
                start = hint;
                break;
            }
        }

        start = hullPrevious[start];
        int edge = start;
        while (orientation(edge, hullNext[edge], point) <= 0) {
            edge = hullNext[edge];
            if (edge == start) {
                // Input points were deduplicated, so skipping would silently lose a support point.
                throw new IOException("Delaunator found no visible hull edge for support point " + point);
            }
        }

        int face = addFace(edge, point, hullNext[edge], -1, -1, hullEdge[edge]);
        hullEdge[point] = legalize(face + 2);
        hullEdge[edge] = face;

        int next = hullNext[edge];
        int after;
        while (orientation(next, after = hullNext[next], point) > 0) {
            face = addFace(next, point, after, hullEdge[point], -1, hullEdge[next]);
            hullEdge[point] = legalize(face + 2);
            hullNext[next] = next;
            next = after;
        }
        if (edge == start) {
            int before;
            while (orientation(before = hullPrevious[edge], edge, point) > 0) {
                face = addFace(before, point, edge, -1, hullEdge[edge], hullEdge[before]);
                legalize(face + 2);
                hullEdge[before] = face;
                hullNext[edge] = edge;
                edge = before;
            }
        }

        hullStart = hullPrevious[point] = edge;
        hullNext[edge] = hullPrevious[next] = point;
        hullNext[point] = next;
        hullHash[bucket(point)] = point;
        hullHash[bucket(edge)] = edge;
    }

    private int legalize(int edge) throws IOException {
        int pending = 0;
        int opposite;
        int iterations = 0;
        while (true) {
            if ((++iterations & 4095) == 0) {
                checkInterrupted();
            }
            int other = halfEdges[edge];
            int face = edge - edge % 3;
            opposite = face + (edge + 2) % 3;
            if (other != -1) {
                int otherFace = other - other % 3;
                int next = face + (edge + 1) % 3;
                int otherOpposite = otherFace + (other + 2) % 3;
                int p0 = triangles[opposite];
                int p1 = triangles[otherOpposite];
                if (inCircle(p0, triangles[edge], triangles[next], p1) < 0) {
                    triangles[edge] = p1;
                    triangles[other] = p0;
                    int nextNeighbor = halfEdges[otherOpposite];
                    if (nextNeighbor == -1) {
                        int hull = hullStart;
                        do {
                            if (hullEdge[hull] == otherOpposite) {
                                hullEdge[hull] = edge;
                                break;
                            }
                            hull = hullPrevious[hull];
                        } while (hull != hullStart);
                    }
                    link(edge, nextNeighbor);
                    link(other, halfEdges[opposite]);
                    link(opposite, otherOpposite);
                    if (pending == edgeStack.length) {
                        edgeStack = Arrays.copyOf(edgeStack, pending * 2);
                    }
                    edgeStack[pending++] = otherFace + (other + 1) % 3;
                    continue;
                }
            }
            if (pending == 0) {
                return opposite;
            }
            edge = edgeStack[--pending];
        }
    }

    private int addFace(int a, int b, int c, int linkA, int linkB, int linkC) {
        int face = edges;
        triangles[face] = a;
        triangles[face + 1] = b;
        triangles[face + 2] = c;
        link(face, linkA);
        link(face + 1, linkB);
        link(face + 2, linkC);
        edges += 3;
        return face;
    }

    private void link(int a, int b) {
        halfEdges[a] = b;
        if (b != -1) {
            halfEdges[b] = a;
        }
    }

    long orientation(int a, int b, int c) {
        long bx = (long) x[b] - x[a], by = (long) y[b] - y[a];
        long cx = (long) x[c] - x[a], cy = (long) y[c] - y[a];
        return bx * cy - by * cx;
    }

    long inCircle(int a, int b, int c, int p) {
        long ax = (long) x[a] - x[p], ay = (long) y[a] - y[p];
        long bx = (long) x[b] - x[p], by = (long) y[b] - y[p];
        long cx = (long) x[c] - x[p], cy = (long) y[c] - y[p];
        // Each cross product is twice the area of a triangle in the bounding square,
        // at most span². The determinant is bounded in absolute value by 6 * span⁴.
        return (ax * ax + ay * ay) * (bx * cy - by * cx)
                + (bx * bx + by * by) * (cx * ay - cy * ax)
                + (cx * cx + cy * cy) * (ax * by - ay * bx);
    }
}
