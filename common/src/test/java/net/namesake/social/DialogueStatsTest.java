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
 * The arithmetic behind the two live-world instruments, over registries small enough to check by
 * hand.
 *
 * <p>{@code SimulationTest} asks whether a hundred days produces sensible numbers;
 * this asks whether the numbers mean what the report says they mean. They are different questions
 * and the second one is where a percentile quietly reads a rank one past the end.
 */
class DialogueStatsTest {

    private static final UUID PLAYER = new UUID(0x5EAF_0000_0000_0001L, 9);
    private static final UUID SOMEBODY_ELSE = new UUID(0x5EAF_0000_0000_0002L, 9);

    private static Persona resident(int index) {
        return Persona.create(new UUID(77, index), 0L).placed(0, index / 3, (byte) 0);
    }

    /** A registry with {@code count} residents, none of whom has met anybody. */
    private static NpcRegistry village(int count) {
        NpcRegistry registry = new NpcRegistry();
        for (int i = 0; i < count; i++) {
            registry.put(resident(i));
        }
        return registry;
    }

    private static Deed gift(UUID subject, int day) {
        return Deed.of(DeedType.GIFT_WANTED, PLAYER, subject, 0, day);
    }

    // --- the absences -----------------------------------------------------------------------------

    @Test
    @DisplayName("an empty world reports zero rather than throwing or reading past the end")
    void anEmptyWorldIsAnAnswer() {
        DialogueStats stats = DialogueStats.of(new NpcRegistry(), PLAYER, 0);

        assertEquals(0, stats.personas());
        assertEquals(0, stats.metTheViewer());
        assertEquals(0, stats.observedMaximum(Bond.WARMTH));
        assertEquals(0, stats.percentile(Bond.WARMTH, 50));
        assertEquals(0F, stats.ringOccupancy());
        assertTrue(stats.deepestRing().isEmpty());
        assertTrue(stats.medianRing().isEmpty());
        assertEquals(-1, stats.contactDaysTo(20),
                "nobody earning anything is a real answer, and it is the LNK failure with the "
                        + "arithmetic done. It must not read as zero days.");
    }

    @Test
    @DisplayName("a village nobody has visited is not the same as an empty village")
    void aVillageWithNoContactStillHasPeopleInIt() {
        DialogueStats stats = DialogueStats.of(village(9), PLAYER, 40);

        assertEquals(9, stats.personas());
        assertEquals(0, stats.metTheViewer(), "nobody has done anything in front of them");
        assertEquals(9, stats.standings().size(), "they still exist, and the report says so");
    }

    // --- the unit ---------------------------------------------------------------------------------

    /**
     * <b>Warmth per in-game day of contact, counted in days rather than in deeds.</b>
     *
     * <p>The distinction is the whole of why the unit was ruled the way it was: three gifts on one
     * day is one day of contact, not three, because the daily cap means the second and third moved
     * nothing. A per-deed rate would report a third of the truth for a player who gave three.
     */
    @Test
    @DisplayName("three deeds on one day are one day of contact, not three")
    void contactIsCountedInDays() {
        NpcRegistry registry = village(1);
        Persona only = registry.all().iterator().next();
        for (int i = 0; i < 3; i++) {
            // Three distinct deeds — different types, so Deed.id() does not collapse them — all on
            // the same in-game day.
            DeedType type = DeedType.values()[i];
            registry.remember(only.id(), Deed.of(type, PLAYER, only.id(), 0, 5));
        }
        registry.putBond(only.id(), PLAYER, Bond.fresh(5).apply(new int[]{0, 6, 0}, 5, 8));

        DialogueStats.Standing standing = DialogueStats.of(registry, PLAYER, 5).standings().get(0);
        assertEquals(1, standing.contactDays(), "one day, whatever happened on it");
        assertEquals(3, standing.ringSlots(), "and three memories of it");
        assertEquals(6F, standing.perContactDay(), 0.001F);
    }

    /**
     * <b>The second column of the earn-rate report, and the reason it exists.</b>
     *
     * <p>Per <i>contact</i> day ignores the decay; per day <i>elapsed</i> is what a player who does
     * not come back actually experiences. Eight warmth earned on day 30 and read on day 39 is not
     * eight warmth — it is four, because warmth falls a point a day toward four tenths of its
     * high-water mark. The two columns disagreeing is the mechanic working.
     */
    @Test
    @DisplayName("elapsed days count from the oldest contact the ring holds, and show the decay")
    void elapsedCountsFromFirstContactAndShowsTheDecay() {
        NpcRegistry registry = village(1);
        Persona only = registry.all().iterator().next();
        registry.remember(only.id(), gift(only.id(), 30));
        registry.putBond(only.id(), PLAYER, Bond.fresh(30).apply(new int[]{0, 10, 0}, 30, 8));

        DialogueStats.Standing standing = DialogueStats.of(registry, PLAYER, 39).standings().get(0);
        assertEquals(10, standing.spanDays(), "day 30 to day 39 inclusive, not day 0 to day 39");
        assertEquals(8, standing.bond().peakWarmth(), "the allowance of 8 is what actually landed");
        assertEquals(3, standing.bond().warmth(),
                "nine days of absence, floored at four tenths of the peak");
        assertEquals(0.3F, standing.perElapsedDay(), 0.001F);
        assertEquals(3F, standing.perContactDay(), 0.001F,
                "one day of contact, and the decay is invisible to this column by design");
    }

