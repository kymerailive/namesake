package net.namesake.fabric;

import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.minecraft.core.UUIDUtil;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.namesake.Namesake;
import net.namesake.platform.PersonaLink;

import java.util.Optional;
import java.util.UUID;

/**
 * Fabric side of the persona attachment.
 *
 * <p>{@code createPersistent} is what puts the 16 bytes into the entity's NBT, which is what makes
 * the persona survive a chunk unload and a world save. Registration happens in this class's static
 * initialiser and {@code Namesake.init()} touches the class deliberately — see {@link PersonaLink}.
 *
 * <p>{@code copyOnDeath()} is <b>not</b> set. Fabric applies it on player respawn and dimension
 * change, not on {@code Mob.convertTo}, and NeoForge applies it on conversion too — setting it
 * would give the two loaders different code paths through the one lifecycle event that matters
 * most. Conversion is handled explicitly and identically on both, in {@code PersonaService}.
 */
public final class FabricPersonaLink implements PersonaLink {

    static final AttachmentType<UUID> PERSONA_ID = AttachmentRegistry.createPersistent(
            ResourceLocation.fromNamespaceAndPath(Namesake.MOD_ID, "persona_id"),
            UUIDUtil.CODEC);

    @Override
    public Optional<UUID> personaId(Entity entity) {
        return Optional.ofNullable(entity.getAttached(PERSONA_ID));
    }

    @Override
    public void setPersonaId(Entity entity, UUID personaId) {
        entity.setAttached(PERSONA_ID, personaId);
    }

    @Override
    public void clearPersonaId(Entity entity) {
        entity.removeAttached(PERSONA_ID);
    }
}
