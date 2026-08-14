package net.namesake.road;

import net.minecraft.world.level.ChunkPos;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * <b>Where a road between two settlements actually runs.</b> A* over a coarse heightmap, one node
 * per chunk. {@code DESIGN.md} §8's road row.
 *
 * <h2>One node per chunk, and the grid is never the world</h2>
 *
 * <p>A route between neighbouring villages is a few dozen chunks, so a chunk-resolution grid is a
 * few dozen nodes wide and the search is over in microseconds. Block resolution would be two hundred
 * and fifty-six times as many nodes for a road nobody would be able to tell apart, and it would need
 * the real heightmap — which means loaded chunks, which means the disk. Session 04 measured a single
 * cold chunk column at <b>72 ms</b>, and a path that touched a hundred of them would be a visible
 * freeze rather than a road.
 *
 * <p><b>So the height comes from the generator's own noise rather than from a chunk.</b> That is
 * what {@link Terrain} is: the caller decides where a height comes from, the search only asks. In a
 * running game it is {@code ChunkGenerator.getBaseHeight}, which is the same estimate vanilla uses to
 * place structures in terrain that has not been generated yet, and it needs neither a chunk on disk
 * nor a chunk in memory. In a test it is a fixture array, which is why every claim about slope,
 * water and the search's own bounds is a unit test rather than a thing somebody watched happen once.
 *
 * <h2>What the cost function says</h2>
 *
 * <p>Three terms, and each is a sentence about roads. A step costs something for being a step, so a
 * road is short. It costs {@link #CLIMB} per block of height change, so a road goes round a hill
 * rather than over it. And entering a chunk whose surface is under the sea costs {@link #WATER},
 * which is large enough that a road walks a long way round a lake and small enough that a village on
 * an island still gets a route rather than a crash.
 *
 * <h2>Bounded, twice, because this runs off the server thread</h2>
 *
 * <p>The search is confined to a box around the two endpoints and to {@link #MAX_EXPANSIONS} nodes.
 * Both bounds produce an <i>incomplete</i> route rather than an exception, and an incomplete route is
 * a road that does not get built. A worker thread that can spin is a worker thread that will.
 */
public final class RoadPath {

    /** Where a height comes from. The search never asks the world anything else. */
    @FunctionalInterface
    public interface Terrain {

        /** The surface height at the centre of this chunk, in blocks. */
        int surfaceAt(int chunkX, int chunkZ);
    }

    /** Vanilla's sea level. A chunk whose surface is at or below it is water. */
    public static final int SEA_LEVEL = 62;

    /** What one orthogonal chunk step costs before terrain has an opinion. */
    public static final int STEP = 10;

    /** A diagonal step, at ten times root two rounded down, so diagonals are never free. */
    public static final int DIAGONAL = 14;

    /** Cost per block of height difference between two chunks. Roads go round hills. */
    public static final int CLIMB = 4;

    /** Cost of entering a chunk that is under water. Large, and deliberately not infinite. */
    public static final int WATER = 300;

    /** How far outside the two endpoints the search may wander, in chunks. */
    public static final int MARGIN = 24;

    /** The spin guard. A route that needs more nodes than this is one we do not build. */
    public static final int MAX_EXPANSIONS = 20_000;

    /**
     * How much worse than flat ground a route may be and still be worth building.
     *
     * <p><b>The A* weight's consumer, and it is an {@code if} rather than a number in a report.</b>
     * Two villages either side of an ocean are still neighbours — the graph says so, and gossip
     * travels the graph — but there is no road to draw between them, and drawing one anyway would
     * put a line of path blocks along the sea floor.
     */
    public static final int IMPASSABLE_RATIO = 6;

    private RoadPath() {
    }

    /**
     * One road, as the chunks it runs through.
     *
     * @param chunks   the route, start first. Empty when none was found.
     * @param cost     what it cost, in the units above. {@link #flatCost} is what it would have cost
     *                 over a billiard table, so the ratio between them is how hard the terrain is.
     * @param complete false when the search ran out of box or out of budget, which is the only two
     *                 ways it can fail. Nothing here throws.
     */
    public record Route(List<ChunkPos> chunks, long cost, long flatCost, boolean complete,
                        int expanded) {

        public Route {
            chunks = List.copyOf(chunks);
        }

        public static Route none(long flatCost, int expanded) {
            return new Route(List.of(), Long.MAX_VALUE, flatCost, false, expanded);
        }

        /** Whether this route is worth laying blocks along. See {@link #IMPASSABLE_RATIO}. */
        public boolean buildable() {
            return complete && !chunks.isEmpty() && cost <= flatCost * (long) IMPASSABLE_RATIO;
        }

        /** How much harder than flat ground the terrain made this. One decimal is plenty. */
        public double roughness() {
            return flatCost == 0 ? 1.0 : (double) cost / flatCost;
        }
    }

    /** The cheapest route between two chunks, or an incomplete one if there is not a cheap one. */
    public static Route between(ChunkPos from, ChunkPos to, Terrain terrain) {
        long flat = flatCost(from, to);
        if (from.equals(to)) {
            return new Route(List.of(from), 0L, flat, true, 0);
        }

        int minX = Math.min(from.x, to.x) - MARGIN;
        int maxX = Math.max(from.x, to.x) + MARGIN;
        int minZ = Math.min(from.z, to.z) - MARGIN;
        int maxZ = Math.max(from.z, to.z) + MARGIN;

        Map<Long, Long> best = new HashMap<>();
        Map<Long, Long> cameFrom = new HashMap<>();
        // Ordered by cost-plus-estimate, and ties broken by the estimate alone. The tie-break is not
        // decoration: over flat ground every node on a wide front has the same total, so without it
        // the search fans out across the whole box instead of walking to the goal — which is how a
        // perfectly ordinary long road hits MAX_EXPANSIONS and comes back unbuilt.
        PriorityQueue<long[]> open = new PriorityQueue<>((one, other) ->
                one[1] != other[1] ? Long.compare(one[1], other[1]) : Long.compare(one[2], other[2]));

        long start = ChunkPos.asLong(from.x, from.z);
        long goal = ChunkPos.asLong(to.x, to.z);
        best.put(start, 0L);
        open.add(new long[]{start, heuristic(from.x, from.z, to), heuristic(from.x, from.z, to)});

        int expanded = 0;
        while (!open.isEmpty()) {
            long[] head = open.poll();
            long current = head[0];
            int cx = ChunkPos.getX(current);
            int cz = ChunkPos.getZ(current);
            long spent = best.getOrDefault(current, Long.MAX_VALUE);
            if (head[1] > spent + heuristic(cx, cz, to)) {
                // A stale queue entry: this node has been reached more cheaply since it was pushed.
                continue;
            }
            if (current == goal) {
                return new Route(rebuild(cameFrom, start, goal), spent, flat, true, expanded);
            }
            if (++expanded > MAX_EXPANSIONS) {
                return Route.none(flat, expanded);
            }

            int here = terrain.surfaceAt(cx, cz);
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dz == 0) {
                        continue;
                    }
                    int nx = cx + dx;
                    int nz = cz + dz;
                    if (nx < minX || nx > maxX || nz < minZ || nz > maxZ) {
                        continue;
                    }
                    int there = terrain.surfaceAt(nx, nz);
                    long step = (dx != 0 && dz != 0 ? DIAGONAL : STEP)
                            + (long) CLIMB * Math.abs(there - here)
                            + (there <= SEA_LEVEL ? WATER : 0);
                    long through = spent + step;
                    long neighbour = ChunkPos.asLong(nx, nz);
                    if (through >= best.getOrDefault(neighbour, Long.MAX_VALUE)) {
                        continue;
                    }
                    best.put(neighbour, through);
                    cameFrom.put(neighbour, current);
                    long estimate = heuristic(nx, nz, to);
                    open.add(new long[]{neighbour, through + estimate, estimate});
                }
            }
        }
        return Route.none(flat, expanded);
    }

    /** What the route would cost across perfectly flat, dry ground. The denominator of a ratio. */
    public static long flatCost(ChunkPos from, ChunkPos to) {
        return heuristic(from.x, from.z, to);
    }

    /**
     * Octile distance in the same units as a step, which makes it admissible: no real step is
     * cheaper than {@link #STEP} orthogonally or {@link #DIAGONAL} diagonally, and terrain only ever
     * adds.
     */
    private static long heuristic(int x, int z, ChunkPos to) {
        long dx = Math.abs((long) x - to.x);
        long dz = Math.abs((long) z - to.z);
        return STEP * (dx + dz) + (DIAGONAL - 2L * STEP) * Math.min(dx, dz);
    }

    private static List<ChunkPos> rebuild(Map<Long, Long> cameFrom, long start, long goal) {
        List<ChunkPos> route = new ArrayList<>();
        long step = goal;
        route.add(new ChunkPos(ChunkPos.getX(step), ChunkPos.getZ(step)));
        while (step != start) {
            Long previous = cameFrom.get(step);
            if (previous == null) {
                // Cannot happen for a goal that was reached; returning what there is beats an NPE on
                // a worker thread nobody is watching.
                break;
            }
            step = previous;
            route.add(new ChunkPos(ChunkPos.getX(step), ChunkPos.getZ(step)));
        }
        Collections.reverse(route);
        return route;
    }
}
