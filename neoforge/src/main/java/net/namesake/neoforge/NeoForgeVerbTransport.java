package net.namesake.neoforge;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.namesake.platform.VerbTransport;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * NeoForge's half of the networking seam.
 *
 * <p><b>The only file in this module allowed to register a payload.</b> A Gradle guard in
 * {@code build.gradle} fails the build if any other source file in this module calls NeoForge's
 * networking registration — see {@code VerbTransport} and CLAUDE.md hard rule 6.
 *
 * <p>Unlike Fabric, NeoForge only accepts registrations inside {@code RegisterPayloadHandlersEvent},
 * which fires after every mod constructor has run. {@code Namesake.init()} runs in the constructor,
 * so registrations are queued here and flushed when the event arrives. The queue is not an
 * optimisation — registering directly from the constructor throws.
 *
 * <p>Handlers run on the server thread: {@code PayloadRegistrar} defaults to
 * {@code HandlerThread.MAIN}, checked in its source rather than assumed.
 */
public final class NeoForgeVerbTransport implements VerbTransport {

    /**
     * Bumped whenever a payload's wire shape changes. NeoForge refuses a connection between two
     * NeoForge sides whose versions disagree, which turns a silent decode failure into a refused
     * login.
     */
    private static final String PROTOCOL_VERSION = "1";

    private final List<Consumer<PayloadRegistrar>> pending = new ArrayList<>();

    private boolean flushed;

    @Override
    public <P extends CustomPacketPayload> void registerServerbound(
            CustomPacketPayload.Type<P> type,
            StreamCodec<? super RegistryFriendlyByteBuf, P> codec,
            BiConsumer<ServerPlayer, P> handler) {
        queue(registrar -> registrar.playToServer(type, codec,
                (payload, context) -> handler.accept((ServerPlayer) context.player(), payload)));
    }

    @Override
    public <P extends CustomPacketPayload> void registerClientbound(
            CustomPacketPayload.Type<P> type,
            StreamCodec<? super RegistryFriendlyByteBuf, P> codec,
            Consumer<P> clientHandler) {
        queue(registrar -> registrar.playToClient(type, codec,
                (payload, context) -> clientHandler.accept(payload)));
    }

    @Override
    public void sendTo(ServerPlayer player, CustomPacketPayload payload) {
        PacketDistributor.sendToPlayer(player, payload);
    }

    void onRegisterPayloadHandlers(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(PROTOCOL_VERSION);
        for (Consumer<PayloadRegistrar> registration : pending) {
            registration.accept(registrar);
        }
        pending.clear();
        flushed = true;
    }

    private void queue(Consumer<PayloadRegistrar> registration) {
        if (flushed) {
            throw new IllegalStateException("A payload was registered after "
                    + "RegisterPayloadHandlersEvent had already fired; it would never reach the "
                    + "wire. Register verbs during mod construction.");
        }
        pending.add(registration);
    }
}
