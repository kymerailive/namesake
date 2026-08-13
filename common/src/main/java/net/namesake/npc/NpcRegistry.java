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
 * <p><b>Settlements live here rather than in a file of their own.</b> A persona references its
 * settlement by id, so two files could be torn apart by a crash between two writes and leave every
 * villager in a village pointing at a settlement that no longer exists. One file means one schema
 * version that cannot disagree with itself and one load path to get right.
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
        if (removed != null || entity != null) {
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

        // Settlements are read after personas and counted into the same damage figure: a file
        // that lost a settlement is exactly as unsafe to write back as one that lost a person,
        // because every persona in that village is now pointing at an id nothing answers to.
        unreadable += registry.settlements.readFrom(tag);

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

        Namesake.LOGGER.info("Loaded {} persona(s), {} bound to an entity, {} settlement(s) (schema {})",
                registry.personas.size(), registry.personaToEntity.size(),
                registry.settlements.size(), result.resultVersion());
        return registry;
    }
}
