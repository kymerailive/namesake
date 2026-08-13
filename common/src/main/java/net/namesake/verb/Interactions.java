package net.namesake.verb;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.namesake.npc.PersonaService;
import net.namesake.platform.PersonaLink;
import net.namesake.platform.VerbTransport;

import java.util.Optional;
import java.util.UUID;

/**
 * The gesture that opens a conversation: <b>sneak, empty main hand, right-click a villager.</b>
 *
 * <p>Session 09 replaces this with a dialogue screen. What does not change is which side does what:
 *
 * <ul>
 *   <li><b>The server opens the interaction.</b> It does so in response to a vanilla interaction it
 *       has already reach-checked, and it mints the token. The client never asks for one, which is
 *       why {@link ServerboundPayload} can require a token with no exceptions.</li>
 *   <li><b>The client only sends verbs.</b> On the click after the conversation is open, it sends
 *       {@link GreetPayload} with the token the server gave it.</li>
 * </ul>
 *
 * <p>So the gesture reads: first click, the villager turns to you; the next, you greet them.
 *
 * <p><b>A cross-loader difference worth knowing.</b> Fabric fires its client-side interact callback
 * from {@code Minecraft.startUseItem}, <i>before</i> the vanilla interact packet is sent; NeoForge
 * fires its equivalent from {@code MultiPlayerGameMode.interact}, <i>after</i>. So the greet packet
 * overtakes the vanilla one on Fabric and trails it on NeoForge. It makes no difference here —
 * opening is idempotent and refreshes rather than re-mints, so the token is the same either way —
 * but the same asymmetry is why the vanilla interaction is cancelled on the <i>server</i>, where
 * both loaders fire at the same point, rather than on the client, where only one of them could.
 */
public final class Interactions {

    private Interactions() {
    }

    /**
     * Sneaking, empty main hand, and something that carries a persona.
     *
     * <p>Empty hand keeps every vanilla use of a villager — trading, name tags, curing with a
     * golden apple — reachable without a modifier, and sneaking keeps trading reachable at all.
     */
    public static boolean isConversationGesture(Player player, InteractionHand hand, Entity target) {
        return hand == InteractionHand.MAIN_HAND
                && player.isShiftKeyDown()
                && player.getMainHandItem().isEmpty()
                && PersonaService.isPersonaCarrier(target);
    }

    /**
     * Server side: open or refresh the interaction, and tell the client its token the once.
     *
     * <p>Called only for {@link #isConversationGesture}, and only after vanilla has already
     * validated that the player could interact with this entity at all.
     */
    public static void onServerGesture(ServerPlayer player, Entity target) {
        Optional<UUID> personaId = PersonaLink.get().personaId(target);
        if (personaId.isEmpty()) {
            return;
        }
        long now = player.serverLevel().getServer().getTickCount();
        InteractionTokens.Opened opened = VerbNetwork.runtime().tokens()
                .open(player.getUUID(), personaId.get(), player.level().dimension(), now);

        if (opened.isNew()) {
            VerbTransport.get().sendTo(player,
                    new InteractionOpenedPayload(opened.interaction().token(), target.getId()));
        }
    }

    /** Client side: if the server has opened a conversation with this entity, greet it. */
    public static void onClientGesture(Entity target) {
        ClientInteractionState.tokenFor(target.getId())
                .ifPresent(token -> ClientPacketSink.send(new GreetPayload(token, target.getId())));
    }
}
