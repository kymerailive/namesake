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
import net.namesake.road.Roads;

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
 * field: a hop counter would be another field on {@link Deed}, persisted in every ring in every
 * save, deriving something a field already there answers. Session 09 added {@code Deed.item} and
 * that argument is untouched — the test is whether a field can be derived, not how many there are.
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
 *   <li><b>Session 10 widens that window and it is worth stating rather than leaving to be found.</b>
 *       A neighbouring settlement holding a story that is still on the road stays in the map until
 *       the day turns, so the window is up to one in-game day rather than two drains, over as many
 *       settlements as the deed's village has roads. What a visit costs is unchanged and it is not
 *       the persona table: {@link #poll} walks a deque of at most {@link #DEQUE_CAPACITY} and returns
 *       nothing, so the four-hundred-record walk still only happens for a settlement with something
 *       to say <i>today</i>.</li>
 *   <li>A drain that finds work walks the persona table once for that settlement's residents. At
 *       {@code DESIGN.md} §8's four hundred records that is four hundred visits, four times an
 *       in-game hour, which is four hundred visits per 72,000 ticks of game time.</li>
 * </ul>
 *
 * <p>Measuring it was the alternative and was rejected for a reason session 06 already recorded: a
 * wall-clock number taken on the owner's machine while they are working on it is the confident-wrong
 * kind. {@code GossipTest.theDrainVisitsOnlySettlementsWithSomethingToSay} pins the bound instead,
 * over a two-hundred-settlement registry, deterministically, in CI.
 *
 * <h2>Session 10: the border, and the two ruled numbers that did not compose</h2>
 *
 * <p><b>The timing.</b> {@link #DRAIN_INTERVAL_TICKS} is 250 and a story is spent after two drains,
 * so a deed is out of its own village's deque about <b>500 ticks</b> after it is queued.
 * {@code DESIGN.md} §4 step 7 gave the cross-settlement hop a <b>1200–6000 tick</b> delay. By the
 * time that delay elapses there is nothing left in the source deque to send, so <i>"take from the
 * deque after a delay"</i> was never an implementation. Three ways out were available and all three
 * cost something real: an in-flight table is persisted state and therefore a schema bump in a
 * ship-or-kill session; lengthening the deque's life changes how far a story travels inside its own
 * village, which is the number session 08 spent its whole budget getting right; shortening the delay
 * removes the thing acceptance step 3 depends on, which is that you can outwalk the news.
 *
 * <p><b>The fourth way is the one taken, and the argument for it is not convenience.</b> The story
 * is handed to the neighbour's deque <i>at the moment it is told at home</i> — while it is still in
 * hand — and the neighbour refuses to tell it until the day has turned. Both halves are read off the
 * deed: {@link Deed#settlementId()} says where it happened, {@link Deed#gameDay()} says when. So the
 * in-flight story is a queued rumour in a persisted deque, which is <b>exactly what session 08
 * persisted the deque for</b>, and session 10 changes no schema.
 *
 * <p>The delay is therefore measured in in-game days rather than in ticks, and that is the part
 * worth arguing rather than the part worth apologising for. <b>Nothing downstream of a deed has ever
 * seen a tick.</b> {@code Deed.dayOf} divides game time by 24,000, {@code Bond.decayedTo} takes a
 * day, {@code Bond.apply} resets the allowance when the day turns — and session 07's whole claim that
 * a hundred simulated days <i>are</i> a hundred days rests on that being true. A tick-precise delay
 * would be the first sub-day clock in the record layer, and the instrument that has to measure this
 * session's propagation curve could no longer run it exactly. A delay that cannot be simulated is a
 * delay nobody can put a number on.
 *
 * <p><b>The confidence.</b> What crosses is the deque's own entry, untouched — <i>the carrier's copy
 * of the story</i> — and the neighbour's first telling degrades it exactly as the source's first
 * telling degraded the witnesses'. A witness leaves the village holding a hundred, so the first
 * people in the next village to hear it hold <b>seventy</b>: they can still name you, which is what
 * {@code DESIGN.md} §10 step 5 is made of, and the telling after that blurs to forty-nine.
 *
 * <p><b>And a story crosses a border only from the place it happened</b> — {@link #atHome} — which is
 * one comparison over a field the deed already carries. Without it the carrier's copy would arrive
 * undegraded in the next village and set out again undegraded from there, and your name would reach
 * the horizon. With it, the two hops {@code DESIGN.md} rules are the two hops you get, and they are
 * still arithmetic: a hundred at home, seventy next door, forty-nine and nameless after that.
 * <b>Nothing counts hops and nothing counts borders.</b>
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

    /**
     * The chance one resident takes one telling that came down a road. {@code DESIGN.md} §4 step 7's
     * 0.15.
     *
     * <p><b>Per hearer, exactly as {@link #TRANSFER_CHANCE} is, and that reading is an interpretive
     * call rather than a reading of the words.</b> §4 step 7 says <i>"same-settlement transfer at
     * 0.35 per hearer … cross-settlement along a road edge at 0.15"</i>, and the alternative is that
     * 0.15 is a coin on the edge itself. Session 08's fourth closing ruling settled the same question
     * for 0.35 — <i>the chance one hearer takes one telling, not a fraction of the village per
     * drain</i> — and applying it again is what makes the two numbers the same kind of thing. It also
     * makes the road load-bearing rather than decorative: <b>the edge decides whether there is a
     * route at all, and this decides how well the news takes when it gets there.</b>
     *
     * <p>What the other reading costs is arithmetic and is the reason this one was chosen. A story
     * gets exactly one attributed telling, so a coin on the edge would put a named story into a
     * <i>specific</i> neighbouring village 15% of the time — and {@code WORKPLAN.md}'s acceptance
     * script is one gift. Per hearer, one gift reaches at least one namer in a nine-person village
     * <b>77%</b> of the time and three gifts reach one <b>99%</b> of the time. Neither number is a
     * choice about how talkative a village is; they are the same 0.15 read two ways, and one of them
     * makes ship-or-kill a coin flip.
     */
    public static final float ARRIVES_BY_ROAD = 0.15F;

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

    /**
     * Takes the oldest story this settlement is able to tell yet, and drops the settlement from the
     * map when nothing is left.
     *
     * <p><b>"Able to tell yet" is the session 10 delay, and it is derived rather than scheduled.</b>
     * A story that happened here is tellable the moment it is queued. A story that came down a road
     * is somebody walking, and they arrive when the day has turned — see {@link #hasArrived}. So the
     * queue is skipped past rather than blocked: a village with a traveller still on the road can
     * still talk about its own business.
     */
    private Deed poll(int settlementId, int day) {
        Deque<Deed> queue = bySettlement.get(settlementId);
        if (queue == null) {
            return null;
        }
        Deed tellable = null;
        for (Deed queued : queue) {
            if (hasArrived(queued, settlementId, day)) {
                tellable = queued;
                break;
            }
        }
        if (tellable == null) {
            return null;
        }
        queue.remove(tellable);
        if (queue.isEmpty()) {
            bySettlement.remove(settlementId);
        }
        return tellable;
    }

    /**
     * Whether this settlement is the one the deed happened in.
     *
     * <p>Two jobs, both of them {@code if} statements over a field that has been on the record since
     * session 05. It decides what a telling is worth here — first-hand business is
     * {@link #TRANSFER_CHANCE}, a story from down the road is {@link #ARRIVES_BY_ROAD} — and it
     * decides whether a story may set out again, which is what bounds how far a name travels. See the
     * class note.
     */
    public static boolean atHome(Deed deed, int settlementId) {
        return deed.settlementId() == settlementId;
    }

    /**
     * Whether a story is here yet. <b>The whole of session 10's delay, as one comparison.</b>
     *
     * <p>A story from elsewhere is a person on a road, and they arrive once the day has turned. That
     * is what stops a player outwalking the news within a day — acceptance step 3 — and it is well
     * inside step 4's two in-game days. It needs no departure timestamp, no arrival tick and no
     * in-flight table, because {@link Deed#gameDay()} is already on the record and already persisted.
     *
     * <p>The honest edge: a deed done in the last minutes before midnight is told next door at first
     * light rather than a day later. That is a smaller delay than the design's shortest, and buying
     * it back would cost the departure timestamp this method exists to do without.
     */
    public static boolean hasArrived(Deed deed, int settlementId, int day) {
        return atHome(deed, settlementId) || day > deed.gameDay();
    }

    /** Drops everything one settlement had to say. Called when a settlement is removed. */
    public boolean forget(int settlementId) {
        return bySettlement.remove(settlementId) != null;
    }

    // --- the drain ---------------------------------------------------------------------------------

    /**
     * What one drain did, so a caller — or a report — can assert on it rather than infer it.
     *
     * @param crossed how many neighbouring settlements this telling set out for. Reported rather
     *                than derived, for the reason session 08 gave when it made a delivery report what
     *                it wrote: a count that is inferred from the graph is a count that stays right
     *                when the code stops doing it.
     */
    public record Drained(Deed told, int offered, int heard, boolean stillTravelling, int crossed) {

        public static final Drained NOTHING = new Drained(null, 0, 0, false, 0);

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
        Deed carried = gossip.poll(settlementId, day);
        if (carried == null) {
            return Drained.NOTHING;
        }
        // A poll is a change to a persisted table whether or not anybody listens: the village's copy
        // of this story is worse attested than it was a moment ago.
        registry.setDirty();

        boolean home = atHome(carried, settlementId);
        // One line, no field. The rate is a property of the story rather than of the teller: news of
        // somewhere else takes less well than the village's own business, and it goes on taking less
        // well on the second telling too, when the teller is a neighbour rather than a traveller.
        // That is the conservative reading of the two available and it costs nothing to hold.
        float chance = home ? TRANSFER_CHANCE : ARRIVES_BY_ROAD;

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
            if (!takes(told, resident.id(), chance)) {
                continue;
            }
            DeedBus.deliver(registry, told, resident, day);
            heard++;
        }

        boolean travelling = told.isAttributed();
        if (travelling) {
            gossip.enqueue(settlementId, told);
        }

        // The border. What crosses is `carried` rather than `told` — the carrier's own copy, which
        // the far village's first telling will degrade exactly as this one degraded the witnesses'.
        // Only from home, which is what stops an undegraded copy setting out again from every
        // village it reaches. Both halves are argued in the class note.
        int crossed = 0;
        if (home) {
            for (int neighbour : Roads.neighboursOf(registry.settlements(), settlementId)) {
                if (gossip.enqueue(neighbour, carried)) {
                    crossed++;
                }
            }
        }

        if (Profiling.ENABLED) {
            DRAIN.end(begun);
            Meters.count("Gossip stories drained");
            Meters.count("Gossip residents offered", offered);
            Meters.count("Gossip residents who heard", heard);
            Meters.count("Gossip borders crossed", crossed);
        }
        Namesake.LOGGER.debug("Gossip in settlement {}: {} told to {} of {} resident(s){}{}",
                settlementId, told, heard, offered,
                travelling ? ", still travelling" : ", and it stops here",
                crossed == 0 ? "" : ", and set out for " + crossed + " neighbour(s)");
        return new Drained(told, offered, heard, travelling, crossed);
    }

    /**
     * One story from every settlement that has one. What a drain tick actually is.
     *
     * <p>The active set is copied before the loop because a drain now writes into <i>another</i>
     * settlement's deque: crossing a border while iterating the map it is adding to would be a
     * concurrent modification, and a story reaching a neighbour that has not been visited yet this
     * tick would be told on the day it arrived rather than the day after.
     *
     * @return how many settlements actually told something. A settlement holding only a story that
     * is still on the road is visited and says nothing, which is the delay working rather than a
     * drain that failed.
     */
    public static int drainEverySettlement(NpcRegistry registry, int day) {
        if (registry.gossip().isEmpty()) {
            // The usual case, and it has to cost nothing: a caller advancing an in-game day runs
            // this ninety-six times whether or not anything has happened.
            return 0;
        }
        Set<Integer> active = Set.copyOf(registry.gossip().bySettlement.keySet());
        int told = 0;
        for (int settlementId : active) {
            if (drain(registry, settlementId, day).happened()) {
                told++;
            }
        }
        return told;
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
     *
     * @param chance {@link #TRANSFER_CHANCE} for the village's own business, {@link #ARRIVES_BY_ROAD}
     *               for a story that came down a road. Passed rather than looked up, so this stays a
     *               pure function of three values and a unit test can ask it about either rate.
     */
    static boolean takes(Deed told, UUID resident, float chance) {
        long hash = Deed.mix(told.id(), resident.getMostSignificantBits());
        hash = Deed.mix(hash, resident.getLeastSignificantBits());
        hash = Deed.mix(hash, told.confidence());
        return (hash >>> 40) < (long) (chance * (1L << 24));
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
