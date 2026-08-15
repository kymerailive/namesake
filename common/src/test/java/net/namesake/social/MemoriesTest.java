package net.namesake.social;

import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.namesake.npc.Persona;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The deed ring: capacity, overflow, dedupe, order, the repeat count, the eviction policy and the
 * packed round trip.
 *
 * <p>All of it is arithmetic over immutable values, which is why the memory-depth work earns no new
 * harness leg of its own. {@code WORKPLAN.md} draws the line: anything a unit test can prove belongs
 * in a unit test, and the in-game harness is for what only a running game can show. A ring is not
 * that; a ring <i>surviving a schema 7 migration in a real save</i> is, and that is where the leg is.
 */
class MemoriesTest {

    private static final UUID A_PLAYER = UUID.fromString("0a0a0a0a-1111-2222-3333-444444444444");
    private static final UUID ANOTHER_PLAYER = UUID.fromString("0b0b0b0b-1111-2222-3333-444444444444");
    private static final UUID ANNA = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final UUID BRAM = UUID.fromString("66666666-7777-8888-9999-aaaaaaaaaaaa");

    /** A deed that differs from its neighbours only in the day it happened on. */
    private static Deed onDay(int day) {
        return Deed.of(DeedType.FED_HUNGRY, A_PLAYER, ANNA, 0, day);
    }

    private static Deed gift(int day) {
        return Deed.of(DeedType.GIFT_WANTED, A_PLAYER, ANNA, 0, day);
    }

    // --- capacity and overflow ---------------------------------------------------------------

    @Test
    @DisplayName("more deeds than the ring holds leave the newest, in order")
    void overflowKeepsTheNewest() {
        Memories memories = new Memories();
        int emitted = Memories.RING_CAPACITY + 8;
        for (int day = 0; day < emitted; day++) {
            assertTrue(memories.remember(ANNA, onDay(day)), "day " + day + " is a deed nobody held");
        }

        List<Deed> ring = memories.of(ANNA);
        assertEquals(Memories.RING_CAPACITY, ring.size());
        assertEquals(128, Memories.RING_CAPACITY,
                "the owner ruled 32 -> 128 at the close of session 08");
        assertEquals(8, ring.get(0).gameDay(), "the oldest survivor is the last-but-capacity");
        assertEquals(emitted - 1, ring.get(ring.size() - 1).gameDay(), "the newest is the last one in");

        for (int slot = 0; slot < ring.size(); slot++) {
            assertEquals(8 + slot, ring.get(slot).gameDay(), "the ring is ordered oldest first");
        }
        assertEquals(Memories.RING_CAPACITY, memories.size());
        assertEquals(1, memories.holders());
    }

    @Test
    @DisplayName("one NPC overflowing does not touch another's ring")
    void ringsAreIndependent() {
        Memories memories = new Memories();
        for (int day = 0; day < Memories.RING_CAPACITY + 8; day++) {
            memories.remember(ANNA, onDay(day));
        }
        memories.remember(BRAM, onDay(3));

        assertEquals(Memories.RING_CAPACITY, memories.of(ANNA).size());
        assertEquals(1, memories.of(BRAM).size());
        assertEquals(3, memories.of(BRAM).get(0).gameDay(),
                "Bram's one memory is not evicted by Anna's overflow");
        assertEquals(Memories.RING_CAPACITY + 1, memories.size());
        assertEquals(2, memories.holders());
    }

    @Test
    @DisplayName("an NPC who has seen nothing has an empty ring rather than a null one")
    void anUnknownHolderReadsAsEmpty() {
        Memories memories = new Memories();
        assertEquals(List.of(), memories.of(ANNA));
        assertEquals(List.of(), memories.slotsOf(ANNA));
        assertFalse(memories.remembers(ANNA, onDay(1).id()));
        assertEquals(0, memories.size());
        assertEquals(0, memories.holders());
    }

    // --- dedupe and the repeat count -----------------------------------------------------------

