package net.namesake.social;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.namesake.culture.Culture;
import net.namesake.npc.Persona;
import net.namesake.npc.TraitRoll;
import net.namesake.settlement.Settlement;
import net.namesake.settlement.Settlements;
import net.namesake.settlement.Specialty;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>What the weight table actually does to a population, rather than to two villagers somebody
 * chose.</b>
 *
 * <p>This exists because a playtest at the close of session 05 found the honest limit of
 * {@code DeedsTest.theSameGiftLandsDifferently}: it asserts that a smith at −40 warmth and an
 * innkeeper at +60 get different numbers, and both of those villagers are fixtures <i>I wrote</i>.
 * Ten villagers out of a real generated world spanned ×0.94 to ×1.29 — a third of the range the
 * fixtures spanned. A test that only ever asks about its own extremes cannot notice that.
 *
 * <p>So this rolls a population through the same three-layer roll the game uses and reports the
 * distribution. It is the instrument the magnitudes are tuned against at session 07, and the guard
 * that fails if they are ever tuned into flatness.
 *
 * <h2>The decomposition that matters</h2>
 *
 * <p>Two numbers, and confusing them is how this gets tuned wrong. <b>Population spread</b> is how
 * different two villagers anywhere in the world are. <b>Within-settlement spread</b> is how
 * different two villagers <i>standing in the same square</i> are — and that is the one the exit
 * criterion is actually about, because nobody compares a gift given here against one given three
 * thousand blocks away. The three-layer roll deliberately clusters a settlement around its own mean,
 * so the second number is always the smaller one, and it is the one that decides whether the
 * mechanic is visible.
 *
 * <h2>What this population is, and is not</h2>
 *
 * <p>Every culture crossed with every specialty, three defensibilities and a spread of needs —
 * the generator's whole space, sampled uniformly. A real world does not cross them uniformly:
 * {@code Cultures.at} ties a culture to a climate, so a Karsk masonry town and an Ashani one are not
 * equally likely. This is therefore a <i>wider</i> population than any one save, which is the right
 * bias for a guard (it will not miss a flatness that only shows in one corner) and the wrong bias
 * for a headline (it overstates what one player meets). Session 07's harness measures the narrower,
 * truer thing.
 */
class PersonalityDistributionTest {

    private static final ResourceLocation OVERWORLD = ResourceLocation.withDefaultNamespace("overworld");

    /** Households per settlement, and members per household. Enough to sample both jitter layers. */
    private static final int HOUSEHOLDS = 6;
    private static final int PER_HOUSEHOLD = 6;

    /**
     * How flat is too flat.
     *
     * <p>Not a tuning target — a floor. If the population's p5-to-p95 ever falls below this, the
     * weight table has stopped distinguishing anybody and the eight persisted trait axes are back
     * to being decoration, which is the failure {@code DESIGN.md} §1 is about. The target the owner
     * ruled at the close of session 05 is far above it and belongs to session 07: two villagers a
     * week apart on identical treatment should end up <b>a full standing band apart</b>.
     */
    private static final float MINIMUM_POPULATION_SPREAD = 0.10F;

    private record Sample(byte culture, int settlement, byte[] traits, float[] scaleByType,
                          int allowance) {

        float gift() {
            return scaleByType[DeedType.GIFT_WANTED.id()];
        }
    }

    /**
     * The population's mean trait vector, which is what {@code Personality.TYPICAL} has to equal.
     *
     * <p>Printed so the constant can be read off a measurement rather than guessed, and asserted
     * below so it fails if the generator ever drifts away from it — a culture's baseline changing,
     * a specialty bias being retuned, a new culture arriving. Centring is only meaningful while
     * "typical" is still typical.
     */
    private static byte[] meanTraits(List<Sample> population) {
        long[] totals = new long[Persona.TRAIT_COUNT];
        for (Sample sample : population) {
            for (int axis = 0; axis < Persona.TRAIT_COUNT; axis++) {
                totals[axis] += sample.traits()[axis];
            }
        }
        byte[] mean = new byte[Persona.TRAIT_COUNT];
        for (int axis = 0; axis < Persona.TRAIT_COUNT; axis++) {
            mean[axis] = (byte) Math.round(totals[axis] / (double) population.size());
        }
        return mean;
    }

    // --- the report ---------------------------------------------------------------------------

