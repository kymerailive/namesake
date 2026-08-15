package net.namesake.social;

import net.namesake.npc.NpcRegistry;
import net.namesake.npc.Persona;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>The first threshold, as arithmetic.</b> {@code DESIGN.md} §5, and the thing the whole pitch
 * turns on: before it they call you stranger, after it they use your name.
 *
 * <p>Every clause here is pure over a registry, which is why this is a ten-millisecond unit test
 * rather than six minutes of CI — {@code WORKPLAN.md}'s line between its two instruments. What only a
 * running game can show is that it <i>survives a save</i> and that the villager's line changes on the
 * other side, and that is the harness leg.
 */
class ResidencyTest {

    private static final UUID PLAYER = new UUID(0x5EAF_0000_0000_0001L, 9);
    private static final UUID STRANGER = new UUID(0x5EAF_0000_0000_0002L, 9);
    private static final int VILLAGE = 0;
    private static final int ELSEWHERE = 1;

    private static Persona resident(int index, int settlement) {
        return Persona.create(new UUID(77, index), 0L).placed(settlement, index / 3, (byte) 0);
    }

    /** A settlement of {@code count} people who have met nobody. */
    private static NpcRegistry village(int count) {
        NpcRegistry registry = new NpcRegistry();
        for (int i = 0; i < count; i++) {
            registry.put(resident(i, VILLAGE));
        }
        return registry;
    }

    /**
     * Puts {@code trust} on the bond {@code index} holds about the player.
     *
     * <p>Built directly rather than through {@code Bond.apply}, which clamps its allowance to what
     * the four-bit {@code gainedToday} counters can hold — so a fixture asking for twenty trust in
     * one call quietly gets fifteen, and would be testing a threshold it never reaches. Twenty trust
     * is a real state a bond reaches on day 28 of one deed a day; this constructs the end of that
     * rather than simulating the days.
     */
    private static void trusts(NpcRegistry registry, int index, int trust) {
        registry.putBond(new UUID(77, index), PLAYER, new Bond((byte) trust, (byte) 0,
                (byte) 0, (short) 0, 0, (short) 0, (byte) 0));
    }

    // --- the band ----------------------------------------------------------------------------------

    @Test
    @DisplayName("nobody is a resident of a village that has not met them")
    void aStrangerIsNotAResident() {
        NpcRegistry registry = village(9);
        Residency.Verdict verdict = Residency.verdict(registry, VILLAGE, PLAYER, 0);

        assertFalse(verdict.granted());
        assertEquals(Residency.Route.NONE, verdict.route());
        assertEquals(0, verdict.residentsAtThreshold());
        assertEquals(0, verdict.feedings());
        assertFalse(verdict.defendedARaid());
    }

    /**
     * <b>Three, and not two.</b> {@code DESIGN.md} §5 says "≥3 residents", and the number is what
     * makes residency a village's decision rather than one villager's — which is the whole reason
     * session 10 can have a second settlement hear about you.
     */
    @Test
    @DisplayName("two residents at the threshold is not a village taking you in; three is")
    void theBandNeedsThree() {
        NpcRegistry registry = village(9);
        trusts(registry, 0, Residency.TRUST_THRESHOLD);
        trusts(registry, 1, Residency.TRUST_THRESHOLD);

        assertFalse(Residency.isResident(registry, VILLAGE, PLAYER, 0));
        assertEquals(2, Residency.verdict(registry, VILLAGE, PLAYER, 0).residentsAtThreshold());

        trusts(registry, 2, Residency.TRUST_THRESHOLD);
        Residency.Verdict verdict = Residency.verdict(registry, VILLAGE, PLAYER, 0);
        assertTrue(verdict.granted());
        assertEquals(Residency.Route.BAND, verdict.route());
        assertEquals(3, verdict.residentsAtThreshold());
    }

    @Test
    @DisplayName("one point below the threshold is below it")
    void theThresholdIsExact() {
        NpcRegistry registry = village(9);
        for (int i = 0; i < 3; i++) {
            trusts(registry, i, Residency.TRUST_THRESHOLD - 1);
        }
        assertFalse(Residency.isResident(registry, VILLAGE, PLAYER, 0));

        for (int i = 0; i < 3; i++) {
            trusts(registry, i, Residency.TRUST_THRESHOLD);
        }
        assertTrue(Residency.isResident(registry, VILLAGE, PLAYER, 0));
    }

