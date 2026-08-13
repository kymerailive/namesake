package net.namesake.npc;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The registry's persistence contract, exercised without a running server.
 *
 * <p>{@code HolderLookup.Provider} is null throughout: none of our codecs touch the registries, and
 * passing null here proves it stays that way. If a future field needs registry access these tests
 * will NPE rather than quietly work in-process and fail in a world.
 */
class NpcRegistryTest {

    private static Persona stamped(UUID id, int settlement, long birthTick, byte warmth) {
        return Persona.create(id, birthTick)
                .withSettlement(settlement)
                .withTrait(Persona.WARMTH, warmth);
    }

    @Test
    @DisplayName("round trip preserves every persona field and every binding")
    void roundTripPreservesEveryField() {
        NpcRegistry original = new NpcRegistry();

        UUID personaA = UUID.fromString("11111111-2222-3333-4444-555555555555");
        UUID personaB = UUID.fromString("66666666-7777-8888-9999-aaaaaaaaaaaa");
        UUID entityA = UUID.fromString("bbbbbbbb-cccc-dddd-eeee-ffffffffffff");

        Persona a = stamped(personaA, 7, 12345L, (byte) 42);
        Persona b = stamped(personaB, 9, 999L, (byte) -17);
        original.put(a);
        original.put(b);
        original.bind(personaA, entityA);
        // b is deliberately left unbound, so the optional binding column is exercised both ways.

        CompoundTag saved = original.save(new CompoundTag(), null);
        NpcRegistry reloaded = reload(saved);

        assertEquals(2, reloaded.size());
        assertEquals(Optional.of(a), reloaded.persona(personaA));
        assertEquals(Optional.of(b), reloaded.persona(personaB));
        assertEquals(42, reloaded.persona(personaA).orElseThrow().trait(Persona.WARMTH));
        assertEquals(-17, reloaded.persona(personaB).orElseThrow().trait(Persona.WARMTH));
        assertEquals(7, reloaded.persona(personaA).orElseThrow().settlementId());
        assertEquals(12345L, reloaded.persona(personaA).orElseThrow().birthTick());
        assertEquals(a.appearanceSeed(), reloaded.persona(personaA).orElseThrow().appearanceSeed());

        assertEquals(Optional.of(entityA), reloaded.boundEntity(personaA));
        assertEquals(Optional.of(personaA), reloaded.personaOfEntity(entityA));
        assertEquals(Optional.empty(), reloaded.boundEntity(personaB));
        assertEquals(1, reloaded.bindingCount());

        assertEquals(NpcSchema.CURRENT, reloaded.loadedSchemaVersion());
        assertFalse(reloaded.isReadOnly());
    }

    @Test
    @DisplayName("a persona whose traits differ is not equal to one whose traits do not")
    void personaEqualityComparesTraitContentsNotArrayIdentity() {
        UUID id = UUID.randomUUID();
        Persona one = Persona.create(id, 5L).withTrait(Persona.TEMPER, (byte) 3);
        Persona same = Persona.create(id, 5L).withTrait(Persona.TEMPER, (byte) 3);
        Persona other = Persona.create(id, 5L).withTrait(Persona.TEMPER, (byte) 4);

        // Without the equals/hashCode overrides on the record this assertion fails, and every
        // "the fields survived the reload" claim in this file becomes vacuous.
        assertEquals(one, same);
        assertEquals(one.hashCode(), same.hashCode());
        assertNotEquals(one, other);
    }

    @Test
    @DisplayName("a persona cannot be built with the wrong number of trait axes")
    void personaRejectsWrongTraitCount() {
        assertThrows(IllegalArgumentException.class,
                () -> new Persona(UUID.randomUUID(), 0, 0, new byte[3], (byte) 0, 0, 0L, 0, (byte) 0));
    }

    @Test
    @DisplayName("mutating the array handed to a persona does not mutate the persona")
    void personaCopiesTraitsDefensively() {
        byte[] traits = new byte[Persona.TRAIT_COUNT];
        traits[Persona.BOLDNESS] = 50;
        Persona persona = new Persona(UUID.randomUUID(), 0, 0, traits, (byte) 0, 0, 0L, 0, (byte) 0);

        traits[Persona.BOLDNESS] = -50;
        assertEquals(50, persona.trait(Persona.BOLDNESS));

        persona.traits()[Persona.BOLDNESS] = -50;
        assertEquals(50, persona.trait(Persona.BOLDNESS));
    }

