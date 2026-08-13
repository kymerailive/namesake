package net.namesake.npc;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.namesake.culture.Culture;
import net.namesake.settlement.Need;
import net.namesake.settlement.Settlement;
import net.namesake.settlement.Settlements;
import net.namesake.settlement.Specialty;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The three-layer roll, and specifically the two things about it that are easy to get wrong and
 * impossible to notice afterwards.
 *
 * <p><b>The spreads have to be real.</b> ±20 and ±25 are the ruled numbers; a layer that quietly
 * spends ±60 produces a village of strangers and a layer that spends ±5 produces a village of
 * clones, and both look plausible in a debug dump.
 *
 * <p><b>The clamp has to be to the axis bounds and to nothing else.</b> Clamping a child to its
 * parent's range is the obvious implementation, reads as conservative, and is wrong: it makes every
 * villager converge on their settlement's mean, which is the flat world this session exists to
 * avoid. The tests below pin down that an individual really can exceed their household in both
 * directions and really can reach ±100.
 */
class TraitRollTest {

    private static final ResourceLocation OVERWORLD = ResourceLocation.withDefaultNamespace("overworld");

    private static Settlement settlement(int id, Specialty specialty, int defensibility, byte[] needs) {
        return new Settlement(id, OVERWORLD, new BlockPos(id * 1000, 64, 0),
                specialty.id(), (byte) defensibility, needs);
    }

    private static Settlements withOne(Settlement settlement) {
        Settlements settlements = new Settlements();
        settlements.put(settlement);
        return settlements;
    }

    private static Persona placed(long seed, int settlementId, int householdId, Culture culture) {
        return Persona.create(new UUID(seed, seed * 31 + 7), 0L)
                .placed(settlementId, householdId, culture.id());
    }

    // --- the layers ------------------------------------------------------------------------------

    @Test
    @DisplayName("a layer never moves an axis further than its spread")
    void aLayerStaysInsideItsSpread() {
        byte[] parent = new byte[]{0, 30, -30, 60, -60, 10, -10, 5};
        for (int spread : new int[]{0, 5, TraitRoll.HOUSEHOLD_SPREAD, TraitRoll.INDIVIDUAL_SPREAD}) {
            for (long seed = 0; seed < 4000; seed++) {
                byte[] child = TraitRoll.jitter(parent, seed, spread);
                for (int axis = 0; axis < Persona.TRAIT_COUNT; axis++) {
                    int delta = child[axis] - parent[axis];
                    int bound = spread;
                    assertTrue(Math.abs(delta) <= bound,
                            "spread " + spread + " moved axis " + axis + " by " + delta);
                }
            }
        }
    }

    @Test
    @DisplayName("a layer spends its whole spread, in both directions, and averages nothing")
    void aLayerSpendsItsWholeSpread() {
        byte[] parent = new byte[Persona.TRAIT_COUNT];
        boolean sawTop = false;
        boolean sawBottom = false;
        long total = 0;
        int draws = 0;

        for (long seed = 0; seed < 20_000; seed++) {
            byte[] child = TraitRoll.jitter(parent, seed, TraitRoll.INDIVIDUAL_SPREAD);
            for (byte value : child) {
                sawTop |= value == TraitRoll.INDIVIDUAL_SPREAD;
                sawBottom |= value == -TraitRoll.INDIVIDUAL_SPREAD;
                total += value;
                draws++;
            }
        }

        assertTrue(sawTop, "the top of the ±25 range is never reached");
        assertTrue(sawBottom, "the bottom of the ±25 range is never reached");
        double mean = (double) total / draws;
        assertTrue(Math.abs(mean) < 0.5,
                () -> "the layer has a bias of " + mean + " per axis; it should average zero");
    }

    /**
     * The clamp test, and the reason this file exists. A household mean of 95 with a ±25 individual
     * layer must be able to produce 100 — and must also produce values above 95, which is exactly
     * what an implementation that clamped to the parent's range could not do.
     */
    @Test
    @DisplayName("clamping is to the axis bounds, never to the parent's range")
    void clampingIsToTheAxisBoundsNotTheParent() {
        byte[] high = new byte[Persona.TRAIT_COUNT];
        java.util.Arrays.fill(high, (byte) 95);
        byte[] low = new byte[Persona.TRAIT_COUNT];
        java.util.Arrays.fill(low, (byte) -95);

        boolean reachedCeiling = false;
        boolean exceededParentUp = false;
        boolean reachedFloor = false;
        boolean exceededParentDown = false;
        int aboveBounds = 0;

        for (long seed = 0; seed < 5000; seed++) {
            for (byte value : TraitRoll.jitter(high, seed, TraitRoll.INDIVIDUAL_SPREAD)) {
                reachedCeiling |= value == 100;
                exceededParentUp |= value > 95;
                aboveBounds += value > 100 ? 1 : 0;
            }
            for (byte value : TraitRoll.jitter(low, seed, TraitRoll.INDIVIDUAL_SPREAD)) {
                reachedFloor |= value == -100;
                exceededParentDown |= value < -95;
                aboveBounds += value < -100 ? 1 : 0;
            }
        }

        assertTrue(reachedCeiling, "an individual can never reach +100");
        assertTrue(reachedFloor, "an individual can never reach -100");
        assertTrue(exceededParentUp,
                "no individual ever rose above their household mean — the roll is clamped to the "
                        + "parental range, which is the mistake WORKPLAN.md names explicitly");
        assertTrue(exceededParentDown, "no individual ever fell below their household mean");
        assertEquals(0, aboveBounds, "an axis left the ±100 bounds");
    }

