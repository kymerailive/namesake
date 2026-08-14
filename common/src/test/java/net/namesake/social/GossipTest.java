package net.namesake.social;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.namesake.npc.NpcRegistry;
import net.namesake.npc.Persona;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The deque, the decay, the hop bound, the blur and the round trip.
 *
 * <p>All of it is arithmetic over immutable values, which is the line {@code WORKPLAN.md} draws
 * between its instruments: the propagation <i>curve</i> is a claim about time and belongs in
 * {@code net.namesake.sim}, and the fact that a drain fires at all in a running game belongs in the
 * attach-bet harness. What is here is everything that is neither.
 */
class GossipTest {

    private static final UUID PLAYER = UUID.fromString("0a0a0a0a-1111-2222-3333-444444444444");
    private static final int VILLAGE = 3;
    private static final int ELSEWHERE = 4;

    private static Deed aKilling(int day) {
        return Deed.of(DeedType.KILLED_RESIDENT, PLAYER, new UUID(9, 9), VILLAGE, day);
    }

    // --- the ruling this session opened on ---------------------------------------------------------

    /**
     * <b>The contradiction session 06 proved and session 08 had to resolve.</b>
     *
     * <p>{@code DESIGN.md} §4 step 7 carried three clauses written before there was any code:
     * {@code confidence × 0.85}, <i>max 2 hops</i>, and <i>identity blurs below 50</i>. At 0.85 a
     * two-hop story stands at 72, so the third clause could never fire and {@code WORKPLAN.md}'s
     * exit criterion — at least one holder unable to name the actor — was unreachable by the design
     * as ruled.
     *
     * <p>The retention is what moved, and the window it had to land in is arithmetic rather than
     * taste. This is that window, asserted so a future change to any of the three numbers has to
     * come past it.
     */
    @Test
    @DisplayName("hop one still names the actor and hop two cannot, which is what the retention is for")
    void theRetentionIsTheOnlyNumberThatFitsTheWindow() {
        assertEquals(0.70F, Deed.RETOLD, 0.0001F);
        assertEquals(50, Deed.ATTRIBUTED);

        Deed firstHand = aKilling(4);
        assertEquals(100, firstHand.confidence());
        assertTrue(firstHand.isAttributed());

        Deed heard = firstHand.retold();
        assertEquals(70, heard.confidence(), "one retelling keeps seven tenths");
        assertTrue(heard.isAttributed(), "session 10's acceptance script step 5 is a one-hop story: "
                + "someone in town B says they have heard your NAME. If this ever goes false, the "
                + "ship-or-kill test cannot pass.");
        assertEquals(PLAYER, heard.actor());

        Deed rumour = heard.retold();
        assertEquals(49, rumour.confidence());
        assertFalse(rumour.isAttributed(), "two hops is where 'someone from the north' comes from");
        assertEquals(Deed.UNKNOWN_ACTOR, rumour.actor());

        // The window itself: anything below 0.50 blurs a one-hop story and breaks session 10;
        // anything at or above 0.7072 leaves a two-hop story attributed and the blur dead again.
        assertTrue(Deed.RETOLD >= 0.50F, "hop one must stay attributed");
        assertTrue(Deed.RETOLD * Deed.RETOLD * Deed.FIRST_HAND < Deed.ATTRIBUTED,
                "hop two must not");
    }

    /**
     * {@code DESIGN.md}'s "max 2 hops", which nothing counts.
     *
     * <p>A story is retold while it can still be attributed, so the bound falls out of the retention
     * and the attribution floor. This is the assertion that keeps the derived answer and the ruled
     * one in step: change {@link Deed#RETOLD} without meaning to change the reach and this goes red
     * with the number it produced instead.
     */
    @Test
    @DisplayName("a story survives exactly two retellings, and nothing anywhere counts hops")
    void theHopBoundFallsOutOfTheArithmetic() {
        Deed carried = aKilling(1);
        int hops = 0;
        while (carried.isAttributed() && hops < 20) {
            carried = carried.retold();
            hops++;
        }
        assertEquals(Deed.MAX_HOPS, hops,
                "the retention and the attribution floor produced " + hops + " hops and DESIGN.md "
                        + "§4 step 7 rules " + Deed.MAX_HOPS);
        assertFalse(carried.isAttributed(), "and the last copy is the one nobody can attribute");
    }

