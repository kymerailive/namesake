package net.namesake.social;

import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.namesake.Namesake;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Every bond in the world, keyed on <b>(the NPC who holds it → whoever it is about)</b>.
 *
 * <h2>Decision 1: what a bond is keyed on — ruled at the open of session 05</h2>
 *
 * <p>Both keys are bare UUIDs, so the <b>shape</b> is general: an NPC can in principle hold a bond
 * about another NPC. The <b>population</b> is not — {@code NpcRegistry.putBond} refuses a bond whose
 * subject is a persona this world knows about, and says so loudly.
 *
 * <p>That split is the point, and it is what the two obvious answers each get wrong. Keying strictly
 * on (npc → player) makes session 16's grievance engine a schema migration, because grievances are
 * between villagers and every bond ever written would need re-keying. Letting NPC-to-NPC bonds
 * "fall out for free" is worse: witnesses are capped at twelve per deed, so a village going about
 * its business would accumulate bonds toward n² of itself, and not one of them would be read by an
 * {@code if} statement before session 16. A persisted social value with no consumer is precisely
 * what {@code DESIGN.md} §1 forbids, and four hundred personas is 160,000 of them.
 *
 * <p><b>What it costs, plainly.</b> Three things.
 * <ol>
 *   <li>A bare UUID cannot say whether it is a player or a persona by looking at it. Today that is
 *       free because everything stored is a player; at session 16 something will have to
 *       disambiguate, and the cheapest honest answer will be to ask the registry — which is exactly
 *       what the guard already does, in the other direction.</li>
 *   <li>Because the format allows what the guard forbids, the guard is the only thing standing
 *       between here and the n² table. It is one method, it logs rather than throwing, and it has
 *       been reverted and watched to fail.</li>
 *   <li>The saved shape is a flat list of triples rather than a nested map. It costs 32 bytes of
 *       key per bond and buys a load path with no partially-built intermediate state.</li>
 * </ol>
 *
 * <h2>Decision 2: where bonds live — same file, same schema version</h2>
 *
 * <p>Inside {@code namesake_npcs.dat}, under {@link net.namesake.npc.NpcSchema}, for the reason
 * session 03 gave for settlements and which applies here with more force. A bond references a
 * persona by id. Two files can be torn apart by a crash between two writes, and the result is not a
 * missing file — it is a save that loads, where every villager in a village has forgotten one
 * particular player, or remembers a player against personas that no longer exist. One file, one
 * version number that cannot disagree with itself, one load path to get right.
 *
 * <p>The counter-argument would be size, and it does not hold: the table is bounded by
 * (personas × players who have done something), which at {@code DESIGN.md} §8's four hundred
 * records and a single player is four hundred rows of about fifty bytes.
 */
public final class Bonds {

    private static final String KEY_LIST = "bonds";
    private static final String KEY_HOLDER = "holder";
    private static final String KEY_ABOUT = "about";
    private static final String KEY_BOND = "bond";

    private final Map<UUID, Map<UUID, Bond>> byHolder = new LinkedHashMap<>();

    /** The bond exactly as stored, if there is one. Not brought up to date — see {@link #at}. */
    public Optional<Bond> stored(UUID holder, UUID about) {
        Map<UUID, Bond> held = byHolder.get(holder);
        return held == null ? Optional.empty() : Optional.ofNullable(held.get(about));
    }

    /**
     * The bond as it stands on {@code day}, decayed, and {@link Bond#fresh} if there is none.
     *
     * <p>Never null and never absent: "they have no feelings about you either way" is a bond at
     * zero, not a missing row, and every caller downstream would otherwise have to invent that
     * answer for itself.
     *
     * <p>Deliberately does not write the decayed value back. A read that stores would mark the
     * registry dirty every time anything looked at a villager, and Minecraft would rewrite the
     * whole file. The stored value plus {@code lastSeenDay} is the authority; this is a view of it,
     * exactly as a villager's name is a view of the fields it derives from.
     */
    public Bond at(UUID holder, UUID about, int day) {
        return stored(holder, about).map(bond -> bond.decayedTo(day)).orElseGet(() -> Bond.fresh(day));
    }

    /** Everything this NPC feels about anyone. Unmodifiable; write through the registry. */
    public Map<UUID, Bond> of(UUID holder) {
        Map<UUID, Bond> held = byHolder.get(holder);
        return held == null ? Map.of() : Collections.unmodifiableMap(held);
    }

