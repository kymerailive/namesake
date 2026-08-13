package net.namesake.settlement;

import java.util.Arrays;

/**
 * Turns a POI census into the three things a settlement is: what it does, how safe it is, and what
 * it is short of.
 *
 * <p><b>This is the off-thread half.</b> Every method here is a pure function of ints — no level,
 * no {@code PoiManager}, no entity — so it runs on {@code Util.backgroundExecutor()} without a
 * single thread-safety question, and it is unit-testable without a running game. That split is the
 * honest reading of {@code DESIGN.md} §8's "once, off-thread, at registration": the reading of the
 * world must happen on the server thread because vanilla's storage is not thread-safe, and the
 * deriving — which is the part that would actually cost a tick — does not.
 *
 * <p><b>The numbers below are v1 and will be wrong.</b> They are deliberately shallow: a settlement
 * that cannot feed itself raises harder people, a sprawling one is less defensible than a tight
 * one. Session 07's headless harness is where thresholds like these stop being guesses, and
 * {@code WORKPLAN.md} already rules that every band threshold comes from that data rather than from
 * intuition. Until then these tilt the trait mean by at most ±16 against a culture baseline of up
 * to ±35, so being wrong here shades a settlement rather than defining it.
 */
public final class SettlementSurvey {

    /** How far out the census counts, in blocks. {@code WORKPLAN.md}: a 128-block survey. */
    public static final int SURVEY_RADIUS = 128;

    /** Below this many workstations, a settlement has no trade worth naming. */
    private static final int MIN_SITES_FOR_A_TRADE = 2;

    /** A settlement whose sites average this close to the bell counts as fully compact. */
    private static final int COMPACT_RADIUS = 16;

    /** How many people one site of each kind supports. */
    private static final int FED_PER_FOOD_SITE = 3;
    private static final int EQUIPPED_PER_TOOL_SITE = 4;
    private static final int HOUSED_PER_BED = 1;
    private static final int SUPPLIED_PER_TRADE_SITE = 5;

    private SettlementSurvey() {
    }

    /** What the census counted, with no interpretation applied. */
    public record Tally(int[] sitesBySpecialty, int beds, int workstations, int meanDistance) {
        public int sites(Specialty specialty) {
            return sitesBySpecialty[specialty.ordinal()];
        }

        @Override
        public String toString() {
            return "Tally[sites=" + Arrays.toString(sitesBySpecialty)
                    + " beds=" + beds + " workstations=" + workstations
                    + " meanDistance=" + meanDistance + ']';
        }
    }

    /** What the survey concluded. Becomes three fields on {@link Settlement}. */
    public record Survey(Specialty specialty, byte defensibility, byte[] needs) {
    }

    public static Survey score(Tally tally) {
        return new Survey(dominantTrade(tally), defensibility(tally), needs(tally));
    }

    /**
     * The trade with the most sites — but only if it is genuinely ahead.
     *
     * <p>A tie is reported as {@link Specialty#MIXED} rather than broken by enum order. Breaking it
     * arbitrarily would make half the villages in a world claim to be farming towns because
     * {@code FARMING} happens to be declared first, and the bias it feeds would then be an artefact
     * of the source file.
     */
    static Specialty dominantTrade(Tally tally) {
        Specialty best = Specialty.MIXED;
        int bestCount = 0;
        int runnerUp = 0;
        for (Specialty specialty : Specialty.values()) {
            if (specialty == Specialty.MIXED) {
                continue;
            }
            int count = tally.sites(specialty);
            if (count > bestCount) {
                runnerUp = bestCount;
                bestCount = count;
                best = specialty;
            } else if (count > runnerUp) {
                runnerUp = count;
            }
        }
        return bestCount >= MIN_SITES_FOR_A_TRADE && bestCount > runnerUp ? best : Specialty.MIXED;
    }

    /**
     * How defensible the place is, 0..100.
     *
     * <p>Two signals, both free from the census: how tightly the settlement clusters around its
     * bell, and how much of it there is. A compact village can be walked end to end by whoever is
     * defending it; a strung-out one cannot. Iron golems would be the obvious third signal and are
     * deliberately not used — they are entities, so counting them would make the answer depend on
     * which chunks happened to be loaded when the surveyor walked in, and a settlement that scores
     * differently depending on how you approached it is not a fact about the settlement.
     */
    static byte defensibility(Tally tally) {
        int counted = tally.workstations() + tally.beds();
        if (counted == 0) {
            return 0;
        }
        int compact = clamp(100 - Math.max(0, tally.meanDistance() - COMPACT_RADIUS) * 2);
        int manned = clamp(counted * 4);
        return (byte) ((compact * 2 + manned) / 3);
    }

    /** What the settlement is short of, 0..100 per {@link Need}, where 100 is desperate. */
    static byte[] needs(Tally tally) {
        // Job sites with nobody housed to fill them are as much a demand on the settlement as
        // mouths are, so demand is whichever of the two is larger.
        int demand = Math.max(1, Math.max(tally.beds(), tally.workstations()));

        int food = tally.sites(Specialty.FARMING)
                + tally.sites(Specialty.FISHING)
                + tally.sites(Specialty.PASTORAL);
        int tools = tally.sites(Specialty.SMITHING) + tally.sites(Specialty.MASONRY);
        int trade = tally.sites(Specialty.SCHOLARLY);

        byte[] needs = new byte[Need.COUNT];
        needs[Need.FOOD.index()] = shortfall(food, demand, FED_PER_FOOD_SITE);
        needs[Need.TOOLS.index()] = shortfall(tools, demand, EQUIPPED_PER_TOOL_SITE);
        needs[Need.SHELTER.index()] = shortfall(tally.beds(), demand, HOUSED_PER_BED);
        needs[Need.TRADE_GOODS.index()] = shortfall(trade, demand, SUPPLIED_PER_TRADE_SITE);
        return needs;
    }

    private static byte shortfall(int suppliers, int demand, int servedPerSupplier) {
        return (byte) clamp(100 - suppliers * servedPerSupplier * 100 / demand);
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(100, value));
    }
}
