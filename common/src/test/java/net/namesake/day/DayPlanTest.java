package net.namesake.day;

import net.minecraft.core.BlockPos;
import net.namesake.npc.Persona;
import net.namesake.testing.MethodBody;
import net.namesake.testing.ModClasses;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The arithmetic of {@code DESIGN.md} §7, held where a unit test can hold it.
 *
 * <p>Everything about the day plan that is a property of numbers lives here; everything that is a
 * property of a running engine — whether a villager actually stands still, whether the work sound
 * plays — is a harness leg, because the whole mechanic rests on what vanilla behaviours do and no
 * unit test can have an opinion about that. {@code WORKPLAN.md}'s line between the two instruments,
 * applied.
 */
class DayPlanTest {

    private static Persona persona(long id, int industry, int tradition) {
        byte[] traits = new byte[Persona.TRAIT_COUNT];
        traits[Persona.INDUSTRY] = (byte) industry;
        traits[Persona.TRADITION] = (byte) tradition;
        return Persona.create(new UUID(0x5EEDL, id), 0L).placed(1, 1, (byte) 0).withTraits(traits);
    }

    // --- the wall that is a wall because it is in code -------------------------------------------

    /**
     * <b>{@code DESIGN.md} §7: the {@code spread ≥ 64} floor is enforced in code, not config, and
     * <i>"never expose 'make everyone punctual' as a config option"</i>.</b>
     *
     * <p>Two claims, checked two ways. That the field cannot be assigned is reflection; that nothing
     * in the class consults the outside world for it is bytecode, because a {@code static final int}
     * initialised from {@code Integer.getInteger("namesake.spread", 64)} is still final and still
     * configurable. The second check is the one that matters and it is the one a reflective test
     * would have missed.
     */
    @Test
    @DisplayName("the spread floor is in code and cannot be lowered by a property or a config")
    void theSpreadFloorIsNotConfigurable() throws Exception {
        Field floor = DayPlan.class.getField("SPREAD_FLOOR");
        assertTrue(Modifier.isStatic(floor.getModifiers()) && Modifier.isFinal(floor.getModifiers()),
                "DayPlan.SPREAD_FLOOR must be static final. DESIGN.md §7 rules it a wall.");
        assertTrue(DayPlan.SPREAD >= DayPlan.SPREAD_FLOOR,
                "DayPlan.SPREAD is below its own floor, which is the one thing the floor exists to "
                        + "stop. 400 villagers over fewer than 64 ticks is 400 path requests in a "
                        + "handful of ticks.");

        MethodBody clinit = MethodBody.of(DayPlan.class, "<clinit>");
        for (String door : List.of("java/lang/System#getProperty", "java/lang/Integer#getInteger",
                "java/lang/System#getenv", "java/lang/Long#getLong")) {
            String owner = door.substring(0, door.indexOf('#'));
            String method = door.substring(door.indexOf('#') + 1);
            assertFalse(clinit.invokes(classFor(owner), method), () ->
                    "DayPlan's static initialiser calls " + door + ", so the spread — and every "
                            + "other wall in this class — can be set from outside the source. "
                            + "DESIGN.md §7: enforced in code, not config. Never expose 'make "
                            + "everyone punctual' as a config option.");
        }
    }

    private static Class<?> classFor(String internalName) throws ClassNotFoundException {
        return Class.forName(internalName.replace('/', '.'));
    }

    /**
     * The floor's own arithmetic, which is where 64 comes from.
     *
     * <p>It is not a round number. Four hundred villagers divided by the transition governor's eight
     * a tick is fifty, and sixty-four is the next power of two above it — so the mean arrival rate
     * stays under the drain rate with room, and the queue is stable by construction rather than by
     * measurement. {@code DayPlanDistributionTest} measures what a real population does inside it.
     */
    @Test
    @DisplayName("the spread is wide enough that the governor can drain 400 villagers")
    void theSpreadOutrunsTheGovernor() {
        assertTrue(400.0 / DayPlan.SPREAD <= Steering.TRANSITIONS_PER_TICK,
                "400 villagers over a spread of " + DayPlan.SPREAD + " arrive faster than the "
                        + "governor serves them, so the queue grows without bound.");
    }

