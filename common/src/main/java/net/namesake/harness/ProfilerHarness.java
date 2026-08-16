package net.namesake.harness;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.StructureTags;
import net.minecraft.util.profiling.ProfileResults;
import net.minecraft.util.profiling.ResultField;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.AABB;
import net.namesake.Namesake;
import net.namesake.npc.NpcRegistry;
import net.namesake.npc.Persona;
import net.namesake.profile.Histogram;
import net.namesake.profile.Meter;
import net.namesake.profile.Meters;
import net.namesake.profile.PersonaSweep;
import net.namesake.profile.Profiling;
import net.namesake.profile.SyntheticPersonas;
import net.namesake.settlement.Settlements;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/**
 * Session 04's instrument: a scripted run that measures what a tick actually costs, with and
 * without us in it.
 *
 * <p><b>Armed only by {@code -Dnamesake.profile=<phase>}</b>, exactly like the attach-bet harness
 * and for the same reason — it rewrites game rules, lays thousands of blocks and spawns four
 * hundred villagers. Three phases, each its own launch:
 *
 * <ul>
 *   <li>{@code vanilla} — our hooks are switched off at {@link Profiling#MOD_INERT} and the run
 *       measures Minecraft with villagers in it. <b>Hard rule 4: this comes first.</b> Without it
 *       our own number means nothing.</li>
 *   <li>{@code namesake} — the same world, the same terrain, the same villager counts, our hooks
 *       live. The difference between the two phases is what we cost.</li>
 *   <li>{@code world} — a real generated world, visited village by village, which is where the
 *       population figure and session 03's two unmeasured costs are read from.</li>
 * </ul>
 *
 * <p><b>The rule this file is written against.</b> A profiler that measures the wrong thing reports
 * a number just as confidently as one that measures the right thing, so nothing here is trusted
 * until it has been pointed at something already known:
 *
 * <ol>
 *   <li>An <b>empty</b> measurement, to establish what the instrument itself costs and therefore
 *       what it cannot resolve.</li>
 *   <li>A spin whose length is decided by {@code System.currentTimeMillis} and read back by a
 *       {@code System.nanoTime} meter. Two clocks, and a factor-of-1000 unit slip survives
 *       neither.</li>
 *   <li>Whole-tick times taken from <b>vanilla's own</b> {@code tickTimesNanos} rather than from a
 *       clock of ours, and cross-checked against the wall-clock length of the window — so a
 *       sampling loop that skipped or double-counted ticks says so instead of shifting the mean.
 *       </li>
 *   <li>An assertion that every villager <b>actually ticked</b> through the window. Session 01 lost
 *       three CI runs to entities that were loaded and not ticking, and a baseline measured on
 *       villagers that never ran their brains is the most confident wrong number available.</li>
 * </ol>
 */
public final class ProfilerHarness {

    /** World for the two measurement phases. Shared, so both see identical terrain. */
    public static final String WORLD_MEASURE = "namesake_profiler";

    /** World for the population census. Never built in, so its villages are as generated. */
    public static final String WORLD_POPULATION = "namesake_population";

    public static final long WORLD_SEED = 20260814L;

    public static final Path RESULT_FILE = Path.of("namesake-profile-result.txt");
    public static final Path REPORT_FILE = Path.of("namesake-profile-report.txt");
    private static final Path VANILLA_TREE_FILE = Path.of("namesake-profile-vanilla-sections.txt");

    /**
     * Where the measurement grid is tried, in order, as offsets from world spawn.
     *
     * <p>Far enough out that the permanent spawn ticket is not holding the chunks — and a list
     * rather than one place because the first version used a single offset and landed the whole
     * grid in an ocean. Sixteen stone rafts is a measurement of villagers on rafts.
     */
    private static final int[][] CANDIDATES = {
            {1200, 1200}, {-1200, 1200}, {1200, -1200}, {-1200, -1200},
            {1800, 0}, {0, 1800}, {-1800, 0}, {0, -1800}};

    private static final int SITE_GRID = 4;
    private static final int SITE_SPACING = 56;
    private static final int SITE_WIDTH = 24;
    private static final int SITE_DEPTH = 32;
    private static final int PER_SITE_MAX = 25;

    /** Ticks discarded before each window, so a cell is not measuring its own arrival. */
    private static final int WARM_UP_TICKS = 400;

    /** Ticks measured per cell. 1200 is a minute of real time and 1200 samples per section. */
    private static final int WINDOW_TICKS = 1200;

    private static final long SHUTDOWN_GRACE_MILLIS = 45_000L;

    private static final String PHASE = Profiling.phase();
    private static final char SEP = ProfileResults.PATH_SEPARATOR;

    private static final Meter SWEEP = Profiling.ENABLED
            ? Meters.meter("PersonaSweep.advance (one bucket of 20)") : null;
    private static final Meter SWEEP_REBUILD = Profiling.ENABLED
            ? Meters.meter("PersonaSweep.rebuild (whole population)") : null;
    private static final Meter EMPTY = Profiling.ENABLED
            ? Meters.meter("calibration: an empty measurement") : null;

    // --- script state -----------------------------------------------------------------------------

    private static int stage;
    private static int tick;
    private static int deadline;
    private static int lastReport;
    private static boolean finished;

    private static final List<String> RESULTS = new ArrayList<>();
    private static final List<String> REPORT = new ArrayList<>();

    private static BlockPos gridCentre;
    private static int siteCandidate;
    private static final List<BlockPos> SITES = new ArrayList<>();
    private static int builtSites;

    private static final PersonaSweep SWEEPER = new PersonaSweep();
    private static List<Persona> synthetic = List.of();
    private static int syntheticEverBuilt;

    private static int cellIndex;
    private static Cell cell;
    private static final Histogram TICKS = new Histogram();
    private static int windowStartTick;
    private static int windowEndTick;
    private static int lastSampledTick;
    private static long windowStartMillis;
    private static final List<Villager> SUBJECTS = new ArrayList<>();
    private static final List<Integer> SUBJECT_TICKS_AT_START = new ArrayList<>();
    private static volatile ProfileResults vanillaResults;

    /** Population phase. */
    private static final int[][] SEARCH_ORIGINS = {{0, 0}, {1600, 0}, {0, 1600}, {-1600, 0},
            {0, -1600}, {1600, 1600}, {-1600, 1600}, {1600, -1600}};

    /** How long to stand in a village before counting it. A village does not arrive all at once. */
    private static final int VILLAGE_DWELL_TICKS = 900;

    private static int searchOrigin;
    private static int dwellUntil;
    private static final List<BlockPos> VILLAGES = new ArrayList<>();
    private static int villageIndex;
    private static final List<String> VILLAGE_ROWS = new ArrayList<>();

    /**
     * The day time every cell was frozen at until session 14: mid-morning, inside
     * {@code DaySlot.LABOUR_I} and a thousand ticks past the widest transition spread.
     */
    private static final int LABOUR_MORNING = 3000;