    @Test
    @DisplayName("the weight table's realised distribution, reported and held above a floor")
    void theRealisedDistribution() {
        List<Sample> population = population(20260814L);
        StringBuilder report = new StringBuilder(
                "\n=== personality weight table: realised distribution ===\n")
                .append(String.format(Locale.ROOT, "  %d personas: %d cultures x %d settlements "
                                + "x %d households x %d members%n",
                        population.size(), Culture.COUNT,
                        population.size() / (Culture.COUNT * HOUSEHOLDS * PER_HOUSEHOLD),
                        HOUSEHOLDS, PER_HOUSEHOLD))
                .append(String.format(Locale.ROOT, "  %-16s %7s %7s %7s %7s %7s   %8s %8s%n",
                        "deed type", "p5", "p25", "p50", "p75", "p95", "spread", "in-town"));

        for (DeedType type : DeedType.values()) {
            float[] all = scalesFor(population, type);
            float populationSpread = percentile(all, 95) - percentile(all, 5);
            float withinTown = medianWithinSettlementSpread(population, type);

            report.append(String.format(Locale.ROOT,
                    "  %-16s %7.3f %7.3f %7.3f %7.3f %7.3f   %8.3f %8.3f%n",
                    type.name().toLowerCase(Locale.ROOT),
                    percentile(all, 5), percentile(all, 25), percentile(all, 50),
                    percentile(all, 75), percentile(all, 95),
                    populationSpread, withinTown));

            assertTrue(populationSpread >= MINIMUM_POPULATION_SPREAD, () -> type
                    + " spans only " + populationSpread + " from p5 to p95. The weight table has "
                    + "stopped telling anybody apart, and eight persisted trait axes are decoration "
                    + "again. DESIGN.md §1.");
        }

        // What the owner's ruling is measured against, so session 07 has the number rather than
        // the adjective. Warmth only: it is the axis a gift moves most and the one that decays.
        report.append(theWeekApart(population));
        report.append(String.format(Locale.ROOT,
                "  the clamp [%.2f, %.2f] engages on %.2f%% of (persona x deed type) pairs%n",
                Personality.MIN, Personality.MAX, clampRate(population) * 100.0));
        report.append(perCulture(population));
        report.append("  population mean traits (Personality.TYPICAL must equal this):\n    ");
        byte[] mean = meanTraits(population);
        for (int axis = 0; axis < Persona.TRAIT_COUNT; axis++) {
            report.append(String.format(Locale.ROOT, "%s=%d ", Persona.TRAIT_NAMES[axis], mean[axis]));
        }
        report.append('\n');
        System.out.println(report);
    }

    /**
     * The number the session 05 ruling is aimed at: after a week of identical treatment, how far
     * apart are the most and least receptive villager <i>of one settlement</i>?
     *
     * <p><b>Two players, because they were not the same answer.</b> The playtest that produced the
     * ruling found that scaling only the per-deed step is visible to somebody who gives one gift a
     * day and completely erased for somebody who gives enough to fill the cap — everybody converged
     * on the same eight. So both are simulated: a casual player at one gift a day, and a saturating
     * one who keeps going until the day's allowance is spent. The second column is the one the
     * ceiling change exists for, and it read exactly zero before it.
     *
     * <p><b>Printed here and asserted in {@link #theWeekApartMeetsTheRuling()}.</b> It was printed
     * and not asserted at the close of session 05, because it was then a tuning target nobody had
     * hit; session 07 hit it, and a target that has been met and is not held is a target that will
     * be flattened by the next person who retunes the table for some other reason.
     */
    private static String theWeekApart(List<Sample> population) {
        List<Float> casual = new ArrayList<>();
        List<Float> saturating = new ArrayList<>();

        for (int settlement : settlements(population)) {
            for (byte culture = 0; culture < Culture.COUNT; culture++) {
                List<Sample> town = inTown(population, culture, settlement);
                if (town.isEmpty()) {
                    continue;
                }
                Sample low = town.get(0);
                Sample high = town.get(0);
                for (Sample resident : town) {
                    if (resident.gift() < low.gift()) {
                        low = resident;
                    }
                    if (resident.gift() > high.gift()) {
                        high = resident;
                    }
                }
                casual.add((float) (weekOfGifts(high, 1) - weekOfGifts(low, 1)));
                saturating.add((float) (weekOfGifts(high, 12) - weekOfGifts(low, 12)));
            }
        }

        float[] casualSorted = sorted(casual);
        float[] saturatingSorted = sorted(saturating);
        return String.format(Locale.ROOT,
                "  a week of identical treatment, warmth gap between a settlement's least and most%n"
                        + "  receptive resident (the close-of-05 ruling wants a full band, ~25):%n"
                        + "    one gift a day        median %.0f   widest %.0f%n"
                        + "    filling the allowance median %.0f   widest %.0f%n",
                percentile(casualSorted, 50), casualSorted[casualSorted.length - 1],
                percentile(saturatingSorted, 50), saturatingSorted[saturatingSorted.length - 1]);
    }

