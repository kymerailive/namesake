package net.namesake.social;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The five claims {@code WORKPLAN.md} names for session 05, and the arithmetic underneath them.
 *
 * <p>Every one of these is a ten-millisecond unit test rather than a harness leg, which is the line
 * the ledger draws: the in-game harness is for what only a running game can show. A daily cap and a
 * decay curve are not that.
 */
class BondTest {

    private static Bond bond(int trust, int warmth, int respect, int fear, int day, int peak) {
        return new Bond((byte) trust, (byte) warmth, (byte) respect, (byte) fear,
                (short) 0, day, (short) 0, (byte) peak);
    }

    private static int[] delta(int trust, int warmth, int respect, int fear) {
        return new int[]{trust, warmth, respect, fear};
    }

    // --- the daily cap ---------------------------------------------------------------------------

    @Test
    @DisplayName("nine feedings in one day move an axis by eight and no more")
    void theCapHolds() {
        // WORKPLAN.md's exit criterion, as arithmetic: FED_HUNGRY is worth +3 to the subject, and
        // repeating it nine times in a day must land on the cap rather than on twenty-seven.
        Bond bond = Bond.fresh(7);
        for (int i = 0; i < 9; i++) {
            bond = bond.apply(delta(3, 3, 0, 0), 7, Bond.DAILY_CAP);
        }

        assertEquals(Bond.DAILY_CAP, bond.trust(), "trust must stop at the daily cap");
        assertEquals(Bond.DAILY_CAP, bond.warmth(), "warmth must stop at the daily cap");
        assertEquals(Bond.DAILY_CAP, bond.gainedToday(Bond.TRUST));
        assertEquals(Bond.DAILY_CAP, bond.gainedToday(Bond.WARMTH));
    }

    @Test
    @DisplayName("the cap is per axis, not per bond")
    void theCapIsPerAxis() {
        Bond bond = Bond.fresh(1);
        for (int i = 0; i < 4; i++) {
            bond = bond.apply(delta(3, 0, 0, 0), 1, Bond.DAILY_CAP);
        }
        // Trust is spent; warmth has its own allowance and has not been touched.
        assertEquals(Bond.DAILY_CAP, bond.trust());
        assertEquals(0, bond.gainedToday(Bond.WARMTH));

        bond = bond.apply(delta(3, 3, 0, 0), 1, Bond.DAILY_CAP);
        assertEquals(Bond.DAILY_CAP, bond.trust(), "trust was already at the cap");
        assertEquals(3, bond.warmth(), "warmth had a full allowance of its own");
    }

    @Test
    @DisplayName("the allowance resets when the day turns, and only then")
    void theCapResetsOnANewDay() {
        Bond bond = Bond.fresh(3);
        for (int i = 0; i < 5; i++) {
            bond = bond.apply(delta(3, 0, 0, 0), 3, Bond.DAILY_CAP);
        }
        assertEquals(Bond.DAILY_CAP, bond.trust());

        Bond sameDay = bond.apply(delta(3, 0, 0, 0), 3, Bond.DAILY_CAP);
        assertEquals(Bond.DAILY_CAP, sameDay.trust(), "the same day must not hand out a second allowance");

        Bond nextDay = bond.apply(delta(3, 0, 0, 0), 4, Bond.DAILY_CAP);
        assertEquals(Bond.DAILY_CAP + 3, nextDay.trust(), "a new day is a new allowance");
    }

    @Test
    @DisplayName("one deed can be capped on one axis and unclipped on another")
    void mixedSignsTakeSeparatePaths() {
        // STRUCK_RESIDENT's shape: respect rises a little and everything else falls a lot. The
        // rising half is an allowance to spend; the falling half is not.
        Bond bond = Bond.fresh(2);
        for (int i = 0; i < 12; i++) {
            bond = bond.apply(delta(-6, 0, 1, 0), 2, Bond.DAILY_CAP);
        }
        assertEquals(Bond.DAILY_CAP, bond.respect(), "the positive axis stopped at the cap");
        assertEquals(-64, bond.trust(), "the negative axis ran all the way to its floor");
    }

