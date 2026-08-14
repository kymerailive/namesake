package net.namesake.npc;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;
import net.namesake.Namesake;
import net.namesake.settlement.Settlement;
import net.namesake.settlement.Settlements;
import net.namesake.social.Bond;
import net.namesake.social.Bonds;
import net.namesake.social.Deed;
import net.namesake.social.Gossip;
import net.namesake.social.Memories;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Every persona in the world, keyed by persona id, plus the binding from a persona to the entity
 * currently carrying it, plus the settlements those personas belong to.
 *
 * <p><b>Settlements, bonds, memories and gossip live here rather than in files of their own.</b> A
 * persona references its settlement by id, a bond references a persona by id, and a deed references
 * both, so two files could be torn apart by a crash between two writes and leave every villager in a
 * village pointing at a settlement that no longer exists, or every bond in the world pointing at
 * people who do not. One file means one schema version that cannot disagree with itself and one load
 * path to get right. Ruled for settlements in session 03, re-ruled for bonds in session 05, again
 * for the deed rings in session 06 — where the size counter-argument was finally big enough to be
 * worth measuring rather than dismissing — and again for the gossip deques in session 08, where a
 * queued rumour is a {@link Deed}, which points at a persona and a settlement by id. See
 * {@link Bonds}, {@link Memories} and {@link Gossip}.
 *
 * <p>Stored once on the overworld's data storage rather than per-dimension: a persona is a person,
 * not a thing in a place, and it has to be findable from any dimension.
 *
 * <p><b>Why the binding lives here and not on the persona:</b> {@code Persona} is the durable
 * identity; which entity happens to be rendering it right now is volatile — it changes on every
 * zombification and cure. Keeping the two apart means the reverse lookup can be rebuilt and
 * reconciled on load without ever rewriting a persona.
 */
public final class NpcRegistry extends SavedData {

    /** File name under {@code <world>/data/}. */
    public static final String FILE_ID = "namesake_npcs";

    private static final String KEY_ENTITY = "entity";

    private final Map<UUID, Persona> personas = new LinkedHashMap<>();
    private final Map<UUID, UUID> personaToEntity = new HashMap<>();
    /** Rebuilt from {@link #personaToEntity} on load; never persisted. */
    private final Map<UUID, UUID> entityToPersona = new HashMap<>();
    private final Settlements settlements = new Settlements();
    private final Bonds bonds = new Bonds();
    private final Memories memories = new Memories();
    private final Gossip gossip = new Gossip();

    private int loadedSchemaVersion = NpcSchema.CURRENT;
    private boolean readOnly;

    public NpcRegistry() {
    }

    /**
     * The vanilla {@code SavedData.Factory} constructor takes a non-null {@code DataFixTypes} —
     * {@code DimensionDataStorage.readTagFromDisk} dereferences it unconditionally. NeoForge
     * patches in a two-argument constructor that permits null; Fabric does not. Passing null here
     * would compile on both loaders and then throw only on Fabric, only when a save already exists.
     * Exactly the shape of failure session 00 shipped, so: always pass a real type.
     *
     * <p>{@code LEVEL} is a no-op at the same data version and only engages if the world is later
     * opened on a newer Minecraft. Our own versioning is {@link NpcSchema}, not this.
     */
    public static SavedData.Factory<NpcRegistry> factory() {
        return new SavedData.Factory<>(NpcRegistry::new, NpcRegistry::load, DataFixTypes.LEVEL);
    }