    // --- the offset --------------------------------------------------------------------------

    @Test
    @DisplayName("an offset never leaves the spread, at any trait combination the roll can produce")
    void everyOffsetIsInsideTheSpread() {
        for (int i = -100; i <= 100; i += 1) {
            for (int t = -100; t <= 100; t += 7) {
                for (DaySlot slot : DaySlot.values()) {
                    final int industry = i;
                    final int tradition = t;
                    final int offset = DayPlan.offsetOf(
                            persona(industry * 211L + tradition, industry, tradition), slot);
                    assertTrue(offset >= 0 && offset < DayPlan.SPREAD,
                            () -> "industry " + industry + ", tradition " + tradition + ", " + slot
                                    + " produced offset " + offset + ", outside [0, "
                                    + DayPlan.SPREAD + "). Nothing here clamps, so an offset out of "
                                    + "range means the arithmetic can leave the range — see "
                                    + "DayPlan.offsetOf on why a clamp would be worse.");
                }
            }
        }
    }

    /**
     * <b>Industry decides the order, which is the mechanic being legible from outside.</b>
     *
     * <p>Compared at full tradition, because that is where the claim is exactly true: an
     * untraditional villager is placed by whim, and whim does not know how industrious anybody is.
     * That is the design, not a weakening of it — see {@link DayPlan#offsetOf}.
     */
    @Test
    @DisplayName("among punctual villagers, the industrious cross first")
    void industryDecidesTheOrder() {
        // Industry ascending, offsets non-increasing: a higher offset is a *later* crossing, so the
        // more industrious a villager is the smaller their offset has to be.
        int previous = DayPlan.SPREAD;
        for (int i = -100; i <= 100; i += 10) {
            final int industry = i;
            final int offset = DayPlan.offsetOf(persona(industry, industry, 100), DaySlot.LABOUR_I);
            final int before = previous;
            assertTrue(offset <= before, () -> "a villager at industry " + industry
                    + " crosses at offset " + offset + ", after a less industrious one at " + before
                    + ". The people who turn up first are supposed to be the people who work.");
            previous = offset;
        }
        assertEquals(0, DayPlan.offsetOf(persona(1, 100, 100), DaySlot.LABOUR_I),
                "the most industrious, most traditional villager possible should cross on the "
                        + "boundary itself");
        assertEquals(DayPlan.SPREAD - 1,
                DayPlan.offsetOf(persona(2, -100, 100), DaySlot.LABOUR_I),
                "the least industrious, most traditional villager possible should cross last");
    }

    @Test
    @DisplayName("tradition decides how far whim may move somebody, and it is a real spread")
    void traditionWidensTheWave() {
        Set<Integer> punctual = new HashSet<>();
        Set<Integer> erratic = new HashSet<>();
        for (long id = 0; id < 400; id++) {
            punctual.add(DayPlan.offsetOf(persona(id, 0, 100), DaySlot.LABOUR_I));
            erratic.add(DayPlan.offsetOf(persona(id, 0, -100), DaySlot.LABOUR_I));
        }
        assertEquals(1, punctual.size(),
                "four hundred equally industrious villagers at full tradition should all cross on "
                        + "the same tick — that is what being punctual means");
        assertTrue(erratic.size() > DayPlan.SPREAD / 2, () ->
                "four hundred equally industrious villagers at no tradition landed on only "
                        + erratic.size() + " distinct ticks. Whim is supposed to place them "
                        + "anywhere in the spread, and it is the half of the wave that does not "
                        + "care what the population's traits happen to look like.");
    }

