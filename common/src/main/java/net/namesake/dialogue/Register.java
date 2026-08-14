package net.namesake.dialogue;

/**
 * <b>What kind of thing is being said</b>, as opposed to {@link Pool}, which is how they feel about
 * you. Five of them, crossed with four pools and eight lines each: {@code WORKPLAN.md}'s
 * <i>4 × 5 × ~8 ≈ 160</i>.
 *
 * <p><b>Every register has a state that selects it, and that is the point rather than a tidiness
 * rule.</b> An authored register nothing can select is dead content — which is the same failure as a
 * persisted field nothing reads, one layer up, and this project has a build rule about that. See
 * {@link Dialogue#registerFor}.
 */
public enum Register {

    /** The first thing said in a conversation. Always available. */
    GREETING,

    /** They have nothing about you to say, so they talk about the day. The fallback. */
    SMALL_TALK,

    /**
     * <b>Something they watched you do.</b> Selected when their ring holds a first-hand deed of
     * yours — {@code confidence == FIRST_HAND} — which is sessions 05 and 06 reaching a person's
     * ears for the first time.
     */
    ABOUT_YOU,

    /**
     * <b>Something they were told about you.</b> Selected when their ring holds a deed of yours that
     * they did not witness, which only exists because of session 08's drain.
     *
     * <p>This is the register the thesis is made of, and it is worth saying where it points:
     * {@code DESIGN.md} §10 step 5 — <i>return to B, someone says they've heard your name,
     * referencing A</i> — is this register speaking, one settlement further along. Session 10 adds
     * the road; the sentence already exists.
     */
    ABOUT_OTHERS,

    /** The conversation has run its course. Selected by the turn count — see {@link Conversations}. */
    PARTING
}
