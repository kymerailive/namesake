package net.namesake.social;

import net.namesake.npc.Persona;

/**
 * The personality weight table: <b>one static {@code float[8][6]}</b>, eight trait axes by six deed
 * types, and the single reason {@code Persona.traits} is allowed to exist.
 *
 * <p><b>This is standing risk 4, paid off.</b> Traits have been written, persisted and displayed
 * since session 01 with nothing branching on them — the exact failure {@code DESIGN.md} §1 forbids
 * and the one both reference codebases died of. {@code SocialValueLedgerTest} granted them an
 * exemption that expires at the close of session 05, and this table is what it was waiting for.
 * Every input to the trait roll was already consumed by session 03; this is the output finally
 * doing something.
 *
 * <p><b>How to read a cell.</b> {@code WEIGHT[axis][type]} is how strongly that trait, at its
 * extreme, moves what that kind of deed is worth to this person. A villager with every axis at zero
 * scores exactly {@link #NEUTRAL} and gets the nominal numbers in {@link DeedType}; each axis then
 * adds {@code trait/100 × weight} to that. So an acquisitive villager values a wanted gift far more
 * than a placid one does, and a hot-tempered one discounts gifts and takes a blow much harder.
 *
 * <p><b>Bounded, and the bound is not decoration.</b> Eight axes at their extremes can sum past
 * anything sensible, and an unbounded multiplier means one lucky roll produces a villager for whom
 * a single loaf is worth a rescue. {@link #MIN} and {@link #MAX} are where that stops. They bind
 * only at the corners: a strongly-drawn villager lands around 0.7 or 1.5 and never reaches them.
 *
 * <p><b>A weight may sharpen a harmful deed and may never soften one.</b> That rule does not live
 * here — {@link Deeds#deltaFor} owns it — because it is a property of how a weight is <i>used</i>
 * rather than of the table. The table simply says a hot temper makes a strike land harder, which is
 * true whichever direction the arithmetic happens to be running.
 */
public final class Personality {

    /** The multiplier for a <i>typical</i> villager — see {@link #typical()}. */
    public static final float NEUTRAL = 1.0F;

    public static final float MIN = 0.4F;
    public static final float MAX = 1.6F;

    /**
     * The trait vector of the average villager the generator actually produces.
     *
     * <p><b>Measured, not chosen.</b> {@code PersonalityDistributionTest} rolls 4,536 personas
     * across every culture, specialty, defensibility and needs vector the generator can produce and
     * reports the mean of each axis; these are those numbers. That test also asserts they are still
     * right, so a culture's baseline changing or a seventh culture arriving turns the build red
     * rather than quietly making "typical" untypical.
     *
     * <p><b>Why eight zeroes was the wrong reference point.</b> Until the close of session 05 a
     * villager with no personality at all scored exactly {@link #NEUTRAL}, which made the nominal
     * numbers in {@link DeedType} the value of a deed to a person who does not exist. Every real
     * villager has a culture, and every culture has a baseline — industry averages 24 across the
     * six, tradition 20 — so the population sat at ×1.04 for a gift and ×1.13 for a defended raid,
     * and the exit criterion's "+3" was a number few players would ever see. Ruled at the close of
     * session 05: <b>nominal means typical.</b>
     */
    private static final byte[] TYPICAL = {3, 24, 2, 3, 20, 9, 7, 1};

    /**
     * Rows are {@code Persona}'s eight axes in their declared order; columns are
     * {@link DeedType}'s six ids in theirs.
     *
     * <pre>
     *                     gift+   gift-   fed    struck  killed  raid
     * </pre>
     */
    private static final float[][] WEIGHT = {
            /* warmth          */ {+0.35F, +0.30F, +0.40F, +0.10F, +0.05F, +0.25F},
            /* industry        */ {+0.05F, +0.00F, +0.05F, +0.00F, +0.00F, +0.15F},
            /* boldness        */ {+0.00F, +0.00F, +0.00F, -0.20F, -0.15F, +0.30F},
            /* curiosity       */ {+0.15F, +0.25F, +0.00F, +0.00F, +0.00F, +0.05F},
            /* tradition       */ {-0.10F, -0.20F, +0.20F, +0.25F, +0.30F, +0.35F},
            /* acquisitiveness */ {+0.45F, +0.15F, +0.10F, +0.00F, +0.00F, +0.00F},
            /* temper          */ {-0.20F, -0.35F, -0.10F, +0.40F, +0.35F, +0.10F},
            /* sociability     */ {+0.20F, +0.15F, +0.25F, +0.05F, +0.00F, +0.20F},
    };

