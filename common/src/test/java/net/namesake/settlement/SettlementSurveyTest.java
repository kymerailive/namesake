package net.namesake.settlement;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The scoring half of the survey — the part that runs off the server thread, and therefore the part
 * that is pure enough to test without a world.
 *
 * <p>These numbers are v1 and {@code SettlementSurvey} says so. What is being pinned down here is
 * not that a farming village scores 34 for tools, but that the shape is right and stays right: a
 * settlement with nothing scores nothing, a tie is not broken by enum order, needs fall as
 * suppliers rise, and no output can leave its range and corrupt the trait mean it feeds.
 */
class SettlementSurveyTest {

    private static SettlementSurvey.Tally tally(int beds, int meanDistance, Object... pairs) {
        int[] sites = new int[Specialty.values().length];
        int workstations = 0;
        for (int i = 0; i < pairs.length; i += 2) {
            Specialty specialty = (Specialty) pairs[i];
            int count = (Integer) pairs[i + 1];
            sites[specialty.ordinal()] += count;
            workstations += count;
        }
        return new SettlementSurvey.Tally(sites, beds, workstations, meanDistance);
    }

    // --- specialty --------------------------------------------------------------------------------

    @Test
    @DisplayName("the commonest trade wins when it is genuinely ahead")
    void theCommonestTradeWins() {
        assertEquals(Specialty.FARMING,
                SettlementSurvey.dominantTrade(tally(6, 20, Specialty.FARMING, 3, Specialty.SMITHING, 1)));
        assertEquals(Specialty.SCHOLARLY,
                SettlementSurvey.dominantTrade(tally(6, 20, Specialty.SCHOLARLY, 4, Specialty.FISHING, 2)));
    }

    /**
     * A tie must not be broken by declaration order. {@code FARMING} is declared first, so breaking
     * ties arbitrarily would make roughly half the villages in a world claim to be farming towns —
     * and the trait bias they feed would be an artefact of this source file.
     */
    @Test
    @DisplayName("a tie is reported as mixed rather than broken by enum order")
    void aTieIsMixed() {
        assertEquals(Specialty.MIXED,
                SettlementSurvey.dominantTrade(tally(6, 20, Specialty.FARMING, 3, Specialty.SMITHING, 3)));
        assertEquals(Specialty.MIXED,
                SettlementSurvey.dominantTrade(tally(6, 20,
                        Specialty.MASONRY, 2, Specialty.PASTORAL, 2, Specialty.FISHING, 2)));
    }

    @Test
    @DisplayName("one workstation is not a trade")
    void oneSiteIsNotATrade() {
        assertEquals(Specialty.MIXED,
                SettlementSurvey.dominantTrade(tally(4, 20, Specialty.FISHING, 1)));
        assertEquals(Specialty.MIXED, SettlementSurvey.dominantTrade(tally(0, 0)));
    }

    // --- defensibility ----------------------------------------------------------------------------

    @Test
    @DisplayName("an empty area is not a fortress")
    void nothingScoresNothing() {
        // Mean distance is zero when there is nothing to measure, and a naive compactness formula
        // reads that as perfectly compact.
        assertEquals(0, SettlementSurvey.defensibility(tally(0, 0)));
    }

    @Test
    @DisplayName("a tight settlement is more defensible than a strung-out one")
    void compactnessRaisesDefensibility() {
        byte tight = SettlementSurvey.defensibility(tally(10, 12, Specialty.FARMING, 8));
        byte sprawling = SettlementSurvey.defensibility(tally(10, 90, Specialty.FARMING, 8));
        assertTrue(tight > sprawling,
                "tight " + tight + " should beat sprawling " + sprawling);
    }

    @Test
    @DisplayName("defensibility stays inside 0..100 at every extreme")
    void defensibilityIsBounded() {
        for (int beds = 0; beds <= 200; beds += 7) {
            for (int distance = 0; distance <= 400; distance += 13) {
                byte score = SettlementSurvey.defensibility(tally(beds, distance, Specialty.SMITHING, beds));
                assertTrue(score >= 0 && score <= 100, "scored " + score);
            }
        }
    }

    // --- needs ------------------------------------------------------------------------------------

    @Test
    @DisplayName("a settlement that can feed and house itself needs neither")
    void aWellSuppliedSettlementNeedsNothing() {
        // Thirteen beds for thirteen job sites. Twelve would leave a shelter need of 8, which is
        // correct and is what caught this fixture the first time it ran.
        byte[] needs = SettlementSurvey.needs(tally(13, 20,
                Specialty.FARMING, 4, Specialty.FISHING, 2, Specialty.SMITHING, 4,
                Specialty.SCHOLARLY, 3));

        assertEquals(0, needs[Need.FOOD.index()]);
        assertEquals(0, needs[Need.SHELTER.index()]);
        assertEquals(0, needs[Need.TOOLS.index()]);
        assertEquals(0, needs[Need.TRADE_GOODS.index()]);
    }

    @Test
    @DisplayName("a settlement with nothing is desperate for everything")
    void anEmptySettlementNeedsEverything() {
        byte[] needs = SettlementSurvey.needs(tally(0, 0));
        for (Need need : Need.values()) {
            assertEquals(100, needs[need.index()], need + " should be at its maximum");
        }
    }

    @Test
    @DisplayName("every need falls as its suppliers rise")
    void needsFallAsSuppliersRise() {
        byte previous = 100;
        for (int farms = 0; farms <= 6; farms++) {
            byte food = SettlementSurvey.needs(tally(12, 20, Specialty.FARMING, farms))[Need.FOOD.index()];
            assertTrue(food <= previous, farms + " farms raised the food need to " + food);
            previous = food;
        }

        // Job sites with nobody housed to fill them are a demand too, so shelter is measured
        // against the larger of beds and workstations.
        byte crowded = SettlementSurvey.needs(tally(2, 20, Specialty.FARMING, 10))[Need.SHELTER.index()];
        byte housed = SettlementSurvey.needs(tally(10, 20, Specialty.FARMING, 10))[Need.SHELTER.index()];
        assertTrue(crowded > housed, "crowded " + crowded + " should exceed housed " + housed);
    }

    @Test
    @DisplayName("every need stays inside 0..100 at every extreme")
    void needsAreBounded() {
        for (int beds = 0; beds <= 300; beds += 11) {
            for (int sites = 0; sites <= 300; sites += 17) {
                byte[] needs = SettlementSurvey.needs(tally(beds, 30,
                        Specialty.FARMING, sites, Specialty.SMITHING, sites, Specialty.SCHOLARLY, sites));
                for (Need need : Need.values()) {
                    assertTrue(needs[need.index()] >= 0 && needs[need.index()] <= 100,
                            need + " scored " + needs[need.index()]);
                }
            }
        }
    }

    @Test
    @DisplayName("scoring produces a complete, in-range survey")
    void scoringProducesAWholeSurvey() {
        SettlementSurvey.Survey survey = SettlementSurvey.score(
                tally(9, 24, Specialty.FARMING, 4, Specialty.SMITHING, 2));

        assertEquals(Specialty.FARMING, survey.specialty());
        assertTrue(survey.defensibility() > 0);
        assertEquals(Need.COUNT, survey.needs().length);
    }
}