    /**
     * <b>The reason session 08 ruled this axis, held as a test rather than as a comment.</b>
     *
     * <p>Warmth decays a point an in-game day toward four tenths of its high-water mark; trust does
     * not decay at all. So residency earned today is residency next season — and if the band read
     * warmth it would be a threshold that no three residents ever reach, which is what sessions 07
     * and 08 measured and what LNK shipped five of.
     */
    @Test
    @DisplayName("residency does not lapse, because trust does not decay")
    void residencyIsMonotonic() {
        NpcRegistry registry = village(9);
        for (int i = 0; i < 3; i++) {
            trusts(registry, i, Residency.TRUST_THRESHOLD);
        }
        assertTrue(Residency.isResident(registry, VILLAGE, PLAYER, 0));
        assertTrue(Residency.isResident(registry, VILLAGE, PLAYER, 400),
                "an in-game year away must not undo a village's decision that you live there");

        // And the warmth those same bonds hold has gone, which is the axis the band deliberately
        // does not read.
        Bond after = registry.bonds().at(new UUID(77, 0), PLAYER, 400);
        assertEquals(0, after.warmth());
        assertTrue(after.trust() >= Residency.TRUST_THRESHOLD);
    }

    @Test
    @DisplayName("another village's trust does not make you a resident of this one")
    void residencyIsPerSettlement() {
        NpcRegistry registry = village(9);
        for (int i = 0; i < 3; i++) {
            trusts(registry, i, Residency.TRUST_THRESHOLD);
        }
        assertTrue(Residency.isResident(registry, VILLAGE, PLAYER, 0));
        assertFalse(Residency.isResident(registry, ELSEWHERE, PLAYER, 0),
                "the settlement over the hill has not met you");
    }

    @Test
    @DisplayName("one player's standing says nothing about another's")
    void residencyIsPerPlayer() {
        NpcRegistry registry = village(9);
        for (int i = 0; i < 3; i++) {
            trusts(registry, i, Residency.TRUST_THRESHOLD);
        }
        assertTrue(Residency.isResident(registry, VILLAGE, PLAYER, 0));
        assertFalse(Residency.isResident(registry, VILLAGE, STRANGER, 0),
                "DESIGN.md §10 step 7: a second player who has done nothing is a stranger everywhere");
    }

    @Test
    @DisplayName("nowhere is not a place you can be a resident of")
    void theWildernessGrantsNothing() {
        NpcRegistry registry = village(9);
        for (int i = 0; i < 3; i++) {
            trusts(registry, i, Residency.TRUST_THRESHOLD);
        }
        assertFalse(Residency.isResident(registry, Persona.UNASSIGNED, PLAYER, 0));
        assertFalse(Residency.isResident(registry, VILLAGE, null, 0));
    }

    // --- the second route --------------------------------------------------------------------------

    /**
     * {@code DESIGN.md} §5's other route, and the reason it is a deed count rather than a second
     * band: it is the fast way in for somebody who turned up at the right moment rather than the
     * patient way in for somebody who kept coming back.
     */
    @Test
    @DisplayName("defending a raid is enough on its own")
    void oneSignificantDeedIsARoute() {
        NpcRegistry registry = village(9);
        registry.remember(new UUID(77, 4),
                Deed.of(DeedType.DEFENDED_RAID, PLAYER, PLAYER, VILLAGE, 3));

        Residency.Verdict verdict = Residency.verdict(registry, VILLAGE, PLAYER, 3);
        assertTrue(verdict.granted());
        assertEquals(Residency.Route.DEED, verdict.route());
        assertTrue(verdict.defendedARaid());
    }

    @Test
    @DisplayName("three hungry people fed is a route; two is not")
    void threeFeedingsIsARoute() {
        NpcRegistry registry = village(9);
        for (int day = 0; day < Residency.FEEDINGS_REQUIRED - 1; day++) {
            registry.remember(new UUID(77, 0),
                    Deed.of(DeedType.FED_HUNGRY, PLAYER, new UUID(77, 0), VILLAGE, day));
        }
        assertFalse(Residency.isResident(registry, VILLAGE, PLAYER, 5));

        registry.remember(new UUID(77, 0), Deed.of(DeedType.FED_HUNGRY, PLAYER, new UUID(77, 0),
                VILLAGE, Residency.FEEDINGS_REQUIRED - 1));
        assertTrue(Residency.isResident(registry, VILLAGE, PLAYER, 5));
    }

