package net.namesake.verb;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.npc.Villager;
import net.namesake.dialogue.Conversations;
import net.namesake.dialogue.Dialogue;
import net.namesake.npc.NpcRegistry;
import net.namesake.npc.Persona;
import net.namesake.social.Deed;

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

    /**
     * Long enough for any Minecraft username, and a bound rather than a hope.
     *
     * <p>Vanilla caps a name at sixteen characters, but the name reaching here has been through a
     * {@code GameProfile}, and a display name is a thing other mods change. An unbounded name inside
     * an authored line is a line nobody has measured against the space it has to sit in, which is the
     * defect this project has shipped six times.
     */
    private static final int NAME_BUDGET = 16;

    @Override
    protected void run(ServerPlayer sender, NpcTarget target, GreetPayload payload) {
        Villager villager = (Villager) target.entity();
        villager.getLookControl().setLookAt(sender, 30.0F, 30.0F);

        ServerLevel level = sender.serverLevel();
        level.playSound(null, villager.getX(), villager.getY(), villager.getZ(),
                SoundEvents.VILLAGER_AMBIENT, SoundSource.NEUTRAL, 1.0F, 1.0F);

        NpcRegistry registry = NpcRegistry.get(level);
        Persona speaker = registry.persona(target.personaId()).orElse(null);
        if (speaker == null || !speaker.isGenerated()) {
            // A minted persona whose settlement is not yet known has no culture, so it has no voice
            // to speak in — and substituting culture 0 is the quiet wrong the schema 2 → 3 fix
            // exists to make visible. It says something rather than nothing, because an accepted
            // verb with no visible effect is indistinguishable from a refused one in a playtest.
            sender.displayClientMessage(
                    Component.literal("They are still settling in. Nothing to say yet."), true);
            return;
        }

        String name = sender.getGameProfile().getName();
        Dialogue.Spoken spoken = Dialogue.speak(registry, speaker, sender.getUUID(),
                name.length() <= NAME_BUDGET ? name : name.substring(0, NAME_BUDGET),
                Conversations.turn(sender.getUUID(), target.personaId()),
                Deed.dayOf(level), level.getGameTime());

        // Chat rather than the action bar, which is where session 02's placeholder lived. The action
        // bar does not wrap and clips at both ends, and this session's whole deliverable is a
        // sentence somebody has to be able to read — including the moment it stops saying "stranger"
        // and starts saying their name.
        sender.sendSystemMessage(Component.literal(
                String.join("\n", Dialogue.rows(speaker, spoken))));
    }
}
