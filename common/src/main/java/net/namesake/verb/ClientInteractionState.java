package net.namesake.verb;

import java.util.OptionalLong;

/**
 * The client's copy of the one interaction the server has open for it.
 *
 * <p>Holds no authority whatsoever. If this is stale, wrong, or forged, the packet it produces is
 * refused by the gate — the server is the only thing that knows which interactions are live. It
 * exists so the client knows whether it has anything worth sending.
 *
 * <p>Deliberately free of any {@code net.minecraft.client} type, so it can be reached from the
 * shared payload handler on NeoForge, which is registered on the dedicated server too and only
 * never runs there.
 */
public final class ClientInteractionState {

    private static volatile InteractionOpenedPayload current;

    private ClientInteractionState() {
    }

    public static void onOpened(InteractionOpenedPayload payload) {
        current = payload;
    }

    /** The token for that entity, if the server has told us about an interaction with it. */
    public static OptionalLong tokenFor(int entityId) {
        InteractionOpenedPayload open = current;
        return open != null && open.targetEntityId() == entityId
                ? OptionalLong.of(open.token())
                : OptionalLong.empty();
    }

    public static void clear() {
        current = null;
    }
}
