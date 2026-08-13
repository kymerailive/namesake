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

    /** The multiplier for a villager whose eight axes are all zero. */
    public static final float NEUTRAL = 1.0F;

    public static final float MIN = 0.4F;
    public static final float MAX = 1.6F;

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

    private Personality() {
    }

    /**
     * How much this deed is worth to this person, as a multiplier of its nominal value.
     *
     * <p>Reads {@link Persona#trait(int)} rather than {@code traits()}, which copies: this runs once
     * per witness per deed, up to thirteen times in the tick a deed is emitted.
     */
    public static float scale(Persona persona, DeedType type) {
        int column = type.id();
        float sum = NEUTRAL;
        for (int axis = 0; axis < Persona.TRAIT_COUNT; axis++) {
            sum += (persona.trait(axis) / 100.0F) * WEIGHT[axis][column];
        }
        return Math.max(MIN, Math.min(MAX, sum));
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
