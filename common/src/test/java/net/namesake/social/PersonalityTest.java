package net.namesake.social;

import net.namesake.npc.Persona;
import net.namesake.testing.MethodBody;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The weight table itself: its shape, its bounds, and the two properties that make it a consumer of
 * {@code Persona.traits} rather than a decoration on one.
 *
 * <p><b>{@code noTraitAxisIsDeadWeight} is the rule 5 test in miniature.</b> An axis whose whole row
 * is zero is a persisted number that changes nothing, which is exactly what
 * {@code SocialValueLedgerTest} refuses at the level of the field — the difference being that a
 * ledger entry can name this class truthfully while one of its eight rows quietly does nothing.
 */
class PersonalityTest {

    /** Typical unless axes are supplied — see the note on the same helper in {@code DeedsTest}. */
    private static Persona person(int... axes) {
        byte[] traits = Personality.typical();
        for (int axis = 0; axis < axes.length; axis++) {
            traits[axis] = (byte) axes[axis];
        }
        return Persona.create(new UUID(11, java.util.Arrays.hashCode(traits)), 0L)
                .placed(1, 1, (byte) 0)
                .withTraits(traits);
    }

    @Test
    @DisplayName("the table is eight trait axes by six deed types")
    void theTableIsEightBySix() {
        assertEquals(Persona.TRAIT_COUNT, Personality.rows(),
                "a row per trait axis, or an axis has no opinion about anything");
        assertEquals(DeedType.values().length, Personality.columns(),
                "a column per deed type, or a deed type is unweighted and traits do not reach it");
    }

    @Test
    @DisplayName("a typical villager scores exactly neutral, on every column")
    void theTableIsCentredOnTheTypicalVillager() {
        // The invariant centring buys, and it holds by construction rather than by tuning: the
        // per-column offset is computed from the same vector this asks about, so it cannot drift
        // from it. What CAN drift is whether that vector is still the population's — which is
        // PersonalityDistributionTest's job, not this one's.
        for (DeedType type : DeedType.values()) {
            assertEquals(Personality.NEUTRAL, Personality.scale(person(), type), 1.0E-5F,
                    type + " must be worth its nominal value to a typical villager");
        }
    }

    @Test
    @DisplayName("a villager with no personality at all scores below nominal, and that is the point")
    void eightZeroesIsNotTheReferencePoint() {
        // Before the close of session 05 eight zeroes was the reference, which made every nominal
        // number the value of a deed to a person who does not exist. Every culture has a baseline —
        // industry averages 24 across the six — so an all-zero villager is not average, it is
        // unusually incurious, idle and untraditional, and it should read that way.
        Persona nobody = Persona.create(new UUID(12, 12), 0L)
                .placed(1, 1, (byte) 0)
                .withTraits(new byte[Persona.TRAIT_COUNT]);

        assertTrue(Personality.scale(nobody, DeedType.GIFT_WANTED) < Personality.NEUTRAL,
                "an all-zero villager must not be the yardstick any more");
    }

    @Test
    @DisplayName("a typical villager's daily allowance is exactly the base cap")
    void theTypicalAllowanceIsTheBaseCap() {
        assertEquals(Bond.DAILY_CAP, Personality.allowance(person()),
                "the base is what a typical villager gets; personality moves it either way");
    }

    @Test
    @DisplayName("the allowance follows receptiveness, and never escapes its four bits")
    void theAllowanceMovesWithPersonalityAndFitsItsNibble() {
        // Warm, sociable and generous against cold, antisocial and short-tempered. The allowance is
        // read off the four benign columns only: the cap exists to limit positives, so a hot temper
        // scoring high on STRUCK_RESIDENT must not raise anybody's capacity for warmth.
        Persona open = person(80, 20, 0, 40, 0, 40, -40, 70);
        Persona closed = person(-60, 0, 0, -40, 40, -40, 80, -60);

        assertTrue(Personality.allowance(open) > Bond.DAILY_CAP,
                "a receptive villager's day is worth more than the base");
        assertTrue(Personality.allowance(closed) < Bond.DAILY_CAP,
                "and a closed one's is worth less");

        // The build-time half of the nibble guard: no personality the clamp allows can produce an
        // allowance that overflows the four bits gainedToday stores each counter in.
        assertTrue(Math.round(Bond.DAILY_CAP * Personality.MAX) <= 15,
                "the largest allowance personality can produce must still fit four bits");
        assertTrue(Personality.allowance(closed) >= 1, "and nobody's day is worth nothing");
    }

