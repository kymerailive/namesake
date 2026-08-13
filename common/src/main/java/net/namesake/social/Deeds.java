package net.namesake.social;

import net.namesake.npc.Persona;

/**
 * What a deed is worth to one particular person. {@code DESIGN.md} §4 step 4, and nothing else.
 *
 * <p>Pure arithmetic over immutable values — no level, no registry, no entity. That is what makes
 * every number in this session a ten-millisecond unit test rather than six minutes of CI, which is
 * the line {@code WORKPLAN.md} draws between its two instruments.
 *
 * <h2>Four weights, and the difference between them is the whole rule</h2>
 *
 * <p>Two are <b>structural</b> — they say how much of this deed is yours at all — and apply
 * whichever way the deed cuts:
 *
 * <ul>
 *   <li>the <b>witness share</b>, from {@link DeedType#witnessShare()}: the subject gets all of it,
 *       a bystander gets a fraction;</li>
 *   <li>the <b>place weight</b>: a witness who does not live in the settlement the deed happened in
 *       takes it at {@link #OUTSIDER_WEIGHT}. It is not their village. The subject is never
 *       discounted this way — it happened to <i>them</i>, wherever they were standing.</li>
 * </ul>
 *
 * <p>Two are <b>softeners</b>, and neither is ever allowed to make a harmful deed cheaper:
 *
 * <ul>
 *   <li>the <b>personality weight</b> ({@link Personality}), which is clamped up to
 *       {@link Personality#NEUTRAL} for a negative axis, so a placid villager does not charge less
 *       for a killing while a vengeful one still charges more;</li>
 *   <li>the <b>daily cap</b>, which lives in {@link Bond#apply} because it needs the bond's own
 *       state — and which negatives skip entirely.</li>
 * </ul>
 *
 * <p>And a third thing that could soften a negative by accident: <b>rounding</b>. A structural
 * weight can take a −1 to −0.33, which rounds to nothing at all, and "he hit me but it did not
 * count" is the same bug as an uncapped apology. So a harmful axis always moves by at least one.
 */
public final class Deeds {

    /**
     * What a witness who is not a resident of the settlement the deed happened in records.
     *
     * <p>Three quarters, not zero: a traveller passing through still saw it. The consumer of
     * {@link Deed#settlementId()}, and the reason a deed carries where it happened rather than
     * leaving that to be inferred from the actor's position later.
     */
    public static final float OUTSIDER_WEIGHT = 0.75F;

    private Deeds() {
    }

    /**
     * The four-axis change this deed makes to {@code holder}'s bond with the actor.
     *
     * <p><b>Whether this is the subject is read off the deed rather than passed in.</b> A deed that
     * has to be told who it happened to is a deed that can be told wrong, and every caller would be
     * repeating a comparison the record already carries the answer to.
     *
     * @param holder whose bond is moving — the subject, or one of the witnesses
     * @return four signed values in {@link Bond}'s axis order. All zero means nothing happened to
     * this person, and {@link DeedBus} is what declines to store a row for it.
     */
    public static int[] deltaFor(Deed deed, Persona holder) {
        DeedType type = deed.type();
        boolean isSubject = holder.id().equals(deed.subject());
        float personality = Personality.scale(holder, type);

        float share = isSubject ? 1.0F : type.witnessShare();
        float place = isSubject || holder.settlementId() == deed.settlementId()
                ? 1.0F
                : OUTSIDER_WEIGHT;
        float structural = share * place
                * (deed.severity() / 100.0F)
                * (deed.confidence() / 100.0F);

        int[] delta = new int[Bond.AXIS_COUNT];
        for (int axis = 0; axis < Bond.AXIS_COUNT; axis++) {
            int nominal = type.delta(axis);
            if (nominal == 0) {
                continue;
            }
            // The one asymmetry: a weight below neutral is ignored on the way down.
            float weighted = nominal > 0
                    ? personality
                    : Math.max(Personality.NEUTRAL, personality);
            int rounded = round(nominal * weighted * structural);
            if (nominal < 0 && rounded == 0) {
                rounded = -1;
            }
            delta[axis] = rounded;
        }
        return delta;
    }

    /**
     * Rounds away from zero rather than toward positive infinity.
     *
     * <p>{@code Math.round} takes −4.5 to −4, which is a magnitude the arithmetic never asked to
     * lose. Half of the invariants above are about a negative keeping its size; a rounding mode that
     * quietly shaves it would defeat them without failing anything that only checks the sign.
     */
    static int round(float value) {
        return value < 0 ? -Math.round(-value) : Math.round(value);
    }
}