    /**
     * <b>Session 06's test, with session 09's answer.</b>
     *
     * <p>That session made a duplicate report "nothing changed" specifically so nine identical
     * feedings would not mark the registry dirty nine times. The owner ruled a repeat count at the
     * close of session 08, so a repeat now genuinely <i>is</i> a change and says so — and the cost is
     * near zero, because the first of the nine has already marked the registry dirty and
     * {@code setDirty} on an already-dirty registry is free.
     */
    @Test
    @DisplayName("nine identical feedings are one memory that knows it happened nine times")
    void exactDedupeCollapsesRepeatsAndCountsThem() {
        Memories memories = new Memories();
        assertTrue(memories.remember(ANNA, onDay(4)));
        for (int i = 0; i < 8; i++) {
            assertTrue(memories.remember(ANNA, onDay(4)),
                    "a second occurrence is a change to what this villager holds");
        }
        assertEquals(1, memories.of(ANNA).size(), "and it is still one memory, not nine");
        assertEquals(9, memories.slotsOf(ANNA).get(0).repeats());
        assertFalse(memories.slotsOf(ANNA).get(0).happenedOnce());
    }

    @Test
    @DisplayName("the count saturates rather than wrapping, and stops reporting changes when it does")
    void theCountSaturates() {
        Memories memories = new Memories();
        memories.remember(ANNA, onDay(4));
        for (int i = 1; i < Memories.MAX_REPEATS; i++) {
            memories.remember(ANNA, onDay(4));
        }
        assertEquals(Memories.MAX_REPEATS, memories.slotsOf(ANNA).get(0).repeats());
        assertFalse(memories.remember(ANNA, onDay(4)),
                "a saturated count has nothing left to write, so the registry must not be dirtied");
    }

    /**
     * <b>Session 06's sentence, held against session 09's new field.</b>
     *
     * <p><i>Being told a thing again is not the thing happening again.</i> Confidence is what
     * discriminates them: {@link Deed#retold()} strictly lowers it, and nothing but an emit produces
     * {@link Deed#FIRST_HAND}. Without that test a village talking about one afternoon would inflate
     * everybody's count of it.
     */
    @Test
    @DisplayName("being told about a thing again does not make it have happened twice")
    void aRetellingIsNotASecondOccurrence() {
        Memories memories = new Memories();
        memories.remember(ANNA, onDay(5));
        assertFalse(memories.remember(ANNA, onDay(5).retold()));
        assertEquals(1, memories.slotsOf(ANNA).get(0).repeats());

        // And the upgrade direction: watching a thing you had been told about is a better account of
        // one event, not a second one.
        Memories told = new Memories();
        told.remember(BRAM, onDay(5).retold());
        assertTrue(told.remember(BRAM, onDay(5)));
        assertEquals(1, told.slotsOf(BRAM).get(0).repeats());
        assertEquals(Deed.FIRST_HAND, told.of(BRAM).get(0).confidence());
    }

    /**
     * <b>Two of one thing on one day are one memory that happened twice.</b>
     *
     * <p>Until session 12 this test was about the object as well: a loaf and an apple on one day
     * were one memory that could name neither of them, which is {@code DESIGN.md} §2's rule for
     * gossip applied to a ring. {@code Deed.item} lost its rule 5 exemption with no consumer to
     * name, so there is no longer an object to disagree about — and what the slot was always
     * <i>for</i> is here: the count, which is session 09's second memory-depth route and is
     * consumed by the eviction policy.
     */
    @Test
    @DisplayName("two of one thing on one day are one memory that happened twice")
    void aRepeatIsCountedRatherThanStored() {
        Memories memories = new Memories();
        memories.remember(ANNA, gift(3));
        assertTrue(memories.remember(ANNA, gift(3)));
        assertEquals(1, memories.of(ANNA).size(), "still one memory");
        assertEquals(2, memories.slotsOf(ANNA).get(0).repeats(), "that happened twice");
    }

    /**
     * The property the ring exists to have, stated as the thing a player can actually do.
     *
     * <p>A full ring of distinct memories, then an afternoon of repeating one gift. With assigned
     * deed ids every one of those gifts is a new entry and the ring ends up holding one afternoon;
     * content addressing is what keeps everything else this villager knows about you.
     */
    @Test
    @DisplayName("a day of repeating yourself cannot evict what a villager already knows")
    void theRingIsNotGrindable() {
        Memories memories = new Memories();
        for (int day = 0; day < Memories.RING_CAPACITY; day++) {
            memories.remember(ANNA, onDay(day));
        }
        assertEquals(Memories.RING_CAPACITY, memories.of(ANNA).size());

        int today = Memories.RING_CAPACITY;
        Deed theKilling = Deed.of(DeedType.KILLED_RESIDENT, A_PLAYER, BRAM, 0, today);
        memories.remember(ANNA, theKilling);

        for (int i = 0; i < 500; i++) {
            memories.remember(ANNA, Deed.of(DeedType.GIFT_WANTED, A_PLAYER, ANNA, 0, today));
        }

        assertEquals(Memories.RING_CAPACITY, memories.of(ANNA).size());
        assertTrue(memories.remembers(ANNA, theKilling.id()),
                "five hundred identical gifts on one day must not push out a killing");
        assertEquals(2, memories.of(ANNA).stream()
                        .filter(deed -> deed.gameDay() == today).count(),
                "the whole afternoon is one entry, next to the killing");
        assertEquals(2, memories.of(ANNA).get(0).gameDay(),
                "only the two oldest days were pushed out, not the whole ring");
    }

