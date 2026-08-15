package net.namesake.social;

import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.namesake.Namesake;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * What each NPC remembers: a <b>128-entry ring of {@link Deed}s per persona</b>, oldest first.
 *
 * <p>{@code WORKPLAN.md} session 06 asks for exact dedupe on {@code (npcUuid, deedId)} — <i>possible
 * only because deeds are structs, not sentences</i>. That is still the property this class is built
 * on. Session 09 built the owner's three memory-depth routes and session 12 took one of them back
 * out: a memory says <i>how many times</i>, there are four times as many of them, and the format
 * they are stored in had to change to make that fit. What it no longer says is <i>which</i> object —
 * {@code Deed.item}'s rule 5 exemption fell due at the close of session 12 with no non-display
 * consumer to name, and the field is gone at schema 8.
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
 *       identity. A ring on that record means up to thirteen record rebuilds carrying a
 *       hundred-and-twenty-eight slot array per deed, and it means "the fields survived the reload" —
 *       session 01's exit criterion, still asserted by the harness — quietly becomes a claim about
 *       history rather than about identity.</li>
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
 * <p><b>The size counter-argument bit at session 09, exactly where session 08 predicted it would.</b>
 * Four hundred personas each holding a full ring is 51,200 deeds at this capacity. Session 06
 * measured the readable-codec form at 122 B a deed, which put that worst case at 6.26 MB of NBT
 * against a 2 MB ceiling — <i>both</i> of {@code MemoriesTest}'s ceilings red, and the note on that
 * test already said the answer to a red ceiling is a decision rather than a bigger number. This is
 * the decision: a fixed-width packed ring behind an actor palette. See {@link #save}.
 */
public final class Memories {

    /**
     * How many deeds one NPC keeps. <b>Ruled by the owner at the close of session 08: 32 → 128.</b>
     *
     * <p>The evidence pointed at the other two routes rather than this one, and the ledger says so:
     * after a hundred in-game days the deepest ring held thirty-two slots and <b>three distinct
     * sentences</b>, so it was shallow because twenty of its rows said the same thing rather than
     * because it ran out of room. The owner ruled all three routes anyway, and the sequencing note
     * was to do the two cheap ones first and raise this last — which is what happened, because this
     * is the one that costs a format and a migration.
     *
     * <p>What it buys is measured rather than assumed: session 07's saturating player fills a
     * thirty-two slot ring in twenty-two in-game days and session 08's gossip took that to thirteen.
     * At a hundred and twenty-eight the same player reaches back into the second month.
     */
    public static final int RING_CAPACITY = 128;

    /**
     * The largest a repeat count can say. Saturating rather than wrapping.
     *
     * <p>One unsigned byte in the packed record. {@code MemoriesTest.theRingIsNotGrindable} pushes
     * five hundred identical gifts through one slot, so this is reached in practice by a test before
     * it is reached by a player, and a wrap would turn "you fed me two hundred and fifty-six times"
     * into "you fed me once".
     */
    public static final int MAX_REPEATS = 255;

    /**
     * How much of a repeat count the eviction policy will actually count.
     *
     * <p>Eight, which is {@link Bond#DAILY_CAP} — the most good a person can do another in one
     * in-game day. A slot's repeats are all from one day by construction, because {@link Deed#id()}
     * carries {@code gameDay}, so "as memorable as a day can get" is the right ceiling and it is a
     * number this codebase already has rather than one chosen here.
     *
     * <p><b>It is also the bound that stops the repeat count reopening the grind.</b> Without a cap,
     * five hundred identical gifts would be the most strongly held benign memory a villager has, for
     * ever — which is the ring's ungrindability lost through a door {@link Deed#id()} does not
     * watch, exactly as session 06 described the original failure.
     */
    static final int REPEATS_COUNTED = 8;

    /**
     * What a harmful deed is worth against a kindness when a slot has to go.
     *
     * <p>One more than {@link #REPEATS_COUNTED}, so <b>any</b> harmful deed outranks <b>every</b>
     * kindness however often it happened. That is the rule stated as arithmetic rather than as a
     * special case, and it is what session 07 asked for by name: <i>nothing in this run gave a
     * villager a killing to keep, so nothing has yet tested whether thirty-two subsequent gifts push
     * one out.</i> Now something does, and the answer is no.
     */
    static final int HARM_RANK = REPEATS_COUNTED + 1;

    private static final String KEY_LIST = "memories";
    private static final String KEY_HOLDER = "holder";
    private static final String KEY_RING = "ring";
    private static final String KEY_ACTORS = "memoryActors";

    /**
     * One packed slot, in bytes. <b>Fixed width, which is the whole point of it.</b>
     *
     * <pre>
     *   0  short  deed type
     *   2  int    actor,   as an index into the actor palette
     *   6  int    subject, as an index into the actor palette
     *  10  int    settlement
     *  14  int    game day
     *  18  byte   severity
     *  19  byte   confidence
     *  20  byte   repeats, unsigned, 1..255
     * </pre>
     *
     * <p><b>Twenty-one bytes at session 12, down from twenty-five, and the item palette is gone with
     * them.</b> {@code Deed.item}'s rule 5 exemption fell due and no non-display consumer appeared;
     * see {@link Deed} for the epitaph. The saving is incidental — the reason is the rule — but it is
     * measured beside the old number in the session 12 log, because a format change that nobody
     * measured is how the tag-tree ceiling gets found by a player instead.
     *
     * <p><b>Palette indices rather than the values, and that is where the saving actually is.</b> A
     * raw pair of UUIDs is thirty-two of a forty-seven byte record — two thirds of the table — and a
     * ring holds deeds by a handful of actors about a handful of subjects. Four bytes rather than two
     * for an index is deliberate: two would cap a world at 32,767 distinct participants, which is a
     * limit nobody would ever meet and a corruption nobody would ever diagnose, and the difference at
     * the measured worst case is under a tenth of the ceiling.
     */
    static final int RECORD_BYTES = 21;

    /**
     * One slot of a ring: a deed, the id it is addressed by, and how many times it happened.
     *
     * <p><b>The id is carried rather than recomputed, and session 08 is what made that matter.</b>
     * This class's own note claimed a lookup was "a linear walk of at most {@link #RING_CAPACITY}
     * long comparisons" — which was true of the comparison and false of what it took to get there,
     * because {@link Deed#id()} is a sixty-four bit mix of eight fields and was being run once per
     * slot per walk. That cost nothing while the only caller was an emit, at most thirteen times in
     * the tick something happened. {@link Gossip} asks the same question of every resident on every
     * drain, which turned it into about ninety microseconds of one tick in every two hundred and
     * fifty; measured through the headless simulation, it was three quarters of the run. Derived and
     * never persisted, so it is not the kind of cache session 03 deleted {@code Settlement.culture}
     * for: it cannot disagree with the deed beside it, because both are filled in from the same
     * record at the same moment and neither is ever mutated.
     *
     * <p><b>{@code repeats} is persisted, and it is a social value with a ledger entry of its own.</b>
     * It is session 09's second memory-depth route — <i>"you fed me, nine times, that day"</i> — and
     * it restores the magnitude that content addressing collapses without giving any of the
     * grindability back. Its non-display consumer is {@link #evictionWeight}: which memory survives an
     * overflow is an {@code if} statement with a consequence, and session 07 flagged eviction as the
     * one question its numbers could not settle.
     *
     * <p>It lives here rather than on {@link Deed} because it is a property of <i>this holder's</i>
     * memory rather than of the event: two villagers who watched the same afternoon can have seen
     * different amounts of it, and a rumour carries the event without carrying anybody's count of it.
     */
    public record Slot(long id, Deed deed, int repeats) {

        /** A memory of something that has happened once. */
        public static final int ONCE = 1;

        public Slot {
            repeats = Math.max(ONCE, Math.min(MAX_REPEATS, repeats));
        }

        static Slot of(Deed deed) {
            return new Slot(deed.id(), deed, ONCE);
        }

        /**
         * The same memory, one occurrence heavier.
         *
         * <p><b>Session 09 to 12 this method also dropped the object when two occurrences disagreed
         * about it</b>, on the rule {@code DESIGN.md} §2 states for gossip — <i>distorts, never
         * lies</i> — because a slot is one memory by {@link Deed#id()} and a day of handing over a
         * loaf and then an apple could name neither. That rule survives the field it was written
         * for: it is the same rule the blur runs on, and it is stated on {@link Deed#UNKNOWN_ACTOR}.
         * With no object to disagree about, a repeat is now nothing but a count.
         */
        Slot again(Deed repeat) {
            return new Slot(id, deed, Math.min(MAX_REPEATS, repeats + 1));
        }

        /** The same memory, better attested. Session 08's rule; the count is not an account. */
        Slot attestedBy(Deed better) {
            return new Slot(id, better, repeats);
        }

        /** True when this is the whole of what happened — the state a repeat count exists to deny. */
        public boolean happenedOnce() {
            return repeats == ONCE;
        }
    }

    /**
     * A list rather than a {@code Deque}, from session 08.
     *
     * <p>It is still a ring in every sense that matters — oldest first, appended at the end — and the
     * change bought one thing: {@link #remember} can replace an entry <i>in its own slot</i> when a
     * better-attested copy of the same event turns up. A {@code Deque} would have had to be rebuilt
     * to do that, and the rule the replacement implements is precisely that the ring's order must not
     * move. Session 09 needs the same property for a second reason: eviction no longer takes the
     * head.
     */
    private final Map<UUID, List<Slot>> byHolder = new LinkedHashMap<>();

    // --- reads -----------------------------------------------------------------------------------

    /** Everything this NPC remembers, <b>oldest first</b>. Unmodifiable; write through the registry. */
    public List<Deed> of(UUID holder) {
        List<Slot> ring = byHolder.get(holder);
        return ring == null ? List.of() : ring.stream().map(Slot::deed).toList();
    }

    /**
     * The same ring with its counts attached, oldest first.
     *
     * <p>Separate from {@link #of} rather than replacing it, because most callers genuinely want the
     * deeds — the bond arithmetic, the propagation count, the gossip guard — and would have to strip
     * the slot back off. What wants a slot is anything that has an opinion about <i>how much</i>
     * happened: the eviction policy, and the row {@code /namesake debug deeds} prints.
     */
    public List<Slot> slotsOf(UUID holder) {
        List<Slot> ring = byHolder.get(holder);
        return ring == null ? List.of() : List.copyOf(ring);
    }

    /**
     * Whether this NPC already holds this exact deed.
     *
     * <p>A linear walk of at most {@link #RING_CAPACITY} long comparisons rather than a companion
     * index. It cannot fall out of step with the ring it describes, and it runs at most thirteen
     * times in the tick a deed is emitted and never on any other tick at all.
     */
    public boolean remembers(UUID holder, long deedId) {
        return slotOf(holder, deedId) >= 0;
    }

    /** Where this deed sits in the holder's ring, or −1. */
    private int slotOf(UUID holder, long deedId) {
        List<Slot> ring = byHolder.get(holder);
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
        for (List<Slot> ring : byHolder.values()) {
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

    /**
     * The ids of everything this NPC remembers, in ring order.
     *
     * <p>Exists so that anything counting who holds what can ask once per villager instead of
     * recomputing {@link Deed#id()} per slot per question. It is the same reason the slot carries
     * the id at all — see {@link Slot} — and it is the difference between a report that costs a
     * hundred-day run three quarters of its time and one that does not.
     */
    public long[] idsOf(UUID holder) {
        List<Slot> ring = byHolder.get(holder);
        if (ring == null) {
            return NO_IDS;
        }
        long[] ids = new long[ring.size()];
        for (int slot = 0; slot < ids.length; slot++) {
            ids[slot] = ring.get(slot).id();
        }
        return ids;
    }

    private static final long[] NO_IDS = new long[0];

    /** What this NPC believes about one particular event, if they have heard of it at all. */
    public java.util.Optional<Deed> held(UUID holder, long deedId) {
        int slot = slotOf(holder, deedId);
        return slot < 0
                ? java.util.Optional.empty()
                : java.util.Optional.of(byHolder.get(holder).get(slot).deed());
    }

    /** The whole slot, count included. {@link #held} is the common case and this is the full one. */
    public java.util.Optional<Slot> slotHeld(UUID holder, long deedId) {
        int slot = slotOf(holder, deedId);
        return slot < 0
                ? java.util.Optional.empty()
                : java.util.Optional.of(byHolder.get(holder).get(slot));
    }

    // --- writes ----------------------------------------------------------------------------------

    /**
     * Appends a deed to one NPC's ring. {@code DESIGN.md} §4 step 3.
     *
     * <p><b>Returns false when nothing changed, and every caller must care.</b>
     * {@code NpcRegistry.remember} marks the file dirty only when this says something happened, so a
     * player whose gift changes nothing does not make Minecraft rewrite the whole registry on the
     * next autosave. That is {@code NpcRegistry.bind}'s rule, for the same reason.
     *
     * <h2>Three things one arrival can be, and confidence is what tells them apart</h2>
     *
     * <ol>
     *   <li><b>A new event</b> — no slot holds this id. Appended, and something is evicted if the
     *       ring is full. See {@link #evictionWeight}.</li>
     *   <li><b>A better account of an event already held</b> — session 08's ruling, unchanged. The
     *       better-attested copy wins and it <i>does not move</i>: refreshing a slot would let a
     *       retelling push first-hand memories out of a ring simply by being repeated. The repeat
     *       count is carried across, because being told about a thing better is not it happening
     *       again.</li>
     *   <li><b>The same event, again</b> — session 09's addition, and the reason it is safe. A
     *       first-hand copy arriving at a slot that already holds a first-hand copy is a second
     *       occurrence: the same kind of deed, by the same person, to the same person, in the same
     *       place, on the same day. That is what {@code repeats} counts. <b>A retelling is not one</b>
     *       — session 06's sentence, unchanged: <i>being told a thing again is not the thing
     *       happening again</i> — and confidence is what discriminates them, because
     *       {@link Deed#retold()} strictly lowers it and nothing but an emit produces
     *       {@link Deed#FIRST_HAND}.</li>
     * </ol>
     *
     * <p><b>What case 3 costs, stated because session 06 guarded against it by hand.</b> That session
     * made a duplicate return false specifically so nine identical feedings would not mark the
     * registry dirty nine times. A repeat now genuinely is a change, so it does — and the cost is
     * near zero for a reason worth writing down rather than assuming: the first of those nine
     * feedings marks the registry dirty already, and {@code setDirty} on an already-dirty registry is
     * free. The only case that costs anything is a repeat arriving after an autosave has cleaned the
     * flag, and {@link #MAX_REPEATS} bounds even that.
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
            List<Slot> ring = byHolder.get(holder);
            Slot held = ring.get(slot);
            if (deed.confidence() > held.deed().confidence()) {
                ring.set(slot, held.attestedBy(deed));
                return true;
            }
            if (deed.confidence() != Deed.FIRST_HAND
                    || held.deed().confidence() != Deed.FIRST_HAND) {
                return false;
            }
            Slot again = held.again(deed);
            if (again.equals(held)) {
                // Saturated, and the object already agreed. Nothing to write and nothing to dirty.
                return false;
            }
            ring.set(slot, again);
            return true;
        }
        List<Slot> ring = byHolder.computeIfAbsent(holder, key -> new ArrayList<>(16));
        ring.add(Slot.of(deed));
        trimTo(ring, RING_CAPACITY);
        return true;
    }

    /**
     * <b>Which memory survives when a ring overflows.</b> Session 09's ruling, and the consumer that
     * makes {@link Slot#repeats()} a social value rather than a caption.
     *
     * <p>Session 07 flagged this as the one question its numbers could not settle: <i>nothing in that
     * run gave a villager a killing to keep, so nothing had tested whether thirty-two subsequent
     * gifts push one out.</i> Until session 09 the answer was yes — the ring took the head, which is
     * the plain reading of "newest 32" and is exactly the wrong answer to that question.
     *
     * <p>Two terms, and the order between them is the whole rule:
     *
     * <ul>
     *   <li><b>A harmful deed outranks every kindness, absolutely.</b> {@link #HARM_RANK} is one more
     *       than the most any repeat count can contribute, so no quantity of bread displaces a
     *       killing. A player cannot bury what they did under what they did afterwards.</li>
     *   <li><b>Within a class, what happened more is held harder</b>, up to
     *       {@link #REPEATS_COUNTED}. An afternoon of nine gifts is a stronger memory than one gift,
     *       which is the sentence the owner asked for — <i>you fed me, nine times, that day</i> —
     *       finally deciding something.</li>
     * </ul>
     *
     * <p><b>Ties go to the oldest, which is what keeps session 06's tests true.</b> Every deed in a
     * ring of single kindnesses weighs the same, so the victim is the head and the behaviour is
     * exactly what it was: the newest {@link #RING_CAPACITY} survive, in order. The policy only
     * departs from that where the old one was demonstrably wrong.
     *
     * <p>Deliberately not a function of {@link Deed#gameDay()} beyond the tie-break, and not of
     * {@link Deed#severity()}. Age is already carried by position; adding a decay term would make
     * eviction depend on when it is asked rather than on what the ring holds, and a ring is asked on
     * every emit.
     */
    static int evictionWeight(Slot slot) {
        int repeats = Math.min(slot.repeats(), REPEATS_COUNTED);
        return slot.deed().type().isHarmful() ? HARM_RANK + repeats : repeats;
    }

    /** Drops the weakest slots until the ring fits. Used by {@link #remember} and by {@link #readFrom}. */
    private static void trimTo(List<Slot> ring, int capacity) {
        while (ring.size() > capacity) {
            ring.remove(weakest(ring));
        }
    }

    /** The index of the least strongly held memory; ties go to the oldest, which is the lowest index. */
    private static int weakest(List<Slot> ring) {
        int victim = 0;
        int lowest = evictionWeight(ring.get(0));
        for (int slot = 1; slot < ring.size(); slot++) {
            int weight = evictionWeight(ring.get(slot));
            if (weight < lowest) {
                lowest = weight;
                victim = slot;
            }
        }
        return victim;
    }

    /** Drops everything one NPC remembered. Called when their persona is removed. */
    public boolean forget(UUID holder) {
        return byHolder.remove(holder) != null;
    }

    // --- persistence -------------------------------------------------------------------------

    /**
     * <b>A fixed-width packed ring behind two palettes.</b> Session 09's format, and the decision
     * {@code MemoriesTest}'s ceiling note has been asking for since session 06.
     *
     * <p>Session 06 wrote each deed as a {@code CompoundTag} through {@link Deed#CODEC} and measured
     * what that cost: <b>122 B a deed</b>, a third of it seven key names repeated twelve thousand
     * times. At thirty-two slots the worst case was 1.57 MB and it fitted a 2 MB ceiling. At the
     * hundred and twenty-eight the owner ruled it would have been <b>6.26 MB</b>, and session 06's
     * own note already said what to do about a red ceiling: <i>the fix is a decision — shorter keys,
     * a packed ring, or a smaller capacity — not a bigger number here.</i>
     *
     * <p>Two things are stored rather than one, and the extra one is why it fits:
     *
     * <ul>
     *   <li>the <b>actor palette</b>, every distinct participant in every ring, once. Two UUIDs is
     *       thirty-two bytes of a deed and a village's rings are full of the same few people;</li>
     *   <li>one <b>byte array per holder</b>, {@link #RECORD_BYTES} to a slot, in ring order.</li>
     * </ul>
     *
     * <p><b>There were three until session 12.</b> An item palette held every distinct object once,
     * with index 0 reserved for "no object" — and the field it existed for lost its rule 5 exemption
     * at the close of session 12 with no non-display consumer to name. Both the palette and the four
     * bytes of index per slot are gone at schema 8.
     *
     * <p><b>What is given up, said plainly.</b> A save file stops being readable with an NBT viewer,
     * which is a real loss — session 06 chose the long key names deliberately and the reasoning was
     * good. It is paid back where it still matters: {@link Gossip}'s deque is bounded by settlements
     * rather than by personas, so it keeps the readable form, and a stuck rumour is the thing anybody
     * actually opens a save to look at. {@code /namesake debug deeds} is the instrument for a ring.
     */
    public void save(CompoundTag root) {
        List<UUID> actors = new ArrayList<>();
        Map<UUID, Integer> actorIndex = new LinkedHashMap<>();

        ListTag list = new ListTag();
        for (Map.Entry<UUID, List<Slot>> holder : byHolder.entrySet()) {
            if (holder.getValue().isEmpty()) {
                continue;
            }
            byte[] packed = new byte[holder.getValue().size() * RECORD_BYTES];
            ByteBuffer buffer = ByteBuffer.wrap(packed);
            for (Slot slot : holder.getValue()) {
                Deed deed = slot.deed();
                buffer.putShort(deed.typeId());
                buffer.putInt(intern(deed.actor(), actors, actorIndex));
                buffer.putInt(intern(deed.subject(), actors, actorIndex));
                buffer.putInt(deed.settlementId());
                buffer.putInt(deed.gameDay());
                buffer.put(deed.severity());
                buffer.put(deed.confidence());
                buffer.put((byte) slot.repeats());
            }
            CompoundTag entry = new CompoundTag();
            entry.putIntArray(KEY_HOLDER, UUIDUtil.uuidToIntArray(holder.getKey()));
            entry.putByteArray(KEY_RING, packed);
            list.add(entry);
        }
        root.put(KEY_LIST, list);

        int[] flattened = new int[actors.size() * 4];
        for (int i = 0; i < actors.size(); i++) {
            System.arraycopy(UUIDUtil.uuidToIntArray(actors.get(i)), 0, flattened, i * 4, 4);
        }
        root.putIntArray(KEY_ACTORS, flattened);
    }

    private static <T> int intern(T value, List<T> palette, Map<T, Integer> index) {
        Integer existing = index.get(value);
        if (existing != null) {
            return existing;
        }
        int next = palette.size();
        palette.add(value);
        index.put(value, next);
        return next;
    }

    /**
     * Reads the memory table out of a registry tag.
     *
     * <p><b>A tag written before schema 5 has no {@code memories} key, and that must read as "nobody
     * has seen anything yet" rather than as damage.</b> {@code NpcSchemaTest} pins it, because both
     * failure directions are silent: read as damage and the registry goes read-only, so a world with
     * memories in it stops saving them; read as zero when the key was genuinely unreadable and every
     * ring in the world is dropped and the file rewritten without them.
     *
     * <p><b>A schema-6 ring must not read as an empty one, and that trap is new this session.</b>
     * Vanilla's {@code CompoundTag.getByteArray} returns an empty array for a key that holds the
     * wrong tag type — so a ring still written as a {@code ListTag} of compounds would load as a
     * villager who remembers nothing, silently, and then be written back that way. That is the exact
     * shape of failure hard rule 1 exists for, arriving through a type mismatch rather than through a
     * missing fixer, so it is checked by name below and counted as damage.
     *
     * <p><b>A ring longer than {@link #RING_CAPACITY} is trimmed rather than refused</b>, by the same
     * eviction rule an overflow uses. The bound is this build's, not the file's, and the honest
     * response to a save written by a build that kept more is to keep the ones this one would have
     * kept. Refusing would turn a survivable difference into a read-only world.
     *
     * @return how many memory records could not be read. Non-zero must make the registry read-only,
     * exactly as an unreadable persona does.
     */
    public int readFrom(CompoundTag root) {
        ListTag list = root.getList(KEY_LIST, Tag.TAG_COMPOUND);
        int unreadable = 0;

        List<UUID> actors = readActors(root);


        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);
            if (!entry.contains(KEY_HOLDER, Tag.TAG_INT_ARRAY)) {
                Namesake.LOGGER.error("Memory record {} has no holder id", i);
                unreadable++;
                continue;
            }
            UUID holder = UUIDUtil.uuidFromIntArray(entry.getIntArray(KEY_HOLDER));

            if (!entry.contains(KEY_RING, Tag.TAG_BYTE_ARRAY)) {
                Namesake.LOGGER.error(
                        "{}'s ring is not a packed byte array. A schema-6 ring read by this build "
                                + "would come back empty rather than failing, so it is counted as "
                                + "damage: the world must not be written back without those deeds.",
                        holder);
                unreadable++;
                continue;
            }
            byte[] packed = entry.getByteArray(KEY_RING);
            if (packed.length % RECORD_BYTES != 0) {
                Namesake.LOGGER.error("{}'s ring is {} bytes, which is not a whole number of {}-byte "
                        + "slots", holder, packed.length, RECORD_BYTES);
                unreadable++;
                continue;
            }

            ByteBuffer buffer = ByteBuffer.wrap(packed);
            List<Slot> ring = new ArrayList<>(packed.length / RECORD_BYTES);
            for (int slot = 0; slot < packed.length / RECORD_BYTES; slot++) {
                short typeId = buffer.getShort();
                int actorIndex = buffer.getInt();
                int subjectIndex = buffer.getInt();
                int settlementId = buffer.getInt();
                int gameDay = buffer.getInt();
                byte severity = buffer.get();
                byte confidence = buffer.get();

                int repeats = buffer.get() & 0xFF;

                if (actorIndex < 0 || actorIndex >= actors.size()
                        || subjectIndex < 0 || subjectIndex >= actors.size()) {
                    Namesake.LOGGER.error(
                            "Unreadable deed in {}'s ring at slot {}: palette index out of range "
                                    + "(actor {}, subject {} against {} actor(s))",
                            holder, slot, actorIndex, subjectIndex, actors.size());
                    unreadable++;
                    continue;
                }
                Deed deed;
                try {
                    deed = new Deed(typeId, actors.get(actorIndex), actors.get(subjectIndex),
                            settlementId, gameDay, severity, confidence);
                    // Resolves the type id, which is the one field that can be a value no build
                    // understands — a deed type from an addon that is no longer installed.
                    deed.type();
                } catch (RuntimeException failure) {
                    Namesake.LOGGER.error("Unreadable deed in {}'s ring at slot {}: {}",
                            holder, slot, failure.getMessage());
                    unreadable++;
                    continue;
                }
                ring.add(new Slot(deed.id(), deed, repeats));
            }
            if (ring.isEmpty()) {
                continue;
            }
            trimTo(ring, RING_CAPACITY);
            byHolder.put(holder, ring);
        }
        return unreadable;
    }

    private static List<UUID> readActors(CompoundTag root) {
        int[] flattened = root.getIntArray(KEY_ACTORS);
        List<UUID> actors = new ArrayList<>(flattened.length / 4);
        for (int i = 0; i + 3 < flattened.length; i += 4) {
            actors.add(UUIDUtil.uuidFromIntArray(
                    new int[]{flattened[i], flattened[i + 1], flattened[i + 2], flattened[i + 3]}));
        }
        return actors;
    }

}
