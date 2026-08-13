package net.namesake.verb;

import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * A packet the server sends to a client. Carries no authorization: the server is the authority, and
 * a client believing something the server said is not a security question.
 *
 * <p>This marker exists so that {@code VerbGateTest} can insist every payload in the mod is
 * <i>classified</i>. A new {@link CustomPacketPayload} that is neither a {@link ServerboundPayload}
 * with a registered verb nor declared clientbound here turns the build red — which is what stops a
 * serverbound handler from being added without a gate.
 */
public interface ClientboundPayload extends CustomPacketPayload {
}