    @Test
    @DisplayName("a retelling degrades and never invents")
    void nothingIsEverInvented() {
        Deed firstHand = new Deed(DeedType.STRUCK_RESIDENT.id(), PLAYER, new UUID(7, 7),
                VILLAGE, 12, (byte) 60, Deed.FIRST_HAND);
        Deed heard = firstHand.retold();

        assertEquals(firstHand.typeId(), heard.typeId());
        assertEquals(firstHand.subject(), heard.subject());
        assertEquals(firstHand.settlementId(), heard.settlementId());
        assertEquals(firstHand.gameDay(), heard.gameDay());
        assertEquals(firstHand.severity(), heard.severity(), "a story cannot grow in the telling");
        assertTrue(heard.confidence() < firstHand.confidence(), "only downward");

        // And the same event, so it dedupes against the deed it retells rather than becoming a
        // second row for one killing. That is why confidence is outside Deed.id().
        assertEquals(firstHand.id(), heard.id());
        assertNotEquals(firstHand.id(), heard.retold().id(),
                "blurring the actor is a genuinely different claim, and session 06 said so");
    }

    // --- the deque ---------------------------------------------------------------------------------

    @Test
    @DisplayName("a settlement holds thirty-two stories, oldest out")
    void theDequeIsBounded() {
        Gossip gossip = new Gossip();
        for (int day = 0; day < 40; day++) {
            assertTrue(gossip.enqueue(VILLAGE, aKilling(day)));
        }
        assertEquals(Gossip.DEQUE_CAPACITY, gossip.of(VILLAGE).size());
        assertEquals(32, Gossip.DEQUE_CAPACITY, "DESIGN.md §4 step 6 sizes it at 32");
        assertEquals(8, gossip.of(VILLAGE).get(0).gameDay(), "the oldest survivor");
        assertEquals(39, gossip.of(VILLAGE).get(Gossip.DEQUE_CAPACITY - 1).gameDay());
    }

    /**
     * The ring's ungrindability, inherited rather than reimplemented.
     *
     * <p>Nine identical feedings on one day are one deed, so they are one rumour. Without this a
     * player standing in the square repeating themselves would flush every other story out of the
     * village's deque, which is the exact failure {@code Deed.id()} exists to stop one level up.
     */
    @Test
    @DisplayName("repeating yourself is one rumour, not thirty-two")
    void theDequeIsNotGrindable() {
        Gossip gossip = new Gossip();
        Deed theKilling = aKilling(4);
        assertTrue(gossip.enqueue(VILLAGE, theKilling));
        for (int i = 0; i < 500; i++) {
            assertFalse(gossip.enqueue(VILLAGE, aKilling(4)),
                    "a story already in flight is not a new story");
        }
        assertEquals(1, gossip.of(VILLAGE).size());
        assertTrue(gossip.holds(VILLAGE, theKilling.id()));
    }

    @Test
    @DisplayName("a deed done in the wilderness has no village to talk about it")
    void anUnsettledDeedIsNotQueued() {
        Gossip gossip = new Gossip();
        Deed nowhere = Deed.of(DeedType.FED_HUNGRY, PLAYER, new UUID(1, 1), Persona.UNASSIGNED, 0);
        assertFalse(gossip.enqueue(Persona.UNASSIGNED, nowhere));
        assertTrue(gossip.isEmpty(),
                "queueing under the unassigned sentinel would make one deque for everywhere "
                        + "nobody lives");
    }

    @Test
    @DisplayName("two settlements talk about their own business")
    void dequesAreIndependent() {
        Gossip gossip = new Gossip();
        gossip.enqueue(VILLAGE, aKilling(1));
        gossip.enqueue(ELSEWHERE, aKilling(2));

        assertEquals(2, gossip.settlements());
        assertEquals(1, gossip.of(VILLAGE).size());
        assertEquals(1, gossip.of(ELSEWHERE).size());
        assertTrue(gossip.forget(ELSEWHERE));
        assertEquals(1, gossip.settlements());
    }

