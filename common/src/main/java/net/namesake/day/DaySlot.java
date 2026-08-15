package net.namesake.day;

import net.minecraft.world.entity.schedule.Activity;
import net.minecraft.world.entity.schedule.Schedule;

/**
 * <b>The eight slots of a villager's day, and the one table that is the same for everybody.</b>
 * {@code DESIGN.md} §7.
 *
 * <h2>Global, and this is the half of §7 that is</h2>
 *
 * <p>§7 said two things that are not the same claim: <i>slot boundaries are <b>global</b></i>, and
 * <i>the boundary offset derives from industry, jittered by tradition</i>. Both are true and they
 * are about different objects, so the section is now written to say which is which and this class
 * is one of the two halves.
 *
 * <p><b>The table is global.</b> Which stretch of the day is {@link #LABOUR_I} and what that stretch
 * means is one static table, identical for every villager in every settlement in every world. That
 * is not tidiness — it is the entire mechanism behind §7's at-a-glance test. <i>Four facts and the
 * player can predict any NPC in the world</i> is only reachable if there are four facts to learn.
 * A per-villager table would be four hundred days happening at once, and nothing about it could be
 * learned by watching.
 *
 * <p><b>The moment a given villager crosses a boundary is not.</b> That is {@link DayPlan#offsetOf},
 * and it exists so four hundred villagers do not path in the same tick. See that method for the
 * spread and its floor.
 *
 * <h2>We never call {@code brain.setSchedule}, so this table does not decide anything</h2>
 *
 * <p>Vanilla decides what a villager is <i>doing</i> — {@link Schedule#VILLAGER_DEFAULT} and
 * {@code UpdateActivityFromSchedule}, untouched. This table decides what <i>we</i> may say about it,
 * and its only job is to be finer-grained than vanilla's five keyframes so a slot can be steered
 * without the whole activity moving.
 *
 * <p><b>Which vanilla activity a slot sits inside is asked rather than stored.</b>
 * {@link #vanillaActivity} calls {@code Schedule.VILLAGER_DEFAULT.getActivityAt}, so there is no
 * copy of vanilla's answer in this file to drift from it. That is the ruling this project has made
 * five times — {@code Settlement.culture} deleted for being a cache, {@code Persona.professionId}
 * deleted for duplicating {@code getVillagerData()}, the Notice Board derived rather than stored —
 * arriving at a table of enum constants. It also keeps this class loadable without a bootstrapped
 * registry, which is what lets most of the day-plan tests run in ten milliseconds.
 *
 * <h2>"Four are exact keyframes" — three of the eight starts are, and the fourth is inside a slot</h2>
 *
 * <p>Stated precisely because §7's own sentence cannot be checked as written. {@code VILLAGER_DEFAULT}
 * has five keyframes — <b>10, 2000, 9000, 11000, 12000</b> — and this table has eight slot starts:
 * 0, 2000, 5000, 6000, 7000, 9000, 11000, 14000. The exact matches are <b>2000, 9000 and 11000</b>.
 * The fourth keyframe that matters, <b>12000</b>, is not a slot start at all: it is the arrow in
 * §7's <i>IDLE→REST</i> row, in the middle of {@link #HEARTH}, and it is the boundary session 14
 * splits into 6a and 6b.
 *
 * <p>The fifth, <b>10</b>, is the one worth knowing about. {@code Timeline#getValueAt} <i>wraps</i>
 * before its first keyframe — it takes the last keyframe's value — so {@code getActivityAt(0)}
 * answers <b>{@code REST}</b> rather than {@code IDLE}, and a vanilla villager is still asleep for
 * the first ten ticks of {@link #DAWN}. Nothing here depends on it and nothing should; it is
 * recorded because §7's table says {@code IDLE} against {@code 0–1999} and that is true of 1,990 of
 * those 2,000 ticks. {@code DaySlotTest.theKeyframesAreVanillasOwn} asserts all of it against the
 * real schedule rather than against this comment.
 */
public enum DaySlot {

    /** First light. Vanilla is idling; nothing anchors a villager anywhere. */
    DAWN(0),

    /**
     * The first working stretch, and one of the two the industry standoff applies in.
     *
     * <p>Begins on {@code Schedule.WORK_START_TIME}, which is a real {@code VILLAGER_DEFAULT}
     * keyframe rather than a number that resembles one. Written as the literal 2000 rather than as
     * that constant so this enum does not force Minecraft's registries to bootstrap; the test
     * asserts the two are equal.
     */
    LABOUR_I(2000),

    /** Session 14's {@link Errand#HAUL}: eleven o'clock, and the roads fill. */
    HAUL(5000),

    /** Session 14's {@link Errand#NOON}: noon, and the hearths fill. */
    NOON(6000),

    /** The second working stretch, and the other one the standoff applies in. */
    LABOUR_II(7000),

    /**
     * The ring at the bell. Vanilla's {@code MEET}.
     *
     * <p><b>§7's ruled steering for this slot is a walk target within six blocks of the bell, and
     * vanilla already does exactly that</b> — {@code SetWalkTargetFromBlockMemory(MEETING_POINT,
     * speed, closeEnough = 6, …)} at {@code VillagerGoalPackages.java:139}, with
     * {@code StrollAroundPoi(MEETING_POINT, 0.4F, 40)} beside it. Writing ours would be a second
     * answer to a question that already has one, which this project has ruled against five times.
     * So this slot ships no code and the reason is recorded rather than left as a blank row.
     */
    COMMONS(9000),

