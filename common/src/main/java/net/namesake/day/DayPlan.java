package net.namesake.day;

import net.minecraft.core.BlockPos;
import net.namesake.npc.Persona;

/**
 * <b>What one villager does with the global day, and where a lazy one stands.</b> The per-villager
 * half of {@code DESIGN.md} §7; {@link DaySlot} is the global half.
 *
 * <p>Every method here is a pure function of a persona and a number. <b>Nothing in this file is
 * persisted and nothing needs to be</b>, which is §7's own rule — <i>a plan stores WHAT, never
 * WHEN</i> — read one turn further than it was written: if the plan is derived from a persona, a
 * settlement and a day, there is no plan to store at all, and rule 5 has nothing to classify.
 * Sessions 03, 09, 10 and 11 each reached this answer about a different field. This is the fifth.
 *
 * <h2>The two vanilla numbers this file is built out of, both read off the engine</h2>
 *
 * <p>Session 13 verified both in the 1.21.1 NeoForm decompile rather than inferring them, and the
 * second one moved a ruled decision:
 *
 * <ul>
 *   <li>{@code WorkAtPoi.DISTANCE = 1.73}, used by <b>both</b> {@code checkExtraStartConditions}
 *       and {@code canStillUse}, through {@code BlockPos#closerToCenterThan} — so it is measured
 *       from the workstation block's <b>centre</b> to the villager's position, not corner to feet.
 *       {@code WorkAtPoi.start} is what plays the work sound, stamps {@code LAST_WORKED_AT_POI} and
 *       calls {@code restock()}, so all three observables are one gate.</li>
 *   <li>{@code SetWalkTargetFromBlockMemory(JOB_SITE, speed, closeEnough = 9, …)} measures
 *       <b>{@code distManhattan}</b>, not Euclidean — {@code VillagerGoalPackages.java:78} and
 *       {@code SetWalkTargetFromBlockMemory.java:48}. §7's "inside vanilla's 9 m tolerance" is
 *       therefore a Manhattan nine, and its "3–8 m" was a Euclidean band. The two do not compose:
 *       a villager standing six east and six north is Euclidean 8.49 and <b>Manhattan 12</b>, and
 *       vanilla hauls them back.</li>
 * </ul>
 *
 * <h2>The standoff annulus, and why it is two metrics rather than one band</h2>
 *
 * <p>A lazy villager has to stand somewhere three vanilla behaviours all leave alone. Each measures
 * differently, so the place is an intersection rather than a range:
 *
 * <table border="1">
 *   <caption>what has to be true of a standoff point, and which behaviour each clause silences</caption>
 *   <tr><th>clause</th><th>metric</th><th>silences</th></tr>
 *   <tr><td>{@code distManhattan ≤ 9}</td><td>Manhattan, including height</td>
 *       <td>{@code SetWalkTargetFromBlockMemory(JOB_SITE, …, 9, 100, 1200)}, priority 2</td></tr>
 *   <tr><td>{@code dx² + dz² ≥ 25}</td><td>Euclidean, horizontal</td>
 *       <td>{@code StrollAroundPoi(JOB_SITE, 0.4F, 4)}, inside the WORK gate</td></tr>
 *   <tr><td>…which also puts it past 1.73</td><td>Euclidean, centre to feet</td>
 *       <td>{@code WorkAtPoi} — <b>the whole observable</b></td></tr>
 * </table>
 *
 * <p><b>One vanilla behaviour is left, and it cannot be silenced by standing anywhere.</b>
 * {@code StrollToPoi.create(JOB_SITE, 0.4F, closeEnoughDist = 1, maxDistFromPoi = 10)} sits in the
 * WORK package's {@code RunOne} at weight 5 of 31, and for any villager within <b>ten metres</b> of
 * its job site it writes {@code WALK_TARGET = the job site, close enough 1} — at most once per 80
 * ticks, which is four seconds. There is no gap to stand in: {@code Manhattan ≤ 9} implies Euclidean
 * ≤ 9.87, which is inside ten, and any point outside ten is outside the Manhattan tolerance and gets
 * hauled back by the other behaviour. <b>Every position vanilla will leave you at is a position
 * {@code StrollToPoi} will pull you off.</b>
 *
 * <p>So the standoff is <b>not</b> un-contested, which is what §7 assumed, and it is not held by a
 * counter-walk-target either, which would be the tug-of-war §7 rules out. It is held by a
 * <b>veto</b> — see {@link Steering#declineTheJobSite}. That is why this class supplies geometry and
 * predicates and never a walk target of its own.
 */
