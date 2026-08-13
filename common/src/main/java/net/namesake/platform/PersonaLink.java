package net.namesake.platform;

import net.minecraft.world.entity.Entity;

import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.UUID;

/**
 * The only thing an entity stores about its persona: which one it is.
 *
 * <p>Fabric's attachment API and NeoForge's data attachments do the same job with incompatible
 * types, so neither type may appear in {@code common}. This is the seam. It carries a bare
 * {@code UUID} rather than a whole {@code Persona} on purpose — the record is the authority and
 * lives in {@code NpcRegistry}; the entity carries a pointer to it and nothing else. That keeps the
 * per-entity NBT at 16 bytes and means a persona is never duplicated between two stores that can
 * disagree.
 *
 * <p><b>Registration is not lazy.</b> Both loaders drop attachment data whose id was not registered
 * at the time entity NBT is read, so the implementation must be forced to load during mod init —
 * see {@code Namesake.init()}. A {@code ServiceLoader} lookup on first use would silently lose
 * every persona link on the first world load and look exactly like the feature never worked.
 */
public interface PersonaLink {

    /** The persona this entity carries, or empty if it has none. */
    Optional<UUID> personaId(Entity entity);

    void setPersonaId(Entity entity, UUID personaId);

    void clearPersonaId(Entity entity);

    static PersonaLink get() {
        return Holder.INSTANCE;
    }

    final class Holder {
        static final PersonaLink INSTANCE = load();

        private Holder() {
        }

        private static PersonaLink load() {
            try {
                return ServiceLoader.load(PersonaLink.class).findFirst().orElseThrow();
            } catch (NoSuchElementException e) {
                throw new IllegalStateException(
                        "No PersonaLink implementation found. The loader module is missing its "
                                + "META-INF/services/net.namesake.platform.PersonaLink entry.", e);
            }
        }
    }
}
