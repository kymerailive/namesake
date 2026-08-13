package net.namesake.fabric;

import net.fabricmc.api.EnvType;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.namesake.platform.VerbTransport;

import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Fabric's half of the networking seam.
 *
 * <p><b>The only file in this module allowed to register a serverbound payload.</b> A Gradle guard
 * in {@code build.gradle} fails the build if any other source file in this module calls Fabric's
 * networking registration — see {@code VerbTransport} and CLAUDE.md hard rule 6.
 *
 * <p>Fabric's handler runs on the server thread ({@code ServerPlayNetworking.PlayPayloadHandler}
 * says so in as many words), so the gate and the verb both run where the world can be touched.
 */
public final class FabricVerbTransport implements VerbTransport {

    @Override
    public <P extends CustomPacketPayload> void registerServerbound(
            CustomPacketPayload.Type<P> type,
            StreamCodec<? super RegistryFriendlyByteBuf, P> codec,
            BiConsumer<ServerPlayer, P> handler) {
        PayloadTypeRegistry.playC2S().register(type, codec);
        ServerPlayNetworking.registerGlobalReceiver(type,
                (payload, context) -> handler.accept(context.player(), payload));
    }

    @Override
    public <P extends CustomPacketPayload> void registerClientbound(
            CustomPacketPayload.Type<P> type,
            StreamCodec<? super RegistryFriendlyByteBuf, P> codec,
            Consumer<P> clientHandler) {
        // The type has to be known on both sides: the server needs it to encode, the client to
        // decode. Only the client gets a receiver, and only the client may load the class that
        // registers one — ClientPlayNetworking does not exist on a dedicated server.
        PayloadTypeRegistry.playS2C().register(type, codec);
        if (FabricLoader.getInstance().getEnvironmentType() == EnvType.CLIENT) {
            FabricClientReceivers.register(type, clientHandler);
        }
    }

    @Override
    public void sendTo(ServerPlayer player, CustomPacketPayload payload) {
        ServerPlayNetworking.send(player, payload);
    }
}
