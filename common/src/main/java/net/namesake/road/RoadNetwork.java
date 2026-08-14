package net.namesake.road;

import net.minecraft.Util;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.namesake.Namesake;
import net.namesake.npc.NpcRegistry;
import net.namesake.profile.Profiling;
import net.namesake.settlement.Settlement;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * <b>The first thing in this mod that changes the world.</b> One A* off the server thread per tick,
 * and a bounded number of block columns laid per tick after it.
 *
 * <h2>What it will not touch — written down before a block was placed</h2>
 *
 * <p>Nine sessions of this project exist because the attach architecture let it avoid terrain
 * entirely, so the bounds are the design rather than a safety note bolted on afterwards. A road that
 * runs through somebody's base is not a bug report, it is a reason to uninstall.
 *
 * <ol>
 *   <li><b>It replaces exactly seven blocks and nothing else.</b> {@link #PAVEABLE} is grass, dirt,
 *       coarse dirt, podzol, rooted dirt, mycelium and moss — the natural ground cover a road is
 *       walked into. Sand, gravel, stone, snow blocks and every other surface are <i>skipped</i>, not
 *       converted, so a road fades out over a beach rather than digging one up.</li>
 *   <li><b>It never breaks anything, and it never places above ground level.</b> One block is
 *       swapped for {@code dirt_path} in place. Nothing above the surface is read, moved or
 *       removed.</li>
 *   <li><b>A player build is invisible to it.</b> The surface is found with
 *       {@code MOTION_BLOCKING_NO_LEAVES}, so the highest thing that stops you walking is what gets
 *       tested — a floor, a bridge, a farm, a path already laid. None of those are in the allowlist,
 *       so the column is skipped and the natural ground <i>underneath</i> a build is never even
 *       looked at. Farmland is not in the list either, so a village's crops are safe.</li>
 *   <li><b>It never loads a chunk.</b> Only chunks already in memory are paved, so a road appears as
 *       a player travels it and the rest waits. Session 04 measured one cold chunk column at
 *       <b>72 ms</b>; a materialiser that pulled a thousand of them off the disk would be a freeze,
 *       and session 03 lost a harness leg to {@code getHeight} answering with the world floor for a
 *       chunk that was not loaded.</li>
 *   <li><b>Reversible: honestly, no — and that is why there is a switch.</b> A laid block is a laid
 *       block; nothing here remembers what was underneath, because remembering would be persisted
 *       state and this session's whole argument is that roads need none.
 *       {@code -Dnamesake.roads=off} stops the block laying entirely, and <b>everything the thesis
 *       depends on still works</b> — the graph is what gossip crosses, and it is arithmetic over the
 *       settlement table. The blocks are the part you can see.</li>
 * </ol>
 *
 * <h2>What it costs</h2>
 *
 * <p>Two transient costs of the same class as session 03's census: bounded, one-shot per place, and
 * zero once there is nothing left to do. The A* is one edge per tick on the background executor and
 * never touches the world — see {@link RoadPath}. The laying is at most {@link #SCAN_PER_TICK}
 * column checks a tick while a road is being built, and a build that finds nothing to do backs off
 * for {@link #IDLE_TICKS}, so a half-built road on the far side of the world is not a permanent
 * per-tick cost. With no roads outstanding this returns on its second line.
 */
public final class RoadNetwork {

    /** Block columns examined per server tick while a road is being built. */
    public static final int SCAN_PER_TICK = 48;

    /** How long a build waits after a pass that laid nothing, because its chunks are not loaded. */
    public static final int IDLE_TICKS = 200;

    /**
     * The only blocks a road may replace. Natural ground cover, and nothing that anybody built.
     *
     * <p>Deliberately a literal list rather than a block tag. {@code #minecraft:dirt} contains
     * farmland and {@code #minecraft:base_stone_overworld} would let a road carve a mountain; a
     * modpack can add to a tag and would then be adding to what this is allowed to destroy. Seven
     * names in a file is a promise that reads the same in every modpack.
     */
    public static final Set<Block> PAVEABLE = Set.of(
            Blocks.GRASS_BLOCK,
            Blocks.DIRT,
            Blocks.COARSE_DIRT,
            Blocks.PODZOL,
            Blocks.ROOTED_DIRT,
            Blocks.MYCELIUM,
            Blocks.MOSS_BLOCK);

    /** What a road is made of. One vanilla block, no art, no new registry entry. */
    public static final Block SURFACE = Blocks.DIRT_PATH;

    private static final String SWITCH = "namesake.roads";

    private static final Map<RoadEdge, Build> BUILDS = new LinkedHashMap<>();
    private static final List<RoadEdge> UNROUTED = new ArrayList<>();

    private static int knownRevision = -1;
    private static int rotation;
    private static volatile boolean pathing;

    private RoadNetwork() {
    }

    /** Whether roads are laid as blocks at all. The graph does not care; see the class note. */
    public static boolean materialises() {
        return !"off".equalsIgnoreCase(System.getProperty(SWITCH, "on"));
    }

    /**
     * Where a height comes from in a running world: the generator's own noise, not a chunk.
     *
     * <p>The same estimate vanilla uses to place structures in terrain nobody has generated, so it
     * needs neither a chunk on disk nor a chunk in memory — which is what lets the search run off the
     * server thread at all. Session 04 measured one cold chunk column at 72 ms; a router that read a
     * hundred of them would be a freeze rather than a road.
     *
     * <p>Public because the harness has to be able to predict the route the router will find, and a
     * second copy of this lambda would be a fixture testing itself.
     */
    public static RoadPath.Terrain terrainOf(ServerLevel level) {
        ChunkGenerator generator = level.getChunkSource().getGenerator();
        RandomState random = level.getChunkSource().randomState();
        Map<Long, Integer> heights = new HashMap<>();
        return (cx, cz) -> heights.computeIfAbsent(ChunkPos.asLong(cx, cz), key ->
                generator.getBaseHeight(cx * 16 + 8, cz * 16 + 8,
                        Heightmap.Types.WORLD_SURFACE_WG, level, random));
    }

    /** Hooked to the end of every server tick by both loaders, beside the gossip drain. */
    public static void onServerTick(MinecraftServer server) {
        if (Profiling.MOD_INERT) {
            // Hard rule 4's baseline: the same world with none of our code in it. See Profiling.
            return;
        }
        if (!materialises()) {
            return;
        }
        NpcRegistry registry = NpcRegistry.get(server);
        RoadGraph graph = Roads.graphOf(registry.settlements());
        if (registry.settlements().revision() != knownRevision) {
            knownRevision = registry.settlements().revision();
            reconcile(graph);
        }
        if (BUILDS.isEmpty() && UNROUTED.isEmpty()) {
            return;
        }
        startOneRoute(server, registry);
        layOneBudget(server);
    }

    /** Clears per-run state. A new world is a new set of settlements and a new set of roads. */
    public static void onServerStopping() {
        BUILDS.clear();
        UNROUTED.clear();
        knownRevision = -1;
        rotation = 0;
        pathing = false;
        Roads.forget();
    }

    // --- the graph changed -------------------------------------------------------------------------

    /**
     * Adds work for edges that appeared and forgets edges that went away.
     *
     * <p><b>Blocks already laid are left where they are, and that is a decision rather than an
     * omission.</b> Un-paving would mean remembering what every column used to be, which is persisted
     * state for a cosmetic; and a road that was there yesterday is a road somebody walked.
     */
    private static void reconcile(RoadGraph graph) {
        BUILDS.keySet().removeIf(edge -> !graph.edges().contains(edge));
        UNROUTED.removeIf(edge -> !graph.edges().contains(edge));
        for (RoadEdge edge : graph.edges()) {
            if (!BUILDS.containsKey(edge) && !UNROUTED.contains(edge)) {
                UNROUTED.add(edge);
            }
        }
    }

    // --- one A* per tick, off the server thread ----------------------------------------------------

    private static void startOneRoute(MinecraftServer server, NpcRegistry registry) {
        if (pathing || UNROUTED.isEmpty()) {
            return;
        }
        RoadEdge edge = UNROUTED.remove(0);
        Optional<Settlement> from = registry.settlements().byId(edge.a());
        Optional<Settlement> to = registry.settlements().byId(edge.b());
        if (from.isEmpty() || to.isEmpty()) {
            return;
        }
        ServerLevel level = levelOf(server, from.get().dimension());
        if (level == null || !from.get().dimension().equals(to.get().dimension())) {
            return;
        }

        ChunkPos start = new ChunkPos(from.get().centre());
        ChunkPos goal = new ChunkPos(to.get().centre());
        // Built on the server thread: the generator and the random state are immutable for the life
        // of a world, and nothing below reads a chunk. Session 03's rule — what goes off-thread is
        // arithmetic over values already in hand, never a table the server thread is writing.
        RoadPath.Terrain terrain = terrainOf(level);
        ResourceKey<Level> dimension = level.dimension();

        pathing = true;
        Util.backgroundExecutor().execute(() -> {
            try {
                RoadPath.Route route = RoadPath.between(start, goal, terrain);
                server.execute(() -> commit(edge, dimension, route));
            } catch (RuntimeException e) {
                Namesake.LOGGER.error("Road {} could not be routed; no road will be built", edge, e);
                server.execute(() -> pathing = false);
            }
        });
    }

    private static void commit(RoadEdge edge, ResourceKey<Level> dimension, RoadPath.Route route) {
        pathing = false;
        if (!route.buildable()) {
            Namesake.LOGGER.info("Road {} is not worth building: {} ({} node(s) searched). "
                            + "The two settlements are still neighbours — gossip crosses the graph, "
                            + "not the blocks.",
                    edge, route.complete()
                            ? String.format(Locale.ROOT, "%.1fx the cost of flat ground",
                            route.roughness())
                            : "no route inside the search bounds", route.expanded());
            BUILDS.put(edge, Build.impassable(edge, dimension, route));
            return;
        }
        List<BlockPos> paving = RoadTrail.paving(RoadTrail.centreLine(route.chunks()), RoadTrail.WIDTH);
        BUILDS.put(edge, new Build(edge, dimension, route, paving));
        Namesake.LOGGER.info("Road {}: {} chunk(s), {} column(s), {}x the cost of flat ground",
                edge, route.chunks().size(), paving.size(),
                String.format(Locale.ROOT, "%.1f", route.roughness()));
    }

    // --- laying blocks -----------------------------------------------------------------------------

    private static void layOneBudget(MinecraftServer server) {
        if (BUILDS.isEmpty()) {
            return;
        }
        List<Build> builds = new ArrayList<>(BUILDS.values());
        for (int attempt = 0; attempt < builds.size(); attempt++) {
            Build build = builds.get(Math.floorMod(rotation++, builds.size()));
            if (build.finished || build.idleUntil > server.getTickCount()) {
                continue;
            }
            ServerLevel level = server.getLevel(build.dimension);
            if (level == null) {
                build.finished = true;
                continue;
            }
            if (build.spend(level, SCAN_PER_TICK) == 0) {
                // Nothing here is loaded. Come back in ten seconds rather than every tick.
                build.idleUntil = server.getTickCount() + IDLE_TICKS;
            }
            return;
        }
    }

    private static ServerLevel levelOf(MinecraftServer server, ResourceLocation dimension) {
        for (ServerLevel level : server.getAllLevels()) {
            if (level.dimension().location().equals(dimension)) {
                return level;
            }
        }
        return null;
    }

    // --- reporting ---------------------------------------------------------------------------------

    /**
     * How much of each road exists, for {@code /namesake debug roads} and for the harness.
     *
     * <p>Values rather than rows — see {@link RoadProgress} for why the layout is not done here.
     */
    public static List<RoadProgress> progress() {
        List<RoadProgress> rows = new ArrayList<>();
        for (Build build : BUILDS.values()) {
            rows.add(build.progress());
        }
        return rows;
    }

    /** Edges the router has not reached yet. Empty once every road has been decided. */
    public static List<RoadEdge> unrouted() {
        return List.copyOf(UNROUTED);
    }

    /** How many columns of road exist as blocks. The harness asserts on this. */
    public static int columnsLaid() {
        int laid = 0;
        for (Build build : BUILDS.values()) {
            laid += build.laid;
        }
        return laid;
    }

    /** The route found for an edge, for the harness and the debug command. */
    public static Optional<RoadPath.Route> routeOf(RoadEdge edge) {
        Build build = BUILDS.get(edge);
        return build == null ? Optional.empty() : Optional.of(build.route);
    }

    /**
     * One road being laid: where it runs, and which of its columns have been answered.
     *
     * <p>"Answered" rather than "laid" — a column that turned out to be a player's floor is done
     * with, and revisiting it every tick for the life of the world is how a bounded cost becomes an
     * unbounded one.
     */
    private static final class Build {

        private final RoadEdge edge;
        private final ResourceKey<Level> dimension;
        private final RoadPath.Route route;
        private final List<BlockPos> paving;
        private final BitSet answered;
        private int cursor;
        private int laid;
        private int refused;
        private int idleUntil;
        private boolean finished;

        Build(RoadEdge edge, ResourceKey<Level> dimension, RoadPath.Route route,
              List<BlockPos> paving) {
            this.edge = edge;
            this.dimension = dimension;
            this.route = route;
            this.paving = paving;
            this.answered = new BitSet(paving.size());
            this.finished = paving.isEmpty();
        }

        static Build impassable(RoadEdge edge, ResourceKey<Level> dimension, RoadPath.Route route) {
            return new Build(edge, dimension, route, List.of());
        }

        /** Answers up to {@code budget} columns, and reports how many blocks it actually laid. */
        int spend(ServerLevel level, int budget) {
            int placed = 0;
            for (int i = 0; i < budget && !finished; i++) {
                cursor = nextUnanswered();
                if (cursor < 0) {
                    finished = true;
                    break;
                }
                BlockPos column = paving.get(cursor);
                int chunkX = column.getX() >> 4;
                int chunkZ = column.getZ() >> 4;
                if (level.getChunkSource().getChunkNow(chunkX, chunkZ) == null) {
                    // Not loaded. Leave it unanswered — the road finishes when somebody walks it.
                    cursor++;
                    continue;
                }
                answered.set(cursor);
                if (pave(level, column)) {
                    laid++;
                    placed++;
                } else {
                    refused++;
                }
                cursor++;
            }
            if (answered.cardinality() >= paving.size()) {
                finished = true;
            }
            return placed;
        }

        private int nextUnanswered() {
            int from = answered.nextClearBit(Math.max(cursor, 0));
            if (from < paving.size()) {
                return from;
            }
            int wrapped = answered.nextClearBit(0);
            return wrapped < paving.size() ? wrapped : -1;
        }

        RoadProgress progress() {
            return paving.isEmpty()
                    ? RoadProgress.unbuilt(edge, route.complete(), route.roughness())
                    : new RoadProgress(edge, laid, paving.size(), refused, route.chunks().size(),
                    route.roughness(), true);
        }
    }

    /**
     * Lays one block, or refuses. Every bound in the class note is this method.
     *
     * @return true if a block was placed
     */
    private static boolean pave(ServerLevel level, BlockPos column) {
        // MOTION_BLOCKING_NO_LEAVES: the highest thing that would stop you walking. A floor, a
        // bridge, a farm, a trunk or water all answer here, and none of them are paveable — so a
        // player build hides the ground under it from this method entirely.
        int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                column.getX(), column.getZ());
        BlockPos surface = new BlockPos(column.getX(), top - 1, column.getZ());
        if (surface.getY() <= level.getMinBuildHeight() || surface.getY() >= level.getMaxBuildHeight()) {
            return false;
        }
        BlockState state = level.getBlockState(surface);
        if (!PAVEABLE.contains(state.getBlock()) || state.hasBlockEntity()) {
            return false;
        }
        if (!level.getFluidState(surface.above()).isEmpty()) {
            // A path under water is a trench in a lake bed. Skip the column.
            return false;
        }
        // Flag 2: tell the clients, do not run a neighbour cascade. Vanilla's own village paths are
        // placed this way, and a road is thousands of columns — a cascade per column would be a
        // physics storm for a cosmetic.
        return level.setBlock(surface, SURFACE.defaultBlockState(), Block.UPDATE_CLIENTS);
    }
}
