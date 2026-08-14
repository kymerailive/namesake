package net.namesake.social;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.namesake.Namesake;
import net.namesake.npc.NpcRegistry;
import net.namesake.npc.Persona;
import net.namesake.profile.Meter;
import net.namesake.profile.Meters;
import net.namesake.profile.Profiling;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * <b>What a settlement is currently talking about, and the rule that spends it.</b>
 * {@code DESIGN.md} §4 steps 6 and 7.
 *
 * <p>Steps 1 to 4 reach the twelve people who were standing there and stop. This is the half that
 * makes the thesis a thesis: a deed one villager witnessed becomes something a villager who was
 * nowhere near it can tell you about. {@code DESIGN.md} says it plainly and it is the sentence this
 * class is built around — <b>reputation travelling to the next settlement is not bolted onto the
 * pipeline, it is the pipeline run one hop further</b>. Session 10 adds an edge here; it does not
 * add a mechanism.
 *
 * <h2>The table and the mechanism are one class, deliberately</h2>
 *
 * <p>{@link Memories} and {@link Bonds} are tables and {@link DeedBus} is the pipeline that writes
 * them, because the pipeline already existed. Here there is no such split to inherit: a settlement's
 * stock of stories and the rule that spends four of them an in-game hour are one thing, and putting
 * the deque in one file and the drain in another would be two answers to one question. The sections
 * below are the seam.
 *
 * <h2>What one drain does</h2>
 *
 * <ol>
 *   <li>Take the oldest story off the settlement's deque.</li>
 *   <li>Work out how it is told now — {@link Deed#retold()}, which keeps {@link Deed#RETOLD} of the
 *       teller's confidence and drops the actor's name when there is too little left to attribute
 *       it.</li>
 *   <li>Offer it to every resident who does not already know it. Each takes it with probability
 *       {@link #TRANSFER_CHANCE}, and taking it means the shipped record layer runs — the ring, and
 *       the bond, through the same door an emit uses.</li>
 *   <li>Put it back on the deque if it can still be attributed, because a story you can name
 *       somebody in is a story worth passing on. If it cannot, it stops here.</li>
 * </ol>
 *
 * <p><b>Step 4 is where {@code DESIGN.md}'s "max 2 hops" comes from, and nothing counts hops.</b>
 * {@link Deed#RETOLD} and {@link Deed#ATTRIBUTED} between them permit exactly two retellings —
 * 100 → 70 → 49 — so the bound is arithmetic rather than bookkeeping. That is not a trick to save a
 * field: a hop counter would be an eighth field on a record session 06 deliberately held at seven,
 * persisted in every ring in every save, deriving something a field already there answers.
 *
 * <h2>Nothing is invented</h2>
 *
 * <p>Every field of a retold deed but two is carried through untouched, and those two only ever move
 * one way: confidence down, and the actor from a person to nobody. There is no path in this class
 * that adds a detail to a story. That is what {@code DESIGN.md} §2's <i>"distorts, never lies"</i>
 * means in code, and it is why a deed had to stay a struct rather than becoming a sentence.
 *
 * <h2>The first thing in this mod that polls, and what it costs</h2>
 *
 * <p>Every social mechanic through session 07 costs literally zero per tick — session 04 measured
 * <i>no meter recorded a sample and no counter moved</i>. That ends here, and it ends deliberately.
 * The cost is <b>bounded by construction rather than measured</b>, and the construction is this:
 *
 * <ul>
 *   <li>On 249 ticks in every 250 the hook reads {@code getTickCount()} and returns.</li>
 *   <li>On the 250th it looks the registry up and asks whether {@link #isEmpty()}. A settlement is
 *       in this map <b>only while it has an unspent story</b>: it is put there by an emit and
 *       removed the moment its deque runs dry, which is at most two drains after the last deed
 *       anybody did there. With one player that is one settlement, whatever the save holds — the map
 *       is sized by recent events, not by the world.</li>
 *   <li>A drain that finds work walks the persona table once for that settlement's residents. At
 *       {@code DESIGN.md} §8's four hundred records that is four hundred visits, four times an
 *       in-game hour, which is four hundred visits per 72,000 ticks of game time.</li>
 * </ul>
 *
 * <p>Measuring it was the alternative and was rejected for a reason session 06 already recorded: a
 * wall-clock number taken on the owner's machine while they are working on it is the confident-wrong
 * kind. {@code GossipTest.theDrainVisitsOnlySettlementsWithSomethingToSay} pins the bound instead,
 * over a two-hundred-settlement registry, deterministically, in CI.
 */
public final class Gossip {

    /** How many stories one settlement can have in flight. {@code DESIGN.md} §4 step 6. */
    public static final int DEQUE_CAPACITY = 32;

    /** {@code DESIGN.md} §8's cadence. One drain per settlement with something to say. */
    public static final int DRAIN_INTERVAL_TICKS = 250;

    /** Four an in-game hour, which is what {@link #DRAIN_INTERVAL_TICKS} is chosen to produce. */
    public static final int DRAINS_PER_HOUR = 1000 / DRAIN_INTERVAL_TICKS;

    /** Ninety-six. What a caller advancing a whole in-game day has to run. */
    public static final int DRAINS_PER_DAY = 24_000 / DRAIN_INTERVAL_TICKS;

    /**
     * The chance one resident takes one telling. {@code DESIGN.md} §4 step 7's 0.35.
     *
     * <p>Per hearer rather than per drain, which is the reading that makes the cross-settlement 0.15
     * beside it mean the same kind of thing. Two drains at this rate reach 58% of the people who did
     * not already know, which is what carries the exit criterion — the witnesses are the rest.
     */
    public static final float TRANSFER_CHANCE = 0.35F;

    private static final String KEY_LIST = "gossip";
    private static final String KEY_SETTLEMENT = "settlement";
    private static final String KEY_QUEUE = "queue";

    private static final Meter DRAIN = Profiling.ENABLED ? Meters.meter("Gossip.drain") : null;

    /**
     * Only settlements with an unspent story appear here, and that is the whole cost bound.
     *
     * <p>A key is created by {@link #enqueue} and removed by {@link #drain} the moment the deque
     * empties, so {@code bySettlement.size()} is the number of places something has recently
     * happened in rather than the number of places that exist.
     */
    private final Map<Integer, Deque<Deed>> bySettlement = new LinkedHashMap<>();

    // --- reads -------------------------------------------------------------------------------------

    /** What this settlement is talking about, oldest first. Unmodifiable. */
    public List<Deed> of(int settlementId) {
        Deque<Deed> queue = bySettlement.get(settlementId);
        return queue == null ? List.of() : List.copyOf(queue);
    }

    /** How many stories are in flight anywhere. */
    public int size() {
        int total = 0;
        for (Deque<Deed> queue : bySettlement.values()) {
            total += queue.size();
        }
        return total;
    }

    /** How many settlements have anything to say. The number the per-tick cost is proportional to. */
    public int settlements() {
        return bySettlement.size();
    }

    public boolean isEmpty() {
        return bySettlement.isEmpty();
    }

    /** Whether this settlement is already telling this story, whatever confidence it has left. */
    public boolean holds(int settlementId, long deedId) {
        for (Deed queued : of(settlementId)) {
            if (queued.id() == deedId) {
                return true;
            }
        }
        return false;
    }

    public Map<Integer, List<Deed>> all() {
        Map<Integer, List<Deed>> copy = new LinkedHashMap<>();
        bySettlement.forEach((settlement, queue) -> copy.put(settlement, List.copyOf(queue)));
        return Collections.unmodifiableMap(copy);
    }

    // --- writes ------------------------------------------------------------------------------------

    /**
     * Puts a story into a settlement's deque. {@code DESIGN.md} §4 step 6.
     *
     * <p><b>One entry per story, and a story already queued is not queued again.</b> Nine identical
     * feedings on one day are one deed — {@link Deed#id()} is content-addressed — so they are also
     * one rumour, and the deque inherits the ring's ungrindability for free rather than needing its
     * own version of it. A retold copy carries the same id while it is still attributed, so this also
     * refuses to resurrect a story the village has already half-forgotten.
     *
     * <p>Reaching this method directly queues a rumour that exists until the world reloads and then
     * does not. Write through {@code NpcRegistry.enqueueRumour}, which is the only door that marks
     * the file dirty — the same discipline {@link Bonds#put} and {@link Memories#remember} carry.
     *
     * @return true if the deque changed
     */
    public boolean enqueue(int settlementId, Deed deed) {
        if (settlementId == Persona.UNASSIGNED) {
            // A deed in the wilderness has no village to talk about it. Queueing it under the
            // unassigned sentinel would make one deque for everywhere nobody lives.
            return false;
        }
        if (holds(settlementId, deed.id())) {
            return false;
        }
        Deque<Deed> queue = bySettlement.computeIfAbsent(settlementId, key -> new ArrayDeque<>(4));
        queue.addLast(deed);
        while (queue.size() > DEQUE_CAPACITY) {
            queue.removeFirst();
        }
        return true;
    }

    /** Takes the oldest story, and drops the settlement from the map when nothing is left. */
    private Deed poll(int settlementId) {
        Deque<Deed> queue = bySettlement.get(settlementId);
        if (queue == null) {
            return null;
        }
        Deed head = queue.pollFirst();
        if (queue.isEmpty()) {
            bySettlement.remove(settlementId);
        }
        return head;
    }

    /** Drops everything one settlement had to say. Called when a settlement is removed. */
    public boolean forget(int settlementId) {
        return bySettlement.remove(settlementId) != null;
    }

    // --- the drain ---------------------------------------------------------------------------------

    /** What one drain did, so a caller — or a report — can assert on it rather than infer it. */
    public record Drained(Deed told, int offered, int heard, boolean stillTravelling) {

        public static final Drained NOTHING = new Drained(null, 0, 0, false);

        public boolean happened() {
            return told != null;
        }
    }

    /**
     * Spends one story from one settlement. {@code DESIGN.md} §4 step 7.
     *
     * <p>Pure over the registry: no level, no entities, no server. That is deliberate and it is the
     * same decision {@code DeedBus.record} made — everything below the spatial query is the record
     * layer, so the headless simulation runs this exact method rather than a copy of it, and the
     * propagation curve in a report is a curve produced by shipped code.
     *
     * @param day the in-game day the telling happens on, which is never earlier than the day the
     *            deed happened. See {@code DeedBus.deliver}.
     */
    public static Drained drain(NpcRegistry registry, int settlementId, int day) {
        long begun = Meters.now();
        Gossip gossip = registry.gossip();
        Deed carried = gossip.poll(settlementId);
        if (carried == null) {
            return Drained.NOTHING;
        }
        // A poll is a change to a persisted table whether or not anybody listens: the village's copy
        // of this story is worse attested than it was a moment ago.
        registry.setDirty();

        Deed told = carried.retold();
        int offered = 0;
        int heard = 0;
        for (Persona resident : registry.all()) {
            if (resident.settlementId() != settlementId) {
                continue;
            }
            offered++;
            if (registry.memories().remembers(resident.id(), told.id())) {
                continue;
            }
            // The one case the id cannot catch on its own. A blurred copy is a different deed by
            // construction — the actor is part of the derivation — so without this the villager who
            // watched you kill the smith would be handed "somebody killed the smith" as news.
            if (!told.isAttributed() && registry.memories().remembers(resident.id(), carried.id())) {
                continue;
            }
            if (!takes(told, resident.id())) {
                continue;
            }
            DeedBus.deliver(registry, told, resident, day);
            heard++;
        }

        boolean travelling = told.isAttributed();
        if (travelling) {
            gossip.enqueue(settlementId, told);
        }

        if (Profiling.ENABLED) {
            DRAIN.end(begun);
            Meters.count("Gossip stories drained");
            Meters.count("Gossip residents offered", offered);
            Meters.count("Gossip residents who heard", heard);
        }
        Namesake.LOGGER.debug("Gossip in settlement {}: {} told to {} of {} resident(s){}",
                settlementId, told, heard, offered,
                travelling ? ", still travelling" : ", and it stops here");
        return new Drained(told, offered, heard, travelling);
    }

    /**
     * One story from every settlement that has one. What a drain tick actually is.
     *
     * @return how many settlements had something to say
     */
    public static int drainEverySettlement(NpcRegistry registry, int day) {
        if (registry.gossip().isEmpty()) {
            // The usual case, and it has to cost nothing: a caller advancing an in-game day runs
            // this ninety-six times whether or not anything has happened.
            return 0;
        }
        Set<Integer> active = Set.copyOf(registry.gossip().bySettlement.keySet());
        for (int settlementId : active) {
            drain(registry, settlementId, day);
        }
        return active.size();
    }

    /**
     * Hooked to the end of every server tick by both loaders. <b>The first poll in this mod.</b>
     *
     * <p>Returns on its first line on 249 ticks in every 250, and on the 250th it returns on its
     * third unless something has actually happened somewhere recently. See the class note for why
     * that is a bound rather than a hope.
     */
    public static void onServerTick(MinecraftServer server) {
        if (Profiling.MOD_INERT) {
            // Hard rule 4's baseline: the same world with none of our code in it. See Profiling.
            return;
        }
        if (server.getTickCount() % DRAIN_INTERVAL_TICKS != 0) {
            return;
        }
        NpcRegistry registry = NpcRegistry.get(server);
        if (registry.gossip().isEmpty()) {
            return;
        }
        drainEverySettlement(registry, Deed.dayOf(server.overworld()));
    }

    /**
     * Whether this resident takes this telling.
     *
     * <p><b>A hash rather than a random number, and that is not a micro-optimisation.</b> The
     * headless simulation has to reproduce bit for bit on any machine — session 07 turned the build
     * red for choosing a subject off the wall clock — so a shared {@code RandomSource} would have to
     * be seeded, persisted and advanced in lockstep with a save file. Deriving the coin from the
     * story and the hearer costs nothing, needs no state, and gives the same answer in a report and
     * in a game.
     *
     * <p>Confidence is mixed in as well as the id, so the second telling of a story is not offered
     * to exactly the people who refused the first one. Without it a resident's answer would be fixed
     * for the life of a deed and two drains would reach the same 35% twice.
     */
    static boolean takes(Deed told, UUID resident) {
        long hash = Deed.mix(told.id(), resident.getMostSignificantBits());
        hash = Deed.mix(hash, resident.getLeastSignificantBits());
        hash = Deed.mix(hash, told.confidence());
        return (hash >>> 40) < (long) (TRANSFER_CHANCE * (1L << 24));
    }

    // --- persistence -------------------------------------------------------------------------------

    /**
     * One entry per settlement, each carrying its deque in order.
     *
     * <p>Shaped like {@link Memories} rather than like {@link Bonds}, for the same reason: order is
     * data here rather than an artefact, and repeating a settlement id per row would buy nothing.
     * The value is a plain {@link Deed}, which is what makes this a schema change with <b>no new
     * record in it</b> — the confidence a story has left is already a field, and the hop count that
     * would otherwise need one is derivable from it.
     */
    public void save(CompoundTag root) {
        ListTag list = new ListTag();
        for (Map.Entry<Integer, Deque<Deed>> settlement : bySettlement.entrySet()) {
            if (settlement.getValue().isEmpty()) {
                continue;
            }
            CompoundTag entry = new CompoundTag();
            entry.putInt(KEY_SETTLEMENT, settlement.getKey());
            ListTag queue = new ListTag();
            for (Deed deed : settlement.getValue()) {
                queue.add(Deed.CODEC.encodeStart(NbtOps.INSTANCE, deed)
                        .getOrThrow(error -> new IllegalStateException("Cannot encode rumour " + deed
                                + " in settlement " + settlement.getKey() + ": " + error)));
            }
            entry.put(KEY_QUEUE, queue);
            list.add(entry);
        }
        root.put(KEY_LIST, list);
    }

    /**
     * Reads the gossip table out of a registry tag.
     *
     * <p><b>A tag written before schema 6 has no {@code gossip} key, and that must read as "nothing
     * is in flight" rather than as damage.</b> That absence <i>is</i> the schema 5 → 6 migration —
     * the third additive one running, after bonds at 4 and rings at 5 — and it is free because this
     * method returns zero unreadable records for an absent list rather than failing on one.
     * {@code NpcSchemaTest} pins it, because both failure directions are silent: read as damage and
     * the registry goes read-only, so a world stops saving its personas, settlements, bonds and rings
     * along with its rumours; read as zero when the key was genuinely unreadable and every story in
     * the world is quietly dropped.
     *
     * <p>A deque longer than {@link #DEQUE_CAPACITY} is truncated to its newest entries rather than
     * refused, exactly as an over-long ring is. The bound is this build's, not the file's.
     *
     * @return how many records could not be read. Non-zero must make the registry read-only.
     */
    public int readFrom(CompoundTag root) {
        ListTag list = root.getList(KEY_LIST, Tag.TAG_COMPOUND);
        int unreadable = 0;

        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);
            if (!entry.contains(KEY_SETTLEMENT, Tag.TAG_INT)) {
                Namesake.LOGGER.error("Gossip record {} has no settlement id", i);
                unreadable++;
                continue;
            }
            int settlementId = entry.getInt(KEY_SETTLEMENT);
            ListTag queue = entry.getList(KEY_QUEUE, Tag.TAG_COMPOUND);
            List<Deed> parsed = new ArrayList<>(queue.size());
            for (int slot = 0; slot < queue.size(); slot++) {
                int index = slot;
                Deed deed = Deed.CODEC.parse(NbtOps.INSTANCE, queue.getCompound(slot))
                        .resultOrPartial(error -> Namesake.LOGGER.error(
                                "Unreadable rumour in settlement {} at slot {}: {}",
                                settlementId, index, error))
                        .orElse(null);
                if (deed == null) {
                    unreadable++;
                    continue;
                }
                parsed.add(deed);
            }
            for (Deed deed : parsed) {
                enqueue(settlementId, deed);
            }
        }
        return unreadable;
    }
}