    // --- the settlement mean ---------------------------------------------------------------------

    @Test
    @DisplayName("a villager of no settlement is their culture and nothing else")
    void anUnsettledVillagerIsJustTheirCulture() {
        for (Culture culture : Culture.values()) {
            assertArrayEquals(culture.traitBase(), TraitRoll.settlementMean(culture, null),
                    culture + " should contribute nothing but its baseline when there is no place");
        }
    }

    /**
     * The rule 5 consumers, proved by effect rather than by the ledger's word. Each of the survey's
     * three outputs must move the mean it feeds — a field that changes nothing when it changes is
     * a field with no consumer, whatever a test file claims.
     */
    @Test
    @DisplayName("each of the survey's three outputs moves the settlement mean")
    void theSurveyOutputsAllReachTheMean() {
        byte[] noNeeds = new byte[Need.COUNT];
        byte[] hungry = new byte[Need.COUNT];
        hungry[Need.FOOD.index()] = 100;

        Settlement plain = settlement(0, Specialty.MIXED, 100, noNeeds);
        Settlement smiths = settlement(0, Specialty.SMITHING, 100, noNeeds);
        Settlement exposed = settlement(0, Specialty.MIXED, 0, noNeeds);
        Settlement starving = settlement(0, Specialty.MIXED, 100, hungry);

        byte[] baseline = TraitRoll.settlementMean(Culture.VALE, plain);

        assertTrue(TraitRoll.settlementMean(Culture.VALE, smiths)[Persona.INDUSTRY]
                        > baseline[Persona.INDUSTRY],
                "specialty must reach the mean: a smithing town should be more industrious");
        assertTrue(TraitRoll.settlementMean(Culture.VALE, exposed)[Persona.BOLDNESS]
                        < baseline[Persona.BOLDNESS],
                "defensibility must reach the mean: an exposed settlement raises warier people");
        assertTrue(TraitRoll.settlementMean(Culture.VALE, starving)[Persona.ACQUISITIVENESS]
                        > baseline[Persona.ACQUISITIVENESS],
                "needs must reach the mean: a hungry settlement raises grabbier people");
    }

    @Test
    @DisplayName("two settlements of one culture and one trade are still not the same place")
    void twoAlikeSettlementsStillDiffer() {
        byte[] needs = new byte[Need.COUNT];
        byte[] first = TraitRoll.settlementMean(Culture.VALE, settlement(0, Specialty.FARMING, 70, needs));
        byte[] second = TraitRoll.settlementMean(Culture.VALE, settlement(1, Specialty.FARMING, 70, needs));

        assertTrue(!java.util.Arrays.equals(first, second),
                "two farming villages of one culture came out identical; the world would read as "
                        + "one village copied");
    }

    // --- end to end ------------------------------------------------------------------------------

    @Test
    @DisplayName("a rolled persona never leaves the axis bounds, in any culture or settlement")
    void theRollNeverLeavesTheBounds() {
        byte[] needs = {100, 100, 100, 100};
        Settlements settlements = withOne(settlement(0, Specialty.SMITHING, 0, needs));

        for (Culture culture : Culture.values()) {
            for (int i = 0; i < 3000; i++) {
                byte[] traits = TraitRoll.roll(placed(i, 0, i % 17, culture), settlements);
                for (int axis = 0; axis < Persona.TRAIT_COUNT; axis++) {
                    assertTrue(traits[axis] >= -100 && traits[axis] <= 100,
                            culture + " rolled " + traits[axis] + " on axis " + axis);
                }
            }
        }
    }

    /**
     * The half of this session's exit criterion the owner reads by eye: two people from one house
     * should look more alike than two people from opposite ends of the village.
     */
    @Test
    @DisplayName("a household resembles itself more than it resembles the village")
    void householdsCluster() {
        Settlements settlements = withOne(settlement(0, Specialty.FARMING, 60, new byte[Need.COUNT]));
        int households = 10;
        int perHousehold = 6;

        List<List<byte[]>> families = new ArrayList<>();
        for (int household = 0; household < households; household++) {
            List<byte[]> members = new ArrayList<>();
            for (int member = 0; member < perHousehold; member++) {
                members.add(TraitRoll.roll(
                        placed(household * 1000L + member, 0, household, Culture.VALE), settlements));
            }
            families.add(members);
        }

        double within = 0;
        int withinPairs = 0;
        double between = 0;
        int betweenPairs = 0;
        for (int a = 0; a < households; a++) {
            for (int b = 0; b < households; b++) {
                for (byte[] left : families.get(a)) {
                    for (byte[] right : families.get(b)) {
                        if (left == right) {
                            continue;
                        }
                        double distance = meanAxisDistance(left, right);
                        if (a == b) {
                            within += distance;
                            withinPairs++;
                        } else {
                            between += distance;
                            betweenPairs++;
                        }
                    }
                }
            }
        }

        double withinMean = within / withinPairs;
        double betweenMean = between / betweenPairs;
        assertTrue(withinMean < betweenMean * 0.85,
                () -> "household members differ by " + Math.round(withinMean) + " on average and "
                        + "strangers by " + Math.round(betweenMean) + ". Families do not read as "
                        + "families.");
    }