    /**
     * Seven days of gifts, through the real records rather than multiplied out.
     *
     * <p>The cap, the personality-scaled allowance, the rounding and the lazy decay all bite, and
     * the point of the exercise is what survives all four. Twelve gifts a day is well past any
     * allowance the clamp permits, so it stands for "kept going until it stopped working".
     */
    private static int weekOfGifts(Sample who, int giftsPerDay) {
        int warmth = Deeds.round(DeedType.GIFT_WANTED.delta(Bond.WARMTH) * who.gift());
        Bond bond = Bond.fresh(0);
        for (int day = 0; day < 7; day++) {
            for (int gift = 0; gift < giftsPerDay; gift++) {
                bond = bond.apply(new int[]{0, warmth, 0}, day, who.allowance());
            }
        }
        return bond.warmth();
    }

    private static float[] sorted(List<Float> values) {
        float[] array = new float[values.size()];
        for (int i = 0; i < array.length; i++) {
            array[i] = values.get(i);
        }
        Arrays.sort(array);
        return array;
    }

    /** Per-culture medians, so a flat table can be told from a table with six flat clusters. */
    private static String perCulture(List<Sample> population) {
        StringBuilder out = new StringBuilder("  per culture, gift_wanted median:\n");
        for (Culture culture : Culture.values()) {
            List<Sample> ofCulture = population.stream()
                    .filter(sample -> sample.culture() == culture.id())
                    .toList();
            float[] scales = scalesFor(ofCulture, DeedType.GIFT_WANTED);
            out.append(String.format(Locale.ROOT, "    %-10s %6.3f   (p5 %.3f, p95 %.3f)%n",
                    culture.displayName(), percentile(scales, 50),
                    percentile(scales, 5), percentile(scales, 95)));
        }
        return out.toString();
    }

    // --- the guards ---------------------------------------------------------------------------

    /**
     * <b>The close-of-session-05 ruling, held rather than remembered.</b>
     *
     * <p><i>Two villagers a week apart on identical treatment should end up a full standing band
     * apart</i> — about 25 points. Session 05 measured a median of 14 and handed the magnitude to
     * session 07, which closed it by widening {@link Personality#SPREAD} from 1.0 to 1.6.
     *
     * <p><b>The number is quantised to multiples of seven, and 25 is therefore not reachable.</b>
     * A daily allowance is an integer — {@code Bond.gainedToday} counts it in four bits — so seven
     * days of two villagers' allowances differ by exactly seven times an integer. The reachable
     * values either side of the ruling are 21 and 28; {@code SPREAD} 1.2 reaches 21 and 1.6 is the
     * smallest that reaches 28. Asserting ≥ 25 therefore means "28 or better", which is the honest
     * reading of the ruling rather than a number that flatters it.
     */
    @Test
    @DisplayName("a week apart still leaves a settlement's two extremes a full band apart")
    void theWeekApartMeetsTheRuling() {
        float median = saturatingWeekApart(population(20260814L), 50);

        assertTrue(median >= 25.0F, () -> "the median settlement's two extremes end a week of "
                + "identical treatment " + median + " points apart. The owner ruled at the close of "
                + "session 05 that it should be a full standing band, ~25, and session 07 set "
                + "Personality.SPREAD to 1.6 to reach 28. Widen SPREAD — never Bond.DAILY_CAP, "
                + "which is what stops a village being ground out and is the same number session "
                + "12's band thresholds are set against.");
    }