public final class DayPlan {

    // --- vanilla's numbers, read off the engine and named where they are used --------------------

    /**
     * {@code SetWalkTargetFromBlockMemory(JOB_SITE, …, closeEnough = 9, …)}, and it is
     * <b>{@code distManhattan}</b>.
     *
     * <p>Beyond this the villager is walked back to its workstation whenever {@code WALK_TARGET} is
     * absent. Inside it, that behaviour does nothing at all — which is the one thing §7 got right
     * about the band, once the metric is the one vanilla actually uses.
     */
    public static final int VANILLA_JOB_SITE_TOLERANCE = 9;

    /**
     * {@code StrollAroundPoi(JOB_SITE, 0.4F, maxDistFromPoi = 4)}'s reach, squared, in the
     * horizontal plane.
     *
     * <p>Twenty-five rather than sixteen deliberately. The behaviour tests
     * {@code closerToCenterThan(position, 4)}, which is centre-to-feet and would be satisfied at
     * exactly four; a villager's position drifts by a fraction of a block as it settles, and a
     * standoff that is silent only while nobody breathes on it is not silent. Five blocks out is the
     * nearest whole number with room, and it is what {@link #STANDOFF_OFFSETS} is built from.
     */
    public static final int STANDOFF_MIN_DIST_SQR = 25;

    /**
     * {@code StrollToPoi(JOB_SITE, 0.4F, 1, maxDistFromPoi = 10)}'s reach.
     *
     * <p>Not a constraint on where a villager stands, because <b>no reachable point is outside it</b>
     * — see the class note. It is here because {@code Steering} needs to say, in a log line and in a
     * test, that this is the behaviour the veto declines and how often it fires.
     */
    public static final int STROLL_TO_POI_REACH = 10;

    /** {@code StrollToPoi}'s own cooldown, in ticks: how often the veto has work to do. */
    public static final int STROLL_TO_POI_COOLDOWN = 80;

    // --- ours ------------------------------------------------------------------------------------

    /**
     * <b>The hard floor on the boundary spread, in ticks, in code and never in config.</b>
     * {@code DESIGN.md} §7, and one of {@code WORKPLAN.md}'s load-bearing walls.
     *
     * <p>Without it, four hundred villagers cross a boundary in one tick and fire four hundred path
     * requests into a single server tick. With it, the worst case is {@code 400 / 64 ≈ 6} a tick,
     * which is the number §7 quotes and where sixty-four comes from — it is not a round number, it
     * is four hundred divided by the six-per-tick the transition governor is sized for.
     *
     * <p><b>There is deliberately no way to lower it.</b> Not a system property, not a config field,
     * not a setter. <i>"Never expose 'make everyone punctual' as a config option"</i> is §7's own
     * instruction, and {@code DayPlanTest.theSpreadFloorIsNotConfigurable} reads this class's
     * bytecode to prove nothing here consults a property.
     */
    public static final int SPREAD_FLOOR = 64;

    /**
     * The spread actually used. Held to the floor by construction rather than by remembering to
     * check: it <i>is</i> the floor, and raising it is a source change with a test to run.
     */
    public static final int SPREAD = SPREAD_FLOOR;

