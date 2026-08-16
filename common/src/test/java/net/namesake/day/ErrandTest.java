package net.namesake.day;

import com.google.common.collect.ImmutableList;
import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.Dynamic;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.behavior.BehaviorControl;
import net.minecraft.world.entity.ai.behavior.GoToPotentialJobSite;
import net.minecraft.world.entity.ai.behavior.HarvestFarmland;
import net.minecraft.world.entity.ai.behavior.SetEntityLookTarget;
import net.minecraft.world.entity.ai.behavior.SetLookAndInteract;
import net.minecraft.world.entity.ai.behavior.SetWalkTargetFromBlockMemory;
import net.minecraft.world.entity.ai.behavior.ShowTradesToPlayer;
import net.minecraft.world.entity.ai.behavior.StrollAroundPoi;
import net.minecraft.world.entity.ai.behavior.StrollToPoi;
import net.minecraft.world.entity.ai.behavior.StrollToPoiList;
import net.minecraft.world.entity.ai.behavior.UpdateActivityFromSchedule;
import net.minecraft.world.entity.ai.behavior.WorkAtComposter;
import net.minecraft.world.entity.ai.behavior.WorkAtPoi;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.schedule.Activity;
import net.namesake.testing.MethodBody;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>The custom activity, and the three claims it rests on that nothing else in this repository
 * could hold.</b>
 *
 * <p>Session 14's risk is not that an errand looks wrong — a harness leg can see that. It is that a
 * villager <b>never comes back out of one</b>, which looks like nothing at all until somebody's
 * village has been standing in the square for a week. Every test here is about that:
 *
 * <ol>
 *   <li>the key {@code ERRAND} borrows is one an adult villager's brain does not use — held against
 *       {@code Villager.registerBrainGoals}' own bytecode rather than against a comment;</li>
 *   <li>{@code addActivitySafely} survives the wipe that {@code refreshBrain} performs on <b>every
 *       load from disk</b>, and does not grow the behaviour list while doing it;</li>
 *   <li>the package omits {@code UpdateActivityFromSchedule} — which it must, or an errand lasts
 *       twenty ticks — and therefore contains nothing that would drag a villager back to their
 *       workstation either.</li>
 * </ol>
 */
class ErrandTest {

    @BeforeAll
    static void bootstrapMinecraft() {
        // Activity, MemoryModuleType and EntityType are registry entries, and the behaviour list
        // touches all three. Idempotent by Bootstrap's own guard.
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    /**
     * <b>The borrowed key is one no adult villager's brain claims, and this reads vanilla to say
     * so.</b>
     *
     * <p>{@code Errand.ACTIVITY} is a vanilla {@code Activity} constant rather than a new one,
     * because {@code Activity(String)} is private in vanilla and public only under NeoForge's own
     * access transformer — see {@code Errand.ACTIVITY} for the whole argument. The safety of that
     * choice is one claim and one claim only: <b>{@code Villager.registerBrainGoals} does not
     * register a package under it.</b> If it ever did, our behaviours and vanilla's would both be
     * live at once and the errand would silently stop silencing anything.
     *
     * <p>So the claim is checked where it is decided. The constant is found by identity rather than
     * by name, so changing which key is borrowed cannot make this test stop testing anything.
     */
    @Test
    @DisplayName("the activity key ERRAND borrows is one a villager brain never registers")
    void theBorrowedKeyIsFree() throws Exception {
        String borrowed = null;
        List<String> allKeys = new ArrayList<>();
        for (Field field : Activity.class.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers()) || field.getType() != Activity.class) {
                continue;
            }
            allKeys.add(field.getName());
            if (field.get(null) == Errand.ACTIVITY) {
                borrowed = field.getName();
            }
        }
        assertTrue(allKeys.size() > 20,
                "Activity should declare vanilla's whole set of keys; found " + allKeys);
        assertSame(Activity.class, Errand.ACTIVITY.getClass(),
                "Errand.ACTIVITY has become something other than a vanilla Activity constant. If "
                        + "that is deliberate — an access transformer, say — this test is now "
                        + "checking nothing and the argument in Errand.ACTIVITY needs rewriting.");
        final String key = borrowed;
        assertTrue(key != null, () -> "Errand.ACTIVITY is not one of vanilla's own Activity "
                + "constants " + allKeys + ", so the claim this test rests on cannot be checked");

