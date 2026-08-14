package net.namesake.road;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The chunk route as block columns: a centre line, and the columns a road of a given width covers.
 *
 * <p>Split out from {@link RoadPath} because it is geometry rather than search, and from
 * {@code RoadNetwork} because it is arithmetic rather than a world. That is the seam that lets the
 * road's <i>shape</i> be a unit test — that it is continuous, that it stays inside its own chunks,
 * that widening it does not make it cross itself — while the part that touches blocks stays the one
 * thing only a running game can show.
 *
 * <p><b>The y coordinate is deliberately zero everywhere here.</b> A column's height is a property of
 * the world at the moment a block is laid, not of the route, and reading it here would mean reading a
 * heightmap from a chunk that may not be loaded. Session 03 lost a whole harness leg to exactly that:
 * {@code LevelReader#getHeight} answers with the world floor for a chunk that is not loaded, and a
 * village got built at y = −64 inside the deepslate.
 */
public final class RoadTrail {

    /**
     * How wide a road is, in blocks.
     *
     * <p>Three. One block reads as a mistake in the terrain and five is a motorway; three is what
     * vanilla's own village paths use, and it is the narrowest width that still reads as a road from
     * a distance — which is the entire point of building it, since nothing mechanical depends on the
     * blocks existing.
     */
    public static final int WIDTH = 3;

    private RoadTrail() {
    }

    /**
     * The centre line of the road, one block column per step, in order and without repeats.
     *
     * <p>Chunk centres joined by straight lines. The A* already smoothed the route at chunk scale,
     * so anything cleverer here — a spline, a jitter — would be decoration on a line nobody can see
     * the whole of at once.
     */
    public static List<BlockPos> centreLine(List<ChunkPos> route) {
        List<BlockPos> line = new ArrayList<>();
        if (route.isEmpty()) {
            return line;
        }
        BlockPos previous = null;
        for (int i = 0; i < route.size() - 1; i++) {
            for (BlockPos step : segment(centreOf(route.get(i)), centreOf(route.get(i + 1)))) {
                if (!step.equals(previous)) {
                    line.add(step);
                    previous = step;
                }
            }
        }
        if (route.size() == 1) {
            line.add(centreOf(route.get(0)));
        }
        return line;
    }

    /**
     * Every column a road of {@link #WIDTH} covers, centre line included.
     *
     * <p>Widened perpendicular to the local direction rather than by a disc, so a road running
     * north-south is three wide east-west and a diagonal is three wide across itself. A disc would
     * make corners fat and straights thin, which is the wrong way round.
     */
    public static List<BlockPos> paving(List<BlockPos> centreLine, int width) {
        Set<BlockPos> columns = new LinkedHashSet<>();
        int arm = (width - 1) / 2;
        for (int i = 0; i < centreLine.size(); i++) {
            BlockPos here = centreLine.get(i);
            BlockPos next = centreLine.get(Math.min(i + 1, centreLine.size() - 1));
            BlockPos back = centreLine.get(Math.max(i - 1, 0));
            int dx = next.getX() - back.getX();
            int dz = next.getZ() - back.getZ();
            // The perpendicular, normalised to the eight directions. A zero-length direction (a
            // one-column road) widens along x, which is as good an answer as any.
            int px = -Integer.signum(dz);
            int pz = Integer.signum(dx);
            if (px == 0 && pz == 0) {
                px = 1;
            }
            for (int offset = -arm; offset <= arm; offset++) {
                columns.add(new BlockPos(here.getX() + px * offset, 0, here.getZ() + pz * offset));
            }
        }
        return List.copyOf(columns);
    }

    /** The block column at the centre of a chunk. */
    public static BlockPos centreOf(ChunkPos chunk) {
        return new BlockPos(chunk.getMiddleBlockX(), 0, chunk.getMiddleBlockZ());
    }

    /** A straight line of block columns from one point to another, both ends included. */
    private static List<BlockPos> segment(BlockPos from, BlockPos to) {
        int dx = to.getX() - from.getX();
        int dz = to.getZ() - from.getZ();
        int steps = Math.max(Math.abs(dx), Math.abs(dz));
        List<BlockPos> line = new ArrayList<>(steps + 1);
        if (steps == 0) {
            line.add(from);
            return line;
        }
        for (int i = 0; i <= steps; i++) {
            line.add(new BlockPos(
                    from.getX() + Math.round((float) dx * i / steps), 0,
                    from.getZ() + Math.round((float) dz * i / steps)));
        }
        return line;
    }
}