    // --- the drain ---------------------------------------------------------------------------------

    /** A village of {@code residents} people, all in one settlement, with a deed already emitted. */
    private static NpcRegistry village(int residents) {
        NpcRegistry registry = new NpcRegistry();
        for (int i = 0; i < residents; i++) {
            registry.put(Persona.create(new UUID(0x5A1E, i), 0L)
                    .placed(VILLAGE, 1, (byte) 0)
                    .withTraits(Personality.typical()));
        }
        return registry;
    }

    private static List<Persona> residentsOf(NpcRegistry registry) {
        return new ArrayList<>(registry.all());
    }

    @Test
    @DisplayName("a drain tells the village, and the story comes back with less of itself")
    void oneDrainRetellsAndDegrades() {
        NpcRegistry registry = village(12);
        Deed deed = aKilling(0);
        // Only the subject was there, so everyone else is a candidate hearer.
        DeedBus.record(registry, deed, List.of(residentsOf(registry).get(0)), 0);
        assertEquals(1, registry.gossip().size(), "step 6 put it into the settlement's deque");

        Gossip.Drained first = Gossip.drain(registry, VILLAGE, 0);
        assertTrue(first.happened());
        assertEquals(70, first.told().confidence());
        assertTrue(first.heard() > 0, "somebody has to hear it, or the fixture proves nothing");
        assertTrue(first.stillTravelling(), "a story you can attribute is worth passing on");
        assertEquals(1, registry.gossip().size(), "and it goes back on the deque");
        assertEquals(70, registry.gossip().of(VILLAGE).get(0).confidence());

        Gossip.Drained second = Gossip.drain(registry, VILLAGE, 0);
        assertEquals(49, second.told().confidence());
        assertFalse(second.stillTravelling(), "a story nobody can attribute stops here");
        assertTrue(registry.gossip().isEmpty(), "and the settlement leaves the map entirely");

        assertEquals(Gossip.Drained.NOTHING, Gossip.drain(registry, VILLAGE, 0));
    }

    /**
     * <b>The {@code if} statement the blur pays for.</b>
     *
     * <p>A villager who cannot say who killed the smith has no reason to think worse of <i>you</i>.
     * They remember it, because it happened. That asymmetry is session 06's — seeing something is not
     * the same as it changing your mind about somebody — arriving from the other side.
     */
    @Test
    @DisplayName("an unattributed rumour is remembered by somebody and moves nobody's bond")
    void aBlurredRumourMovesNoBond() {
        NpcRegistry registry = village(12);
        Deed deed = aKilling(0);
        DeedBus.record(registry, deed, List.of(residentsOf(registry).get(0)), 0);

        Gossip.drain(registry, VILLAGE, 0);
        int bondsAfterOneHop = registry.bonds().size();
        Gossip.Drained blurred = Gossip.drain(registry, VILLAGE, 0);

        assertTrue(blurred.heard() > 0, "somebody heard the rumour");
        assertEquals(bondsAfterOneHop, registry.bonds().size(),
                "and it moved nobody's opinion of anybody, because nobody knows who did it");

        long unattributed = registry.all().stream()
                .flatMap(persona -> registry.memories().of(persona.id()).stream())
                .filter(held -> !held.isAttributed())
                .count();
        assertTrue(unattributed > 0, "it is still in somebody's ring");
        assertTrue(registry.bonds().of(Deed.UNKNOWN_ACTOR).isEmpty());
        assertTrue(registry.all().stream()
                        .noneMatch(p -> registry.bonds().stored(p.id(), Deed.UNKNOWN_ACTOR).isPresent()),
                "nothing may hold a bond about nobody");
    }

