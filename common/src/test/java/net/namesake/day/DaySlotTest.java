package net.namesake.day;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.schedule.Activity;
import net.minecraft.world.entity.schedule.Schedule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>{@code DESIGN.md} §7's slot table, held against the engine rather than against its own comment.</b>
 *
 * <p>The whole day plan rests on the claim that these eight ranges line up with what vanilla is
 * already doing, and a table that merely <i>claims</i> to agree with {@code Schedule.VILLAGER_DEFAULT}
 * is the kind of thing that stops agreeing silently — session 11's advance table, which had five of
 * its numbers wrong on the first run that actually asked the engine. So this bootstraps Minecraft's
 * registries and reads the real schedule.
 *
 * <p>It is the only test in {@code net.namesake.day} that needs the bootstrap, which is why
 * {@link DaySlot} asks vanilla for an activity rather than storing one: everything else in the
 * package loads in a plain JVM and runs in milliseconds.
 */
class DaySlotTest {

    @BeforeAll
    static void bootstrapMinecraft() {
        // Activity and Schedule are registry entries, so touching either without this throws
        // "Not bootstrapped". Idempotent by Bootstrap's own guard.
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    /**
     * <b>§7 says "four are exact {@code Schedule.VILLAGER_DEFAULT} keyframes" and this is what that
     * sentence turns out to mean.</b>
     *
     * <p>Vanilla has five keyframes: 10, 2000, 9000, 11000 and 12000. This table has eight slot
     * starts. Three of the starts are exact keyframes — 2000, 9000, 11000 — and the fourth keyframe
     * that matters, 12000, is not a start at all: it is inside {@link DaySlot#HEARTH}, and it is the
     * line session 14 splits into 6a and 6b. The fifth, 10, is ten ticks after {@link DaySlot#DAWN}
     * begins.
     *
     * <p>All of that is asserted rather than described, so it cannot be quietly wrong.
     */
    @Test
    @DisplayName("the keyframes are vanilla's own, and there are three of them on a slot start")
    void theKeyframesAreVanillasOwn() {
        assertEquals(2000, Schedule.WORK_START_TIME,
                "DaySlot.LABOUR_I is written as the literal 2000 so the enum does not force a "
                        + "registry bootstrap. If vanilla ever moves WORK_START_TIME, this is where "
                        + "that is found.");
        assertEquals(Schedule.WORK_START_TIME, DaySlot.LABOUR_I.startsAt());

        Set<Integer> vanillaKeyframes = new LinkedHashSet<>();
        Activity previous = null;
        for (int dayTime = 0; dayTime < DaySlot.DAY_LENGTH; dayTime++) {
            Activity now = Schedule.VILLAGER_DEFAULT.getActivityAt(dayTime);
            if (previous != null && now != previous) {
                vanillaKeyframes.add(dayTime);
            }
            previous = now;
        }
        assertEquals(Set.of(10, 2000, 9000, 11000, 12000), vanillaKeyframes,
                "Schedule.VILLAGER_DEFAULT's keyframes have moved. Everything in DESIGN.md §7's "
                        + "table is written against these five.");

        Set<Integer> starts = new LinkedHashSet<>();
        for (DaySlot slot : DaySlot.values()) {
            starts.add(slot.startsAt());
        }
        Set<Integer> exact = new LinkedHashSet<>(starts);
        exact.retainAll(vanillaKeyframes);
        assertEquals(Set.of(2000, 9000, 11000), exact,
                "Exactly three slot starts are vanilla keyframes. DESIGN.md §7 says four, and the "
                        + "fourth is 12000 — which is inside HEARTH rather than starting a slot, "
                        + "and is session 14's 6a/6b line.");
        assertTrue(vanillaKeyframes.contains(12000)
                        && 12000 > DaySlot.HEARTH.startsAt() && 12000 < DaySlot.HEARTH.endsAt(),
                "the fourth keyframe should sit inside HEARTH, which is why §7 writes that row as "
                        + "IDLE→REST rather than as one activity");
    }

    /**
     * <b>The vanilla activity each slot begins in, read off the schedule.</b>
     *
     * <p>{@link DaySlot#DAWN} is the interesting row and it is the one §7 gets slightly wrong.
     * {@code Timeline#getValueAt} <i>wraps</i> before its first keyframe — it takes the last
     * keyframe's value — so at day time 0 the highest-valued timeline is {@code REST}, and a vanilla
     * villager is still asleep for the first ten ticks of the day. §7's table says {@code IDLE}
     * against {@code 0–1999}, which is true of 1,990 of those 2,000 ticks.
     *
     * <p>Nothing in this session depends on it, and it is asserted anyway: an undocumented ten-tick
     * disagreement between a design table and the engine is exactly the kind of thing session 14
     * would build on without noticing.
     */
    @Test
    @DisplayName("each slot begins in the vanilla activity DESIGN.md §7's table claims")
    void eachSlotBeginsInItsClaimedActivity() {
        assertSame(Activity.REST, DaySlot.DAWN.vanillaActivity(),
                "day time 0 is REST, not IDLE — Timeline.getValueAt wraps to the last keyframe "
                        + "before the first one, so vanilla villagers sleep through the first ten "
                        + "ticks of the day");
        assertSame(Activity.IDLE, Schedule.VILLAGER_DEFAULT.getActivityAt(10),
                "and IDLE begins at 10, which is where §7's DAWN row is actually true from");

        assertSame(Activity.WORK, DaySlot.LABOUR_I.vanillaActivity());
        assertSame(Activity.WORK, DaySlot.HAUL.vanillaActivity());
        assertSame(Activity.WORK, DaySlot.NOON.vanillaActivity());
        assertSame(Activity.WORK, DaySlot.LABOUR_II.vanillaActivity());
        assertSame(Activity.MEET, DaySlot.COMMONS.vanillaActivity());
        assertSame(Activity.IDLE, DaySlot.HEARTH.vanillaActivity());
        assertSame(Activity.REST, DaySlot.NIGHT.vanillaActivity());
    }

    /**
     * <b>The standoff only applies where vanilla is running {@code WORK}, and not to every slot that
     * is.</b>
     *
     * <p>{@link DaySlot#HAUL} and {@link DaySlot#NOON} are vanilla {@code WORK} too and belong to
     * session 14's {@code ERRAND}. Steering a villager to stand still during a slot whose whole
     * point is that they are carrying something somewhere would be session 13 spending session 14's
     * budget, and it would be invisible until session 14 tried to build on top of it.
     */
    @Test
    @DisplayName("the standoff applies to the two labour slots and to no other WORK slot")
    void labourIsNotEveryWorkSlot() {
        for (DaySlot slot : DaySlot.values()) {
            if (slot.isLabour()) {
                assertSame(Activity.WORK, slot.vanillaActivity(),
                        () -> slot + " is a labour slot but vanilla is not running WORK in it, so "
                                + "WorkAtPoi could not fire there whatever we did");
            }
        }
        assertTrue(DaySlot.LABOUR_I.isLabour() && DaySlot.LABOUR_II.isLabour());
        assertFalse(DaySlot.HAUL.isLabour(), "HAUL is session 14's ERRAND, not session 13's");
        assertFalse(DaySlot.NOON.isLabour(), "NOON is session 14's ERRAND, not session 13's");
    }

    /**
     * <b>The two mechanics are disjoint by slot, and that is a correctness property rather than
     * tidiness.</b>
     *
     * <p>They are two answers to the same question about the same villager. A slot that was both
     * would have session 13's veto erasing a walk target session 14 had just written, every tick,
     * and the villager would stand at their workstation while every counter in the mod said they
     * were on their way to the bell.
     */
    @Test
    @DisplayName("no slot is both a labour slot and an errand slot")
    void theTwoMechanicsDoNotOverlap() {
        for (DaySlot slot : DaySlot.values()) {
            assertFalse(slot.isLabour() && slot.mayCarryAnErrand(),
                    () -> slot + " is both a labour slot and an errand slot. The standoff holds a "
                            + "villager near their workstation by erasing walk targets that point "
                            + "at it; an errand sends them across the village. One of them would "
                            + "win every tick and nothing would go red.");
        }
        assertEquals(Set.of(DaySlot.HAUL, DaySlot.NOON, DaySlot.HEARTH, DaySlot.NIGHT),
                java.util.Arrays.stream(DaySlot.values())
                        .filter(DaySlot::mayCarryAnErrand)
                        .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new)),
                "DESIGN.md §7 hands session 14 slots 2, 3, 6b and 7. These are ruled rows.");
    }

    /**
     * <b>The 6a/6b line, and it is vanilla's own keyframe rather than a number this mod invented.</b>
     *
     * <p>§7's slot 6 is one row that spans an activity change — {@code IDLE→REST} — and the change
     * is at 12000. That is where {@link Errand#WATCH}'s window opens, so the plan needs a crossing
     * there; it is deliberately <b>not</b> a ninth slot, because the whole mechanism behind the
     * at-a-glance test is that there are few enough facts to learn by watching.
     */
    @Test
    @DisplayName("the vigil is vanilla's 12000 keyframe and it sits inside HEARTH, not on a boundary")
    void theVigilIsVanillasOwnKeyframe() {
        assertEquals(12000, DaySlot.VIGIL_BEGINS_AT, "DESIGN.md §7's 6a/6b line is a ruled number");
        assertSame(Activity.IDLE,
                Schedule.VILLAGER_DEFAULT.getActivityAt(DaySlot.VIGIL_BEGINS_AT - 1),
                "6a is vanilla IDLE — the plan leaves it alone entirely");
        assertSame(Activity.REST, Schedule.VILLAGER_DEFAULT.getActivityAt(DaySlot.VIGIL_BEGINS_AT),
                "and 6b is vanilla REST, which is what makes the watch visible: everybody else is "
                        + "walking home");
        assertSame(DaySlot.HEARTH, DaySlot.at(DaySlot.VIGIL_BEGINS_AT),
                "the vigil must fall inside HEARTH rather than starting a slot of its own");
        assertTrue(DaySlot.VIGIL_BEGINS_AT > DaySlot.HEARTH.startsAt()
                        && DaySlot.VIGIL_BEGINS_AT < DaySlot.HEARTH.endsAt(),
                "strictly inside, so 6a and 6b are both non-empty");
        for (DaySlot slot : DaySlot.values()) {
            assertNotEquals(DaySlot.VIGIL_BEGINS_AT, slot.startsAt(),
                    "the vigil is not a slot start and must not quietly become one — the table has "
                            + "eight rows and DESIGN.md §7 rules all eight");
        }
    }

    /**
     * <b>The ruled table, pinned to its literals.</b>
     *
     * <p>The first version of this test asserted that each slot begins where its predecessor ends —
     * and a breakage that moved {@code NOON} from 6000 to 6100 turned nothing red, because
     * {@link DaySlot#endsAt} is <i>derived</i> from the next slot's start and the assertion was
     * therefore true of any table at all. A tautological test is one of the three things hard rule 3
     * names, and it took a deliberate breakage to see it.
     *
     * <p>So the eight starts are held against {@code DESIGN.md} §7's own numbers. They are ruled,
     * and moving one is a design change rather than a refactor — session 14 hangs {@code ERRAND} off
     * two of these rows.
     */
    @Test
    @DisplayName("the eight slot starts are DESIGN.md §7's own, and the table tiles the day")
    void theTableTilesTheDay() {
        int[] ruled = {0, 2000, 5000, 6000, 7000, 9000, 11000, 14000};
        assertEquals(ruled.length, DaySlot.values().length,
                "DESIGN.md §7's table has eight rows");
        for (int i = 0; i < ruled.length; i++) {
            assertEquals(ruled[i], DaySlot.values()[i].startsAt(),
                    "slot " + i + " (" + DaySlot.values()[i] + ") does not begin where DESIGN.md §7 "
                            + "says it does. These are ruled numbers; four of them are vanilla "
                            + "keyframes and two of them are session 14's ERRAND boundaries.");
        }
        assertEquals(DaySlot.DAY_LENGTH - 1,
                DaySlot.values()[DaySlot.values().length - 1].endsAt());
        for (int i = 1; i < DaySlot.values().length; i++) {
            assertTrue(DaySlot.values()[i].startsAt() > DaySlot.values()[i - 1].startsAt(),
                    "slot " + i + " does not begin after its predecessor");
        }
        for (int dayTime = 0; dayTime < DaySlot.DAY_LENGTH; dayTime++) {
            DaySlot slot = DaySlot.at(dayTime);
            assertTrue(dayTime >= slot.startsAt() && dayTime <= slot.endsAt(),
                    "day time " + dayTime + " resolved to " + slot + ", which does not contain it");
        }
    }

    @Test
    @DisplayName("a day time from anywhere in a world's life resolves without throwing")
    void theClockIsFolded() {
        // Level#getDayTime counts from world creation, and /time set can move it backwards.
        assertSame(DaySlot.at(100), DaySlot.at(100 + DaySlot.DAY_LENGTH * 4000L));
        assertSame(DaySlot.at(100), DaySlot.at(100 - DaySlot.DAY_LENGTH * 4000L));
        assertSame(DaySlot.DAWN, DaySlot.at(-DaySlot.DAY_LENGTH));
        assertEquals(0, DaySlot.DAWN.elapsedAt(0));
        assertEquals(0, DaySlot.LABOUR_I.elapsedAt(DaySlot.LABOUR_I.startsAt()));
    }
}
