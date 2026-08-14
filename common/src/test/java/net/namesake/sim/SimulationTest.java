package net.namesake.sim;

import net.namesake.social.Bond;
import net.namesake.social.Deed;
import net.namesake.social.DialogueStats;
import net.namesake.social.Memories;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The hundred-day run, and the properties that make its output evidence rather than a number.
 *
 * <p>{@code WORKPLAN.md} draws the line: anything a unit test can prove belongs in a unit test, and
 * a report's layout, percentiles, bucketing and arithmetic are all pure. So the numbers live here
 * and the harness earns no new leg for them. What only a running game can show is that a hundred
 * days completes without wedging a server, which is a claim about the runner rather than about the
 * numbers, and {@code /namesake debug simulate} is what makes it.
 */
class SimulationTest {

    private static final long SEED = 20260814L;

    @Test
    @DisplayName("a hundred in-game days run, and run well under the minute the ledger asks for")
    void aHundredDaysRuns() {
        Simulation.Outcome outcome = Simulation.run(
                Simulation.Plan.standard(SEED, 100, PlayerModel.ATTENTIVE));

        assertEquals(100, outcome.plan().days());
        assertEquals(100, outcome.chronicle().size(),
                "one deed a day for a hundred days is a hundred deeds");
        assertTrue(outcome.elapsedMillis() < 60_000L,
                () -> "the ledger's exit criterion is a hundred days in under a minute; this took "
                        + outcome.elapsedMillis() + " ms");
    }

    /**
     * <b>The property that makes a report evidence.</b> A number nobody else can reproduce is not a
     * measurement, and every band threshold session 12 sets comes out of one of these.
     */
    @Test
    @DisplayName("the same plan produces exactly the same run, twice")
    void aRunIsDeterministic() {
        Simulation.Plan plan = Simulation.Plan.standard(SEED, 40, PlayerModel.INTERMITTENT);
        List<String> first = Reports.full(Simulation.run(plan));
        List<String> second = Reports.full(Simulation.run(plan));

        // The wall-clock line is the one thing that legitimately differs between two runs.
        assertEquals(withoutTiming(first), withoutTiming(second),
                "two runs of one plan must produce the same report");
    }

    private static List<String> withoutTiming(List<String> lines) {
        return lines.stream().filter(line -> !line.startsWith("  ran ")).toList();
    }

    @Test
    @DisplayName("a different seed is a different village, not the same one renamed")
    void seedsProduceDifferentVillages() {
        Simulation.Outcome one = Simulation.run(Simulation.Plan.standard(1L, 30, PlayerModel.ATTENTIVE));
        Simulation.Outcome two = Simulation.run(Simulation.Plan.standard(2L, 30, PlayerModel.ATTENTIVE));

        assertTrue(one.residents().stream().map(p -> p.traits()[0]).toList()
                        .equals(two.residents().stream().map(p -> p.traits()[0]).toList()) == false,
                "two seeds produced identical people, so the settlement is not seeded at all");
    }

    // --- the claims the report makes --------------------------------------------------------------

    @Test
    @DisplayName("the daily cap holds across a hundred days, not only across one")
    void theDailyCapHoldsOverTime() {
        // The claim BondTest makes about one day, made about a hundred. A saturating player emits
        // twelve deeds a day for a hundred days; if the cap leaked even a point a day the warmth
        // would be pinned at the ceiling by day ten and nothing here would look wrong.
        Simulation.Outcome outcome = Simulation.run(
                Simulation.Plan.standard(SEED, 100, PlayerModel.SATURATING));
        DialogueStats stats = Reports.statsOf(outcome);

        assertTrue(stats.observedMaximum(Bond.WARMTH) <= 100,
                "warmth cannot exceed its ceiling however long anybody grinds");
        for (DialogueStats.Standing standing : stats.standings()) {
            // Against the true contact days rather than the ring's. The ring holds thirty-two and a
            // hundred days does not fit in thirty-two, so the ring-derived rate legitimately reads
            // higher than any allowance — which is the finding Reports.ringTruncation exists to
            // report, and asserting on it here would be asserting the truncation is a cap breach.
            Simulation.History history = outcome.histories().get(standing.holder());
            if (history == null || history.contactDays() == 0) {
                continue;
            }
            float trueRate = (float) standing.bond().warmth() / history.contactDays();
            assertTrue(trueRate <= standing.allowance() + 0.01F,
                    () -> standing.name() + " earned " + trueRate + " warmth per day of contact "
                            + "against an allowance of " + standing.allowance()
                            + ". The daily cap is what stops a village being ground out.");
        }
    }