    /**
     * <b>The fixture the breakage pass found missing, and it is the third time in three sessions.</b>
     *
     * <p>Removing the blur guard from {@code DeedBus.deliver} turned <i>nothing</i> red, because
     * {@code NpcRegistry.putBond} refuses a bond about nobody anyway — the second of the two doors —
     * so the bond table looked identical either way. Two things were still wrong and neither had a
     * fixture: the delivery <i>reported</i> a bond it had not written, and every rumour in the world
     * would spend a {@code Deeds.deltaFor} and an ERROR line on the way to being refused.
     */
    @Test
    @DisplayName("delivering an unattributed rumour reports that it moved no bond")
    void anUnattributedDeliveryReportsNoBondMoved() {
        NpcRegistry registry = village(4);
        Persona hearer = residentsOf(registry).get(0);
        Deed rumour = aKilling(0).retold().retold();
        assertFalse(rumour.isAttributed(), "the fixture has to actually be blurred");

        DeedBus.Delivery delivery = DeedBus.deliver(registry, rumour, hearer, 0);

        assertTrue(delivery.remembered(), "they remember that it happened, because it did");
        assertFalse(delivery.bondMoved(),
                "a delivery that reports a bond it did not write is a count that lies — and the "
                        + "path it takes to get there logs an ERROR for every rumour in the world");
        assertEquals(0, registry.bonds().size());
    }

    /**
     * The one case the deed id cannot catch on its own.
     *
     * <p>A blurred copy is a different deed by construction, so without an explicit guard the
     * villager who <i>watched</i> the killing would be handed "somebody killed the smith" as news and
     * would end up holding two rows for one event.
     */
    @Test
    @DisplayName("a witness is not told a rumour about the thing they watched")
    void aWitnessIsNotToldTheirOwnStory() {
        NpcRegistry registry = village(12);
        Deed deed = aKilling(0);
        List<Persona> residents = residentsOf(registry);
        // Everybody saw it, so the only people the drain can reach already know.
        DeedBus.record(registry, deed, residents, residents.size() - 1);

        Gossip.drain(registry, VILLAGE, 0);
        Gossip.drain(registry, VILLAGE, 0);

        for (Persona resident : residents) {
            List<Deed> ring = registry.memories().of(resident.id());
            assertEquals(1, ring.size(),
                    () -> "somebody holds one event twice: " + ring);
            assertEquals(Deed.FIRST_HAND, ring.get(0).confidence(),
                    "and they still know it first-hand");
        }
    }

    @Test
    @DisplayName("a drain reaches this settlement's residents and nobody else's")
    void aDrainStaysInItsOwnVillage() {
        NpcRegistry registry = village(8);
        Persona outsider = Persona.create(new UUID(0xFA4, 1), 0L)
                .placed(ELSEWHERE, 1, (byte) 0).withTraits(Personality.typical());
        registry.put(outsider);

        Deed deed = aKilling(0);
        DeedBus.record(registry, deed, List.of(residentsOf(registry).get(0)), 0);
        Gossip.drain(registry, VILLAGE, 0);
        Gossip.drain(registry, VILLAGE, 0);

        assertTrue(registry.memories().of(outsider.id()).isEmpty(),
                "session 10 is what carries a story down a road; a drain does not leak across one");
    }

    /**
     * <b>The cost bound, pinned rather than claimed.</b>
     *
     * <p>{@code DESIGN.md} §8 puts the drain on a 250-tick cadence, and the honest worry is a drain
     * that walks every settlement in a two-hundred-village save every 250 ticks. It does not: a
     * settlement is in the map only while it has an unspent story, and a story is spent after two
     * drains. This is that sentence as a number.
     */
    @Test
    @DisplayName("the drain visits only settlements with something to say, whatever the save holds")
    void theDrainVisitsOnlySettlementsWithSomethingToSay() {
        NpcRegistry registry = village(9);
        for (int settlement = 100; settlement < 300; settlement++) {
            registry.put(Persona.create(new UUID(0xE1, settlement), 0L)
                    .placed(settlement, 1, (byte) 0).withTraits(Personality.typical()));
        }
        assertEquals(0, Gossip.drainEverySettlement(registry, 0),
                "a world where nothing has happened costs nothing at all");

        DeedBus.record(registry, aKilling(0), List.of(residentsOf(registry).get(0)), 0);
        assertEquals(1, registry.gossip().settlements(),
                "one deed in one village puts one settlement in the map, of 201 that exist");

        assertEquals(1, Gossip.drainEverySettlement(registry, 0));
        assertEquals(1, Gossip.drainEverySettlement(registry, 0));
        assertEquals(0, Gossip.drainEverySettlement(registry, 0),
                "and it leaves the map after " + Deed.MAX_HOPS + " drains, by itself");

        // A whole in-game day of drains over a quiet world.
        for (int drain = 0; drain < Gossip.DRAINS_PER_DAY; drain++) {
            assertEquals(0, Gossip.drainEverySettlement(registry, 1));
        }
    }