    /**
     * <b>Session 06's derived deed id paying off somewhere it was not written for.</b>
     *
     * <p>One feeding watched by four people is four ring entries and one <i>deed</i>, because the id
     * is a function of the deed's own fields. Counting rows rather than events would let a player
     * buy residency by feeding one villager in front of a crowd.
     */
    @Test
    @DisplayName("one feeding four people watched is one feeding, not four")
    void witnessesDoNotMultiplyADeed() {
        NpcRegistry registry = village(9);
        Deed once = Deed.of(DeedType.FED_HUNGRY, PLAYER, new UUID(77, 0), VILLAGE, 1);
        for (int witness = 0; witness < 4; witness++) {
            registry.remember(new UUID(77, witness), once);
        }

        assertEquals(1, Residency.verdict(registry, VILLAGE, PLAYER, 1).feedings());
        assertFalse(Residency.isResident(registry, VILLAGE, PLAYER, 1));
    }

    /**
     * <b>The two-pass shortcut, held to the only thing it may not change.</b>
     *
     * <p>{@link Residency#verdict} counts bands first and reads the rings only if the band is not
     * met, because this runs on an interaction and a ring walk at {@code DESIGN.md} §8's population
     * is tens of thousands of deeds. A shortcut that changed the <i>answer</i> would be a bug wearing
     * an optimisation's clothes, so the answer is what is asserted: a settlement that would grant
     * residency both ways still grants it, and by the band.
     */
    @Test
    @DisplayName("a village that would say yes twice still says yes, and says the band did it")
    void bothRoutesAtOnceIsStillResidency() {
        NpcRegistry registry = village(9);
        for (int i = 0; i < Residency.RESIDENTS_REQUIRED; i++) {
            trusts(registry, i, Residency.TRUST_THRESHOLD);
        }
        for (int day = 0; day < Residency.FEEDINGS_REQUIRED; day++) {
            registry.remember(new UUID(77, 0),
                    Deed.of(DeedType.FED_HUNGRY, PLAYER, new UUID(77, 0), VILLAGE, day));
        }

        Residency.Verdict verdict = Residency.verdict(registry, VILLAGE, PLAYER, 0);
        assertTrue(verdict.granted());
        assertEquals(Residency.Route.BAND, verdict.route(),
                "the band is checked first, so it is what is reported");
        assertEquals(Residency.RESIDENTS_REQUIRED, verdict.residentsAtThreshold());
        assertEquals(0, verdict.feedings(),
                "and the rings were not read at all, which is the whole point of the shortcut");
    }

    @Test
    @DisplayName("a deed done somewhere else does not count here")
    void deedsAreCountedInTheirOwnSettlement() {
        NpcRegistry registry = village(9);
        for (int day = 0; day < Residency.FEEDINGS_REQUIRED; day++) {
            registry.remember(new UUID(77, 0),
                    Deed.of(DeedType.FED_HUNGRY, PLAYER, new UUID(77, 0), ELSEWHERE, day));
        }
        assertFalse(Residency.isResident(registry, VILLAGE, PLAYER, 5),
                "a villager who watched you feed somebody in the next valley has not seen you feed "
                        + "anybody here");
    }

    /**
     * The blur, arriving in a third place. A story nobody can attribute names
     * {@link Deed#UNKNOWN_ACTOR}, so it cannot be evidence that <i>you</i> did anything.
     */
    @Test
    @DisplayName("a rumour nobody can attribute earns nobody residency")
    void anUnattributedDeedIsNotEvidence() {
        NpcRegistry registry = village(9);
        for (int day = 0; day < Residency.FEEDINGS_REQUIRED; day++) {
            Deed blurred = Deed.of(DeedType.FED_HUNGRY, PLAYER, new UUID(77, 0), VILLAGE, day)
                    .retold().retold();
            assertFalse(blurred.isAttributed(), "the fixture has to actually be blurred");
            registry.remember(new UUID(77, 0), blurred);
        }
        assertFalse(Residency.isResident(registry, VILLAGE, PLAYER, 5));
        assertEquals(0, Residency.verdict(registry, VILLAGE, PLAYER, 5).feedings());
    }
}