    /**
     * One measured configuration.
     *
     * @param records    how many fixture persona records the sweep walks
     * @param payload    whether the sweep runs the probe payload or only its own frame
     * @param mcProfiler whether Minecraft's own profiler records during the window
     * @param dayTime    <b>the hour this cell measures, and session 14 is why it is a field.</b> A
     *                   tick happens at one time of day, so a per-tick budget is a claim about the
     *                   <i>worst</i> hour rather than about the sum of them — and the day plan does
     *                   entirely different work at eleven o'clock (everybody on an errand) than at
     *                   nine (one villager in four vetoed at a workstation). One frozen hour would
     *                   have priced session 14 at zero and been perfectly confident about it.
     */
    private record Cell(String name, int sites, int perSite, int records, boolean payload,
                        boolean mcProfiler, int dayTime) {

        Cell(String name, int sites, int perSite, int records, boolean payload, boolean mcProfiler) {
            this(name, sites, perSite, records, payload, mcProfiler, LABOUR_MORNING);
        }

        int villagers() {
            return sites * perSite;
        }
    }

    private ProfilerHarness() {
    }

    public static boolean enabled() {
        return Profiling.ENABLED;
    }

    public static boolean isFinished() {
        return finished;
    }

    public static String worldName() {
        return "world".equals(PHASE) ? WORLD_POPULATION : WORLD_MEASURE;
    }

    /**
     * The measured configurations, in order.
     *
     * <p>Both phases run the same villager counts on the same sites so the two tables subtract.
     * Only the {@code namesake} phase sweeps records, because the sweep is our code — which makes
     * the three-way decomposition explicit: {@code vanilla 400} is the engine, {@code namesake 400}
     * with no records is our hooks, and {@code namesake 400 + 400} adds the sweep.
     */
    private static List<Cell> cells() {
        if (PHASE.startsWith("sections")) {
            // One cell, because Minecraft's own metrics recorder stops itself ten seconds in and
            // this phase exists only to read the section tree out of it. Five minutes rather than
            // twenty when the question is "what is villagerBrain actually costing".
            //
            // Two spellings: `sections` runs with our hooks inert and `sections-live` runs with
            // them on. The pair was built when the two measurement phases disagreed by a factor of
            // two at four hundred villagers while our meters recorded not one call during the
            // window — a section tree from each side being the only instrument that could say
            // where that went. It turned out to be POI tickets, golems and dropped items left
            // behind by the previous run, and clearing them made the two phases agree to within
            // 0.35 ms. The pair stays, because that question will be asked again and this is what
            // answers it in five minutes.
            return List.of(new Cell("400 loaded, MC profiler recording", 16, 25, 0, false, true));
        }
        if ("hours".equals(PHASE)) {
            // ONE POPULATION, THREE HOURS, and nothing else. Session 14's whole budget question is
            // whether the *peak* moved, which is a comparison between hours at one population — and
            // the eleven-cell run that answered it first took forty minutes, during which this
            // machine was also building and testing. Two cells of that run executed the same
            // do-nothing path and read 1.65 µs and 0.735 µs, which is a 2.2x spread on identical
            // work and is the instrument saying its own floor had moved. `sections` exists for the
            // same reason one question at a time deserves its own phase.
            return List.of(
                    new Cell("idle, no villagers", 0, 0, 0, false, false),
                    new Cell("96 loaded + 304 records at 09:00 (LABOUR_I)", 8, 12, 304, true, false),
                    new Cell("96 loaded + 304 records at 11:00 (HAUL)",
                            8, 12, 304, true, false, ERRAND_MIDDAY),
                    new Cell("96 loaded + 304 records at 21:00 (NIGHT)",
                            8, 12, 304, true, false, ERRAND_NIGHT));
        }
        if ("vanilla".equals(PHASE)) {
            return List.of(
                    new Cell("idle, no villagers", 0, 0, 0, false, false),
                    new Cell("96 loaded (8 sites x 12)", 8, 12, 0, false, false),
                    new Cell("96 loaded at 11:00 (HAUL)", 8, 12, 0, false, false, ERRAND_MIDDAY),
                    new Cell("96 loaded at 21:00 (NIGHT)", 8, 12, 0, false, false, ERRAND_NIGHT),
                    new Cell("100 loaded (4 x 25)", 4, 25, 0, false, false),
                    new Cell("200 loaded (8 x 25)", 8, 25, 0, false, false),
                    new Cell("400 loaded (16 x 25)", 16, 25, 0, false, false),
                    new Cell("400 loaded, MC profiler recording", 16, 25, 0, false, true));
        }
        return List.of(
                new Cell("idle, no villagers", 0, 0, 0, false, false),
                new Cell("sweep 400 records, frame only", 0, 0, 400, false, false),
                new Cell("sweep 400 records, probe payload", 0, 0, 400, true, false),
                new Cell("DESIGN scenario: 96 loaded + 304 records", 8, 12, 304, true, false),
                // Session 14's two, and they are the point of the run rather than an addition. The
                // ~5.95 µs budget is a per-tick number and a tick happens at one time of day, so
                // what has to be priced is not "the standoff plus the errands" but the most
                // expensive HOUR. These are the two hours session 14 puts work in that nine o'clock
                // does not: every villager on an errand, and the watch out with the rest asleep.
                new Cell("DESIGN scenario at 11:00 (HAUL): 96 loaded + 304 records",
                        8, 12, 304, true, false, ERRAND_MIDDAY),
                new Cell("DESIGN scenario at 21:00 (NIGHT): 96 loaded + 304 records",
                        8, 12, 304, true, false, ERRAND_NIGHT),
                new Cell("100 loaded (4 x 25)", 4, 25, 0, false, false),
                new Cell("200 loaded (8 x 25)", 8, 25, 0, false, false),
                new Cell("400 loaded (16 x 25)", 16, 25, 0, false, false),
                new Cell("400 loaded at 11:00 (HAUL)", 16, 25, 0, false, false, ERRAND_MIDDAY),
                new Cell("400 loaded + 400 records swept", 16, 25, 400, true, false));
    }

    /** Inside {@code DaySlot.HAUL} and past the spread: every villager is on an errand. */
    private static final int ERRAND_MIDDAY = 5500;

    /** Inside {@code DaySlot.NIGHT}: the watch is out and everybody else is in bed. */
    private static final int ERRAND_NIGHT = 15000;

    // --- driver -----------------------------------------------------------------------------------

    /** Hooked to the end of every server tick by both loaders. */
    public static void onServerTick(MinecraftServer server) {
        if (!enabled() || finished) {
            return;
        }
        tick++;
        try {
            if (tick == 1) {
                writeResult("RUNNING", "the script started but never reached a conclusion");
                Namesake.LOGGER.info("[profile] phase '{}' starting in world '{}' (mod inert: {})",
                        PHASE, worldName(), Profiling.MOD_INERT);
            }
            if ("world".equals(PHASE)) {
                runPopulation(server);
            } else if ("vanilla".equals(PHASE) || "namesake".equals(PHASE) || "hours".equals(PHASE)
                    || PHASE.startsWith("sections")) {
                runMeasurement(server);
            } else {
                Namesake.LOGGER.error("[profile] unknown phase '{}'; expected vanilla, namesake, "
                        + "sections, sections-live, hours or world", PHASE);
                record(false, "unknown phase '" + PHASE + "'");
                finish(server, false);
            }
        } catch (Exception e) {
            Namesake.LOGGER.error("[profile] stage {} threw", stage, e);
            record(false, "stage " + stage + " threw " + e);
            finish(server, false);
        }
    }

    // --- the measurement phases -------------------------------------------------------------------

