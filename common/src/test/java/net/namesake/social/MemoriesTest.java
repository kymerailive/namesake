package net.namesake.social;

import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
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
 * The deed ring: capacity, overflow, dedupe, order and the round trip.
 *
 * <p>All of it is arithmetic over immutable values, which is why session 06 earns no new harness
 * leg. {@code WORKPLAN.md} draws the line: anything a unit test can prove belongs in a unit test, and
 * the in-game harness is for what only a running game can show. A ring is not that.
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

    // --- capacity and overflow ---------------------------------------------------------------

    @Test
    @DisplayName("forty deeds at one NPC leave the newest thirty-two, in order")
    void overflowKeepsTheNewest() {
        Memories memories = new Memories();
        for (int day = 0; day < 40; day++) {
            assertTrue(memories.remember(ANNA, onDay(day)), "day " + day + " is a deed nobody held");
        }

        // WORKPLAN.md's exit criterion for this session, as arithmetic.
        List<Deed> ring = memories.of(ANNA);
        assertEquals(Memories.RING_CAPACITY, ring.size());
        assertEquals(32, Memories.RING_CAPACITY, "DESIGN.md §3 sizes the ring at 32");
        assertEquals(8, ring.get(0).gameDay(), "the oldest survivor is the fortieth-from-last");
        assertEquals(39, ring.get(ring.size() - 1).gameDay(), "and the newest is the last one in");

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
        for (int day = 0; day < 40; day++) {
            memories.remember(ANNA, onDay(day));
        }
        memories.remember(BRAM, onDay(3));

        assertEquals(Memories.RING_CAPACITY, memories.of(ANNA).size());
        assertEquals(1, memories.of(BRAM).size());
        assertEquals(3, memories.of(BRAM).get(0).gameDay(),
                "Bram's one memory is not evicted by Anna's thirty-nine");
        assertEquals(Memories.RING_CAPACITY + 1, memories.size());
        assertEquals(2, memories.holders());
    }

    @Test
    @DisplayName("an NPC who has seen nothing has an empty ring rather than a null one")
    void anUnknownHolderReadsAsEmpty() {
        Memories memories = new Memories();
        assertEquals(List.of(), memories.of(ANNA));
        assertFalse(memories.remembers(ANNA, onDay(1).id()));
        assertEquals(0, memories.size());
        assertEquals(0, memories.holders());
    }

    // --- dedupe --------------------------------------------------------------------------------

    @Test
    @DisplayName("forty emits of the same deed leave one entry, and thirty-nine of them change nothing")
    void exactDedupeCollapsesRepeats() {
        Memories memories = new Memories();
        assertTrue(memories.remember(ANNA, onDay(4)));
        for (int i = 0; i < 39; i++) {
            assertFalse(memories.remember(ANNA, onDay(4)),
                    "a repeat must report that nothing changed, or the registry is marked dirty "
                            + "every time a player hands over the same loaf twice");
        }
        assertEquals(1, memories.of(ANNA).size());
    }

    /**
     * The property the ring exists to have, stated as the thing a player can actually do.
     *
     * <p>Thirty-two distinct memories, then an afternoon of repeating one gift. With assigned deed
     * ids every one of those gifts is a new entry and the ring ends up holding one afternoon;
     * content addressing is what keeps the other thirty-one things this villager knows about you.
     */
    @Test
    @DisplayName("a day of repeating yourself cannot evict what a villager already knows")
    void theRingIsNotGrindable() {
        Memories memories = new Memories();
        // A full ring of thirty-two distinct days, so there is no spare slot to absorb anything.
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
        // Two slots consumed by today, so exactly two of yesterday's went. With assigned ids the
        // whole ring would be today.
        assertEquals(2, memories.of(ANNA).get(0).gameDay(),
                "only the two oldest days were pushed out, not all thirty-two");
    }

    @Test
    @DisplayName("a duplicate does not move the entry it duplicates to the newest slot")
    void aDuplicateDoesNotReorderTheRing() {
        Memories memories = new Memories();
        Deed first = onDay(1);
        memories.remember(ANNA, first);
        memories.remember(ANNA, onDay(2));
        memories.remember(ANNA, first);

        // Being told a thing again is not the thing happening again. Refreshing the slot would let
        // session 08's gossip push first-hand memories out of a ring simply by retelling them.
        assertEquals(List.of(1, 2), memories.of(ANNA).stream().map(Deed::gameDay).toList());
    }

    @Test
    @DisplayName("two deeds that differ in any identity field are two deeds")
    void everyIdentityFieldSeparatesTwoDeeds() {
        Deed base = new Deed(DeedType.FED_HUNGRY.id(), A_PLAYER, ANNA, 3, 7,
                Deed.NOMINAL, Deed.FIRST_HAND);
        Memories memories = new Memories();
        memories.remember(ANNA, base);

        assertTrue(memories.remember(ANNA,
                new Deed(DeedType.GIFT_WANTED.id(), A_PLAYER, ANNA, 3, 7, Deed.NOMINAL, Deed.FIRST_HAND)),
                "a different kind of deed");
        assertTrue(memories.remember(ANNA,
                new Deed(DeedType.FED_HUNGRY.id(), ANOTHER_PLAYER, ANNA, 3, 7, Deed.NOMINAL, Deed.FIRST_HAND)),
                "a different actor");
        assertTrue(memories.remember(ANNA,
                new Deed(DeedType.FED_HUNGRY.id(), A_PLAYER, BRAM, 3, 7, Deed.NOMINAL, Deed.FIRST_HAND)),
                "a different subject");
        assertTrue(memories.remember(ANNA,
                new Deed(DeedType.FED_HUNGRY.id(), A_PLAYER, ANNA, 4, 7, Deed.NOMINAL, Deed.FIRST_HAND)),
                "a different settlement");
        assertTrue(memories.remember(ANNA,
                new Deed(DeedType.FED_HUNGRY.id(), A_PLAYER, ANNA, 3, 8, Deed.NOMINAL, Deed.FIRST_HAND)),
                "a different day");
        assertTrue(memories.remember(ANNA, base.withSeverity((byte) 50)),
                "a different severity — a blow for two hearts is not the blow for eight");

        assertEquals(7, memories.of(ANNA).size(), "the base deed plus one per identity field");
    }

    /**
     * The session 08 half of the id decision, pinned here rather than left to be discovered.
     *
     * <p>Confidence is deliberately outside {@link Deed#id()}: a rumour retold is the same event
     * known less well. If it were inside, a villager who heard about a murder twice by two routes
     * would hold two rows for one murder.
     */
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
                "the copy already held is kept; which copy wins when they disagree is session 08's "
                        + "to rule, and today they never do");
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
    @DisplayName("a ring survives a save and a load with its order and every field intact")
    void ringsRoundTrip() {
        Memories original = new Memories();
        for (int day = 0; day < 40; day++) {
            original.remember(ANNA, onDay(day));
        }
        Deed struck = Deed.of(DeedType.STRUCK_RESIDENT, ANOTHER_PLAYER, BRAM, 4, 12)
                .withSeverity((byte) 37);
        original.remember(BRAM, struck);

        CompoundTag tag = new CompoundTag();
        original.save(tag);
        Memories reloaded = new Memories();
        assertEquals(0, reloaded.readFrom(tag));

        assertEquals(original.of(ANNA), reloaded.of(ANNA));
        assertEquals(List.of(struck), reloaded.of(BRAM));
        assertEquals(37, reloaded.of(BRAM).get(0).severity(), "severity is not lost in the round trip");
        assertEquals(Memories.RING_CAPACITY + 1, reloaded.size());
        assertEquals(2, reloaded.holders());
    }

    @Test
    @DisplayName("a tag written before schema 5 reads as an empty table, not as damage")
    void anAbsentTableIsNotDamage() {
        // The whole content of the schema 4 -> 5 migration. Read as damage, NpcRegistry goes
        // read-only and a world silently stops saving its bonds and settlements too, because there
        // is one file.
        Memories memories = new Memories();
        assertEquals(0, memories.readFrom(new CompoundTag()));
        assertEquals(0, memories.size());
    }

    @Test
    @DisplayName("an unreadable deed is counted, and costs one memory rather than a whole ring")
    void oneBadDeedIsCountedAndTheRestSurvive() {
        Memories good = new Memories();
        good.remember(ANNA, onDay(1));
        good.remember(ANNA, onDay(2));
        CompoundTag tag = new CompoundTag();
        good.save(tag);

        ListTag list = tag.getList("memories", Tag.TAG_COMPOUND);
        list.getCompound(0).getList("ring", Tag.TAG_COMPOUND).add(new CompoundTag());

        Memories reloaded = new Memories();
        assertEquals(1, reloaded.readFrom(tag), "the damage must be reported so the file is not "
                + "written back over the records that could not be read");
        assertEquals(2, reloaded.of(ANNA).size(), "and the readable deeds still load");
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
     * <p>The bound is this build's, not the file's. Keeping the newest {@link Memories#RING_CAPACITY}
     * is the same answer overflow gives, and it is the difference between a survivable disagreement
     * and a read-only world.
     */
    @Test
    @DisplayName("a longer ring on disk is truncated to the newest, not refused")
    void anOverlongRingIsTruncatedRatherThanRefused() {
        // Built by hand, because a Memories at this capacity cannot produce an over-long ring to
        // read back — which is the point: the file was written by a build that could.
        ListTag ring = new ListTag();
        for (int day = 0; day < 50; day++) {
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
        assertEquals(0, reloaded.readFrom(tag), "a bound this build does not share is not damage");
        assertEquals(Memories.RING_CAPACITY, reloaded.of(ANNA).size());
        assertEquals(49, reloaded.of(ANNA).get(Memories.RING_CAPACITY - 1).gameDay(),
                "the newest survive, exactly as they do on overflow");
        assertEquals(18, reloaded.of(ANNA).get(0).gameDay());
    }

    // --- what it costs -------------------------------------------------------------------------

    /**
     * The size counter-argument to "one file", measured rather than waved away.
     *
     * <p>{@code DESIGN.md} §8's four hundred records, every one of them holding a full ring: 12,800
     * deeds, which is the state of a save where a player has personally done thirty-two distinct
     * things in front of every villager in fifty villages. It cannot happen, and it is the number the
     * format has to survive.
     *
     * <p>Both figures are asserted because they answer different questions. The compressed one is
     * what {@code namesake_npcs.dat} costs on disk, since {@code SavedData} is written through
     * {@code NbtIo.writeCompressed}. The uncompressed one is what the tag tree costs in memory each
     * time Minecraft saves a dirty registry, and it is the larger of the two by an order of
     * magnitude — which is the honest cost of readable field names in {@link Deed#CODEC}.
     *
     * <p><b>Measured 2026-08-14: 1,565,620 B of NBT and 46,506 B gzipped — 122.3 B and 3.6 B per
     * deed.</b> Gzip pays for the readable key names thirty-four times over, because twelve thousand
     * copies of the same seven strings and the same actor UUID is what a compressor is for. So the
     * cost that is actually paid every save is the tag tree, not the file, and it is the one worth
     * watching.
     *
     * <p>The ceilings sit a little above those figures so this fails on a change of shape rather
     * than on noise: one more {@code int} on {@link Deed} would add ~17 B a deed and still fit, two
     * would not. If it goes red, the fix is a decision — shorter keys, a packed ring, or a smaller
     * {@link Memories#RING_CAPACITY} — not a bigger number here.
     */
    @Test
    @DisplayName("the worst-case memory table is measured, and it fits")
    void theWorstCaseTableFitsInItsBudget() throws IOException {
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
        assertTrue(compressed < 100_000,
                "the bytes written to namesake_npcs.dat were " + compressed
                        + " B, over the 100,000 B ceiling");
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
     * yesterday's duplicates become distinct, and a ring that held one afternoon starts holding
     * thirty-two copies of it. There is no schema break to catch that, because nothing is stored —
     * so this literal is the catch. If it goes red, that is a decision to make deliberately, not a
     * number to update.
     *
     * <p><b>The literal was computed outside this codebase</b> — the documented mix, run over the
     * eight inputs below in an independent implementation — rather than copied out of a first run.
     * A pin taken from the code's own output pins whatever the code happens to do; this one also
     * says the implementation matches the algorithm the javadoc describes.
     */
    @Test
    @DisplayName("the deed id of a fixed deed is pinned, so the derivation cannot drift by accident")
    void theDerivationIsPinned() {
        Deed fixed = new Deed(DeedType.FED_HUNGRY.id(), A_PLAYER, ANNA, 3, 7,
                Deed.NOMINAL, Deed.FIRST_HAND);
        assertEquals(-4535043805363013135L, fixed.id());
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
        // A 64-bit key over 120,000 draws expects far less than one collision; any at all means the
        // mix is losing a field rather than being unlucky.
        assertEquals(made, ids.size(), "the derivation collided, so it is dropping an input");
    }
}