    /**
     * <b>The property {@code Deed.id()} exists to have, over a hundred days rather than over nine
     * feedings.</b>
     *
     * <p>Session 06's harness proved that nine identical feedings on one day are one memory. This is
     * the same claim at the scale the ring actually has to survive: a saturating player emits twelve
     * hundred deeds and a ring holds thirty-two, so if repetition were not collapsed the rings would
     * be thirty-two copies of one afternoon.
     */
    @Test
    @DisplayName("a hundred days of a grinder does not fill a ring with one afternoon")
    void theRingIsNotGroundOutOverAHundredDays() {
        // Gossip off, deliberately: the claim here is about Deed.id()'s content addressing and
        // nothing else, and propagation is a second variable that legitimately moves the number.
        // What it costs is measured separately, in gossipCostsMemoryDepth.
        Simulation.Outcome outcome = Simulation.run(
                Simulation.Plan.standard(SEED, 100, PlayerModel.SATURATING).withGossip(false));
        DialogueStats stats = Reports.statsOf(outcome);

        assertTrue(stats.deepestRing().isPresent(), "a hundred days must leave somebody remembering");
        DialogueStats.Ring deepest = stats.deepestRing().get();
        assertTrue(deepest.reachDays() >= Memories.RING_CAPACITY / 2,
                () -> "the deepest ring holds " + deepest.slots() + " deeds spanning only "
                        + deepest.reachDays() + " days. Twelve deeds a day collapsing to fewer than "
                        + "one entry a day is what Deed.id()'s content addressing is for.");
    }

    /**
     * <b>What gossip costs a villager's memory, measured rather than discovered later.</b>
     *
     * <p>It fell out of the breakage above: with step 7 on, a villager's ring fills with things that
     * happened to their neighbours as well as things that happened in front of them, so it reaches
     * <i>less far back</i> in the same number of days. That is a real consequence of this session
     * rather than a defect — the ring is thirty-two slots and gossip is competition for them — and it
     * is the owner's to rule against, which is why it is a printed number rather than a threshold.
     */
    @Test
    @DisplayName("gossip fills a ring faster, so it reaches less far back — measured, not assumed")
    void gossipCostsMemoryDepth() {
        Simulation.Plan plan = Simulation.Plan.standard(SEED, 100, PlayerModel.SATURATING);
        DialogueStats.Ring silent = Reports.statsOf(Simulation.run(plan.withGossip(false)))
                .deepestRing().orElseThrow();
        DialogueStats.Ring spreading = Reports.statsOf(Simulation.run(plan.withGossip(true)))
                .deepestRing().orElseThrow();

        System.out.printf("[gossip] the deepest ring reaches back %d days without propagation "
                        + "and %d days with it%n", silent.reachDays(), spreading.reachDays());

        assertTrue(spreading.reachDays() <= silent.reachDays(),
                "propagation can only ever add entries to a ring, so it cannot make one reach "
                        + "further back than it did without");
    }