    @Test
    @DisplayName("a villager's morning offset is not their afternoon one")
    void theWaveHasNoPermanentShape() {
        int differ = 0;
        for (long id = 0; id < 200; id++) {
            Persona who = persona(id, 20, -40);
            if (DayPlan.offsetOf(who, DaySlot.LABOUR_I) != DayPlan.offsetOf(who, DaySlot.LABOUR_II)) {
                differ++;
            }
        }
        final int moved = differ;
        assertTrue(moved > 150, () -> "only " + moved + " of 200 villagers have a different "
                + "offset in the morning and the afternoon. Seed the offset with the slot, or the "
                + "same fourteen people are the same fourteen ticks late for ever and the wave has "
                + "a permanent shape a player would learn instead of a rhythm.");
    }

    @Test
    @DisplayName("an offset is stable — the same villager crosses at the same moment every day")
    void theOffsetIsStable() {
        Persona who = persona(7, 33, -12);
        int first = DayPlan.offsetOf(who, DaySlot.LABOUR_I);
        for (int i = 0; i < 100; i++) {
            assertEquals(first, DayPlan.offsetOf(who, DaySlot.LABOUR_I),
                    "offsetOf is not a pure function of the persona and the slot");
        }
    }

    // --- the slot a villager is actually in -----------------------------------------------------

    /**
     * <b>The global table and the per-villager crossing, as one assertion.</b>
     *
     * <p>This is the test that makes §7's two claims distinguishable: two villagers standing next to
     * each other are in <i>different slots</i> for up to a spread after every boundary, and in the
     * same one for the rest of the day. If the LUT were per-villager the second half would fail; if
     * the crossing were global the first half would.
     */
    @Test
    @DisplayName("everybody shares the table and nobody shares the moment they cross it")
    void theTableIsGlobalAndTheCrossingIsNot() {
        Persona early = persona(1, 100, 100);
        Persona late = persona(2, -100, 100);

        long boundary = DaySlot.LABOUR_I.startsAt();
        assertEquals(DaySlot.LABOUR_I, DayPlan.slotFor(early, boundary),
                "the most industrious villager crosses on the boundary itself");
        assertEquals(DaySlot.DAWN, DayPlan.slotFor(late, boundary),
                "the least industrious villager has not crossed yet, so they are still in DAWN — "
                        + "which is the transition wave, and is the whole reason four hundred "
                        + "villagers do not path in one tick");
        assertNotEquals(DayPlan.slotFor(early, boundary), DayPlan.slotFor(late, boundary));

        long wellInside = boundary + DayPlan.SPREAD;
        assertEquals(DaySlot.LABOUR_I, DayPlan.slotFor(early, wellInside));
        assertEquals(DaySlot.LABOUR_I, DayPlan.slotFor(late, wellInside),
                "once the spread has passed, everybody is in the same slot — the table is global");
    }

    @Test
    @DisplayName("the first ticks of a Minecraft day fold back into NIGHT, not off the end of the table")
    void theDayWrapsRatherThanThrowing() {
        Persona late = persona(3, -100, 100);
        assertEquals(DaySlot.NIGHT, DayPlan.slotFor(late, 0L),
                "a villager who has not crossed into DAWN yet is still in last night's slot");
        for (long dayTime = -48000; dayTime <= 48000; dayTime += 137) {
            DayPlan.slotFor(late, dayTime);
        }
    }

    // --- diligence and the standoff ----------------------------------------------------------

    @Test
    @DisplayName("diligence is one comparison on one axis, and it does not change from day to day")
    void diligenceIsStable() {
        assertTrue(DayPlan.isDiligent(persona(1, DayPlan.INDUSTRY_TO_WORK, 0)),
                "the threshold includes its own value");
        assertFalse(DayPlan.isDiligent(persona(2, DayPlan.INDUSTRY_TO_WORK - 1, 0)));
    }

