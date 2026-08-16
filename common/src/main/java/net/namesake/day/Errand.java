package net.namesake.day;

import com.google.common.collect.ImmutableList;
import com.mojang.datafixers.util.Pair;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.behavior.BehaviorControl;
import net.minecraft.world.entity.ai.behavior.DoNothing;
import net.minecraft.world.entity.ai.behavior.RunOne;
import net.minecraft.world.entity.ai.behavior.SetEntityLookTarget;
import net.minecraft.world.entity.ai.behavior.SetLookAndInteract;
import net.minecraft.world.entity.ai.behavior.ShowTradesToPlayer;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.schedule.Activity;
import net.minecraft.core.GlobalPos;

/**
 * <b>The custom {@code ERRAND} activity: what it is, what it is made of, and the two things that
 * make a custom activity survivable at all.</b> Session 14, and {@code DESIGN.md} §7's slots 2, 3,
 * 6b and 7.
 *
 * <h2>What an {@code Activity} actually is, because the answer decides the whole session</h2>
 *
 * <p>It is a <b>map key</b>. {@code Brain} holds behaviours in
 * {@code Map<Integer, Map<Activity, Set<BehaviorControl>>>} and runs only the entries whose
 * {@code Activity} is in {@code activeActivities}. That is the entire mechanism, and the two
 * consequences below are the whole design:
 *
 * <ul>
 *   <li><b>Activating a non-{@code WORK} activity is the only way to make vanilla's {@code WORK}
 *       package stop running.</b> Session 13's veto could hold a villager five to eight blocks from
 *       their bench because {@code SetWalkTargetFromBlockMemory(JOB_SITE, …, closeEnough = 9, …)} is
 *       silent inside Manhattan nine and {@code StrollToPoi} only fires once every eighty ticks. An
 *       errand goes <i>further than nine</i> — to the bell, to a bed — where that behaviour writes
 *       the job site back <b>every tick</b>. Vetoing that is a write and an erase per villager per
 *       tick, which is the tug-of-war §7 rules out, arriving as a bill rather than as a design.
 *       Switching the activity costs one set operation and silences eleven behaviours at once.</li>
 *   <li><b>Nothing else in Minecraft looks an {@code Activity} up.</b>
 *       {@code BuiltInRegistries.ACTIVITY} is mentioned twice in the whole game — its own
 *       declaration and {@code Activity.register} — and {@code Brain.getActiveActivities()} has no
 *       callers at all. So the key's <i>identity</i> is all that matters and its <i>name</i> reaches
 *       nothing: no packet, no save file, no screen. See {@link #ACTIVITY} for why that turns "make
 *       a new activity", which vanilla refuses without an access transformer, into "borrow a key no
 *       villager uses", which costs nothing on either loader.</li>
 * </ul>
 *
 * <h2>{@code ERRAND} is vanilla's {@code WORK} package with the work taken out</h2>
 *
 * <p>{@link #behaviours} is three entries, and each one is lifted from {@code getWorkPackage} at the
 * same priority. What is <i>not</i> there is the point: no {@code WorkAtPoi}, no
 * {@code StrollToPoi}, no {@code StrollAroundPoi}, no {@code SetWalkTargetFromBlockMemory}, no
 * {@code HarvestFarmland} — nothing that measures anything from the workstation. So a villager on an
 * errand still looks at you, still holds their goods up when you come close, and cannot be dragged
 * back to their bench.
 *
 * <p><b>It is therefore cheaper for the engine than the slot it replaces</b>, which is the answer to
 * the budget session 13 handed this one: a villager in {@code ERRAND} runs three behaviours where a
 * villager in {@code WORK} runs eleven, and the walk target comes from {@link Steering}'s
 * end-of-tick hook rather than from a behaviour that rewrites it whenever it is absent.
 *
 * <h2>And it deliberately omits {@code UpdateActivityFromSchedule}, which is a loaded gun</h2>
 *
 * <p>{@code Schedule.VILLAGER_DEFAULT} says {@code WORK} from 2000 to 8999, so
 * {@code UpdateActivityFromSchedule} — priority 99 in five of vanilla's seven packages — would
 * switch {@code ERRAND} off within twenty ticks of it being switched on. Omitting it is the only way
 * an activity outside the schedule can exist at all.
 *
 * <p><b>It is also exactly how the villagers stuck in their own houses happen.</b> {@code HIDE} is
 * the only vanilla package with no {@code UpdateActivityFromSchedule}, which is why the schedule
 * cannot pull a villager out of it and why its one exit — {@code SetHiddenState}, needing a hiding
 * place a bedless villager can never acquire — is a deadlock with no way out. Session 13 diagnosed
 * that, named it {@code Steering.Posture.BELL_LOCKED} and repaired it on the owner's ruling.
 *
 * <p><b>So an {@code ERRAND} with no exit is a second bell lock, written by us, and</b>
 * {@code DESIGN.md} <b>did not say so.</b> It does now, and {@link Steering#leaveErrand} is the
 * exit: the deactivation watchdog was built before anything that could activate this.
 */
