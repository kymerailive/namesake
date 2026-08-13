package net.namesake.fabric;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.function.Consumer;

/**
 * Client-only, and separate from {@link FabricVerbTransport} for exactly one reason: touching this
 * class loads {@code ClientPlayNetworking}, which does not exist on a dedicated server. Keeping it
 * behind its own class name means the branch that never runs there also never links.
 */
final class FabricClientReceivers {

    private FabricClientReceivers() {
    }

    static <P extends CustomPacketPayload> void register(CustomPacketPayload.Type<P> type,
                                                         Consumer<P> handler) {
        ClientPlayNetworking.registerGlobalReceiver(type, (payload, context) -> handler.accept(payload));
    }
}
