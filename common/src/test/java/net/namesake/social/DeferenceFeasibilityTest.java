package net.namesake.social;

import net.namesake.culture.Culture;
import net.namesake.npc.Persona;
import net.namesake.settlement.Specialty;
import net.namesake.sim.PlayerModel;
import net.namesake.sim.Reports;
import net.namesake.sim.Simulation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>Can a {@code deference} axis be earned?</b> Session 16's verify-first step, built before the
 * schema bump rather than after it.
 *
 * <p>{@code DESIGN.md} §6's force-resolution wants <i>deference up</i>, and the field it would have
 * read — {@code Bond.respect} — was deleted at schema 8 because the instrument session 07 exists for
 * refused it: observed maximum <b>zero, zero, zero, zero and four</b> across five player models and
 * a hundred in-game days, against a lowest ladder mark of twenty. {@code DESIGN.md} and
 * {@code WORKPLAN.md} both say session 16 adds an axis back and pays hard rule 1 for it.
 *
 * <p><b>This asks the question in the order that would have saved the last one.</b> A datafixer is
 * cheap to write and impossible to unwrite: once schema 9 ships with a {@code deference} column,
 * every save in the world has the field, and deleting it at session 20 costs schema 10 and a second
 * migration. So the arithmetic comes first.
 *
 * <h2>What this measures, and the one thing it has to invent</h2>
 *
 * <p>Two legs. The first runs {@link Simulation} — session 07's instrument, unmodified — across all
 * five {@link PlayerModel} rows and reports what {@code Bond.FEAR} actually reaches. Fear is the
 * control: it is the one axis already written only by a <i>hostile and occasional</i> act, which is
 * the shape force-resolution has, so whatever the pipeline does to fear it would do to deference.
 *
 * <p>The second leg is the arithmetic for an axis that does not exist yet, and it has to invent
 * exactly one thing, stated here rather than smuggled in: <b>how many times a player forces a
 * resolution in a hundred in-game days.</b> None of the five player models does it even once —
 * they give and they strike, because those were the only deeds when they were written. §6 caps
 * <i>active</i> dramas at {@code 2 + population/10}, which is <b>two</b> at the nine-resident
 * village every measured number in this project comes from; how many of those two resolve and
 * reopen inside a hundred days is a property of an engine nobody has built. So the table inverts
 * the question and reports the <b>number of forced resolutions required</b> at each candidate event
 * size, which is a statement the grievance engine can then be held to.
 *
 * <h2>Why the personality weight is bracketed rather than chosen</h2>
 *
 * <p>{@link Personality#scale} is indexed by <i>deed type</i> — {@code SHAPE} is
 * {@code float[8][6]}, eight trait axes by six deed columns — so a {@code FORCED_RESOLUTION} deed
 * would need <b>a seventh column: eight new weights nobody has measured.</b> That is a real cost of
 * the axis and it is not in either document. Rather than invent eight numbers and then measure
 * them, this leg brackets: it computes each persona's realised scale across all six existing
 * columns and reports the table at the <i>weakest</i> and the <i>median</i> of them. The weakest is
 * the honest one, because <b>a personality weight below neutral rounding a small delta to nothing
 * is precisely what killed respect</b> — see {@code DeedType.STRUCK_RESIDENT}'s javadoc.
 */
final class DeferenceFeasibilityTest {

    /**
     * Candidate per-event deference deltas, before any weighting.
     *
     * <p>Bracketed by the table that exists: {@code STRUCK_RESIDENT} wrote {@code +1} respect and
     * rounded to nothing, {@code DEFENDED_RAID} writes {@code +8} trust and is the largest benign
     * row in the mod, and {@code KILLED_RESIDENT}'s {@code 35} fear is the largest anything writes.
     */
    private static final int[] DELTAS = {1, 2, 4, 6, 8, 12};

    /** How many forced resolutions a hundred in-game days might contain. */
    private static final int[] RESOLUTIONS = {1, 2, 4, 8, 16, 32};

    /** The simulated span, matching every other number this project has measured. */
    private static final int DAYS = 100;

    @Test
    @DisplayName("session 07's instrument on fear — the axis a rare hostile act already writes")
    void theControlAxisAsTheInstrumentSeesIt() {
        StringBuilder report = new StringBuilder(
                "\n=== the control: what Bond.FEAR reaches in " + DAYS + " in-game days ===\n")
                .append("  fear is written by STRUCK_RESIDENT (+6, half witness share) and\n")
                .append("  KILLED_RESIDENT (+35, full share) and by nothing else, so it is the\n")
                .append("  closest shipped analogue to an axis written by force-resolution.\n")
                .append(String.format(Locale.ROOT, "  %-16s %8s %8s %8s %8s%n",
                        "model", "max", "p50", "p90", "deeds"));

        int highest = 0;
        for (PlayerModel model : PlayerModel.values()) {
            Simulation.Outcome outcome =
                    Simulation.run(Simulation.Plan.standard(20261101L, DAYS, model));
            DialogueStats stats = Reports.statsOf(outcome);
            int max = stats.observedMaximum(Bond.FEAR);
            highest = Math.max(highest, max);
            report.append(String.format(Locale.ROOT, "  %-16s %8d %8d %8d %8d%n",
                    model, max, stats.percentile(Bond.FEAR, 50),
                    stats.percentile(Bond.FEAR, 90), outcome.chronicle().size()));
        }

        report.append(String.format(Locale.ROOT,
                "  the lowest mark anything in this mod reads is %d (DialogueStats.LADDER[0]);%n"
                        + "  Standing.WARM_WARMTH is %d and Residency.TRUST_THRESHOLD is %d.%n",
                DialogueStats.LADDER[0], Standing.WARM_WARMTH, Residency.TRUST_THRESHOLD));
        report.append(String.format(Locale.ROOT,
                "  VERDICT: the highest fear any model reached is %d, which is %s the lowest mark.%n",
                highest, highest >= DialogueStats.LADDER[0] ? "AT OR ABOVE" : "BELOW"));
        System.out.println(report);

        // Not an assertion about the number — an assertion that the run produced data at all. A
        // table of zeroes because the instrument did nothing is the most confident wrong number
        // available, and PLAYBOOK.md says so about profiler baselines in the same words.
        assertTrue(highest > 0,
                "every player model reached zero fear, so this leg measured nothing. CARELESS "
                        + "strikes every eighth visit by construction, so a zero here is a broken "
                        + "fixture rather than a finding.");
    }

    @Test
    @DisplayName("what a deference axis would reach, by event size and number of forced resolutions")
    void whatDeferenceWouldReach() {
        List<Persona> population = instrumentPopulation();
        assertTrue(population.size() > 500,
                "only " + population.size() + " personas were generated, so the percentiles below "
                        + "are not a distribution and this test proves nothing");

        int mark = DialogueStats.LADDER[0];
        StringBuilder report = new StringBuilder(
                "\n=== what a deference axis would reach in " + DAYS + " in-game days ===\n")
                .append(String.format(Locale.ROOT,
                        "  %d personas across %d cultures and %d specialties, real traits through%n"
                                + "  TraitRoll, real allowances through Personality.allowance.%n",
                        population.size(), Culture.COUNT, Specialty.values().length))
                .append("  one forced resolution per day at most; the daily cap is per axis per day,\n")
                .append("  so an event worth more than a persona's whole allowance is truncated.\n")
                .append("  cells are the MEDIAN persona's deference after N forced resolutions.\n")
                .append("  the mark to clear is ").append(mark).append(".\n\n");

        for (Weighting weighting : Weighting.values()) {
            int[] perEventAtDelta = new int[DELTAS.length];
            report.append("  personality weight: ").append(weighting.label()).append('\n');
            report.append(String.format(Locale.ROOT, "  %7s", "delta"));
            for (int resolutions : RESOLUTIONS) {
                report.append(String.format(Locale.ROOT, " %6s", "N=" + resolutions));
            }
            report.append(String.format(Locale.ROOT, " %10s%n", "N to clear"));

            for (int d = 0; d < DELTAS.length; d++) {
                int delta = DELTAS[d];
                report.append(String.format(Locale.ROOT, "  %7d", delta));
                for (int resolutions : RESOLUTIONS) {
                    report.append(String.format(Locale.ROOT, " %6d",
                            medianReached(population, weighting, delta, resolutions)));
                }
                perEventAtDelta[d] = medianPerEvent(population, weighting, delta);
                int needed = perEventAtDelta[d] <= 0
                        ? -1
                        : (mark + perEventAtDelta[d] - 1) / perEventAtDelta[d];
                report.append(String.format(Locale.ROOT, " %10s%n",
                        needed < 0 ? "never" : String.valueOf(needed)));
            }

            report.append(String.format(Locale.ROOT,
                    "  per-event value for the median persona, by delta: %s%n%n",
                    Arrays.toString(perEventAtDelta)));
        }

        report.append("  N is the invented number: no PlayerModel forces a resolution, and §6 caps\n")
                .append("  ACTIVE dramas at 2 + population/10 = 2 at nine residents. The rightmost\n")
                .append("  column is therefore what the grievance engine must deliver per hundred\n")
                .append("  days for the axis to be earnable at that event size.\n");
        System.out.println(report);
    }

    @Test
    @DisplayName("the marks this table is read against come off the constants, not out of this file")
    void theMarksAreReadRatherThanRestated() {
        // The property being held is that nothing above restates a threshold. If DialogueStats.LADDER
        // or either band constant moves, the table moves with it and this test is what says the
        // three are still the three — respect was judged against the ladder's lowest mark, and a
        // successor axis judged against a stale copy of it would be judged against nothing.
        assertEquals(20, DialogueStats.LADDER[0],
                "the ladder's lowest mark moved, so every verdict printed above was read against "
                        + "a different number than the one respect was refused by");
        assertEquals(20, Standing.WARM_WARMTH, "Standing.WARM_WARMTH moved");
        assertEquals(28, Residency.TRUST_THRESHOLD, "Residency.TRUST_THRESHOLD moved");
        assertEquals(-20, Standing.RESENTED_TRUST, "Standing.RESENTED_TRUST moved");
        assertEquals(3, Bond.AXIS_COUNT,
                "Bond is no longer three axes. If deference has landed, this whole file is a "
                        + "record of why rather than a question, and the verdict belongs in "
                        + "WORKPLAN.md's session 16 log instead.");
    }

    /** Which of a persona's six realised weights to price the hypothetical deed at. */
    private enum Weighting {
        WEAKEST("the weakest of the six existing columns (what killed respect)"),
        MEDIAN("the median of the six existing columns");

        private final String label;

        Weighting(String label) {
            this.label = label;
        }

        String label() {
            return label;
        }
    }

    /**
     * The personality multiplier a {@code FORCED_RESOLUTION} column would plausibly produce for one
     * persona, bracketed across the six columns that exist.
     *
     * <p>{@link Personality#scale} is a clamped weighted sum over all eight trait axes, so a seventh
     * column's realised value for a given persona lands inside the same {@code [MIN, MAX]} envelope
     * as the six. Taking the weakest is the pessimistic read and the one the respect deletion
     * argues for.
     */
    private static float weightFor(Persona persona, Weighting weighting) {
        float[] scales = new float[DeedType.values().length];
        for (DeedType type : DeedType.values()) {
            scales[type.id()] = Personality.scale(persona, type);
        }
        Arrays.sort(scales);
        return weighting == Weighting.WEAKEST ? scales[0] : scales[scales.length / 2];
    }

    /** What one forced resolution is worth to the median persona, after weight and the daily cap. */
    private static int medianPerEvent(List<Persona> population, Weighting weighting, int delta) {
        List<Integer> values = new ArrayList<>(population.size());
        for (Persona persona : population) {
            values.add(perEvent(persona, weighting, delta));
        }
        return median(values);
    }

    private static int medianReached(List<Persona> population, Weighting weighting,
                                     int delta, int resolutions) {
        List<Integer> values = new ArrayList<>(population.size());
        for (Persona persona : population) {
            values.add(Math.min(Bond.ceilingOf(), perEvent(persona, weighting, delta) * resolutions));
        }
        return median(values);
    }

    /**
     * One event's contribution, through the same two gates a real deed passes.
     *
     * <p>The personality weight, then {@code Bond.apply}'s per-axis daily allowance — which is where
     * a large delta stops being large, because the cap is the single most load-bearing number in the
     * social system and it does not care what the deed was.
     */
    private static int perEvent(Persona persona, Weighting weighting, int delta) {
        int weighted = Math.round(delta * weightFor(persona, weighting));
        return Math.max(0, Math.min(weighted, Personality.allowance(persona)));
    }

    private static int median(List<Integer> values) {
        int[] sorted = values.stream().mapToInt(Integer::intValue).sorted().toArray();
        return sorted[sorted.length / 2];
    }

    /**
     * Real personas, generated by the shipped instrument rather than by a fixture of this file's own.
     *
     * <p>Two days rather than a hundred, because this leg reads traits and allowances — which are
     * rolled at generation — and not bonds. Culture and specialty are both varied, because
     * {@code TraitRoll} reads the settlement as well as the culture and a population drawn from one
     * village would understate the spread the table is about.
     */
    private static List<Persona> instrumentPopulation() {
        List<Persona> people = new ArrayList<>();
        long seed = 20261101L;
        for (Culture culture : Culture.values()) {
            for (Specialty trade : Specialty.values()) {
                Simulation.Plan plan = new Simulation.Plan(
                        seed++, 2, 30, 6, culture.id(), trade.id(), (byte) 70,
                        PlayerModel.ATTENTIVE, Simulation.TYPICAL_FOCUS,
                        Simulation.TYPICAL_WITNESS_FRACTION, true, 1);
                for (Persona persona : Simulation.run(plan).residents()) {
                    if (persona.isGenerated()) {
                        people.add(persona);
                    }
                }
            }
        }
        return people;
    }
}
