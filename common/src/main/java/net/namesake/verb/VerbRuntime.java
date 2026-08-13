package net.namesake.verb;

/**
 * The mutable state the gate needs, in one object with a lifetime.
 *
 * <p>Tokens and rate buckets are per-server, not per-world and not global. Handing the runtime to
 * the gate as a parameter rather than reaching for a static means a unit test gets a fresh one per
 * test, and it means leaving a server cannot leave a live token behind for the next one.
 */
public final class VerbRuntime {

    private final InteractionTokens tokens = new InteractionTokens();
    private final RateLimiter rates = new RateLimiter();
    private final boolean logRefusals;

    /**
     * @param logRefusals whether a refused packet is worth a log line at INFO. True in a
     *                    development environment, where a silent refusal during a playtest is
     *                    indistinguishable from the packet never arriving. False in production,
     *                    where a hostile client would otherwise write the log for us.
     */
    public VerbRuntime(boolean logRefusals) {
        this.logRefusals = logRefusals;
    }

    public InteractionTokens tokens() {
        return tokens;
    }

    public RateLimiter rates() {
        return rates;
    }

    public boolean logRefusals() {
        return logRefusals;
    }

    /** Periodic housekeeping. Nothing depends on it for correctness — both stores check expiry. */
    public void sweep(long now) {
        tokens.sweep(now);
        rates.sweep(now);
    }

    public void clear() {
        tokens.clear();
        rates.clear();
    }
}
