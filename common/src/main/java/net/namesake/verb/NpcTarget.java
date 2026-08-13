package net.namesake.verb;

import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.namesake.npc.PersonaService;
import net.namesake.platform.PersonaLink;

import java.util.Optional;
import java.util.UUID;

/**
 * A persona-carrying entity, as targeted by a packet that named its entity id.
 *
 * <p>Resolution goes entity id → entity → persona id, and every step can fail on a forged packet:
 * the id may name nothing, may name a pig, or may name a villager the client can see but that
 * carries no persona. All three come back empty, and the gate refuses.
 */
public record NpcTarget(UUID personaId, Entity entity) implements VerbTarget {

    /**
     * @param entityId the network entity id the client sent, which is entirely under its control
     */
    public static Optional<NpcTarget> resolve(ServerLevel level, int entityId) {
        Entity entity = level.getEntity(entityId);
        if (entity == null || !PersonaService.isPersonaCarrier(entity)) {
            return Optional.empty();
        }
        return PersonaLink.get().personaId(entity).map(id -> new NpcTarget(id, entity));
    }

    @Override
    public UUID key() {
        return personaId;
    }

    @Override
    public ResourceKey<Level> dimension() {
        return entity.level().dimension();
    }

    @Override
    public AABB bounds() {
        return entity.getBoundingBox();
    }

    @Override
    public boolean stillValid() {
        return !entity.isRemoved() && entity.isAlive() && PersonaService.isPersonaCarrier(entity);
    }

    @Override
    public String describe() {
        return "persona " + personaId + " (entity " + entity.getUUID() + ")";
    }
}