    /**
     * <b>The question session 07 said its numbers could not settle, answered.</b>
     *
     * <p><i>"Nothing in this run gave a villager a killing to keep, so nothing has yet tested whether
     * thirty-two subsequent gifts push one out."</i> Until session 09 the ring evicted its head, so
     * the answer was yes — a player could bury what they did under enough of what they did
     * afterwards, one day at a time, and content addressing did not stop it because every day is a
     * different deed.
     */
    @Test
    @DisplayName("a killing outlives a whole ring's worth of later kindness, day after day")
    void aHarmfulDeedIsNeverEvictedForAKindness() {
        Memories memories = new Memories();
        Deed theKilling = Deed.of(DeedType.KILLED_RESIDENT, A_PLAYER, BRAM, 0, 0);
        memories.remember(ANNA, theKilling);

        for (int day = 1; day <= Memories.RING_CAPACITY * 2; day++) {
            memories.remember(ANNA, onDay(day));
        }

        assertEquals(Memories.RING_CAPACITY, memories.of(ANNA).size());
        assertTrue(memories.remembers(ANNA, theKilling.id()),
                "two rings' worth of feeding does not buy back a killing");
        assertEquals(0, memories.of(ANNA).get(0).gameDay(),
                "and it is still the oldest thing they hold, in its own slot");
    }

    /**
     * <b>The repeat count deciding something.</b> Revert {@link Memories#evictionWeight}'s repeat
     * term and this is the test that goes red — which is what makes {@code Slot.repeats} a social
     * value with a consumer rather than a number in a report.
     */
    @Test
    @DisplayName("an afternoon that happened nine times outlives a single gift of the same age")
    void aRepeatedMemoryOutlivesASingleOne() {
        Memories memories = new Memories();
        // Day 0 happened nine times; every other day happened once.
        for (int i = 0; i < 9; i++) {
            memories.remember(ANNA, onDay(0));
        }
        for (int day = 1; day < Memories.RING_CAPACITY; day++) {
            memories.remember(ANNA, onDay(day));
        }
        assertEquals(Memories.RING_CAPACITY, memories.of(ANNA).size());

        memories.remember(ANNA, onDay(Memories.RING_CAPACITY));

        assertTrue(memories.remembers(ANNA, onDay(0).id()),
                "the day they were fed nine times is the strongest benign memory in the ring");
        assertFalse(memories.remembers(ANNA, onDay(1).id()),
                "so the single gift the day after is what went, though it is newer");
        assertEquals(Memories.RING_CAPACITY, memories.of(ANNA).size());
    }

    /**
     * The cap that stops the repeat count reopening the grind content addressing closed.
     *
     * <p>Without it, five hundred identical gifts would be the most strongly held benign memory a
     * villager has for ever, and a player could pin a slot by clicking. {@link Memories#REPEATS_COUNTED}
     * is {@link Bond#DAILY_CAP} — as memorable as one in-game day can get — and beyond it a repeat
     * buys nothing.
     */
    @Test
    @DisplayName("a hundred repeats is worth no more to the eviction policy than eight")
    void theRepeatContributionIsCapped() {
        Memories memories = new Memories();
        for (int i = 0; i < 100; i++) {
            memories.remember(ANNA, onDay(0));
        }
        for (int i = 0; i < Memories.REPEATS_COUNTED; i++) {
            memories.remember(ANNA, onDay(1));
        }
        Memories.Slot hundred = memories.slotsOf(ANNA).get(0);
        Memories.Slot eight = memories.slotsOf(ANNA).get(1);
        assertEquals(100, hundred.repeats());
        assertEquals(Memories.REPEATS_COUNTED, eight.repeats());
        assertEquals(Memories.evictionWeight(eight), Memories.evictionWeight(hundred),
                "past the cap a repeat buys nothing, or a grinder owns a slot for ever");

        // And a harmful deed still outranks both, whatever they add up to.
        Memories harm = new Memories();
        harm.remember(BRAM, Deed.of(DeedType.STRUCK_RESIDENT, A_PLAYER, BRAM, 0, 9));
        assertTrue(Memories.evictionWeight(harm.slotsOf(BRAM).get(0))
                        > Memories.evictionWeight(hundred),
                "a harmful deed outranks every kindness however often it happened");
    }