    @Test
    @DisplayName("a deed by somebody else is not contact with you")
    void anotherPlayersDeedIsNotYourContact() {
        // Per-player scoping, at the level of the instrument rather than of the bond. The owner's
        // session 06 playtest found this by arriving as a fresh username and seeing +0 everywhere;
        // this is the same claim where a test can hold it.
        NpcRegistry registry = village(1);
        Persona only = registry.all().iterator().next();
        registry.remember(only.id(), Deed.of(DeedType.GIFT_WANTED, SOMEBODY_ELSE, only.id(), 0, 3));

        DialogueStats.Standing standing = DialogueStats.of(registry, PLAYER, 9).standings().get(0);
        assertEquals(0, standing.contactDays(), "somebody else fed them; you did not");
        assertEquals(1, standing.ringSlots(), "and they remember it perfectly well");
        assertFalse(standing.hasMetTheViewer());
    }

    // --- the percentiles ---------------------------------------------------------------------------

    @Test
    @DisplayName("percentiles are taken over those who have met you, not over the whole world")
    void percentilesIgnoreStrangers() {
        // The failure this catches: four hundred personas in a world the player has walked past
        // would drag every percentile to zero and report that nobody earns anything, which is the
        // exact shape of the wrong conclusion this session exists to prevent.
        NpcRegistry registry = village(100);
        int index = 0;
        for (Persona persona : registry.all()) {
            if (index >= 4) {
                break;
            }
            index++;
            registry.remember(persona.id(), gift(persona.id(), 1));
            // Three, six, nine, twelve. All inside the fifteen a four-bit counter can hold, because
            // Bond.apply clamps the allowance to that whatever a caller asks for.
            registry.putBond(persona.id(), PLAYER,
                    Bond.fresh(1).apply(new int[]{0, index * 3, 0}, 1, 15));
        }

        DialogueStats stats = DialogueStats.of(registry, PLAYER, 1);
        assertEquals(4, stats.metTheViewer());
        assertTrue(stats.percentile(Bond.WARMTH, 50) > 0,
                "ninety-six strangers must not make the median of four friendships zero");
        assertEquals(12, stats.observedMaximum(Bond.WARMTH));
    }

    @Test
    @DisplayName("the observed maximum is the number LNK never had")
    void theObservedMaximumIsExact() {
        NpcRegistry registry = village(3);
        int warmth = 7;
        for (Persona persona : registry.all()) {
            registry.remember(persona.id(), gift(persona.id(), 0));
            registry.putBond(persona.id(), PLAYER,
                    Bond.fresh(0).apply(new int[]{0, warmth, 0}, 0, 15));
            warmth += 4;
        }
        DialogueStats stats = DialogueStats.of(registry, PLAYER, 0);

        assertEquals(15, stats.observedMaximum(Bond.WARMTH));
        assertEquals(7, stats.observedMinimum(Bond.WARMTH));
    }

    // --- the rings --------------------------------------------------------------------------------

    @Test
    @DisplayName("a ring's shape is its occupancy, its reach and its mix")
    void aRingReportsItsShape() {
        NpcRegistry registry = village(1);
        Persona only = registry.all().iterator().next();
        registry.remember(only.id(), Deed.of(DeedType.FED_HUNGRY, PLAYER, only.id(), 0, 4));
        registry.remember(only.id(), Deed.of(DeedType.FED_HUNGRY, PLAYER, only.id(), 0, 9));
        registry.remember(only.id(), Deed.of(DeedType.GIFT_WANTED, PLAYER, only.id(), 0, 9));

        DialogueStats.Ring ring = DialogueStats.of(registry, PLAYER, 9).deepestRing().orElseThrow();
        assertEquals(3, ring.slots());
        assertEquals(4, ring.oldestDay());
        assertEquals(9, ring.newestDay());
        assertEquals(6, ring.reachDays(), "day 4 to day 9 inclusive");
        assertFalse(ring.isFull());
        assertEquals(2, ring.mix().get(DeedType.FED_HUNGRY));
        assertEquals(DeedType.FED_HUNGRY, ring.byFrequency().get(0).getKey());
    }

    /**
     * <b>The artefact the owner's two parked rulings are made against.</b>
     *
     * <p>Session 06 declined to rule on the ring's depth or on its eviction policy against a
     * two-entry fixture. What a full ring can say for itself is how far back it still reaches, and
     * that is the number: a ring holding a capacity's worth of eight more deeds has forgotten the
     * first eight and its oldest survivor is the ninth.
     */
    @Test
    @DisplayName("a full ring reports how far back it still reaches, which is what it forgot")
    void aFullRingSaysHowFarBackItReaches() {
        NpcRegistry registry = village(1);
        Persona only = registry.all().iterator().next();
        int emitted = Memories.RING_CAPACITY + 8;
        for (int day = 0; day < emitted; day++) {
            registry.remember(only.id(), gift(only.id(), day));
        }

        DialogueStats.Ring ring = DialogueStats.of(registry, PLAYER, emitted - 1)
                .deepestRing().orElseThrow();
        assertTrue(ring.isFull());
        assertEquals(Memories.RING_CAPACITY, ring.slots());
        assertEquals(8, ring.oldestDay(), "the first eight days are gone");
        assertEquals(emitted - 1, ring.newestDay());
        assertEquals(Memories.RING_CAPACITY, ring.reachDays());
    }

