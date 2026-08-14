package net.namesake.road;

import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>Where a road runs, against a heightmap somebody wrote down.</b>
 *
 * <p>This is the half of session 10 that a unit test can prove and a running game cannot: whether a
 * road goes round a hill is a claim about a cost function, and pointing a client at real terrain and
 * looking at the result would be measuring the terrain rather than the search. So the terrain is a
 * fixture — {@link RoadPath.Terrain} exists for exactly this — and every claim below is about
 * arithmetic.
 */
class RoadPathTest {

    private static final RoadPath.Terrain FLAT = (x, z) -> 70;

    /** A north-south wall of mountain at {@code x == wallX}, with a gap at {@code gapZ}. */
    private static RoadPath.Terrain wall(int wallX, int gapZ) {
        return (x, z) -> x == wallX && z != gapZ ? 200 : 70;
    }

    /** A lake: everything inside the box is under the sea. */
    private static RoadPath.Terrain lake(int minX, int maxX, int minZ, int maxZ) {
        return (x, z) -> x >= minX && x <= maxX && z >= minZ && z <= maxZ
                ? RoadPath.SEA_LEVEL - 8 : 70;
    }

    @Test
    @DisplayName("over flat ground the road is the straight line, and costs exactly flat")
    void flatGroundIsTheStraightLine() {
        RoadPath.Route route = RoadPath.between(new ChunkPos(0, 0), new ChunkPos(20, 12), FLAT);

        assertTrue(route.complete());
        assertTrue(route.buildable());
        assertEquals(route.flatCost(), route.cost(),
                "flat ground has to cost what flat ground costs, or the heuristic is not admissible");
        assertEquals(21, route.chunks().size(), "octile: twelve diagonals and eight straights");
        assertEquals(new ChunkPos(0, 0), route.chunks().get(0));
        assertEquals(new ChunkPos(20, 12), route.chunks().get(route.chunks().size() - 1));
        assertEquals(1.0, route.roughness(), 0.0001);
    }

    /**
     * The sentence the cost function is for: a road goes round a hill rather than over it.
     *
     * <p>Held to the route rather than to the cost, because "it cost more" is true of a road that
     * went straight over as well.
     */
    @Test
    @DisplayName("a road goes round a mountain, through the one gap in it")
    void aRoadGoesRoundAHill() {
        RoadPath.Route route = RoadPath.between(new ChunkPos(0, 0), new ChunkPos(10, 0), wall(5, 6));

        assertTrue(route.complete());
        for (ChunkPos step : route.chunks()) {
            assertFalse(step.x == 5 && step.z != 6,
                    () -> "the road climbed the wall at " + step + " instead of using the gap");
        }
        assertTrue(route.chunks().stream().anyMatch(step -> step.x == 5 && step.z == 6),
                "and it went through the gap");
        assertTrue(route.cost() > route.flatCost(), "a detour costs more than a straight line");
    }

    @Test
    @DisplayName("a road walks round a lake rather than along the bottom of it")
    void aRoadGoesRoundWater() {
        RoadPath.Route route = RoadPath.between(new ChunkPos(0, 0), new ChunkPos(12, 0),
                lake(3, 9, -3, 3));

        assertTrue(route.complete());
        assertTrue(route.buildable(), "a lake you can walk round is still a road");
        for (ChunkPos step : route.chunks()) {
            assertFalse(step.x >= 3 && step.x <= 9 && step.z >= -3 && step.z <= 3,
                    () -> "the road ran through the lake at " + step);
        }
    }

    /**
     * <b>The A* weight's consumer, and it is an {@code if} rather than a number in a report.</b>
     *
     * <p>A village on an island is still a neighbour — the graph says so and gossip crosses the graph
     * — and there is no road to draw. Without this the materialiser would lay a line of path blocks
     * along the sea floor, which is the kind of thing a player uninstalls over.
     */
    @Test
    @DisplayName("a route that can only cross an ocean is found and refused")
    void anOceanCrossingIsNotBuilt() {
        RoadPath.Route route = RoadPath.between(new ChunkPos(0, 0), new ChunkPos(30, 0),
                lake(1, 29, -60, 60));

        assertTrue(route.complete(), "there is a way; it is just all sea");
        assertFalse(route.buildable(),
                () -> "an ocean crossing came out at " + route.roughness() + "x flat ground, inside "
                        + "the " + RoadPath.IMPASSABLE_RATIO + "x a road is allowed to be");
        assertTrue(route.roughness() > RoadPath.IMPASSABLE_RATIO);
    }

