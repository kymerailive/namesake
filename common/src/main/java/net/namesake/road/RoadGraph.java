package net.namesake.road;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.namesake.settlement.Settlement;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * <b>Which settlements are neighbours.</b> The Delaunay triangulation of their bells, pruned to the
 * relative-neighbourhood graph.
 *
 * <h2>Why this graph and not one of the two obvious ones</h2>
 *
 * <p>{@code WORKPLAN.md} puts it in one line and it is worth keeping: <i>a complete graph looks like
 * nothing, an MST looks like a tree, the RNG between them looks like roads someone chose.</i> A
 * complete graph joins every village to every other, which is not a road network but a scribble; a
 * minimum spanning tree has exactly one route between any two places, which reads as a diagram
 * rather than as a country. The relative-neighbourhood graph sits between them: it keeps the
 * spanning tree, and it keeps a second route wherever two villages are genuinely close to each other
 * rather than merely connected through a third.
 *
 * <p><b>The rule is one sentence and it is exact integer arithmetic.</b> Two settlements are
 * neighbours unless some third settlement is nearer to <i>both</i> of them than they are to each
 * other — the "lune" test. Block coordinates are ints and squared distances fit in a long, so the
 * only floating point anywhere in this package is {@link Delaunay}, and that is allowed to be wrong
 * in one direction only. See its note.
 *
 * <h2>Nothing here is persisted</h2>
 *
 * <p>The graph is a pure function of the settlement table, which has been on disk since schema 3. It
 * is rebuilt on load and after every registration, deterministically. Storing it would be a cache of
 * something already saved, which is what session 03 deleted {@code Settlement.culture} for, and it
 * would be the second thing in the world able to disagree with the settlement table.
 *
 * <p><b>Dimensions never join.</b> A bell in the Nether is not this village's neighbour, for the same
 * reason {@code Settlements.containing} checks it: the two coordinate spaces are eight times apart
 * and a road between them would be a line through the void.
 */
public record RoadGraph(List<RoadEdge> edges, Map<Integer, int[]> adjacency) {

    public static final RoadGraph EMPTY = new RoadGraph(List.of(), Map.of());

    public RoadGraph {
        edges = List.copyOf(edges);
        adjacency = Map.copyOf(adjacency);
    }

    /** Every settlement this one has a road to. Empty — never null — for a place with no neighbours. */
    public int[] neighboursOf(int settlementId) {
        int[] found = adjacency.get(settlementId);
        return found == null ? EMPTY_ADJACENCY : found;
    }

    private static final int[] EMPTY_ADJACENCY = new int[0];

    public boolean joins(int one, int other) {
        for (int neighbour : neighboursOf(one)) {
            if (neighbour == other) {
                return true;
            }
        }
        return false;
    }

    public int size() {
        return edges.size();
    }

    // --- construction ------------------------------------------------------------------------------

    /** One point in the graph: a settlement id and where its bell is. */
    private record Site(int id, int x, int z) {
    }

    /**
     * Builds the graph for every dimension that has settlements in it.
     *
     * <p>Deterministic in the settlement table alone: same settlements, same graph, on any machine
     * and in any order. That matters more than it looks — the headless simulation and a running game
     * both drive {@code Gossip} through this, and a report that cannot be reproduced is not evidence.
     */
    public static RoadGraph of(Collection<Settlement> settlements) {
        Map<ResourceLocation, List<Site>> byDimension = new LinkedHashMap<>();
        for (Settlement settlement : settlements) {
            BlockPos centre = settlement.centre();
            byDimension.computeIfAbsent(settlement.dimension(), key -> new ArrayList<>())
                    .add(new Site(settlement.id(), centre.getX(), centre.getZ()));
        }

        Set<RoadEdge> kept = new LinkedHashSet<>();
        for (List<Site> sites : byDimension.values()) {
            prune(sites, candidates(sites), kept);
        }

        List<RoadEdge> edges = new ArrayList<>(kept);
        edges.sort((one, other) -> one.a() != other.a()
                ? Integer.compare(one.a(), other.a())
                : Integer.compare(one.b(), other.b()));

        Map<Integer, List<Integer>> building = new LinkedHashMap<>();
        for (RoadEdge edge : edges) {
            building.computeIfAbsent(edge.a(), key -> new ArrayList<>()).add(edge.b());
            building.computeIfAbsent(edge.b(), key -> new ArrayList<>()).add(edge.a());
        }
        Map<Integer, int[]> adjacency = new LinkedHashMap<>();
        building.forEach((id, list) -> {
            int[] packed = new int[list.size()];
            for (int i = 0; i < packed.length; i++) {
                packed[i] = list.get(i);
            }
            adjacency.put(id, packed);
        });

        return new RoadGraph(Collections.unmodifiableList(edges), adjacency);
    }

