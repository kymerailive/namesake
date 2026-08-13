package net.namesake.verb;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.namesake.Namesake;
import net.namesake.platform.VerbTransport;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * The only door a serverbound packet gets in through, and the second half of hard rule 6.
 *
 * <p>The first half is the compiler: {@link ServerboundVerb#authorize} is abstract, so a verb that
 * answers nothing does not build. That is not quite enough on its own. Nothing stops one verb
 * extending another and inheriting a permissive answer — and once this mod has an addon API, that
 * is exactly the shape a third-party verb would take. So registration checks, by reflection, that
 * the verb's <b>own class</b> declares {@code authorize}. Inheriting somebody else's is refused.
 *
 * <p>This is enforced at registration rather than only in a test on purpose: a test protects this
 * repository, and a check at registration protects every addon that is never in it.
 */
public final class VerbRegistry {

    private static final Map<ResourceLocation, ServerboundVerb<?, ?>> VERBS = new LinkedHashMap<>();

    private static boolean installed;

    private VerbRegistry() {
    }

    /**
     * Adds a verb.
     *
     * @throws IllegalStateException if the verb does not declare its own {@code authorize}, if its
     *                               id is already taken, or if the network has already been wired
     */
    public static void register(ServerboundVerb<?, ?> verb) {
        Objects.requireNonNull(verb, "verb");
        if (installed) {
            throw new IllegalStateException("Verb " + verb.id() + " was registered after the "
                    + "network was wired. Register verbs during mod initialisation; a verb added "
                    + "later has no payload type on the wire and would never arrive.");
        }
        requireOwnAuthorize(verb);
        ServerboundVerb<?, ?> clash = VERBS.putIfAbsent(verb.id(), verb);
        if (clash != null) {
            throw new IllegalStateException("Two verbs claim the id " + verb.id() + ": "
                    + clash.getClass().getName() + " and " + verb.getClass().getName());
        }
    }

    /**
     * The registration-time gate.
     *
     * <p>Bridge and synthetic methods are skipped: a verb declaring
     * {@code authorize(ServerPlayer, NpcTarget)} also gets a compiler-generated bridge with the
     * erased signature, and counting that as the declaration would let an inheriting subclass pass.
     */
    private static void requireOwnAuthorize(ServerboundVerb<?, ?> verb) {
        Class<?> type = verb.getClass();
        Method declared = null;
        for (Method method : type.getDeclaredMethods()) {
            if (!"authorize".equals(method.getName())
                    || method.isBridge() || method.isSynthetic()
                    || method.getParameterCount() != 2) {
                continue;
            }
            Class<?>[] parameters = method.getParameterTypes();
            if (parameters[0] != ServerPlayer.class
                    || !VerbTarget.class.isAssignableFrom(parameters[1])) {
                continue;
            }
            declared = method;
            break;
        }

        if (declared == null) {
            throw new IllegalStateException(
                    "Verb " + verb.id() + " cannot be registered: " + type.getName()
                            + " does not declare its own authorize(ServerPlayer, <target>). Every "
                            + "serverbound verb states its own authorization — inheriting another "
                            + "verb's is not enough. See CLAUDE.md hard rule 6.");
        }
        if (declared.getReturnType() != Authorization.class) {
            throw new IllegalStateException("Verb " + verb.id() + ": " + type.getName()
                    + ".authorize must return " + Authorization.class.getName() + ", not "
                    + declared.getReturnType().getName());
        }
        if (Modifier.isStatic(declared.getModifiers())) {
            throw new IllegalStateException("Verb " + verb.id() + ": " + type.getName()
                    + ".authorize must not be static; a static method cannot override the gate.");
        }
    }

    public static Collection<ServerboundVerb<?, ?>> verbs() {
        return java.util.Collections.unmodifiableCollection(VERBS.values());
    }

    public static boolean isInstalled() {
        return installed;
    }

    /**
     * Hands every registered verb to the loader's networking, wrapping each in the gate.
     *
     * <p>This is the only place a serverbound payload is ever wired to a handler. The loader
     * modules hold nothing but the translation to Fabric's and NeoForge's networking calls, and a
     * Gradle guard fails the build if either module registers a serverbound payload anywhere else.
     */
    public static void install(VerbTransport transport, Supplier<VerbRuntime> runtime) {
        Objects.requireNonNull(transport, "transport");
        Objects.requireNonNull(runtime, "runtime");
        if (installed) {
            throw new IllegalStateException("The verb network was already wired.");
        }
        for (ServerboundVerb<?, ?> verb : VERBS.values()) {
            wire(transport, verb, runtime);
        }
        installed = true;
        Namesake.LOGGER.info("Wired {} serverbound verb(s), each behind its own authorize gate: {}",
                VERBS.size(), VERBS.keySet());
    }

    /** Separate method purely to capture the wildcards on {@code verb}. */
    private static <P extends ServerboundPayload, T extends VerbTarget> void wire(
            VerbTransport transport, ServerboundVerb<P, T> verb, Supplier<VerbRuntime> runtime) {
        transport.registerServerbound(verb.type(), verb.codec(),
                (player, payload) -> verb.receive(new ServerVerbSender(player), runtime.get(), payload));
    }

    /**
     * Test-only reset. Registration is process-wide state, and a test that registers a deliberately
     * broken verb must not leave it behind for the next one.
     */
    static void resetForTesting() {
        VERBS.clear();
        installed = false;
    }
}