public enum Errand {

    /**
     * <b>11:00 — the roads fill.</b> {@code DESIGN.md} §7 slot 2.
     *
     * <p>Out of the workshop and in to the middle of the village, everybody at once, which is what
     * <i>sacks moving one direction</i> is when the direction is a place rather than a heading. The
     * destination is {@code MEETING_POINT} — the bell — which every villager already holds because
     * {@code AcquirePoi} in vanilla's CORE package writes it. Reading a memory rather than the
     * settlement table is what keeps this off the registry and out of the tick budget.
     *
     * <p><b>Only the employed haul</b>, and that is the one rule this slot has: you carry what you
     * made. A nitwit and an unemployed villager have nothing to bring, so they are left to vanilla
     * and read {@code NO_JOB} exactly as they do during a labour slot.
     */
    HAUL(MemoryModuleType.MEETING_POINT, 4, 0.5F, true, Steering.Posture.HAULING),

    /**
     * <b>12:00 — the hearths fill.</b> {@code DESIGN.md} §7 slot 3.
     *
     * <p>Home, to the bed's own position, which puts a villager inside their house rather than
     * outside it. {@code closeEnough} is one because that is what vanilla's own
     * {@code SetWalkTargetFromBlockMemory(HOME, …, 1, 150, 1200)} uses in the REST package, so
     * "arrived" means the same thing here as it does at bedtime.
     *
     * <p><b>Nobody sleeps.</b> {@code SleepInBed} lives in REST and REST is not running, so a
     * villager stands at their hearth for the hour and then goes back to work. That is the
     * difference between lunch and a nap, and it is expressed by which package is active rather than
     * by a flag.
     */
    NOON(MemoryModuleType.HOME, 1, 0.5F, false, Steering.Posture.AT_HEARTH),

    /**
     * <b>18:00 to dawn — the watch.</b> {@code DESIGN.md} §7 slots 6b and 7.
     *
     * <p>The one errand that is <i>not</i> everybody: {@link DayPlan#standsWatch} selects
     * <b>one villager in four</b> off {@code boldness} — {@code BOLDNESS_TO_WATCH = 20} measures
     * 25.0% over 4,536 real personas — so a village of nine keeps 2.2 people out after the rest
     * have gone in, against §7's ruled two. <i>(This said "one in five" until session 15. The
     * comment moved, not the constant: the number is measured and both {@code DESIGN.md} and
     * {@code /namesake debug dayplan} already said one in four.)</i> The window opens on vanilla's own 12000 keyframe — the tick
     * {@code VILLAGER_DEFAULT} turns {@code IDLE} into {@code REST} — which is the boundary §7 calls
     * the 6a/6b line, and it runs to the end of the day.
     *
     * <p><b>At the bell rather than on the perimeter, and that is a ruling with a reason.</b> §7's
     * at-a-glance line is <i>two torches on the perimeter</i>, and the perimeter is refused twice
     * over: a torch is a block this mod does not place, and the edge of a village after dark is
     * where the mobs are — standing somebody's villagers out there to satisfy a legibility law is
     * this mod spending a player's village on a picture. The meeting point is inside, lit, and where
     * the golem walks; the observable that carries the line is that at midnight these are the only
     * two people out, which no other hour of the day looks like.
     *
     * <p>Walking speed rather than the profession's, because a villager taking up a post is not in a
     * hurry — session 13's argument for the standoff's 0.4, at the other end of the day.
     */
    WATCH(MemoryModuleType.MEETING_POINT, 4, 0.4F, false, Steering.Posture.ON_WATCH);

