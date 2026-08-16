package net.namesake.verb;

import net.namesake.Namesake;
import net.namesake.platform.Platform;
import net.namesake.platform.VerbTransport;

/**
 * Wires the verbs to the loader's networking, and owns the state the gate reads.
 *
 * <p>One call, from {@code Namesake.init()}, on both loaders.
 */
public final class VerbNetwork {

    private static volatile VerbRuntime runtime = new VerbRuntime(false);

    private VerbNetwork() {
    }

    public static void bootstrap() {
        runtime = new VerbRuntime(Platform.get().isDevelopmentEnvironment());

        for (ServerboundVerb<?, ?> verb : VerbCatalog.all()) {
            VerbRegistry.register(verb);
        }
        VerbTransport transport = VerbTransport.get();
        VerbRegistry.install(transport, VerbNetwork::runtime);

        transport.registerClientbound(InteractionOpenedPayload.TYPE, InteractionOpenedPayload.CODEC,
                ClientInteractionState::onOpened);
        // Session 11's Notice Board. The handler goes through a sink rather than naming the screen,
        // because NeoForge registers a clientbound handler on the dedicated server too — see
        // ClientScreenSink.
        transport.registerClientbound(NoticeBoardPayload.TYPE, NoticeBoardPayload.CODEC,
                ClientScreenSink::openNoticeBoard);
        // Session 15's renderer swap. Same shape and the same reason: a dedicated server registers
        // this handler too, so it must not name a client class. See ClientAppearanceSink.
        transport.registerClientbound(AppearancePayload.TYPE, AppearancePayload.CODEC,
                ClientAppearanceSink::accept);
    }

    public static VerbRuntime runtime() {
        return runtime;
    }

    /**
     * Drops every open interaction and rate bucket when a server shuts down.
     *
     * <p>Not housekeeping. In single player, leaving one world and opening another reuses the same
     * process, and a token issued in the first would otherwise still be live in the second — where
     * its target key means nothing.
     */
    public static void onServerStopping() {
        runtime.clear();
        ClientInteractionState.clear();
        Namesake.LOGGER.debug("Cleared open interactions and rate buckets for server shutdown");
    }
}