    @Test
    @DisplayName("the cadence is four an in-game hour, which is what 250 ticks means")
    void theCadenceIsWhatDesignRules() {
        assertEquals(250, Gossip.DRAIN_INTERVAL_TICKS, "DESIGN.md §8");
        assertEquals(4, Gossip.DRAINS_PER_HOUR, "DESIGN.md §4 step 7: 4 per in-game hour");
        assertEquals(96, Gossip.DRAINS_PER_DAY);
    }

    /**
     * The transfer coin, which has to be a function rather than a random number.
     *
     * <p>Session 07 turned the build red for choosing a simulation's subject off the wall clock, and
     * a report that cannot be reproduced is not evidence. A shared {@code RandomSource} would have to
     * be seeded, persisted and advanced in lockstep with a save file; a hash of the story and the
     * hearer costs nothing and gives the same answer in a report and in a game.
     */
    @Test
    @DisplayName("who takes a story is decided by a function, not by a random number")
    void theTransferCoinIsDeterministicAndFair() {
        Deed told = aKilling(1).retold();
        for (int i = 0; i < 50; i++) {
            UUID resident = new UUID(0xC01, i);
            assertEquals(Gossip.takes(told, resident), Gossip.takes(told, resident),
                    "the same story and the same hearer must always agree");
        }

        int took = 0;
        int trials = 20_000;
        for (int i = 0; i < trials; i++) {
            if (Gossip.takes(told, new UUID(0xC01, i))) {
                took++;
            }
        }
        float rate = (float) took / trials;
        assertTrue(Math.abs(rate - Gossip.TRANSFER_CHANCE) < 0.02F,
                () -> "the coin came up " + rate + " against a ruled " + Gossip.TRANSFER_CHANCE);

        // And the second telling is not offered to exactly the people who refused the first.
        Deed again = told.retold();
        int changed = 0;
        for (int i = 0; i < 200; i++) {
            UUID resident = new UUID(0xC01, i);
            if (Gossip.takes(told, resident) != Gossip.takes(again, resident)) {
                changed++;
            }
        }
        final int moved = changed;
        assertTrue(moved > 20, () -> "only " + moved + " of 200 residents answered the second "
                + "telling differently, so confidence is not in the coin and two drains reach the "
                + "same 35% twice");
    }

    // --- persistence -------------------------------------------------------------------------------

    @Test
    @DisplayName("the deques survive a save and a load, in order and per settlement")
    void gossipRoundTrips() {
        Gossip original = new Gossip();
        original.enqueue(VILLAGE, aKilling(1));
        original.enqueue(VILLAGE, aKilling(2).retold());
        original.enqueue(ELSEWHERE, aKilling(3));

        CompoundTag tag = new CompoundTag();
        original.save(tag);
        Gossip reloaded = new Gossip();
        assertEquals(0, reloaded.readFrom(tag));

        assertEquals(original.of(VILLAGE), reloaded.of(VILLAGE));
        assertEquals(original.of(ELSEWHERE), reloaded.of(ELSEWHERE));
        assertEquals(70, reloaded.of(VILLAGE).get(1).confidence(),
                "a story halfway through its life comes back halfway through its life");
        assertEquals(3, reloaded.size());
        assertEquals(2, reloaded.settlements());
    }