    @Test
    @DisplayName("a duplicate does not move the entry it duplicates to the newest slot")
    void aDuplicateDoesNotReorderTheRing() {
        Memories memories = new Memories();
        Deed first = onDay(1);
        memories.remember(ANNA, first);
        memories.remember(ANNA, onDay(2));
        memories.remember(ANNA, first);

        // Refreshing the slot would let session 08's gossip push first-hand memories out of a ring
        // simply by retelling them.
        assertEquals(List.of(1, 2), memories.of(ANNA).stream().map(Deed::gameDay).toList());
    }

    @Test
    @DisplayName("two deeds that differ in any identity field are two deeds")
    void everyIdentityFieldSeparatesTwoDeeds() {
        Deed base = new Deed(DeedType.FED_HUNGRY.id(), A_PLAYER, ANNA, 3, 7,
                Deed.NOMINAL, Deed.FIRST_HAND);
        Memories memories = new Memories();
        memories.remember(ANNA, base);

        assertTrue(memories.remember(ANNA, new Deed(DeedType.GIFT_WANTED.id(), A_PLAYER, ANNA, 3, 7,
                Deed.NOMINAL, Deed.FIRST_HAND)), "a different kind of deed");
        assertTrue(memories.remember(ANNA, new Deed(DeedType.FED_HUNGRY.id(), ANOTHER_PLAYER, ANNA,
                3, 7, Deed.NOMINAL, Deed.FIRST_HAND)), "a different actor");
        assertTrue(memories.remember(ANNA, new Deed(DeedType.FED_HUNGRY.id(), A_PLAYER, BRAM, 3, 7,
                Deed.NOMINAL, Deed.FIRST_HAND)), "a different subject");
        assertTrue(memories.remember(ANNA, new Deed(DeedType.FED_HUNGRY.id(), A_PLAYER, ANNA, 4, 7,
                Deed.NOMINAL, Deed.FIRST_HAND)), "a different settlement");
        assertTrue(memories.remember(ANNA, new Deed(DeedType.FED_HUNGRY.id(), A_PLAYER, ANNA, 3, 8,
                Deed.NOMINAL, Deed.FIRST_HAND)), "a different day");
        assertTrue(memories.remember(ANNA, base.withSeverity((byte) 50)),
                "a different severity — a blow for two hearts is not the blow for eight");

        assertEquals(7, memories.of(ANNA).size(), "the base deed plus one per identity field");
    }

    @Test
    @DisplayName("the same event at a lower confidence is the same deed, not a second one")
    void confidenceIsNotPartOfWhatADeedIs() {
        Deed firstHand = onDay(2);
        Deed retold = new Deed(firstHand.typeId(), firstHand.actor(), firstHand.subject(),
                firstHand.settlementId(), firstHand.gameDay(), firstHand.severity(), (byte) 72);

        assertEquals(firstHand.id(), retold.id());

        Memories memories = new Memories();
        assertTrue(memories.remember(ANNA, firstHand));
        assertFalse(memories.remember(ANNA, retold));
        assertEquals(1, memories.of(ANNA).size());
        assertEquals(Deed.FIRST_HAND, memories.of(ANNA).get(0).confidence(),
                "being told about a thing you watched must not degrade your memory of it");
    }

    @Test
    @DisplayName("the better-attested copy of an event wins, and it does not move in the ring")
    void theBetterAttestedCopyWinsWithoutReordering() {
        Deed heard = onDay(5).retold();
        assertEquals(70, heard.confidence());

        Memories memories = new Memories();
        memories.remember(ANNA, heard);
        memories.remember(ANNA, onDay(9));

        assertTrue(memories.remember(ANNA, onDay(5)),
                "watching a thing you had only been told about is a change to what you know");
        assertEquals(2, memories.of(ANNA).size(), "and not a second row for one event");
        assertEquals(Deed.FIRST_HAND, memories.held(ANNA, heard.id()).orElseThrow().confidence());
        assertEquals(List.of(5, 9), memories.of(ANNA).stream().map(Deed::gameDay).toList(),
                "and the ring's order is decided by when things happened, never by when somebody "
                        + "last mentioned them");

        assertFalse(memories.remember(ANNA, heard));
        assertEquals(Deed.FIRST_HAND, memories.held(ANNA, heard.id()).orElseThrow().confidence());
    }

