package net.namesake.social;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The price arithmetic and the recipe table — the two halves of session 12's first two consumers
 * that do not need a world.
 *
 * <p>What needs one is in the harness: a real trade window with a real multiplier on it, and a
 * recipe actually unlocked in a real player's recipe book. That is the line {@code WORKPLAN.md}
 * draws, and it cuts exactly here.
 */
class TradingTest {

    // --- the multiplier, as item counts -------------------------------------------------------------

    /**
     * <b>Every band against every cost vanilla can put on the left of a trade.</b>
     *
     * <p>Enumerated rather than sampled, and the range is the real one: a vanilla trade's first cost
     * runs from one item to a stack of sixty-four.
     */
    @Test
    @DisplayName("every band charges what its multiplier says, at every cost vanilla can produce")
    void theAdjustmentIsTheMultiplier() {
        for (Standing band : Standing.values()) {
            for (int cost = 1; cost <= 64; cost++) {
                final int base = cost;
                int diff = Trading.adjustmentFor(base, band.priceMultiplier());
                int charged = base + diff;
                float wanted = base * band.priceMultiplier();
                assertTrue(Math.abs(charged - wanted) <= 0.5F,
                        () -> band + " on a cost of " + base + " charges " + charged
                                + " where the multiplier says " + wanted);
            }
        }
    }

    @Test
    @DisplayName("the standing price moves nothing at all, at any cost")
    void theNeutralBandIsFree() {
        for (int base = 1; base <= 64; base++) {
            assertEquals(0, Trading.adjustmentFor(base, Standing.NEUTRAL.priceMultiplier()),
                    "DESIGN.md §10 step 1: prices 1.00 means the offer is untouched");
        }
    }

    @Test
    @DisplayName("a discount never raises a price and a markup never lowers one")
    void theSignIsAlwaysRight() {
        for (Standing band : Standing.values()) {
            for (int base = 1; base <= 64; base++) {
                final int diff = Trading.adjustmentFor(base, band.priceMultiplier());
                if (band.priceMultiplier() < 1F) {
                    assertTrue(diff <= 0, () -> band + " raised a price by " + diff);
                } else if (band.priceMultiplier() > 1F) {
                    assertTrue(diff >= 0, () -> band + " lowered a price by " + diff);
                }
            }
        }
    }

    /**
     * <b>The property a band expressed as a fraction of a price cannot have, stated rather than
     * hidden.</b>
     *
     * <p>A cost of one item cannot move by any multiplier on this ladder, because a quarter of one
     * rounds to nothing — and vanilla's own {@code clamp(…, 1, maxStackSize)} would refuse it even
     * if it did not. So the cheapest trades in the game are the same price to everybody, which is a
     * real limitation and is recorded here because a player will notice it before a test does.
     */
    @Test
    @DisplayName("a one-item cost is the same price to everybody, and that is the arithmetic")
    void theCheapestTradeCannotMove() {
        for (Standing band : Standing.values()) {
            assertEquals(0, Trading.adjustmentFor(1, band.priceMultiplier()),
                    () -> band + " moved a one-item cost, which vanilla's own clamp would refuse");
        }
        // Two is where the ladder starts biting, at the ends of it.
        assertEquals(-1, Trading.adjustmentFor(2, Standing.WARM.priceMultiplier()));
        assertEquals(1, Trading.adjustmentFor(2, Standing.RESENTED.priceMultiplier()));
    }

    /**
     * <b>The band that is worst for you costs the most, on the trade a player actually meets.</b>
     *
     * <p>Twenty emeralds is roughly a librarian's top-tier book. The spread across the ladder is
     * what a player is meant to be able to feel without being told a number, which is
     * {@code DESIGN.md} §2's <i>bands, never raw integers</i> arriving at a price tag.
     */
    @Test
    @DisplayName("the ladder is legible on a real trade: 27 emeralds against 15")
    void theSpreadIsVisible() {
        assertEquals(27, 20 + Trading.adjustmentFor(20, Standing.RESENTED.priceMultiplier()));
        assertEquals(23, 20 + Trading.adjustmentFor(20, Standing.WARY.priceMultiplier()));
        assertEquals(20, 20 + Trading.adjustmentFor(20, Standing.NEUTRAL.priceMultiplier()));
        assertEquals(18, 20 + Trading.adjustmentFor(20, Standing.TRUSTED.priceMultiplier()));
        assertEquals(15, 20 + Trading.adjustmentFor(20, Standing.WARM.priceMultiplier()));
    }

    // --- the recipe table ---------------------------------------------------------------------------

    /**
     * Vanilla's thirteen working professions plus the two that are not a trade.
     *
     * <p>Written out rather than read off {@code BuiltInRegistries}, because a unit test in this
     * module runs with no registries bootstrapped at all — which is the same property session 07's
     * headless simulation depends on. A vanilla profession added or removed by a Minecraft version
     * bump turns this red, which is the right time to find out.
     */
    private static final List<String> VANILLA_PROFESSIONS = List.of(
            "armorer", "butcher", "cartographer", "cleric", "farmer", "fisherman", "fletcher",
            "leatherworker", "librarian", "mason", "shepherd", "toolsmith", "weaponsmith",
            "nitwit", "none");

    @Test
    @DisplayName("every vanilla profession with a trade has a recipe, and the two without have none")
    void everyTradeTeachesSomething() {
        Set<String> covered = new LinkedHashSet<>(Teaching.professionsWithARecipe());
        for (String profession : VANILLA_PROFESSIONS) {
            boolean isATrade = !profession.equals("nitwit") && !profession.equals("none");
            assertEquals(isATrade, covered.remove(profession),
                    () -> isATrade
                            ? profession + " has a trade and nothing to show you for it"
                            : profession + " has no trade and is being made to teach one");
        }
        assertTrue(covered.isEmpty(),
                () -> "the table names professions vanilla does not have: " + covered);
    }

    @Test
    @DisplayName("no two professions teach the same recipe")
    void everyTradeTeachesItsOwnThing() {
        Set<String> recipes = new LinkedHashSet<>();
        for (String profession : Teaching.professionsWithARecipe()) {
            String recipe = Teaching.recipeNameFor(profession);
            assertTrue(recipes.add(recipe),
                    () -> profession + " teaches " + recipe + ", which somebody else already taught");
        }
        assertEquals(Teaching.professionsWithARecipe().size(), recipes.size());
    }

    /**
     * <b>The librarian teaches you to build a lectern, and a lectern is session 11's Notice Board.</b>
     *
     * <p>Nothing was arranged to make that true — it falls out of the profession table, because a
     * librarian's workstation <i>is</i> a lectern. It is pinned rather than left to be noticed,
     * because it is the one row of this table that composes with another session's work, and session
     * 15 has an open question about whether a village comes with a board already standing. This does
     * not answer it; it is a third answer that was already there.
     */
    @Test
    @DisplayName("a librarian who trusts you teaches you to make a notice board")
    void theLibrarianTeachesTheBoard() {
        assertEquals("lectern", Teaching.recipeNameFor("librarian"));
    }
}
