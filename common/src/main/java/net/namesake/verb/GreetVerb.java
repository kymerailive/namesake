package net.namesake.verb;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.npc.Villager;

import java.util.Optional;

/**
 * The first verb: greet the villager you are talking to.
 *
 * <p>It is deliberately trivial, and deliberately not decoration. Every check in
 * {@link ServerboundVerb#receive} is a real condition here — the payload names an entity id the
 * client chose, so the target may be nothing, a pig, a villager thirty blocks away, a villager in
 * the Nether, or a villager the player never opened a conversation with. Session 09 replaces what
 * this <i>says</i>; the gate it arrives through does not change.
 */
public final class GreetVerb extends ServerboundVerb<GreetPayload, NpcTarget> {

    /**
     * Four in three seconds. A greeting is one click, and the ceiling only exists to bound what a
     * client that is not clicking can cost the server.
     */
    private static final RateLimiter.Policy RATE = new RateLimiter.Policy(4, 60);

    public GreetVerb() {
        super(GreetPayload.TYPE, GreetPayload.CODEC, RATE);
    }

    @Override
    protected Optional<NpcTarget> resolveTarget(VerbSender sender, GreetPayload payload) {
        ServerPlayer player = sender.player();
        return NpcTarget.resolve(player.serverLevel(), payload.targetEntityId());
    }

    @Override
    protected Authorization authorize(ServerPlayer sender, NpcTarget target) {
        if (sender.isSpectator()) {
            return Authorization.deny("spectators are not present enough to be spoken to");
        }
        if (!(target.entity() instanceof Villager villager)) {
            // A zombie villager still carries its persona — that is the whole attach bet — but it
            // is not a person you can talk to. Reached in game by greeting one, which makes this
            // branch the easiest way to watch the gate refuse something.
            return Authorization.deny("target carries a persona but is not a villager");
        }
        if (villager.isSleeping()) {
            return Authorization.deny("villager is asleep");
        }
        return Authorization.allow();
    }

    @Override
    protected void run(ServerPlayer sender, NpcTarget target, GreetPayload payload) {
        Villager villager = (Villager) target.entity();
        villager.getLookControl().setLookAt(sender, 30.0F, 30.0F);

        ServerLevel level = sender.serverLevel();
        level.playSound(null, villager.getX(), villager.getY(), villager.getZ(),
                SoundEvents.VILLAGER_AMBIENT, SoundSource.NEUTRAL, 1.0F, 1.0F);

        // Placeholder until session 09 owns what a villager says. It says something rather than
        // nothing on purpose: an accepted verb with no visible effect is indistinguishable from a
        // refused one during a playtest.
        sender.displayClientMessage(
                Component.literal("The villager turns to listen. (" + target.describe() + ")"),
                true);
    }
}
