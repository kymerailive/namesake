package net.namesake.verb;

import java.util.function.Consumer;

/**
 * The seam an {@link AppearancePayload} crosses to reach the renderer.
 *
 * <p>Sibling of {@code ClientScreenSink}, and it exists for that class's reason exactly: <b>NeoForge
 * registers a clientbound payload handler on the dedicated server too</b>, so the handler passed to
 * {@code VerbTransport.registerClientbound} must not name a {@code net.minecraft.client} type. A
 * dedicated server that resolved one would crash on a class that is not there.
 *
 * <p>Nothing installed is not an error. A dedicated server never installs one, and neither does a
 * client that has not reached its own bootstrap yet, so a payload arriving early is dropped rather
 * than thrown — the villager renders in the neutral until the next tracking start.
 */
public final class ClientAppearanceSink {

    private static volatile Consumer<AppearancePayload> receiver;
    private static volatile Runnable forget;

    private ClientAppearanceSink() {
    }

    /** Installed by each loader's client bootstrap. */
    public static void install(Consumer<AppearancePayload> clientReceiver, Runnable clientForget) {
        receiver = clientReceiver;
        forget = clientForget;
    }

    /**
     * Drops whatever the client is holding, when a server stops.
     *
     * <p>Through the sink rather than by naming the store directly, and it is not tidiness: the
     * appearance store reaches {@code NativeImage} to read a colormap, and a dedicated server
     * calling it by name would resolve a client-only class on a machine that has none. That is the
     * failure {@code ClientScreenSink} exists for, arriving at a second surface.
     *
     * <p>A no-op on a dedicated server, because nothing installed one.
     */
    public static void forget() {
        Runnable installed = forget;
        if (installed != null) {
            installed.run();
        }
    }

    /** Called on the client's network thread by the transport. */
    public static void accept(AppearancePayload payload) {
        Consumer<AppearancePayload> installed = receiver;
        if (installed != null) {
            installed.accept(payload);
        }
    }
}
