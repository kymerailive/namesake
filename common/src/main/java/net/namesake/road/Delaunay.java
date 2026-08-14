package net.namesake.road;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The Delaunay triangulation of a handful of points, by Bowyer–Watson.
 *
 * <p><b>It exists to produce candidates, not answers.</b> {@link RoadGraph} keeps an edge only if it
 * survives the relative-neighbourhood test, and that test is exact integer arithmetic over block
 * coordinates. This is the cheap way to avoid asking it about every pair: the relative-neighbourhood
 * graph is a subgraph of the Gabriel graph, which is a subgraph of the Delaunay triangulation, so
 * every edge that could survive is in here and the prune runs over O(n) candidates instead of O(n²).
 *
 * <p><b>So a numerical mistake here can only lose an edge, never invent one</b> — and losing one is
 * caught two ways. {@link RoadGraph} falls back to every pair when the triangulation comes back
 * degenerate or disconnected, and {@code RoadGraphTest} asserts that the pruned Delaunay and the
 * pruned all-pairs graph are the <i>same graph</i> over hundreds of random and adversarial
 * configurations. That is why this file is allowed to be doubles.
 *
 * <p>Package-private: nothing outside this package should have an opinion about triangles.
 */
final class Delaunay {

    private Delaunay() {
    }

    /** One point to triangulate: which settlement it is, and where. */
    record Site(int settlementId, double x, double z) {
    }

    /**
     * Every edge of the triangulation, as settlement-id pairs.
     *
     * <p>Empty for fewer than three sites, for sites that are all in one place, and for sites that
     * are all on one line — in each case there are no triangles to find, and the caller falls back.
     */
    static Set<RoadEdge> edges(List<Site> sites) {
        Set<RoadEdge> edges = new LinkedHashSet<>();
        int n = sites.size();
        if (n < 3) {
            return edges;
        }

        double minX = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE;
        double minZ = Double.MAX_VALUE;
        double maxZ = -Double.MAX_VALUE;
        for (Site site : sites) {
            minX = Math.min(minX, site.x());
            maxX = Math.max(maxX, site.x());
            minZ = Math.min(minZ, site.z());
            maxZ = Math.max(maxZ, site.z());
        }
        double span = Math.max(maxX - minX, maxZ - minZ);
        if (span <= 0) {
            // Every settlement in one place. Settlements.MEMBERSHIP_RADIUS makes this impossible in
            // a real world, which is exactly why it is worth returning cleanly rather than dividing.
            return edges;
        }

        double midX = (minX + maxX) / 2;
        double midZ = (minZ + maxZ) / 2;
        // A triangle that comfortably contains the bounding box. Twenty spans rather than three, so
        // the super vertices are far enough away that no real circumcircle reaches them by rounding.
        double reach = 20 * span;

        double[] px = new double[n + 3];
        double[] pz = new double[n + 3];
        for (int i = 0; i < n; i++) {
            px[i] = sites.get(i).x();
            pz[i] = sites.get(i).z();
        }
        px[n] = midX - reach;
        pz[n] = midZ - span;
        px[n + 1] = midX;
        pz[n + 1] = midZ + reach;
        px[n + 2] = midX + reach;
        pz[n + 2] = midZ - span;

        List<int[]> triangles = new ArrayList<>();
        triangles.add(new int[]{n, n + 1, n + 2});

        List<int[]> cavity = new ArrayList<>();
        for (int point = 0; point < n; point++) {
            cavity.clear();
            for (int i = triangles.size() - 1; i >= 0; i--) {
                int[] triangle = triangles.get(i);
                if (!inCircumcircle(px, pz, triangle, px[point], pz[point])) {
                    continue;
                }
                cavity.add(new int[]{triangle[0], triangle[1]});
                cavity.add(new int[]{triangle[1], triangle[2]});
                cavity.add(new int[]{triangle[2], triangle[0]});
                triangles.remove(i);
            }
            // The cavity's boundary is every edge that belonged to exactly one of the removed
            // triangles. The shared ones are interior and disappear with them.
            for (int i = 0; i < cavity.size(); i++) {
                int[] edge = cavity.get(i);
                if (edge == null) {
                    continue;
                }
                boolean shared = false;
                for (int j = i + 1; j < cavity.size(); j++) {
                    int[] other = cavity.get(j);
                    if (other != null && sameEdge(edge, other)) {
                        cavity.set(j, null);
                        shared = true;
                    }
                }
                if (!shared) {
                    triangles.add(new int[]{edge[0], edge[1], point});
                }
            }
        }

        for (int[] triangle : triangles) {
            if (triangle[0] >= n || triangle[1] >= n || triangle[2] >= n) {
                // Touches the scaffolding, so it is not part of the answer.
                continue;
            }
            add(edges, sites, triangle[0], triangle[1]);
            add(edges, sites, triangle[1], triangle[2]);
            add(edges, sites, triangle[2], triangle[0]);
        }
        return edges;
    }

    private static void add(Set<RoadEdge> edges, List<Site> sites, int i, int j) {
        edges.add(new RoadEdge(sites.get(i).settlementId(), sites.get(j).settlementId()));
    }

    private static boolean sameEdge(int[] one, int[] other) {
        return (one[0] == other[0] && one[1] == other[1])
                || (one[0] == other[1] && one[1] == other[0]);
    }

    /**
     * Whether {@code (qx, qz)} lies inside this triangle's circumcircle.
     *
     * <p>The standard {@code incircle} determinant, with the orientation normalised first: the sign
     * of the result flips with the winding, so a triangle built clockwise would answer every
     * question backwards and the triangulation would come apart quietly rather than loudly.
     */
    private static boolean inCircumcircle(double[] px, double[] pz, int[] triangle,
                                          double qx, double qz) {
        int i = triangle[0];
        int j = triangle[1];
        int k = triangle[2];
        double area = (px[j] - px[i]) * (pz[k] - pz[i]) - (pz[j] - pz[i]) * (px[k] - px[i]);
        if (area == 0) {
            // Three points on a line have no circumcircle, so nothing is inside it.
            return false;
        }
        if (area < 0) {
            int swap = j;
            j = k;
            k = swap;
        }

        double ax = px[i] - qx;
        double az = pz[i] - qz;
        double bx = px[j] - qx;
        double bz = pz[j] - qz;
        double cx = px[k] - qx;
        double cz = pz[k] - qz;

        return (ax * ax + az * az) * (bx * cz - cx * bz)
                + (bx * bx + bz * bz) * (cx * az - ax * cz)
                + (cx * cx + cz * cz) * (ax * bz - bx * az) > 0;
    }
}
