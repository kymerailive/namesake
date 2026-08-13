package net.namesake.social;

import net.namesake.npc.Persona;
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

    private static Persona person(int... axes) {
        byte[] traits = new byte[Persona.TRAIT_COUNT];
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
    @DisplayName("a villager with eight zeroes scores exactly neutral")
    void neutralIsNeutral() {
        for (DeedType type : DeedType.values()) {
            assertEquals(Personality.NEUTRAL, Personality.scale(person(), type), 1.0E-6F,
                    type + " must be worth its nominal value to somebody with no personality");
        }
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
    @DisplayName("temper cuts one way on a gift and the other on a blow")
    void temperReadsAsTemper() {
        Persona hot = person(0, 0, 0, 0, 0, 0, 80, 0);
        assertTrue(Personality.scale(hot, DeedType.GIFT_WANTED) < Personality.NEUTRAL,
                "a short temper should discount a kindness");
        assertTrue(Personality.scale(hot, DeedType.STRUCK_RESIDENT) > Personality.NEUTRAL,
                "and take a blow harder");
    }
}
