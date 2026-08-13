package net.namesake.social;

import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.namesake.Namesake;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * What each NPC remembers: a <b>32-entry ring of {@link Deed}s per persona</b>, oldest first.
 *
 * <p>{@code DESIGN.md} §3 sizes it at ~24 B a deed and ~768 B a ring, and {@code WORKPLAN.md}
 * session 06 asks for exact dedupe on {@code (npcUuid, deedId)} — <i>possible only because deeds are
 * structs, not sentences</i>. Both of those are properties of this class, and the tests that hold
 * them are in {@code MemoriesTest}.
 *
 * <h2>Decision 1: where a ring lives — a side table, not a field on {@code Persona}</h2>
 *
 * <p>Keyed by persona id in a table of its own, exactly as {@link Bonds} is. Three reasons, and the
 * third is the one that decided it.
 *
 * <ol>
 *   <li><b>Bonds set the precedent one session ago and the shape is identical.</b> Both are
 *       per-persona social state, written by {@link DeedBus} on emit and read by sessions 09 and 11.
 *       Putting one beside the persona and one inside it would be two answers to one question, which
 *       is the documents-disagreeing problem {@code CLAUDE.md} names, in code.</li>
 *   <li><b>{@code Persona} is identity, and it is rebuilt whole on every write.</b> Every wither on
 *       it — {@code withTrait}, {@code placed}, {@code withTraits} — constructs a new record, and its
 *       {@code equals} is hand-written because {@code byte[] traits} would otherwise compare by
 *       identity. A ring on that record means up to thirteen record rebuilds carrying a thirty-two
 *       slot array per deed, and it means "the fields survived the reload" — session 01's exit
 *       criterion, still asserted by the harness — quietly becomes a claim about history rather than
 *       about identity.</li>
 *   <li><b>The load path, which is where the real difference is.</b> {@code Persona.CODEC} is parsed
 *       per record and a parse failure counts that whole record unreadable, which turns the registry
 *       read-only. A single malformed deed inside a persona would therefore take that person's name,
 *       culture, household and traits with it. As a table of its own it is counted in its own lane,
 *       exactly as settlements and bonds already are, and the worst a bad deed can do is cost one
 *       villager one memory.</li>
 * </ol>
 *
 * <h2>Decision 2: which file — the same one, under the same schema version</h2>
 *
 * <p>Inside {@code namesake_npcs.dat} and {@code NpcSchema}, on the argument session 03 made for
 * settlements and session 05 re-made for bonds. A deed references a persona and a settlement by id.
 * Two files torn apart by a crash between two writes do not produce a missing file; they produce a
 * save that loads, in which a village remembers things about people who are no longer in it.
 *
 * <p><b>The size counter-argument is real this time and was measured rather than waved away.</b> The
 * table is bigger than the bond table by two orders of magnitude: four hundred personas each holding
 * a full ring is 12,800 deeds. {@code MemoriesTest} builds exactly that and reports what it costs as
 * NBT, and holds it to a ceiling so a future session that widens {@link Deed} or raises
 * {@link #RING_CAPACITY} finds out at build time. Two things bound it in practice — a ring only
 * fills for a villager a player has actually done thirty-two distinct things in front of, and
 * {@link Deed#id()} makes a day of repeating yourself one entry rather than many.
 */
public final class Memories {

    /**
     * How many deeds one NPC keeps. {@code DESIGN.md} §3.
     *
     * <p>On overflow the <b>oldest</b> goes, which is the plain reading of "newest 32" and is
     * deliberately not clever: nothing here weighs a killing against a gift when deciding what to
     * forget. That would be inventing a policy the design has not ruled, and {@link Deed#id()}
     * already removes the pressure that would make one necessary — the eviction a player can
     * generate is bounded by <i>distinct</i> deeds, not by how many times they click.
     */
    public static final int RING_CAPACITY = 32;

    private static final String KEY_LIST = "memories";
    private static final String KEY_HOLDER = "holder";
    private static final String KEY_RING = "ring";

    private final Map<UUID, Deque<Deed>> byHolder = new LinkedHashMap<>();

    // --- reads -----------------------------------------------------------------------------------

    /** Everything this NPC remembers, <b>oldest first</b>. Unmodifiable; write through the registry. */
    public List<Deed> of(UUID holder) {
        Deque<Deed> ring = byHolder.get(holder);
        return ring == null ? List.of() : List.copyOf(ring);
    }

    /**
     * Whether this NPC already holds this exact deed.
     *
     * <p>A linear walk of at most {@link #RING_CAPACITY} long comparisons rather than a companion
     * index. Thirty-two comparisons is cheaper than the map that would avoid them, it cannot fall out
     * of step with the ring it describes, and it runs at most thirteen times in the tick a deed is
     * emitted and never on any other tick at all.
     */
    public boolean remembers(UUID holder, long deedId) {
        Deque<Deed> ring = byHolder.get(holder);
        if (ring == null) {
            return false;
        }
        for (Deed held : ring) {
            if (held.id() == deedId) {
                return true;
            }
        }
        return false;
    }

    /** How many deeds are held in total, across every ring. */
    public int size() {
        int total = 0;
        for (Deque<Deed> ring : byHolder.values()) {
            total += ring.size();
        }
        return total;
    }

    /** How many NPCs remember anything at all. */
    public int holders() {
        return byHolder.size();
    }

    public Map<UUID, List<Deed>> all() {
        Map<UUID, List<Deed>> copy = new LinkedHashMap<>();
        byHolder.forEach((holder, ring) -> copy.put(holder, List.copyOf(ring)));
        return Collections.unmodifiableMap(copy);
    }

    // --- writes ----------------------------------------------------------------------------------

    /**
     * Appends a deed to one NPC's ring. {@code DESIGN.md} §4 step 3.
     *
     * <p><b>Returns false when nothing changed, and every caller must care.</b> A duplicate is not an
     * error and is not a write: {@code NpcRegistry.remember} marks the file dirty only when this says
     * something happened, so a player repeating themselves does not make Minecraft rewrite the whole
     * registry on the next autosave. That is {@code NpcRegistry.bind}'s rule, for the same reason.
     *
     * <p><b>A duplicate does not move the deed it duplicates, either.</b> The entry keeps its place
     * in the ring rather than being refreshed to the newest slot. Being told a thing again is not the
     * thing happening again, and refreshing would let session 08's gossip push first-hand memories
     * out of a ring by retelling them.
     *
     * <p>Reaching this method directly appends a deed that exists until the world reloads and then
     * does not. Write through {@code NpcRegistry.remember}, which is the only door that marks the
     * file dirty — the same discipline {@link Bonds#put} carries, and for the same reason.
     *
     * @return true if the ring changed
     */
    public boolean remember(UUID holder, Deed deed) {
        long deedId = deed.id();
        if (remembers(holder, deedId)) {
            return false;
        }
        Deque<Deed> ring = byHolder.computeIfAbsent(holder, key -> new ArrayDeque<>(RING_CAPACITY));
        ring.addLast(deed);
        while (ring.size() > RING_CAPACITY) {
            ring.removeFirst();
        }
        return true;
    }

    /** Drops everything one NPC remembered. Called when their persona is removed. */
    public boolean forget(UUID holder) {
        return byHolder.remove(holder) != null;
    }

    // --- persistence -------------------------------------------------------------------------

    /**
     * One entry per holder, each carrying its ring in order.
     *
     * <p>Grouped by holder rather than written as a flat list of (holder, deed) pairs the way
     * {@link Bonds} is, because the two tables have different shapes: a bond table is wide and
     * shallow — many subjects, one row each — and a memory table is narrow and deep. Flattening would
     * repeat a 16-byte holder id thirty-two times per villager to buy nothing, and the ring's order
     * is data here rather than an artefact, so it wants a list that keeps it.
     */
    public void save(CompoundTag root) {
        ListTag list = new ListTag();
        for (Map.Entry<UUID, Deque<Deed>> holder : byHolder.entrySet()) {
            if (holder.getValue().isEmpty()) {
                continue;
            }
            CompoundTag entry = new CompoundTag();
            entry.putIntArray(KEY_HOLDER, UUIDUtil.uuidToIntArray(holder.getKey()));
            ListTag ring = new ListTag();
            for (Deed deed : holder.getValue()) {
                ring.add(Deed.CODEC.encodeStart(NbtOps.INSTANCE, deed)
                        .getOrThrow(error -> new IllegalStateException(
                                "Cannot encode deed " + deed + " of " + holder.getKey() + ": " + error)));
            }
            entry.put(KEY_RING, ring);
            list.add(entry);
        }
        root.put(KEY_LIST, list);
    }

    /**
     * Reads the memory table out of a registry tag.
     *
     * <p><b>A tag written before schema 5 has no {@code memories} key, and that must read as "nobody
     * has seen anything yet" rather than as damage.</b> That absence <i>is</i> the schema 4 → 5
     * migration — the same free, additive shape the bond table was at schema 4 and the settlement
     * table at schema 3 — and it is free because this method was written to return zero unreadable
     * records for an absent list rather than to fail on one. {@code NpcSchemaTest} pins it, because
     * both failure directions are silent: read as damage and the registry goes read-only, so a world
     * with memories in it stops saving them; read as zero when the key was genuinely unreadable and
     * every ring in the world is dropped and the file rewritten without them.
     *
     * <p><b>A ring longer than {@link #RING_CAPACITY} is truncated to its newest entries rather than
     * refused.</b> The bound is this build's, not the file's, and the honest response to a save
     * written by a build that kept more is to keep the newest ones this one can — the same answer
     * overflow gives. Refusing would turn a survivable difference into a read-only world.
     *
     * @return how many memory records could not be read. Non-zero must make the registry read-only,
     * exactly as an unreadable persona does.
     */
    public int readFrom(CompoundTag root) {
        ListTag list = root.getList(KEY_LIST, Tag.TAG_COMPOUND);
        int unreadable = 0;

        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);
            if (!entry.contains(KEY_HOLDER, Tag.TAG_INT_ARRAY)) {
                Namesake.LOGGER.error("Memory record {} has no holder id", i);
                unreadable++;
                continue;
            }
            UUID holder = UUIDUtil.uuidFromIntArray(entry.getIntArray(KEY_HOLDER));
            ListTag ring = entry.getList(KEY_RING, Tag.TAG_COMPOUND);
            for (int slot = 0; slot < ring.size(); slot++) {
                int index = slot;
                Deed deed = Deed.CODEC.parse(NbtOps.INSTANCE, ring.getCompound(slot))
                        .resultOrPartial(error -> Namesake.LOGGER.error(
                                "Unreadable deed in {}'s ring at slot {}: {}", holder, index, error))
                        .orElse(null);
                if (deed == null) {
                    unreadable++;
                    continue;
                }
                remember(holder, deed);
            }
        }
        return unreadable;
    }
}