    /**
     * <b>The industry a villager needs to actually work, and it is the first quartile of a measured
     * population rather than a number anybody chose.</b>
     *
     * <p>§7's table said {@code ≥ 96}. Rolled through the real {@code TraitRoll} across six cultures
     * and every specialty — 4,536 personas — industry runs:
     *
     * <table border="1">
     *   <caption>industry over the real generator, seed 20260815</caption>
     *   <tr><th>min</th><th>p5</th><th>p25</th><th>p50</th><th>p75</th><th>p95</th><th>max</th></tr>
     *   <tr><td>−47</td><td>−11</td><td><b>12</b></td><td>25</td><td>39</td><td>58</td><td>85</td></tr>
     * </table>
     *
     * <p><b>The observed maximum is 85, so {@code ≥ 96} selects nobody and every villager in the
     * world would stand off.</b> That is session 12's {@code Bond.respect} exactly — a threshold at
     * 20 against an observed maximum of 4 — and it is the second time this project's own instrument
     * has refused a ruled number before it reached a player rather than after.
     *
     * <p>Twelve is <b>p25</b>: the threshold is defined by the shape of the population rather than
     * picked to hit a target, which is the strongest available reading of <i>measured, not chosen</i>.
     * <b>One villager in four stands off</b> — 75.0% diligent, 25.0% lazy — so a village of nine has
     * two or three idlers in it and still reads as a working village. §7's at-a-glance test is
     * "everyone at a workstation, <i>except</i> those eight blocks away", and the exception has to be
     * an exception. {@code DayPlanDistributionTest} holds both ends: the build goes red if the
     * diligent share falls below two thirds, and red again if the lazy share falls below 8%, because
     * a mechanic that never fires passes every other test in the repository.
     */
    public static final int INDUSTRY_TO_WORK = 12;

    /**
     * <b>The boldness a villager needs to stay out after the rest have gone in, read off the same
     * measured population as {@link #INDUSTRY_TO_WORK} and for the same reason.</b>
     *
     * <p><b>Its provenance is different from {@link #INDUSTRY_TO_WORK}'s and the difference is worth
     * stating.</b> Industry's threshold is a percentile for its own sake — p25, chosen so the split
     * is a property of the population's shape. This one has a <i>ruled target</i> to hit: §7's
     * at-a-glance line for twenty o'clock is <i>streets empty but for two torches on the
     * perimeter</i>, and <b>two</b> is a number. So this is the boldness mark that leaves two people
     * out of a village of nine, read off a measured distribution rather than guessed:
     *
     * <table border="1">
     *   <caption>boldness over the real {@code TraitRoll}, 4,536 personas, six cultures</caption>
     *   <tr><th>min</th><th>p25</th><th>p50</th><th>p75</th><th>p80</th><th>p95</th><th>max</th></tr>
     *   <tr><td>−67</td><td>−16</td><td>1</td><td><b>19</b></td><td>24</td><td>44</td><td>78</td></tr>
     * </table>
     *
     * <p>Twenty puts <b>25.0%</b> of that population on watch — <b>2.2 of a village of nine</b>,
     * against §7's two — and it sits a point above p75. The neighbouring candidates are worse in
     * both directions: 25 gives 1.8 and 30 gives 1.3, which is a village that mostly has no watch at
     * all, and 10 gives 3.4, which is a third of the village standing about at midnight.
     *
     * <p>{@code DayPlanDistributionTest.theBoldnessDistribution} prints that table and holds both
     * ends: red past a third of a village, and red below a tenth — because <b>a mechanic that never
     * fires passes every other test in the repository.</b> That second clause is
     * {@code Bond.respect}'s failure and §7's own {@code industry ≥ 96}, and this is the third time
     * this project has pointed the instrument at a threshold before building on it rather than after.
     *
     * <p><b>Boldness rather than industry, and the axis is the argument:</b> the question the watch
     * asks is who is willing to be outside in the dark. It is also {@code boldness}'s first
     * non-display consumer — the axes are rolled independently, so about one villager in sixteen is
     * both an idler by day and a watchman by night, which is a person rather than a category.
     */
    public static final int BOLDNESS_TO_WATCH = 20;

