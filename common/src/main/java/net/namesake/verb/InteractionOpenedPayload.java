package net.namesake.verb;

import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.namesake.Namesake;

/**
 * "You are now in a conversation with that villager, and here is the token for it."
 *
 * <p>Sent once, when the server opens an interaction — never in reply to a client asking for one.
 * That asymmetry is the whole design: the client cannot mint an interaction, so it cannot mint the
 * authority to act inside one.
 */
public record InteractionOpenedPayload(long token, int targetEntityId) implements ClientboundPayload {

    public static final CustomPacketPayload.Type<InteractionOpenedPayload> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(Namesake.MOD_ID, "interaction_opened"));

    public static final StreamCodec<io.netty.buffer.ByteBuf, InteractionOpenedPayload> CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_LONG, InteractionOpenedPayload::token,
                    ByteBufCodecs.VAR_INT, InteractionOpenedPayload::targetEntityId,
                    InteractionOpenedPayload::new);

    @Override
    public CustomPacketPayload.Type<InteractionOpenedPayload> type() {
        return TYPE;
    }
}
