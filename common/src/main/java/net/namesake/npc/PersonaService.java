package net.namesake.npc;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.ZombieVillager;
import net.minecraft.world.entity.npc.Villager;
import net.namesake.Namesake;
import net.namesake.platform.PersonaLink;

import java.util.Optional;
import java.util.UUID;

/**
 * The whole attach bet, in one class.
 *
 * <p>Everything in {@code DESIGN.md} assumes a persona can ride a <b>vanilla</b> {@code Villager}
 * through the entity's entire lifecycle: chunk unload, world save, and zombification and cure —
 * which destroys the entity and builds a new one with a different UUID. Two loader hooks feed this:
 * an entity-load hook and a conversion hook. Everything else is here, once.
 */
public final class PersonaService {

    private PersonaService() {
    }

    /**
     * An entity carries a persona if it is a villager, or a zombie villager that used to be one.
     * Only villagers ever get a <i>new</i> persona minted — a zombie villager that spawned wild in
     * a dungeon is not a resident of anywhere.
     */
    public static boolean isPersonaCarrier(Entity entity) {
        return entity instanceof Villager || entity instanceof ZombieVillager;
    }

    public static Optional<Persona> personaOf(Entity entity) {
        if (!(entity.level() instanceof ServerLevel level)) {
            return Optional.empty();
        }
        return PersonaLink.get().personaId(entity)
                .flatMap(id -> NpcRegistry.get(level).persona(id));
    }

    /**
     * Called for every entity entering a server level — fresh spawns and chunk loads alike.
     *
     * <p>Three cases, and the third is the one that matters: an entity that remembers a persona the
     * registry has lost. That happens if someone deletes {@code namesake_npcs.dat} or restores a
     * partial backup. Minting a fresh id there would silently sever the villager from every deed
     * and bond ever recorded about it; rebuilding an empty record under the <i>same</i> id keeps
     * the identity and makes the loss visible in the log instead.
     */
    public static void onEntityLoad(Entity entity) {
        if (!(entity.level() instanceof ServerLevel level) || !isPersonaCarrier(entity)) {
            return;
        }
        NpcRegistry registry = NpcRegistry.get(level);
        Optional<UUID> linked = PersonaLink.get().personaId(entity);

        if (linked.isEmpty()) {
            if (!(entity instanceof Villager)) {
                return;
            }
            Persona minted = registry.createPersona(level.getGameTime());
            PersonaLink.get().setPersonaId(entity, minted.id());
            registry.bind(minted.id(), entity.getUUID());
            Namesake.LOGGER.debug("Minted persona {} for villager {}", minted.id(), entity.getUUID());
            generate(level, registry, entity, minted);
            return;
        }

        UUID personaId = linked.get();
        if (registry.persona(personaId).isEmpty()) {
            registry.put(Persona.create(personaId, level.getGameTime()));
            Namesake.LOGGER.warn(
                    "Entity {} carries persona {}, which the registry does not have. Rebuilt an empty "
                            + "record under the same id; its traits and history are gone.",
                    entity.getUUID(), personaId);
        }
        registry.bind(personaId, entity.getUUID());
        registry.persona(personaId).ifPresent(persona -> generate(level, registry, entity, persona));
    }

    /**
     * Gives a minted persona a culture, a household and traits — or starts the survey that will.
     *
     * <p>Only villagers. A zombie villager has no household and belongs to nowhere; if it is cured
     * it becomes a villager, this runs then, and it is generated on the spot. Which is right: a
     * cured villager joins the village that cured it.
     *
     * <p>Runs on every entity load rather than only on mint, because the answer may not have been
     * available the first time — a persona minted in an unsurveyed area stays ungenerated, saves,
     * and finishes generating on a later load. {@code Personas.onPersonaLoaded} returns on its
     * first line for anyone already generated and settled, which is everybody, almost always.
     */
    private static void generate(ServerLevel level, NpcRegistry registry, Entity entity,
                                 Persona persona) {
        if (entity instanceof Villager) {
            Personas.onPersonaLoaded(level, registry, persona, entity.blockPosition());
        }
    }

    /**
     * Carries the persona across {@code Mob.convertTo} — villager to zombie villager and back.
     *
     * <p>The two loaders fire their conversion event at different points, and the difference is
     * load-bearing. Fabric fires inside {@code convertTo} <i>before</i> the new entity is added to
     * the level; NeoForge fires at the call site <i>after</i>. So on NeoForge the entity-load hook
     * has already run for the new villager and already minted it a persona of its own. That stray
     * is reaped here, or every cure would leave a dead record behind forever.
     */
    public static void onConversion(Entity from, Entity to) {
        if (!(to.level() instanceof ServerLevel level)) {
            return;
        }
        Optional<UUID> carried = PersonaLink.get().personaId(from);
        if (carried.isEmpty()) {
            return;
        }
        UUID personaId = carried.get();
        NpcRegistry registry = NpcRegistry.get(level);

        PersonaLink.get().personaId(to)
                .filter(minted -> !minted.equals(personaId))
                .ifPresent(minted -> reapStrayMint(registry, level, to.getUUID(), minted));

        PersonaLink.get().setPersonaId(to, personaId);
        registry.bind(personaId, to.getUUID());
        Namesake.LOGGER.info("Persona {} survived {} -> {} (entity {} -> {})",
                personaId,
                EntityType.getKey(from.getType()),
                EntityType.getKey(to.getType()),
                from.getUUID(), to.getUUID());
    }

    /**
     * Removes a persona that the entity-load hook minted moments ago for an entity that turned out
     * to be a conversion product. Both conditions are checked rather than assumed: it must be bound
     * to this very entity and have been born this tick. If it is anything else, leave it alone and
     * say so — an unexplained second persona is a bug worth seeing, not worth deleting.
     */
    private static void reapStrayMint(NpcRegistry registry, ServerLevel level, UUID entityId, UUID strayId) {
        boolean boundToThisEntity = registry.boundEntity(strayId).filter(entityId::equals).isPresent();
        boolean bornThisTick = registry.persona(strayId)
                .filter(persona -> persona.birthTick() == level.getGameTime())
                .isPresent();

        if (boundToThisEntity && bornThisTick) {
            registry.remove(strayId);
            // Deliberately INFO, not DEBUG. This only fires on a loader whose conversion event runs
            // after the new entity has joined the level, and it is the only visible evidence that
            // the reaping happened at all rather than the stray never existing.
            Namesake.LOGGER.info("Reaped stray persona {} minted for conversion product {}", strayId, entityId);
        } else {
            Namesake.LOGGER.warn(
                    "Entity {} already carried persona {} before conversion and it does not look freshly "
                            + "minted (bound here: {}, born this tick: {}). Leaving it in the registry.",
                    entityId, strayId, boundToThisEntity, bornThisTick);
        }
    }
}
