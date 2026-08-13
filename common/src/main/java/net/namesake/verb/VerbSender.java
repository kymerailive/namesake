package net.namesake.verb;

import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.UUID;

/**
 * Everything the gate in {@link ServerboundVerb} needs to know about who sent a packet.
 *
 * <p>This exists so the gate can be driven by a unit test. A {@link ServerPlayer} cannot be built
 * without a running server, and a gate that is only ever exercised through a live game is a gate
 * whose ordering nobody has actually checked. {@link ServerVerbSender} is the production
 * implementation and does nothing but read the player.
 *
 * <p>Position and reach are read here rather than computed by the caller so that the reach check
 * itself lives inside the base class. A verb author cannot supply their own answer to "is this in
 * range".
 */
public interface VerbSender {

    UUID id();

    /** For log lines only. */
    String name();

    ResourceKey<Level> dimension();

    Vec3 eyePosition();

    /**
     * The sender's own entity interaction range, straight off the attribute vanilla uses.
     *
     * <p>Reading the attribute rather than hard-coding 3.0 means the gate can never reject a
     * distance vanilla itself would have allowed — including in a modpack that raises reach.
     */
    double interactionRange();

    /**
     * Monotonic server tick count, for token expiry and rate limiting.
     *
     * <p><b>Not</b> level game time. {@code /time set} and a night's sleep both move game time by
     * thousands of ticks in one tick, which would expire every open interaction on the server the
     * moment anyone went to bed.
     */
    long tickCount();

    /**
     * The real player, to hand to {@code authorize} and {@code run}.
     *
     * <p>Non-null in production. The unit-test fake returns {@code null}, so the gate itself must
     * never dereference this — everything the gate needs is on the methods above.
     */
    ServerPlayer player();
}
