package net.namesake.npc;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Households, which are derived rather than stored.
 *
 * <p>A household is the cell of the world a villager was generated in, measured from its
 * settlement's bell. That buys three things a stored household table would not: nothing to persist,
 * nothing to migrate, and nothing that can drift out of step with the positions it describes. What
 * it costs is a grid — two neighbours either side of a cell boundary are not family — and that is
 * the trade recorded here rather than in a comment nobody reads.
 */
class PersonasTest {

    private static final BlockPos BELL = new BlockPos(120, 64, -340);

    @Test
    @DisplayName("villagers in one cell are one household")
    void oneCellIsOneHousehold() {
        int first = Personas.householdAt(3, BELL, BELL.offset(2, 0, 3));
        int second = Personas.householdAt(3, BELL, BELL.offset(5, 12, 9));

        assertEquals(first, second, "two villagers 7 blocks apart should be family");
    }

    @Test
    @DisplayName("height does not decide a household")
    void householdsAreHorizontal() {
        // A villager in the cellar is in the house above it.
        assertEquals(Personas.householdAt(3, BELL, BELL.offset(4, 0, 4)),
                Personas.householdAt(3, BELL, BELL.offset(4, -40, 4)));
    }

    @Test
    @DisplayName("the next cell over is a different household")
    void adjacentCellsAreDifferentHouseholds() {
        int here = Personas.householdAt(3, BELL, BELL.offset(2, 0, 2));
        assertNotEquals(here, Personas.householdAt(3, BELL,
                BELL.offset(2 + Personas.HOUSEHOLD_CELL, 0, 2)));
        assertNotEquals(here, Personas.householdAt(3, BELL,
                BELL.offset(2, 0, 2 + Personas.HOUSEHOLD_CELL)));
    }

    @Test
    @DisplayName("the grid is aligned to the bell, not to the world origin")
    void theGridFollowsTheSettlement() {
        // The same offset from two different bells must land in the same relative cell, or a
        // village's households would depend on where the world's origin happens to be.
        BlockPos other = new BlockPos(-7331, 70, 918);
        assertEquals(Personas.householdAt(3, BELL, BELL.offset(20, 0, 20)) != Personas.householdAt(3, BELL, BELL.offset(4, 0, 4)),
                Personas.householdAt(3, other, other.offset(20, 0, 20)) != Personas.householdAt(3, other, other.offset(4, 0, 4)));
    }

    @Test
    @DisplayName("the same cell in two settlements is two different families")
    void householdsDoNotRhymeAcrossSettlements() {
        assertNotEquals(Personas.householdAt(0, BELL, BELL.offset(4, 0, 4)),
                Personas.householdAt(1, BELL, BELL.offset(4, 0, 4)));
    }

    /**
     * A household id is compared against {@link Persona#UNASSIGNED} to decide whether a persona has
     * been placed, so it must never be able to produce it. Shifting right by 33 rather than masking
     * is what guarantees that, and this is the test that would notice the shift being changed.
     */
    @Test
    @DisplayName("a household id is never negative and never the unassigned sentinel")
    void householdIdsAreNeverTheSentinel() {
        Set<Integer> seen = new HashSet<>();
        for (int settlement = -3; settlement < 40; settlement++) {
            for (int x = -2000; x <= 2000; x += 37) {
                for (int z = -2000; z <= 2000; z += 41) {
                    int household = Personas.householdAt(settlement, BELL, new BlockPos(x, 64, z));
                    assertTrue(household >= 0, "household " + household + " is negative");
                    assertNotEquals(Persona.UNASSIGNED, household);
                    seen.add(household);
                }
            }
        }
        assertTrue(seen.size() > 10_000,
                () -> "only " + seen.size() + " distinct households across 43 settlements; the "
                        + "hash is collapsing");
    }

    @Test
    @DisplayName("household assignment is deterministic")
    void householdsAreDeterministic() {
        for (int i = 0; i < 200; i++) {
            BlockPos pos = new BlockPos(i * 13, 64, i * -29);
            assertEquals(Personas.householdAt(2, BELL, pos), Personas.householdAt(2, BELL, pos));
        }
    }
}
