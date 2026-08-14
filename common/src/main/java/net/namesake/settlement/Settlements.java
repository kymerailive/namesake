package net.namesake.settlement;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.namesake.Namesake;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Every settlement in the world, and the counter that hands out their ids.
 *
 * <p>Deliberately <b>not</b> its own {@code SavedData}. Personas reference settlements by id, so
 * two files could be torn apart by a crash between two writes and leave every villager in a village
 * pointing at a settlement that no longer exists. One file, one version number, one load path —
 * {@code NpcRegistry} owns this and persists it inside {@code namesake_npcs.dat}, under the same
 * {@code NpcSchema} ladder.
 */
public final class Settlements {

    /**
     * How far from the bell a villager still counts as a resident, horizontally.
     *
     * <p>Smaller than {@link SettlementSurvey#SURVEY_RADIUS} on purpose: the survey should see the
     * outlying farm, but the farmer who sleeps out there is still one of ours. It also decides when
     * a second bell is a second settlement rather than part of this one.
     */
    public static final int MEMBERSHIP_RADIUS = 96;

    private static final String KEY_LIST = "settlements";
    private static final String KEY_NEXT_ID = "nextSettlementId";

    private final Map<Integer, Settlement> byId = new LinkedHashMap<>();
    private int nextId;
    private int revision;

    public Optional<Settlement> byId(int id) {
        return Optional.ofNullable(byId.get(id));
    }

    public Collection<Settlement> all() {
        return List.copyOf(byId.values());
    }

    public int size() {
        return byId.size();
    }

    /**
     * The settlement a position belongs to, if any.
     *
     * <p>The two {@code if}s here are the whole reason {@link Settlement#dimension()} and
     * {@link Settlement#centre()} are persisted: a bell in the Nether is not this village's bell,
     * and a bell four hundred blocks away is somebody else's.
     */
    public Optional<Settlement> containing(ResourceLocation dimension, BlockPos pos) {
        Settlement best = null;
        long nearest = Long.MAX_VALUE;
        long limit = (long) MEMBERSHIP_RADIUS * MEMBERSHIP_RADIUS;

        for (Settlement settlement : byId.values()) {
            if (!settlement.dimension().equals(dimension)) {
                continue;
            }
            BlockPos centre = settlement.centre();
            long dx = (long) centre.getX() - pos.getX();
            long dz = (long) centre.getZ() - pos.getZ();
            long distanceSqr = dx * dx + dz * dz;
            if (distanceSqr <= limit && distanceSqr < nearest) {
                nearest = distanceSqr;
                best = settlement;
            }
        }
        return Optional.ofNullable(best);
    }

    /** The next free id. Starts at zero, which is a legal id — see {@code Persona.UNASSIGNED}. */
    public int claimId() {
        return nextId++;
    }

    public void put(Settlement settlement) {
        byId.put(settlement.id(), settlement);
        if (settlement.id() >= nextId) {
            nextId = settlement.id() + 1;
        }
        revision++;
    }

    /**
     * How many times this table has changed. Not persisted, and not a version of anything on disk.
     *
     * <p>Session 10's road graph is a pure function of this table, so it is derived rather than
     * stored — and derived means recomputed, which means something has to say when it is stale.
     * A counter that only ever goes up is the cheapest honest answer: {@code Roads} holds one graph
     * and the revision it was built at, and a settlement registering anywhere invalidates it. It
     * cannot disagree with the table it counts, because it is incremented by the only method that
     * changes one.
     */
    public int revision() {
        return revision;
    }

    // --- persistence ---------------------------------------------------------------------------

    public void save(CompoundTag root) {
        ListTag list = new ListTag();
        for (Settlement settlement : byId.values()) {
            list.add(Settlement.CODEC.encodeStart(NbtOps.INSTANCE, settlement)
                    .getOrThrow(error -> new IllegalStateException(
                            "Cannot encode settlement " + settlement.id() + ": " + error)));
        }
        root.put(KEY_LIST, list);
        root.putInt(KEY_NEXT_ID, nextId);
    }

    /**
     * Reads the settlement table out of a registry tag.
     *
     * <p>A tag written before schema 3 simply has no {@code settlements} key, which reads as an
     * empty world of settlements and is exactly right — nothing had detected any yet. That absence
     * is half of what the schema 2 → 3 migration means, and {@code NpcSchemaTest} pins it down.
     *
     * @return how many settlement records could not be read. The caller must treat a non-zero
     * count the same way it treats an unreadable persona: refuse to write the file back, rather
     * than saving a version of the world with those settlements quietly missing.
     */
    public int readFrom(CompoundTag root) {
        ListTag list = root.getList(KEY_LIST, Tag.TAG_COMPOUND);
        int unreadable = 0;

        for (int i = 0; i < list.size(); i++) {
            Optional<Settlement> parsed = Settlement.CODEC.parse(NbtOps.INSTANCE, list.getCompound(i))
                    .resultOrPartial(error -> Namesake.LOGGER.error(
                            "Unreadable settlement record: {}", error));
            if (parsed.isEmpty()) {
                unreadable++;
                continue;
            }
            put(parsed.get());
        }

        // put() already pushed nextId past the highest id it saw, so a stored counter can only
        // raise it — which matters when the highest-numbered settlement was removed.
        nextId = Math.max(nextId, root.getInt(KEY_NEXT_ID));
        return unreadable;
    }
}
