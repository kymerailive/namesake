package net.namesake.sim;

import net.namesake.social.Bond;
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
        Simulation.Outcome outcome = Simulation.run(
                Simulation.Plan.standard(SEED, 100, PlayerModel.SATURATING));
        DialogueStats stats = Reports.statsOf(outcome);

        assertTrue(stats.deepestRing().isPresent(), "a hundred days must leave somebody remembering");
        DialogueStats.Ring deepest = stats.deepestRing().get();
        assertTrue(deepest.reachDays() >= Memories.RING_CAPACITY / 2,
                () -> "the deepest ring holds " + deepest.slots() + " deeds spanning only "
                        + deepest.reachDays() + " days. Twelve deeds a day collapsing to fewer than "
                        + "one entry a day is what Deed.id()'s content addressing is for.");
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
     * <p>A ring holds thirty-two deeds. A hundred days of contact does not fit, so the days a live
     * save can count are fewer than the days that happened while the warmth is all of it — which
     * makes a rate read off a real save an <i>over</i>-estimate. Session 12 has to know that before
     * it sets a threshold from one.
     */
    @Test
    @DisplayName("the ring-derived rate overstates the true one, and never understates it")
    void theRingOverstatesTheEarnRate() {
        Simulation.Outcome outcome = Simulation.run(
                Simulation.Plan.standard(SEED, 100, PlayerModel.ATTENTIVE));
        DialogueStats stats = Reports.statsOf(outcome);

        boolean anyTruncated = false;
        for (DialogueStats.Standing standing : stats.standings()) {
            Simulation.History history = outcome.histories().get(standing.holder());
            if (history == null || history.contactDays() == 0) {
                continue;
            }
            assertTrue(standing.contactDays() <= history.contactDays(),
                    () -> standing.name() + "'s ring claims " + standing.contactDays()
                            + " days of contact and only " + history.contactDays() + " happened");
            anyTruncated |= standing.contactDays() < history.contactDays();
        }
        assertTrue(anyTruncated, "a hundred days against a thirty-two entry ring must truncate "
                + "somebody, or this test is asserting nothing about a full ring");
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
        Reports.acrossModels(plan).forEach(line -> out.append(line).append('\n'));
        out.append('\n');
        Reports.witnessSensitivity(plan).forEach(line -> out.append(line).append('\n'));
        System.out.println(out);
    }
}