    /**
     * Hearths fill, then beds. <b>Vanilla changes activity inside this slot, at
     * {@link #VIGIL_BEGINS_AT}</b>, and that is §7's 6a/6b line: 6a is left entirely to vanilla and
     * 6b is when {@link Errand#WATCH} takes the bold outside while everybody else goes to bed.
     */
    HEARTH(11000),

    /** Streets empty. Sleepers sleep — §7 rules the steering <b>none</b> for them — and the watch stays out. */
    NIGHT(14000);

    /** Ticks in a Minecraft day. */
    public static final int DAY_LENGTH = 24000;

    /**
     * <b>The one boundary the plan acts on that is not a slot start: vanilla's own 12000
     * keyframe.</b> {@code DESIGN.md} §7's 6a/6b line.
     *
     * <p>{@code Schedule.VILLAGER_DEFAULT} turns {@code IDLE} into {@code REST} here, in the middle
     * of {@link #HEARTH} — so it is a moment vanilla already has rather than one this mod invents,
     * and it is the moment a village visibly goes indoors. {@link Errand#WATCH}'s window opens on
     * it, which is why the plan needs a crossing here and nowhere else inside a slot.
     *
     * <p><b>It is not a ninth slot deliberately.</b> §7's table has eight rows, the eight starts are
     * ruled numbers, and the whole mechanism behind the at-a-glance test is that there are few
     * enough facts to learn by watching. A ninth row would be a ninth fact for a boundary only one
     * villager in five ever crosses. {@code DaySlotTest.theKeyframesAreVanillasOwn} holds that this
     * number is one of vanilla's five and that it falls strictly inside {@link #HEARTH}.
     */
    public static final int VIGIL_BEGINS_AT = 12000;

    private static final DaySlot[] VALUES = values();

    private final int startsAt;

    DaySlot(int startsAt) {
        this.startsAt = startsAt;
    }

    /** The first tick of the day this slot covers, before any villager's own offset. */
    public int startsAt() {
        return startsAt;
    }

    /** The last tick of the day this slot covers. */
    public int endsAt() {
        return (ordinal() + 1 < VALUES.length ? VALUES[ordinal() + 1].startsAt : DAY_LENGTH) - 1;
    }

    /**
     * Which vanilla activity this slot <b>begins</b> in, asked of vanilla rather than stored.
     *
     * <p>{@link #HEARTH} spans a change — {@code IDLE} until 12000 and {@code REST} after — so the
     * answer is about its first tick, which is what session 14's 6a/6b split is about.
     *
     * <p><b>Not for the tick path.</b> {@code Timeline#getValueAt} caches an index in a field, so
     * this mutates shared state on {@code Schedule.VILLAGER_DEFAULT}. It is harmless on the server
     * thread, where vanilla calls it every tick anyway, and it is why this is used by the debug
     * command and the tests and by nothing that runs per villager per tick.
     */
    public Activity vanillaActivity() {
        return Schedule.VILLAGER_DEFAULT.getActivityAt(startsAt);
    }

    /**
     * True while this slot is one of the two the industry standoff applies in.
     *
     * <p>Not every {@code WORK} slot: {@link #HAUL} and {@link #NOON} are vanilla {@code WORK} too,
     * and they belong to session 14's {@code ERRAND}. Steering a villager to stand still during a
     * slot whose whole point is that they are carrying something somewhere would be this session
     * spending session 14's budget.
     */
    public boolean isLabour() {
        return this == LABOUR_I || this == LABOUR_II;
    }

    /**
     * True while an {@link Errand} can begin in this slot — the four rows {@code DESIGN.md} §7 hands
     * session 14.
     *
     * <p><b>Deliberately disjoint from {@link #isLabour}</b>, and {@code DaySlotTest} holds that:
     * the standoff and an errand are two answers to the same question about the same villager, and a
     * slot that was both would have session 13 vetoing a walk target session 14 had just written.
     *
     * <p>It is a <i>may</i> rather than a <i>does</i>, and both of the reasons are in
     * {@link DayPlan#errandFor}: {@link #HEARTH} only carries one past {@link #VIGIL_BEGINS_AT}, and
     * {@link #HEARTH} and {@link #NIGHT} only carry one for the villagers who stand watch. This is
     * the cheap test that ends the question for the other twenty hours of the day.
     */
    public boolean mayCarryAnErrand() {
        return this == HAUL || this == NOON || this == HEARTH || this == NIGHT;
    }

    /**
     * Which slot a time of day falls in.
     *
     * <p>Takes the raw day time and reduces it here rather than trusting a caller to have done it,
     * because {@code Level#getDayTime} counts from world creation and is a long. Negative inputs are
     * folded rather than refused: a world whose time has been set backwards is a thing a player can
     * do with one command, and it must not throw inside a server tick.
     */
    public static DaySlot at(long dayTime) {
        int time = (int) Math.floorMod(dayTime, (long) DAY_LENGTH);
        // Eight entries, walked backwards. A binary search over eight is slower than this and a
        // lookup table of 24,000 bytes to save six compares is a cache line spent on nothing.
        for (int i = VALUES.length - 1; i > 0; i--) {
            if (time >= VALUES[i].startsAt) {
                return VALUES[i];
            }
        }
        return DAWN;
    }

    /** How many ticks into this slot a given day time is. */
    public int elapsedAt(long dayTime) {
        int time = (int) Math.floorMod(dayTime, (long) DAY_LENGTH);
        return time - startsAt;
    }
}
