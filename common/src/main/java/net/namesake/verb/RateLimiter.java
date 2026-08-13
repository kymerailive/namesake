package net.namesake.verb;

import net.minecraft.resources.ResourceLocation;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * A token bucket per (sender, verb).
 *
 * <p>The point is not to stop a player greeting a villager twice. It is that a packet handler is a
 * function a hostile client can call as fast as it can write to a socket, and every check in the
 * gate downstream of this one costs something — an entity lookup, a map lookup, whatever the verb
 * itself does. This runs first for that reason.
 *
 * <p>Buckets refill continuously rather than resetting on a window boundary, so a client cannot get
 * {@code 2 × burst} through by sending on either side of a boundary.
 */
public final class RateLimiter {

    /**
     * @param burst       how many uses may be spent at once
     * @param windowTicks how long a full bucket takes to refill, in server ticks
     */
    public record Policy(int burst, int windowTicks) {
        public Policy {
            if (burst < 1) {
                throw new IllegalArgumentException("A rate policy with burst " + burst
                        + " would refuse every packet, including the first.");
            }
            if (windowTicks < 1) {
                throw new IllegalArgumentException("A rate policy needs a positive window, got "
                        + windowTicks);
            }
        }

        double refillPerTick() {
            return (double) burst / windowTicks;
        }
    }

    /**
     * Buckets untouched for this long are dropped. Five minutes of server time — long enough that
     * a returning player is never handed a fresh burst they had already spent, short enough that
     * the map cannot grow without bound on a busy server.
     */
    private static final long IDLE_TICKS = 6000L;

    /** Sweeping costs a full map walk, so only do it when the map is big enough to be worth it. */
    private static final int SWEEP_ABOVE = 128;

    private record Key(UUID sender, ResourceLocation verb) {
    }

    private static final class Bucket {
        double tokens;
        long lastTouched;

        Bucket(double tokens, long now) {
            this.tokens = tokens;
            this.lastTouched = now;
        }
    }

    private final Map<Key, Bucket> buckets = new HashMap<>();

    /**
     * Spends one use if the sender has one.
     *
     * @param now the monotonic server tick count — see {@link VerbSender#tickCount()}
     */
    public boolean tryAcquire(UUID sender, ResourceLocation verb, Policy policy, long now) {
        Objects.requireNonNull(sender, "sender");
        Objects.requireNonNull(verb, "verb");
        Objects.requireNonNull(policy, "policy");

        if (buckets.size() > SWEEP_ABOVE) {
            sweep(now);
        }

        Bucket bucket = buckets.computeIfAbsent(new Key(sender, verb),
                key -> new Bucket(policy.burst(), now));

        long elapsed = Math.max(0L, now - bucket.lastTouched);
        bucket.tokens = Math.min(policy.burst(), bucket.tokens + elapsed * policy.refillPerTick());
        bucket.lastTouched = now;

        if (bucket.tokens < 1.0) {
            return false;
        }
        bucket.tokens -= 1.0;
        return true;
    }

    /** Drops buckets nobody has touched recently. Idempotent. */
    public void sweep(long now) {
        buckets.entrySet().removeIf(entry -> now - entry.getValue().lastTouched > IDLE_TICKS);
    }

    public void clear() {
        buckets.clear();
    }

    /** Bucket count, for tests and {@code /namesake debug}. */
    public int size() {
        return buckets.size();
    }
}
