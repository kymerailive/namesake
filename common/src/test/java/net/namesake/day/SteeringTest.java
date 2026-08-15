package net.namesake.day;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.schedule.Activity;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>The veto, which is the one thing in session 13 that can stop a villager moving.</b>
 *
 * <p>Erasing {@code WALK_TARGET} is exactly how the bug this session inherited happens — a villager
 * who never gets a walk target never gets a path, never opens a door, and never leaves the building
 * they are in. Every other assertion in the day plan is about a villager standing still on purpose,
 * and a villager standing still by accident looks identical to all of them. So the five clauses that
 * keep the veto narrow are the ones most worth holding, and they are held here.
 *
 * <p>{@code Steering.declines} exists as a pure function for this reason and no other: the version
 * that took a brain, a level and an entity could only be tested by a harness leg costing six minutes
 * a case, and a guard that expensive to test is a guard nobody adds a case to.
 */
class SteeringTest {

    @BeforeAll
    static void bootstrapMinecraft() {
        // Activity is a registry entry. Idempotent by Bootstrap's own guard.
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static final BlockPos JOB = new BlockPos(100, 64, 100);

    /** The case the veto exists for: a labour slot, working, unbothered, target on the workstation. */
    private static boolean declinesTheJobSite() {
        return Steering.declines(DaySlot.LABOUR_I, Activity.WORK, false, false, JOB, JOB);
    }

    @Test
    @DisplayName("the veto fires on the one case it is for")
    void theVetoFiresOnTheJobSite() {
        assertTrue(declinesTheJobSite(),
                "a lazy villager in a labour slot, working, with vanilla pointing them back at "
                        + "their own workstation, is the whole of what this mechanic is");
        assertTrue(Steering.declines(DaySlot.LABOUR_II, Activity.IDLE, false, false,
                        JOB.offset(1, 0, 0), JOB),
                "one block off the workstation still counts — SetWalkTargetFromBlockMemory's near "
                        + "case aims at the block rather than at the point of interest");
    }

    /**
     * <b>Clause by clause, and each one names the villager it would otherwise strand.</b>
     */
    @Test
    @DisplayName("the veto is narrow: five clauses, and each refuses on its own")
    void theVetoIsNarrow() {
        // 1. Only in a labour slot.
        for (DaySlot slot : DaySlot.values()) {
            boolean declines = Steering.declines(slot, Activity.WORK, false, false, JOB, JOB);
            assertTrue(declines == slot.isLabour(), () -> slot + ": the plan has an opinion during "
                    + "the two labour slots and no opinion anywhere else. HAUL and NOON are session "
                    + "14's ERRAND and must not inherit this.");
        }

        // 2. Only in a steerable activity — an allowlist, so a new one is refused by default.
        for (Activity activity : List.of(Activity.HIDE, Activity.REST, Activity.PANIC,
                Activity.RAID, Activity.PRE_RAID, Activity.MEET, Activity.PLAY)) {
            assertFalse(Steering.declines(DaySlot.LABOUR_I, activity, false, false, JOB, JOB),
                    () -> "a villager in " + activity + " must keep every walk target it is given. "
                            + "HIDE is the one that matters most: LocateHidingPlace requires "
                            + "WALK_TARGET to be absent and is the only writer of HIDING_PLACE, "
                            + "which is the only way out of the bell lock — so a veto here is a "
                            + "villager stuck in their house for the rest of the world's life.");
        }
        assertTrue(Steering.declines(DaySlot.LABOUR_I, Activity.IDLE, false, false, JOB, JOB),
                "IDLE is on the allowlist deliberately: a villager idling through a labour slot is "
                        + "exactly who the standoff is for, and this session shipped `isActive(WORK)` "
                        + "for an hour before the harness proved it steered nobody");
        assertFalse(Steering.declines(DaySlot.LABOUR_I, null, false, false, JOB, JOB),
                "a brain with no non-core activity at all is not a brain to take a walk target from");

        // 3. Never while somebody is trading with them.
        assertFalse(Steering.declines(DaySlot.LABOUR_I, Activity.WORK, true, false, JOB, JOB),
                "LookAndFollowTradingPlayerSink writes a walk target every tick while a counter is "
                        + "open; cancelling it drags a villager out from under their own trade screen");

        // 4. Never while hurt or while something hostile is visible.
        assertFalse(Steering.declines(DaySlot.LABOUR_I, Activity.WORK, false, true, JOB, JOB),
                "HURT_BY_ENTITY and NEAREST_HOSTILE are set the tick *before* VillagerPanicTrigger "
                        + "changes the activity, so clause 2 has not caught up yet and this is what "
                        + "stops a villager being pinned in place while something is hitting them");

        // 5. Only a target that is the job site.
        assertFalse(Steering.declines(DaySlot.LABOUR_I, Activity.WORK, false, false,
                        JOB.offset(3, 0, 0), JOB),
                "three blocks off is a stroll, and a stroll is not ours to refuse");
        assertFalse(Steering.declines(DaySlot.LABOUR_I, Activity.WORK, false, false,
                        JOB.offset(0, 6, 0), JOB),
                "a bed upstairs is somewhere else, and height counts");
    }

    /**
     * <b>The bell lock repair, and the three clauses that keep it off everybody else.</b>
     *
     * <p>Ruled by the owner at the close of session 13. It reaches into vanilla state the mod does
     * not own, so what it does has to be exactly right: it runs {@code SetHiddenState}'s own exit at
     * a villager {@code SetHiddenState} cannot reach, and it does that only for a villager who has
     * been in {@code HIDE} with a bell memory and <b>no hiding place</b> for a full in-game minute.
     * A villager who is merely hiding — which is what a bell is supposed to do — must be left alone.
     */
    @Test
    @DisplayName("the bell lock repair fires on the deadlock and on nothing else")
    void theBellLockRepairIsNarrow() {
        long patient = Steering.BELL_LOCK_PATIENCE;

        assertTrue(Steering.shouldUnstick(Activity.HIDE, true, false, patient),
                "hiding, a bell memory, no hiding place, and a minute gone is the deadlock: "
                        + "SetHiddenState needs HIDING_PLACE before it will erase HEARD_BELL_TIME, "
                        + "and LocateHidingPlace is the only writer of one");

        assertFalse(Steering.shouldUnstick(Activity.HIDE, true, true, patient),
                "a villager WITH a hiding place is not stuck — SetHiddenState can run, and it will "
                        + "time out on its own three hundred ticks after the bell. This is the "
                        + "clause that keeps the repair off every villager who is simply hiding.");
        assertFalse(Steering.shouldUnstick(Activity.HIDE, true, false, patient - 1),
                "one tick short of the patience");

        // PINNED TO VANILLA'S NUMBER, NOT TO OUR OWN. The line above reads BELL_LOCK_PATIENCE, so
        // it moves whenever the constant does — a breakage that set the patience to zero passed it,
        // because "one tick short of zero" is minus one and still refuses. That is the same
        // self-referential failure as a slot table asserting each slot begins where the last one
        // ends, and it took a breakage pass to see it both times. So the floor is a literal, and
        // the literal is vanilla's.
        assertTrue(Steering.BELL_LOCK_PATIENCE >= 300,
                "SetHiddenState.HIDE_TIMEOUT is 300 — vanilla's own view of when a villager should "
                        + "have finished hiding, and the comparison its own exit fires on. A "
                        + "patience below that would have the mod overruling the engine rather than "
                        + "finishing what the engine could not start.");
        assertFalse(Steering.shouldUnstick(Activity.HIDE, true, false, 299),
                "at 299 ticks vanilla's own SetHiddenState has not yet timed out, so a villager "
                        + "who is simply hiding must be left entirely alone — this is a literal "
                        + "rather than BELL_LOCK_PATIENCE - 1 so that shortening the patience turns "
                        + "it red instead of moving it");
        assertFalse(Steering.shouldUnstick(Activity.WORK, true, false, patient),
                "not hiding at all — a stale bell memory on a working villager is harmless, because "
                        + "ReactToBell only re-asserts HIDE, and something already took them out");
        assertFalse(Steering.shouldUnstick(Activity.HIDE, false, false, patient),
                "hiding with no bell memory is a raid, which has its own exit and is not ours");
        assertFalse(Steering.shouldUnstick(null, true, false, patient),
                "a brain with no non-core activity is not one to rewrite memories on");
    }

    /**
     * <b>The narrowing is what makes it safe, so removing any one clause has to be visible.</b>
     *
     * <p>This is the shape hard rule 3 asks for, written as a test rather than as a breakage pass:
     * for each clause, the case it protects must currently be refused. A change that widened the
     * veto would turn exactly one of these red and name it.
     */
    @Test
    @DisplayName("every clause is load-bearing — the base case fires and each variation does not")
    void everyClauseChangesTheAnswer() {
        assertTrue(declinesTheJobSite(), "the base case must fire, or the rest proves nothing");

        record Variation(String what, boolean declines) {
        }
        List<Variation> variations = List.of(
                new Variation("outside a labour slot",
                        Steering.declines(DaySlot.NIGHT, Activity.WORK, false, false, JOB, JOB)),
                new Variation("hiding",
                        Steering.declines(DaySlot.LABOUR_I, Activity.HIDE, false, false, JOB, JOB)),
                new Variation("trading",
                        Steering.declines(DaySlot.LABOUR_I, Activity.WORK, true, false, JOB, JOB)),
                new Variation("threatened",
                        Steering.declines(DaySlot.LABOUR_I, Activity.WORK, false, true, JOB, JOB)),
                new Variation("walking somewhere else",
                        Steering.declines(DaySlot.LABOUR_I, Activity.WORK, false, false,
                                JOB.offset(5, 0, 0), JOB)));

        for (Variation variation : variations) {
            assertFalse(variation.declines(), () -> "changing only \"" + variation.what()
                    + "\" left the veto firing, so that clause is not doing anything. Every one of "
                    + "them is the difference between a villager standing still on purpose and a "
                    + "villager who cannot move.");
        }
    }
}
