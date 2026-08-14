package net.namesake.dialogue;

/**
 * Which set of lines a villager is speaking out of. {@code DESIGN.md} §5 and §12.
 *
 * <p>Four pools, and the distance between them is the whole of what a player hears change. A pool is
 * chosen from a bond — see {@link Dialogue#poolFor} — and never from anything a player can set.
 *
 * <p><b>Session 12 owns the bands, and this is deliberately not them.</b> {@code DESIGN.md} lists
 * five standing bands with thresholds set from session 07's earn-rate data, driving exactly three
 * consumers of which pool selection is one. Naming four pools here does not decide those five bands;
 * when 12 lands, {@link Dialogue#poolFor} is the one method that changes.
 */
public enum Pool {

    /**
     * They do not know you, and every line in this pool says so.
     *
     * <p><b>{@code WORKPLAN.md}'s rule for this session, and it is a rule rather than a preference:
     * silence reads as permission, so a gap has to be authored as a negative.</b> A villager who says
     * nothing about you reads to a player as a villager who has nothing to say — which is
     * indistinguishable from a mod that is not working. {@code DialogueTest} holds every line of this
     * pool's greeting, about-you and about-others registers to carrying an explicit negative.
     */
    STRANGER,

    /** They have met you, or heard of you. Neither warm nor cold. */
    KNOWN,

    /** They like you, and it is recent — see {@link Dialogue#WARM_WARMTH}. */
    WARM,

    /** You have cost them something, and they have not forgotten it. */
    HOSTILE
}
