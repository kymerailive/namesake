package net.namesake.platform;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;

import java.util.NoSuchElementException;
import java.util.ServiceLoader;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * The loader seam for networking.
 *
 * <p>Fabric and NeoForge register custom payloads through incompatible APIs, so neither appears in
 * {@code common}. What does live in common is every decision that matters: which payloads exist,
 * which direction each travels, and — for serverbound ones — the gate they arrive through.
 *
 * <p><b>This is the only route a serverbound payload may take to a handler.</b> The implementations
 * are two thin adapters, and a Gradle guard fails the build if either loader module calls its
 * networking API to register a serverbound payload anywhere else. That guard is what stops an
 * unguarded handler being added on the far side of this seam, where a unit test in {@code common}
 * cannot see it.
 */
public interface VerbTransport {

    /**
     * Registers a payload the client may send, together with the handler that receives it.
     *
     * <p>The handler runs on the server thread on both loaders — Fabric schedules it there, and
     * NeoForge's registrar defaults to {@code HandlerThread.MAIN}. Both were read from their
     * sources rather than assumed; "it compiles on both" says nothing about "it behaves the same
     * on both".
     */
    <P extends CustomPacketPayload> void registerServerbound(
            CustomPacketPayload.Type<P> type,
            StreamCodec<? super RegistryFriendlyByteBuf, P> codec,
            BiConsumer<ServerPlayer, P> handler);

    /**
     * Registers a payload the server may send.
     *
     * @param clientHandler runs on the receiving client's main thread. Must not touch any
     *                      client-only Minecraft class: on NeoForge this is registered on the
     *                      dedicated server too, and only never runs there.
     */
    <P extends CustomPacketPayload> void registerClientbound(
            CustomPacketPayload.Type<P> type,
            StreamCodec<? super RegistryFriendlyByteBuf, P> codec,
            Consumer<P> clientHandler);

    void sendTo(ServerPlayer player, CustomPacketPayload payload);

    static VerbTransport get() {
        return Holder.INSTANCE;
    }

    final class Holder {
        static final VerbTransport INSTANCE = load();

        private Holder() {
        }

        private static VerbTransport load() {
            try {
                return ServiceLoader.load(VerbTransport.class).findFirst().orElseThrow();
            } catch (NoSuchElementException e) {
                throw new IllegalStateException(
                        "No VerbTransport implementation found. The loader module is missing its "
                                + "META-INF/services/net.namesake.platform.VerbTransport entry.", e);
            }
        }
    }
}
