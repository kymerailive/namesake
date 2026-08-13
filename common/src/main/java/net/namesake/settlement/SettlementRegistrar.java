package net.namesake.settlement;

import net.minecraft.Util;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.PoiTypeTags;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.namesake.Namesake;
import net.namesake.npc.NpcRegistry;
import net.namesake.npc.Personas;
import net.namesake.profile.Meter;
import net.namesake.profile.Meters;
import net.namesake.profile.Profiling;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

/**
 * Finds settlements, one at a time, without costing a tick.
 *
 * <p>A survey is three pieces of work with two thread boundaries between them, and the boundaries
 * are where safety puts them rather than where the design would prefer:
 *
 * <ol>
 *   <li><b>Census — server thread, spread over ticks.</b> {@code PoiManager} is a
 *       {@code SectionStorage} backed by a plain hash map that reads a whole chunk column off disk
 *       on a miss. It is not thread-safe, so this cannot move off the server thread however much
 *       {@code DESIGN.md} §8 would like it to. What it <i>can</i> do is stop being one blocking
 *       lump: a 128-block survey is 17×17 chunks, so at {@value #CHUNKS_PER_TICK} chunks a tick it
 *       finishes in under a second having never held the thread for more than a fraction of one.
 *       An all-at-once version would be ~289 blocking region reads in a single tick.</li>
 *   <li><b>Scoring — off-thread.</b> {@link SettlementSurvey} is pure arithmetic over the immutable
 *       values the census collected, so it goes to the background executor as written.</li>
 *   <li><b>Commit — server thread.</b> Claiming an id and writing to the registry.</li>
 * </ol>
 *
 * <p><b>One survey at a time, one survey per place, ever.</b> Areas are probed at a 128-block
 * granularity and remembered, so walking back and forth through a village does not re-survey it,
 * and a stretch of wilderness that turned out to have no bell is not asked twice.
 *
 * <p><b>Not polled.</b> A survey is requested by a villager loading with no settlement — an event —
 * and the tick hook only exists to spend the work that request created. With nothing queued it
 * returns on its first line.
 */
public final class SettlementRegistrar {

    /** Chunks whose POI columns are read per server tick while a survey is running. */
    private static final int CHUNKS_PER_TICK = 16;

    /** Probe granularity: one survey per square of this many blocks, per run. */
    private static final int PROBE_CELL = 128;

    private static final Deque<Request> QUEUE = new ArrayDeque<>();

    /** Cells with a survey queued or running. A villager here waits rather than being told "no". */
    private static final Set<Long> PENDING = new HashSet<>();

    /** Cells already surveyed this run. A villager here gets an answer immediately. */
    private static final Set<Long> PROBED = new HashSet<>();

    private static Scan active;

    /** True while a score is in flight on the background executor. */
    private static volatile boolean scoring;

    private record Request(ResourceKey<Level> dimension, BlockPos origin, long cell) {
    }

    private SettlementRegistrar() {
    }

    /**
     * Asks for the area around {@code pos} to be surveyed.
     *
     * @return true if the caller should wait — a survey is queued, running, or about to be. False
     * means this area has already been surveyed and has no settlement in it, so the caller should
     * stop waiting and act on that.
     */
    public static boolean request(ServerLevel level, BlockPos pos) {
        long cell = cellOf(level, pos);
        if (PENDING.contains(cell)) {
            return true;
        }
        if (PROBED.contains(cell)) {
            return false;
        }
        PENDING.add(cell);
        QUEUE.add(new Request(level.dimension(), pos.immutable(), cell));
        return true;
    }

