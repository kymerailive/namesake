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
 * extreme, moves what that kind of deed is worth to this person. A <i>typical</i> villager — see
 * {@link #typical()} — scores exactly {@link #NEUTRAL} and gets the nominal numbers in
 * {@link DeedType}; each axis then adds {@code trait/100 × weight} to that. So an acquisitive
 * villager values a wanted gift far more than a placid one does, and a hot-tempered one discounts
 * gifts and takes a blow much harder.
 *
 * <p><b>Bounded, and the bound is not decoration.</b> Eight axes at their extremes can sum past
 * anything sensible, and an unbounded multiplier means one lucky roll produces a villager for whom
 * a single loaf is worth a rescue. {@link #MIN} and {@link #MAX} are where that stops, and
 * <b>"they bind only at the corners" is a measured property rather than a hope</b>: over the whole
 * generator space they engage on well under a percent of (persona × deed type) pairs, and
 * {@code PersonalityDistributionTest.theClampBindsOnlyAtTheCorners} is what says so. A clamp that
 * bit in ordinary play would be flattening real variation rather than guarding against absurd
 * variation, which is the opposite of what it is for — so it has to widen whenever {@link #SPREAD}
 * does.
 *
 * <p><b>A weight may sharpen a harmful deed and may never soften one.</b> That rule does not live
 * here — {@link Deeds#deltaFor} owns it — because it is a property of how a weight is <i>used</i>
 * rather than of the table. The table simply says a hot temper makes a strike land harder, which is
 * true whichever direction the arithmetic happens to be running.
 */
public final class Personality {

    /** The multiplier for a <i>typical</i> villager — see {@link #typical()}. */
    public static final float NEUTRAL = 1.0F;

    public static final float MIN = 0.3F;
    public static final float MAX = 1.8F;

    /**
     * How far the shape in {@link #SHAPE} is allowed to move a deed's value. <b>Session 07's
     * magnitude, and the whole of what session 07 changed about this table.</b>
     *
     * <p>Session 05 closed with the owner's ruling that <i>two villagers a week apart on identical
     * treatment should end up a full standing band apart</i>, about 25 points, and measured 14. It
     * also ruled <i>shape now, magnitude at session 07</i>. This constant is what makes those two
     * sentences compatible: the eight-by-six table below is byte-for-byte the one session 05
     * shipped, and every weight is read through this multiplier, so the magnitude moved and the
     * shape provably did not.
     *
     * <p><b>Why one number rather than forty-eight retuned literals.</b> Scaling the whole table by
     * <i>k</i> scales every villager's <i>deviation from neutral</i> by exactly <i>k</i> — the
     * centring in {@link #CENTRE} scales with it, so a typical villager still scores exactly
     * {@link #NEUTRAL} by construction. That makes the tuning one-dimensional and reversible, and it
     * means session 12 can move the magnitude again without anyone having to decide afresh whether
     * acquisitiveness should matter more to a gift than warmth does.
     *
     * <p><b>What 1.6 buys, measured rather than chosen.</b>
     * {@code PersonalityDistributionTest.theWeekApart} sweeps a settlement's least and most
     * receptive resident through a real week of gifts. The week-apart is <b>quantised to multiples
     * of seven</b>, because the daily allowance is an integer and seven days of it is what the gap
     * is made of — so 25 is not a reachable value and the honest targets either side of it are 21
     * and 28. 1.2 reaches 21 and 1.6 is the smallest multiplier that reaches 28.
     *
     * <p><b>What it costs, stated rather than discovered later.</b> The between-culture gap widens by
     * the same factor, because it comes out of the same table: a Karsk villager warms at roughly half
     * a Meridian one's rate where before it was three quarters. That is a real change to how a
     * village feels and it is the owner's to rule; it is also, for what it is worth, the direction
     * standing risk 3 wants.
     */
    public static final float SPREAD = 1.6F;

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
     * <b>The shape, and only the shape.</b> Rows are {@code Persona}'s eight axes in their declared
     * order; columns are {@link DeedType}'s six ids in theirs.
     *
     * <p>Read through {@link #weight(int, DeedType)}, which applies {@link #SPREAD}. Nothing should
     * read this array directly: a cell here says <i>how much this axis matters relative to the
     * others</i>, and the effective weight is that times the magnitude. Session 07 moved the
     * magnitude and left every number below untouched, which is only a checkable claim because the
     * two live in different places.
     *
     * <pre>
     *                     gift+   gift-   fed    struck  killed  raid
     * </pre>
     */
    private static final float[][] SHAPE = {
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
                sum += (TYPICAL[axis] / 100.0F) * weight(axis, type);
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
            sum += (persona.trait(axis) / 100.0F) * SHAPE[axis][column] * SPREAD;
        }
        return Math.max(MIN, Math.min(MAX, sum));
    }

    /** True when the clamp engaged, which is what the corners-only claim is measured against. */
    public static boolean isClamped(Persona persona, DeedType type) {
        float scale = scale(persona, type);
        return scale <= MIN || scale >= MAX;
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
     * gifts the median settlement's two extremes diverge by <b>28 points</b> instead of nothing —
     * the full standing band the owner asked for, reached at session 07 by widening {@link #SPREAD}
     * from 1.0 to 1.6. Session 05 shipped this mechanism at a median of 14.
     *
     * <p><b>The gap is quantised to multiples of seven and that is structural, not a rounding
     * artefact.</b> An allowance is an integer because {@code Bond.gainedToday} counts it in four
     * bits, so seven days of two villagers' allowances can only ever differ by a multiple of seven.
     * There is no tuning of this table that lands the median on exactly 25.
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

    /**
     * One cell's <b>effective</b> weight — the shape times {@link #SPREAD}.
     *
     * <p>Deliberately not the raw table. A caller asking "how much does temper matter to a gift"
     * wants the number the arithmetic actually uses; a caller wanting the shape alone would be
     * asking a question only this class has any business having an opinion about.
     */
    public static float weight(int axis, DeedType type) {
        return SHAPE[axis][type.id()] * SPREAD;
    }

    public static int rows() {
        return SHAPE.length;
    }

    public static int columns() {
        return SHAPE[0].length;
    }
}