    /**
     * What {@link #TYPICAL} scores on each column, subtracted so that it scores exactly one.
     *
     * <p>Derived at class initialisation rather than written down. A per-column constant somebody
     * typed in is a constant that drifts the moment a weight is retuned; computing it from the
     * table and the population mean means the two cannot disagree, and the invariant
     * <i>a typical villager scores exactly {@link #NEUTRAL}</i> holds by construction on every
     * column, for ever, without a test having to check six numbers.
     */
    private static final float[] CENTRE = centres();

    private static float[] centres() {
        float[] centres = new float[DeedType.values().length];
        for (DeedType type : DeedType.values()) {
            float sum = 0F;
            for (int axis = 0; axis < Persona.TRAIT_COUNT; axis++) {
                sum += (TYPICAL[axis] / 100.0F) * WEIGHT[axis][type.id()];
            }
            centres[type.id()] = sum;
        }
        return centres;
    }

    private Personality() {
    }

    /** Defensive copy — a caller must not be able to redefine "typical" for everyone. */
    public static byte[] typical() {
        return TYPICAL.clone();
    }

    /**
     * How much this deed is worth to this person, as a multiplier of its nominal value.
     *
     * <p>Reads {@link Persona#trait(int)} rather than {@code traits()}, which copies: this runs once
     * per witness per deed, up to thirteen times in the tick a deed is emitted.
     */
    public static float scale(Persona persona, DeedType type) {
        int column = type.id();
        float sum = NEUTRAL - CENTRE[column];
        for (int axis = 0; axis < Persona.TRAIT_COUNT; axis++) {
            sum += (persona.trait(axis) / 100.0F) * WEIGHT[axis][column];
        }
        return Math.max(MIN, Math.min(MAX, sum));
    }

    /**
     * How much this villager's opinion of one person may move in a single in-game day.
     *
     * <p><b>Ruled at the close of session 05: personality controls the ceiling, not the step.</b>
     * The playtest that produced that ruling found the reason — the daily cap and the weight table
     * were pulling against each other. Scaling only what one deed is worth means a warm villager
     * gains 4 a gift and a cold one 3, which is visible to a player who gives one gift a day and
     * <i>completely erased</i> for one who gives three: both hit the same cap of 8 and end the day
     * identical. Personality decided how many gifts it took, not where anybody ended up.
     *
     * <p>Scaling the allowance survives that, because it <i>is</i> the cap. Over a week of daily
     * gifts the two diverge by roughly 28 points instead of nothing, which is the full standing band
     * the owner asked for.
     *
     * <p><b>Averaged over the deeds that can fill it, and no others.</b> The cap only ever limits
     * positives — a negative bypasses it entirely — so the question this answers is "how much good
     * can be done to this person in a day", and the harmful columns have no business in it. Without
     * that filter a hot temper, which scores high on {@code STRUCK_RESIDENT}, would <i>raise</i> a
     * villager's capacity for warmth.
     *
     * <p>No new numbers: it is the same table read a second way.
     */
    public static int allowance(Persona persona) {
        float sum = 0F;
        int benign = 0;
        for (DeedType type : DeedType.values()) {
            if (type.isHarmful()) {
                continue;
            }
            sum += scale(persona, type);
            benign++;
        }
        return Math.max(1, Math.round(Bond.DAILY_CAP * (sum / benign)));
    }

    /** One cell, for the tests that assert the table's shape rather than its contents. */
    public static float weight(int axis, DeedType type) {
        return WEIGHT[axis][type.id()];
    }

    public static int rows() {
        return WEIGHT.length;
    }

    public static int columns() {
        return WEIGHT[0].length;
    }
}
