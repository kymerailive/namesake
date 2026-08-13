package net.namesake.verb;

import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.namesake.Namesake;

/**
 * "I am talking to that villager."
 *
 * <p>Two fields, both entirely under the client's control, and that is the point: the entity id may
 * name anything at all, and the token may be one the server never issued. This is the smallest
 * payload that still makes every check in the gate a real condition rather than decoration.
 */
public record GreetPayload(long interactionToken, int targetEntityId) implements ServerboundPayload {

    public static final CustomPacketPayload.Type<GreetPayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(Namesake.MOD_ID, "greet"));

    public static final StreamCodec<io.netty.buffer.ByteBuf, GreetPayload> CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_LONG, GreetPayload::interactionToken,
                    ByteBufCodecs.VAR_INT, GreetPayload::targetEntityId,
                    GreetPayload::new);

    @Override
    public CustomPacketPayload.Type<GreetPayload> type() {
        return TYPE;
    }
}