    /** The saturating column of {@link #theWeekApart}, as a number rather than as a line of a report. */
    private static float saturatingWeekApart(List<Sample> population, int percent) {
        List<Float> gaps = new ArrayList<>();
        for (int settlement : settlements(population)) {
            for (byte culture = 0; culture < Culture.COUNT; culture++) {
                List<Sample> town = inTown(population, culture, settlement);
                if (town.isEmpty()) {
                    continue;
                }
                Sample low = town.get(0);
                Sample high = town.get(0);
                for (Sample resident : town) {
                    if (resident.gift() < low.gift()) {
                        low = resident;
                    }
                    if (resident.gift() > high.gift()) {
                        high = resident;
                    }
                }
                gaps.add((float) (weekOfGifts(high, 12) - weekOfGifts(low, 12)));
            }
        }
        return percentile(sorted(gaps), percent);
    }

    /**
     * <b>How many in-game days of daily kindness each candidate trust threshold costs.</b>
     *
     * <p>Reported rather than asserted, and it exists because of a ruling. The owner ruled at the
     * close of session 13 that <b>three days to a discount is too fast and it should cost more</b>,
     * and {@code Residency.TRUST_THRESHOLD} is the number that decides it — read twice since session
     * 12, because {@code Standing.TRUSTED_TRUST} <i>is</i> that constant rather than a copy. Moving
     * it moves what every villager in the world charges <i>and</i> when a village takes you in.
     *
     * <p>So whoever moves it should pick from this table rather than from a feeling about the
     * number. Trust does not decay and a day is worth at most one personality-scaled
     * {@code Bond.DAILY_CAP}, so days-to-threshold is {@code ceil(threshold / allowance)} and the
     * spread across a real population is the spread of the allowance — measured 4 to 12, commonest
     * 8 and 9.
     *
     * <p>The row for 20 is the one to read the others against: it reproduces session 12's measured
     * <b>77% inside three days</b>, which is what says this arithmetic agrees with the instrument.
     */
    @Test
    @DisplayName("what each candidate trust threshold would cost in in-game days")
    void whatEachTrustThresholdCosts() {
        List<Sample> population = population(20260815L);
        StringBuilder report = new StringBuilder(
                "\n=== days of daily kindness to reach a trust threshold ===\n")
                .append(String.format(Locale.ROOT, "  %d personas; allowance is Personality"
                        + ".allowance, capped at Bond.DAILY_CAP%n", population.size()))
                .append("  threshold    median   p90   share reaching it inside 3 days\n");

        for (int threshold : new int[]{20, 24, 28, 32, 40, 48}) {
            List<Float> days = new ArrayList<>();
            int insideThree = 0;
            for (Sample sample : population) {
                int allowance = Math.max(1, sample.allowance());
                int taken = (threshold + allowance - 1) / allowance;
                days.add((float) taken);
                if (taken <= 3) {
                    insideThree++;
                }
            }
            float[] sorted = sorted(days);
            report.append(String.format(Locale.ROOT, "  %9d %9.0f %5.0f %28.1f%%%n",
                    threshold, percentile(sorted, 50), percentile(sorted, 90),
                    insideThree * 100.0 / population.size()));
        }
        report.append("  (Residency.TRUST_THRESHOLD is ").append(Residency.TRUST_THRESHOLD)
                .append(" — and it is also Standing.TRUSTED_TRUST, so a change here moves every "
                        + "price in the world)\n");
        System.out.println(report);
    }

    @Test
    @DisplayName("two villagers of one settlement differ, not only two villagers of one world")
    void withinOneSettlementPeopleStillDiffer() {
        // The failure this catches is subtle and would look fine in every other test here: a table
        // that separates cultures perfectly and everybody inside a village not at all. The exit
        // criterion is about two villagers you can walk between, so the within-town number is the
        // one that has to be non-zero.
        List<Sample> population = population(776644L);
        float withinTown = medianWithinSettlementSpread(population, DeedType.GIFT_WANTED);

        assertTrue(withinTown > 0.05F, () -> "the median settlement spans only " + withinTown
                + " from its least to its most receptive resident. The table may be separating "
                + "cultures while telling nobody in a village apart, which is the half of the "
                + "criterion a player can actually see.");
    }

