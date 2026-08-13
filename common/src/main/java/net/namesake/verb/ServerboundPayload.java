package net.namesake.verb;

import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Every packet this mod accepts from a client.
 *
 * <p>The interaction token is on the interface, not on individual payloads, so that a serverbound
 * payload <b>cannot be declared without one</b>. There is no policy flag and no opt-out: the
 * question "does this packet need a token?" has one answer, and it is yes.
 *
 * <p>That is only workable because interactions are opened by the <i>server</i>, in response to a
 * vanilla interaction it already validated — never by a packet from the client. So there is no
 * bootstrap packet that would need an exemption.
 *
 * @see ClientboundPayload
 */
public interface ServerboundPayload extends CustomPacketPayload {

    /** The token the server issued when it opened the interaction this packet belongs to. */
    long interactionToken();
}