    @Test
    @DisplayName("a player who never comes back does not keep the warmth they earned")
    void absenceCools() {
        // The lazy decay, over a hundred days rather than over a fixture. A weekly visitor's warmth
        // is fought back down between visits; a daily one's is not.
        DialogueStats daily = Reports.statsOf(Simulation.run(
                Simulation.Plan.standard(SEED, 100, PlayerModel.ATTENTIVE)));
        DialogueStats weekly = Reports.statsOf(Simulation.run(
                Simulation.Plan.standard(SEED, 100, PlayerModel.PASSING_THROUGH)));

        assertTrue(weekly.observedMaximum(Bond.WARMTH) < daily.observedMaximum(Bond.WARMTH),
                "somebody who visits weekly must not end where somebody who visits daily does");
    }

    /**
     * <b>What a hundred days of casual violence actually costs, which is less than it sounds.</b>
     *
     * <p>The first version of this test asserted that {@code CARELESS} leaves somebody actively
     * distrustful, and it failed — which is the instrument working. Two gifts a day outrun a blow
     * every eighth visit by a wide margin, because a blow is one deed and the goodwill is two a day,
     * so the axis never goes negative at all. That is a finding about the design rather than a bug,
     * and the honest assertions are the two things that <i>are</i> true and load-bearing.
     */
    @Test
    @DisplayName("force is recorded as having worked, and is remembered as itself")
    void violenceIsRecorded() {
        Simulation.Outcome outcome = Simulation.run(
                Simulation.Plan.standard(SEED, 100, PlayerModel.CARELESS));
        DialogueStats careless = Reports.statsOf(outcome);

        assertTrue(careless.observedMaximum(Bond.FEAR) > 0,
                "force must be recorded as having worked, or DESIGN.md §6's fear axis does nothing");
        assertTrue(careless.deedMix().stream()
                        .anyMatch(entry -> entry.getKey() == net.namesake.social.DeedType.STRUCK_RESIDENT),
                "a blow must survive in somebody's ring rather than only in the arithmetic");

        // And the finding: goodwill given twice a day drowns a blow given every eighth visit.
        DialogueStats attentive = Reports.statsOf(Simulation.run(
                Simulation.Plan.standard(SEED, 100, PlayerModel.ATTENTIVE)));
        assertTrue(careless.observedMaximum(Bond.WARMTH) > attentive.observedMaximum(Bond.WARMTH),
                "twice the gifts and an occasional blow still ends warmer than half the gifts and "
                        + "none, which is the shape session 12's hostile band has to be set against");
    }

    // --- the instrument reads itself back ----------------------------------------------------------

    /**
     * <b>{@code /namesake debug earnrate} on a live save cannot see past the ring, and this is by
     * how much.</b>
     *
     * <p>A ring holds {@link Memories#RING_CAPACITY} deeds. A hundred days of contact does not fit,
     * so the days a live save can count are fewer than the days that happened while the warmth is all
     * of it — which makes a rate read off a real save an <i>over</i>-estimate. Session 12 has to know
     * that before it sets a threshold from one.
     *
     * <h2>Session 09 found a second reason, in the other direction, and it is worth its place</h2>
     *
     * <p>Raising the ring to a hundred and twenty-eight slots turned this test red, and the cause was
     * not the capacity: <b>a villager's ring counts days of contact the player never gave them.</b> A
     * story drained into somebody's ring keeps the day it <i>happened</i> on, not the day they heard
     * it, so a hearer's ring reports contact on a day the player was nowhere near them. At thirty-two
     * slots eviction hid it; at a hundred and twenty-eight the ring reaches far enough back to show
     * it.
     *
     * <p>So the claim is measured in two halves rather than weakened. The first-hand count — the days
     * the player actually stood there — is what the ring truncates, and it can only fall short. The
     * whole count is what {@code /namesake debug earnrate} reads, and gossip inflates it. Both push a
     * live save's rate away from the truth, and this is the one place the two can be told apart.
     */
    @Test
    @DisplayName("a hundred days now fits the ring, and a saturating player still does not")
    void theRingOverstatesTheEarnRate() {
        // The capacity paying off. At thirty-two slots session 07 measured the error reaching +109%
        // at a hundred days; at a hundred and twenty-eight this player loses nothing at all.
        Truncation attentive = truncationUnder(PlayerModel.ATTENTIVE);
        assertEquals(0, attentive.truncated(),
                "a hundred days of one deed a day fits " + Memories.RING_CAPACITY + " slots, so a "
                        + "live save's earn rate is no longer reading a window");

        // And it still bites where the pressure actually is. Twelve deeds a day for a hundred days
        // is more distinct events than any ring this side of consolidation can hold.
        Truncation saturating = truncationUnder(PlayerModel.SATURATING);
        assertTrue(saturating.truncated() > 0,
                "a saturating player must still overflow a ring, or the eviction policy is "
                        + "untested by this instrument");

        assertTrue(attentive.inflated() > 0 || saturating.inflated() > 0,
                "and gossip must give somebody a contact day the player never gave them, or the "
                        + "second half of this test is asserting nothing");
    }

