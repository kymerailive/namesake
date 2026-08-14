package net.namesake.social;

import net.namesake.npc.Persona;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a deed is worth to one person: the witness share, the place weight, the personality weight
 * and the rule that none of the softening ones may ever make a harmful deed cheaper.
 */
class DeedsTest {

    private static final UUID PLAYER = new UUID(0x5EAF_0000_0000_0001L, 9);

    /** Somebody the deed happened to who is nobody in these tests, so every holder is a witness. */
    private static final UUID SOMEBODY_ELSE = new UUID(0x1111_2222_3333_4444L, 5);

    private static final int DAY = 12;

    /**
     * A villager of settlement {@code home}, <b>typical unless axes are supplied</b>.
     *
     * <p>The base is {@code Personality.typical()} rather than eight zeroes, and that is the whole
     * of what centring changed for these tests. Since the close of session 05 the weight table is
     * centred on the population the generator actually produces, so nominal is what a <i>real</i>
     * villager gets — a villager with no personality at all now scores below it, because no such
     * villager exists. Every test below that expects a nominal number therefore asks for a typical
     * person, and every test about a specific personality supplies all eight axes.
     */
    private static Persona person(long seed, int home, int... axes) {
        byte[] traits = Personality.typical();
        for (int axis = 0; axis < axes.length; axis++) {
            traits[axis] = (byte) axes[axis];
        }
        return Persona.create(new UUID(9, seed), 0L).placed(home, 1, (byte) 0).withTraits(traits);
    }

    /** A deed done to {@code subject}, so {@code deltaFor} reads them as the subject. */
    private static Deed doneTo(DeedType type, int settlementId, Persona subject) {
        return Deed.of(type, PLAYER, subject.id(), settlementId, DAY);
    }

    /** A deed done to somebody else, so every persona here reads as a witness. */
    private static Deed watched(DeedType type, int settlementId) {
        return Deed.of(type, PLAYER, SOMEBODY_ELSE, settlementId, DAY);
    }

    // --- the exit criterion ------------------------------------------------------------------------

    @Test
    @DisplayName("feeding a hungry villager is worth +3 to them and +1 to each witness")
    void theExitCriterionAsArithmetic() {
        // WORKPLAN.md's exit criterion for this session, with the personality weight standing at
        // neutral so the structural numbers are the only thing under test. The harness proves the
        // same shape with real witnesses in a real level; this proves the arithmetic behind it.
        Persona fedVillager = person(1, 4);
        Persona bystander = person(2, 4);

        assertArrayEquals(new int[]{3, 3, 0, 0},
                Deeds.deltaFor(doneTo(DeedType.FED_HUNGRY, 4, fedVillager), fedVillager),
                "the subject takes the whole share");
        assertArrayEquals(new int[]{1, 1, 0, 0},
                Deeds.deltaFor(doneTo(DeedType.FED_HUNGRY, 4, fedVillager), bystander),
                "a witness takes a third of it");
    }

    @Test
    @DisplayName("who the subject is comes off the deed, not off the caller")
    void theSubjectIsReadOffTheDeed() {
        Persona villager = person(3, 4);
        Deed theirs = doneTo(DeedType.FED_HUNGRY, 4, villager);
        Deed somebodyElses = watched(DeedType.FED_HUNGRY, 4);

        assertArrayEquals(new int[]{3, 3, 0, 0}, Deeds.deltaFor(theirs, villager));
        assertArrayEquals(new int[]{1, 1, 0, 0}, Deeds.deltaFor(somebodyElses, villager));
    }

