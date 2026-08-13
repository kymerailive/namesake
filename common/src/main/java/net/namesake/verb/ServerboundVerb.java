package net.namesake.verb;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.namesake.Namesake;

import java.util.Objects;
import java.util.Optional;

/**
 * The base every packet this mod accepts from a client must extend. <b>Hard rule 6.</b>
 *
 * <p>MCA has 29 serverbound packets with essentially no authorization on any of them. The failure
 * is not that its authors were careless; it is that nothing about writing a packet handler forces
 * you to think about who is allowed to send it. So here the gate is the base class and the payload
 * is the subclass, rather than the other way around:
 *
 * <ul>
 *   <li>{@link #authorize} is <b>abstract</b>. A verb that does not answer it does not compile.</li>
 *   <li>{@link #receive} is <b>final</b>. A verb cannot replace the gate, only what runs after it.</li>
 *   <li>{@link VerbRegistry#register} refuses any verb whose own class does not declare
 *       {@code authorize}, so an addon cannot inherit somebody else's permissive answer.</li>
 *   <li>The rate, reach, dimension, target-validity and interaction-token checks are all in
 *       {@link #receive} and none of them are overridable. There is nothing to forget.</li>
 * </ul>
 *
 * <p><b>Order of checks, and why.</b> Rate first, because it is the only check that bounds what a
 * hostile client can make the server do. Then target resolution and validity, then dimension, then
 * reach — the cheap "is this a coherent request at all" group. Then the interaction token, which is
 * the check a forged packet actually fails. Then the verb's own {@code authorize}, last, so that a
 * verb author's code never runs against a target that is gone, in another dimension, or out of
 * reach.
 *
 * @param <P> the payload this verb reads
 * @param <T> what it acts on
 */
public abstract class ServerboundVerb<P extends ServerboundPayload, T extends VerbTarget> {

    /**
     * Slop added to the sender's own interaction range before the reach test, matching the 1.0 that
     * vanilla's {@code ServerGamePacketListenerImpl.handleInteract} allows. Using vanilla's own
     * number means this gate can never reject a distance the vanilla interact packet would have
     * accepted, so a legitimate client is never punished for latency we invented.
     */
    public static final double REACH_SLOP = 1.0;

    private final CustomPacketPayload.Type<P> type;
    private final StreamCodec<? super RegistryFriendlyByteBuf, P> codec;
    private final RateLimiter.Policy rate;

    protected ServerboundVerb(CustomPacketPayload.Type<P> type,
                              StreamCodec<? super RegistryFriendlyByteBuf, P> codec,
                              RateLimiter.Policy rate) {
        this.type = Objects.requireNonNull(type, "verb payload type");
        this.codec = Objects.requireNonNull(codec, "verb payload codec");
        this.rate = Objects.requireNonNull(rate, "verb rate policy");
    }

    // --- what a verb must supply ---------------------------------------------------------------

    /**
     * Turns whatever the payload names into a target, or empty if it names nothing this verb acts
     * on. Everything the client sent is untrusted; this is where that is assumed.
     */
    protected abstract Optional<T> resolveTarget(VerbSender sender, P payload);

    /**
     * <b>The gate.</b> May this sender do this to this target?
     *
     * <p>Abstract on purpose, and re-declared by every concrete verb on purpose (see
     * {@link VerbRegistry#register}). By the time this is called the target is known to exist, to
     * be alive, to be in the sender's dimension, within reach, and covered by a live interaction
     * the server itself opened — so this method is only ever about the rules of <i>this verb</i>.
     *
     * @param sender the sending player; never null in production
     */
    protected abstract Authorization authorize(ServerPlayer sender, T target);

    /** What the verb actually does. Only reached when every check above passed. */
    protected abstract void run(ServerPlayer sender, T target, P payload);

    // --- the gate ------------------------------------------------------------------------------

    /**
     * Runs every check, then the verb. Final: this is the method a handler cannot forget, because a
     * handler cannot write it.
     *
     * @return what happened, for tests and logs. Production callers may ignore it.
     */
    public final VerbOutcome receive(VerbSender sender, VerbRuntime runtime, P payload) {
        Objects.requireNonNull(sender, "sender");
        Objects.requireNonNull(runtime, "runtime");
        Objects.requireNonNull(payload, "payload");

        long now = sender.tickCount();

        if (!runtime.rates().tryAcquire(sender.id(), id(), rate, now)) {
            return refuse(sender, runtime, VerbOutcome.RATE_LIMITED, null);
        }

        Optional<T> resolved = resolveTarget(sender, payload);
        if (resolved.isEmpty()) {
            return refuse(sender, runtime, VerbOutcome.TARGET_UNRESOLVED, null);
        }
        T target = resolved.get();

        if (!target.stillValid()) {
            return refuse(sender, runtime, VerbOutcome.TARGET_GONE, null);
        }
        if (!sender.dimension().equals(target.dimension())) {
            return refuse(sender, runtime, VerbOutcome.WRONG_DIMENSION, null);
        }
        if (!withinReach(sender, target)) {
            return refuse(sender, runtime, VerbOutcome.OUT_OF_REACH, null);
        }
        if (runtime.tokens()
                .validate(sender.id(), payload.interactionToken(), target.key(), now)
                .isEmpty()) {
            return refuse(sender, runtime, VerbOutcome.NO_LIVE_INTERACTION, null);
        }

        Authorization authorization = authorize(sender.player(), target);
        if (authorization == null) {
            // A verb that returns null has not answered. Treat silence as refusal, loudly: the
            // alternative reading is "allowed", which is exactly the default this class exists to
            // remove.
            Namesake.LOGGER.error("Verb {} returned null from authorize(); refusing. "
                    + "A verb must return allow() or deny(reason).", id());
            return refuse(sender, runtime, VerbOutcome.DENIED, null);
        }
        if (!authorization.allowed()) {
            return refuse(sender, runtime, VerbOutcome.DENIED, authorization.reason());
        }

        run(sender.player(), target, payload);
        return VerbOutcome.ACCEPTED;
    }

    /**
     * Vanilla's own reach test, reimplemented against {@link VerbSender} so the gate can be driven
     * without a running server: {@code Player.canInteractWithEntity} measures squared distance from
     * the eye position to the target's bounding box against {@code (range + slop)}.
     */
    private static boolean withinReach(VerbSender sender, VerbTarget target) {
        double limit = sender.interactionRange() + REACH_SLOP;
        return target.bounds().distanceToSqr(sender.eyePosition()) < limit * limit;
    }

    private VerbOutcome refuse(VerbSender sender, VerbRuntime runtime, VerbOutcome outcome,
                               String reason) {
        if (runtime.logRefusals()) {
            Namesake.LOGGER.info("Verb {} refused for {}: {}{}",
                    id(), sender.name(), outcome, reason == null ? "" : " (" + reason + ")");
        } else {
            Namesake.LOGGER.debug("Verb {} refused for {}: {}", id(), sender.name(), outcome);
        }
        return outcome;
    }

    // --- identity ------------------------------------------------------------------------------

    public final ResourceLocation id() {
        return type.id();
    }

    public final CustomPacketPayload.Type<P> type() {
        return type;
    }

    public final StreamCodec<? super RegistryFriendlyByteBuf, P> codec() {
        return codec;
    }

    public final RateLimiter.Policy rate() {
        return rate;
    }

    @Override
    public final String toString() {
        return getClass().getSimpleName() + "[" + id() + "]";
    }
}