    /**
     * <b>The whole content of the schema 5 → 6 migration.</b>
     *
     * <p>Read as damage, {@code NpcRegistry} goes read-only and a world that has been played for a
     * week silently stops saving its personas, settlements, bonds and rings — because there is one
     * file. This is the direction that matters, and it has been reverted and watched to fail.
     */
    @Test
    @DisplayName("a tag written before schema 6 reads as nothing in flight, not as damage")
    void anAbsentTableIsNotDamage() {
        Gossip gossip = new Gossip();
        assertEquals(0, gossip.readFrom(new CompoundTag()));
        assertEquals(0, gossip.size());
        assertTrue(gossip.isEmpty());
    }

    @Test
    @DisplayName("an unreadable rumour is counted, and costs one story rather than a village")
    void oneBadRumourIsCountedAndTheRestSurvive() {
        Gossip good = new Gossip();
        good.enqueue(VILLAGE, aKilling(1));
        good.enqueue(VILLAGE, aKilling(2));
        CompoundTag tag = new CompoundTag();
        good.save(tag);
        tag.getList("gossip", Tag.TAG_COMPOUND).getCompound(0)
                .getList("queue", Tag.TAG_COMPOUND).add(new CompoundTag());

        Gossip reloaded = new Gossip();
        assertEquals(1, reloaded.readFrom(tag), "the damage must be reported so the file is not "
                + "written back over the records that could not be read");
        assertEquals(2, reloaded.of(VILLAGE).size(), "and the readable ones still load");
    }

    @Test
    @DisplayName("a record with no settlement id is damage rather than a deque belonging nowhere")
    void aRecordWithNoSettlementIsCounted() {
        CompoundTag tag = new CompoundTag();
        ListTag list = new ListTag();
        list.add(new CompoundTag());
        tag.put("gossip", list);

        assertEquals(1, new Gossip().readFrom(tag));
    }

    @Test
    @DisplayName("a longer deque on disk is truncated to the newest, not refused")
    void anOverlongDequeIsTruncatedRatherThanRefused() {
        ListTag queue = new ListTag();
        for (int day = 0; day < 50; day++) {
            queue.add(Deed.CODEC.encodeStart(NbtOps.INSTANCE, aKilling(day)).getOrThrow());
        }
        CompoundTag entry = new CompoundTag();
        entry.putInt("settlement", VILLAGE);
        entry.put("queue", queue);
        ListTag list = new ListTag();
        list.add(entry);
        CompoundTag tag = new CompoundTag();
        tag.put("gossip", list);

        Gossip reloaded = new Gossip();
        assertEquals(0, reloaded.readFrom(tag), "a bound this build does not share is not damage");
        assertEquals(Gossip.DEQUE_CAPACITY, reloaded.of(VILLAGE).size());
        assertEquals(49, reloaded.of(VILLAGE).get(Gossip.DEQUE_CAPACITY - 1).gameDay());
    }

    // --- the registry doors ------------------------------------------------------------------------

    @Test
    @DisplayName("a rumour that changes nothing does not mark the whole registry dirty")
    void onlyARealChangeMarksTheFileDirty() {
        NpcRegistry registry = village(4);
        Deed deed = aKilling(0);

        registry.setDirty(false);
        assertTrue(registry.enqueueRumour(deed));
        assertTrue(registry.isDirty(), "a new story reaches the file");

        registry.setDirty(false);
        assertFalse(registry.enqueueRumour(deed),
                "nine identical feedings are one deed and therefore one rumour");
        assertFalse(registry.isDirty(),
                "and eight of them must not have Minecraft rewrite every persona, settlement, bond "
                        + "and ring in the world at the next autosave");
    }

