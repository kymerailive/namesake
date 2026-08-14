package net.namesake.road;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The shape of a road, as columns rather than as blocks.
 *
 * <p>Split out from the materialiser so that "is it continuous", "is it three wide" and "does it
 * stay where the route said" are arithmetic rather than something somebody looked at once in a
 * running game. What only a running game can show is what happens when a column turns out to be
 * somebody's floor, and that is a harness leg.
 */
class RoadTrailTest {

    private static List<ChunkPos> route(int... coordinates) {
        List<ChunkPos> chunks = new ArrayList<>();
        for (int i = 0; i < coordinates.length; i += 2) {
            chunks.add(new ChunkPos(coordinates[i], coordinates[i + 1]));
        }
        return chunks;
    }

    /**
     * A road with a hole in it is a road a player walks off, and the hole would be invisible in
     * every other test here — the count would be right and the picture would not.
     */
    @Test
    @DisplayName("the centre line is continuous: every step touches the one before it")
    void theCentreLineHasNoHoles() {
        List<BlockPos> line = RoadTrail.centreLine(route(0, 0, 1, 0, 2, 1, 2, 2, 3, 3));
        assertTrue(line.size() > 40, () -> "four chunk hops is a lot of blocks, not " + line.size());
        for (int i = 1; i < line.size(); i++) {
            BlockPos here = line.get(i);
            BlockPos before = line.get(i - 1);
            int dx = Math.abs(here.getX() - before.getX());
            int dz = Math.abs(here.getZ() - before.getZ());
            assertTrue(dx <= 1 && dz <= 1 && dx + dz > 0,
                    () -> "step " + before + " -> " + here + " is a hole in the road");
        }
    }

    @Test
    @DisplayName("the line starts and ends at the two chunk centres the route named")
    void itRunsBetweenTheBells() {
        List<BlockPos> line = RoadTrail.centreLine(route(2, 3, 5, 3));
        assertEquals(RoadTrail.centreOf(new ChunkPos(2, 3)), line.get(0));
        assertEquals(RoadTrail.centreOf(new ChunkPos(5, 3)), line.get(line.size() - 1));
        assertEquals(new BlockPos(40, 0, 56), line.get(0), "chunk 2,3's middle block");
    }

    @Test
    @DisplayName("paving is three wide across the road, whichever way the road is going")
    void pavingIsThreeWide() {
        for (List<ChunkPos> path : List.of(route(0, 0, 4, 0), route(0, 0, 0, 4), route(0, 0, 4, 4))) {
            List<BlockPos> line = RoadTrail.centreLine(path);
            List<BlockPos> paving = RoadTrail.paving(line, RoadTrail.WIDTH);

            assertTrue(paving.size() >= line.size() * 2,
                    () -> "a three-wide road over " + line.size() + " steps is not " + paving.size()
                            + " columns");
            assertTrue(paving.size() <= line.size() * RoadTrail.WIDTH,
                    "and widening must not invent columns beyond its own width");
            assertTrue(new LinkedHashSet<>(paving).size() == paving.size(),
                    "a column laid twice is a column tested twice");
            Set<BlockPos> covered = new LinkedHashSet<>(paving);
            for (BlockPos step : line) {
                assertTrue(covered.contains(step),
                        () -> "the centre line itself has to be paved: " + step);
            }
        }
    }

    @Test
    @DisplayName("a one-wide road is the centre line and nothing else")
    void widthOneIsTheLine() {
        List<BlockPos> line = RoadTrail.centreLine(route(0, 0, 3, 1));
        assertEquals(new LinkedHashSet<>(line), new LinkedHashSet<>(RoadTrail.paving(line, 1)));
    }

    @Test
    @DisplayName("an empty route paves nothing rather than throwing on a worker thread")
    void theEmptyCases() {
        assertTrue(RoadTrail.centreLine(List.of()).isEmpty());
        assertTrue(RoadTrail.paving(List.of(), RoadTrail.WIDTH).isEmpty());
        assertEquals(1, RoadTrail.centreLine(route(7, 7)).size(),
                "a route of one chunk is one column, not a crash");
    }
}
