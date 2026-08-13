package net.namesake.verb;

/**
 * What the gate did with one serverbound packet.
 *
 * <p>Every value except {@link #ACCEPTED} is a refusal, and each names the check that refused. The
 * distinction only ever reaches the server log — see {@link Authorization}.
 */
public enum VerbOutcome {

    /** Every check passed and the verb's payload ran. */
    ACCEPTED,

    /** The sender is sending this verb faster than its rate policy allows. */
    RATE_LIMITED,

    /** The payload named something that is not a target of this verb at all. */
    TARGET_UNRESOLVED,

    /** The target resolved but is dead, removed, or no longer what it claimed to be. */
    TARGET_GONE,

    /** The target is in a different dimension from the sender. */
    WRONG_DIMENSION,

    /** The target is further away than the sender could interact with it. */
    OUT_OF_REACH,

    /**
     * No live interaction backs this packet: the sender presented a token the server never issued,
     * one that has expired, or one issued for a different target.
     *
     * <p>This is the check MCA has no equivalent of, and the one a forged packet fails first.
     */
    NO_LIVE_INTERACTION,

    /** Everything above passed and the verb's own {@code authorize} said no. */
    DENIED;

    public boolean accepted() {
        return this == ACCEPTED;
    }
}