    @Test
    @DisplayName("orphan cleanup drops personas whose entity is gone and keeps the rest")
    void pruneOrphansRemovesOnlyTheGone() {
        NpcRegistry registry = new NpcRegistry();
        Persona live = registry.createPersona(1L);
        Persona dead = registry.createPersona(2L);
        Persona unbound = registry.createPersona(3L);

        UUID liveEntity = UUID.randomUUID();
        UUID deadEntity = UUID.randomUUID();
        registry.bind(live.id(), liveEntity);
        registry.bind(dead.id(), deadEntity);

        Set<UUID> alive = new HashSet<>(Set.of(liveEntity));
        int removed = registry.pruneOrphans(alive::contains);

        assertEquals(2, removed, "the dead-entity persona and the unbound one");
        assertEquals(1, registry.size());
        assertTrue(registry.persona(live.id()).isPresent());
        assertTrue(registry.persona(dead.id()).isEmpty());
        assertTrue(registry.persona(unbound.id()).isEmpty());
        assertEquals(Optional.empty(), registry.personaOfEntity(deadEntity));
    }

    @Test
    @DisplayName("rebinding a persona to a new entity clears the old reverse index")
    void rebindingDropsTheStaleReverseIndex() {
        NpcRegistry registry = new NpcRegistry();
        Persona persona = registry.createPersona(1L);
        UUID before = UUID.randomUUID();
        UUID after = UUID.randomUUID();

        registry.bind(persona.id(), before);
        registry.bind(persona.id(), after);

        assertEquals(Optional.of(after), registry.boundEntity(persona.id()));
        assertEquals(Optional.of(persona.id()), registry.personaOfEntity(after));
        assertEquals(Optional.empty(), registry.personaOfEntity(before),
                "the zombie's entity id must not still resolve to the persona after the cure");
        assertEquals(1, registry.bindingCount());
    }

    @Test
    @DisplayName("a registry file newer than this build is not migrated and never overwritten")
    void refusesToDowngradeANewerSchema() {
        CompoundTag future = new CompoundTag();
        future.putInt(NpcSchema.KEY_VERSION, NpcSchema.CURRENT + 1);
        future.put(NpcSchema.KEY_NPCS, new ListTag());

        NpcRegistry registry = reload(future);

        assertTrue(registry.isReadOnly());
        assertEquals(NpcSchema.CURRENT + 1, registry.loadedSchemaVersion());

        registry.createPersona(1L);
        assertFalse(registry.isDirty(),
                "a read-only registry must not become dirty, or Minecraft writes the old shape over "
                        + "a newer save");
    }

    /**
     * A migration that is never written back is not a migration. Minecraft only saves a
     * {@code SavedData} that is dirty, so a registry that migrated on load must come back dirty or
     * the file stays on the old schema and every subsequent load repeats the whole fix chain.
     */
    @Test
    @DisplayName("a registry that migrated on load is dirty, so the fix reaches disk")
    void migrationMarksTheRegistryForSaving() {
        NpcRegistry migrated = reload(schemaOneTag());
        assertTrue(migrated.isDirty(),
                "a migrated registry must be written back, or it migrates again on every load");

        NpcRegistry alreadyCurrent = new NpcRegistry();
        alreadyCurrent.put(stamped(UUID.randomUUID(), 4, 1L, (byte) 5));
        NpcRegistry reloaded = reload(alreadyCurrent.save(new CompoundTag(), null));
        assertFalse(reloaded.isDirty(),
                "a registry that needed no migration must not be rewritten for no reason");
    }

    @Test
    @DisplayName("an unreadable record makes the whole registry read-only rather than dropping it")
    void oneCorruptRecordProtectsTheFile() {
        NpcRegistry good = new NpcRegistry();
        good.put(stamped(UUID.randomUUID(), 3, 8L, (byte) 1));
        CompoundTag tag = good.save(new CompoundTag(), null);

        ListTag npcs = tag.getList(NpcSchema.KEY_NPCS, Tag.TAG_COMPOUND);
        CompoundTag broken = new CompoundTag();
        broken.putString("id", "not a uuid");
        npcs.add(broken);

        NpcRegistry reloaded = reload(tag);

        assertEquals(1, reloaded.size(), "the readable record still loads");
        assertTrue(reloaded.isReadOnly(), "but the damaged file must not be rewritten");
    }

    /** Runs the same path {@code DimensionDataStorage} does: deserialize a tag into a registry. */
    private static NpcRegistry reload(CompoundTag tag) {
        return NpcRegistry.factory().deserializer().apply(tag, null);
    }

    /** A registry tag in the shape schema 1 wrote: version stamp 1, zero for "no settlement". */
    private static CompoundTag schemaOneTag() {
        NpcRegistry registry = new NpcRegistry();
        registry.put(stamped(UUID.randomUUID(), 0, 1L, (byte) 1));
        CompoundTag tag = registry.save(new CompoundTag(), null);
        tag.putInt(NpcSchema.KEY_VERSION, 1);
        ListTag npcs = tag.getList(NpcSchema.KEY_NPCS, Tag.TAG_COMPOUND);
        for (int i = 0; i < npcs.size(); i++) {
            npcs.getCompound(i).putInt("settlement", 0);
            npcs.getCompound(i).putInt("household", 0);
        }
        return tag;
    }
}