    /**
     * <b>The activity key, and it is a borrowed vanilla one rather than a new one. That is a finding
     * about 1.21.1 rather than a shortcut, and it is the single most surprising line in the
     * session.</b>
     *
     * <p><b>Vanilla will not let a mod make an {@code Activity}.</b> {@code Activity(String)} is
     * {@code private}; the only caller is {@code Activity.register}, which is private too. It reads
     * as public in a NeoForge decompile because <i>NeoForge ships an access transformer for it</i>,
     * which is a property of one loader rather than an API — and {@code :common} compiles against
     * plain NeoForm, where {@code new Activity(…)} does not compile at all. Widening it ourselves
     * would mean an access transformer for {@code :common}, an access widener for {@code :fabric},
     * and a third module that already has one: three spellings of one change, the first bytecode-level
     * modification this project has made to a class it does not own, and a new way for the two
     * loaders to disagree — in the session that already carries the highest risk class in the plan.
     *
     * <p><b>And it buys nothing, because an {@code Activity}'s name is never read by anything.</b>
     * Measured over all 6,316 source files of 1.21.1: {@code BuiltInRegistries.ACTIVITY} is
     * mentioned exactly twice — its own declaration and {@code Activity.register} — and
     * {@code Brain.getActiveActivities()} has <b>no callers at all</b>. No packet carries it, no save
     * file holds it, no screen prints it. An {@code Activity} is a map key in
     * {@code Brain.availableBehaviorsByPriority} and nothing else, and what this session needs is
     * <b>a key an adult villager's brain does not use</b>. Vanilla registers twenty-seven and
     * {@code Villager.registerBrainGoals} claims ten of them; the other seventeen are free.
     *
     * <p><b>{@code LAY_SPAWN} is chosen on safety rather than on how it reads</b>, because how it
     * reads costs nothing — the borrowed name appears in one diagnostic line this file writes and
     * nowhere else, while every other surface says {@link Errand} or a {@code Steering.Posture}. It
     * is the frog's egg-laying state: the one key on the list that nothing which walks on two legs
     * will ever be given, so it is the one least likely to be claimed by a future vanilla package or
     * by another mod's villager behaviours. {@code ErrandTest.theBorrowedKeyIsFree} holds that claim
     * against {@code Villager.registerBrainGoals}'s own bytecode, so it turns red the day Mojang
     * disagrees rather than the day a player notices their villagers standing still.
     */
    public static final Activity ACTIVITY = Activity.LAY_SPAWN;

    /**
     * <b>The behaviour list, and it is the same for every errand.</b>
     *
     * <p>Constructed fresh on each call and never shared, because a {@code Behavior} carries its own
     * {@code status} and {@code endTimestamp} — one instance across two villagers is two villagers
     * sharing a state machine. Vanilla builds a fresh package per villager for the same reason, and
     * it is why {@link #addActivitySafely} registers <b>once per brain</b> rather than on every
     * entry: adding a second copy would leave both in the set for ever, and
     * {@code startEachNonRunningBehavior} would walk all of them every tick.
     *
     * <p>Three entries, all of them vanilla's, all at vanilla's own priority:
     *
     * <ul>
     *   <li><b>the minimal look</b> — {@code getMinimalLookBehavior()} is private, so it is rebuilt
     *       here from the public parts it is made of. Without it a villager on an errand walks with
     *       a fixed stare, and §7's fourth legibility law is <i>facing beats posing</i>.</li>
     *   <li><b>{@code ShowTradesToPlayer}</b> — which is where the sack comes from. Vanilla makes a
     *       villager hold their goods up when somebody is watching, so §7's <i>held item is a noun</i>
     *       is paid by the engine at zero art, in the one slot the design wanted it for.</li>
     *   <li><b>{@code SetLookAndInteract}</b> — a villager who will not turn towards you while they
     *       cross the square is worse than vanilla, and this session may not make anything worse than
     *       vanilla.</li>
     * </ul>
     */
    static ImmutableList<Pair<Integer, ? extends BehaviorControl<? super Villager>>> behaviours() {
        return ImmutableList.of(
                Pair.of(5, new RunOne<LivingEntity>(ImmutableList.of(
                        Pair.of(SetEntityLookTarget.create(EntityType.VILLAGER, 8.0F), 2),
                        Pair.of(SetEntityLookTarget.create(EntityType.PLAYER, 8.0F), 2),
                        Pair.of(new DoNothing(30, 60), 8)))),
                Pair.of(10, new ShowTradesToPlayer(400, 1600)),
                Pair.of(10, SetLookAndInteract.create(EntityType.PLAYER, 4)));
    }

    private final MemoryModuleType<GlobalPos> destination;
    private final int closeEnough;
    private final float speed;
    private final boolean needsAJob;
    private final Steering.Posture posture;

    Errand(MemoryModuleType<GlobalPos> destination, int closeEnough, float speed, boolean needsAJob,
           Steering.Posture posture) {
        this.destination = destination;
        this.closeEnough = closeEnough;
        this.speed = speed;
        this.needsAJob = needsAJob;
        this.posture = posture;
    }