    @Test
    @DisplayName("the six cultures do not all land on the same median")
    void culturesAreNotInterchangeable() {
        List<Sample> population = population(31415L);
        float lowest = Float.MAX_VALUE;
        float highest = -Float.MAX_VALUE;
        for (Culture culture : Culture.values()) {
            float median = percentile(scalesFor(population.stream()
                    .filter(sample -> sample.culture() == culture.id())
                    .toList(), DeedType.GIFT_WANTED), 50);
            lowest = Math.min(lowest, median);
            highest = Math.max(highest, median);
        }
        float spread = highest - lowest;
        assertTrue(spread > 0.05F, () -> "every culture's median is within " + spread
                + " of every other's. Standing risk 3 is that settlement two feels like settlement "
                + "one, and a weight table that reads six cultures identically is one more way for "
                + "that to be true.");
    }

    /**
     * The other half of centring, and the half that can rot.
     *
     * <p>{@code Personality.TYPICAL} is a measurement of this population, and the per-column offsets
     * are computed from it — so "a typical villager scores exactly one" holds by construction and
     * proves nothing about whether the vector is still typical. Retune a specialty bias, change a
     * culture's baseline, add a seventh culture, and the constant silently becomes a description of
     * a population that no longer exists. This is what notices.
     *
     * <p>Deliberately a different seed from the run the constant was read off, so it is measuring
     * the generator rather than replaying one sample of it.
     */
    @Test
    @DisplayName("the typical villager the table is centred on is still the typical villager")
    void typicalIsStillTypical() {
        byte[] measured = meanTraits(population(58008L));
        byte[] declared = Personality.typical();

        for (int axis = 0; axis < Persona.TRAIT_COUNT; axis++) {
            int drift = Math.abs(measured[axis] - declared[axis]);
            int finalAxis = axis;
            assertTrue(drift <= 3, () -> "Personality.TYPICAL says " + Persona.TRAIT_NAMES[finalAxis]
                    + "=" + declared[finalAxis] + ", but the generator now averages "
                    + measured[finalAxis] + ". The weight table is centred on a population that no "
                    + "longer exists, so nominal has stopped meaning typical. Re-read the vector "
                    + "off this test's report and update the constant.");
        }
    }

    @Test
    @DisplayName("no personality the clamp allows can overflow the four-bit allowance counters")
    void everyRealisedAllowanceFitsItsNibble() {
        for (Sample sample : population(4242L)) {
            assertTrue(sample.allowance() >= 1 && sample.allowance() <= 15,
                    () -> "a rolled persona's daily allowance came out at " + sample.allowance()
                            + ", which does not fit the four bits gainedToday stores it against");
        }
    }

    /**
     * <b>What makes {@code Personality.MIN} and {@code MAX} measured numbers rather than chosen
     * ones, and the guard that has to move whenever {@code SPREAD} does.</b>
     *
     * <p>The clamp's stated job is to stop one lucky roll producing a villager for whom a loaf of
     * bread is worth a rescue — a guard against <i>absurd</i> variation. A clamp that engages in
     * ordinary play is doing something else entirely: it is compressing the distribution, and the
     * villagers it compresses are exactly the interesting ones at either end of a village. That
     * failure is silent, because every other test here reads the clamped value and finds it in
     * range by construction.
     *
     * <p>So the property is stated as a rate. Session 07 widened {@code SPREAD} from 1.0 to 1.6 and
     * widened the clamp from [0.4, 1.6] to [0.3, 1.8] in the same breath, precisely so this number
     * did not move; it reads about 0.7%, against a ceiling of 2%. Raise {@code SPREAD} again without
     * widening the clamp and this is what says so.
     */
    @Test
    @DisplayName("the clamp binds only at the corners, and that is a measured rate")
    void theClampBindsOnlyAtTheCorners() {
        double rate = clampRate(population(20260814L));
        assertTrue(rate < 0.02, () -> "the clamp engages on " + String.format(Locale.ROOT, "%.2f%%",
                rate * 100.0) + " of (persona x deed type) pairs. It exists to stop absurd "
                + "variation at the corners of the trait space, not to flatten the real variation "
                + "the generator produces — and the villagers it is flattening are the ones at "
                + "either end of a village, which is where the whole mechanic is visible. Widen "
                + "Personality.MIN and MAX to match Personality.SPREAD.");
    }

    private static double clampRate(List<Sample> population) {
        int clamped = 0;
        int total = 0;
        for (Sample sample : population) {
            for (float scale : sample.scaleByType()) {
                total++;
                if (scale <= Personality.MIN || scale >= Personality.MAX) {
                    clamped++;
                }
            }
        }
        return (double) clamped / total;
    }

