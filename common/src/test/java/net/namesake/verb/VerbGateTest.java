package net.namesake.verb;

import net.minecraft.core.registries.Registries;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The gate itself, driven through the <b>real</b> {@link ServerboundVerb#receive} rather than a
 * copy of it.
 *
 * <p>That distinction is the whole value of the file. Every check could be unit tested in isolation
 * and every one of them pass while the pipeline calls them in the wrong order, or skips one, or
 * runs the verb's payload anyway. What has to be proven is the composition: which checks run, in
 * what order, and what does <i>not</i> happen when one of them refuses.
 *
 * <p>A {@link ServerPlayer} cannot be built without a running server, which is why
 * {@link VerbSender} exists — see its javadoc.
 */
class VerbGateTest {

    // Built here rather than read off Level.OVERWORLD so that touching this file never runs
    // Level's static initialiser. Same values; see Level:81-82.
    private static final ResourceKey<Level> OVERWORLD = ResourceKey.create(Registries.DIMENSION,
            ResourceLocation.withDefaultNamespace("overworld"));
    private static final ResourceKey<Level> NETHER = ResourceKey.create(Registries.DIMENSION,
            ResourceLocation.withDefaultNamespace("the_nether"));

    private static final UUID PLAYER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID TARGET = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID OTHER_TARGET = UUID.fromString("33333333-3333-3333-3333-333333333333");

    private VerbRuntime runtime;
    private CountingVerb verb;

    @BeforeEach
    void setUp() {
        runtime = new VerbRuntime(false);
        verb = new CountingVerb();
    }

    // --- the happy path, so every refusal below means something ---------------------------------

    @Test
    @DisplayName("a packet inside a live interaction is accepted and the verb runs")
    void acceptsAPacketBackedByALiveInteraction() {
        long token = openInteraction(TARGET, 100L);

        assertEquals(VerbOutcome.ACCEPTED, receive(sender(100L), payload(token)));
        assertEquals(1, verb.authorizeCalls);
        assertEquals(1, verb.runCalls);
    }

    // --- the session token: the check MCA has no equivalent of ----------------------------------

    @Test
    @DisplayName("a packet for an interaction that was never opened is refused")
    void refusesAPacketWithNoInteractionBehindIt() {
        // The forged-packet case, exactly: standing next to the villager, in reach, with a target
        // that resolves — and a screen that was never opened.
        assertEquals(VerbOutcome.NO_LIVE_INTERACTION, receive(sender(100L), payload(0xC0FFEEL)));
        assertEquals(0, verb.runCalls);
    }

    @Test
    @DisplayName("a token issued for one villager does not work on the one beside it")
    void refusesATokenIssuedForADifferentTarget() {
        long token = openInteraction(OTHER_TARGET, 100L);

        assertEquals(VerbOutcome.NO_LIVE_INTERACTION, receive(sender(100L), payload(token)));
        assertEquals(0, verb.runCalls);
    }

    @Test
    @DisplayName("a token belonging to another player is refused")
    void refusesAnotherPlayersToken() {
        UUID otherPlayer = UUID.fromString("44444444-4444-4444-4444-444444444444");
        long token = runtime.tokens().open(otherPlayer, TARGET, OVERWORLD, 100L).interaction().token();

        assertEquals(VerbOutcome.NO_LIVE_INTERACTION, receive(sender(100L), payload(token)));
    }

    @Test
    @DisplayName("a token expires, and using it before it does keeps it alive")
    void tokensExpireUnlessUsed() {
        long token = openInteraction(TARGET, 100L);
        long justBeforeExpiry = 100L + InteractionTokens.TTL_TICKS;

        // Using it refreshes it, so the deadline moves rather than the interaction dying under a
        // player who is in the middle of using it.
        assertEquals(VerbOutcome.ACCEPTED, receive(sender(justBeforeExpiry), payload(token)));
        assertEquals(VerbOutcome.ACCEPTED,
                receive(sender(justBeforeExpiry + InteractionTokens.TTL_TICKS), payload(token)));

        long wellPast = justBeforeExpiry + (2 * InteractionTokens.TTL_TICKS) + 1;
        assertEquals(VerbOutcome.NO_LIVE_INTERACTION, receive(sender(wellPast), payload(token)));
    }

    @Test
    @DisplayName("opening a conversation with a second villager retires the first token")
    void onlyOneInteractionIsLivePerPlayer() {
        long first = openInteraction(TARGET, 100L);
        openInteraction(OTHER_TARGET, 110L);

        assertEquals(VerbOutcome.NO_LIVE_INTERACTION, receive(sender(120L), payload(first)));
    }

    @Test
    @DisplayName("re-opening the same conversation keeps the token the client already holds")
    void reopeningTheSameInteractionRefreshesRatherThanReminting() {
        // The gesture that opens a conversation is the gesture that uses it, so re-minting on the
        // second click would invalidate the token the client sends in the same tick.
        long first = openInteraction(TARGET, 100L);
        InteractionTokens.Opened again = runtime.tokens().open(PLAYER, TARGET, OVERWORLD, 140L);

        assertEquals(first, again.interaction().token());
        assertFalse(again.isNew(), "a refresh must not be announced to the client as a new token");
        assertEquals(VerbOutcome.ACCEPTED, receive(sender(140L), payload(first)));
    }

    @Test
    @DisplayName("zero is never a live token")
    void zeroIsNotAToken() {
        openInteraction(TARGET, 100L);
        assertEquals(VerbOutcome.NO_LIVE_INTERACTION, receive(sender(100L), payload(0L)));
    }

    // --- reach, dimension, target validity ------------------------------------------------------

    @Test
    @DisplayName("a target out of reach is refused even with a valid token")
    void refusesATargetOutOfReach() {
        long token = openInteraction(TARGET, 100L);
        verb.targetPosition = new Vec3(0, 0, 40);

        assertEquals(VerbOutcome.OUT_OF_REACH, receive(sender(100L), payload(token)));
        assertEquals(0, verb.runCalls);
    }

    @Test
    @DisplayName("reach matches vanilla's own interact tolerance, not a number we invented")
    void reachUsesTheSendersOwnRangePlusVanillaSlop() {
        long token = openInteraction(TARGET, 100L);
        double limit = FakeSender.RANGE + ServerboundVerb.REACH_SLOP;

        // The target is a point box, so distance to the box is distance to the point.
        verb.targetPosition = new Vec3(0, 0, limit - 0.01);
        assertEquals(VerbOutcome.ACCEPTED, receive(sender(100L), payload(token)));

        verb.targetPosition = new Vec3(0, 0, limit + 0.01);
        assertEquals(VerbOutcome.OUT_OF_REACH, receive(sender(100L), payload(token)));
    }

    @Test
    @DisplayName("a target in another dimension is refused")
    void refusesATargetInAnotherDimension() {
        long token = openInteraction(TARGET, 100L);
        verb.targetDimension = NETHER;

        assertEquals(VerbOutcome.WRONG_DIMENSION, receive(sender(100L), payload(token)));
    }

    @Test
    @DisplayName("a target that resolves but has died is refused")
    void refusesATargetThatIsGone() {
        long token = openInteraction(TARGET, 100L);
        verb.targetValid = false;

        assertEquals(VerbOutcome.TARGET_GONE, receive(sender(100L), payload(token)));
    }

    @Test
    @DisplayName("a payload naming something that is not a target at all is refused")
    void refusesAnUnresolvableTarget() {
        openInteraction(TARGET, 100L);
        verb.resolves = false;

        assertEquals(VerbOutcome.TARGET_UNRESOLVED, receive(sender(100L), payload(1L)));
    }

    // --- rate ----------------------------------------------------------------------------------

    @Test
    @DisplayName("a burst beyond the policy is refused, and the bucket refills over time")
    void refusesAPacketFloodAndRecovers() {
        long token = openInteraction(TARGET, 100L);

        for (int i = 0; i < CountingVerb.BURST; i++) {
            assertEquals(VerbOutcome.ACCEPTED, receive(sender(100L), payload(token)),
                    "use " + i + " is inside the burst");
        }
        assertEquals(VerbOutcome.RATE_LIMITED, receive(sender(100L), payload(token)));

        // A full window later the bucket is full again.
        assertEquals(VerbOutcome.ACCEPTED,
                receive(sender(100L + CountingVerb.WINDOW), payload(token)));
    }

    @Test
    @DisplayName("the rate check runs before anything the packet could make the server do")
    void rateLimitingIsTheFirstCheck() {
        long token = openInteraction(TARGET, 100L);
        for (int i = 0; i < CountingVerb.BURST; i++) {
            receive(sender(100L), payload(token));
        }
        int resolvesBefore = verb.resolveCalls;

        assertEquals(VerbOutcome.RATE_LIMITED, receive(sender(100L), payload(token)));
        assertEquals(resolvesBefore, verb.resolveCalls,
                "a rate-limited packet must not even be resolved to a target; that is the work the "
                        + "rate limit exists to bound");
    }

    @Test
    @DisplayName("one player's flood does not rate-limit another")
    void rateBucketsArePerSender() {
        long token = openInteraction(TARGET, 100L);
        for (int i = 0; i < CountingVerb.BURST; i++) {
            receive(sender(100L), payload(token));
        }
        assertEquals(VerbOutcome.RATE_LIMITED, receive(sender(100L), payload(token)));

        UUID second = UUID.fromString("55555555-5555-5555-5555-555555555555");
        long secondToken = runtime.tokens().open(second, TARGET, OVERWORLD, 100L).interaction().token();
        FakeSender other = new FakeSender(second, 100L);

        assertEquals(VerbOutcome.ACCEPTED, verb.receive(other, runtime, payload(secondToken)));
    }

    // --- the verb's own gate --------------------------------------------------------------------

    @Test
    @DisplayName("a denial stops the verb running")
    void deniedVerbsDoNotRun() {
        long token = openInteraction(TARGET, 100L);
        verb.answer = Authorization.deny("not today");

        assertEquals(VerbOutcome.DENIED, receive(sender(100L), payload(token)));
        assertEquals(1, verb.authorizeCalls);
        assertEquals(0, verb.runCalls);
    }

    @Test
    @DisplayName("a verb that answers null is refused rather than allowed")
    void aSilentVerbIsARefusal() {
        long token = openInteraction(TARGET, 100L);
        verb.answer = null;

        assertEquals(VerbOutcome.DENIED, receive(sender(100L), payload(token)));
        assertEquals(0, verb.runCalls);
    }

    @Test
    @DisplayName("authorize is never consulted about a target that failed an earlier check")
    void authorizeRunsLast() {
        long token = openInteraction(TARGET, 100L);
        // A window apart each time, so a refusal here is the check under test and never the rate
        // limiter standing in for it.
        long tick = 100L;

        verb.targetValid = false;
        assertEquals(VerbOutcome.TARGET_GONE, receive(sender(tick), payload(token)));
        verb.targetValid = true;

        verb.targetDimension = NETHER;
        assertEquals(VerbOutcome.WRONG_DIMENSION,
                receive(sender(tick += CountingVerb.WINDOW), payload(token)));
        verb.targetDimension = OVERWORLD;

        verb.targetPosition = new Vec3(0, 0, 40);
        assertEquals(VerbOutcome.OUT_OF_REACH,
                receive(sender(tick += CountingVerb.WINDOW), payload(token)));
        verb.targetPosition = Vec3.ZERO;

        assertEquals(VerbOutcome.NO_LIVE_INTERACTION,
                receive(sender(tick += CountingVerb.WINDOW), payload(token + 1)));

        assertEquals(0, verb.authorizeCalls,
                "a verb's own rules must never run against a target that is gone, in another "
                        + "dimension, out of reach, or outside any interaction");

        assertEquals(VerbOutcome.ACCEPTED,
                receive(sender(tick + CountingVerb.WINDOW), payload(token)));
        assertEquals(1, verb.authorizeCalls);
    }

    @Test
    @DisplayName("a denial carries a reason and cannot be built without one")
    void denialsMustSayWhy() {
        assertTrue(Authorization.deny("because").reason().contains("because"));
        assertThrows(IllegalArgumentException.class, () -> Authorization.deny("  "));
        assertNotEquals(Authorization.allow(), Authorization.deny("no"));
    }

    // --- helpers --------------------------------------------------------------------------------

    private long openInteraction(UUID target, long now) {
        return runtime.tokens().open(PLAYER, target, OVERWORLD, now).interaction().token();
    }

    private VerbOutcome receive(FakeSender sender, FakePayload payload) {
        return verb.receive(sender, runtime, payload);
    }

    private static FakeSender sender(long tick) {
        return new FakeSender(PLAYER, tick);
    }

    private static FakePayload payload(long token) {
        return new FakePayload(token);
    }

    /** A sender at the origin with vanilla's default 3.0-block entity interaction range. */
    private record FakeSender(UUID id, long tickCount) implements VerbSender {
        static final double RANGE = 3.0;

        @Override
        public String name() {
            return "fake";
        }

        @Override
        public ResourceKey<Level> dimension() {
            return OVERWORLD;
        }

        @Override
        public Vec3 eyePosition() {
            return Vec3.ZERO;
        }

        @Override
        public double interactionRange() {
            return RANGE;
        }

        /** Null on purpose: if the gate ever dereferences this, these tests fail loudly. */
        @Override
        public ServerPlayer player() {
            return null;
        }
    }

    private record FakePayload(long interactionToken) implements ServerboundPayload {
        static final CustomPacketPayload.Type<FakePayload> TYPE = new CustomPacketPayload.Type<>(
                ResourceLocation.fromNamespaceAndPath("namesake", "test_payload"));

        @Override
        public CustomPacketPayload.Type<FakePayload> type() {
            return TYPE;
        }
    }

    private static final class FakeTarget implements VerbTarget {
        private final VerbGateTest.CountingVerb verb;

        FakeTarget(VerbGateTest.CountingVerb verb) {
            this.verb = verb;
        }

        @Override
        public UUID key() {
            return TARGET;
        }

        @Override
        public ResourceKey<Level> dimension() {
            return verb.targetDimension;
        }

        @Override
        public AABB bounds() {
            Vec3 at = verb.targetPosition;
            return new AABB(at, at);
        }

        @Override
        public boolean stillValid() {
            return verb.targetValid;
        }

        @Override
        public String describe() {
            return "fake target";
        }
    }

    /**
     * A verb that records what the gate asked it, and can be made to fail any single check.
     *
     * <p>Its {@code authorize} reads {@code target} and not {@code sender} because
     * {@link FakeSender#player()} is null — see {@link VerbSender#player()}.
     */
    private static final class CountingVerb extends ServerboundVerb<FakePayload, FakeTarget> {

        static final int BURST = 3;
        static final int WINDOW = 40;

        boolean resolves = true;
        boolean targetValid = true;
        ResourceKey<Level> targetDimension = OVERWORLD;
        Vec3 targetPosition = Vec3.ZERO;
        Authorization answer = Authorization.allow();

        int resolveCalls;
        int authorizeCalls;
        int runCalls;

        CountingVerb() {
            super(FakePayload.TYPE,
                    StreamCodec.composite(ByteBufCodecs.VAR_LONG, FakePayload::interactionToken,
                            FakePayload::new),
                    new RateLimiter.Policy(BURST, WINDOW));
        }

        @Override
        protected Optional<FakeTarget> resolveTarget(VerbSender sender, FakePayload payload) {
            resolveCalls++;
            return resolves ? Optional.of(new FakeTarget(this)) : Optional.empty();
        }

        @Override
        protected Authorization authorize(ServerPlayer sender, FakeTarget target) {
            authorizeCalls++;
            return target.stillValid() ? answer : Authorization.deny("gone");
        }

        @Override
        protected void run(ServerPlayer sender, FakeTarget target, FakePayload payload) {
            runCalls++;
        }
    }
}