    @Test
    @DisplayName("a typical villager scores exactly nominal, on every deed type")
    void typicalTraitsGiveTheNominalNumbers() {
        // The point of centring: the numbers in DeedType are what a real villager gets, not what an
        // impossible one would. Before session 05's close this held for eight zeroes instead, and
        // the population sat 4% above nominal on a gift and 13% above on a defended raid.
        Persona typical = person(4, 0);
        for (DeedType type : DeedType.values()) {
            int[] delta = Deeds.deltaFor(doneTo(type, 0, typical), typical);
            for (int axis = 0; axis < Bond.AXIS_COUNT; axis++) {
                assertEquals(type.delta(axis), delta[axis],
                        type + " axis " + axis + " must be its own nominal value for a typical villager");
            }
        }
    }

    // --- the rule that matters -----------------------------------------------------------------------

    @Test
    @DisplayName("a placid villager does not charge less for a blow; a vengeful one charges more")
    void negativesAreNeverSoftenedByPersonality() {
        // Boldness and a cool temper both pull the STRUCK_RESIDENT column below neutral.
        Persona placid = person(10, 1, 0, 0, 60, 0, 0, 0, -60, 0);
        Persona vengeful = person(11, 1, 0, 0, 0, 0, 60, 0, 80, 0);
        Persona neutral = person(12, 1);

        assertTrue(Personality.scale(placid, DeedType.STRUCK_RESIDENT) < Personality.NEUTRAL,
                "the fixture must actually be below neutral or this test proves nothing");
        assertTrue(Personality.scale(vengeful, DeedType.STRUCK_RESIDENT) > Personality.NEUTRAL);

        int neutralTrust = Deeds.deltaFor(doneTo(DeedType.STRUCK_RESIDENT, 1, neutral), neutral)[Bond.TRUST];
        int placidTrust = Deeds.deltaFor(doneTo(DeedType.STRUCK_RESIDENT, 1, placid), placid)[Bond.TRUST];
        int vengefulTrust = Deeds.deltaFor(doneTo(DeedType.STRUCK_RESIDENT, 1, vengeful), vengeful)[Bond.TRUST];

        assertEquals(neutralTrust, placidTrust,
                "a weight below neutral must be ignored on the way down");
        assertTrue(vengefulTrust < neutralTrust,
                "a weight above neutral must still bite: " + vengefulTrust + " vs " + neutralTrust);
    }

    @Test
    @DisplayName("a harmful axis always moves by at least one, however small its share")
    void aNegativeAlwaysLands() {
        // Half a witness's share, three quarters for being an outsider, at the lightest severity a
        // blow can carry: −6 becomes −0.45, which rounds to nothing at all. "He hit me but it did
        // not count" is the same bug as an uncapped apology.
        Deed glancing = watched(DeedType.STRUCK_RESIDENT, 1).withSeverity((byte) 20);
        int[] delta = Deeds.deltaFor(glancing, person(20, 99));

        assertEquals(-1, delta[Bond.TRUST]);
        assertTrue(delta[Bond.WARMTH] <= -1);
    }

    @Test
    @DisplayName("rounding is away from zero, so it never shaves a negative")
    void roundingIsSymmetric() {
        assertEquals(5, Deeds.round(4.5F));
        assertEquals(-5, Deeds.round(-4.5F), "Math.round would give -4 and lose the half");
        assertEquals(0, Deeds.round(0.4F));
        assertEquals(0, Deeds.round(-0.4F));
    }

    // --- the structural weights -----------------------------------------------------------------------

    @Test
    @DisplayName("a witness who does not live where it happened records less of it")
    void outsidersRecordLess() {
        Deed defended = watched(DeedType.DEFENDED_RAID, 4);
        int resident = Deeds.deltaFor(defended, person(30, 4))[Bond.RESPECT];
        int passerby = Deeds.deltaFor(defended, person(31, 7))[Bond.RESPECT];
        int unsettled = Deeds.deltaFor(defended, person(32, Persona.UNASSIGNED))[Bond.RESPECT];

        assertEquals(12, resident, "a resident of the raided village takes it whole");
        assertEquals(9, passerby, "a traveller takes three quarters — it is not their village");
        assertEquals(passerby, unsettled, "and neither is nowhere");
    }

