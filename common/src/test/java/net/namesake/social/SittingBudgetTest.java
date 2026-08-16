package net.namesake.social;

import net.namesake.sim.PlayerModel;
import net.namesake.sim.Simulation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>Session 15's exit criterion, in the half a test can have an opinion about.</b>
 *
 * <p>The criterion is <i>a stranger plays 45 minutes and can describe, unprompted, something a
 * villager remembered about them</i>. Whether a person says anything is not machine-checkable and is
 * the owner's; <b>whether the sentence is available to be said inside the sitting is arithmetic</b>,
 * and that is what this holds.
 *
 * <p><b>Forty-five minutes is 54,000 ticks, or 2.25 in-game days.</b> That number is what forced
 * every other ruling in the session: at a residency threshold of 28 the third resident crosses on
 * day 41, so the name swap — which is the pitch — is out of reach of a stranger by a factor of
 * eighteen. What is left is the Notice Board, which reads a bond of zero, and the second-hand line,
 * which needs the story to have travelled.
 *
 * <p>So this asks the one question the criterion actually rests on: <b>does a deed done in the first
 * minutes of a sitting reach a villager in the next village before the sitting ends?</b>
 *
 * <p>Counted in <b>in-game days</b> and never in wall clock, per this ledger's rule that no CI
 * assertion may compare against a clock — and it is a stronger measurement for it, because an
 * in-game day is a property of the code where a millisecond is a property of a runner.
 */
class SittingBudgetTest {

    /** 45 real minutes at 20 ticks a second. */
    static final int SITTING_TICKS = 45 * 60 * 20;

    /** 54,000 / 24,000. The number every ruling in session 15 was made against. */
    static final float SITTING_DAYS = SITTING_TICKS / 24_000F;

    @Test
    @DisplayName("a 45-minute sitting is 2.25 in-game days, which is the number the session was ruled against")
    void theSittingIsTwoAndAQuarterDays() {
        assertTrue(SITTING_DAYS > 2.2F && SITTING_DAYS < 2.3F,
                () -> "45 minutes is " + SITTING_DAYS + " in-game days");
        assertTrue(SITTING_DAYS < Residency.RESIDENTS_REQUIRED, "if a sitting were ever long enough "
                + "to reach residency the criterion could rest on the name swap, and session 15's "
                + "whole ordering — the Notice Board first — would be wrong");
    }

    /**
     * <b>The sentence is available inside the sitting, and with a day and a half to spare.</b>
     *
     * <p>A deed done in village A reaches village B on the in-game day <i>after</i> it happened —
     * {@code DESIGN.md} §2's cross-settlement delay, derived from {@code Deed.gameDay} rather than
     * scheduled. So the whole chain a stranger needs is: arrive, do something in front of somebody,
     * walk down the road, and read a board. This measures it through the shipped record layer rather
     * than asserting the arithmetic that produced it.
     *
     * <p>What it does <b>not</b> claim: that a stranger will do those things, find the road, or
     * think to right-click a lectern. Those are the criterion and they are the owner's.
     */
    @Test
    @DisplayName("a deed done at the start of a sitting reaches the next village before it ends")
    void theSecondHandLineIsReachableInsideTheSitting() {
        Simulation.Outcome outcome = Simulation.run(
                Simulation.Plan.standard(20260816L, 4, PlayerModel.ATTENTIVE).withNeighbour());

        int arrivedOnDay = -1;
        for (Simulation.Spread story : outcome.spread()) {
            for (int day = 0; day < story.away().length; day++) {
                if (story.away()[day] > 0 && (arrivedOnDay < 0 || day < arrivedOnDay)) {
                    arrivedOnDay = day;
                }
            }
        }

        int finalDay = arrivedOnDay;
        assertTrue(finalDay >= 0, () ->
                "no story reached the neighbouring village at all in " + outcome.plan().days()
                        + " in-game days, so there is nothing for a stranger to be told and the "
                        + "exit criterion has no route. " + outcome.spread().size() + " stories ran.");
        assertTrue(finalDay < SITTING_DAYS, () ->
                "the first story reached the next village on in-game day " + finalDay
                        + ", and a 45-minute sitting is " + SITTING_DAYS + " days. The criterion "
                        + "says a stranger can describe something a villager remembered about them; "
                        + "if the news outruns the sitting there is nothing available to describe.");
    }

    /**
     * And the board says it with no threshold at all, which is the other half of why the criterion
     * had to be re-routed onto the board.
     *
     * <p>A stranger's bond with everybody is zero. {@code Standing.of} puts them in
     * {@code NEUTRAL}, and {@code Board.of} still returns the hearsay rows — because a memory is a
     * memory whatever the bond attached to it says. That is the property the criterion rests on and
     * it is worth pinning, because a future session adding a "do not show the board to strangers"
     * gate would break the exit criterion of this one without failing anything else.
     */
    @Test
    @DisplayName("a bond of zero is still a bond a Notice Board will draw")
    void theBoardNeedsNoThreshold() {
        Bond nothing = Bond.fresh(0);
        assertTrue(nothing.isNothing(), "a stranger's bond is nothing at all");
        assertTrue(Standing.of(nothing) == Standing.NEUTRAL,
                "and nothing at all is the neutral band, at price 1.00 — DESIGN.md §10 step 1");
        assertTrue(Standing.NEUTRAL.priceMultiplier() == 1.00F,
                "a stranger pays the standing price, which is what makes the board the only surface "
                        + "in this mod that tells a new player anything");
    }
}
