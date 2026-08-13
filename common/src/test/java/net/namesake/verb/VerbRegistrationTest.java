package net.namesake.verb;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.namesake.Namesake;
import net.namesake.platform.VerbTransport;
import net.namesake.testing.MethodBody;
import net.namesake.testing.ModClasses;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Type;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>Hard rule 6, enforced.</b> Every serverbound packet carries its own authorization.
 *
 * <p>MCA has 29 serverbound packets and essentially no authorization on any of them. Four things
 * stop that happening here, and this file exercises the ones a compiler cannot:
 *
 * <ol>
 *   <li><b>The compiler.</b> {@code ServerboundVerb.authorize} is abstract, so a verb that answers
 *       nothing does not build. Nothing to test — it is not expressible.</li>
 *   <li><b>Registration.</b> {@link VerbRegistry#register} refuses a verb whose own class does not
 *       declare {@code authorize}, so a subclass cannot inherit a permissive one. Tested here, and
 *       it protects addons that will never be in this repository.</li>
 *   <li><b>This test.</b> Every payload the mod compiles must be classified: serverbound and owned
 *       by a verb, or clientbound. An unguarded handler in {@code :common} means an unclassified
 *       payload, and that turns the build red.</li>
 *   <li><b>A Gradle guard</b> in each loader module, for the far side of the seam, which no test in
 *       {@code :common} can see.</li>
 * </ol>
 */
class VerbRegistrationTest {

    @BeforeEach
    @AfterEach
    void isolateTheRegistry() {
        // Registration is process-wide. A test that registers a deliberately broken verb must not
        // leave it behind, and must not care whether another test ran first.
        VerbRegistry.resetForTesting();
    }

    // --- the registration gate ------------------------------------------------------------------

    @Test
    @DisplayName("every verb this mod ships registers, so the gate is not passing by being unused")
    void shippedVerbsRegister() {
        for (ServerboundVerb<?, ?> verb : VerbCatalog.all()) {
            VerbRegistry.register(verb);
        }
        assertEquals(VerbCatalog.all().size(), VerbRegistry.verbs().size());
    }

    @Test
    @DisplayName("a verb that inherits its authorize instead of declaring one is refused")
    void refusesAVerbThatInheritsItsAuthorize() {
        // The addon case, and the one the compiler cannot catch. PoliteVerb answers properly;
        // RudeVerb extends it, changes only what it does, and quietly keeps somebody else's answer
        // to who may do it.
        VerbRegistry.register(new PoliteVerb());

        IllegalStateException refusal = assertThrows(IllegalStateException.class,
                () -> VerbRegistry.register(new RudeVerb()));

        assertTrue(refusal.getMessage().contains("does not declare its own authorize"),
                () -> "unhelpful refusal: " + refusal.getMessage());
        assertTrue(refusal.getMessage().contains("hard rule 6"),
                "the refusal must point at the rule it enforces");
    }

    @Test
    @DisplayName("two verbs cannot claim the same id")
    void refusesADuplicateId() {
        VerbRegistry.register(new PoliteVerb());
        assertThrows(IllegalStateException.class, () -> VerbRegistry.register(new PoliteVerb()));
    }

    @Test
    @DisplayName("a verb cannot be added after the network is wired")
    void refusesLateRegistration() {
        VerbRegistry.install(new RecordingTransport(), () -> new VerbRuntime(false));
        assertThrows(IllegalStateException.class, () -> VerbRegistry.register(new PoliteVerb()));
    }

    @Test
    @DisplayName("installing hands the loader exactly the verbs the registry holds")
    void installWiresExactlyTheRegisteredVerbs() {
        for (ServerboundVerb<?, ?> verb : VerbCatalog.all()) {
            VerbRegistry.register(verb);
        }
        RecordingTransport transport = new RecordingTransport();
        VerbRegistry.install(transport, () -> new VerbRuntime(false));

        assertEquals(
                VerbCatalog.all().stream().map(ServerboundVerb::id).collect(Collectors.toSet()),
                transport.serverbound);
    }

    // --- every authorize is a real gate ---------------------------------------------------------

    @Test
    @DisplayName("no verb's authorize ignores both the sender and the target")
    void everyAuthorizeReadsItsInputs() {
        for (ServerboundVerb<?, ?> verb : VerbCatalog.all()) {
            assertTrue(authorizeReadsItsInputs(verb.getClass()),
                    () -> verb.getClass().getName() + ".authorize reads neither its sender nor its "
                            + "target. A method that returns the same answer whatever it is asked "
                            + "is a comment, not a gate.");
        }
    }

    @Test
    @DisplayName("an authorize that ignores its inputs is caught, bridge method and all")
    void anIndifferentAuthorizeIsCaught() {
        // The check above is only worth having if it can fail, and there is a specific way it could
        // silently pass: a verb whose target type is a subtype of VerbTarget also gets a
        // compiler-generated bridge, whose entire body is "load every parameter and delegate". Read
        // the bridge instead of the real method and every verb reads its inputs for free.
        assertTrue(hasBridgeAuthorize(IndifferentVerb.class),
                "fixture is meant to have a bridge; without one this test proves nothing");
        assertFalse(authorizeReadsItsInputs(IndifferentVerb.class),
                "a constant-answer authorize must be caught even though its bridge loads both "
                        + "parameters");

        // And it is not simply that everything fails: the same fixture with a real answer passes.
        assertTrue(hasBridgeAuthorize(PoliteVerb.class));
        assertTrue(authorizeReadsItsInputs(PoliteVerb.class));
    }

    // --- every payload is classified ------------------------------------------------------------

    @Test
    @DisplayName("every payload the mod compiles is either a registered verb's or clientbound")
    void everyPayloadIsClassified() {
        Set<ResourceLocation> verbIds = VerbCatalog.all().stream()
                .map(ServerboundVerb::id).collect(Collectors.toSet());

        List<String> unclassified = new ArrayList<>();
        List<String> unowned = new ArrayList<>();
        int payloads = 0;

        for (Class<?> candidate : ModClasses.all()) {
            if (candidate.isInterface() || !CustomPacketPayload.class.isAssignableFrom(candidate)) {
                continue;
            }
            payloads++;

            boolean serverbound = ServerboundPayload.class.isAssignableFrom(candidate);
            boolean clientbound = ClientboundPayload.class.isAssignableFrom(candidate);

            if (serverbound == clientbound) {
                unclassified.add(candidate.getName()
                        + (serverbound ? " (both directions)" : " (neither direction)"));
            } else if (serverbound && !verbIds.contains(payloadId(candidate))) {
                unowned.add(candidate.getName());
            }
        }

        assertTrue(payloads > 0, "the scan found no payloads at all, so it proves nothing");
        assertTrue(unclassified.isEmpty(), () -> """
                These payloads are not classified:
                  %s
                Every packet in this mod travels in one declared direction. Implement \
                ServerboundPayload (and add a verb for it to VerbCatalog) or ClientboundPayload. \
                CLAUDE.md hard rule 6.""".formatted(String.join("\n  ", unclassified)));
        assertTrue(unowned.isEmpty(), () -> """
                These serverbound payloads have no verb, so nothing gates them:
                  %s
                Add a ServerboundVerb for each to VerbCatalog. CLAUDE.md hard rule 6.""".formatted(
                String.join("\n  ", unowned)));
    }

    @Test
    @DisplayName("the payload scan actually sees this mod's payloads")
    void theScanFindsTheKnownPayloads() {
        // Without this, everyPayloadIsClassified would still pass if the scan silently found
        // nothing — a green build proving the absence of a scan rather than the absence of a hole.
        Set<Class<?>> found = ModClasses.all().stream()
                .filter(CustomPacketPayload.class::isAssignableFrom)
                .filter(type -> !type.isInterface())
                .collect(Collectors.toCollection(HashSet::new));

        assertTrue(found.contains(GreetPayload.class), "scan missed GreetPayload");
        assertTrue(found.contains(InteractionOpenedPayload.class),
                "scan missed InteractionOpenedPayload");
    }

    // --- helpers ---------------------------------------------------------------------------------

    private static boolean authorizeReadsItsInputs(Class<?> verb) {
        Method authorize = declaredAuthorize(verb);
        return MethodBody.of(verb, "authorize")
                .readsAnyParameter(Type.getMethodDescriptor(authorize),
                        Modifier.isStatic(authorize.getModifiers()));
    }

    private static boolean hasBridgeAuthorize(Class<?> verb) {
        for (Method method : verb.getDeclaredMethods()) {
            if ("authorize".equals(method.getName()) && (method.isBridge() || method.isSynthetic())) {
                return true;
            }
        }
        return false;
    }

    private static Method declaredAuthorize(Class<?> verb) {
        for (Method method : verb.getDeclaredMethods()) {
            if ("authorize".equals(method.getName()) && !method.isBridge() && !method.isSynthetic()) {
                return method;
            }
        }
        throw new AssertionError(verb.getName() + " declares no authorize; VerbRegistry would have "
                + "refused it, so this should be unreachable.");
    }

    private static ResourceLocation payloadId(Class<?> payload) {
        try {
            return ((CustomPacketPayload.Type<?>) payload.getField("TYPE").get(null)).id();
        } catch (ReflectiveOperationException | ClassCastException e) {
            throw new AssertionError(payload.getName() + " must expose its "
                    + "CustomPacketPayload.Type as a public static field named TYPE, so the scan "
                    + "can tell which verb owns it.", e);
        }
    }

    // --- fixtures --------------------------------------------------------------------------------

    private record TestPayload(long interactionToken) implements ServerboundPayload {
        static final CustomPacketPayload.Type<TestPayload> TYPE = new CustomPacketPayload.Type<>(
                ResourceLocation.fromNamespaceAndPath(Namesake.MOD_ID, "registration_test"));

        static final StreamCodec<io.netty.buffer.ByteBuf, TestPayload> CODEC =
                StreamCodec.composite(ByteBufCodecs.VAR_LONG, TestPayload::interactionToken,
                        TestPayload::new);

        @Override
        public CustomPacketPayload.Type<TestPayload> type() {
            return TYPE;
        }
    }

    /**
     * A target type that is a <i>subtype</i> of {@link VerbTarget}, which is what makes javac emit
     * the bridge method these fixtures are about.
     */
    private record TestTarget(UUID key) implements VerbTarget {
        @Override
        public ResourceKey<Level> dimension() {
            return null;
        }

        @Override
        public AABB bounds() {
            return null;
        }

        @Override
        public boolean stillValid() {
            return true;
        }

        @Override
        public String describe() {
            return "test target";
        }
    }

    /** Well-behaved: declares its own answer, and the answer depends on what it was asked. */
    private static class PoliteVerb extends ServerboundVerb<TestPayload, TestTarget> {

        PoliteVerb() {
            super(TestPayload.TYPE, TestPayload.CODEC, new RateLimiter.Policy(1, 20));
        }

        @Override
        protected Optional<TestTarget> resolveTarget(VerbSender sender, TestPayload payload) {
            return Optional.empty();
        }

        @Override
        protected Authorization authorize(ServerPlayer sender, TestTarget target) {
            return target.stillValid() ? Authorization.allow() : Authorization.deny("gone");
        }

        @Override
        protected void run(ServerPlayer sender, TestTarget target, TestPayload payload) {
        }
    }

    /**
     * The verb registration must refuse: it changes what it does and says nothing about who may do
     * it, inheriting {@link PoliteVerb}'s answer instead.
     */
    private static final class RudeVerb extends PoliteVerb {
        @Override
        protected void run(ServerPlayer sender, TestTarget target, TestPayload payload) {
            // Something a stranger should not be able to make happen.
        }
    }

    /** Declares an authorize, and it is a constant. Registration accepts it; bytecode does not. */
    private static final class IndifferentVerb extends ServerboundVerb<TestPayload, TestTarget> {

        IndifferentVerb() {
            super(TestPayload.TYPE, TestPayload.CODEC, new RateLimiter.Policy(1, 20));
        }

        @Override
        protected Optional<TestTarget> resolveTarget(VerbSender sender, TestPayload payload) {
            return Optional.empty();
        }

        @Override
        protected Authorization authorize(ServerPlayer sender, TestTarget target) {
            return Authorization.allow();
        }

        @Override
        protected void run(ServerPlayer sender, TestTarget target, TestPayload payload) {
        }
    }

    /** Records what the registry hands the loader, without a loader. */
    private static final class RecordingTransport implements VerbTransport {

        final Set<ResourceLocation> serverbound = new HashSet<>();
        final Set<ResourceLocation> clientbound = new HashSet<>();

        @Override
        public <P extends CustomPacketPayload> void registerServerbound(
                CustomPacketPayload.Type<P> type,
                StreamCodec<? super RegistryFriendlyByteBuf, P> codec,
                BiConsumer<ServerPlayer, P> handler) {
            serverbound.add(type.id());
        }

        @Override
        public <P extends CustomPacketPayload> void registerClientbound(
                CustomPacketPayload.Type<P> type,
                StreamCodec<? super RegistryFriendlyByteBuf, P> codec,
                Consumer<P> clientHandler) {
            clientbound.add(type.id());
        }

        @Override
        public void sendTo(ServerPlayer player, CustomPacketPayload payload) {
        }
    }
}