    public static NpcRegistry get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(factory(), FILE_ID);
    }

    public static NpcRegistry get(ServerLevel level) {
        return get(level.getServer());
    }

    // --- reads ---------------------------------------------------------------------------------

    public Optional<Persona> persona(UUID personaId) {
        return Optional.ofNullable(personas.get(personaId));
    }

    public Optional<UUID> boundEntity(UUID personaId) {
        return Optional.ofNullable(personaToEntity.get(personaId));
    }

    public Optional<UUID> personaOfEntity(UUID entityId) {
        return Optional.ofNullable(entityToPersona.get(entityId));
    }

    public Collection<Persona> all() {
        return List.copyOf(personas.values());
    }

    /**
     * The settlement table. Read freely; write through {@link #putSettlement} so the file is
     * actually marked dirty — a settlement registered into a registry that never goes dirty is a
     * settlement that is re-detected on every single load.
     */
    public Settlements settlements() {
        return settlements;
    }

    /**
     * The bond table. Read freely; write through {@link #putBond}, for the same reason settlements
     * do — and for one more. A bond updated without {@code setDirty} is a bond that exists until the
     * world reloads and then does not, which is session 01's migration defect in a new place.
     */
    public Bonds bonds() {
        return bonds;
    }

    /**
     * The deed rings. Read freely; write through {@link #remember}, which is the only door that
     * marks the file dirty.
     */
    public Memories memories() {
        return memories;
    }

    /**
     * What each settlement is still talking about.
     *
     * <p>Read freely; written through {@link #enqueueRumour} and {@code Gossip.drain}, which are the
     * two doors that mark the file dirty. The drain is the one place in this mod where a persisted
     * table changes on a tick rather than on an event, so it is worth saying where the dirty flag
     * comes from: a drain marks it because the village's copy of a story is worse attested
     * afterwards than it was before, and a rumour that quietly failed to degrade across a reload
     * would travel further than the design permits.
     */
    public Gossip gossip() {
        return gossip;
    }

    public int size() {
        return personas.size();
    }

    public int bindingCount() {
        return personaToEntity.size();
    }

    /** The version found on disk. Equal to {@link NpcSchema#CURRENT} unless a migration ran. */
    public int loadedSchemaVersion() {
        return loadedSchemaVersion;
    }

    /** True when the file on disk must not be overwritten. See {@link #setDirty(boolean)}. */
    public boolean isReadOnly() {
        return readOnly;
    }

    // --- writes --------------------------------------------------------------------------------

    public Persona createPersona(long birthTick) {
        UUID id = UUID.randomUUID();
        Persona persona = Persona.create(id, birthTick);
        personas.put(id, persona);
        setDirty();
        return persona;
    }

    /**
     * Writes a persona into the registry.
     *
     * <p>Refuses an id in {@link Persona#PROFILING_NAMESPACE}. Session 04 builds hundreds of
     * fixture records to time a sweep over them, and a fixture that reached this map would be
     * saved, reloaded and indistinguishable from a person — a silent corruption rather than a
     * loud one. The fixtures are held in the profiler's own list and never offered here; this is
     * the door that makes that a property of the code rather than of the harness remembering to
     * tidy up after a run it may not have finished.
     */
    public void put(Persona persona) {
        if (Persona.isReservedForProfiling(persona.id())) {
            Namesake.LOGGER.error(
                    "Refused to store persona {}: that id is reserved for profiling fixtures and "
                            + "must never reach a save file. Nothing was written.", persona.id());
            return;
        }
        personas.put(persona.id(), persona);
        setDirty();
    }

    public void putSettlement(Settlement settlement) {
        settlements.put(settlement);
        setDirty();
    }

    /**
     * Writes a bond, and refuses one whose subject is somebody this world knows as a persona.
     *
     * <p><b>This is the guard that makes decision 1 of session 05 hold.</b> {@link Bonds} is keyed
     * on two bare UUIDs and can therefore represent an NPC's feelings about another NPC; nothing
     * reads such a bond until session 16's grievance engine, and a persisted social value with no
     * consumer is exactly what {@code DESIGN.md} §1 forbids. Twelve witnesses per deed across four
     * hundred personas is a table that grows toward n², so this is not a tidiness rule.
     *
     * <p>Session 16 deletes the {@code if} below and changes no schema, which is the whole reason
     * the key was left general. Until then the refusal is loud rather than silent — a bond quietly
     * not written is indistinguishable from a bond written and lost.
     */
    public void putBond(UUID holder, UUID about, Bond bond) {
        if (Deed.UNKNOWN_ACTOR.equals(about)) {
            // The backstop to DeedBus.deliver's blur guard, and two doors rather than one for the
            // reason the profiling-fixture refusal has two: what is being guarded against is silent.
            // A bond about nobody loads perfectly, reads as a real relationship forever after, and
            // is a persisted social value with no person on the other end of it.
            Namesake.LOGGER.error(
                    "Refused a bond from persona {} about the unknown actor: an unattributed rumour "
                            + "moves nobody's opinion of anybody, because nobody knows who did it. "
                            + "Nothing was written.", holder);
            return;
        }
        if (personas.containsKey(about)) {
            Namesake.LOGGER.error(
                    "Refused a bond from persona {} about persona {}: NPC-to-NPC bonds have no "
                            + "consumer until session 16's grievance engine, and DESIGN.md §1 "
                            + "forbids persisting a social value nothing reads. Nothing was written.",
                    holder, about);
            return;
        }
        bonds.put(holder, about, bond);
        setDirty();
    }

    /**
     * Appends a deed to one NPC's ring, and marks the file dirty only if the ring actually changed.
     *
     * <p><b>Deliberately without {@link #putBond}'s population guard, and the asymmetry is the
     * point.</b> That guard exists because a bond table keyed on two personas grows toward n² — twelve
     * witnesses a deed across four hundred people — so its size is decided by who the subjects are. A
     * ring is capped at {@link Memories#RING_CAPACITY} per holder whoever the actor was, so the whole
     * table is bounded by the persona count and cannot be made to grow by emitting a different kind
     * of deed. Session 16's NPC-to-NPC deeds therefore need nothing here; {@link net.namesake.social.DeedBus}
     * is where they are currently declined, one level up, and for the bond's reason rather than this
     * one.
     *
     * <p>The dirty flag follows {@link #bind}'s rule. A duplicate deed changes nothing, and marking
     * the registry dirty for it would have Minecraft rewrite every persona, settlement, bond and ring
     * in the world on the next autosave because a player gave the same villager the same loaf twice.
     *
     * @return true if the ring changed
     */
    public boolean remember(UUID holder, Deed deed) {
        if (!memories.remember(holder, deed)) {
            return false;
        }
        setDirty();
        return true;
    }

    /**
     * Puts a deed into its own settlement's deque. {@code DESIGN.md} §4 step 6.
     *
     * <p>The settlement is read off the deed rather than passed in, for session 05's reason in a new
     * place: a deed that has to be <i>told</i> where it happened is a deed that can be told wrong,
     * and it already carries the answer. A deed with no settlement — one done in the wilderness — is
     * refused by {@link Gossip#enqueue} rather than filed under the unassigned sentinel, because
     * there is no village there to talk about it.
     *
     * <p>Dirty only when the deque actually changed, on {@link #bind}'s rule. Nine identical
     * feedings are one deed and therefore one rumour, so eight of them must not have Minecraft
     * rewrite every persona, settlement, bond and ring in the world at the next autosave.
     *
     * @return true if the deque changed
     */
    public boolean enqueueRumour(Deed deed) {
        if (!gossip.enqueue(deed.settlementId(), deed)) {
            return false;
        }
        setDirty();
        return true;
    }

    /**
     * Points a persona at the entity currently carrying it. Idempotent, and deliberately silent
     * when nothing changes — this runs on every chunk load and must not mark the file dirty for
     * a rebinding that is already true.
     */
    public void bind(UUID personaId, UUID entityId) {
        UUID previous = personaToEntity.get(personaId);
        if (entityId.equals(previous)) {
            return;
        }
        if (previous != null) {
            entityToPersona.remove(previous);
        }
        UUID displaced = entityToPersona.put(entityId, personaId);
        if (displaced != null && !displaced.equals(personaId)) {
            // Two personas claiming one entity means something upstream double-minted. Losing the
            // binding silently would make it invisible, so say so.
            Namesake.LOGGER.warn("Entity {} was bound to persona {}; rebinding to {}",
                    entityId, displaced, personaId);
            personaToEntity.remove(displaced);
        }
        personaToEntity.put(personaId, entityId);
        setDirty();
    }

    public boolean remove(UUID personaId) {
        Persona removed = personas.remove(personaId);
        UUID entity = personaToEntity.remove(personaId);
        if (entity != null) {
            entityToPersona.remove(entity);
        }
        // A persona's bonds and memories go with it. Leaving them behind would accumulate rows keyed
        // on people the registry can no longer name, and the ones that survive a prune are exactly
        // the ones nothing will ever look up again.
        boolean hadBonds = !bonds.of(personaId).isEmpty();
        bonds.forget(personaId);
        boolean hadMemories = memories.forget(personaId);
        if (removed != null || entity != null || hadBonds || hadMemories) {
            setDirty();
            return true;
        }
        return false;
    }

    /**
     * Drops personas whose entity {@code entityAlive} says is gone, and personas with no binding
     * at all.
     *
     * <p><b>Never call this on a schedule.</b> An unloaded villager is indistinguishable from a
     * dead one without walking the whole region file, and the architecture in {@code DESIGN.md} §8
     * depends on most personas having no loaded entity at all. This exists for the debug command
     * and for tests; the real lifecycle answer is a death hook, which is sessions 21-23.
     *
     * @return how many personas were removed
     */
    public int pruneOrphans(Predicate<UUID> entityAlive) {
        List<UUID> doomed = personas.keySet().stream()
                .filter(personaId -> {
                    UUID entity = personaToEntity.get(personaId);
                    return entity == null || !entityAlive.test(entity);
                })
                .toList();
        for (UUID personaId : doomed) {
            Namesake.LOGGER.debug("Pruning orphan persona {} (entity {})",
                    personaId, personaToEntity.get(personaId));
            remove(personaId);
        }
        if (!doomed.isEmpty()) {
            Namesake.LOGGER.info("Pruned {} orphan persona(s); {} remain", doomed.size(), personas.size());
        }
        return doomed.size();
    }

    /**
     * Refuses to mark dirty when the file on disk is newer than this build understands. Minecraft
     * only writes a {@code SavedData} that is dirty, so this is what actually prevents an old jar
     * from stamping over a newer save.
     */
    @Override
    public void setDirty(boolean dirty) {
        if (readOnly && dirty) {
            return;
        }
        super.setDirty(dirty);
    }

    // --- persistence ---------------------------------------------------------------------------

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putInt(NpcSchema.KEY_VERSION, NpcSchema.CURRENT);
        ListTag list = new ListTag();
        for (Persona persona : personas.values()) {
            if (Persona.isReservedForProfiling(persona.id())) {
                // The backstop to the one in put(). Two doors rather than one because the thing
                // being guarded against is silent: a fixture on disk loads perfectly and reads as
                // a person forever after.
                Namesake.LOGGER.error(
                        "Persona {} is a profiling fixture and is being dropped rather than saved. "
                                + "It should never have reached the registry.", persona.id());
                continue;
            }
            CompoundTag entry = (CompoundTag) Persona.CODEC
                    .encodeStart(NbtOps.INSTANCE, persona)
                    .getOrThrow(error -> new IllegalStateException(
                            "Cannot encode persona " + persona.id() + ": " + error));
            UUID entity = personaToEntity.get(persona.id());
            if (entity != null) {
                entry.putIntArray(KEY_ENTITY, UUIDUtil.uuidToIntArray(entity));
            }
            list.add(entry);
        }
        tag.put(NpcSchema.KEY_NPCS, list);
        settlements.save(tag);
        bonds.save(tag);
        memories.save(tag);
        gossip.save(tag);
        return tag;
    }

    private static NpcRegistry load(CompoundTag tag, HolderLookup.Provider registries) {
        NpcRegistry registry = new NpcRegistry();
        NpcSchema.Result result = NpcSchema.migrate(tag);
        registry.loadedSchemaVersion = result.foundVersion();
        registry.readOnly = result.refused();

        if (result.migrated()) {
            Namesake.LOGGER.info("NPC registry migrated {} -> {} on load ({} record(s) rewritten)",
                    result.foundVersion(), result.resultVersion(), result.recordsRewritten());
        }

        ListTag list = tag.getList(NpcSchema.KEY_NPCS, Tag.TAG_COMPOUND);
        int unreadable = 0;
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);
            Optional<Persona> parsed = Persona.CODEC.parse(NbtOps.INSTANCE, entry)
                    .resultOrPartial(error -> Namesake.LOGGER.error(
                            "Unreadable persona record in {}: {}", FILE_ID, error));
            if (parsed.isEmpty()) {
                unreadable++;
                continue;
            }
            Persona persona = parsed.get();
            registry.personas.put(persona.id(), persona);
            if (entry.contains(KEY_ENTITY, Tag.TAG_INT_ARRAY)) {
                UUID entity = UUIDUtil.uuidFromIntArray(entry.getIntArray(KEY_ENTITY));
                registry.personaToEntity.put(persona.id(), entity);
                registry.entityToPersona.put(entity, persona.id());
            }
        }

        // Settlements, bonds and memories are read after personas and counted into the same damage
        // figure: a file that lost a settlement is exactly as unsafe to write back as one that lost a
        // person, because every persona in that village is now pointing at an id nothing answers to.
        // A file that lost a bond is worse in one way and better in none — nothing about the world
        // looks wrong, a villager has simply forgotten somebody. A lost deed is the same shape again,
        // and it is the reason the ring is a table of its own: counted here, one bad deed costs one
        // villager one memory, where the same deed inside a persona record would have cost that
        // villager their name, their culture and their traits.
        unreadable += registry.settlements.readFrom(tag);
        unreadable += registry.bonds.readFrom(tag);
        unreadable += registry.memories.readFrom(tag);
        unreadable += registry.gossip.readFrom(tag);

        if (unreadable > 0) {
            // Saving now would drop those records for good. Better a world that loses nothing and
            // refuses to persist than one that quietly forgets people.
            registry.readOnly = true;
            Namesake.LOGGER.error(
                    "{} record(s) could not be read. The registry is now read-only for this "
                            + "session so the damaged file is not overwritten. Back up <world>/data/{}.dat.",
                    unreadable, FILE_ID);
        }

        // Minecraft only writes a SavedData that is dirty, and migrating does not make it dirty.
        // Without this the fixed records live in memory and die there: the file stays on the old
        // schema and every future load runs the whole chain again. Found by loading one world twice
        // and seeing the migration line the second time.
        //
        // Position matters as much as the call. Run before read-only is decided, this hands
        // Minecraft permission to rewrite a file we could not fully read, dropping the damaged
        // records for good — the one case the read-only guard exists for. setDirty(boolean) refuses
        // too, but saying the condition here keeps the reason next to the reader.
        if (result.migrated() && !registry.readOnly) {
            registry.setDirty();
        }

        Namesake.LOGGER.info(
                "Loaded {} persona(s), {} bound to an entity, {} settlement(s), {} bond(s), "
                        + "{} deed(s) across {} ring(s), {} rumour(s) in {} settlement(s) (schema {})",
                registry.personas.size(), registry.personaToEntity.size(),
                registry.settlements.size(), registry.bonds.size(),
                registry.memories.size(), registry.memories.holders(),
                registry.gossip.size(), registry.gossip.settlements(), result.resultVersion());
        return registry;
    }
}
