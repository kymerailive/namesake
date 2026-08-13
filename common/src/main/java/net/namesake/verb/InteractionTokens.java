package net.namesake.verb;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The session token, as ruled in {@code WORKPLAN.md} on 2026-08-13.
 *
 * <p><b>A per-interaction nonce issued by the server.</b> When the server opens an interaction for
 * a player — today a conversation with a villager, later a dialogue screen or the Notice Board — it
 * mints a short-lived token, and every packet claiming to be about that interaction must present
 * it. It is not the vanilla login session, and it is not a per-packet replay nonce.
 *
 * <p>What it closes: MCA has 29 serverbound packets, and a modified client can send any of them for
 * a screen it never opened, at an NPC it has never seen, from anywhere in the world. Reach alone
 * does not close that — the client can stand next to the villager and still send packets for a
 * screen that was never opened. A token the server issued and remembers does.
 *
 * <p>One live interaction per player, deliberately. A player has one screen open at a time; letting
 * a second interaction live alongside the first would mean a token from a screen the player closed
 * still authorises packets.
 *
 * <p>The token is bound to the <i>target</i> as well as the player, so a token issued for one
 * villager cannot be replayed at the villager next to it.
 */
public final class InteractionTokens {

    /**
     * How long an interaction lives without use, in server ticks. Sixty seconds: long enough that a
     * player reading a screen does not have it expire under them, short enough that a token is not
     * worth stealing.
     *
     * <p>Refreshed on every accepted verb, so an interaction the player is actually using never
     * expires.
     */
    public static final long TTL_TICKS = 1200L;

    /** Sweeping costs a full map walk, so only do it when the map is big enough to be worth it. */
    private static final int SWEEP_ABOVE = 128;

    /**
     * @param token     the nonce the client must present
     * @param player    who it was issued to
     * @param targetKey what it was issued about — see {@link VerbTarget#key()}
     * @param dimension where it was issued; a token does not survive a dimension change
     * @param expiresAt server tick count after which it is dead
     */
    public record Interaction(long token, UUID player, UUID targetKey,
                              ResourceKey<Level> dimension, long expiresAt) {
    }

    /**
     * @param interaction the live interaction, new or refreshed
     * @param isNew       true only when a token was minted, so the client is told exactly once
     */
    public record Opened(Interaction interaction, boolean isNew) {
    }

    private final Map<UUID, Interaction> open = new HashMap<>();
    private final SecureRandom random = new SecureRandom();

    /**
     * Opens an interaction, or refreshes the one already open for the same player and target.
     *
     * <p>Refreshing rather than re-minting matters: the gesture that opens a conversation is the
     * same gesture that uses it, so re-minting on every click would invalidate the token the client
     * is about to send in the same tick.
     */
    public Opened open(UUID player, UUID targetKey, ResourceKey<Level> dimension, long now) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(targetKey, "targetKey");
        Objects.requireNonNull(dimension, "dimension");

        // One entry per player who has ever opened an interaction this session. Bounded on a small
        // server, unbounded over a long-running large one — so drop the dead ones occasionally.
        if (open.size() > SWEEP_ABOVE) {
            sweep(now);
        }

        Interaction existing = open.get(player);
        if (existing != null
                && existing.targetKey().equals(targetKey)
                && existing.dimension().equals(dimension)
                && now <= existing.expiresAt()) {
            Interaction refreshed = new Interaction(existing.token(), player, targetKey, dimension,
                    now + TTL_TICKS);
            open.put(player, refreshed);
            return new Opened(refreshed, false);
        }

        Interaction minted = new Interaction(mintToken(), player, targetKey, dimension,
                now + TTL_TICKS);
        open.put(player, minted);
        return new Opened(minted, true);
    }

    /**
     * The whole point of this class. Returns the live interaction only if the presented token was
     * issued to this player, for this target, and has not expired.
     *
     * <p>A successful validation refreshes the expiry: an interaction in use does not time out.
     */
    public Optional<Interaction> validate(UUID player, long token, UUID targetKey, long now) {
        Interaction interaction = open.get(player);
        if (interaction == null
                || interaction.token() != token
                || !interaction.targetKey().equals(targetKey)
                || now > interaction.expiresAt()) {
            return Optional.empty();
        }
        Interaction refreshed = new Interaction(interaction.token(), interaction.player(),
                interaction.targetKey(), interaction.dimension(), now + TTL_TICKS);
        open.put(player, refreshed);
        return Optional.of(refreshed);
    }

    /** The interaction open for this player, live or not. Read-only; validity is {@link #validate}. */
    public Optional<Interaction> current(UUID player) {
        return Optional.ofNullable(open.get(player));
    }

    public void close(UUID player) {
        open.remove(player);
    }

    public void clear() {
        open.clear();
    }

    /** Drops interactions that have expired. Idempotent; validity does not depend on it. */
    public void sweep(long now) {
        open.values().removeIf(interaction -> now > interaction.expiresAt());
    }

    public int size() {
        return open.size();
    }

    /** Zero is the wire sentinel for "no token", so it can never be a real one. */
    private long mintToken() {
        long token;
        do {
            token = random.nextLong();
        } while (token == 0L);
        return token;
    }
}
