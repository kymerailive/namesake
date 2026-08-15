package net.namesake.day;

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
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>What the day plan does to the population the generator actually produces, rather than to a
 * villager somebody wrote.</b>
 *
 * <p>This exists because {@code DESIGN.md} §7 shipped a threshold — <i>industry ≥ 96 works, below
 * it stands off</i> — that was never measured against a real roll, and against one it selects
 * <b>nobody</b>. That is session 12's {@code Bond.respect} exactly: a threshold at 20 against an
 * observed maximum of 4, in the session whose own instrument exists to catch it. Session 12 caught
 * it after eleven sessions of the ledger promising it would be paid; this catches it before a line
 * of the mechanic reaches a player.
 *
 * <p>Three separate things are reported and held, and they fail differently:
 *
 * <ol>
 *   <li><b>The industry distribution</b>, so {@link DayPlan#INDUSTRY_TO_WORK} is read off a table
 *       rather than chosen — and so the share it selects is a number in the ledger rather than an
 *       adjective.</li>
 *   <li><b>The realised boundary spread</b>, which is the thing {@link DayPlan#SPREAD_FLOOR} is a
 *       wall for. A floor of 64 that produces a realised spread of 3 because the arithmetic piles
 *       everybody at one end is a wall with a door in it.</li>
 *   <li><b>The standoff table</b>, held against both vanilla metrics at once.</li>
 * </ol>
 *
 * <p>The population is {@code PersonalityDistributionTest}'s, built the same way and for the same
 * reason: every culture crossed with every specialty is <i>wider</i> than any one save, which is the
 * right bias for a guard and the wrong one for a headline.
 */
class DayPlanDistributionTest {

    private static final ResourceLocation OVERWORLD = ResourceLocation.withDefaultNamespace("overworld");

    private static final int HOUSEHOLDS = 6;
    private static final int PER_HOUSEHOLD = 6;

    /**
     * The share of a real population that must still turn up for work.
     *
     * <p>§7's at-a-glance test is <i>everyone at a workstation, <b>except</b> those eight blocks
     * away doing nothing</i>, so the lazy villagers are the exception and the exception has to be a
     * minority. Two thirds is the floor rather than the target — a village of nine with three idlers
     * still reads as a working village; one with six does not read as anything.
     */
    private static final double MINIMUM_DILIGENT_SHARE = 0.66;

    /**
     * And the other side of it, which is the failure that would look like success.
     *
     * <p>A threshold nobody falls below ships a mechanic that never fires, and the whole session
     * would be green. {@code Bond.respect} passed every test in the repository for seven sessions.
     */
    private static final double MINIMUM_LAZY_SHARE = 0.08;

    @Test
    @DisplayName("the industry distribution, and the share each candidate threshold would select")
    void theIndustryDistribution() {
        List<Persona> population = population(20260815L);
        int[] industry = population.stream()
                .mapToInt(p -> p.trait(Persona.INDUSTRY)).sorted().toArray();

        StringBuilder report = new StringBuilder(
                "\n=== day plan: industry over the real generator ===\n")
                .append(String.format(Locale.ROOT, "  %d personas, %d cultures%n",
                        population.size(), Culture.COUNT))
                .append(String.format(Locale.ROOT,
                        "  min %d  p5 %d  p25 %d  p50 %d  p75 %d  p95 %d  max %d%n",
                        industry[0], at(industry, 5), at(industry, 25), at(industry, 50),
                        at(industry, 75), at(industry, 95), industry[industry.length - 1]));

        report.append("  threshold   diligent share   lazy share\n");
        for (int threshold : new int[]{-20, -10, -4, 0, 4, 8, 12, 16, 20, 30, 96}) {
            long diligent = 0;
            for (int value : industry) {
                if (value >= threshold) {
                    diligent++;
                }
            }
            double share = diligent / (double) industry.length;
            report.append(String.format(Locale.ROOT, "  %9d   %13.1f%%   %8.1f%%%s%n",
                    threshold, share * 100.0, (1 - share) * 100.0,
                    threshold == DayPlan.INDUSTRY_TO_WORK ? "   <-- INDUSTRY_TO_WORK" : ""));
        }
        System.out.println(report);

        long diligent = population.stream().filter(DayPlan::isDiligent).count();
        double share = diligent / (double) population.size();

        assertTrue(share >= MINIMUM_DILIGENT_SHARE, () -> String.format(Locale.ROOT,
                "DayPlan.INDUSTRY_TO_WORK = %d leaves only %.1f%% of a real population turning up "
                        + "for work. DESIGN.md §7's at-a-glance test is 'everyone at a workstation, "
                        + "except those eight blocks away' — the idlers are the exception. Lower the "
                        + "threshold.", DayPlan.INDUSTRY_TO_WORK, share * 100.0));

        assertTrue(1 - share >= MINIMUM_LAZY_SHARE, () -> String.format(Locale.ROOT,
                "DayPlan.INDUSTRY_TO_WORK = %d makes %.1f%% of a real population lazy, which is "
                        + "close enough to nobody that the standoff would never be seen. This is "
                        + "Bond.respect's failure — a threshold at 20 against an observed maximum "
                        + "of 4 — and DESIGN.md §7's own '>= 96' is exactly it: against this "
                        + "population 96 selects %.2f%%.",
                DayPlan.INDUSTRY_TO_WORK, (1 - share) * 100.0,
                population.stream().filter(p -> p.trait(Persona.INDUSTRY) < 96).count()
                        * 100.0 / population.size()));
    }

    /**
     * The share of a real population that may be out after dark.
     *
     * <p>§7's at-a-glance line is <i>streets empty but for two torches on the perimeter</i>. Two of
     * nine is 22%, and a third of a village standing about at midnight is not a watch — it is a
     * village that never went to bed.
     */
    private static final double MAXIMUM_WATCH_SHARE = 0.33;

    /** And the other end, which is {@link #MINIMUM_LAZY_SHARE}'s argument at a second threshold. */
    private static final double MINIMUM_WATCH_SHARE = 0.10;

    /**
     * <b>The boldness distribution, and the share each candidate threshold would put on watch.</b>
     *
     * <p>The third time this project has pointed the instrument at a threshold before building on
     * it, and it exists because of the two times it was not: {@code Bond.respect} shipped a ladder
     * whose lowest mark was five times the observed maximum and survived seven sessions, and §7's
     * own {@code industry ≥ 96} selected 0.0% of a real population. A threshold nobody crosses and a
     * threshold everybody crosses are both green in every other test in the repository.
     *
     * <p>{@link DayPlan#BOLDNESS_TO_WATCH} is read off the table this prints, at the percentile that
     * leaves a village of nine with two people out — so the number is a property of the population
     * the generator makes rather than one somebody chose to hit a target.
     */
    @Test
    @DisplayName("the boldness distribution, and the share each candidate threshold sends on watch")
    void theBoldnessDistribution() {
        List<Persona> population = population(20260815L);
        int[] boldness = population.stream()
                .mapToInt(p -> p.trait(Persona.BOLDNESS)).sorted().toArray();

        StringBuilder report = new StringBuilder(
                "\n=== day plan: boldness over the real generator ===\n")
                .append(String.format(Locale.ROOT, "  %d personas, %d cultures%n",
                        population.size(), Culture.COUNT))
                .append(String.format(Locale.ROOT,
                        "  min %d  p5 %d  p25 %d  p50 %d  p75 %d  p80 %d  p90 %d  p95 %d  max %d%n",
                        boldness[0], at(boldness, 5), at(boldness, 25), at(boldness, 50),
                        at(boldness, 75), at(boldness, 80), at(boldness, 90), at(boldness, 95),
                        boldness[boldness.length - 1]));

        report.append("  threshold   on watch   in a village of nine\n");
        for (int threshold : new int[]{0, 10, 20, 25, 30, 35, 40, 45, 50, 60, 80}) {
            long watch = 0;
            for (int value : boldness) {
                if (value >= threshold) {
                    watch++;
                }
            }
            double share = watch / (double) boldness.length;
            report.append(String.format(Locale.ROOT, "  %9d   %7.1f%%   %18.1f%s%n",
                    threshold, share * 100.0, share * 9,
                    threshold == DayPlan.BOLDNESS_TO_WATCH ? "   <-- BOLDNESS_TO_WATCH" : ""));
        }
        System.out.println(report);

        long watch = population.stream().filter(DayPlan::standsWatch).count();
        double share = watch / (double) population.size();

        assertTrue(share >= MINIMUM_WATCH_SHARE, () -> String.format(Locale.ROOT,
                "DayPlan.BOLDNESS_TO_WATCH = %d puts %.1f%% of a real population on watch, which is "
                        + "close enough to nobody that the village would simply go quiet at eight "
                        + "and DESIGN.md §7's twenty-o'clock line would be unbuildable rather than "
                        + "unbuilt. A mechanic that never fires passes every other test here.",
                DayPlan.BOLDNESS_TO_WATCH, share * 100.0));

        assertTrue(share <= MAXIMUM_WATCH_SHARE, () -> String.format(Locale.ROOT,
                "DayPlan.BOLDNESS_TO_WATCH = %d keeps %.1f%% of a real population out after dark — "
                        + "%.1f of a village of nine. The line is 'streets empty but for two', and "
                        + "an exception has to be an exception.",
                DayPlan.BOLDNESS_TO_WATCH, share * 100.0, share * 9));
    }

    /**
     * <b>The wall, measured through both of its halves rather than declared.</b>
     *
     * <p>§7 promises <i>worst case ~6/tick</i> against 400 villagers, and this is the test that
     * holds it. The number that matters is not how many villagers share a boundary offset — it is
     * <b>how many reach the transition governor in one tick</b>, and two things stand between those:
     *
     * <ol>
     *   <li>{@link DayPlan#offsetOf} spreads the crossings over {@link DayPlan#SPREAD} ticks;</li>
     *   <li>{@link DayPlan#pathGateOpen} then delays each villager to the next tick their own id
     *       opens, which scatters everyone sharing an offset across seven more.</li>
     * </ol>
     *
     * <p><b>Two assertions this session wrote before this one were both wrong, and the way they were
     * wrong is worth keeping.</b> The first asserted that no offset holds more than twelve of four
     * hundred; the second, that no more than eight reach the governor in one tick. Measured, the
     * answer to both is about <b>17.5</b> — the traits are clustered, so any monotone map of one is
     * clustered too, and a uniform delay of up to seven ticks is a seven-tick moving average that
     * does not flatten a hump that wide.
     *
     * <p>The second assertion is wrong in kind rather than in value, and that is the useful part:
     * <b>a governor that is never exceeded is a governor that does nothing.</b> Its whole job is to
     * absorb a burst into a queue. So the two numbers that actually decide whether the wall holds
     * are the ones below — the <i>mean</i> arrival rate, which decides whether the queue is stable
     * at all, and the <i>worst delay</i> any villager suffers, which is the only part a player could
     * ever see.
     */
    @Test
    @DisplayName("the governor's queue is stable, and the latest villager crosses within half a spread")
    void theBoundarySpreadIsReal() {
        List<Persona> population = population(20260815L);

        for (DaySlot slot : new DaySlot[]{DaySlot.LABOUR_I, DaySlot.LABOUR_II, DaySlot.COMMONS}) {
            int[] histogram = new int[DayPlan.SPREAD];
            for (Persona persona : population) {
                int offset = DayPlan.offsetOf(persona, slot);
                assertTrue(offset >= 0 && offset < DayPlan.SPREAD,
                        () -> "offset " + offset + " is outside [0, " + DayPlan.SPREAD + ")");
                histogram[offset]++;
            }

            int used = 0;
            int busiestCount = 0;
            for (int count : histogram) {
                if (count > 0) {
                    used++;
                }
                busiestCount = Math.max(busiestCount, count);
            }
            final int occupied = used;
            final double rawPeak = busiestCount * 400.0 / population.size();

            // Both walls and the queue, simulated at four hundred villagers — the number DESIGN.md
            // §7 quotes. Every phase of the path gate against the boundary, worst taken: the gate's
            // effect depends on which absolute tick the boundary lands on, and a test that picked
            // one phase would be reporting an accident.
            int worstDelay = 0;
            for (int phase = 0; phase < DayPlan.PATH_GATE_PERIOD; phase++) {
                int[] arrivals = new int[DayPlan.SPREAD + DayPlan.PATH_GATE_PERIOD];
                for (Persona persona : population) {
                    int crossesAt = DayPlan.offsetOf(persona, slot);
                    // The first tick at or after the crossing on which this villager's own gate
                    // opens. Exactly what Steering.tickLevel does, arithmetic and all.
                    int gate = Math.floorMod(persona.id().hashCode(), DayPlan.PATH_GATE_PERIOD);
                    int wait = Math.floorMod(gate - (phase + crossesAt), DayPlan.PATH_GATE_PERIOD);
                    arrivals[crossesAt + wait]++;
                }
                worstDelay = Math.max(worstDelay, drain(arrivals, population.size()));
            }
            final int latest = worstDelay;

            System.out.printf(Locale.ROOT,
                    "  %-10s %2d/%d offsets used; peak %.1f per 400 at one offset; mean %.2f/tick "
                            + "against a governor serving %d; last villager crosses %d tick(s) "
                            + "after the boundary%n",
                    slot, occupied, DayPlan.SPREAD, rawPeak, 400.0 / DayPlan.SPREAD,
                    Steering.TRANSITIONS_PER_TICK, latest);

            // The structural claim, and the one that decides whether the queue is stable at all.
            // DESIGN.md §7's "400 / 64 ≈ 6 a tick" is exactly this number, and it has to stay under
            // the governor's rate or the backlog grows without bound and never drains.
            assertTrue(400.0 / DayPlan.SPREAD <= Steering.TRANSITIONS_PER_TICK,
                    () -> "400 villagers over a spread of " + DayPlan.SPREAD + " arrive at "
                            + (400.0 / DayPlan.SPREAD) + " a tick, and the governor serves only "
                            + Steering.TRANSITIONS_PER_TICK + ". The queue would never drain. Widen "
                            + "DayPlan.SPREAD — never the governor, which is the wall.");

            // And the only part of it a player could see: how late the last one is.
            int allowed = DayPlan.SPREAD + DayPlan.SPREAD / 2;
            assertTrue(latest <= allowed, () -> slot + "'s last villager crosses " + latest
                    + " ticks after the boundary, against a spread of " + DayPlan.SPREAD
                    + ". The transition wave has stopped being a wave and become a queue.");

            // And a coverage floor, which is a proxy rather than the wall and is asserted loosely
            // for that reason. It exists to catch a collapse — an offsetOf that returned one value
            // would pass the ceiling above at a population of one and fail here at any population.
            assertTrue(occupied >= DayPlan.SPREAD / 2, () -> slot + " uses only " + occupied
                    + " of " + DayPlan.SPREAD + " offsets, so more than half the spread the floor "
                    + "guarantees is empty and the wave is narrower than it claims.");
        }
    }

    /**
     * Every entry of the standoff table clears both vanilla metrics — on flat ground, which is the
     * only claim a table can make. {@code Steering} re-checks the resolved position because a hill
     * changes the Manhattan answer and not the table.
     */
    @Test
    @DisplayName("every standoff offset is silent to both vanilla behaviours at once")
    void theStandoffTableClearsBothMetrics() {
        BlockPos job = new BlockPos(100, 64, 100);
        for (int[] entry : DayPlan.STANDOFF_OFFSETS) {
            final String name = entry[0] + "," + entry[1];
            BlockPos where = job.offset(entry[0], 0, entry[1]);
            assertTrue(DayPlan.isAStandoff(job, where),
                    () -> "standoff offset " + name + " fails its own test");
            assertTrue(job.distManhattan(where) <= DayPlan.VANILLA_JOB_SITE_TOLERANCE - 1,
                    () -> "standoff offset " + name + " is Manhattan " + job.distManhattan(where)
                            + " on flat ground, leaving no headroom for a block of height. "
                            + "SetWalkTargetFromBlockMemory measures Manhattan and the ground is "
                            + "not flat.");
            assertTrue(!DayPlan.isWithinArmsReach(job, where),
                    () -> "standoff offset " + name
                            + " is inside WorkAtPoi's 1.73, so the villager would work");
        }
    }

    /**
     * Runs {@code arrivals} through the transition governor and returns the tick the last villager
     * is finally served on, scaled to a population of four hundred.
     *
     * <p>The scaling is why this takes the population size: the test rolls 4,536 personas because a
     * wide population is the right bias for a guard, and {@code DESIGN.md} §7's claim is about four
     * hundred. Scaling the arrival counts rather than the answer keeps the queue arithmetic honest —
     * a governor serving eight a tick against 4,536 arrivals would report a delay eleven times too
     * long and nothing would be wrong with it.
     */
    private static int drain(int[] arrivals, int populationSize) {
        int queue = 0;
        int served = 0;
        int lastTick = 0;
        int total = 0;
        for (int count : arrivals) {
            total += Math.round(count * 400.0F / populationSize);
        }
        for (int tick = 0; served < total; tick++) {
            if (tick < arrivals.length) {
                queue += Math.round(arrivals[tick] * 400.0F / populationSize);
            }
            int now = Math.min(queue, Steering.TRANSITIONS_PER_TICK);
            queue -= now;
            served += now;
            if (now > 0) {
                lastTick = tick;
            }
            if (tick > 100_000) {
                throw new IllegalStateException("the governor's queue never drained");
            }
        }
        return lastTick;
    }

    private static int at(int[] sorted, int percent) {
        return sorted[Math.min(sorted.length - 1, percent * sorted.length / 100)];
    }

    private static List<Persona> population(long seed) {
        Settlements table = new Settlements();
        List<Integer> settlementIds = new ArrayList<>();
        int id = 0;
        for (Specialty trade : Specialty.values()) {
            for (int defensibility : new int[]{25, 55, 85}) {
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

        List<Persona> population = new ArrayList<>();
        long counter = 0;
        for (Culture culture : Culture.values()) {
            for (int settlementId : settlementIds) {
                for (int household = 0; household < HOUSEHOLDS; household++) {
                    for (int member = 0; member < PER_HOUSEHOLD; member++) {
                        Persona placed = Persona.create(new UUID(seed, counter++), 0L)
                                .placed(settlementId, 4096 + household * 31 + settlementId * 7,
                                        culture.id());
                        population.add(placed.withTraits(TraitRoll.roll(placed, table)));
                    }
                }
            }
        }
        return population;
    }
}