    private static void runMeasurement(MinecraftServer server) {
        ServerLevel level = server.overworld();
        switch (stage) {
            case 0 -> {
                ServerPlayer player = player(server);
                if (player == null) {
                    return;
                }
                configure(server, level, player);
                BlockPos spawn = level.getSharedSpawnPos();
                int[] candidate = CANDIDATES[siteCandidate];
                gridCentre = new BlockPos(spawn.getX() + candidate[0], spawn.getY(),
                        spawn.getZ() + candidate[1]);
                teleport(player, level, gridCentre.getX(), 200, gridCentre.getZ());
                await(4000);
            }
            case 1 -> {
                // Chunk IO, so never sprinted: LevelReader#getHeight answers with the world floor
                // for a chunk that is not loaded, and session 03 built a village inside the
                // deepslate that way.
                if (waiting(server, () -> chunksReady(level), "the site chunks to load")) {
                    return;
                }
                if (!isLand(level) && siteCandidate + 1 < CANDIDATES.length) {
                    // The first run of this landed the whole grid in an ocean — sixteen stone rafts
                    // and three elder guardians in the profile. Villagers on a raft path differently
                    // from villagers on ground, which is the one thing this site has to get right.
                    Namesake.LOGGER.info("[profile] candidate {} at {} is water; trying the next",
                            siteCandidate, gridCentre.toShortString());
                    siteCandidate++;
                    stage = 0;
                    deadline = tick + 100;
                    lastReport = tick;
                    return;
                }
                record(chunksReady(level), "SITE every chunk of the measurement grid is loaded");
                record(isLand(level), "SITE all sixteen sites are on land above sea level");
                gridCentre = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, gridCentre);
                record(gridCentre.getY() > level.getMinBuildHeight() + 8,
                        "SITE the measurement grid is on real ground at y=" + gridCentre.getY());
                SITES.clear();
                for (int gz = 0; gz < SITE_GRID; gz++) {
                    for (int gx = 0; gx < SITE_GRID; gx++) {
                        SITES.add(gridCentre.offset(
                                (2 * gx - (SITE_GRID - 1)) * SITE_SPACING / 2, 0,
                                (2 * gz - (SITE_GRID - 1)) * SITE_SPACING / 2));
                    }
                }
                teleport(player(server), level, gridCentre.getX(), gridCentre.getY() + 4,
                        gridCentre.getZ());
                builtSites = 0;
                next();
            }
            case 2 -> {
                // Two sites a tick. Laying every site at once stalls the server for seconds, and
                // the catch-up ticks that follow are what unloaded entities under session 03.
                for (int i = 0; i < 2 && builtSites < SITES.size(); i++) {
                    buildSite(level, SITES.get(builtSites++));
                }
                if (builtSites < SITES.size()) {
                    return;
                }
                Namesake.LOGGER.info("[profile] built {} site(s) around {}",
                        SITES.size(), gridCentre.toShortString());
                await(600);
            }
            case 3 -> {
                if (waiting(server, () -> false, "the built sites to settle")) {
                    return;
                }
                calibrate();
                cellIndex = 0;
                next();
            }
            case 4 -> {
                List<Cell> cells = cells();
                if (cellIndex >= cells.size()) {
                    stage = 8;
                    deadline = tick + 600;
                    lastReport = tick;
                    return;
                }
                cell = cells.get(cellIndex);
                Namesake.LOGGER.info("[profile] cell {}/{}: {}",
                        cellIndex + 1, cells.size(), cell.name());
                populate(server, level, cell);
                await(3000);
            }
            case 5 -> {
                if (waiting(server, () -> settled(server),
                        "the villagers to load, take jobs and find the bell")) {
                    return;
                }
                // AND ONLY NOW THE HOUR THIS CELL IS ABOUT. See populate() for why they settle at
                // mid-morning first whatever hour is being measured.
                level.setDayTime(cell.dayTime());
                record(settled(server), "SETTLED '" + cell.name() + "': the villagers were employed "
                        + "and holding a meeting point before the clock was moved to "
                        + cell.dayTime());
                windowStartTick = server.getTickCount() + WARM_UP_TICKS;
                await(WARM_UP_TICKS + 400);
            }
            case 6 -> {
                driveSweep(server);
                if (server.getTickCount() < windowStartTick && tick < deadline) {
                    return;
                }
                // Everything measured starts here: the meters are wiped after the warm-up rather
                // than before it, so a cell reports its steady state and not its arrival.
                Meters.reset();
                TICKS.clear();
                SUBJECT_TICKS_AT_START.clear();
                for (Villager villager : SUBJECTS) {
                    SUBJECT_TICKS_AT_START.add(villager.tickCount);
                }
                lastSampledTick = server.getTickCount();
                windowEndTick = server.getTickCount() + WINDOW_TICKS;
                windowStartMillis = System.currentTimeMillis();
                if (cell.mcProfiler()) {
                    vanillaResults = null;
                    server.startRecordingMetrics(results -> vanillaResults = results, path -> {
                    });
                }
                next();
            }
            case 7 -> {
                driveSweep(server);
                sampleTicks(server);
                // The metrics recorder stops itself ten seconds in — its own deadline, not ours —
                // so that cell's window is however long it actually recorded. Letting it run the
                // full minute would average two hundred profiled ticks with a thousand unprofiled
                // ones and report the overhead as a fifth of what it is.
                boolean done = cell.mcProfiler()
                        ? vanillaResults != null || lastSampledTick >= windowEndTick
                        : lastSampledTick >= windowEndTick;
                if (!done) {
                    return;
                }
                closeCell(server);
                cellIndex++;
                stage = 4;
            }
            case 8 -> {
                if (waiting(server, () -> vanillaResults != null || !anyProfiledCell(),
                        "Minecraft's profiler to hand back its results")) {
                    return;
                }
                if (vanillaResults != null) {
                    appendVanillaSections(vanillaResults);
                    vanillaResults = null;
                }
                proveTheSaveIsClean(server);
                finish(server, true);
            }
            default -> finish(server, true);
        }
    }

    private static boolean anyProfiledCell() {
        return cells().stream().anyMatch(Cell::mcProfiler);
    }

    /**
     * Points the instrument at things whose answer is already known.
     *
     * <p>The empty measurement is the floor: nothing this instrument reports can mean anything
     * below it. The spin is the unit check — its length is decided by {@code currentTimeMillis} and
     * read back by a {@code nanoTime} meter, so two clocks have to agree, and a millisecond
     * mistaken for a microsecond cannot survive it.
     */
    private static void calibrate() {
        for (int i = 0; i < 2000; i++) {
            long begun = System.nanoTime();
            EMPTY.end(begun);
        }
        record(EMPTY.histogram().percentile(0.50) < 10_000L,
                "CALIBRATION an empty measurement reads " + EMPTY.histogram().describeMicros()
                        + " — that is the floor, and nothing below it is resolvable");

        Meter spin = Meters.meter("calibration: a 20 ms spin, timed by the other clock");
        for (int i = 0; i < 5; i++) {
            long begun = System.nanoTime();
            long until = System.currentTimeMillis() + 20L;
            long burn = 0;
            while (System.currentTimeMillis() < until) {
                burn += System.currentTimeMillis();
            }
            spin.end(begun);
            SyntheticPersonas.SINK += burn;
        }
        long median = spin.histogram().percentile(0.50);
        record(median >= 18_000_000L && median <= 80_000_000L,
                "CALIBRATION a 20 ms spin measured by System.currentTimeMillis reads "
                        + Histogram.micros(median) + " on the nanosecond meter");
        REPORT.add("### calibration");
        REPORT.add("  empty measurement   " + EMPTY.histogram().describeMicros());
        REPORT.add("  20 ms spin          " + spin.histogram().describeMicros());
        Meters.reset();
    }

    /** Rebuilds the world to match a cell: this many villagers at these sites, these records. */
    private static void populate(MinecraftServer server, ServerLevel level, Cell target) {
        // MID-MORNING WHILE THEY SETTLE, WHATEVER HOUR THE CELL MEASURES — and the reason is
        // session 13's frozen clock arriving from the other side. A villager spawned at 21:00
        // resolves to REST on its first brain tick, and `Activity.WORK` is registered with
        // `JOB_SITE VALUE_PRESENT` while `GoToPotentialJobSite` refuses to run outside IDLE, WORK
        // and PLAY — so it can never take a job, never acquire a meeting point, and the cell
        // measures a village of unemployed sleepers with perfect confidence. The first run of the
        // `hours` phase did exactly that: 0 of 96 employed at night and 89 of 96 OFF_DUTY at eleven
        // o'clock, in a cell whose whole purpose is the mechanic those villagers were not running.
        // The clock is moved to the cell's own hour in stage 5, once they have settled.
        level.setDayTime(LABOUR_MORNING);
        // Every mob, not just the villagers. Villagers with beds and jobs spawn iron golems, and a
        // teardown that only removed villagers left the golems behind — so each cell inherited
        // every golem the cells before it had produced, and the second launch inherited the first
        // launch's as well. That drift is what made two hundred villagers cost 15.5 ms in one
        // phase and 8.8 ms in the other with no code between them doing anything per tick.
        for (Entity entity : level.getEntities((Entity) null, gridBox(),
                candidate -> !(candidate instanceof Player))) {
            // Items as well as mobs. Discarding a villager drops its inventory, so a teardown that
            // removed only mobs left four hundred item entities ticking for the five minutes it
            // takes them to despawn — and a cell that runs inside those five minutes is measuring
            // eight hundred entities while its report says four hundred.
            if (entity instanceof Villager villager) {
                // Vanilla releases a villager's points of interest in Villager#die and nowhere
                // else, so a discarded villager keeps its workstation and its bed claimed forever.
                // Without this, employment fell from 95/96 to 52/200 across one run and every tick
                // time after that was measuring a village of the unemployed. Nothing threw.
                releasePois(villager);
            }
            entity.discard();
        }
        SUBJECTS.clear();
        for (int siteIndex = 0; siteIndex < target.sites(); siteIndex++) {
            BlockPos site = SITES.get(siteIndex);
            for (int i = 0; i < target.perSite(); i++) {
                BlockPos at = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                        site.offset(2 + (i % 10) * 2, 0, 22 + (i / 10) * 3));
                Villager villager = EntityType.VILLAGER.spawn(level, at, MobSpawnType.COMMAND);
                if (villager == null) {
                    throw new IllegalStateException("could not spawn villager " + i
                            + " at site " + siteIndex);
                }
                villager.setPersistenceRequired();
                SUBJECTS.add(villager);
            }
        }

        if (target.records() > 0) {
            Settlements settlements = SyntheticPersonas.settlements(target.records());
            long begun = System.nanoTime();
            synthetic = SyntheticPersonas.build(target.records(), settlements);
            SWEEPER.rebuild(synthetic);
            SWEEP_REBUILD.end(begun);
            syntheticEverBuilt += synthetic.size();
        } else {
            synthetic = List.of();
            SWEEPER.rebuild(synthetic);
        }
        Namesake.LOGGER.info("[profile] cell populated: {} villager(s), {} fixture record(s) in "
                + "{} bucket(s)", SUBJECTS.size(), synthetic.size(), PersonaSweep.BUCKETS);
    }

    /** The sweep, driven at one bucket a tick exactly as {@code DESIGN.md} §8 specifies. */
    private static void driveSweep(MinecraftServer server) {
        if (cell == null || cell.records() == 0) {
            return;
        }
        long begun = System.nanoTime();
        if (cell.payload()) {
            SWEEPER.advance(server.getTickCount(), SyntheticPersonas::probePayload);
        } else {
            SWEEPER.advance(server.getTickCount(), persona -> {
            });
        }
        SWEEP.end(begun);
    }

    /**
     * Takes whole-tick times out of vanilla's own record of them.
     *
     * <p>Deliberately not a clock of ours. {@code MinecraftServer} already measures the duration of
     * {@code tickServer} and keeps the last hundred, so the number is produced by the thing being
     * measured rather than by an instrument standing next to it, and our sampling costs an array
     * read. The tick <i>before</i> this one is sampled rather than this one, because the two
     * loaders do not fire their end-of-tick hook at the same point and only the previous tick's
     * slot is certainly written.
     */
    private static void sampleTicks(MinecraftServer server) {
        long[] times = server.getTickTimesNanos();
        int upTo = server.getTickCount() - 1;
        for (int t = Math.max(lastSampledTick + 1, upTo - 90); t <= upTo; t++) {
            TICKS.record(times[Math.floorMod(t, times.length)]);
        }
        lastSampledTick = Math.max(lastSampledTick, upTo);
    }

    private static void closeCell(MinecraftServer server) {
        long wallMillis = System.currentTimeMillis() - windowStartMillis;
        if (cell.mcProfiler() && server.isRecordingMetrics()) {
            server.finishRecordingMetrics();
        }

        REPORT.add("");
        REPORT.add("### " + cell.name());
        REPORT.add(String.format(Locale.ROOT,
                "  villagers %d, fixture records %d, payload %s, day time %d (%02d:00, slot %s)",
                cell.villagers(), cell.records(), cell.payload() ? "probe" : "none",
                cell.dayTime(), Math.floorMod(cell.dayTime() / 1000 + 6, 24),
                net.namesake.day.DaySlot.at(cell.dayTime())));
        REPORT.add("  whole server tick   " + TICKS.describeMicros());

        // Two clocks and an accounting check on one line. If the sampling loop skipped or repeated
        // a tick, or the histogram were out by a factor, this is where it shows rather than in a
        // number nobody can check.
        long accounted = TICKS.sum() / 1_000_000L;
        REPORT.add(String.format(Locale.ROOT,
                "  consistency         %d ticks sampled; %d ms of tick work inside a %d ms window",
                TICKS.count(), accounted, wallMillis));
        int expected = cell.mcProfiler() ? (int) TICKS.count() : WINDOW_TICKS;
        record(TICKS.count() >= expected - 2 && TICKS.count() <= expected + 2,
                "SAMPLING '" + cell.name() + "' sampled " + TICKS.count() + " ticks for a "
                        + expected + "-tick window");
        record(accounted <= wallMillis + 100,
                "SAMPLING '" + cell.name() + "' accounts " + accounted + " ms of tick work inside a "
                        + wallMillis + " ms window");

        // The check that decides whether any of the above is evidence at all. Session 01: an entity
        // that is loaded and not ticking reports zero cost, confidently.
        if (!SUBJECTS.isEmpty()) {
            int alive = 0;
            int minAdvance = Integer.MAX_VALUE;
            for (int i = 0; i < SUBJECTS.size(); i++) {
                Villager villager = SUBJECTS.get(i);
                if (villager.isRemoved()) {
                    continue;
                }
                alive++;
                minAdvance = Math.min(minAdvance, villager.tickCount - SUBJECT_TICKS_AT_START.get(i));
            }
            long employed = SUBJECTS.stream()
                    .filter(v -> !v.isRemoved())
                    .filter(v -> v.getVillagerData().getProfession() != VillagerProfession.NONE)
                    .count();
            REPORT.add(String.format(Locale.ROOT,
                    "  villagers           %d of %d alive, %d employed; the least-ticked advanced "
                            + "%d of %d ticks",
                    alive, SUBJECTS.size(), employed,
                    minAdvance == Integer.MAX_VALUE ? 0 : minAdvance, TICKS.count()));
            record(alive >= SUBJECTS.size() * 95 / 100,
                    "TICKING '" + cell.name() + "': " + alive + "/" + SUBJECTS.size()
                            + " villagers survived the window");
            record(minAdvance >= TICKS.count() * 90 / 100,
                    "TICKING '" + cell.name() + "': every villager ran its brain — the least-ticked "
                            + "advanced " + minAdvance + " of " + TICKS.count() + " ticks");
            record(employed >= alive * 70 / 100,
                    "EMPLOYED '" + cell.name() + "': " + employed + "/" + alive
                            + " hold a workstation, so the brain has work to do");
        }

        // What else is in the world, because a cell is only comparable to another cell that had
        // the same things in it. The two measurement phases are separate launches sharing one
        // save, so anything the first left behind — golems, dropped items, animals that wandered
        // in — is in the second's numbers, and a tick time is not evidence of our cost until that
        // has been ruled out rather than assumed.
        // WHAT THE DAY PLAN WAS ACTUALLY DOING, and it is here because a cheap cell and an idle cell
        // report the same number. Session 13 froze the clock ON the LABOUR_I boundary and every
        // villager whose offset was not zero stayed in DAWN for ever, so the day plan measured as
        // free; session 14 adds two hours whose whole content is a mechanic that might not have
        // fired. A measurement of nothing is the most confident wrong number available.
        REPORT.add("  the day plan        " + net.namesake.day.Steering.describe(server.overworld()));
        if (!SUBJECTS.isEmpty()) {
            Map<String, Integer> postures = new LinkedHashMap<>();
            for (Villager villager : SUBJECTS) {
                if (!villager.isRemoved()) {
                    postures.merge(net.namesake.day.Steering.postureOf(villager).name(), 1,
                            Integer::sum);
                }
            }
            REPORT.add("  postures            " + postures);
        }
        REPORT.add("  also in the grid    " + entityCensus(server.overworld()));
        REPORT.add("  registry            "
                + NpcRegistry.get(server).size() + " persona(s), "
                + NpcRegistry.get(server).settlements().size() + " settlement(s)");

        REPORT.addAll(Meters.report());
        if (cell.records() > 0) {
            REPORT.add("  sweep sink          " + SyntheticPersonas.SINK
                    + "  (logged so the payload cannot be optimised away)");
        }
        Namesake.LOGGER.info("[profile] cell '{}' done: tick {}", cell.name(), TICKS.describeMicros());
    }

    /**
     * Minecraft's own profiler, read for the sections it pushes and we cannot.
     *
     * <p><b>This is the answer to whether the built-in profiler is enough, and it is "for vanilla's
     * own sections, yes; for ours, no".</b> {@code Mob#serverAiStep} pushes {@code sensing},
     * {@code targetSelector}, {@code goalSelector}, {@code navigation} and {@code mob tick}, and
     * {@code Villager#customServerAiStep} pushes {@code villagerBrain} inside the last of those —
     * exactly the sections {@code WORKPLAN.md} names, and without a mixin there is no other way to
     * reach them. But {@code ProfileResults} only ever hands out <i>percentages</i>: the absolute
     * durations live in a private map on {@code FilledProfileResults}, the printed report is two
     * decimal places of a percentage, and our whole budget is around a hundredth of one. It also
     * keeps a sum, a count and a max per section and never a distribution.
     */
    private static void appendVanillaSections(ProfileResults results) {
        long nanos = results.getNanoDuration();
        int ticks = Math.max(1, results.getTickDuration());
        REPORT.add("");
        REPORT.add("### Minecraft's own profiler: " + ticks + " ticks over "
                + (nanos / 1_000_000L) + " ms");
        Map<String, ResultField> wanted = new LinkedHashMap<>();
        walk(results, "root", 0, nanos, ticks, wanted);

        REPORT.add("  -- the sections WORKPLAN.md names --");
        for (String name : List.of("sensing", "targetSelector", "goalSelector", "navigation",
                "mob tick", "villagerBrain", "controls")) {
            ResultField field = wanted.get(name);
            if (field == null) {
                REPORT.add(String.format(Locale.ROOT, "    %-16s not reported", name));
                continue;
            }
            REPORT.add(String.format(Locale.ROOT, "    %-16s %6.2f%% of the tick  %s/tick  (n=%d)",
                    name, field.globalPercentage,
                    Histogram.micros(field.globalPercentage / 100.0 * nanos / ticks), field.count));
        }
        if (results.saveResults(VANILLA_TREE_FILE)) {
            REPORT.add("  full section tree written to " + VANILLA_TREE_FILE.toAbsolutePath());
        }
    }

    /**
     * Walks the profiler tree, printing anything that is a measurable share of a tick.
     *
     * <p>The paths are not spelled out because one of them cannot be: the level's own section is
     * named {@code serverlevel + " " + dimension}, so it carries the world's name in it. Walking is
     * the only version of this that does not break when the world is renamed.
     */
    private static void walk(ProfileResults results, String path, int depth, long nanos, int ticks,
                             Map<String, ResultField> wanted) {
        // Twelve, and it took two runs to find out why. A villager's own sections are deeper than
        // they look: root.tick.levels.<level>.tick.entities.tick.minecraft:villager.ai.newAi.
        // mob tick.villagerBrain is eleven, because LivingEntity#tick pushes "ai" and "newAi"
        // around serverAiStep before Mob pushes anything. A tree cut off one level short prints
        // "not reported" for the exact section this session is named after.
        if (depth > 12) {
            return;
        }
        List<ResultField> fields = results.getTimes(path);
        for (int i = 1; i < fields.size(); i++) {
            ResultField field = fields.get(i);
            if (field.globalPercentage < 0.05) {
                continue;
            }
            REPORT.add(String.format(Locale.ROOT, "  %s%-40s %6.2f%%  %s/tick  (n=%d)",
                    "  ".repeat(depth), field.name, field.globalPercentage,
                    Histogram.micros(field.globalPercentage / 100.0 * nanos / ticks), field.count));
            wanted.putIfAbsent(field.name, field);
            if (!"unspecified".equals(field.name)) {
                walk(results, path + SEP + field.name, depth + 1, nanos, ticks, wanted);
            }
        }
    }

    // --- the population phase ---------------------------------------------------------------------

    /**
     * What a real generated world actually produces.
     *
     * <p>{@code WORKPLAN.md} carries 400 as "the working figure but an assumption", and session
     * 01's minting-on-sight ruling is what makes it worth checking: the record count tracks world
     * population rather than player experience. So this finds real villages with vanilla's own
     * structure locator, walks to each, and counts who is in it.
     */
    private static void runPopulation(MinecraftServer server) {
        ServerLevel level = server.overworld();
        switch (stage) {
            case 0 -> {
                ServerPlayer player = player(server);
                if (player == null) {
                    return;
                }
                configure(server, level, player);
                await(200);
            }
            case 1 -> {
                if (waiting(server, () -> false, "the world to finish loading")) {
                    return;
                }
                BlockPos spawn = level.getSharedSpawnPos();
                // Searched from a ring of origins rather than one, because the locator run twice
                // from the same point returns the same village twice — and one origin per tick,
                // because a forty-chunk structure search blocks the server thread for seconds and
                // eight of them in one tick is a stall the game reports as a crash-shaped hang.
                int[] from = SEARCH_ORIGINS[searchOrigin];
                BlockPos found = level.findNearestMapStructure(StructureTags.VILLAGE,
                        spawn.offset(from[0], 0, from[1]), 40, false);
                if (found != null && VILLAGES.stream().noneMatch(v -> v.closerThan(found, 192))) {
                    VILLAGES.add(found);
                    Namesake.LOGGER.info("[profile] village {} at {}",
                            VILLAGES.size() - 1, found.toShortString());
                }
                if (++searchOrigin < SEARCH_ORIGINS.length) {
                    return;
                }
                record(!VILLAGES.isEmpty(), "POPULATION vanilla's locator found " + VILLAGES.size()
                        + " distinct village(s)");
                if (VILLAGES.isEmpty()) {
                    finish(server, false);
                    return;
                }
                villageIndex = 0;
                next();
            }
            case 2 -> {
                if (villageIndex >= VILLAGES.size()) {
                    stage = 6;
                    deadline = tick + 100;
                    lastReport = tick;
                    return;
                }
                BlockPos village = VILLAGES.get(villageIndex);
                teleport(player(server), level, village.getX(), 200, village.getZ());
                await(6000);
            }
            case 3 -> {
                BlockPos village = VILLAGES.get(villageIndex);
                ChunkPos chunk = new ChunkPos(village);
                if (waiting(server, () -> level.getChunkSource().hasChunk(chunk.x, chunk.z),
                        "village " + villageIndex + "'s chunks")) {
                    return;
                }
                BlockPos ground = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, village);
                teleport(player(server), level, ground.getX(), ground.getY() + 3, ground.getZ());
                await(3000);
            }
            case 4 -> {
                // Two conditions, not one: the chunks are back, and the survey the arriving
                // villagers asked for has finished. Session 03's fourth application of the rule.
                NpcRegistry registry = NpcRegistry.get(server);
                BlockPos village = VILLAGES.get(villageIndex);
                if (waiting(server, () -> registry.settlements()
                                .containing(level.dimension().location(), village).isPresent()
                                && !level.getEntitiesOfClass(Villager.class,
                                        new AABB(village).inflate(96)).isEmpty(),
                        "village " + villageIndex + " to load and be surveyed")) {
                    return;
                }
                // Counted after a dwell, not the moment the settlement registers. A village does
                // not arrive all at once: its outlying chunks are still loading and the villagers
                // in them have not been minted yet, so leaving straight away counts the first two
                // residents of a village of twelve and calls it a population.
                dwellUntil = tick + VILLAGE_DWELL_TICKS;
                next();
            }
            case 5 -> {
                if (waiting(server, () -> tick >= dwellUntil,
                        "village " + villageIndex + " to finish arriving")) {
                    return;
                }
                NpcRegistry registry = NpcRegistry.get(server);
                BlockPos village = VILLAGES.get(villageIndex);
                int loaded = level.getEntitiesOfClass(Villager.class,
                        new AABB(village).inflate(96)).size();
                int residents = registry.settlements()
                        .containing(level.dimension().location(), village)
                        .map(settlement -> (int) registry.all().stream()
                                .filter(p -> p.settlementId() == settlement.id()).count())
                        .orElse(-1);
                VILLAGE_ROWS.add(String.format(Locale.ROOT,
                        "  village %d at %-22s  loaded villagers %3d  settlement residents %3d  "
                                + "registry total %4d",
                        villageIndex, village.toShortString(), loaded, residents, registry.size()));
                Namesake.LOGGER.info("[profile] {}", VILLAGE_ROWS.get(VILLAGE_ROWS.size() - 1));
                villageIndex++;
                stage = 2;
            }
            case 6 -> {
                NpcRegistry registry = NpcRegistry.get(server);
                REPORT.add("### a real generated world (seed " + WORLD_SEED + ")");
                REPORT.addAll(VILLAGE_ROWS);
                REPORT.add("  after visiting " + VILLAGES.size() + " village(s): "
                        + registry.size() + " persona(s), " + registry.settlements().size()
                        + " settlement(s) in the registry");
                REPORT.add("");
                REPORT.add("### session 03's two costs, discovering those villages");
                REPORT.addAll(Meters.report());

                // Walk away and come back. This is the only way to make session 03's claim
                // measurable: onPersonaLoaded runs again for every villager, and by now every one
                // of them is generated and settled — which is the branch the claim is about.
                Meters.reset();
                BlockPos spawn = level.getSharedSpawnPos();
                teleport(player(server), level, spawn.getX(), 250, spawn.getZ());
                await(900);
            }
            case 7 -> {
                if (waiting(server, () -> false, "the village chunks to unload")) {
                    return;
                }
                BlockPos village = VILLAGES.get(0);
                teleport(player(server), level, village.getX(), 200, village.getZ());
                await(4000);
            }
            case 8 -> {
                BlockPos village = VILLAGES.get(0);
                if (waiting(server, () -> !level.getEntitiesOfClass(Villager.class,
                                new AABB(village).inflate(96)).isEmpty(),
                        "the villagers to come back")) {
                    return;
                }
                REPORT.add("");
                REPORT.add("### onPersonaLoaded on a chunk reload, everyone already settled");
                REPORT.addAll(Meters.report());
                long settled = Meters.counter("PersonaService.onEntityLoad calls");
                record(settled > 0, "RELOAD " + settled + " villager(s) re-entered the world, so "
                        + "the branch counts below are of something rather than of nothing");
                proveTheSaveIsClean(server);
                finish(server, true);
            }
            default -> finish(server, true);
        }
    }

    // --- the cleanliness proof --------------------------------------------------------------------

    /**
     * Proves the world holds no fixture records — by querying it, not by having tidied up.
     *
     * <p>Two queries, because they fail differently. The registry in memory is what the guard in
     * {@code NpcRegistry.put} protects; the file on disk is what a player would open tomorrow, and
     * it is read back out of the {@code .dat} rather than believed.
     */
    private static void proveTheSaveIsClean(MinecraftServer server) {
        NpcRegistry registry = NpcRegistry.get(server);
        long inMemory = registry.all().stream()
                .filter(persona -> Persona.isReservedForProfiling(persona.id()))
                .count();
        record(inMemory == 0, "CLEAN the registry holds " + registry.size() + " persona(s) and "
                + inMemory + " of them are fixtures (" + syntheticEverBuilt
                + " fixture(s) were built during this run)");

        server.saveEverything(true, true, true);
        Path file = DimensionType.getStorageFolder(Level.OVERWORLD,
                        server.getWorldPath(LevelResource.ROOT))
                .resolve("data").resolve(NpcRegistry.FILE_ID + ".dat");
        if (!Files.isRegularFile(file)) {
            // A registry that was never made dirty is never written, which is the normal shape of
            // the vanilla phase: our hooks are inert, nothing was minted, and there is no file. A
            // missing file is only a pass while the registry is also empty — otherwise records
            // exist and something failed to save them.
            record(registry.size() == 0, "CLEAN no registry file exists at " + file.getFileName()
                    + " and the registry holds " + registry.size() + " persona(s), so nothing "
                    + "reached disk at all");
            return;
        }
        try {
            CompoundTag root = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
            ListTag list = root.getCompound("data").getList("npcs", Tag.TAG_COMPOUND);
            int fixtures = 0;
            for (int i = 0; i < list.size(); i++) {
                int[] id = list.getCompound(i).getIntArray("id");
                if (id.length == 4 && Persona.isReservedForProfiling(UUIDUtil.uuidFromIntArray(id))) {
                    fixtures++;
                }
            }
            record(fixtures == 0, "CLEAN " + file.getFileName() + " on disk holds " + list.size()
                    + " persona record(s) and " + fixtures + " of them are fixtures");
        } catch (IOException e) {
            record(false, "CLEAN could not read " + file + ": " + e);
        }
    }

    // --- world building ---------------------------------------------------------------------------

    /**
     * Clears standing room over one site and gives it a bell, workstations and beds.
     *
     * <p><b>Not a floating platform.</b> Villagers walk on the terrain the seed generated, because
     * pathfinding over flat ground is materially cheaper than over real ground and a baseline
     * measured on a table top is a baseline that flatters us. All this does is clear three blocks
     * of headroom and make sure the surface is something a villager can stand on rather than drown
     * in — session 03's suffocated-villagers lesson, in the other direction.
     */
    private static void buildSite(ServerLevel level, BlockPos site) {
        BlockState floor = Blocks.STONE.defaultBlockState();
        BlockState air = Blocks.AIR.defaultBlockState();
        for (int x = 0; x < SITE_WIDTH; x++) {
            for (int z = 0; z < SITE_DEPTH; z++) {
                BlockPos column = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                        site.offset(x, 0, z));
                BlockPos below = column.below();
                if (!level.getBlockState(below).isSolidRender(level, below)) {
                    level.setBlock(below, floor, Block.UPDATE_CLIENTS);
                }
                for (int y = 0; y < 3; y++) {
                    level.setBlock(column.above(y), air, Block.UPDATE_CLIENTS);
                }
            }
        }

        // setBlockAndUpdate for the points of interest: session 03 proved that path reaches
        // ServerLevel#onBlockStateChange and registers the POI, which is the entire reason the
        // villagers standing here have anything to do.
        level.setBlockAndUpdate(surfaceAt(level, site, 12, 16), Blocks.BELL.defaultBlockState());
        for (int i = 0; i < PER_SITE_MAX; i++) {
            level.setBlockAndUpdate(surfaceAt(level, site, 2 + (i % 5) * 4, 2 + (i / 5) * 3),
                    WORKSTATIONS[i % WORKSTATIONS.length].defaultBlockState());
        }
        for (int i = 0; i < PER_SITE_MAX; i++) {
            BlockPos foot = surfaceAt(level, site, 1 + (i % 9) * 2, 22 + (i / 9) * 3);
            level.setBlockAndUpdate(foot, Blocks.RED_BED.defaultBlockState()
                    .setValue(BedBlock.FACING, Direction.SOUTH)
                    .setValue(BedBlock.PART, BedPart.FOOT));
            level.setBlockAndUpdate(foot.relative(Direction.SOUTH), Blocks.RED_BED.defaultBlockState()
                    .setValue(BedBlock.FACING, Direction.SOUTH)
                    .setValue(BedBlock.PART, BedPart.HEAD));
        }
    }

    /** Every entity type in the measurement grid and how many of each, commonest first. */
    private static String entityCensus(ServerLevel level) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Entity entity : level.getEntities((Entity) null,
                gridBox(), e -> true)) {
            counts.merge(EntityType.getKey(entity.getType()).getPath(), 1, Integer::sum);
        }
        return counts.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                .limit(10)
                .map(entry -> entry.getValue() + " " + entry.getKey())
                .collect(java.util.stream.Collectors.joining(", "));
    }

    /** Everything {@code Villager#releaseAllPois} releases, which is private and only runs on death. */
    private static void releasePois(Villager villager) {
        villager.releasePoi(MemoryModuleType.HOME);
        villager.releasePoi(MemoryModuleType.JOB_SITE);
        villager.releasePoi(MemoryModuleType.POTENTIAL_JOB_SITE);
        villager.releasePoi(MemoryModuleType.MEETING_POINT);
    }

    private static BlockPos surfaceAt(ServerLevel level, BlockPos site, int x, int z) {
        return level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, site.offset(x, 0, z));
    }

    /** One of each, so the villagers take a spread of professions rather than all the same one. */
    private static final Block[] WORKSTATIONS = {
            Blocks.COMPOSTER, Blocks.SMITHING_TABLE, Blocks.CARTOGRAPHY_TABLE, Blocks.LOOM,
            Blocks.BARREL, Blocks.FLETCHING_TABLE, Blocks.BLAST_FURNACE, Blocks.SMOKER,
            Blocks.STONECUTTER, Blocks.GRINDSTONE, Blocks.LECTERN, Blocks.CAULDRON,
            Blocks.BREWING_STAND};

    // --- helpers ----------------------------------------------------------------------------------

    private static boolean chunksReady(ServerLevel level) {
        ChunkPos centre = new ChunkPos(gridCentre);
        int radius = 8;
        for (int x = -radius; x <= radius; x++) {
            for (int z = -radius; z <= radius; z++) {
                if (!level.getChunkSource().hasChunk(centre.x + x, centre.z + z)) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * True when every one of the sixteen site centres is dry land above sea level.
     *
     * <p>Sampled at the site centres rather than at the grid centre, because a coastline puts one
     * on the beach and the other under twelve blocks of water — and the grid centre being fine is
     * exactly the reassuring reading that hides it.
     */
    private static boolean isLand(ServerLevel level) {
        for (int gz = 0; gz < SITE_GRID; gz++) {
            for (int gx = 0; gx < SITE_GRID; gx++) {
                BlockPos probe = gridCentre.offset(
                        (2 * gx - (SITE_GRID - 1)) * SITE_SPACING / 2, 0,
                        (2 * gz - (SITE_GRID - 1)) * SITE_SPACING / 2);
                BlockPos surface = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, probe);
                if (surface.getY() <= level.getSeaLevel()
                        || !level.getBlockState(surface.below()).getFluidState().isEmpty()) {
                    return false;
                }
            }
        }
        return true;
    }

    private static AABB gridBox() {
        return new AABB(gridCentre).inflate(SITE_GRID * SITE_SPACING + 160);
    }

    /**
     * True once the cell's villagers are all alive and mostly employed.
     *
     * <p>Employment is the interesting half. A villager with no workstation runs a different and
     * cheaper brain than one with a job, so measuring before they have settled into work measures
     * a village that does not exist.
     */
    private static boolean settled(MinecraftServer server) {
        if (SUBJECTS.isEmpty()) {
            return true;
        }
        if (SUBJECTS.stream().anyMatch(Villager::isRemoved)) {
            return false;
        }
        long employed = SUBJECTS.stream()
                .filter(v -> v.getVillagerData().getProfession() != VillagerProfession.NONE)
                .count();
        // AND A MEETING POINT, added at session 14. Two of the three errands walk to the bell, and
        // `AcquirePoi` writes that memory on its own schedule — so a cell that began measuring
        // before it arrived would report an errand mechanic that never fired, which is the most
        // confident wrong number this instrument can produce. Session 04's own rule: a measurement
        // is not evidence until the thing being measured has been shown to be running.
        long haveTheBell = SUBJECTS.stream()
                .filter(v -> v.getBrain().hasMemoryValue(MemoryModuleType.MEETING_POINT))
                .count();
        return employed >= SUBJECTS.size() * 80 / 100
                && haveTheBell >= SUBJECTS.size() * 80 / 100;
    }

    private static void configure(MinecraftServer server, ServerLevel level, ServerPlayer player) {
        server.setDifficulty(Difficulty.NORMAL, true);
        GameRules rules = server.getGameRules();
        rules.getRule(GameRules.RULE_DOMOBSPAWNING).set(false, server);
        rules.getRule(GameRules.RULE_DAYLIGHT).set(false, server);
        rules.getRule(GameRules.RULE_WEATHER_CYCLE).set(false, server);
        rules.getRule(GameRules.RULE_MOBGRIEFING).set(false, server);
        rules.getRule(GameRules.RULE_DO_TRADER_SPAWNING).set(false, server);
        rules.getRule(GameRules.RULE_DOFIRETICK).set(false, server);
        player.setGameMode(GameType.CREATIVE);
        player.getAbilities().flying = true;
        player.onUpdateAbilities();
        player.setInvulnerable(true);
        // Mid-morning and frozen: LABOUR_I in DESIGN.md §7's table, which is when a villager's
        // brain has the most to do. Measuring at night would measure a village asleep.
        //
        // THREE THOUSAND RATHER THAN TWO, changed at session 13 and the reason is the thing being
        // measured. LABOUR_I begins at 2000, and a villager reaches a boundary DayPlan.offsetOf
        // ticks after it — so with the clock frozen *on* the boundary, every villager whose offset
        // is not zero stays in DAWN for ever, no standoff is ever issued, and the day plan reports
        // a cost of nothing. Three thousand is a thousand ticks past the widest spread, so the
        // whole population has crossed and the steady state being measured is the real one: the
        // industrious at their workstations and the lazy parked and being vetoed. Both phases share
        // this method, so the vanilla baseline moves with it and the two still subtract.
        level.setDayTime(LABOUR_MORNING);
        // FROZEN, AND NOW PER CELL. Session 13 set this once and every cell measured mid-morning;
        // session 14 moved it onto Cell, because a per-tick budget is a claim about the most
        // expensive hour and the day plan does different work in different ones. This is only the
        // value the run opens on — see populate().
        Namesake.LOGGER.info("[profile] configured: normal difficulty, no spawning, daylight cycle "
                + "off, one creative player. Each cell freezes the clock at its own hour.");
    }

    private static ServerPlayer player(MinecraftServer server) {
        List<ServerPlayer> players = server.getPlayerList().getPlayers();
        return players.isEmpty() ? null : players.get(0);
    }

    private static void teleport(ServerPlayer player, ServerLevel level, double x, double y, double z) {
        player.teleportTo(level, x, y, z, player.getYRot(), player.getXRot());
    }

    private static void next() {
        stage++;
        deadline = tick + 200000;
        lastReport = tick;
    }

    private static void await(int maxTicks) {
        stage++;
        deadline = tick + maxTicks;
        lastReport = tick;
    }

    /**
     * True while the caller should keep waiting; false once the condition holds or the deadline
     * passes, so the caller always runs its assertion and a timeout fails loudly rather than
     * silently.
     *
     * <p><b>Never sprints.</b> Every wait here is either on chunk IO — where sprinting outruns the
     * chunk loader, which is how session 01 read a slow machine as a lost persona — or inside a
     * measurement, where sprinting would measure a game running as fast as it can rather than a
     * game running at twenty ticks a second.
     */
    private static boolean waiting(MinecraftServer server, BooleanSupplier condition, String what) {
        if (condition.getAsBoolean() || tick >= deadline) {
            return false;
        }
        if (tick - lastReport >= 400) {
            lastReport = tick;
            Namesake.LOGGER.info("[profile] waiting for {} ({} ticks left)", what, deadline - tick);
        }
        return true;
    }

    private static void record(boolean pass, String what) {
        String line = (pass ? "PASS  " : "FAIL  ") + what;
        RESULTS.add(line);
        if (pass) {
            Namesake.LOGGER.info("[profile] {}", line);
        } else {
            Namesake.LOGGER.error("[profile] {}", line);
        }
    }

    private static void finish(MinecraftServer server, boolean reachedTheEnd) {
        finished = true;
        boolean allPassed = reachedTheEnd && RESULTS.stream().allMatch(line -> line.startsWith("PASS"));

        List<String> report = new ArrayList<>();
        report.add("namesake profiler report — phase " + PHASE
                + " (mod inert: " + Profiling.MOD_INERT + ")");
        report.add("");
        report.addAll(RESULTS);
        report.addAll(REPORT);

        StringBuilder summary = new StringBuilder("\n==== namesake profiler: phase ")
                .append(PHASE).append(" ====\n");
        report.forEach(line -> summary.append(line).append('\n'));
        summary.append("==== ").append(allPassed ? "ALL CHECKS PASSED" : "CHECKS FAILED").append(" ====");
        Namesake.LOGGER.info(summary.toString());

        try {
            Files.write(REPORT_FILE, report);
        } catch (IOException e) {
            Namesake.LOGGER.error("[profile] could not write {}", REPORT_FILE.toAbsolutePath(), e);
        }
        writeResult(allPassed ? "PASS" : "FAIL", String.join("\n", RESULTS));
        Namesake.LOGGER.info("[profile] PROFILE COMPLETE phase={} result={} report={}",
                PHASE, allPassed ? "PASS" : "FAIL", REPORT_FILE.toAbsolutePath());
        startShutdownWatchdog();
        server.halt(false);
    }

    /** Bounds a shutdown that has wedged on CI before. See {@code AttachBetHarness}. */
    private static void startShutdownWatchdog() {
        Thread watchdog = new Thread(() -> {
            try {
                Thread.sleep(SHUTDOWN_GRACE_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            Namesake.LOGGER.warn("[profile] the game did not shut down within {}s. Exiting hard; "
                    + "the report was written before this point.", SHUTDOWN_GRACE_MILLIS / 1000);
            Runtime.getRuntime().halt(0);
        }, "namesake-profiler-shutdown-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
    }

    private static void writeResult(String verdict, String detail) {
        try {
            Files.write(RESULT_FILE, List.of("RESULT " + verdict + " phase=" + PHASE, detail));
        } catch (IOException e) {
            Namesake.LOGGER.error("[profile] could not write {}", RESULT_FILE.toAbsolutePath(), e);
        }
    }
}