    /** Hooked to the end of every server tick by both loaders. */
    public static void onServerTick(MinecraftServer server) {
        if (Profiling.MOD_INERT) {
            // Hard rule 4's baseline: the same world with none of our code in it. See Profiling.
            return;
        }
        if (scoring) {
            return;
        }
        if (active == null) {
            Request next = QUEUE.poll();
            if (next == null) {
                return;
            }
            ServerLevel level = server.getLevel(next.dimension());
            if (level == null) {
                // The dimension went away between the request and now. Treat it as surveyed so
                // nothing waits on it forever.
                PENDING.remove(next.cell());
                PROBED.add(next.cell());
                return;
            }
            active = new Scan(level, next.origin(), next.cell());
        }

        long begun = Meters.now();
        active.step(CHUNKS_PER_TICK);
        if (Profiling.ENABLED) {
            // What a survey costs the tick it is running in. Session 03 shipped this spread at
            // sixteen chunks a tick and recorded it as "bounded and one-shot, but unmeasured".
            STEP.end(begun);
        }
        if (!active.done()) {
            return;
        }
        Scan finished = active;
        active = null;
        if (Profiling.ENABLED) {
            // Counters, not a meter: these are chunks and ticks, and a histogram of nanoseconds
            // prints nineteen ticks as "0.019 us", which is a number that reads like a measurement
            // and is a unit error.
            Meters.count("censuses completed");
            Meters.count("census: server ticks spent", finished.ticksSpent);
            Meters.count("census: chunk columns read", finished.chunksRead);
        }
        onCensusComplete(server, finished);
    }

    private static final Meter STEP =
            Profiling.ENABLED ? Meters.meter("SettlementRegistrar step (" + CHUNKS_PER_TICK + " chunks)") : null;

    private static final Meter CHUNK =
            Profiling.ENABLED ? Meters.meter("PoiManager.getInChunk (one chunk column)") : null;

    private static final Meter SCORING =
            Profiling.ENABLED ? Meters.meter("SettlementSurvey.score (off-thread)") : null;

    /** Clears per-run state. Tokens, buckets and probes do not outlive a server. */
    public static void onServerStopping() {
        QUEUE.clear();
        PENDING.clear();
        PROBED.clear();
        active = null;
        scoring = false;
    }

    public static int probedCells() {
        return PROBED.size();
    }

    // --- the three stages ------------------------------------------------------------------------

    private static void onCensusComplete(MinecraftServer server, Scan scan) {
        Optional<BlockPos> centre = scan.census.centre(scan.origin);
        if (centre.isEmpty()) {
            Namesake.LOGGER.debug("Survey around {} found no bell in {} blocks; nobody here is a resident",
                    scan.origin.toShortString(), SettlementSurvey.SURVEY_RADIUS);
            settled(scan);
            return;
        }

        Settlements settlements = NpcRegistry.get(server).settlements();
        Optional<Settlement> existing =
                settlements.containing(scan.level.dimension().location(), centre.get());
        if (existing.isPresent()) {
            // Two overlapping probe cells found the same village. Nothing to register.
            Namesake.LOGGER.debug("Survey around {} reached settlement {}, already registered",
                    scan.origin.toShortString(), existing.get().id());
            settled(scan);
            return;
        }

        BlockPos bell = centre.get();
        scoring = true;
        Util.backgroundExecutor().execute(() -> {
            try {
                long begun = Meters.now();
                SettlementSurvey.Tally tally = scan.census.tally(bell, SettlementSurvey.SURVEY_RADIUS);
                SettlementSurvey.Survey survey = SettlementSurvey.score(tally);
                // Measured here and recorded on the server thread. A meter written from two
                // threads would need a lock, and a lock inside an instrument changes what it
                // measures — so the duration crosses the boundary, not the meter.
                long spent = Profiling.ENABLED ? System.nanoTime() - begun : 0L;
                server.execute(() -> {
                    if (Profiling.ENABLED) {
                        SCORING.record(spent);
                    }
                    commit(server, scan, bell, tally, survey);
                });
            } catch (RuntimeException e) {
                Namesake.LOGGER.error("Settlement survey around {} failed to score",
                        bell.toShortString(), e);
                server.execute(() -> settled(scan));
            }
        });
    }