    /**
     * How long a walk target that cannot be pathed has to have been failing before this villager is
     * called stuck rather than lazy, in ticks.
     *
     * <p>Vanilla's own {@code CANT_REACH_WALK_TARGET_SINCE} is the signal and this is the patience.
     * Six hundred ticks is half of {@code SetWalkTargetFromBlockMemory}'s own
     * {@code tooLongUnreachableDuration = 1200}, deliberately: vanilla's answer to a job site it
     * cannot reach for a minute is to <b>release the point of interest</b>, so a villager sealed in
     * their own house loses their profession rather than escapes. Naming the state at half that
     * means it is visible for a full minute before vanilla acts on it, which is the difference
     * between a diagnosis and an obituary.
     */
    public static final int STUCK_AFTER_TICKS = 600;

    /**
     * Where a lazy villager may stand, as offsets from the workstation block.
     *
     * <p>Sixteen, so a village of nine does not put everybody in the same spot, and every one of
     * them satisfies both metrics in the class note: {@code |dx| + |dz| ≤ 8} leaves a block of
     * Manhattan headroom for height, and {@code dx² + dz² ≥ 25} clears {@code StrollAroundPoi}.
     * Eight compass directions at five blocks and four at seven, so the choice is visible as a
     * choice — a player watching two lazy villagers should not see them standing on each other.
     *
     * <p>Ordered rather than shuffled: {@link Steering} tries a villager's own offset first and then
     * walks the table, so the fallback is deterministic and a villager stands in the same place
     * every day. §7's first legibility law is <i>one intent, one place</i>.
     */
    public static final int[][] STANDOFF_OFFSETS = {
            {5, 0}, {4, 3}, {3, 4}, {0, 5},
            {-3, 4}, {-4, 3}, {-5, 0}, {-4, -3},
            {-3, -4}, {0, -5}, {3, -4}, {4, -3},
            {7, 0}, {0, 7}, {-7, 0}, {0, -7},
    };

    /**
     * How often a villager may be handed a walk target by us: one tick in seven, chosen by identity.
     *
     * <p>{@code WORKPLAN.md}'s {@code id % 7} path gate, and it bounds a different thing from the
     * governor. The governor caps the <b>burst</b> at a boundary; this caps the <b>steady state</b>,
     * so a hundred loaded villagers can only ever produce fourteen candidates in any one tick before
     * the governor has even looked at them. Seven is coprime with neither 20 nor 24000 by accident:
     * it does not share a factor with the sweep's bucket count, so a villager cannot land in the
     * same tick as its own record visit forever.
     */
    public static final int PATH_GATE_PERIOD = 7;

    private static final long OFFSET_SALT = 0x44_41_59_50_4C_41_4E_01L;
    private static final long PLACE_SALT = 0x53_54_41_4E_44_00_00_01L;

    private DayPlan() {
    }

    /**
     * <b>Whether this villager works today, or stands off.</b>
     *
     * <p>One comparison, on one axis, and it is the axis §7 names. It is not a coin: the same
     * villager is lazy every day, which is what makes it a character trait a player can learn rather
     * than weather.
     */
    public static boolean isDiligent(Persona persona) {
        return persona.trait(Persona.INDUSTRY) >= INDUSTRY_TO_WORK;
    }

    /**
     * <b>Whether this villager is one of the two who stay out after dark.</b>
     *
     * <p>One comparison, on one axis, and not a coin — the same villagers keep watch every night,
     * which is what makes it a thing about them a player can learn rather than weather. Session 13's
     * argument for {@link #isDiligent}, at the other end of the day.
     */
    public static boolean standsWatch(Persona persona) {
        return persona.trait(Persona.BOLDNESS) >= BOLDNESS_TO_WATCH;
    }