    /** How many rings lost a day they watched, and how many gained one they did not. */
    private record Truncation(int truncated, int inflated) {
    }

    private Truncation truncationUnder(PlayerModel model) {
        Simulation.Outcome outcome = Simulation.run(Simulation.Plan.standard(SEED, 100, model));
        DialogueStats stats = Reports.statsOf(outcome);

        int truncated = 0;
        int inflated = 0;
        for (DialogueStats.Standing standing : stats.standings()) {
            Simulation.History history = outcome.histories().get(standing.holder());
            if (history == null || history.contactDays() == 0) {
                continue;
            }
            // Only the deeds they watched, which is exactly what the chronicle counts as reach.
            long firstHand = outcome.registry().memories().of(standing.holder()).stream()
                    .filter(deed -> deed.actor().equals(outcome.player()))
                    .filter(deed -> deed.confidence() == Deed.FIRST_HAND)
                    .map(Deed::gameDay)
                    .distinct()
                    .count();
            assertTrue(firstHand <= history.contactDays(),
                    () -> standing.name() + "'s ring claims " + firstHand
                            + " days they watched and only " + history.contactDays() + " happened");
            if (firstHand < history.contactDays()) {
                truncated++;
            }
            if (standing.contactDays() > history.contactDays()) {
                inflated++;
            }
        }
        return new Truncation(truncated, inflated);
    }

    @Test
    @DisplayName("the simulation never reaches for a registry the world could save")
    void theSimulationBuildsItsOwnRegistry() {
        // Structural rather than behavioural, and that is the strongest form available: a registry
        // built with `new` and never handed to a DimensionDataStorage has no path to disk at all.
        // Session 04 needed a reserved id range because its fixtures *were* offered to the world's
        // registry; this needs none, and the guard is that nothing in the package takes a server.
        Simulation.Outcome outcome = Simulation.run(
                Simulation.Plan.standard(SEED, 5, PlayerModel.ATTENTIVE));
        assertEquals(5, outcome.registry().settlements().size() + outcome.residents().size() - 5,
                "sanity: the run built its own settlement and residents");
        assertTrue(outcome.registry().isDirty(),
                "the run wrote to its own registry, which is the thing that proves it is not "
                        + "writing to anybody else's");
    }

    // --- the report itself --------------------------------------------------------------------------

    @Test
    @DisplayName("print the hundred-day report")
    void printTheReport() {
        Simulation.Plan plan = Simulation.Plan.standard(SEED, 100, PlayerModel.ATTENTIVE);
        Simulation.Outcome outcome = Simulation.run(plan);

        StringBuilder out = new StringBuilder("\n");
        Reports.full(outcome).forEach(line -> out.append(line).append('\n'));
        out.append('\n');
        Reports.propagation(plan).forEach(line -> out.append(line).append('\n'));
        out.append('\n');
        Reports.residencyWithAndWithoutGossip(plan).forEach(line -> out.append(line).append('\n'));
        out.append('\n');
        Reports.acrossModels(plan).forEach(line -> out.append(line).append('\n'));
        out.append('\n');
        Reports.witnessSensitivity(plan).forEach(line -> out.append(line).append('\n'));
        System.out.println(out);
    }

