package net.namesake.verb;

import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.function.Consumer;

/**
 * How shared code sends a packet to the server without naming either loader's client API.
 *
 * <p>An installed sink rather than a {@code ServiceLoader} seam on purpose: both loaders' "send to
 * server" call lives in a client-only class, and a service file shipped in one jar is read on the
 * dedicated server too. Each loader's client bootstrap installs a method reference here; a
 * dedicated server installs nothing and the sink stays inert.
 */
public final class ClientPacketSink {

    private static volatile Consumer<CustomPacketPayload> sink;

    private ClientPacketSink() {
    }

    public static void install(Consumer<CustomPacketPayload> clientSender) {
        sink = clientSender;
    }

    /** No-op when nothing is installed — a server has nowhere to send a serverbound packet. */
    public static void send(CustomPacketPayload payload) {
        Consumer<CustomPacketPayload> installed = sink;
        if (installed != null) {
            installed.accept(payload);
        }
    }
}