    /**
     * <b>A rumour reaches you on the day you hear it, never on the day it happened.</b>
     *
     * <p>{@code Bond.apply} stamps {@code lastSeenDay} with the day it is handed, and a story queued
     * before midnight can be drained after it. Handing the bond the day the deed happened would set
     * that stamp <i>backwards</i>, and the next read would run the lazy decay over days it had
     * already decayed. Nothing in the emit path could produce this, which is exactly why it needed
     * looking for.
     */
    @Test
    @DisplayName("a story heard days later does not stamp a bond backwards")
    void aLateTellingDoesNotRewindTheBond() {
        NpcRegistry registry = village(4);
        Persona hearer = residentsOf(registry).get(0);
        registry.putBond(hearer.id(), PLAYER,
                Bond.fresh(5).apply(new int[]{0, 10, 0, 0}, 5, Bond.DAILY_CAP));
        assertEquals(5, registry.bonds().stored(hearer.id(), PLAYER).orElseThrow().lastSeenDay());

        // A deed from day 0, told on day 5 — the shape a deque that survives a night produces.
        DeedBus.deliver(registry, aKilling(0).retold(), hearer, 5);

        assertEquals(5, registry.bonds().stored(hearer.id(), PLAYER).orElseThrow().lastSeenDay(),
                "a bond stamped with a day earlier than the one it already holds would run the "
                        + "lazy decay backwards on the very next read");
    }

    /**
     * The backstop to {@code DeedBus.deliver}'s blur guard.
     *
     * <p>Two doors rather than one, for the reason the profiling-fixture refusal has two: what is
     * being guarded against is silent. A bond about nobody loads perfectly and reads as a real
     * relationship for ever after.
     */
    @Test
    @DisplayName("the registry refuses a bond about the unknown actor, whatever asks for one")
    void noBondMayBeHeldAboutNobody() {
        NpcRegistry registry = village(2);
        Persona holder = residentsOf(registry).get(0);

        registry.putBond(holder.id(), Deed.UNKNOWN_ACTOR,
                Bond.fresh(0).apply(new int[]{5, 5, 0, 0}, 0, Bond.DAILY_CAP));

        assertTrue(registry.bonds().stored(holder.id(), Deed.UNKNOWN_ACTOR).isEmpty(),
                "an unattributed rumour moves nobody's opinion of anybody, because nobody knows "
                        + "who did it");
        assertEquals(0, registry.bonds().size());
    }

    @Test
    @DisplayName("a drain marks the file dirty, because the village knows the story less well after it")
    void aDrainMarksTheFileDirty() {
        NpcRegistry registry = village(6);
        registry.enqueueRumour(aKilling(0));

        registry.setDirty(false);
        Gossip.drain(registry, VILLAGE, 0);
        assertTrue(registry.isDirty(),
                "a rumour that quietly failed to degrade across a reload would travel further than "
                        + "the design permits");

        registry.setDirty(false);
        Gossip.drain(registry, ELSEWHERE, 0);
        assertFalse(registry.isDirty(), "and a drain that finds nothing changes nothing");
    }

    /**
     * <b>The second fixture the breakage pass found missing.</b>
     *
     * <p>Removing {@code setDirty} from the drain turned nothing red, because in the fixture above
     * somebody heard the story and {@code NpcRegistry.remember} marks the file dirty on its own. The
     * case the drain's own flag actually protects is the one where <i>nobody</i> is left to tell —
     * the village's copy is worse attested than it was and not one ring changed. Without it that
     * degradation never reaches the disk, and a story reloads with the confidence it had an hour ago
     * and travels further than the design permits.
     */
    @Test
    @DisplayName("a drain marks the file dirty even when there is nobody left to tell")
    void aDrainThatTellsNobodyStillDegradesTheStory() {
        NpcRegistry registry = village(5);
        List<Persona> residents = residentsOf(registry);
        // Everybody watched it, so the drain has nobody to reach and no ring can change.
        DeedBus.record(registry, aKilling(0), residents, residents.size() - 1);

        registry.setDirty(false);
        Gossip.Drained drained = Gossip.drain(registry, VILLAGE, 0);

        assertEquals(0, drained.heard(), "the fixture has to leave nobody to tell, or it proves nothing");
        assertEquals(70, registry.gossip().of(VILLAGE).get(0).confidence(),
                "the village's own copy is worse attested than it was a moment ago");
        assertTrue(registry.isDirty(),
                "and that has to reach the disk, or the story reloads with the confidence it had an "
                        + "hour ago and travels further than DESIGN.md permits");
    }
}