    @Test
    @DisplayName("no trait axis is dead weight")
    void noTraitAxisIsDeadWeight() {
        for (int axis = 0; axis < Persona.TRAIT_COUNT; axis++) {
            boolean matters = false;
            for (DeedType type : DeedType.values()) {
                matters |= Personality.weight(axis, type) != 0F;
            }
            assertTrue(matters, Persona.TRAIT_NAMES[axis] + " changes nothing about any deed. It is "
                    + "persisted on every villager in the world and feeds no `if` statement. "
                    + "DESIGN.md §1: give it a weight or delete the axis.");
        }
    }

    @Test
    @DisplayName("no deed type is unweighted")
    void noDeedTypeIsUnweighted() {
        for (DeedType type : DeedType.values()) {
            boolean weighted = false;
            for (int axis = 0; axis < Persona.TRAIT_COUNT; axis++) {
                weighted |= Personality.weight(axis, type) != 0F;
            }
            assertTrue(weighted, type + " is worth the same to everybody, which means traits do not "
                    + "reach it at all");
        }
    }

    @Test
    @DisplayName("the multiplier is bounded even at the corners of the trait space")
    void theScaleIsBounded() {
        // Eight axes at ±100 in whichever direction pushes hardest. Unbounded, one lucky roll
        // produces a villager for whom a loaf of bread is worth a rescue.
        for (DeedType type : DeedType.values()) {
            int[] high = new int[Persona.TRAIT_COUNT];
            int[] low = new int[Persona.TRAIT_COUNT];
            for (int axis = 0; axis < Persona.TRAIT_COUNT; axis++) {
                high[axis] = Personality.weight(axis, type) >= 0 ? 100 : -100;
                low[axis] = -high[axis];
            }
            float ceiling = Personality.scale(person(high), type);
            float floor = Personality.scale(person(low), type);

            assertTrue(ceiling <= Personality.MAX, type + " reached " + ceiling);
            assertTrue(floor >= Personality.MIN, type + " reached " + floor);
            assertTrue(ceiling > floor, type + " does not vary at all");
        }
    }

    @Test
    @DisplayName("a strongly-drawn villager lands inside the bounds rather than on them")
    void theBoundsBindOnlyAtTheCorners() {
        // A ±25 individual layer on top of a ±20 household layer does not reach the corners, so a
        // clamp that bit in ordinary play would be flattening real variation rather than guarding
        // against absurd variation.
        float smith = Personality.scale(person(-40, 0, 0, 0, 30, 10, 50, -30), DeedType.GIFT_WANTED);
        float innkeeper = Personality.scale(person(60, 0, 0, 25, 0, 20, -30, 55), DeedType.GIFT_WANTED);

        assertTrue(smith > Personality.MIN && smith < Personality.NEUTRAL,
                "the smith landed at " + smith);
        assertTrue(innkeeper < Personality.MAX && innkeeper > Personality.NEUTRAL,
                "the innkeeper landed at " + innkeeper);
    }

    @Test
    @DisplayName("the deed pipeline actually asks for the personality-scaled allowance")
    void theBusUsesTheAllowance() {
        // The gap this closes is the one every other test here leaves open. Bond.apply takes the
        // allowance as an argument and DeedBus is the only production caller, so a version that
        // passed Bond.DAILY_CAP instead would put every villager back on the base cap and no unit
        // test would notice — the arithmetic is all still correct, it is simply being asked the
        // wrong question. Read out of the bytecode, which is the same instrument the rule 5 ledger
        // uses for exactly this shape of claim.
        assertTrue(MethodBody.of(DeedBus.class, "applyTo").invokes(Personality.class, "allowance"),
                "DeedBus.applyTo never calls Personality.allowance, so the daily ceiling is not "
                        + "personalised and the ruling at the close of session 05 is a method "
                        + "nobody calls.");
    }

    @Test
    @DisplayName("temper cuts one way on a gift and the other on a blow")
    void temperReadsAsTemper() {
        Persona hot = person(0, 0, 0, 0, 0, 0, 80, 0);
        assertTrue(Personality.scale(hot, DeedType.GIFT_WANTED) < Personality.NEUTRAL,
                "a short temper should discount a kindness");
        assertTrue(Personality.scale(hot, DeedType.STRUCK_RESIDENT) > Personality.NEUTRAL,
                "and take a blow harder");
    }
}