    @Test
    @DisplayName("the allowance passed in is what bounds a day, not the base cap")
    void theAllowanceIsWhatBounds() {
        // The ceiling ruling, at the level of the record. Bond.apply must spend against what it was
        // handed; a version that quietly used DAILY_CAP would pass every other test in this file,
        // because every other test passes DAILY_CAP.
        Bond receptive = Bond.fresh(1);
        Bond closed = Bond.fresh(1);
        for (int i = 0; i < 6; i++) {
            receptive = receptive.apply(delta(3, 0, 0, 0), 1, 11);
            closed = closed.apply(delta(3, 0, 0, 0), 1, 5);
        }

        assertEquals(11, receptive.trust(), "a bigger allowance must let a day go further");
        assertEquals(5, closed.trust(), "and a smaller one must stop it sooner");
    }

    @Test
    @DisplayName("an allowance too big for the counters is clamped rather than wrapped")
    void anOversizedAllowanceIsClamped() {
        // Belt to the build-time brace in PersonalityTest. Four bits per axis hold 0..15; a caller
        // passing 40 must not wrap into the neighbouring axis's counter and hand out an allowance
        // nobody has spent.
        Bond bond = Bond.fresh(0);
        for (int i = 0; i < 30; i++) {
            bond = bond.apply(delta(3, 3, 0, 0), 0, 40);
        }
        assertEquals(15, bond.gainedToday(Bond.TRUST));
        assertEquals(15, bond.gainedToday(Bond.WARMTH), "the neighbouring counter is intact");
    }

    @Test
    @DisplayName("the cap fits in the four bits it is stored in")
    void theCapFitsItsNibble() {
        // gainedToday packs four counters into one short. A cap raised past fifteen would wrap into
        // the next axis's counter and read as a bond that had already spent its allowance on
        // something nobody did.
        assertTrue(Bond.DAILY_CAP <= 15,
                "DAILY_CAP is stored in four bits per axis; raising it past 15 needs a wider field "
                        + "and a schema version");

        Bond bond = Bond.fresh(0)
                .apply(delta(8, 0, 0, 0), 0, Bond.DAILY_CAP)
                .apply(delta(0, 8, 0, 0), 0, Bond.DAILY_CAP)
                .apply(delta(0, 0, 8, 0), 0, Bond.DAILY_CAP)
                .apply(delta(0, 0, 0, 8), 0, Bond.DAILY_CAP);
        for (int axis = 0; axis < Bond.AXIS_COUNT; axis++) {
            assertEquals(Bond.DAILY_CAP, bond.gainedToday(axis),
                    "axis " + axis + "'s counter was corrupted by its neighbours");
        }
    }

    // --- negatives -------------------------------------------------------------------------------

    @Test
    @DisplayName("a negative is not capped, and does not spend the day's allowance")
    void negativesBypassTheCap() {
        Bond bond = Bond.fresh(5);
        for (int i = 0; i < 4; i++) {
            bond = bond.apply(delta(-6, 0, 0, 0), 5, Bond.DAILY_CAP);
        }
        assertEquals(-24, bond.trust(), "four blows is four blows, whatever day it is");
        assertEquals(0, bond.gainedToday(Bond.TRUST),
                "a negative must not consume the allowance, or a strike today would make "
                        + "tomorrow's apology cheaper");
    }

    // --- floors and the ceiling --------------------------------------------------------------------

    @Test
    @DisplayName("warmth floors at zero — there is no negative warmth, only its absence")
    void warmthFloorsAtZero() {
        Bond bond = bond(0, 30, 0, 0, 1, 30).apply(delta(0, -100, 0, 0), 1, Bond.DAILY_CAP);
        assertEquals(0, bond.warmth());
        assertEquals(0, Bond.floorOf(Bond.WARMTH));
        assertEquals(0, Bond.floorOf(Bond.FEAR), "'unafraid' is not a feeling either");
    }

    @Test
    @DisplayName("trust and respect floor at -64 — active distrust and contempt are real states")
    void trustAndRespectFloorAtMinusSixtyFour() {
        Bond bond = Bond.fresh(1).apply(delta(-127, 0, -127, 0), 1, Bond.DAILY_CAP);
        assertEquals(-64, bond.trust());
        assertEquals(-64, bond.respect());
        assertEquals(-64, Bond.floorOf(Bond.TRUST));
        assertEquals(-64, Bond.floorOf(Bond.RESPECT));
    }

    @Test
    @DisplayName("no axis passes 100")
    void everyAxisStopsAtTheCeiling() {
        Bond bond = Bond.fresh(0);
        for (int day = 0; day < 40; day++) {
            bond = bond.apply(delta(8, 8, 8, 8), day, Bond.DAILY_CAP);
        }
        for (int axis = 0; axis < Bond.AXIS_COUNT; axis++) {
            assertEquals(Bond.ceilingOf(), bond.axis(axis), "axis " + axis + " passed the ceiling");
        }
    }