    @Test
    @DisplayName("the subject is never discounted for being away from home")
    void theSubjectIsNeverAnOutsider() {
        Persona local = person(40, 4);
        Persona visitor = person(41, 7);

        assertArrayEquals(Deeds.deltaFor(doneTo(DeedType.FED_HUNGRY, 4, local), local),
                Deeds.deltaFor(doneTo(DeedType.FED_HUNGRY, 4, visitor), visitor),
                "it happened to them, wherever they were standing");
    }

    @Test
    @DisplayName("a heavier blow is worth more than a glancing one")
    void severityScalesTheBlow() {
        Persona victim = person(50, 1);
        Deed light = doneTo(DeedType.STRUCK_RESIDENT, 1, victim).withSeverity(SocialEvents.severityOf(1.0F));
        Deed heavy = doneTo(DeedType.STRUCK_RESIDENT, 1, victim).withSeverity(SocialEvents.severityOf(8.0F));

        assertTrue(Deeds.deltaFor(heavy, victim)[Bond.WARMTH] < Deeds.deltaFor(light, victim)[Bond.WARMTH],
                "eight points of damage must cost more than one");
        assertEquals(Deed.NOMINAL, SocialEvents.severityOf(8.0F), "a full blow is nominal");
        assertTrue(SocialEvents.severityOf(0.1F) >= 20, "and the lightest still registers");
    }

    @Test
    @DisplayName("hearsay is worth less than having seen it, before session 08 makes any")
    void confidenceScales() {
        Persona holder = person(60, 4);
        Deed firstHand = doneTo(DeedType.DEFENDED_RAID, 4, holder);
        Deed secondHand = new Deed(firstHand.typeId(), firstHand.actor(), firstHand.subject(),
                firstHand.settlementId(), firstHand.gameDay(), firstHand.severity(), (byte) 50);

        assertTrue(Deeds.deltaFor(secondHand, holder)[Bond.RESPECT]
                < Deeds.deltaFor(firstHand, holder)[Bond.RESPECT]);
    }

    // --- the ruling the owner has to make ---------------------------------------------------------

    @Test
    @DisplayName("the same gift lands differently on a suspicious smith and a warm innkeeper")
    void theSameGiftLandsDifferently() {
        // The second half of this session's exit criterion. Machine-checkable as two different
        // numbers; whether the difference is *legible* is the owner's ruling, and /namesake debug
        // bonds prints the multiplier next to the name so it can be read in a running game.
        Persona smith = person(70, 1, -40, 0, 0, 0, 30, 10, 50, -30);
        Persona innkeeper = person(71, 1, 60, 0, 0, 25, 0, 20, -30, 55);

        int[] toSmith = Deeds.deltaFor(doneTo(DeedType.GIFT_WANTED, 1, smith), smith);
        int[] toInnkeeper = Deeds.deltaFor(doneTo(DeedType.GIFT_WANTED, 1, innkeeper), innkeeper);

        // Session 05 shipped these as {1,2} and {3,4}; session 07 widened Personality.SPREAD from
        // 1.0 to 1.6 to close the week-apart gap the owner ruled at that session's close, and this
        // is the same two fixtures re-measured through it. The loaf is now worth four times as much
        // to the innkeeper as to the smith rather than twice.
        assertArrayEquals(new int[]{1, 1, 0, 0}, toSmith);
        assertArrayEquals(new int[]{4, 5, 0, 0}, toInnkeeper);
        assertTrue(toInnkeeper[Bond.WARMTH] - toSmith[Bond.WARMTH] >= 2,
                "the difference has to be big enough for a person to notice, not just for a test");
        // And the fixtures are extremes I chose. PersonalityDistributionTest is what says whether
        // the villagers a real world generates differ by anything like this much — a playtest at
        // the close of session 05 found they span about half of it, which is what moved personality
        // onto the daily ceiling as well as the step, and session 07's harness is what set the
        // magnitude against a hundred simulated days rather than against anyone's eye.
    }
}
