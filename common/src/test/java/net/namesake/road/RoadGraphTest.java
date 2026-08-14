package net.namesake.road;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.namesake.settlement.Settlement;
import net.namesake.settlement.Specialty;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>The shape of the road network, held to the property that decides how it reads.</b>
 *
 * <p>{@code WORKPLAN.md} rules the graph in one sentence — <i>a complete graph looks like nothing, an
 * MST looks like a tree, the RNG between them looks like roads someone chose</i> — and every test
 * here is that sentence in a different clothes. The headline one is
 * {@link #theGraphIsTheRelativeNeighbourhoodGraph}: the pruned Delaunay is checked against a
 * <b>second, independent implementation written in this file</b> that asks about every pair. That is
 * what makes {@link Delaunay} allowed to be doubles — a triangulation that came apart on a rounding
 * error would show up here as a missing road, over hundreds of configurations.
 */
class RoadGraphTest {

    private static final ResourceLocation OVERWORLD =
            ResourceLocation.withDefaultNamespace("overworld");
    private static final ResourceLocation NETHER =
            ResourceLocation.withDefaultNamespace("the_nether");

    private static Settlement at(int id, int x, int z) {
        return at(id, x, z, OVERWORLD);
    }

    private static Settlement at(int id, int x, int z, ResourceLocation dimension) {
        return new Settlement(id, dimension, new BlockPos(x, 64, z),
                Specialty.FARMING.id(), (byte) 50, new byte[]{0, 0, 0, 0});
    }

    /** The definition, over every pair. Deliberately not the code under test. */
    private static Set<RoadEdge> byDefinition(List<Settlement> settlements) {
        Set<RoadEdge> edges = new LinkedHashSet<>();
        for (int i = 0; i < settlements.size(); i++) {
            for (int j = i + 1; j < settlements.size(); j++) {
                Settlement p = settlements.get(i);
                Settlement q = settlements.get(j);
                if (!p.dimension().equals(q.dimension())) {
                    continue;
                }
                long span = distance(p, q);
                if (span == 0) {
                    continue;
                }
                boolean blocked = false;
                for (Settlement r : settlements) {
                    if (r == p || r == q || !r.dimension().equals(p.dimension())) {
                        continue;
                    }
                    if (Math.max(distance(p, r), distance(q, r)) < span) {
                        blocked = true;
                        break;
                    }
                }
                if (!blocked) {
                    edges.add(new RoadEdge(p.id(), q.id()));
                }
            }
        }
        return edges;
    }

    private static long distance(Settlement one, Settlement other) {
        long dx = (long) one.centre().getX() - other.centre().getX();
        long dz = (long) one.centre().getZ() - other.centre().getZ();
        return dx * dx + dz * dz;
    }

    /**
     * <b>The one that makes the triangulation safe to be doubles.</b>
     *
     * <p>Two hundred configurations, three to twenty villages, over a spread wide enough that
     * degeneracies are rare and narrow enough that they happen. If {@link Delaunay} ever loses a
     * candidate that the definition would have kept, this says which world it happened in.
     */
    @Test
    @DisplayName("the pruned Delaunay is the relative-neighbourhood graph, over 200 random worlds")
    void theGraphIsTheRelativeNeighbourhoodGraph() {
        Random random = new Random(20261010L);
        for (int world = 0; world < 200; world++) {
            int count = 3 + random.nextInt(18);
            List<Settlement> settlements = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                settlements.add(at(i, random.nextInt(8000) - 4000, random.nextInt(8000) - 4000));
            }
            Set<RoadEdge> expected = byDefinition(settlements);
            Set<RoadEdge> actual = new LinkedHashSet<>(RoadGraph.of(settlements).edges());
            int number = world;
            assertEquals(expected, actual,
                    () -> "world " + number + " of " + count + " villages disagreed. The Delaunay is "
                            + "allowed to be doubles only because this test asks every pair.");
        }
    }

    /**
     * The RNG's headline property, and the reason it reads as roads rather than as a scribble.
     *
     * <p>Three villages in a line: the far pair are <i>not</i> neighbours, because the one in the
     * middle is nearer to both of them than they are to each other. A complete graph would draw the
     * long road anyway, and a player would see a road that goes past a village without stopping.
     */
    @Test
    @DisplayName("a village in the middle breaks the long road past it")
    void aVillageInTheMiddleBreaksTheLongEdge() {
        List<Settlement> line = List.of(at(0, 0, 0), at(1, 500, 0), at(2, 1000, 0));
        RoadGraph graph = RoadGraph.of(line);

        assertTrue(graph.joins(0, 1), "neighbours");
        assertTrue(graph.joins(1, 2), "neighbours");
        assertFalse(graph.joins(0, 2),
                "the far pair must go through the middle, or the graph is complete and reads as one");
        assertEquals(2, graph.size());
    }

    /**
     * Collinear villages have no triangulation at all — every triangle is degenerate — so the
     * fallback in {@link RoadGraph} is not padding. Five in a row must still be a road.
     */
    @Test
    @DisplayName("five villages on one line still get four roads")
    void collinearVillagesStillGetRoads() {
        List<Settlement> line = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            line.add(at(i, i * 400, 0));
        }
        RoadGraph graph = RoadGraph.of(line);
        assertEquals(byDefinition(line), new LinkedHashSet<>(graph.edges()));
        assertEquals(4, graph.size(), "a chain, and nothing skipping a village");
    }

    @Test
    @DisplayName("two villages are always neighbours, and one has nobody to be a neighbour of")
    void theSmallCases() {
        assertEquals(1, RoadGraph.of(List.of(at(0, 0, 0), at(1, 900, 300))).size());
        assertEquals(0, RoadGraph.of(List.of(at(0, 0, 0))).size());
        assertEquals(0, RoadGraph.of(List.of()).size());
        assertEquals(0, RoadGraph.EMPTY.size());
        assertEquals(0, RoadGraph.EMPTY.neighboursOf(4).length, "and it answers rather than throwing");
    }

    /**
     * Between the two graphs the ledger rules out, measured rather than asserted by eye.
     *
     * <p>More edges than a spanning tree, so there are loops and a road network rather than a
     * diagram; far fewer than every pair, so it is not a scribble. Both numbers come out of the same
     * cloud of villages.
     */
    @Test
    @DisplayName("more than a tree, far less than a complete graph")
    void itSitsBetweenTheTwoGraphsTheLedgerRulesOut() {
        Random random = new Random(4242L);
        List<Settlement> cloud = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            cloud.add(at(i, random.nextInt(20000) - 10000, random.nextInt(20000) - 10000));
        }
        RoadGraph graph = RoadGraph.of(cloud);
        int tree = cloud.size() - 1;
        int complete = cloud.size() * (cloud.size() - 1) / 2;

        assertTrue(graph.size() >= tree,
                () -> "an RNG contains a spanning tree, so " + graph.size() + " < " + tree
                        + " means somewhere is cut off");
        assertTrue(graph.size() < complete / 4,
                () -> "at " + graph.size() + " of a possible " + complete + " this is a scribble");
        assertTrue(connected(graph, cloud), "every village has to be reachable from every other");
    }

    private static boolean connected(RoadGraph graph, List<Settlement> settlements) {
        Set<Integer> seen = new LinkedHashSet<>();
        List<Integer> frontier = new ArrayList<>();
        frontier.add(settlements.get(0).id());
        seen.add(settlements.get(0).id());
        while (!frontier.isEmpty()) {
            int current = frontier.remove(frontier.size() - 1);
            for (int next : graph.neighboursOf(current)) {
                if (seen.add(next)) {
                    frontier.add(next);
                }
            }
        }
        return seen.size() == settlements.size();
    }

    /**
     * {@code Settlements.containing} already refuses to put a villager in another dimension's
     * village; a road between them would be a line through the void, and eight blocks of the Nether
     * are one of the Overworld, so the coordinates are not even comparable.
     */
    @Test
    @DisplayName("a bell in the Nether is nobody's neighbour")
    void dimensionsNeverJoin() {
        List<Settlement> both = List.of(
                at(0, 0, 0), at(1, 600, 0),
                at(2, 10, 10, NETHER), at(3, 610, 10, NETHER));
        RoadGraph graph = RoadGraph.of(both);

        assertTrue(graph.joins(0, 1));
        assertTrue(graph.joins(2, 3));
        assertFalse(graph.joins(0, 2), "two coordinate spaces eight times apart");
        assertFalse(graph.joins(1, 3));
        assertEquals(2, graph.size());
    }

    /**
     * The whole road network is derived rather than stored, so the order settlements happen to be
     * registered in must not change it — otherwise a world would come back with different roads
     * after a reload, and nothing would say why.
     */
    @Test
    @DisplayName("the graph does not depend on the order settlements were registered in")
    void theGraphIsDeterministic() {
        Random random = new Random(77L);
        List<Settlement> cloud = new ArrayList<>();
        for (int i = 0; i < 15; i++) {
            cloud.add(at(i, random.nextInt(6000) - 3000, random.nextInt(6000) - 3000));
        }
        Set<RoadEdge> first = new LinkedHashSet<>(RoadGraph.of(cloud).edges());

        List<Settlement> shuffled = new ArrayList<>(cloud);
        Collections.shuffle(shuffled, new Random(99L));
        Set<RoadEdge> second = new LinkedHashSet<>(RoadGraph.of(shuffled).edges());

        assertEquals(first, second);
        assertEquals(RoadGraph.of(cloud).edges(), RoadGraph.of(cloud).edges(),
                "and the edge list is in a stable order, so a report reads the same twice");
    }

    @Test
    @DisplayName("an edge is undirected, normalised, and never joins a village to itself")
    void theEdgeRecordBehaves() {
        assertEquals(new RoadEdge(3, 1), new RoadEdge(1, 3));
        assertEquals(3, new RoadEdge(1, 3).other(1));
        assertEquals(1, new RoadEdge(1, 3).other(3));
        assertEquals(-1, new RoadEdge(1, 3).other(9));
        assertTrue(new RoadEdge(1, 3).touches(3));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new RoadEdge(2, 2));
    }
}