    private static void commit(MinecraftServer server, Scan scan, BlockPos bell,
                               SettlementSurvey.Tally tally, SettlementSurvey.Survey survey) {
        try {
            NpcRegistry registry = NpcRegistry.get(server);
            Settlements settlements = registry.settlements();
            // Checked again: a second survey may have registered this village while this one was
            // being scored off-thread.
            if (settlements.containing(scan.level.dimension().location(), bell).isPresent()) {
                return;
            }

            Settlement settlement = new Settlement(
                    settlements.claimId(),
                    scan.level.dimension().location(),
                    bell,
                    survey.specialty().id(),
                    survey.defensibility(),
                    survey.needs());
            registry.putSettlement(settlement);

            Namesake.LOGGER.info("Registered settlement {} at {} ({}): {}, defensibility {}, {}",
                    settlement.id(), bell.toShortString(),
                    scan.level.dimension().location(),
                    settlement.specialtyValue(), settlement.defensibility(), tally);
        } finally {
            settled(scan);
        }
    }

    /**
     * Marks the probed cell answered and generates everyone who was waiting on it.
     *
     * <p>Runs on every path out of a survey, including the failures — a villager left waiting on a
     * survey that threw would stay nameless forever, and nothing would ever say why.
     */
    private static void settled(Scan scan) {
        scoring = false;
        PENDING.remove(scan.cell);
        PROBED.add(scan.cell);
        int generated = Personas.generateLoadedNear(scan.level, scan.origin);
        if (generated > 0) {
            Namesake.LOGGER.debug("Generated {} persona(s) waiting on the survey around {}",
                    generated, scan.origin.toShortString());
        }
    }

    private static long cellOf(ServerLevel level, BlockPos pos) {
        long cellX = Math.floorDiv(pos.getX(), PROBE_CELL);
        long cellZ = Math.floorDiv(pos.getZ(), PROBE_CELL);
        return (long) level.dimension().location().hashCode() * 0x9E3779B97F4A7C15L
                ^ cellX * 0xC2B2AE3D27D4EB4FL
                ^ cellZ * 0x165667B19E3779F9L;
    }

    /**
     * One survey in progress: a rectangle of chunks and a cursor through it.
     *
     * <p>Holds only immutable values once filled, which is what makes handing {@link #census} to a
     * background thread safe.
     */
    private static final class Scan {

        private final ServerLevel level;
        private final BlockPos origin;
        private final long cell;
        private final SettlementCensus census = new SettlementCensus();
        private final int minX;
        private final int minZ;
        private final int maxX;
        private final int maxZ;
        private int cursorX;
        private int cursorZ;
        private int chunksRead;
        private int ticksSpent;

        Scan(ServerLevel level, BlockPos origin, long cell) {
            this.level = level;
            this.origin = origin.immutable();
            this.cell = cell;
            int radius = SettlementSurvey.SURVEY_RADIUS / 16;
            ChunkPos middle = new ChunkPos(origin);
            this.minX = middle.x - radius;
            this.minZ = middle.z - radius;
            this.maxX = middle.x + radius;
            this.maxZ = middle.z + radius;
            this.cursorX = minX;
            this.cursorZ = minZ;
        }

        boolean done() {
            return cursorZ > maxZ;
        }

        void step(int chunks) {
            PoiManager poi = level.getPoiManager();
            ticksSpent++;
            for (int i = 0; i < chunks && !done(); i++) {
                // Timed one chunk column at a time rather than sixteen at once, because the shape
                // of this cost is the point: PoiManager reads a whole column off disk on a miss,
                // so the interesting number is not the mean but the tail.
                long begun = Meters.now();
                poi.getInChunk(holder -> holder.is(PoiTypeTags.VILLAGE),
                                new ChunkPos(cursorX, cursorZ), PoiManager.Occupancy.ANY)
                        .forEach(record -> census.record(record.getPoiType(), record.getPos()));
                if (Profiling.ENABLED) {
                    CHUNK.end(begun);
                }
                chunksRead++;
                if (++cursorX > maxX) {
                    cursorX = minX;
                    cursorZ++;
                }
            }
        }
    }
}
