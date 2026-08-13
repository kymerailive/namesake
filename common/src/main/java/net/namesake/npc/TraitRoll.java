package net.namesake.npc;

import net.minecraft.core.BlockPos;
import net.namesake.culture.Culture;
import net.namesake.settlement.Need;
import net.namesake.settlement.Settlement;
import net.namesake.settlement.Settlements;
import net.namesake.settlement.Specialty;

/**
 * The three-layer trait roll: settlement mean → household ±20 → individual ±25.
 *
 * <p><b>This is where three of {@code SocialValueLedgerTest}'s expiring exemptions are paid off.</b>
 * {@link #roll} reads a persona's {@code settlementId} to choose whose mean it starts from, its
 * {@code householdId} to seed the middle layer, and its {@code cultureId} for both the baseline and
 * how far individuals are allowed to stray from it. {@link #settlementMean} reads the survey's
 * three outputs off the {@link Settlement}. None of those reads is decorative; change any one of
 * them and different numbers come out.
 *
 * <p><b>Clamped to ±100, and to nothing else.</b> The obvious mistake — and the one the ledger
 * calls out by name — is clamping a child to its parent's range, so that a household ±20 about a
 * settlement mean of 40 can only produce 20..60 and an individual can never leave it. Do that and
 * every villager in a settlement converges on the settlement, which is precisely the flat, samey
 * world this session exists to avoid. Each layer is applied and then clamped to the axis bounds
 * alone, so a warm culture in a warm settlement really can produce someone at 100, and a cold one
 * in the same household really can produce someone at 12.
 *
 * <p><b>Why the layers are seeded separately.</b> The household layer is seeded from the household
 * id and nothing else, so every member of a household draws the <i>same</i> middle layer — that is
 * what makes siblings resemble each other, and it is the half of this session's exit criterion the
 * owner reads as "households are recognisably related". The individual layer is seeded from the
 * persona id, so they are still themselves.
 */
public final class TraitRoll {

    /** How far a household may sit from its settlement's mean, per axis. */
    public static final int HOUSEHOLD_SPREAD = 20;

    /**
     * How far an individual may sit from their household's mean, per axis, at full conformity.
     * A culture may narrow this — never widen it — through {@link Culture#conformity()}.
     */
    public static final int INDIVIDUAL_SPREAD = 25;

    /** How far a settlement may sit from its culture-and-circumstances mean, per axis. */
    private static final int SETTLEMENT_SPREAD = 10;

    private static final long HOUSEHOLD_SALT = 0x48_4F_55_53_45_00_00_01L;
    private static final long INDIVIDUAL_SALT = 0x53_45_4C_46_00_00_00_01L;
    private static final long SETTLEMENT_SALT = 0x54_4F_57_4E_00_00_00_01L;

    private TraitRoll() {
    }

    /**
     * Rolls a placed persona's eight axes.
     *
     * <p>The persona must already know its settlement, household and culture — see
     * {@link Persona#placed}. Rolling and placing are separate steps so that the roll is a pure
     * function of a persona plus the settlement table, testable without a world.
     */
    public static byte[] roll(Persona persona, Settlements settlements) {
        if (!persona.isGenerated()) {
            throw new IllegalStateException("Persona " + persona.id()
                    + " has no culture, so there is nothing to roll it against. Place it first.");
        }
        Culture culture = Culture.byId(persona.cultureId());
        Settlement settlement = settlements.byId(persona.settlementId()).orElse(null);

        byte[] settlementMean = settlementMean(culture, settlement);
        byte[] householdMean = jitter(settlementMean,
                mix(persona.householdId() * 0x9E3779B97F4A7C15L ^ HOUSEHOLD_SALT),
                HOUSEHOLD_SPREAD);

        // Conformity only ever narrows, so the ruled ±25 remains the bound for every culture.
        int individualSpread = Math.round(INDIVIDUAL_SPREAD * culture.conformity());
        return jitter(householdMean,
                mix(persona.id().getMostSignificantBits() ^ persona.id().getLeastSignificantBits()
                        ^ INDIVIDUAL_SALT),
                individualSpread);
    }