    // --- decay -----------------------------------------------------------------------------------

    @Test
    @DisplayName("warmth decays toward four tenths of its own high-water mark, never below")
    void decayStopsAtTheTarget() {
        Bond bond = bond(0, 50, 0, 0, 0, 50);
        assertEquals(40, bond.decayedTo(10).warmth(), "ten days away, ten points cooler");
        assertEquals(20, bond.decayedTo(30).warmth(), "thirty days away lands on the floor");
        assertEquals(20, bond.decayedTo(31).warmth(), "and does not go through it");
        assertEquals(20, Math.round(50 * Bond.DECAY_TARGET), "the floor is peak x 0.4");
    }

    @Test
    @DisplayName("the peak is a high-water mark and survives being knocked down")
    void thePeakIsAHighWaterMark() {
        Bond risen = Bond.fresh(0).apply(delta(0, 5, 0, 0), 0, Bond.DAILY_CAP);
        assertEquals(5, risen.peakWarmth(), "the peak follows warmth up");

        Bond warm = bond(0, 80, 0, 0, 5, 80);
        Bond struck = warm.apply(delta(0, -60, 0, 0), 5, Bond.DAILY_CAP);
        assertEquals(20, struck.warmth());
        assertEquals(80, struck.peakWarmth(), "the peak records what they once felt, not what they feel");
        // And a bond knocked below its own decay floor is not healed by being left alone.
        assertEquals(20, struck.decayedTo(200).warmth());
    }

    @Test
    @DisplayName("decay is lazy: reading a bond does not change it")
    void decayIsLazy() {
        Bonds table = new Bonds();
        java.util.UUID holder = new java.util.UUID(1, 1);
        java.util.UUID about = new java.util.UUID(2, 2);
        table.put(holder, about, bond(0, 50, 0, 0, 0, 50));

        assertEquals(20, table.at(holder, about, 100).warmth(), "the view is up to date");
        assertEquals(50, table.stored(holder, about).orElseThrow().warmth(),
                "the stored value is untouched — a read that wrote would mark the whole registry "
                        + "dirty every time anything looked at a villager");
    }

    @Test
    @DisplayName("decay is idempotent")
    void decayIsIdempotent() {
        Bond bond = bond(0, 90, 0, 0, 0, 90);
        Bond once = bond.decayedTo(20);
        assertEquals(once, once.decayedTo(20), "twice on the same day is once");
        assertSame(once, once.decayedTo(20), "and cheap enough to be free");
        assertNotEquals(once, once.decayedTo(21), "a later day is a different answer");
    }

    @Test
    @DisplayName("a clock that runs backwards does not decay a bond upwards")
    void anEarlierDayChangesNothing() {
        Bond bond = bond(0, 50, 0, 0, 30, 50);
        assertSame(bond, bond.decayedTo(29));
        assertSame(bond, bond.decayedTo(30));
    }

    @Test
    @DisplayName("one catch-up decays at most 64 days' worth")
    void theDayDeltaIsClamped() {
        // On WORKPLAN.md's never-cut list. The peak is deliberately below the warmth here — the
        // state a hand-edited save or a future fixer can produce — because with a real peak the
        // decay floor hides the clamp entirely, and a bound nothing can observe is a bound nobody
        // will notice has been removed.
        Bond bond = bond(0, 100, 0, 0, 0, 0);
        assertEquals(100 - Bond.MAX_DAY_DELTA, bond.decayedTo(1000).warmth(),
                "an in-game decade must catch up by the clamp, not by ten thousand");
        assertEquals(50, bond.decayedTo(50).warmth(), "under the clamp, a day is a point");
    }

    // --- shape -----------------------------------------------------------------------------------

    @Test
    @DisplayName("a bond that has never been anything says so")
    void freshIsNothing() {
        assertTrue(Bond.fresh(4).isNothing());
        assertTrue(Bond.fresh(4).apply(delta(0, 0, 0, 0), 4, Bond.DAILY_CAP).isNothing());
        assertTrue(!Bond.fresh(4).apply(delta(1, 0, 0, 0), 4, Bond.DAILY_CAP).isNothing());
    }

    @Test
    @DisplayName("a delta of the wrong width is a programming error, not a silent truncation")
    void aDeltaMustHaveFourAxes() {
        assertThrows(IllegalArgumentException.class,
                () -> Bond.fresh(0).apply(new int[]{1, 2, 3}, 0, Bond.DAILY_CAP));
    }
}