    /**
     * The three vanilla comparisons, restated as a table.
     *
     * <p>Every row is a distance and the behaviour that measures it. A change to
     * {@link DayPlan#isAStandoff} that made any of these disagree with the engine would produce a
     * villager who oscillates rather than one who stands still, and it would look fine.
     */
    @Test
    @DisplayName("the standoff test refuses a point either vanilla behaviour would act on")
    void theStandoffTestIsBothMetrics() {
        BlockPos job = new BlockPos(0, 64, 0);

        assertFalse(DayPlan.isAStandoff(job, job.offset(1, 0, 0)),
                "one block away is inside WorkAtPoi's 1.73, so the villager would work");
        assertFalse(DayPlan.isAStandoff(job, job.offset(3, 0, 0)),
                "three blocks away is inside StrollAroundPoi's 4, which would send them wandering");
        assertTrue(DayPlan.isAStandoff(job, job.offset(5, 0, 0)),
                "five blocks away clears StrollAroundPoi and is Manhattan 5, well inside "
                        + "SetWalkTargetFromBlockMemory's 9");

        // The finding this session opened on: the two guards use different metrics, and DESIGN.md
        // §7's "3-8 m" band was written in the wrong one.
        assertFalse(DayPlan.isAStandoff(job, job.offset(6, 0, 6)),
                "six east and six north is Euclidean 8.49 — inside §7's ruled 3-8 m band — and "
                        + "Manhattan 12, so SetWalkTargetFromBlockMemory hauls them back. That is "
                        + "the composition failure this session found: the tolerance is Manhattan "
                        + "and the band was Euclidean.");
        assertTrue(DayPlan.isAStandoff(job, job.offset(8, 0, 0)),
                "eight blocks straight out is Manhattan 8 and vanilla leaves it alone");

        assertFalse(DayPlan.isAStandoff(job, job.offset(5, 5, 0)),
                "height counts toward Manhattan, so a standoff up a hill is not one");
    }

    /**
     * <b>Arm's reach is {@code WorkAtPoi}'s own 1.73, and the case that pins it is the awkward one.</b>
     *
     * <p>The first version of this test asserted one block in (true), one diagonal (true) and two
     * blocks out (false) — and a deliberate breakage that replaced 1.73 with <b>2.0</b> passed all
     * three, because two blocks out is 2.06 from the block's centre and is outside both. A test that
     * only asks about its own extremes cannot notice a constant moving between them, which is
     * {@code PersonalityDistributionTest}'s opening paragraph arriving somewhere new.
     *
     * <p>So the pin is a position that is <i>between</i> the two: one across and two up is
     * <b>1.803</b> from the centre — outside 1.73 and inside 2.0. It is also a real position, which
     * is why it is the one chosen: a villager standing on a block above their workstation.
     */
    @Test
    @DisplayName("arm's reach is WorkAtPoi's own 1.73, and a round 2.0 would not do")
    void armsReachIsTheEnginesNumber() {
        BlockPos job = new BlockPos(0, 64, 0);
        assertTrue(DayPlan.isWithinArmsReach(job, job.offset(1, 0, 0)));
        assertTrue(DayPlan.isWithinArmsReach(job, job.offset(1, 0, 1)),
                "diagonally adjacent is 1.5 from the centre once the half-block on each axis is "
                        + "taken into account, which is inside 1.73 — and it is the reason a "
                        + "villager standing on the corner of their workstation still works");
        assertFalse(DayPlan.isWithinArmsReach(job, job.offset(2, 0, 0)));

        assertFalse(DayPlan.isWithinArmsReach(job, job.offset(1, 2, 0)),
                "one across and two up is 1.803 from the workstation's centre. It is outside "
                        + "WorkAtPoi's 1.73 and inside a round 2.0, so it is the only kind of case "
                        + "that can tell the engine's constant from a tidier one — and a breakage "
                        + "pass proved it, because replacing 1.73 with 2.0 turned nothing red.");
    }