    public Map<UUID, Map<UUID, Bond>> all() {
        return Collections.unmodifiableMap(byHolder);
    }

    /** How many bonds exist in total, across every holder. */
    public int size() {
        int total = 0;
        for (Map<UUID, Bond> held : byHolder.values()) {
            total += held.size();
        }
        return total;
    }

    public int holders() {
        return byHolder.size();
    }

    /**
     * Writes a bond.
     *
     * <p>Package-visible discipline is not possible here — {@code NpcRegistry} is in another
     * package — so the door that actually enforces the population rule is
     * {@code NpcRegistry.putBond}, which is also the only one that marks the file dirty. Reaching
     * this method directly writes a bond that exists until the world reloads and then does not,
     * which is session 01's defect wearing a new hat.
     */
    public void put(UUID holder, UUID about, Bond bond) {
        byHolder.computeIfAbsent(holder, key -> new LinkedHashMap<>()).put(about, bond);
    }

    /** Drops everything one NPC felt about anyone. Called when their persona is removed. */
    public boolean forget(UUID holder) {
        return byHolder.remove(holder) != null;
    }

    public boolean remove(UUID holder, UUID about) {
        Map<UUID, Bond> held = byHolder.get(holder);
        if (held == null || held.remove(about) == null) {
            return false;
        }
        if (held.isEmpty()) {
            byHolder.remove(holder);
        }
        return true;
    }

    // --- persistence -------------------------------------------------------------------------

    public void save(CompoundTag root) {
        ListTag list = new ListTag();
        for (Map.Entry<UUID, Map<UUID, Bond>> holder : byHolder.entrySet()) {
            for (Map.Entry<UUID, Bond> about : holder.getValue().entrySet()) {
                CompoundTag entry = new CompoundTag();
                entry.putIntArray(KEY_HOLDER, UUIDUtil.uuidToIntArray(holder.getKey()));
                entry.putIntArray(KEY_ABOUT, UUIDUtil.uuidToIntArray(about.getKey()));
                entry.put(KEY_BOND, Bond.CODEC.encodeStart(NbtOps.INSTANCE, about.getValue())
                        .getOrThrow(error -> new IllegalStateException(
                                "Cannot encode bond " + holder.getKey() + " -> " + about.getKey()
                                        + ": " + error)));
                list.add(entry);
            }
        }
        root.put(KEY_LIST, list);
    }

    /**
     * Reads the bond table out of a registry tag.
     *
     * <p><b>A tag written before schema 4 has no {@code bonds} key at all, and that must read as an
     * empty table rather than as damage.</b> That absence <i>is</i> the schema 3 → 4 migration —
     * there is no value to rewrite, only an assumption to get right — and it is the same shape as
     * the settlement half of schema 3. {@code NpcSchemaTest} pins it, because the failure mode is
     * silent in both directions: read as damage, the registry goes read-only and the world stops
     * saving; read as zero when the key was really unreadable, every bond in the world is lost and
     * the file is rewritten without them.
     *
     * @return how many bond records could not be read. Non-zero must make the registry read-only,
     * exactly as an unreadable persona does.
     */
    public int readFrom(CompoundTag root) {
        ListTag list = root.getList(KEY_LIST, Tag.TAG_COMPOUND);
        int unreadable = 0;

        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);
            if (!entry.contains(KEY_HOLDER, Tag.TAG_INT_ARRAY) || !entry.contains(KEY_ABOUT, Tag.TAG_INT_ARRAY)) {
                Namesake.LOGGER.error("Bond record {} has no holder or subject id", i);
                unreadable++;
                continue;
            }
            Optional<Bond> parsed = Bond.CODEC.parse(NbtOps.INSTANCE, entry.getCompound(KEY_BOND))
                    .resultOrPartial(error -> Namesake.LOGGER.error("Unreadable bond record: {}", error));
            if (parsed.isEmpty()) {
                unreadable++;
                continue;
            }
            put(UUIDUtil.uuidFromIntArray(entry.getIntArray(KEY_HOLDER)),
                    UUIDUtil.uuidFromIntArray(entry.getIntArray(KEY_ABOUT)),
                    parsed.get());
        }
        return unreadable;
    }
}
