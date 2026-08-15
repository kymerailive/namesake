package net.namesake.day;

import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.schedule.Activity;
import net.minecraft.world.level.levelgen.Heightmap;
import net.namesake.Namesake;
import net.namesake.npc.Persona;
import net.namesake.npc.PersonaService;
import net.namesake.profile.Meter;
import net.namesake.profile.Meters;
import net.namesake.profile.Profiling;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * <b>The industry standoff, the transition governor and the path gate.</b> Session 13, and this is
 * the whole of what the session steers.
 *
 * <h2>What session 13 actually ships, stated plainly</h2>
 *
 * <p>{@code DESIGN.md} §7's table gives this session five slots and three of them have steering
 * <b>none</b>. A slot with no steering is not code, so the honest inventory is shorter than the
 * table looks:
 *
 * <table border="1">
 *   <caption>§7's five free slots, and what each one costs</caption>
 *   <tr><th>slot</th><th>§7's steering</th><th>what ships</th></tr>
 *   <tr><td>{@link DaySlot#DAWN}</td><td>walk+look (uncontested)</td>
 *       <td><b>nothing.</b> Vanilla's {@code IDLE} package already strolls and looks —
 *           {@code VillageBoundRandomStroll}, {@code SetWalkTargetFromLookTarget},
 *           {@code SetEntityLookTarget} — and ours would be a second answer to a question that has
 *           one.</td></tr>
 *   <tr><td>{@link DaySlot#LABOUR_I}</td><td>none</td><td><b>the standoff.</b></td></tr>
 *   <tr><td>{@link DaySlot#LABOUR_II}</td><td>none</td><td><b>the standoff.</b></td></tr>
 *   <tr><td>{@link DaySlot#COMMONS}</td><td>walk target r≤6 of bell</td>
 *       <td><b>nothing.</b> That is literally
 *           {@code SetWalkTargetFromBlockMemory(MEETING_POINT, speed, closeEnough = 6, …)} at
 *           {@code VillagerGoalPackages.java:139}. Vanilla already does it, at the ruled radius.</td></tr>
 *   <tr><td>{@link DaySlot#NIGHT}</td><td>none for sleepers</td><td><b>nothing.</b></td></tr>
 * </table>
 *
 * <p>So: <b>session 13 is the standoff and the transition governor.</b> The slot table exists
 * because the standoff is slot-gated and because session 14 hangs {@code ERRAND} off it; the
 * governor and the gate exist because the standoff issues walk targets at a boundary and four
 * hundred of them in one tick is the failure §7 names. Four of the five slots ship no code, and
 * that is a result rather than an omission — the session's job in those four is to prove we did not
 * break what vanilla already does there, which is what the harness legs assert.
 *
 * <h2>Where this runs, and it is not in the sweep</h2>
 *
 * <p>{@code DESIGN.md} §8 rules that the persona record is the authority and a rotating sweep visits
 * four hundred records in twenty buckets. <b>The day plan is not in it.</b> Three reasons, and the
 * first is sufficient:
 *
 * <ol>
 *   <li><b>Steering's only output is a brain memory on a loaded entity.</b> Three hundred of the
 *       four hundred records have no entity, so a sweep visit would have nothing to do.</li>
 *   <li><b>The sweep visits a record about once a second.</b> The veto below has to beat a
 *       behaviour that writes on one tick and is acted on the next; once a second is twenty times
 *       too slow.</li>
 *   <li><b>The plan persists nothing</b>, so there is no record state for a sweep to advance. See
 *       {@link DayPlan}.</li>
 * </ol>
 *
 * <p>So the sweep's 125–225 ns per record visit — everything sessions 05 to 14 put in the payload —
 * is <b>untouched by this session</b>, and the cost here is a new line item against the ~5.95 µs/tick
 * total, sized by <i>loaded entities</i> rather than by records. Measured max in a real generated
 * world: eleven. Designed against: sixty to a hundred.
 *
 * <h2>The standoff is a veto, not a walk target</h2>
 *
 * <p>See {@link DayPlan} for the three vanilla behaviours and the finding that there is no position
 * they all leave alone. The consequence for this class is the whole of its design:
 *
 * <ul>
 *   <li><b>One walk target is issued</b>, at the boundary, to send a lazy villager out to its
 *       standoff point. It <i>replaces</i> a walk target vanilla was about to issue to the same
 *       villager toward the same workstation, so <b>this session adds no path requests to the
 *       server</b> — it changes where an existing one goes. That is the budget argument, and it is
 *       why nothing here calls {@code createPath}.</li>
 *   <li><b>Everything after that is {@link #declineTheJobSite}</b>: erasing a walk target that
 *       points at the workstation, in the window between the behaviour that wrote it and
 *       {@code MoveToTargetSink} that would path on it. {@code Brain.availableBehaviorsByPriority}
 *       is a {@code TreeMap} and the sink is priority 1 while the writers are 2 and 5, so a write
 *       this tick is not acted on until the next one — and our end-of-server-tick hook sits between
 *       them. An erase costs a hashmap remove and never a path.</li>
 * </ul>
 *
 * <p><b>A veto is not a tug-of-war.</b> §7 rules the band out of a tug-of-war and it was right about
 * the outcome and wrong about the mechanism: nothing here writes a competing destination, nothing
 * paths twice, and the villager does not oscillate. It stands still, which is the observable.
 *
 * <h2>What the veto refuses to touch</h2>
 *
 * <p>Erasing {@code WALK_TARGET} blindly would stop a villager fleeing, going to bed, following a
 * trade or picking up bread — and a villager who cannot move is the bug this session inherited,
 * manufactured on purpose. So the veto is narrow in five ways at once, and
 * {@code SteeringTest.theVetoIsNarrow} holds every one of them:
 *
 * <ol>
 *   <li>only while the brain has {@link Activity#WORK} active — so {@code PANIC}, {@code RAID},
 *       {@code HIDE} and {@code REST} are untouched, because vanilla switches activity for all four;
 *   <li>only during a {@link DaySlot#isLabour} slot;
 *   <li>only for a villager this plan made lazy, who has actually arrived at their standoff point;
 *   <li>only when the walk target <b>is the job site</b> — within a block of it. A random stroll, a
 *       wanted item, a trading player and a bed are all left alone;
 *   <li>never while somebody is trading with them, and never while they have been hurt or can see
 *       something hostile.
 * </ol>
 */
public final class Steering {

    /**
     * <b>The transition governor: at most this many villagers may be steered in one tick.</b>
     * {@code WORKPLAN.md}'s load-bearing wall, and the number §7 sizes the spread against —
     * {@code 400 / DayPlan.SPREAD ≈ 6}, and eight is the next power of two above it.
     *
     * <p>It bounds the <b>burst</b>. {@link DayPlan#PATH_GATE_PERIOD} bounds the steady state. They
     * are different failures: a boundary releases everybody at once, and a busy village trickles.
     */
    public static final int TRANSITIONS_PER_TICK = 8;

    /**
     * How close to its standoff point a villager has to be before it counts as parked, in Manhattan
     * blocks. One, because that is {@code MoveToTargetSink}'s own arrival test for a
     * {@code WalkTarget} with {@code closeEnough = 1}, and using the engine's number means the two
     * cannot disagree about whether somebody has arrived.
     */
    private static final int PARKED_WITHIN = 1;

    /**
     * How close to the job site a walk target has to be before the veto will erase it.
     *
     * <p>{@code StrollToPoi} writes the job site position exactly, so zero would do. One block of
     * slack catches {@code SetWalkTargetFromBlockMemory}'s near case as well and still refuses every
     * destination that is genuinely somewhere else — which is the clause that keeps a fleeing,
     * sleeping or trading villager moving.
     */
    private static final int VETO_WITHIN = 1;

    /** What the plan believes a villager is doing. Reported by the debug command and the harness. */
    public enum Posture {
        /** Diligent, and left entirely to vanilla. The common case. */
        WORKING,
        /** Lazy, and standing at a point the plan chose. */
        STANDOFF,
        /** Lazy, and on the way to it. */
        WALKING_OUT,
        /**
         * Has a workstation and cannot reach where it was sent, for longer than
         * {@link DayPlan#STUCK_AFTER_TICKS}.
         *
         * <p><b>This is the session's answer to the villagers stuck in their own houses.</b> A stuck
         * villager is silent exactly like a lazy one, so without a name for it the exit criterion's
         * "you can hear which is which" is satisfied by a false positive. See
         * {@link #postureOf}.
         */
        STUCK,
        /** No workstation, so nothing to stand off from. A nitwit, or somebody unemployed. */
        NO_JOB,
        /** Not a labour slot, or vanilla is not running {@code WORK}. The plan says nothing. */
        OFF_DUTY,
        /**
         * <b>Hiding, with no way out. The inherited bug, named.</b>
         *
         * <p>{@code BellBlockEntity.updateEntities} writes {@code HEARD_BELL_TIME} into every living
         * entity within 32 blocks of a rung bell, <b>with no expiry</b>. {@code ReactToBell} sits in
         * the CORE package at priority 0, so it runs in every activity, and re-asserts
         * {@link Activity#HIDE} on every tick the memory is present. The HIDE package is the only
         * one of vanilla's seven with <b>no {@code UpdateActivityFromSchedule}</b> — WORK, MEET,
         * IDLE, PLAY and REST all end with {@code Pair.of(99, …)} and HIDE does not — so the
         * schedule cannot pull a villager out of it.
         *
         * <p>Its one exit is {@code SetHiddenState}, which needs {@code HIDING_PLACE} <i>and</i>
         * {@code HEARD_BELL_TIME} present before it will time out after three hundred ticks and
         * erase them both. {@code HIDING_PLACE} has exactly one writer — {@code LocateHidingPlace} —
         * which requires {@code WALK_TARGET} to be absent and then needs to find a HOME point of
         * interest within 32 blocks, or a HOME memory. <b>A villager with neither never gets a
         * hiding place, so the state machine has no exit at all</b>, and one who is walked to their
         * house first is a villager stuck in their house.
         *
         * <p>Neither memory declares a codec, so neither is persisted — which is why the bug heals
         * on a chunk unload and reads as intermittent rather than permanent. That matches the one
         * thing known about it from the close-of-10 playtest: <i>I have seen it; I do not know how
         * often.</i>
         *
         * <p><b>Reported rather than repaired.</b> Every repair available is a change to vanilla
         * state we do not own — erasing somebody's bell memory, handing them a hiding place they did
         * not choose — on a diagnosis with a verified mechanism and no reproduction. What session 13
         * ships is the name, so the next sighting comes back as evidence instead of as a
         * description. What it also ships is not causing it: see {@link #releaseStandoff} and the
         * guard in {@code enterSlot}.
         */
        BELL_LOCKED
    }

    /**
     * Per-villager runtime state. Transient by construction: none of this reaches a save file.
     *
     * <p><b>The persona is cached and refreshed at every slot boundary rather than read per tick.</b>
     * {@link DayPlan#slotFor} needs two trait axes on every villager on every tick, and a registry
     * lookup per villager per tick is a hash of a UUID sixty times a second for a value that changes
     * approximately never. Refreshing it inside {@link #enterSlot} — which is behind the governor,
     * so at most eight a tick — costs nothing and means a trait that <i>does</i> change takes effect
     * at the next boundary rather than never. Without the refresh, {@code /namesake debug settrait}
     * would silently do nothing to the day plan, and so would every harness leg built on it.
     */
    private static final class Tracked {
        private final Villager villager;
        private Persona persona;
        /** The slot this villager was last steered for, so a boundary is a change rather than a clock. */
        private DaySlot servedSlot;
        /** Where the plan sent them, or null while they are working. */
        private BlockPos standoff;
        /** True once they have arrived, so the veto has something to protect. */
        private boolean parked;
        /** Set when a standoff turned out to be unreachable, so it is not offered again today. */
        private boolean gaveUp;

        Tracked(Villager villager, Persona persona) {
            this.villager = villager;
            this.persona = persona;
        }
    }

    private static final class LevelState {
        private final Map<Integer, Tracked> roster = new HashMap<>();
        private final Deque<Tracked> waiting = new ArrayDeque<>();
        private int vetoes;
        private int steered;
        private int stuck;
    }

    private static final Map<ServerLevel, LevelState> STATE = new HashMap<>();

    private static final Meter TICK = Profiling.ENABLED ? Meters.meter("Steering.onServerTick") : null;

    private Steering() {
    }

    // --- the roster --------------------------------------------------------------------------------

    /**
     * A villager entered a level. Called from the same loader hook that mints a persona.
     *
     * <p>Keyed on the entity id rather than the persona id, because that is what the level hands
     * back on the way out and a roster that cannot be pruned is a memory leak wearing a cache's
     * clothes.
     */
    public static void onVillagerLoaded(Entity entity) {
        if (Profiling.MOD_INERT || !(entity instanceof Villager villager)
                || !(entity.level() instanceof ServerLevel level)) {
            return;
        }
        PersonaService.personaOf(villager)
                .filter(Persona::isGenerated)
                .ifPresent(persona -> state(level).roster
                        .put(villager.getId(), new Tracked(villager, persona)));
    }

    /** A villager left a level — unloaded, died, or was converted. */
    public static void onVillagerUnloaded(Entity entity) {
        if (!(entity instanceof Villager) || !(entity.level() instanceof ServerLevel level)) {
            return;
        }
        LevelState levelState = STATE.get(level);
        if (levelState != null) {
            levelState.roster.remove(entity.getId());
        }
    }

    /** Nothing here outlives a server. Single player reuses the process between worlds. */
    public static void onServerStopping() {
        STATE.clear();
    }

    private static LevelState state(ServerLevel level) {
        return STATE.computeIfAbsent(level, unused -> new LevelState());
    }

    // --- the tick ----------------------------------------------------------------------------------

    /**
     * Hooked to the end of every server tick by both loaders.
     *
     * <p>Three passes, in this order and for a reason. The veto runs first because it is the only
     * one with a deadline — it has to land before the next brain tick reads the memory. The scan is
     * gated to a seventh of the roster. The governor drains at most {@link #TRANSITIONS_PER_TICK}.
     */
    public static void onServerTick(MinecraftServer server) {
        if (Profiling.MOD_INERT || STATE.isEmpty()) {
            return;
        }
        long begun = Meters.now();
        try {
            for (ServerLevel level : server.getAllLevels()) {
                LevelState levelState = STATE.get(level);
                if (levelState == null || levelState.roster.isEmpty()) {
                    continue;
                }
                tickLevel(level, levelState);
            }
        } finally {
            if (Profiling.ENABLED) {
                TICK.end(begun);
                Meters.count("Steering.onServerTick calls");
            }
        }
    }

    private static void tickLevel(ServerLevel level, LevelState levelState) {
        long gameTime = level.getGameTime();
        long dayTime = level.getDayTime();

        List<Integer> gone = null;
        for (Map.Entry<Integer, Tracked> entry : levelState.roster.entrySet()) {
            Tracked tracked = entry.getValue();
            if (tracked.villager.isRemoved() || !tracked.villager.isAlive()) {
                // Pruned on the way past rather than by a second pass. The unload hooks are the
                // real mechanism; this is what catches a villager that died between them.
                (gone == null ? gone = new ArrayList<>() : gone).add(entry.getKey());
                continue;
            }
            // Their own slot, not the clock's. Two villagers standing next to each other are in
            // different slots for up to DayPlan.SPREAD ticks after every boundary, which is the
            // transition wave and the reason the whole village does not path at once.
            DaySlot slot = DayPlan.slotFor(tracked.persona, dayTime);

            if (tracked.standoff != null) {
                if (slot.isLabour() && tracked.villager.getBrain().isActive(Activity.WORK)) {
                    updateArrival(level, tracked);
                    if (tracked.parked) {
                        declineTheJobSite(levelState, tracked, slot);
                    }
                } else {
                    releaseStandoff(tracked);
                }
            }
            if (slot != tracked.servedSlot && DayPlan.pathGateOpen(tracked.persona, gameTime)) {
                levelState.waiting.addLast(tracked);
                tracked.servedSlot = slot;
            }
        }
        if (gone != null) {
            gone.forEach(levelState.roster::remove);
        }

        for (int served = 0; served < TRANSITIONS_PER_TICK && !levelState.waiting.isEmpty(); served++) {
            Tracked next = levelState.waiting.pollFirst();
            enterSlot(level, levelState, next, DayPlan.slotFor(next.persona, dayTime));
        }
    }

    /**
     * <b>The veto.</b> Declines the walk target vanilla keeps offering a parked villager.
     *
     * <p>Called every tick for every parked villager, which is why it opens with the cheapest test
     * that can end it — {@code getMemory} on a brain that usually holds nothing there. In the common
     * case this is one hashmap lookup and a return.
     *
     * <p>It fires about once per {@link DayPlan#STROLL_TO_POI_COOLDOWN} ticks per parked villager,
     * because that is {@code StrollToPoi}'s own cooldown and {@code StrollToPoi} is the only
     * behaviour left that can reach a correctly-chosen standoff point.
     */
    private static void declineTheJobSite(LevelState levelState, Tracked tracked, DaySlot slot) {
        if (!slot.isLabour()) {
            return;
        }
        Villager villager = tracked.villager;
        Brain<Villager> brain = villager.getBrain();

        Optional<WalkTarget> target = brain.getMemory(MemoryModuleType.WALK_TARGET);
        if (target.isEmpty()) {
            return;
        }
        // Vanilla switches activity for panic, raids, hiding and sleeping, so one test covers all
        // four — and covers any future one, which a list of activity names would not.
        if (!brain.isActive(Activity.WORK)) {
            return;
        }
        if (villager.getTradingPlayer() != null
                || brain.hasMemoryValue(MemoryModuleType.HURT_BY_ENTITY)
                || brain.hasMemoryValue(MemoryModuleType.NEAREST_HOSTILE)) {
            return;
        }
        BlockPos jobSite = jobSiteOf(villager);
        if (jobSite == null) {
            return;
        }
        BlockPos going = target.get().getTarget().currentBlockPosition();
        if (going.distManhattan(jobSite) > VETO_WITHIN) {
            // Somewhere else entirely — a stroll, a dropped loaf, a bed. Not ours to refuse.
            return;
        }
        brain.eraseMemory(MemoryModuleType.WALK_TARGET);
        levelState.vetoes++;
        if (Profiling.ENABLED) {
            Meters.count("Steering.declineTheJobSite erasures");
        }
    }

    /**
     * A villager has crossed into a new slot. Decides what, if anything, to do about it.
     *
     * <p>This is the only place a walk target is written, and it is behind both walls: the caller
     * has already spent one of {@link #TRANSITIONS_PER_TICK}, and the villager has already passed
     * {@link DayPlan#pathGateOpen}.
     */
    private static void enterSlot(ServerLevel level, LevelState levelState, Tracked tracked,
                                  DaySlot slot) {
        tracked.parked = false;
        tracked.standoff = null;
        tracked.gaveUp = false;
        // The one place the cached persona is refreshed. See Tracked.
        PersonaService.personaOf(tracked.villager)
                .filter(Persona::isGenerated)
                .ifPresent(fresh -> tracked.persona = fresh);

        if (!slot.isLabour() || DayPlan.isDiligent(tracked.persona)) {
            // A diligent villager is left entirely alone. Vanilla walks them to their workstation,
            // WorkAtPoi fires, the sound plays and the trades restock. Not steering is the feature.
            return;
        }
        if (!tracked.villager.getBrain().isActive(Activity.WORK)) {
            // THE GUARD THIS SESSION ALMOST SHIPPED WITHOUT, and it is the inherited bug's own
            // mechanism pointed back at us. A bell writes HEARD_BELL_TIME with no expiry into every
            // living entity within 32 blocks; ReactToBell (CORE, priority 0) then re-asserts HIDE
            // every tick while it is present; the HIDE package is the only one of the seven with no
            // UpdateActivityFromSchedule, so the schedule cannot pull a villager out of it; and its
            // one exit, SetHiddenState, needs HIDING_PLACE, which only LocateHidingPlace writes and
            // which requires WALK_TARGET to be ABSENT.
            //
            // So a walk target of ours, written to a villager who happens to be hiding, can cost
            // them the tick that would have let them out — and a villager who never gets out of
            // HIDE is a villager stuck indoors for the rest of the world's life. That is the bug
            // this session inherited, and writing it ourselves was one missing line away.
            return;
        }
        BlockPos jobSite = jobSiteOf(tracked.villager);
        if (jobSite == null) {
            return;
        }
        BlockPos standoff = chooseStandoff(level, tracked.persona, jobSite);
        if (standoff == null) {
            // Nowhere to stand: hemmed in, on a cliff, or the offsets all landed in water. A lazy
            // villager with nowhere to be is a working villager, which fails safe in the direction
            // that leaves vanilla in charge.
            return;
        }
        tracked.standoff = standoff;
        // Close enough 1, which is the same number StrollToPoi uses, so "arrived" means the same
        // thing to MoveToTargetSink for our target as for vanilla's. Walking speed rather than the
        // profession's, because somebody who is not going to work is not in a hurry.
        tracked.villager.getBrain().setMemory(MemoryModuleType.WALK_TARGET,
                new WalkTarget(standoff, 0.4F, PARKED_WITHIN));
        levelState.steered++;
        if (Profiling.ENABLED) {
            Meters.count("Steering.enterSlot walk targets");
        }
    }

    /**
     * The first offset from {@link DayPlan#STANDOFF_OFFSETS} whose resolved ground still satisfies
     * both vanilla metrics.
     *
     * <p><b>Resolved and then re-checked</b>, because the table is written for flat ground and the
     * world is not: a standoff seven blocks east and three up a hill is Manhattan ten, and
     * {@code SetWalkTargetFromBlockMemory} — which measures in Manhattan — would haul them back from
     * it. {@link DayPlan#isAStandoff} is applied to where the villager would actually stand.
     *
     * <p>Bounded at four candidates rather than sixteen. Each one is a heightmap read, this runs
     * behind the governor at eight a tick, and a villager who fails four is a villager standing
     * somewhere strange enough that working is the better answer.
     */
    private static BlockPos chooseStandoff(ServerLevel level, Persona persona, BlockPos jobSite) {
        int preferred = DayPlan.preferredStandoff(persona);
        for (int i = 0; i < 4; i++) {
            int[] offset = DayPlan.STANDOFF_OFFSETS[
                    (preferred + i) % DayPlan.STANDOFF_OFFSETS.length];
            BlockPos flat = jobSite.offset(offset[0], 0, offset[1]);
            if (!level.getChunkSource().hasChunk(flat.getX() >> 4, flat.getZ() >> 4)) {
                // An unloaded chunk answers with the world floor rather than with the ground —
                // session 03 built a village inside the deepslate that way. Refuse instead.
                continue;
            }
            BlockPos ground = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, flat);
            if (DayPlan.isAStandoff(jobSite, ground)) {
                return ground;
            }
        }
        return null;
    }

    // --- what the plan believes ---------------------------------------------------------------

    /**
     * <b>What this villager is doing, and whether the plan meant it.</b>
     *
     * <p>The one method the debug command and the harness both read, so the sentence a player sees
     * and the sentence a test asserts cannot disagree — session 11's argument about
     * {@code Dialogue.poolFor}, in a new place.
     *
     * <p><b>{@link Posture#STUCK} is the point of this method.</b> Vanilla sets
     * {@code CANT_REACH_WALK_TARGET_SINCE} when a path cannot be computed or cannot reach, and it is
     * the only honest signal available for a villager sealed into their own house by village
     * generation. Without it a stuck villager reads as a lazy one — both are silent, both are away
     * from their workstation, and the exit criterion's <i>you can hear which is which</i> would be
     * met by a villager nobody meant to be quiet.
     */
    public static Posture postureOf(Villager villager) {
        if (!(villager.level() instanceof ServerLevel level)) {
            return Posture.OFF_DUTY;
        }
        Brain<Villager> brain = villager.getBrain();
        if (isBellLocked(brain)) {
            return Posture.BELL_LOCKED;
        }
        if (jobSiteOf(villager) == null) {
            return Posture.NO_JOB;
        }
        LevelState levelState = STATE.get(level);
        Tracked tracked = levelState == null ? null : levelState.roster.get(villager.getId());
        // This villager's own slot, offset and all — so a villager who has not crossed yet reads
        // OFF_DUTY rather than WORKING, which is what they are.
        DaySlot slot = tracked == null
                ? DaySlot.at(level.getDayTime())
                : DayPlan.slotFor(tracked.persona, level.getDayTime());
        if (!slot.isLabour() || !brain.isActive(Activity.WORK)) {
            return Posture.OFF_DUTY;
        }
        long unreachableSince = brain.getMemory(MemoryModuleType.CANT_REACH_WALK_TARGET_SINCE)
                .orElse(Long.MIN_VALUE);
        if (unreachableSince != Long.MIN_VALUE
                && level.getGameTime() - unreachableSince >= DayPlan.STUCK_AFTER_TICKS) {
            return Posture.STUCK;
        }
        if (tracked == null || tracked.standoff == null) {
            return Posture.WORKING;
        }
        return tracked.parked ? Posture.STANDOFF : Posture.WALKING_OUT;
    }

    /** Where the plan sent this villager, if it sent them anywhere. For the debug command. */
    public static Optional<BlockPos> standoffOf(Villager villager) {
        if (!(villager.level() instanceof ServerLevel level)) {
            return Optional.empty();
        }
        LevelState levelState = STATE.get(level);
        Tracked tracked = levelState == null ? null : levelState.roster.get(villager.getId());
        return Optional.ofNullable(tracked == null ? null : tracked.standoff);
    }

    /**
     * The bell lock's signature, as three memory reads. See {@link Posture#BELL_LOCKED}.
     *
     * <p>All three clauses are needed and each rules something out. {@code HIDE} active alone is
     * ordinary behaviour during a raid, and a villager who heard a bell a moment ago is supposed to
     * hide. It is <b>{@code HIDING_PLACE} absent while both of the others hold</b> that says the
     * exit condition can never be reached, because {@code SetHiddenState} is the only thing that
     * erases {@code HEARD_BELL_TIME} and it will not run without a hiding place.
     */
    public static boolean isBellLocked(Brain<Villager> brain) {
        return brain.isActive(Activity.HIDE)
                && brain.hasMemoryValue(MemoryModuleType.HEARD_BELL_TIME)
                && !brain.hasMemoryValue(MemoryModuleType.HIDING_PLACE);
    }

    /** {@code MemoryModuleType.JOB_SITE} as a position in this level, or null. */
    public static BlockPos jobSiteOf(Villager villager) {
        GlobalPos job = villager.getBrain().getMemory(MemoryModuleType.JOB_SITE).orElse(null);
        return job == null || job.dimension() != villager.level().dimension() ? null : job.pos();
    }

    /** Counts since the server started, for the debug command and the harness. */
    public static String describe(ServerLevel level) {
        LevelState levelState = STATE.get(level);
        if (levelState == null) {
            return "no villagers tracked";
        }
        return levelState.roster.size() + " tracked, " + levelState.steered + " steered, "
                + levelState.vetoes + " job-site walk target(s) declined, "
                + levelState.waiting.size() + " waiting on the governor";
    }

    /**
     * <b>Takes our walk target back when the slot ends or vanilla stops running {@code WORK}.</b>
     *
     * <p>This exists because <b>vanilla does not do it for its own</b>, and that is a defect this
     * session had to read the engine to find. {@code Brain.setActiveActivity} erases only the
     * memories listed in {@code activityMemoriesToEraseWhenStopped}, and that map is populated
     * solely by {@code addActivityAndRemoveMemoryWhenStopped} — which {@code Villager
     * .registerBrainGoals} never calls. So a walk target written on the last tick of {@code WORK}
     * survives into {@code HIDE} or {@code REST} untouched.
     *
     * <p>One stale walk target is enough to matter: {@code LocateHidingPlace} requires
     * {@code WALK_TARGET} to be <b>absent</b>, and it is the only writer of {@code HIDING_PLACE},
     * which is the only way out of {@code HIDE}. Leaving ours behind would be handing a villager a
     * chance of never coming out of their house again — the bug this session inherited, caused by
     * the session that was supposed to be looking at it.
     *
     * <p><b>Erases only if the target is still ours.</b> If vanilla has already written something
     * else there — a bed, a hiding place, a hostile to flee — that is the villager getting on with
     * their life and it is not ours to cancel.
     */
    private static void releaseStandoff(Tracked tracked) {
        BlockPos standoff = tracked.standoff;
        tracked.standoff = null;
        tracked.parked = false;
        tracked.gaveUp = false;
        if (standoff == null) {
            return;
        }
        Brain<Villager> brain = tracked.villager.getBrain();
        brain.getMemory(MemoryModuleType.WALK_TARGET)
                .filter(target -> target.getTarget().currentBlockPosition().equals(standoff))
                .ifPresent(target -> brain.eraseMemory(MemoryModuleType.WALK_TARGET));
    }

    /**
     * A villager the plan sent somewhere has arrived, or has proved it cannot.
     *
     * <p>Called from the tick rather than from an event because vanilla has no arrival hook, and
     * checking a block position is cheaper than the alternative — this is one integer subtraction
     * against a position the entity already holds.
     */
    private static void updateArrival(ServerLevel level, Tracked tracked) {
        if (tracked.standoff == null || tracked.parked || tracked.gaveUp) {
            return;
        }
        if (tracked.villager.blockPosition().distManhattan(tracked.standoff) <= PARKED_WITHIN) {
            tracked.parked = true;
            return;
        }
        long since = tracked.villager.getBrain()
                .getMemory(MemoryModuleType.CANT_REACH_WALK_TARGET_SINCE).orElse(Long.MIN_VALUE);
        if (since != Long.MIN_VALUE
                && level.getGameTime() - since >= DayPlan.STUCK_AFTER_TICKS) {
            // We sent them somewhere they cannot get to. Stop asking: leaving our own unreachable
            // target in place would let vanilla's tooLongUnreachableDuration release the job site
            // POI after twelve hundred ticks, which would cost this villager their profession
            // because of a place we chose. A lazy villager who cannot reach their standoff works.
            tracked.gaveUp = true;
            tracked.standoff = null;
            Namesake.LOGGER.debug("Standoff for persona {} is unreachable; leaving them to vanilla",
                    tracked.persona.id());
        }
    }
}