    /**
     * The spin guard. This runs on a background thread nobody is watching, so the failure mode has
     * to be an unbuilt road rather than a worker at 100%.
     */
    @Test
    @DisplayName("the search is bounded, and running out of budget is an unbuilt road, not a throw")
    void theSearchIsBounded() {
        Random random = new Random(31337L);
        int[] noise = new int[1 << 16];
        for (int i = 0; i < noise.length; i++) {
            noise[i] = 60 + random.nextInt(120);
        }
        RoadPath.Terrain rough = (x, z) -> noise[Math.floorMod(x * 7919 + z * 104729, noise.length)];

        RoadPath.Route route = RoadPath.between(new ChunkPos(0, 0), new ChunkPos(900, 400), rough);
        assertTrue(route.expanded() <= RoadPath.MAX_EXPANSIONS + 1,
                () -> "the search expanded " + route.expanded() + " nodes against a cap of "
                        + RoadPath.MAX_EXPANSIONS);
        if (!route.complete()) {
            assertFalse(route.buildable(), "an incomplete route must never be laid as blocks");
            assertTrue(route.chunks().isEmpty());
        }
    }

    @Test
    @DisplayName("a road that stays inside its own bounding box, and one to nowhere")
    void theSearchStaysInItsBox() {
        RoadPath.Route route = RoadPath.between(new ChunkPos(-8, -8), new ChunkPos(8, 8),
                wall(0, 40));
        for (ChunkPos step : route.chunks()) {
            assertTrue(step.x >= -8 - RoadPath.MARGIN && step.x <= 8 + RoadPath.MARGIN
                            && step.z >= -8 - RoadPath.MARGIN && step.z <= 8 + RoadPath.MARGIN,
                    () -> step + " is outside the search box");
        }

        RoadPath.Route nowhere = RoadPath.between(new ChunkPos(3, 3), new ChunkPos(3, 3), FLAT);
        assertEquals(1, nowhere.chunks().size(), "a settlement is already at its own bell");
        assertEquals(0L, nowhere.cost());
    }

    /**
     * A road between two real villages is a few dozen chunks, so the search has to be cheap enough
     * that one a tick is genuinely free. Not a wall-clock assertion — {@code WORKPLAN.md} forbids
     * those in CI — but a count of nodes, which is a property of the code.
     */
    @Test
    @DisplayName("a road between neighbouring villages costs a few hundred nodes, not a few thousand")
    void aRealisticRouteIsCheap() {
        RoadPath.Terrain rolling = (x, z) -> 70 + (int) (18 * Math.sin(x * 0.31) * Math.cos(z * 0.27));
        RoadPath.Route route = RoadPath.between(new ChunkPos(0, 0), new ChunkPos(64, 24), rolling);

        assertTrue(route.complete());
        assertTrue(route.expanded() < 4_000,
                () -> "a 64-chunk road expanded " + route.expanded() + " nodes; the tie-break on the "
                        + "open queue is what keeps that from fanning out across the whole box");
    }

    /**
     * <b>The fixture the breakage pass found missing, and the two ways it was missing.</b>
     *
     * <p>Removing the tie-break on the open queue turned <i>nothing</i> red. The rolling-terrain
     * route above has few ties to break, so it never reached the guard — and the <b>first</b> fixture
     * written for that ran flat ground due east, where every deviation costs more and the cheapest
     * path is therefore unique. There were no ties to break there either.
     *
     * <p>The case that matters is flat ground on a <b>bearing that is neither straight nor a perfect
     * diagonal</b>: every ordering of the diagonal and the straight steps costs exactly the same, so
     * the whole staircase between the two villages carries one cost-plus-estimate and the queue has
     * no opinion at all. Measured: <b>120 nodes with the tie-break and 1,100 without</b> — which is
     * also why the budget here is three hundred rather than the fifteen hundred the second attempt
     * used. <b>A threshold above the broken number is a guard that is still not reached.</b>
     *
     * <p>Two villages a hundred and twenty chunks apart across a plain is not an exotic world, and
     * hitting {@link RoadPath#MAX_EXPANSIONS} there is a road that silently does not get built.
     */
    @Test
    @DisplayName("a long road over flat ground walks to the goal instead of searching the whole box")
    void flatGroundDoesNotFanOut() {
        RoadPath.Route route = RoadPath.between(new ChunkPos(0, 0), new ChunkPos(120, 60), FLAT);

        assertTrue(route.complete(), "a plain has to be crossable");
        assertEquals(route.flatCost(), route.cost(), "and at exactly the cost of flat ground");
        assertTrue(route.expanded() < 300,
                () -> "a 120-chunk road across flat ground expanded " + route.expanded()
                        + " nodes against a budget of 300. Every ordering of its steps costs the "
                        + "same, so without the tie-break on the open queue the search expands the "
                        + "whole staircase — measured at 1,100.");
    }
}