    /**
     * Conformity may only ever narrow the individual layer. If a culture could widen it, the ruled
     * ±25 would stop being a bound and start being a suggestion.
     */
    @Test
    @DisplayName("no culture spends more than the ruled 25 on the individual layer")
    void conformityOnlyNarrows() {
        Settlements settlements = withOne(settlement(0, Specialty.MIXED, 50, new byte[Need.COUNT]));

        double widest = 0;
        Culture widestCulture = Culture.VALE;
        for (Culture culture : Culture.values()) {
            assertTrue(culture.conformity() > 0.0F && culture.conformity() <= 1.0F,
                    culture + " has a conformity outside (0, 1]");

            double spread = observedIndividualSpread(settlements, culture);
            assertTrue(spread <= TraitRoll.INDIVIDUAL_SPREAD,
                    () -> culture + " spent " + spread + " on the individual layer, more than the "
                            + "ruled " + TraitRoll.INDIVIDUAL_SPREAD);
            if (spread > widest) {
                widest = spread;
                widestCulture = culture;
            }
        }

        assertEquals(TraitRoll.INDIVIDUAL_SPREAD, (int) widest,
                "no culture reaches the full ±25, so the ruled number is not the bound at all — "
                        + "it is just a number nothing touches. Widest was " + widestCulture);

        assertTrue(observedIndividualSpread(settlements, Culture.TALQIR)
                        < observedIndividualSpread(settlements, Culture.MERIDIAN),
                "the most conformist culture should visibly produce more alike people than the "
                        + "least");
    }

    @Test
    @DisplayName("the same persona always rolls the same traits")
    void theRollIsDeterministic() {
        Settlements settlements = withOne(settlement(0, Specialty.FISHING, 40, new byte[Need.COUNT]));
        Persona persona = placed(99, 0, 3, Culture.YUN);

        assertArrayEquals(TraitRoll.roll(persona, settlements), TraitRoll.roll(persona, settlements));
    }

    @Test
    @DisplayName("a persona with no culture cannot be rolled at all")
    void anUngeneratedPersonaCannotBeRolled() {
        // Rolling against a missing culture would have to substitute one, and substituting culture
        // zero is precisely the silent wrongness the schema 2 -> 3 fix exists to prevent.
        Persona blank = Persona.create(UUID.randomUUID(), 0L);
        assertThrows(IllegalStateException.class, () -> TraitRoll.roll(blank, new Settlements()));
    }

    @Test
    @DisplayName("a persona whose settlement is not in the table rolls from its culture alone")
    void aMissingSettlementIsNotAnError() {
        // A world where the settlement table lost a record should still produce people, not throw
        // in the middle of a chunk load.
        byte[] traits = TraitRoll.roll(placed(5, 4242, 1, Culture.ASHANI), new Settlements());
        assertEquals(Persona.TRAIT_COUNT, traits.length);
    }

    // --- helpers ---------------------------------------------------------------------------------

    /** The largest distance any member of one household sits from that household's own mean. */
    private static double observedIndividualSpread(Settlements settlements, Culture culture) {
        byte[] settlementMean = TraitRoll.settlementMean(culture,
                settlements.byId(0).orElseThrow());
        int household = 12345;
        byte[] householdMean = TraitRoll.jitter(settlementMean,
                mixLikeTheRoll(household), TraitRoll.HOUSEHOLD_SPREAD);

        int widest = 0;
        for (int i = 0; i < 5000; i++) {
            byte[] traits = TraitRoll.roll(placed(i, 0, household, culture), settlements);
            for (int axis = 0; axis < Persona.TRAIT_COUNT; axis++) {
                // Only axes with room on both sides, so a clamp at ±100 is not read as narrowness.
                if (Math.abs(householdMean[axis]) <= 100 - TraitRoll.INDIVIDUAL_SPREAD) {
                    widest = Math.max(widest, Math.abs(traits[axis] - householdMean[axis]));
                }
            }
        }
        return widest;
    }

    /** Mirrors the household seeding inside {@code TraitRoll.roll}. */
    private static long mixLikeTheRoll(int householdId) {
        long value = householdId * 0x9E3779B97F4A7C15L ^ 0x48_4F_55_53_45_00_00_01L;
        long z = value + 0x9E3779B97F4A7C15L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    private static double meanAxisDistance(byte[] left, byte[] right) {
        int total = 0;
        for (int axis = 0; axis < Persona.TRAIT_COUNT; axis++) {
            total += Math.abs(left[axis] - right[axis]);
        }
        return (double) total / Persona.TRAIT_COUNT;
    }
}