    @Test
    @DisplayName("a better-attested copy evicts nothing, because it takes the slot it already had")
    void anUpgradeEvictsNothing() {
        Memories memories = new Memories();
        for (int day = 0; day < Memories.RING_CAPACITY; day++) {
            memories.remember(ANNA, onDay(day).retold());
        }
        assertEquals(Memories.RING_CAPACITY, memories.of(ANNA).size());

        for (int day = 0; day < Memories.RING_CAPACITY; day++) {
            assertTrue(memories.remember(ANNA, onDay(day)));
        }
        assertEquals(Memories.RING_CAPACITY, memories.of(ANNA).size());
        assertEquals(0, memories.of(ANNA).get(0).gameDay(), "the oldest is still the oldest");
        assertTrue(memories.of(ANNA).stream().allMatch(deed -> deed.confidence() == Deed.FIRST_HAND));
    }

    // --- removal -------------------------------------------------------------------------------

    @Test
    @DisplayName("forgetting one NPC leaves everyone else's ring alone")
    void forgetDropsOneRing() {
        Memories memories = new Memories();
        memories.remember(ANNA, onDay(1));
        memories.remember(BRAM, onDay(1));

        assertTrue(memories.forget(ANNA));
        assertFalse(memories.forget(ANNA), "forgetting twice is not a change");
        assertEquals(0, memories.of(ANNA).size());
        assertEquals(1, memories.of(BRAM).size());
    }

    // --- persistence ---------------------------------------------------------------------------

    @Test
    @DisplayName("a ring survives a save and a load with its order, its objects and its counts")
    void ringsRoundTrip() {
        Memories original = new Memories();
        for (int day = 0; day < Memories.RING_CAPACITY + 8; day++) {
            original.remember(ANNA, onDay(day));
        }
        // An afternoon with a count and an object on it, so both new fields cross the disk.
        original.remember(ANNA, gift(200));
        original.remember(ANNA, gift(200));
        original.remember(ANNA, gift(200));

        Deed struck = Deed.of(DeedType.STRUCK_RESIDENT, ANOTHER_PLAYER, BRAM, 4, 12)
                .withSeverity((byte) 37);
        original.remember(BRAM, struck);

        CompoundTag tag = new CompoundTag();
        original.save(tag);
        Memories reloaded = new Memories();
        assertEquals(0, reloaded.readFrom(tag));

        assertEquals(original.of(ANNA), reloaded.of(ANNA));
        assertEquals(original.slotsOf(ANNA), reloaded.slotsOf(ANNA), "including every count");
        assertEquals(List.of(struck), reloaded.of(BRAM));
        assertEquals(37, reloaded.of(BRAM).get(0).severity(), "severity is not lost in the round trip");
        assertEquals(3, reloaded.slotsOf(ANNA).get(reloaded.of(ANNA).size() - 1).repeats());
        assertEquals(Memories.RING_CAPACITY + 1, reloaded.size());
        assertEquals(2, reloaded.holders());
    }

    @Test
    @DisplayName("a tag written before schema 5 reads as an empty table, not as damage")
    void anAbsentTableIsNotDamage() {
        Memories memories = new Memories();
        assertEquals(0, memories.readFrom(new CompoundTag()));
        assertEquals(0, memories.size());
    }

    /**
     * <b>The trap the packed format introduced, and the reason it is checked by name.</b>
     *
     * <p>Vanilla's {@code CompoundTag.getByteArray} returns an <i>empty array</i> for a key holding
     * the wrong tag type. So a schema-6 ring — a {@code ListTag} of compounds — read by this build
     * without its migration would come back as a villager who remembers nothing: no error, no crash,
     * and then written back that way at the next autosave. That is exactly the shape of failure hard
     * rule 1 exists for, arriving through a type mismatch rather than through a missing fixer.
     */
    @Test
    @DisplayName("a schema-6 ring is damage rather than an empty one")
    void aSchemaSixRingIsNotReadAsEmpty() {
        ListTag ring = new ListTag();
        for (int day = 0; day < 4; day++) {
            ring.add(Deed.CODEC.encodeStart(NbtOps.INSTANCE, onDay(day)).getOrThrow());
        }
        CompoundTag entry = new CompoundTag();
        entry.putIntArray("holder", UUIDUtil.uuidToIntArray(ANNA));
        entry.put("ring", ring);
        ListTag list = new ListTag();
        list.add(entry);
        CompoundTag tag = new CompoundTag();
        tag.put("memories", list);

        Memories reloaded = new Memories();
        assertEquals(1, reloaded.readFrom(tag),
                "an unmigrated ring must make the registry read-only rather than load as nothing");
        assertEquals(0, reloaded.of(ANNA).size());
    }