    @Test
    @DisplayName("occupancy is a fraction of the world's capacity, not of the rings that exist")
    void occupancyIsAgainstTheWholeWorld() {
        // Six villagers, one of whom holds four deeds. Reporting four out of one ring's thirty-two
        // would say 12% and mean nothing; four out of six villagers' capacity is the number that
        // tells you whether the ring is under pressure anywhere.
        NpcRegistry registry = village(6);
        Persona first = registry.all().iterator().next();
        for (int day = 0; day < 4; day++) {
            registry.remember(first.id(), gift(first.id(), day));
        }

        DialogueStats stats = DialogueStats.of(registry, PLAYER, 4);
        assertEquals(4, stats.deedsHeld());
        assertEquals(0, stats.fullRings());
        assertEquals(4F / (6 * Memories.RING_CAPACITY), stats.ringOccupancy(), 1.0E-6F);
        assertEquals(1, stats.rings().size(), "only one villager remembers anything");
    }

    @Test
    @DisplayName("the median ring is the typical villager's memory, not the busiest one's")
    void theMedianRingIsTypical() {
        NpcRegistry registry = village(5);
        int deeds = 1;
        for (Persona persona : registry.all()) {
            for (int day = 0; day < deeds; day++) {
                registry.remember(persona.id(), gift(persona.id(), day));
            }
            deeds += 2;
        }

        DialogueStats stats = DialogueStats.of(registry, PLAYER, 10);
        assertEquals(9, stats.deepestRing().orElseThrow().slots());
        assertEquals(5, stats.medianRing().orElseThrow().slots(),
                "rings sorted deepest first, so the middle of five is the third");
    }

    // --- the ladder --------------------------------------------------------------------------------

    /**
     * <b>"Never" has to survive the case it is actually for.</b>
     *
     * <p>{@code anEmptyWorldIsAnAnswer} covers a world where nobody has met anybody, and a breakage
     * that made a zero rate report zero days rather than never turned it green — because that path
     * returns early on an empty list and the interesting branch was never reached. The case that
     * matters is a village that has <i>met</i> the player and is still earning nothing, which is not
     * hypothetical: a witness gains one warmth and the decay takes it back the next day, so it is
     * what most of a real village looks like.
     */
    @Test
    @DisplayName("a village that has met you and earned nothing reads as never, not as zero days")
    void earningNothingIsNeverRatherThanImmediately() {
        NpcRegistry registry = village(3);
        for (Persona persona : registry.all()) {
            // Met on day 1, gained a point, and it decayed away by day 40 — the bystander's whole
            // arc, and the reason the median rate in a real village is routinely zero.
            registry.remember(persona.id(), gift(persona.id(), 1));
            registry.putBond(persona.id(), PLAYER,
                    Bond.fresh(1).apply(new int[]{0, 1, 0}, 1, 8));
        }

        DialogueStats stats = DialogueStats.of(registry, PLAYER, 40);
        assertEquals(3, stats.metTheViewer(), "they all met the player");
        assertEquals(0, stats.observedMaximum(Bond.WARMTH), "and none of them kept anything");
        assertEquals(-1, stats.contactDaysTo(20),
                "a rate of zero must read as never. Reporting zero days would say a threshold is "
                        + "reached immediately by somebody who will never reach it at all, which is "
                        + "the LNK failure with the sign flipped.");
    }

    @Test
    @DisplayName("days to a target come from the median rate and round up")
    void daysToATargetRoundUp() {
        NpcRegistry registry = village(3);
        for (Persona persona : registry.all()) {
            registry.remember(persona.id(), gift(persona.id(), 0));
            registry.remember(persona.id(), gift(persona.id(), 1));
            registry.putBond(persona.id(), PLAYER,
                    Bond.fresh(0).apply(new int[]{0, 3, 0}, 0, 8)
                            .apply(new int[]{0, 3, 0}, 1, 8));
        }

        DialogueStats stats = DialogueStats.of(registry, PLAYER, 1);
        DialogueStats.Standing standing = stats.standings().get(0);
        // Three on day 0, one lost to decay overnight, three more on day 1: five over two days.
        assertEquals(5, standing.bond().warmth());
        assertEquals(2.5F, standing.perContactDay(), 0.001F);
        assertEquals(8, stats.contactDaysTo(20), "20 / 2.5 is 8 exactly");
        assertEquals(9, stats.contactDaysTo(21), "21 / 2.5 is 8.4, and part of a day is a day");
    }
}
