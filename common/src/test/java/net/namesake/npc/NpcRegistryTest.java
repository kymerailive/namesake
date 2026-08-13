package net.namesake.npc;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.namesake.settlement.Need;
import net.namesake.settlement.Settlement;
import net.namesake.settlement.Settlements;
import net.namesake.settlement.Specialty;
import net.namesake.social.Bond;
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

    /**
     * The two protections have to compose, and the order they run in decides whether they do. A
     * file that both needs migrating and holds a record we cannot read is the worst case: marking
     * it dirty for the migration would hand Minecraft permission to rewrite it, dropping the
     * unreadable records for good.
     */
    @Test
    @DisplayName("a damaged registry that also needs migrating is still never written back")
    void migrationDoesNotOverrideTheDamagedFileGuard() {
        CompoundTag tag = schemaOneTag();
        CompoundTag broken = new CompoundTag();
        broken.putString("id", "not a uuid");
        tag.getList(NpcSchema.KEY_NPCS, Tag.TAG_COMPOUND).add(broken);

        NpcRegistry registry = reload(tag);

        assertTrue(registry.isReadOnly(), "a damaged file must stay read-only");
        assertFalse(registry.isDirty(),
                "the migration must not mark a damaged registry for saving; that would overwrite "
                        + "the records that could not be read");
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
            npcs.getCompound(i).putByte("culture", (byte) 0);
        }
        tag.remove("settlements");
        return tag;
    }

    // --- settlements, new in session 03 -----------------------------------------------------------

    private static Settlement settlement(int id, int x, int z) {
        return new Settlement(id, ResourceLocation.withDefaultNamespace("overworld"),
                new BlockPos(x, 64, z), Specialty.FARMING.id(), (byte) 71,
                new byte[]{10, 40, 0, 25});
    }

    @Test
    @DisplayName("round trip preserves every settlement field, and the id counter with them")
    void roundTripPreservesSettlements() {
        NpcRegistry original = new NpcRegistry();
        Settlement first = settlement(0, 100, -200);
        Settlement second = settlement(1, 900, 400);
        original.putSettlement(first);
        original.putSettlement(second);
        assertEquals(2, original.settlements().claimId(), "ids continue past the highest stored");

        NpcRegistry reloaded = reload(original.save(new CompoundTag(), null));

        assertEquals(2, reloaded.settlements().size());
        assertEquals(Optional.of(first), reloaded.settlements().byId(0));
        assertEquals(Optional.of(second), reloaded.settlements().byId(1));
        assertEquals(40, reloaded.settlements().byId(0).orElseThrow().need(Need.TOOLS));
        // A counter that reset to zero would hand the next settlement an id somebody already has.
        assertEquals(3, reloaded.settlements().claimId(),
                "the id counter must survive the reload, including the ids already handed out");
    }

    @Test
    @DisplayName("registering a settlement marks the registry for saving")
    void registeringASettlementMarksTheRegistryDirty() {
        NpcRegistry registry = new NpcRegistry();
        assertFalse(registry.isDirty());

        registry.putSettlement(settlement(0, 0, 0));

        // Minecraft only writes a SavedData that is dirty. A settlement registered into a clean
        // registry would be re-detected, and re-surveyed, on every single world load.
        assertTrue(registry.isDirty());
    }

    @Test
    @DisplayName("an unreadable settlement makes the whole registry read-only, like an unreadable persona")
    void oneCorruptSettlementProtectsTheFile() {
        NpcRegistry good = new NpcRegistry();
        good.putSettlement(settlement(0, 0, 0));
        CompoundTag tag = good.save(new CompoundTag(), null);

        CompoundTag broken = new CompoundTag();
        broken.putString("dimension", "not a resource location at all");
        tag.getList("settlements", Tag.TAG_COMPOUND).add(broken);

        NpcRegistry reloaded = reload(tag);

        assertEquals(1, reloaded.settlements().size(), "the readable settlement still loads");
        assertTrue(reloaded.isReadOnly(),
                "every persona in the lost settlement now points at an id nothing answers to, so "
                        + "the file must not be written back");
    }

    // --- bonds, new in session 05 -----------------------------------------------------------------

    private static final UUID A_PLAYER = UUID.fromString("0a0a0a0a-1111-2222-3333-444444444444");

    @Test
    @DisplayName("a bond about a player is stored, marks the registry dirty, and survives a reload")
    void bondsRoundTripAndMarkTheRegistryDirty() {
        NpcRegistry registry = new NpcRegistry();
        UUID personaId = UUID.randomUUID();
        registry.put(stamped(personaId, 3, 1L, (byte) 0));
        assertTrue(registry.isDirty());
        registry.setDirty(false);

        Bond bond = Bond.fresh(40).apply(new int[]{3, 3, 0, 0}, 40, Bond.DAILY_CAP);
        registry.putBond(personaId, A_PLAYER, bond);

        // CLAUDE.md's note for this session: a SavedData is only written when it is dirty, so a
        // bond updated without setDirty is a bond that exists until the world reloads.
        assertTrue(registry.isDirty(), "a bond written into a clean registry never reaches the file");

        NpcRegistry reloaded = reload(registry.save(new CompoundTag(), null));
        assertEquals(1, reloaded.bonds().size());
        assertEquals(Optional.of(bond), reloaded.bonds().stored(personaId, A_PLAYER));
    }

    @Test
    @DisplayName("a bond about another NPC is refused, and refused loudly enough to leave no trace")
    void npcToNpcBondsAreRefused() {
        NpcRegistry registry = new NpcRegistry();
        UUID anna = UUID.randomUUID();
        UUID bram = UUID.randomUUID();
        registry.put(stamped(anna, 3, 1L, (byte) 0));
        registry.put(stamped(bram, 3, 1L, (byte) 0));
        registry.setDirty(false);

        registry.putBond(anna, bram, Bond.fresh(1).apply(new int[]{5, 0, 0, 0}, 1, Bond.DAILY_CAP));

        // Session 05 decision 1: the key is general so session 16 needs no migration, and the
        // population is restricted because an NPC-to-NPC bond has no consumer until then. Twelve
        // witnesses per deed across four hundred personas is 160,000 rows of DESIGN.md §1's
        // forbidden shape, so the guard is not tidiness.
        assertEquals(0, registry.bonds().size(), "nothing may be written");
        assertFalse(registry.isDirty(), "and a refusal must not dirty the file either");

        // The same bond about somebody the registry has never heard of — a player — goes through.
        registry.putBond(anna, A_PLAYER, Bond.fresh(1).apply(new int[]{5, 0, 0, 0}, 1, Bond.DAILY_CAP));
        assertEquals(1, registry.bonds().size());
    }

    @Test
    @DisplayName("removing a persona takes its bonds with it, and says the file changed")
    void removingAPersonaDropsItsBonds() {
        NpcRegistry registry = new NpcRegistry();
        UUID personaId = UUID.randomUUID();
        registry.put(stamped(personaId, 3, 1L, (byte) 0));
        registry.putBond(personaId, A_PLAYER, Bond.fresh(1).apply(new int[]{5, 0, 0, 0}, 1, Bond.DAILY_CAP));
        registry.setDirty(false);

        assertTrue(registry.remove(personaId));
        assertEquals(0, registry.bonds().size(),
                "a bond keyed on a persona nothing can name is a row nothing will ever look up");
        assertTrue(registry.isDirty());
    }

    @Test
    @DisplayName("an unreadable bond makes the whole registry read-only, like an unreadable persona")
    void oneCorruptBondProtectsTheFile() {
        NpcRegistry good = new NpcRegistry();
        UUID personaId = UUID.randomUUID();
        good.put(stamped(personaId, 3, 1L, (byte) 0));
        good.putBond(personaId, A_PLAYER, Bond.fresh(1).apply(new int[]{5, 0, 0, 0}, 1, Bond.DAILY_CAP));
        CompoundTag tag = good.save(new CompoundTag(), null);

        tag.getList("bonds", Tag.TAG_COMPOUND).add(new CompoundTag());

        NpcRegistry reloaded = reload(tag);

        assertEquals(1, reloaded.bonds().size(), "the readable bond still loads");
        assertTrue(reloaded.isReadOnly(),
                "a villager who has quietly forgotten somebody is exactly as unsafe to write back "
                        + "as a village that has lost its bell");
    }

    @Test
    @DisplayName("residency is decided by distance from the bell and by dimension")
    void residencyIsBoundedAndPerDimension() {
        Settlements settlements = new Settlements();
        settlements.put(settlement(0, 0, 0));
        ResourceLocation overworld = ResourceLocation.withDefaultNamespace("overworld");
        ResourceLocation nether = ResourceLocation.withDefaultNamespace("the_nether");

        assertTrue(settlements.containing(overworld, new BlockPos(40, 64, 40)).isPresent());
        assertTrue(settlements.containing(overworld, new BlockPos(0, -400, 0)).isPresent(),
                "residency is horizontal: a mine under the village is still the village");
        assertTrue(settlements.containing(overworld,
                new BlockPos(Settlements.MEMBERSHIP_RADIUS + 1, 64, 0)).isEmpty());
        assertTrue(settlements.containing(nether, new BlockPos(0, 64, 0)).isEmpty(),
                "a bell at the same coordinates in another dimension is somebody else's bell");
    }

    @Test
    @DisplayName("the nearest settlement wins when two are in range")
    void residencyPicksTheNearestBell() {
        Settlements settlements = new Settlements();
        settlements.put(settlement(0, 0, 0));
        settlements.put(settlement(1, 150, 0));
        ResourceLocation overworld = ResourceLocation.withDefaultNamespace("overworld");

        assertEquals(0, settlements.containing(overworld, new BlockPos(60, 64, 0)).orElseThrow().id());
        assertEquals(1, settlements.containing(overworld, new BlockPos(90, 64, 0)).orElseThrow().id());
    }
}