    // --- session 08: the exit criterion, as a claim about time and a population ---------------------

    /**
     * <b>{@code WORKPLAN.md}'s exit criterion for session 08.</b>
     *
     * <p><i>A deed emitted in a settlement is held by 60%+ of its residents within two in-game days
     * at descending confidence, with at least one holder unable to name the actor.</i>
     *
     * <p>It lives here rather than in the attach-bet harness because it is a claim about time, which
     * is the instrument session 07 built. {@code PASSING_THROUGH} over three days emits exactly one
     * deed, on day zero, and then leaves the village alone with it — so the numbers are about one
     * story rather than about a hundred overlapping ones.
     */
    @Test
    @DisplayName("a deed reaches 60% of a village within two in-game days, at descending confidence")
    void aDeedTravels() {
        Simulation.Plan plan = Simulation.Plan.standard(SEED, 3, PlayerModel.PASSING_THROUGH);
        Simulation.Outcome outcome = Simulation.run(plan);

        assertEquals(1, outcome.chronicle().size(), "one deed, so the numbers are about one story");
        assertEquals(1, outcome.spread().size());
        Simulation.Spread story = outcome.spread().get(0);

        float coverage = story.coverage(2, plan.residents());
        assertTrue(coverage >= 0.60F,
                () -> "one deed reached " + story.holders()[2] + " of " + plan.residents()
                        + " residents by day 2, which is " + Math.round(coverage * 100)
                        + "% against the 60% the ledger asks for");

        assertTrue(story.unattributed()[2] >= 1,
                () -> "nobody lost the actor's name. WORKPLAN.md asks for at least one holder unable "
                        + "to name them, and session 08 lowered Deed.RETOLD to 0.70 precisely so "
                        + "that two hops lands at 49 rather than at 72.");

        // Descending, and every step of the ladder actually occupied.
        java.util.Set<Integer> confidences = new java.util.TreeSet<>();
        for (var resident : outcome.residents()) {
            outcome.registry().memories().of(resident.id())
                    .forEach(deed -> confidences.add((int) deed.confidence()));
        }
        assertEquals(java.util.Set.of(100, 70, 49), confidences,
                "a village holds one story at three confidences and no others: watched it, was "
                        + "told, and heard a rumour");
    }

    /**
     * <b>The same criterion across twelve villages rather than the one it was written against.</b>
     *
     * <p>This project's signature defect is a claim measured against a fixture rather than against a
     * village — five instances so far, three of them in the last two sessions. A nine-resident
     * settlement and a deterministic transfer coin is a small enough sample that one seed clearing
     * 60% proves very little, and the seed changes both the personalities and the coin.
     *
     * <p><b>And the row that matters is the zero-witness one.</b> The exit criterion's 60% is partly
     * paid by the people who were standing there, and session 07 called the witness fraction the
     * least grounded input in the whole instrument. A criterion that only clears because of a guess
     * is a criterion that has to wait for session 15's playtest; this one clears without it.
     */
    @Test
    @DisplayName("60% is not seed luck, and it does not depend on the witness fraction")
    void theCriterionHoldsAcrossVillages() {
        int cleared = 0;
        int blurred = 0;
        StringBuilder seen = new StringBuilder();
        for (long seed = 1; seed <= 12; seed++) {
            Simulation.Plan plan = Simulation.Plan.standard(seed, 3, PlayerModel.PASSING_THROUGH)
                    .withWitnessFraction(0F);
            Simulation.Spread story = Simulation.run(plan).spread().get(0);
            float coverage = story.coverage(2, plan.residents());
            seen.append(' ').append(Math.round(coverage * 100)).append('%');
            if (coverage >= 0.60F) {
                cleared++;
            }
            if (story.unattributed()[2] >= 1) {
                blurred++;
            }
        }
        System.out.println("[gossip] coverage by day 2 with nobody watching:" + seen);

        final int clearedSeeds = cleared;
        assertTrue(cleared >= 10, () -> "only " + clearedSeeds + " of twelve villages reached 60% of "
                + "residents from gossip alone, with nobody having witnessed the deed:" + seen);
        final int blurredSeeds = blurred;
        assertTrue(blurred >= 10, () -> "only " + blurredSeeds + " of twelve villages produced a "
                + "holder who could not name the actor");
    }