    /** The vanilla memory this errand walks to. Never a place of ours; never the settlement table. */
    public MemoryModuleType<GlobalPos> destination() {
        return destination;
    }

    /** How near counts as arrived, in {@code MoveToTargetSink}'s own units. */
    public int closeEnough() {
        return closeEnough;
    }

    /** Walking speed. Vanilla's villager modifier for the two errands with somewhere to be. */
    public float speed() {
        return speed;
    }

    /** True while this errand is only for villagers who hold a workstation. */
    public boolean needsAJob() {
        return needsAJob;
    }

    /** What the debug command and the harness call a villager on this errand. */
    public Steering.Posture posture() {
        return posture;
    }

    /**
     * <b>Registers {@code ERRAND} on this brain if it is not there, then activates it — and returns
     * whether the brain agrees.</b> {@code WORKPLAN.md}'s named helper, and the reason it exists is
     * one line of {@code Villager}:
     *
     * <pre>{@code   this.brain = brain.copyWithoutBehaviors();  // then registerBrainGoals(...) }</pre>
     *
     * <p>{@code Brain.copyWithoutBehaviors} builds a <b>brand new</b> {@code Brain} and carries only
     * the memories across. Every behaviour, every activity registration and every
     * {@code activityRequirements} entry is gone, and what comes back is vanilla's list — so an
     * activity added from outside is destroyed. That happens in five places, and it is the fifth
     * that decides this method's shape:
     *
     * <ol>
     *   <li>{@code AssignProfessionFromJobSite} — a villager takes a job</li>
     *   <li>{@code ResetProfession} — a villager loses one</li>
     *   <li>{@code ZombieVillager.finishConversion} — a cure</li>
     *   <li>{@code Villager.ageBoundaryReached} — a baby grows up</li>
     *   <li><b>{@code Villager.readAdditionalSaveData} — every single load from disk</b></li>
     * </ol>
     *
     * <p>So a custom activity is wiped on <b>every chunk load for every villager</b>, which is the
     * ordinary path rather than an edge case, and none of the other four fires an event either
     * loader lets us see. A one-shot registration at mint is therefore not merely fragile — it is
     * wrong the first time a player walks away from a village and comes back.
     *
     * <h2>What re-establishes it, and what that costs per tick: nothing</h2>
     *
     * <p><b>It is re-established on demand, at the moment of use, and the wipe is detected by
     * effect rather than remembered by a flag.</b> {@code setActiveActivityIfPossible} consults
     * {@code activityRequirements}, which is exactly the map {@code copyWithoutBehaviors} drops, so
     * <i>the activation failing is the wipe</i>. There is no per-tick check, no bookkeeping that can
     * disagree with the brain, and no cost at all on any tick this is not called — and it is called
     * only from {@link Steering}'s slot entry, which is already behind the transition governor at
     * eight villagers a tick.
     *
     * <p>It also covers all five callers with one mechanism, including the four that fire no event,
     * which no amount of hooking could.
     *
     * <h2>Why the failed activation is safe, which is the part that had to be checked</h2>
     *
     * <p>{@code setActiveActivityIfPossible} does <b>not</b> leave a brain alone when the
     * requirements are not met — it calls {@code useDefaultActivity()}, which is {@code IDLE}. So a
     * blind activation against a wiped brain would quietly dump a villager out of {@code WORK} in
     * the middle of the morning. Two things make that harmless here and both are deliberate: the
     * second attempt happens in the same call, before the brain ticks again — our hook runs at the
     * <i>end</i> of the server tick, after the entity tick that would have run behaviours — and the
     * caller is told, by the return value, if even that failed.
     *
     * @return true once the brain is running {@code ERRAND}. False means something refused twice,
     *         and the caller must put the villager back on the schedule rather than leave them where
     *         a failed activation left them.
     */
    public static boolean addActivitySafely(Brain<Villager> brain) {
        brain.setActiveActivityIfPossible(ACTIVITY);
        if (brain.isActive(ACTIVITY)) {
            return true;
        }
        // The activation failed, so this brain has never carried ERRAND — either it is new, or
        // refreshBrain replaced it since we last looked. Register once and try again. The set is
        // empty by construction on this path, which is what stops the behaviour list growing.
        brain.addActivity(ACTIVITY, behaviours());
        brain.setActiveActivityIfPossible(ACTIVITY);
        return brain.isActive(ACTIVITY);
    }
}