    @Test
    @DisplayName("a ring that is not a whole number of slots is damage")
    void aTruncatedRingIsCounted() {
        Memories original = new Memories();
        original.remember(ANNA, onDay(1));
        CompoundTag tag = new CompoundTag();
        original.save(tag);

        CompoundTag entry = tag.getList("memories", Tag.TAG_COMPOUND).getCompound(0);
        byte[] packed = entry.getByteArray("ring");
        entry.putByteArray("ring", java.util.Arrays.copyOf(packed, packed.length - 3));

        assertEquals(1, new Memories().readFrom(tag));
    }

    @Test
    @DisplayName("a palette index pointing at nothing costs one memory, not a whole ring")
    void aBadPaletteIndexIsCountedAndTheRestSurvive() {
        Memories original = new Memories();
        original.remember(ANNA, onDay(1));
        original.remember(ANNA, onDay(2));
        CompoundTag tag = new CompoundTag();
        original.save(tag);

        // The actor index of the second slot, four bytes in at offset RECORD_BYTES + 2.
        CompoundTag entry = tag.getList("memories", Tag.TAG_COMPOUND).getCompound(0);
        byte[] packed = entry.getByteArray("ring");
        java.nio.ByteBuffer.wrap(packed).putInt(25 + 2, 9999);
        entry.putByteArray("ring", packed);

        Memories reloaded = new Memories();
        assertEquals(1, reloaded.readFrom(tag), "the damage must be reported so the file is not "
                + "written back over the record that could not be read");
        assertEquals(1, reloaded.of(ANNA).size(), "and the readable deed still loads");
        assertEquals(1, reloaded.of(ANNA).get(0).gameDay());
    }

    @Test
    @DisplayName("a record with no holder id is damage rather than a ring belonging to nobody")
    void aRecordWithNoHolderIsCounted() {
        CompoundTag tag = new CompoundTag();
        ListTag list = new ListTag();
        list.add(new CompoundTag());
        tag.put("memories", list);

        assertEquals(1, new Memories().readFrom(tag));
    }

    /**
     * A save written by a build with a bigger ring must not make this one refuse the world.
     *
     * <p>The bound is this build's, not the file's, and it is trimmed by the same eviction rule an
     * overflow uses rather than by taking the tail — so a killing in a longer ring survives being
     * read by a build that keeps fewer.
     */
    @Test
    @DisplayName("a longer ring on disk is trimmed by the eviction rule, not refused")
    void anOverlongRingIsTrimmedRatherThanRefused() {
        // Built by hand, because a Memories at this capacity cannot produce an over-long ring to
        // read back — which is the point: the file was written by a build that could.
        int written = Memories.RING_CAPACITY + 20;
        byte[] packed = new byte[written * 21];
        java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(packed);
        for (int day = 0; day < written; day++) {
            // The first slot is a killing; everything after it is a gift.
            buffer.putShort(day == 0 ? DeedType.KILLED_RESIDENT.id() : DeedType.FED_HUNGRY.id());
            buffer.putInt(0);
            buffer.putInt(1);
            buffer.putInt(0);
            buffer.putInt(day);
            buffer.put(Deed.NOMINAL);
            buffer.put(Deed.FIRST_HAND);
            buffer.put((byte) 1);
        }
        CompoundTag entry = new CompoundTag();
        entry.putIntArray("holder", UUIDUtil.uuidToIntArray(ANNA));
        entry.putByteArray("ring", packed);
        ListTag list = new ListTag();
        list.add(entry);
        CompoundTag tag = new CompoundTag();
        tag.put("memories", list);
        int[] actors = new int[8];
        System.arraycopy(UUIDUtil.uuidToIntArray(A_PLAYER), 0, actors, 0, 4);
        System.arraycopy(UUIDUtil.uuidToIntArray(ANNA), 0, actors, 4, 4);
        tag.putIntArray("memoryActors", actors);

        Memories reloaded = new Memories();
        assertEquals(0, reloaded.readFrom(tag), "a bound this build does not share is not damage");
        assertEquals(Memories.RING_CAPACITY, reloaded.of(ANNA).size());
        assertEquals(0, reloaded.of(ANNA).get(0).gameDay(),
                "and the killing is what survived the trim, not the twenty oldest gifts");
        assertEquals(DeedType.KILLED_RESIDENT, reloaded.of(ANNA).get(0).type());
        assertEquals(written - 1, reloaded.of(ANNA).get(Memories.RING_CAPACITY - 1).gameDay());
    }