    /**
     * The pairs worth testing.
     *
     * <p>The triangulation when it produces one, and <b>every pair when it does not</b>. The fallback
     * is not defensive padding: three villages in a line have no triangles at all, and two have no
     * triangulation, and both are configurations a real world produces. The disconnection check
     * catches the third case — a triangulation that came apart on a rounding error — and turns a
     * silently missing road into a slower answer instead of a wrong one.
     */
    private static Set<RoadEdge> candidates(List<Site> sites) {
        if (sites.size() < 2) {
            return Set.of();
        }
        List<Delaunay.Site> points = new ArrayList<>(sites.size());
        for (Site site : sites) {
            points.add(new Delaunay.Site(site.id(), site.x(), site.z()));
        }
        Set<RoadEdge> triangulated = Delaunay.edges(points);
        if (connects(sites, triangulated)) {
            return triangulated;
        }
        Set<RoadEdge> all = new LinkedHashSet<>();
        for (int i = 0; i < sites.size(); i++) {
            for (int j = i + 1; j < sites.size(); j++) {
                if (sites.get(i).id() != sites.get(j).id()) {
                    all.add(new RoadEdge(sites.get(i).id(), sites.get(j).id()));
                }
            }
        }
        return all;
    }

    /** Whether these edges reach every site from any one of them. A correct triangulation does. */
    private static boolean connects(List<Site> sites, Set<RoadEdge> edges) {
        if (sites.size() < 2 || edges.isEmpty()) {
            return false;
        }
        Map<Integer, List<Integer>> adjacency = new LinkedHashMap<>();
        for (RoadEdge edge : edges) {
            adjacency.computeIfAbsent(edge.a(), key -> new ArrayList<>()).add(edge.b());
            adjacency.computeIfAbsent(edge.b(), key -> new ArrayList<>()).add(edge.a());
        }
        Set<Integer> seen = new LinkedHashSet<>();
        List<Integer> frontier = new ArrayList<>();
        frontier.add(sites.get(0).id());
        seen.add(sites.get(0).id());
        while (!frontier.isEmpty()) {
            int current = frontier.remove(frontier.size() - 1);
            for (int next : adjacency.getOrDefault(current, List.of())) {
                if (seen.add(next)) {
                    frontier.add(next);
                }
            }
        }
        for (Site site : sites) {
            if (!seen.contains(site.id())) {
                return false;
            }
        }
        return true;
    }

    /**
     * The lune test, in exact integer arithmetic.
     *
     * <p>{@code p} and {@code q} are neighbours unless some {@code r} is strictly nearer to both of
     * them than they are to each other. Squared distances are compared directly, which is the same
     * comparison because both sides are non-negative — and it keeps the whole decision in longs, so
     * two settlements never become neighbours or stop being neighbours because of a rounding error
     * in the last bit of a double.
     */
    private static void prune(List<Site> sites, Set<RoadEdge> candidates, Set<RoadEdge> kept) {
        Map<Integer, Site> byId = new LinkedHashMap<>();
        for (Site site : sites) {
            byId.putIfAbsent(site.id(), site);
        }
        for (RoadEdge candidate : candidates) {
            Site p = byId.get(candidate.a());
            Site q = byId.get(candidate.b());
            if (p == null || q == null) {
                continue;
            }
            long span = distanceSquared(p, q);
            if (span == 0) {
                // Two bells in the same column. Not possible past Settlements.MEMBERSHIP_RADIUS, and
                // an edge of length zero would survive every lune test and pair with everything.
                continue;
            }
            boolean blocked = false;
            for (Site r : sites) {
                if (r.id() == p.id() || r.id() == q.id()) {
                    continue;
                }
                if (Math.max(distanceSquared(p, r), distanceSquared(q, r)) < span) {
                    blocked = true;
                    break;
                }
            }
            if (!blocked) {
                kept.add(candidate);
            }
        }
    }

    private static long distanceSquared(Site one, Site other) {
        long dx = (long) one.x() - other.x();
        long dz = (long) one.z() - other.z();
        return dx * dx + dz * dz;
    }
}