        MethodBody registerBrainGoals = MethodBody.of(Villager.class, "registerBrainGoals");
        assertFalse(registerBrainGoals.invokes(Activity.class, key),
                () -> "Villager.registerBrainGoals now registers a package under Activity." + key
                        + ", which is the key Errand borrows. Two behaviour lists would be live at "
                        + "once and switching to ERRAND would stop silencing WORK — the whole "
                        + "mechanism, gone, with nothing red anywhere else. Borrow a different key "
                        + "and re-run this.");

        // And the ten it does claim, so a change to MethodBody or to the method's name cannot leave
        // this passing vacuously — the assertion above is a negative, and a negative over a body
        // nobody read is always true.
        for (String claimed : List.of("CORE", "IDLE", "WORK", "PLAY", "REST", "MEET", "PANIC",
                "RAID", "PRE_RAID", "HIDE")) {
            assertTrue(registerBrainGoals.invokes(Activity.class, claimed),
                    () -> "Villager.registerBrainGoals no longer mentions Activity." + claimed
                            + ". Either vanilla's brain has changed shape or this test is reading "
                            + "the wrong method, and in both cases the negative above proves "
                            + "nothing.");
        }
    }

    /**
     * <b>What the package must not contain, and each one names the failure it would cause.</b>
     *
     * <p>Two of these would be invisible. A {@code UpdateActivityFromSchedule} in this list turns
     * every errand into a twenty-tick flicker that a harness leg polling for a posture would simply
     * fail to catch; a job-site behaviour puts back exactly the contest the activity swap exists to
     * remove, at a write and an erase per villager per tick, and the only symptom is a number in a
     * profiler nobody ran.
     */
    @Test
    @DisplayName("the ERRAND package is vanilla's WORK with the work taken out")
    void thePackageIsWorkWithTheWorkTakenOut() {
        List<String> names = new ArrayList<>();
        for (Pair<Integer, ? extends BehaviorControl<? super Villager>> entry : Errand.behaviours()) {
            names.add(entry.getFirst() + ":" + entry.getSecond().getClass().getSimpleName());
        }
        assertFalse(names.isEmpty(),
                "an empty package would leave a villager on an errand with no look behaviour at "
                        + "all, walking across the square with a fixed stare — DESIGN.md §7's "
                        + "fourth legibility law is 'facing beats posing'");

        // READ OFF THE BYTECODE, NOT OFF THE RUNTIME CLASS NAMES, and the first version of this test
        // did the latter and was worth nothing. Most of vanilla's behaviours are built by
        // `BehaviorBuilder.create(...)`, which returns an anonymous `OneShot` — so
        // `UpdateActivityFromSchedule.create()` and `StrollToPoi.create()` produce objects whose
        // class is neither of those names, and are indistinguishable from each other. A breakage
        // pass put both of them into the package and this test stayed green twice. What the list is
        // made of is a property of the source, so it is checked where the source is: in the method's
        // own compiled body.
        MethodBody body = MethodBody.of(Errand.class, "behaviours");

        assertFalse(body.mentions(UpdateActivityFromSchedule.class), () -> """
                The ERRAND package contains UpdateActivityFromSchedule, and it must not. \
                Schedule.VILLAGER_DEFAULT says WORK from 2000 to 8999, so within twenty ticks of \
                an errand beginning that behaviour would read the schedule and switch the activity \
                back — every errand would last a fifth of a second. Its absence is what makes \
                Steering's deactivation watchdog necessary and is why that was built first. \
                Built: %s""".formatted(names));

        for (Class<?> forbidden : List.of(WorkAtPoi.class, WorkAtComposter.class, StrollToPoi.class,
                StrollAroundPoi.class, SetWalkTargetFromBlockMemory.class, HarvestFarmland.class,
                GoToPotentialJobSite.class, StrollToPoiList.class)) {
            assertFalse(body.mentions(forbidden), () -> """
                    The ERRAND package contains %s, which measures something from the workstation. \
                    The entire reason an errand switches activity rather than vetoing walk targets \
                    is that beyond Manhattan nine from the job site vanilla writes one back EVERY \
                    TICK, and declining that is a write and an erase per villager per tick — the \
                    tug-of-war DESIGN.md §7 rules the day plan out of, arriving as a bill. \
                    Built: %s""".formatted(forbidden.getSimpleName(), names));
        }

        // And the three it must keep, so the negatives above cannot pass over an empty method.
        for (Class<?> kept : List.of(SetEntityLookTarget.class, ShowTradesToPlayer.class,
                SetLookAndInteract.class)) {
            assertTrue(body.mentions(kept),
                    () -> "the ERRAND package no longer builds " + kept.getSimpleName()
                            + ", so a villager on an errand is worse than a vanilla one. Built: "
                            + names);
        }
    }

    /**
     * <b>The wipe, reproduced: {@code copyWithoutBehaviors} is what
     * {@code Villager.readAdditionalSaveData} does on every load from disk.</b>
     *
     * <p>This is the test that decides whether {@code addActivitySafely} is a tidiness helper or the
     * thing the ledger's never-cut list says it is. Three claims, and the second is the one nothing
     * else could catch:
     *
     * <ol>
     *   <li>a brain that has never seen {@code ERRAND} takes it;</li>
     *   <li><b>a brain that has been wiped takes it again</b>, which is what makes a custom activity
     *       survive a chunk load — and every villager is wiped on every chunk load;</li>
     *   <li><b>and calling it repeatedly does not grow the behaviour list.</b> Behaviours carry
     *       their own {@code status} and cannot be shared between villagers, so a helper that
     *       re-registered on every entry would add a fresh copy of the package four times a day for
     *       ever, and {@code startEachNonRunningBehavior} walks every one of them on every tick. It
     *       would read as a slow leak in somebody's world a week in.</li>
     * </ol>
     */
    @Test
    @DisplayName("addActivitySafely survives the wipe that every chunk load performs")
    void addActivitySafelySurvivesTheWipe() throws Exception {
        Brain<Villager> brain = villagerShapedBrain();

        assertFalse(brain.isActive(Errand.ACTIVITY), "nothing has registered it yet");
        assertTrue(Errand.addActivitySafely(brain), "a fresh brain must take the activity");
        assertTrue(brain.isActive(Errand.ACTIVITY));
        int afterFirst = behaviourCount(brain);
        assertTrue(afterFirst > 0, "the package should have put behaviours on the brain");

        // Claim 3, before the wipe: entering an errand four times a day for ever must not add four
        // packages a day for ever.
        for (int i = 0; i < 20; i++) {
            brain.updateActivityFromSchedule(2000L, i * 100L);
            assertTrue(Errand.addActivitySafely(brain), "re-entry " + i + " must succeed");
        }
        assertEquals(afterFirst, behaviourCount(brain), """
                addActivitySafely added the ERRAND package more than once to the same brain. A \
                Behavior carries its own status and endTimestamp, so the copies are not \
                interchangeable and none of them is removed — the set grows by the size of the \
                package every time a villager begins an errand, and Brain.startEachNonRunningBehavior \
                walks all of them every tick. The guard against it is that registration only happens \
                on the path where activation FAILED, which is the path where the brain is new.""");

        // Claim 2: the wipe itself. This is the line Villager.refreshBrain runs, and refreshBrain is
        // called from readAdditionalSaveData — i.e. every load, for every villager, for ever.
        Brain<Villager> reloaded = brain.copyWithoutBehaviors();
        reloaded.setCoreActivities(Set.of(Activity.CORE));
        reloaded.setDefaultActivity(Activity.IDLE);
        reloaded.addActivity(Activity.IDLE, ImmutableList.of());
        assertFalse(reloaded.isActive(Errand.ACTIVITY),
                "copyWithoutBehaviors must have dropped the activity — if it did not, this test is "
                        + "no longer reproducing the thing addActivitySafely exists for");

        assertTrue(Errand.addActivitySafely(reloaded),
                "a wiped brain must take the activity again, on demand, with no hook and no flag — "
                        + "four of the five refreshBrain callers fire no event either loader can "
                        + "see, and the fifth is every chunk load");
        assertTrue(reloaded.isActive(Errand.ACTIVITY));
    }

    /**
     * <b>The vanilla behaviour that shapes {@code addActivitySafely}, pinned so it cannot change
     * underneath us.</b>
     *
     * <p>{@code setActiveActivityIfPossible} does <b>not</b> leave a brain alone when the activity
     * is unregistered — it calls {@code useDefaultActivity()}. So a blind activation against a brain
     * that has just been reloaded does not fail quietly: it drops the villager into {@code IDLE} in
     * the middle of the working day. That is why the helper registers and retries in the same call,
     * and why it returns a boolean instead of {@code void}.
     */
    @Test
    @DisplayName("an unregistered activity does not fail quietly — vanilla drops the brain to IDLE")
    void aFailedActivationFallsBackToIdle() {
        Brain<Villager> brain = villagerShapedBrain();
        brain.setActiveActivityIfPossible(Activity.WORK);
        assertTrue(brain.isActive(Activity.WORK), "WORK is registered on this fixture");

        brain.setActiveActivityIfPossible(Errand.ACTIVITY);
        assertFalse(brain.isActive(Errand.ACTIVITY));
        assertTrue(brain.isActive(Activity.IDLE), """
                Brain.setActiveActivityIfPossible no longer falls back to the default activity when \
                the requirements are not met. That is the behaviour Errand.addActivitySafely is \
                written around: a blind activation against a wiped brain would otherwise be \
                harmless, and instead it silently takes a villager out of WORK. If vanilla has \
                changed this, the helper's second half can be simplified — check before doing it.""");
    }

    /**
     * A brain shaped like a villager's, without a level: the same core activity, the same default,
     * and {@code WORK} registered with the same job-site requirement.
     *
     * <p>Built by hand rather than off a live {@code Villager} because the whole point of these
     * assertions is that they cost ten milliseconds. A harness leg for the same claims would cost
     * six minutes a case, and a guard that expensive is a guard nobody adds a case to.
     */
    private static Brain<Villager> villagerShapedBrain() {
        Brain<Villager> brain = Brain.<Villager>provider(
                        List.of(MemoryModuleType.JOB_SITE, MemoryModuleType.HOME,
                                MemoryModuleType.MEETING_POINT, MemoryModuleType.WALK_TARGET),
                        List.of())
                .makeBrain(new Dynamic<>(NbtOps.INSTANCE, new CompoundTag()));
        brain.addActivity(Activity.CORE, ImmutableList.of());
        brain.addActivity(Activity.IDLE, ImmutableList.of());
        brain.addActivity(Activity.WORK, ImmutableList.of());
        brain.setCoreActivities(Set.of(Activity.CORE));
        brain.setDefaultActivity(Activity.IDLE);
        brain.setActiveActivityIfPossible(Activity.IDLE);
        return brain;
    }

    /**
     * How many behaviours are registered on this brain under {@code ERRAND}, read out of
     * {@code Brain}'s own private map.
     *
     * <p>Reflection, and it is the only way: {@code Brain} exposes {@code getRunningBehaviors} and
     * nothing that says what is <i>registered</i>. The claim being checked — that the package is
     * added once per brain rather than once per errand — is invisible from every public surface, and
     * a leak that is invisible is a leak that ships. This repository already reads bytecode to hold
     * two invariants it could not otherwise reach; this is the same trade.
     */
    @SuppressWarnings("unchecked")
    private static int behaviourCount(Brain<Villager> brain) throws Exception {
        Field field = Brain.class.getDeclaredField("availableBehaviorsByPriority");
        field.setAccessible(true);
        Map<Integer, Map<Activity, Set<BehaviorControl<? super Villager>>>> byPriority =
                (Map<Integer, Map<Activity, Set<BehaviorControl<? super Villager>>>>) field.get(brain);
        int count = 0;
        for (Map<Activity, Set<BehaviorControl<? super Villager>>> byActivity : byPriority.values()) {
            Set<BehaviorControl<? super Villager>> behaviours = byActivity.get(Errand.ACTIVITY);
            if (behaviours != null) {
                count += behaviours.size();
            }
        }
        return count;
    }
}