    @Test
    @DisplayName("the roll never produces a multiplier outside the clamp")
    void nothingEscapesTheClamp() {
        for (Sample sample : population(999L)) {
            for (float scale : sample.scaleByType()) {
                assertTrue(scale >= Personality.MIN && scale <= Personality.MAX,
                        () -> "a rolled persona scored " + scale + ", outside ["
                                + Personality.MIN + ", " + Personality.MAX + "]");
            }
        }
    }

    // --- the population -------------------------------------------------------------------------

    private static List<Sample> population(long seed) {
        Settlements table = new Settlements();
        List<Integer> settlementIds = new ArrayList<>();
        int id = 0;
        for (Specialty trade : Specialty.values()) {
            for (int defensibility : new int[]{25, 55, 85}) {
                // Needs are derived from the id so the fixture is deterministic and still varied —
                // a settlement short of food is a different place from one short of tools, and
                // TraitRoll reads all four.
                byte[] needs = {
                        (byte) (id * 7 % 61), (byte) (id * 13 % 61),
                        (byte) (id * 17 % 61), (byte) (id * 23 % 61)};
                table.put(new Settlement(id, OVERWORLD,
                        new BlockPos((int) (seed % 4096) + id * 512, 64, id * 331),
                        trade.id(), (byte) defensibility, needs));
                settlementIds.add(id);
                id++;
            }
        }

        List<Sample> population = new ArrayList<>();
        long counter = 0;
        for (Culture culture : Culture.values()) {
            for (int settlementId : settlementIds) {
                for (int household = 0; household < HOUSEHOLDS; household++) {
                    for (int member = 0; member < PER_HOUSEHOLD; member++) {
                        Persona placed = Persona.create(new UUID(seed, counter++), 0L)
                                .placed(settlementId, 4096 + household * 31 + settlementId * 7,
                                        culture.id());
                        Persona rolled = placed.withTraits(TraitRoll.roll(placed, table));
                        float[] scales = new float[DeedType.values().length];
                        for (DeedType type : DeedType.values()) {
                            scales[type.id()] = Personality.scale(rolled, type);
                        }
                        population.add(new Sample(culture.id(), settlementId,
                                rolled.traits(), scales, Personality.allowance(rolled)));
                    }
                }
            }
        }
        return population;
    }

    // --- arithmetic ------------------------------------------------------------------------------

    /** Sorted, so {@link #percentile} and the first/last reads below are both valid. */
    private static float[] scalesFor(List<Sample> samples, DeedType type) {
        float[] scales = new float[samples.size()];
        for (int i = 0; i < samples.size(); i++) {
            scales[i] = samples.get(i).scaleByType()[type.id()];
        }
        Arrays.sort(scales);
        return scales;
    }

    private static float percentile(float[] sorted, int percent) {
        if (sorted.length == 0) {
            return Float.NaN;
        }
        int index = Math.min(sorted.length - 1, (int) ((percent / 100.0) * sorted.length));
        return sorted[index];
    }

    private static List<Integer> settlements(List<Sample> population) {
        return population.stream().map(Sample::settlement).distinct().sorted().toList();
    }

    private static List<Sample> inTown(List<Sample> population, byte culture, int settlement) {
        return population.stream()
                .filter(sample -> sample.culture() == culture && sample.settlement() == settlement)
                .toList();
    }

    /**
     * The median of each settlement's own p5-to-p95 spread.
     *
     * <p>Median rather than mean, because one unusually varied town would otherwise carry the
     * number and the claim being made is about a typical village.
     */
    private static float medianWithinSettlementSpread(List<Sample> population, DeedType type) {
        List<Float> spreads = new ArrayList<>();
        for (int settlement : settlements(population)) {
            for (byte culture = 0; culture < Culture.COUNT; culture++) {
                float[] scales = scalesFor(inTown(population, culture, settlement), type);
                if (scales.length > 0) {
                    spreads.add(percentile(scales, 95) - percentile(scales, 5));
                }
            }
        }
        float[] sorted = new float[spreads.size()];
        for (int i = 0; i < sorted.length; i++) {
            sorted[i] = spreads.get(i);
        }
        Arrays.sort(sorted);
        return percentile(sorted, 50);
    }
}