    /**
     * <b>Which errand, if any, this villager has in this slot.</b> {@code DESIGN.md} §7's slots 2,
     * 3, 6b and 7, as one pure function.
     *
     * <p>Pure, and split out from {@link Steering} for the reason session 13 split
     * {@code Steering.declines} out: the alternative is a harness leg costing six minutes a case,
     * and a table nobody can add a case to cheaply is a table that stops being checked. Everything
     * about <i>who</i> and <i>when</i> is here; everything about <i>whether it is safe right now</i>
     * is {@link Steering#mayEnter}, which is the other half and is equally pure.
     *
     * @param pastVigil whether this villager has crossed {@link DaySlot#VIGIL_BEGINS_AT}. Only
     *                  meaningful inside {@link DaySlot#HEARTH}, and it is passed in rather than
     *                  derived because the caller has already resolved this villager's own crossing
     *                  and re-deriving it is the sixty-four-bit hash session 13 spent 2.4 µs on.
     * @return the errand, or null when the plan has nothing to say — which is the common case and
     *         is every tick of two thirds of the day.
     */
    public static Errand errandFor(Persona persona, DaySlot slot, boolean pastVigil) {
        return switch (slot) {
            case HAUL -> Errand.HAUL;
            case NOON -> Errand.NOON;
            case HEARTH -> pastVigil && standsWatch(persona) ? Errand.WATCH : null;
            case NIGHT -> standsWatch(persona) ? Errand.WATCH : null;
            default -> null;
        };
    }

    /**
     * <b>How many ticks after a global boundary this villager crosses it.</b> In {@code [0, SPREAD)}.
     *
     * <p>Two axes, doing two different jobs:
     *
     * <ul>
     *   <li><b>{@code industry} says where diligence would put them.</b> The most industrious
     *       villager in the world crosses on the boundary itself and the least industrious crosses
     *       last. That is the mechanic being legible from the outside: the people who turn up first
     *       are the people who work.</li>
     *   <li><b>{@code tradition} says how much of that survives contact with whim.</b> A fully
     *       traditional villager lands exactly on their diligence mark. A fully untraditional one is
     *       placed by whim alone — a uniform draw across the entire spread. Everybody else is a
     *       weighted blend of the two, and the weight is their own tradition.</li>
     * </ul>
     *
     * <p><b>A blend rather than a mark plus a jitter, and the difference is the wall.</b> The
     * obvious implementation is {@code mark + jitter}, and it fails a measurement this session ran
     * before shipping it: the traits the generator actually produces span about two thirds of their
     * nominal range — industry runs −47 to 85, not −100 to 100 — so a mark derived from them only
     * ever reaches the middle of the spread, and a jitter carved out of the remainder narrows it
     * further. Measured, that version occupied <b>43 of 64 offsets</b>. Blending toward a draw over
     * the <i>whole</i> spread fixes it at the source: the uniform end of the blend does not care
     * what range the traits happen to occupy, so the coverage is a property of the arithmetic rather
     * than of a distribution that the next culture added will move.
     *
     * <p>Clamping is the other obvious implementation and it is worse than both: a clamp piles every
     * industrious-and-erratic villager onto offset zero, and a heap at offset zero is the
     * four-hundred-paths-in-one-tick the floor exists to prevent, arriving through the arithmetic
     * instead of through the config. Nothing here clamps, because nothing here can leave the range.
     *
     * <p>Seeded from the persona id and the slot, so a villager's morning offset and their afternoon
     * offset differ — otherwise the same fourteen people would always be the same fourteen ticks
     * late and the wave would have a permanent shape.
     */
    public static int offsetOf(Persona persona, DaySlot slot) {
        int span = SPREAD - 1;
        int industry = persona.trait(Persona.INDUSTRY);
        int tradition = persona.trait(Persona.TRADITION);

        // Where diligence would put them: first if they are industrious, last if they are not.
        int mark = Math.round((100 - industry) / 200.0F * span);

        long seed = mix(persona.id().getMostSignificantBits()
                ^ persona.id().getLeastSignificantBits()
                ^ (slot.ordinal() * 0x9E3779B97F4A7C15L)
                ^ OFFSET_SALT);
        // Where whim would put them: anywhere, with no regard for what they are like.
        int whim = (int) Long.remainderUnsigned(seed, SPREAD);

        // How much of themselves they bring to it. Integer arithmetic throughout: a float here
        // would be a rounding difference between two machines running the same save.
        int loyalty = (tradition + 100) / 2;
        return (mark * loyalty + whim * (100 - loyalty)) / 100;
    }