    @Test
    @DisplayName("a villager stands in the same place every day, and neighbours do not share one")
    void aStandoffIsOnePlacePerVillager() {
        Persona who = persona(11, -20, 0);
        int first = DayPlan.preferredStandoff(who);
        assertEquals(first, DayPlan.preferredStandoff(who), "§7's first legibility law is one place");

        Set<Integer> spots = new HashSet<>();
        for (long id = 0; id < 60; id++) {
            spots.add(DayPlan.preferredStandoff(persona(id, -20, 0)));
        }
        assertTrue(spots.size() >= DayPlan.STANDOFF_OFFSETS.length / 2, () ->
                "sixty villagers chose only " + spots.size() + " of "
                        + DayPlan.STANDOFF_OFFSETS.length + " standoff spots. Two lazy villagers "
                        + "standing on each other reads as a bug rather than as a choice.");
    }

    // --- the path gate -------------------------------------------------------------------------

    @Test
    @DisplayName("the path gate opens one tick in seven, and lets about a seventh of a village through")
    void thePathGateIsASeventh() {
        List<Persona> village = new ArrayList<>();
        for (long id = 0; id < 700; id++) {
            village.add(persona(id, 0, 0));
        }
        for (long t = 0; t < DayPlan.PATH_GATE_PERIOD; t++) {
            final long tick = t;
            final long open = village.stream().filter(p -> DayPlan.pathGateOpen(p, tick)).count();
            assertTrue(open > 700 / DayPlan.PATH_GATE_PERIOD / 2
                            && open < 700 / DayPlan.PATH_GATE_PERIOD * 2,
                    () -> "tick " + tick + " opened the gate for " + open + " of 700 villagers, "
                            + "against an expected hundred. A gate that lets everybody through in "
                            + "one tick is not a gate.");
        }
        Persona who = persona(3, 0, 0);
        long openings = 0;
        for (long tick = 0; tick < 70; tick++) {
            if (DayPlan.pathGateOpen(who, tick)) {
                openings++;
            }
        }
        assertEquals(10, openings, "one villager's gate should open exactly ten times in seventy ticks");
    }

    // --- nothing here is persisted --------------------------------------------------------------

    /**
     * <b>{@code DESIGN.md} §7: a plan stores WHAT, never WHEN — read one turn further.</b>
     *
     * <p>If the plan is derived from a persona, a settlement and a day then it persists nothing at
     * all, and {@code SocialValueLedgerTest} has nothing to classify. This is what stops that
     * quietly reverting: a record in {@code net.namesake.day} that declares a codec is a record on
     * its way into a save file, and it would owe hard rule 1 a schema bump, a datafixer and a load
     * test in the same session.
     */
    @Test
    @DisplayName("the day plan persists nothing, so there is no schema to move")
    void theDayPlanIsDerived() {
        List<String> persisted = new ArrayList<>();
        for (Class<?> candidate : ModClasses.all()) {
            if (!candidate.getPackageName().startsWith("net.namesake.day")) {
                continue;
            }
            for (Field field : candidate.getDeclaredFields()) {
                if (com.mojang.serialization.Codec.class.isAssignableFrom(field.getType())) {
                    persisted.add(candidate.getName() + "." + field.getName());
                }
            }
        }
        assertTrue(persisted.isEmpty(), () -> """
                These day-plan types declare a Codec, so they are on their way into a save file:
                  %s
                DESIGN.md §7 rules that a plan stores WHAT and never WHEN, and session 13 read that \
                one turn further: a plan derived from a persona, a settlement and a day persists \
                nothing and has nothing for the rule 5 ledger to classify. If a field genuinely has \
                to be stored, that is schema 9 — a datafixer, a load test against a pre-change save, \
                and a named non-display consumer in the same session.""".formatted(
                String.join("\n  ", persisted)));
    }
}