    /**
     * Where a settlement's people sit before household and individual variation: their culture's
     * baseline, tilted by what the place actually does and what it is short of.
     *
     * <p><b>The consumer of every field the survey produced.</b> {@code specialty} chooses a bias
     * vector, {@code defensibility} and the four {@code needs} shift individual axes. A settlement
     * that cannot feed itself raises warier, greedier people; an exposed one raises people quicker
     * to anger and slower to open up. Those are the {@code if} statements rule 5 asks for.
     *
     * @param settlement may be null — a villager living outside any settlement has nothing but
     *                   their culture, which is the honest answer rather than a fabricated one
     */
    static byte[] settlementMean(Culture culture, Settlement settlement) {
        int[] mean = new int[Persona.TRAIT_COUNT];
        for (int axis = 0; axis < Persona.TRAIT_COUNT; axis++) {
            mean[axis] = culture.traitBase(axis);
        }
        if (settlement == null) {
            return clampAll(mean);
        }

        Specialty trade = settlement.specialtyValue();
        for (int axis = 0; axis < Persona.TRAIT_COUNT; axis++) {
            mean[axis] += trade.traitBias(axis);
        }

        int exposure = 100 - settlement.defensibility();
        mean[Persona.BOLDNESS] -= exposure / 8;
        mean[Persona.TEMPER] += exposure / 10;
        mean[Persona.SOCIABILITY] -= exposure / 12;

        mean[Persona.ACQUISITIVENESS] += settlement.need(Need.FOOD) / 6;
        mean[Persona.WARMTH] -= settlement.need(Need.FOOD) / 8;
        mean[Persona.INDUSTRY] += settlement.need(Need.TOOLS) / 10;
        mean[Persona.TEMPER] += settlement.need(Need.SHELTER) / 10;
        mean[Persona.CURIOSITY] -= settlement.need(Need.TRADE_GOODS) / 12;

        // Two farming villages of the same culture would otherwise be one village written twice.
        // Seeded from the centre rather than the id so a settlement's character is a property of
        // the place, not of the order in which the player happened to find it.
        BlockPos centre = settlement.centre();
        long seed = mix((long) centre.getX() * 0xC2B2AE3D27D4EB4FL
                ^ (long) centre.getZ() * 0x165667B19E3779F9L ^ SETTLEMENT_SALT);
        return jitter(clampAll(mean), seed, SETTLEMENT_SPREAD);
    }

    /**
     * One layer: a uniform draw in {@code [-spread, +spread]} per axis, added to the parent and
     * clamped to the axis bounds.
     *
     * <p>Uniform rather than bell-shaped on purpose. The composition of three uniform layers is
     * already well clustered, and a uniform draw makes the bound exactly testable — a distribution
     * whose tails are "unlikely" cannot be asserted, and an untestable bound is how a ±25 quietly
     * becomes a ±60.
     */
    static byte[] jitter(byte[] parent, long seed, int spread) {
        byte[] rolled = new byte[Persona.TRAIT_COUNT];
        long state = seed;
        for (int axis = 0; axis < Persona.TRAIT_COUNT; axis++) {
            state = mix(state);
            int delta = spread <= 0
                    ? 0
                    : (int) Long.remainderUnsigned(state, 2L * spread + 1) - spread;
            rolled[axis] = clamp(parent[axis] + delta);
        }
        return rolled;
    }

    private static byte[] clampAll(int[] values) {
        byte[] clamped = new byte[values.length];
        for (int axis = 0; axis < values.length; axis++) {
            clamped[axis] = clamp(values[axis]);
        }
        return clamped;
    }

    /** The only clamp in this file, and it is to the axis bounds — never to the parent's range. */
    private static byte clamp(int value) {
        return (byte) Math.max(-100, Math.min(100, value));
    }

    private static long mix(long value) {
        long z = value + 0x9E3779B97F4A7C15L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }
}