    /**
     * <b>Which slot this villager is in — which is not always the slot the clock is in.</b>
     *
     * <p>This is the method that makes {@link #offsetOf} load-bearing rather than decorative, and it
     * is the whole of what "the table is global, the crossing is not" means in code. Everybody
     * shares {@link DaySlot}'s boundaries; a villager reaches one {@code offsetOf} ticks after it,
     * so at any instant inside a spread the village is split between the old slot and the new one.
     * That is the transition wave, and it is the reason four hundred villagers do not path together.
     *
     * <p>The wrap at the start of the day is a real case rather than a formality: a villager whose
     * {@link DaySlot#DAWN} offset is forty is still in {@link DaySlot#NIGHT} at day time thirty-nine,
     * which is yesterday's slot, and asking for {@code ordinal() - 1} without folding would be an
     * array index of minus one on the first tick of every Minecraft day.
     */
    public static DaySlot slotFor(Persona persona, long dayTime) {
        DaySlot reached = DaySlot.at(dayTime);
        if (reached.elapsedAt(dayTime) >= offsetOf(persona, reached)) {
            return reached;
        }
        DaySlot[] all = DaySlot.values();
        return all[Math.floorMod(reached.ordinal() - 1, all.length)];
    }

    /**
     * True on the ticks this villager is allowed to be handed a walk target.
     *
     * <p>Keyed on the persona id rather than the entity id: an entity id changes when a villager is
     * zombified and cured, and a gate that moved under a villager would let the same one through
     * twice in a tick after a cure. It is the same argument {@code PersonaSweep.bucketOf} makes.
     */
    public static boolean pathGateOpen(Persona persona, long gameTime) {
        return Math.floorMod(persona.id().hashCode(), PATH_GATE_PERIOD)
                == Math.floorMod(gameTime, PATH_GATE_PERIOD);
    }

    /** Which of {@link #STANDOFF_OFFSETS} this villager prefers. Stable for the life of the persona. */
    public static int preferredStandoff(Persona persona) {
        long seed = mix(persona.id().getMostSignificantBits()
                ^ persona.id().getLeastSignificantBits() ^ PLACE_SALT);
        return (int) Long.remainderUnsigned(seed, STANDOFF_OFFSETS.length);
    }

    /**
     * Whether a candidate is far enough out that {@code StrollAroundPoi} and {@code WorkAtPoi} are
     * both silent, and close enough in that {@code SetWalkTargetFromBlockMemory} is too.
     *
     * <p>Both clauses are checked on the <i>resolved</i> position rather than on the table entry,
     * because the ground is not flat: a standoff seven blocks east and three blocks up a hill is
     * Manhattan ten, and the behaviour this session is built around measuring in Manhattan does not
     * care that the table said seven.
     */
    public static boolean isAStandoff(BlockPos jobSite, BlockPos candidate) {
        int dx = candidate.getX() - jobSite.getX();
        int dz = candidate.getZ() - jobSite.getZ();
        return jobSite.distManhattan(candidate) <= VANILLA_JOB_SITE_TOLERANCE
                && dx * dx + dz * dz >= STANDOFF_MIN_DIST_SQR;
    }

    /**
     * Whether a villager standing here would be close enough for {@code WorkAtPoi} to fire.
     *
     * <p>The engine's own comparison, restated: {@code closerToCenterThan(position, 1.73)} from the
     * workstation's centre. Used by the harness to assert the thing the exit criterion is actually
     * about, rather than asserting a distance somebody chose.
     */
    public static boolean isWithinArmsReach(BlockPos jobSite, BlockPos where) {
        double dx = (where.getX() + 0.5) - (jobSite.getX() + 0.5);
        double dy = where.getY() - (jobSite.getY() + 0.5);
        double dz = (where.getZ() + 0.5) - (jobSite.getZ() + 0.5);
        return dx * dx + dy * dy + dz * dz < 1.73 * 1.73;
    }

    private static long mix(long value) {
        long z = value + 0x9E3779B97F4A7C15L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }
}