    // --- what it costs -------------------------------------------------------------------------

    /**
     * The size counter-argument to "one file", measured rather than waved away — <b>and the session
     * where it finally bit.</b>
     *
     * <p>{@code DESIGN.md} §8's four hundred records, every one of them holding a full ring: 51,200
     * deeds at session 09's capacity, which is the state of a save where a player has personally done
     * a hundred and twenty-eight distinct things in front of every villager in fifty villages. It
     * cannot happen, and it is the number the format has to survive.
     *
     * <p><b>Session 06 measured the readable form at 122.3 B a deed</b> — 1.57 MB of NBT and 46 KB
     * gzipped at thirty-two slots, against ceilings of 2,000,000 B and 100,000 B. Its own note said
     * what to do when they went red: <i>the fix is a decision — shorter keys, a packed ring, or a
     * smaller capacity — not a bigger number here.</i> At a hundred and twenty-eight slots the same
     * encoding is 6.26 MB and 186 KB, so both went red, and this is that decision made: a fixed-width
     * packed record behind an actor palette and an item palette.
     *
     * <h2>What it measured, and the one ceiling that was re-ruled</h2>
     *
     * <p><b>Measured 2026-08-15: 1,303,029 B of NBT and 153,437 B gzipped — 25.4 B and 3.0 B per
     * deed.</b> Two different answers, and they are worth separating because they are the two halves
     * of what session 08 predicted would go red.
     *
     * <ul>
     *   <li><b>The tag tree, which is the cost actually paid on every save, is unchanged at 2 MB.</b>
     *       It came in at 1.30 MB — a ring four times deeper, each memory carrying two things it did
     *       not, fitting inside a budget set for a table a quarter of the size. Session 08 forecast
     *       6.26 MB for the readable encoding, and that is what the packed one bought.</li>
     *   <li><b>The gzipped figure was 100,000 B and is now 200,000 B</b>, re-ruled here with the
     *       measurement in hand exactly as {@code WORKPLAN.md} required. The per-deed cost went
     *       <i>down</i> — 3.6 B at session 06, 3.0 B now — and the total went up because there are
     *       four times as many deeds. So the old ceiling was not a statement about the format; it was
     *       a statement about a 12,800-deed table, and the table is 51,200 deeds.</li>
     * </ul>
     *
     * <p><b>Two hundred thousand is chosen so that the next capacity raise turns it red</b>, which is
     * the decision it exists to force. Doubling again would be ~307 KB compressed and ~2.6 MB raw, so
     * both ceilings would fire together — and session 06's original rule still holds unchanged: <b>if
     * it goes red the fix is a decision, not a bigger number here.</b>
     */
    @Test
    @DisplayName("the worst-case memory table is measured, and it fits")
    void theWorstCaseTableFitsInItsBudget() throws IOException {
        // A realistic object distribution rather than none: a table of one item id would flatter the
        // palette, and a table of fifty thousand distinct ones is not a thing a player can do.
        String[] items = {"minecraft:bread", "minecraft:wheat", "minecraft:carrot",
                "minecraft:emerald", "minecraft:cooked_beef", "minecraft:potato",
                "minecraft:golden_apple", "minecraft:paper"};

        Memories memories = new Memories();
        for (int holder = 0; holder < 400; holder++) {
            UUID persona = new UUID(0x5EED_0000_0000_0000L, holder);
            for (int day = 0; day < Memories.RING_CAPACITY; day++) {
                memories.remember(persona, new Deed(DeedType.FED_HUNGRY.id(), A_PLAYER, persona,
                        holder % 50, day, Deed.NOMINAL, Deed.FIRST_HAND));
            }
        }
        assertEquals(400 * Memories.RING_CAPACITY, memories.size());

        CompoundTag tag = new CompoundTag();
        memories.save(tag);

        int raw = uncompressedBytes(tag);
        int compressed = compressedBytes(tag);
        System.out.printf("[memories] 400 personas x %d deeds = %d deeds: %,d B of NBT, "
                        + "%,d B gzipped (%.1f B and %.1f B per deed)%n",
                Memories.RING_CAPACITY, memories.size(), raw, compressed,
                raw / (double) memories.size(), compressed / (double) memories.size());

        assertTrue(raw < 2_000_000,
                "the tag tree built on every save was " + raw + " B, over the 2,000,000 B ceiling");
        assertTrue(compressed < 200_000,
                "the bytes written to namesake_npcs.dat were " + compressed
                        + " B, over the 200,000 B ceiling. That ceiling was re-ruled at session 09 "
                        + "with the measurement in hand and is set so the NEXT capacity raise trips "
                        + "it. The fix is a decision — a narrower record, a smaller RING_CAPACITY — "
                        + "not a bigger number here.");
    }

