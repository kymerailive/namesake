package net.namesake.road;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.namesake.settlement.Settlement;
import net.namesake.settlement.Settlements;
import net.namesake.settlement.Specialty;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The memo, and the one way it can be wrong.
 *
 * <p>The graph is derived rather than stored, which means it is <b>recomputed</b>, which means
 * something has to say when it is stale. That something is a counter on the settlement table, and a
 * counter that stops counting is invisible: the graph would simply keep answering with yesterday's
 * neighbours, and a village registered this afternoon would never hear anything from anywhere.
 * Nothing about the world would look wrong.
 */
class RoadsTest {

    private static final ResourceLocation OVERWORLD =
            ResourceLocation.withDefaultNamespace("overworld");

    private static Settlement at(int id, int x, int z) {
        return new Settlement(id, OVERWORLD, new BlockPos(x, 64, z),
                Specialty.FARMING.id(), (byte) 50, new byte[]{0, 0, 0, 0});
    }

    @Test
    @DisplayName("a village registered after the graph was built is in it")
    void theMemoNoticesANewSettlement() {
        Settlements settlements = new Settlements();
        settlements.put(at(0, 0, 0));
        assertEquals(0, Roads.graphOf(settlements).size(), "one village has no neighbours");

        settlements.put(at(1, 700, 0));
        assertTrue(Roads.graphOf(settlements).joins(0, 1),
                "a settlement registered after the graph was memoised must invalidate it, or a "
                        + "village found this afternoon never hears anything from anywhere");

        settlements.put(at(2, 350, 0));
        assertFalse(Roads.graphOf(settlements).joins(0, 1),
                "and a village between two others breaks the road that used to run past it");
        assertEquals(2, Roads.graphOf(settlements).size());
    }

    @Test
    @DisplayName("an unchanged table hands back the same graph rather than building a second one")
    void theMemoIsAMemo() {
        Settlements settlements = new Settlements();
        settlements.put(at(0, 0, 0));
        settlements.put(at(1, 700, 0));

        RoadGraph first = Roads.graphOf(settlements);
        assertSame(first, Roads.graphOf(settlements),
                "the drain asks this once per settlement per drain; rebuilding a Delaunay each time "
                        + "would be a cost nobody had measured");

        Roads.forget();
        assertEquals(first.edges(), Roads.graphOf(settlements).edges(),
                "and forgetting it changes the answer to nothing at all");
    }

    /**
     * Two tables at once, which is exactly what a running game and the headless simulation are. A
     * one-entry memo keyed on identity has to miss rather than answer with the other world's roads.
     */
    @Test
    @DisplayName("two settlement tables do not answer each other's questions")
    void twoWorldsDoNotShareAGraph() {
        Settlements world = new Settlements();
        world.put(at(0, 0, 0));
        world.put(at(1, 700, 0));

        Settlements simulation = new Settlements();
        simulation.put(at(0, 0, 0));

        assertEquals(1, Roads.graphOf(world).size());
        assertEquals(0, Roads.graphOf(simulation).size());
        assertEquals(1, Roads.graphOf(world).size(), "and back again");
    }
}
