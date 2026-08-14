package net.namesake.social;

import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.namesake.Namesake;

import java.util.ArrayList;
import java.util.Collections;
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

    /**
     * One slot of a ring: a deed and the id it is addressed by.
     *
     * <p><b>The id is carried rather than recomputed, and session 08 is what made that matter.</b>
     * This class's own note claimed a lookup was "a linear walk of at most {@link #RING_CAPACITY}
     * long comparisons" — which was true of the comparison and false of what it took to get there,
     * because {@link Deed#id()} is a sixty-four bit mix of eight fields and was being run once per
     * slot per walk. That cost nothing while the only caller was an emit, at most thirteen times in
     * the tick something happened. {@link Gossip} asks the same question of every resident on every
     * drain, which turned it into about ninety microseconds of one tick in every two hundred and
     * fifty; measured through the headless simulation, it was three quarters of the run.
     *
     * <p>Derived and never persisted, so it is not a cache in the sense session 03 deleted
     * {@code Settlement.culture} for: it cannot disagree with the deed it sits beside, because it is
     * filled in at the same moment from the same record and neither is ever mutated.
     */
    private record Held(long id, Deed deed) {

        static Held of(Deed deed) {
            return new Held(deed.id(), deed);
        }
    }

    /**
     * A list rather than a {@code Deque}, from session 08.
     *
     * <p>It is still a ring in every sense that matters — oldest first, appended at the end, oldest
     * out on overflow — and the change bought one thing: {@link #remember} can now replace an entry
     * <i>in its own slot</i> when a better-attested copy of the same event turns up. A
     * {@code Deque} would have had to be rebuilt to do that, and the rule the replacement implements
     * is precisely that the ring's order must not move.
     */
    private final Map<UUID, List<Held>> byHolder = new LinkedHashMap<>();

    // --- reads -----------------------------------------------------------------------------------

    /** Everything this NPC remembers, <b>oldest first</b>. Unmodifiable; write through the registry. */
    public List<Deed> of(UUID holder) {
        List<Held> ring = byHolder.get(holder);
        return ring == null ? List.of() : ring.stream().map(Held::deed).toList();
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
        return slotOf(holder, deedId) >= 0;
    }

    /** Where this deed sits in the holder's ring, or −1. */
    private int slotOf(UUID holder, long deedId) {
        List<Held> ring = byHolder.get(holder);
        if (ring == null) {
            return -1;
        }
        for (int slot = 0; slot < ring.size(); slot++) {
            if (ring.get(slot).id() == deedId) {
                return slot;
            }
        }
        return -1;
    }

    /** How many deeds are held in total, across every ring. */
    public int size() {
        int total = 0;
        for (List<Held> ring : byHolder.values()) {
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
        byHolder.forEach((holder, ring) -> copy.put(holder, of(holder)));
        return Collections.unmodifiableMap(copy);
    }

    /** What this NPC believes about one particular event, if they have heard of it at all. */
    public java.util.Optional<Deed> held(UUID holder, long deedId) {
        int slot = slotOf(holder, deedId);
        return slot < 0
                ? java.util.Optional.empty()
                : java.util.Optional.of(byHolder.get(holder).get(slot).deed());
    }

    // --- writes ----------------------------------------------------------------------------------

    /**
     * Appends a deed to one NPC's ring. {@code DESIGN.md} §4 step 3.
     *
     * <p><b>Returns false when nothing changed, and every caller must care.</b> A duplicate is not an
     * error and is usually not a write: {@code NpcRegistry.remember} marks the file dirty only when
     * this says something happened, so a player repeating themselves does not make Minecraft rewrite
     * the whole registry on the next autosave. That is {@code NpcRegistry.bind}'s rule, for the same
     * reason.
     *
     * <h2>Which copy survives when two disagree — ruled at session 08</h2>
     *
     * <p>Session 06 left this open by name. {@link Deed#id()} deliberately excludes confidence, so
     * <b>a story retold is the same deed known less well</b> and dedupes against the deed it retells
     * rather than becoming a second row for one murder — which means two copies of one event can
     * meet here holding different numbers. Until now the first one in simply won.
     *
     * <p><b>The ruling is: the better-attested copy wins, and it does not move.</b> Two halves, and
     * both are load-bearing.
     *
     * <ul>
     *   <li><b>Better attested wins.</b> A memory should be the best account of an event a person
     *       actually has. Somebody who is told about a killing at seventy and then <i>watches</i> a
     *       hundred-confidence copy of it arrive knows it first-hand from that moment; keeping the
     *       rumour would be recording that they only heard about a thing they saw.</li>
     *   <li><b>It does not move.</b> Session 06's reason stands unchanged and is the half that
     *       actually protects the ring: refreshing a slot would let a retelling push first-hand
     *       memories out of a ring simply by being repeated. The entry is replaced <i>where it
     *       already sits</i>, so the order a villager remembers things in is decided by when they
     *       happened and never by when somebody last mentioned them.</li>
     * </ul>
     *
     * <p><b>What it costs, plainly, because the cheap answer was to rule the other way.</b> This
     * makes the method a read-modify-write rather than a read-and-maybe-append, and it opens a door
     * content addressing had closed: a retelling can now touch a ring. The door only opens
     * <i>upward</i> — a copy that knows less changes nothing at all, and the two ways into this
     * method are an emit (first-hand, a hundred) and a drain (strictly less than whatever it was
     * retold from) — so nothing gossip does can ever degrade a memory. And no path in the mod as it
     * stands produces the case at all: a deed reaches its witnesses at emit and enters the deque
     * afterwards, so first-hand always arrives first. That is stated rather than relied on. The rule
     * is here so that session 10's second settlement and session 16's NPC actors meet a ring that
     * already behaves correctly, instead of meeting one that behaves correctly by accident.
     *
     * <p>Reaching this method directly appends a deed that exists until the world reloads and then
     * does not. Write through {@code NpcRegistry.remember}, which is the only door that marks the
     * file dirty — the same discipline {@link Bonds#put} carries, and for the same reason.
     *
     * @return true if the ring changed
     */
    public boolean remember(UUID holder, Deed deed) {
        long deedId = deed.id();
        int slot = slotOf(holder, deedId);
        if (slot >= 0) {
            List<Held> ring = byHolder.get(holder);
            if (deed.confidence() <= ring.get(slot).deed().confidence()) {
                return false;
            }
            ring.set(slot, new Held(deedId, deed));
            return true;
        }
        List<Held> ring = byHolder.computeIfAbsent(holder, key -> new ArrayList<>(RING_CAPACITY));
        ring.add(new Held(deedId, deed));
        while (ring.size() > RING_CAPACITY) {
            ring.remove(0);
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
        for (Map.Entry<UUID, List<Held>> holder : byHolder.entrySet()) {
            if (holder.getValue().isEmpty()) {
                continue;
            }
            CompoundTag entry = new CompoundTag();
            entry.putIntArray(KEY_HOLDER, UUIDUtil.uuidToIntArray(holder.getKey()));
            ListTag ring = new ListTag();
            for (Held held : holder.getValue()) {
                Deed deed = held.deed();
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
