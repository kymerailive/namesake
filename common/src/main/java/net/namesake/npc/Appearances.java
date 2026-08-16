package net.namesake.npc;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.namesake.platform.VerbTransport;
import net.namesake.verb.AppearancePayload;

/**
 * <b>Tells a client what a villager looks like.</b> The server half of session 15's renderer swap.
 *
 * <p>Nine bytes, once when a player starts tracking a villager, and once more if that villager's
 * persona is generated while somebody is watching. <b>Nothing per tick</b> — see {@link #announce},
 * which is called from a branch the day plan already runs and which only runs for a persona that has
 * not been generated yet.
 *
 * <p>Lives in {@code net.namesake.npc} rather than in {@code net.namesake.client} because it runs on
 * the server and names no client type; the display package is where the drawing is.
 */
public final class Appearances {

    /**
     * How far a player has to be for a re-announcement to reach them, in blocks.
     *
     * <p>Only used by {@link #announce}, which is the rare path — the ordinary one is the
     * tracking-start hook, where the loader has already decided who is watching. Vanilla's own
     * entity tracking range for a villager is 8 chunks, so this is that with room either side rather
     * than a number invented here.
     */
    private static final double ANNOUNCE_RANGE = 160.0;

    private Appearances() {
    }

    /** Sends one villager's appearance to one player. The tracking-start path. */
    public static void tell(ServerPlayer player, Entity entity) {
        if (!(entity.level() instanceof ServerLevel)) {
            return;
        }
        PersonaService.personaOf(entity).ifPresent(persona -> tell(player, entity, persona));
    }

    private static void tell(ServerPlayer player, Entity entity, Persona persona) {
        VerbTransport.get().sendTo(player, new AppearancePayload(
                entity.getId(), persona.appearanceSeed(), persona.cultureId()));
    }

    /**
     * <b>Tells everybody nearby, because this villager just became somebody.</b>
     *
     * <p>A persona is minted when a villager loads and <i>generated</i> later, once the settlement
     * survey has run — so a player standing in a brand-new village is tracking villagers whose
     * culture is {@code UNASSIGNED} at the moment the tracking-start packet went out. Without this
     * they would wear the neutral until the player walked out of range and back.
     *
     * <p><b>It costs nothing per tick</b>, and that is the whole reason it is placed where it is:
     * the day plan already refreshes a cached persona behind its own path gate, and <i>only for
     * villagers that are not yet generated</i>. So this fires on the tick a villager stops being
     * ungenerated, exactly once, from a branch that already existed and that already stops running
     * afterwards.
     */
    public static void announce(ServerLevel level, Entity entity, Persona persona) {
        if (!persona.isGenerated()) {
            return;
        }
        for (ServerPlayer player : level.players()) {
            if (player.distanceToSqr(entity) <= ANNOUNCE_RANGE * ANNOUNCE_RANGE) {
                tell(player, entity, persona);
            }
        }
    }
}