    /**
     * The control, and it is the sentence this whole session exists to make false.
     *
     * <p>With step 7 switched off a deed reaches the people who were standing there and stops. If
     * this ever stops failing to spread, the switch has come unwired and every number in the
     * comparison above is measuring one thing twice.
     */
    @Test
    @DisplayName("with gossip off, a deed reaches the witnesses and nobody else")
    void withoutGossipADeedGoesNowhere() {
        Simulation.Plan plan = Simulation.Plan.standard(SEED, 3, PlayerModel.PASSING_THROUGH)
                .withGossip(false);
        Simulation.Outcome outcome = Simulation.run(plan);
        Simulation.Spread story = outcome.spread().get(0);

        assertEquals(story.holders()[0], story.holders()[2],
                "nothing may spread after the tick the deed was emitted on");
        assertEquals(0, story.unattributed()[2], "and nobody hears it at second hand at all");
        assertTrue(story.coverage(2, plan.residents()) < 0.60F,
                () -> "the control reached " + Math.round(story.coverage(2, plan.residents()) * 100)
                        + "% on its own, so the criterion above is not measuring gossip");
    }

    /**
     * <b>The answer session 09 is handed instead of the question.</b>
     *
     * <p>Session 07 found that no three residents reach 20 warmth in a hundred in-game days, because
     * a witness's share of a gift is one point and warmth decays one a day. Gossip was the plausible
     * fix — more holders is more contact days, and contact days are what stop the decay. This
     * measures whether it is, rather than assuming either way, and the ledger carries the reading.
     */
    @Test
    @DisplayName("gossip is measured against session 07's warmth-decay finding, not assumed")
    void gossipIsMeasuredAgainstTheResidencyThreshold() {
        Simulation.Plan plan = Simulation.Plan.standard(SEED, 100, PlayerModel.ATTENTIVE);
        DialogueStats silent = Reports.statsOf(Simulation.run(plan.withGossip(false)));
        DialogueStats spreading = Reports.statsOf(Simulation.run(plan.withGossip(true)));

        System.out.printf("[gossip] warmth p50 %d -> %d, max %d -> %d; met the player %d -> %d%n",
                silent.percentile(Bond.WARMTH, 50), spreading.percentile(Bond.WARMTH, 50),
                silent.observedMaximum(Bond.WARMTH), spreading.observedMaximum(Bond.WARMTH),
                silent.metTheViewer(), spreading.metTheViewer());

        // The one direction that must hold whatever the warmth does: gossip cannot make a village
        // know the player less well than it did without it.
        assertTrue(spreading.metTheViewer() >= silent.metTheViewer(),
                "propagation must not shrink the number of people who have met you");

        // And the switch has to do something. Measured over ten days rather than a hundred, because
        // at a hundred every ring in the village is full either way and the totals are pinned to
        // residents x RING_CAPACITY — which is itself session 07's finding, not a regression.
        Simulation.Plan brief = plan.withDays(10);
        assertTrue(Reports.statsOf(Simulation.run(brief.withGossip(true))).deedsHeld()
                        > Reports.statsOf(Simulation.run(brief.withGossip(false))).deedsHeld(),
                "gossip must put more deeds into more rings, or the switch does nothing");
    }
}