    private static int uncompressedBytes(CompoundTag tag) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            NbtIo.write(tag, out);
        }
        return bytes.size();
    }

    /** Exactly what {@code SavedData} writes: {@code NbtIo.writeCompressed} into the .dat file. */
    private static int compressedBytes(CompoundTag tag) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        NbtIo.writeCompressed(tag, bytes);
        return bytes.size();
    }

    // --- the id ---------------------------------------------------------------------------------

    /**
     * The derivation is behaviour, and it must not drift.
     *
     * <p>Changing the mix in {@link Deed#id()} re-partitions every ring in every existing save:
     * yesterday's duplicates become distinct, and a ring that held one afternoon starts holding a
     * hundred and twenty-eight copies of it. There is no schema break to catch that, because nothing
     * is stored — so this literal is the catch. If it goes red, that is a decision to make
     * deliberately, not a number to update.
     *
     * <p><b>The literal was computed outside this codebase</b> — the documented mix, run over the
     * eight inputs below in an independent implementation — rather than copied out of a first run.
     */
    @Test
    @DisplayName("the deed id of a fixed deed is pinned, so the derivation cannot drift by accident")
    void theDerivationIsPinned() {
        Deed fixed = new Deed(DeedType.FED_HUNGRY.id(), A_PLAYER, ANNA, 3, 7,
                Deed.NOMINAL, Deed.FIRST_HAND);
        assertEquals(-4535043805363013135L, fixed.id());
    }

    /**
     * <b>Session 09's field was carried and not identified by, and session 12 collected the
     * dividend.</b>
     *
     * <p>Folding {@code Deed.item} into the derivation was the obvious reading of "richer per
     * memory" and would have handed the ring back its grindability: an afternoon of one gift is one
     * entry, and an afternoon of eight different junk items would have been eight. Session 09
     * declined, and that decision is what made the field's deletion at session 12 free —
     * <b>the id above is the same literal it was at session 09</b>, so no ring in any save
     * re-partitions and the migration has no id to move. The pin two tests up is what says so, and
     * it is deliberately the assertion this note points at rather than a second one here.
     */
    @Test
    @DisplayName("two gifts on one day are one deed, and the derivation never knew what they were")
    void theObjectWasOutsideTheDerivation() {
        assertEquals(gift(7).id(), gift(7).id());
        assertEquals(Deed.of(DeedType.GIFT_WANTED, A_PLAYER, ANNA, 0, 7).id(), gift(7).id());
    }

    @Test
    @DisplayName("the six deed types at one moment produce six different ids")
    void theTypesDoNotCollide() {
        long[] ids = new long[DeedType.values().length];
        for (DeedType type : DeedType.values()) {
            ids[type.id()] = Deed.of(type, A_PLAYER, ANNA, Persona.UNASSIGNED, 0).id();
        }
        for (int i = 0; i < ids.length; i++) {
            for (int j = i + 1; j < ids.length; j++) {
                assertNotEquals(ids[i], ids[j],
                        DeedType.values()[i] + " and " + DeedType.values()[j] + " share an id");
            }
        }
    }

    @Test
    @DisplayName("a hundred thousand deeds across every field produce a hundred thousand ids")
    void theDerivationSpreads() {
        Set<Long> ids = new HashSet<>();
        int made = 0;
        for (int day = 0; day < 500; day++) {
            for (int settlement = 0; settlement < 10; settlement++) {
                for (DeedType type : DeedType.values()) {
                    for (int severity = 1; severity <= 4; severity++) {
                        ids.add(Deed.of(type, A_PLAYER, ANNA, settlement, day)
                                .withSeverity((byte) (severity * 25)).id());
                        made++;
                    }
                }
            }
        }
        assertEquals(made, ids.size(), "the derivation collided, so it is dropping an input");
    }
}
