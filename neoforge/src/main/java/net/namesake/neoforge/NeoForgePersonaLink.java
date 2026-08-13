package net.namesake.neoforge;

import net.minecraft.world.entity.Entity;
import net.namesake.platform.PersonaLink;

import java.util.Optional;
import java.util.UUID;

/**
 * NeoForge side of the persona attachment. {@code .serialize(Codec)} on the attachment type is what
 * writes the 16 bytes into the entity's NBT.
 */
public final class NeoForgePersonaLink implements PersonaLink {

    @Override
    public Optional<UUID> personaId(Entity entity) {
        // Never getData(): with a null default that would insert null into the holder's map and
        // make hasData() start returning true for entities that have no persona.
        return Optional.ofNullable(entity.getExistingDataOrNull(NeoForgeAttachments.PERSONA_ID.get()));
    }

    @Override
    public void setPersonaId(Entity entity, UUID personaId) {
        entity.setData(NeoForgeAttachments.PERSONA_ID.get(), personaId);
    }

    @Override
    public void clearPersonaId(Entity entity) {
        entity.removeData(NeoForgeAttachments.PERSONA_ID.get());
    }
}
