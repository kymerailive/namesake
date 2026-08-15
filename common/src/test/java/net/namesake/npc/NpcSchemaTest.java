package net.namesake.npc;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.namesake.settlement.Settlements;
import net.namesake.social.Bonds;
import net.namesake.social.Deed;
import net.namesake.social.DeedType;
import net.namesake.social.Gossip;
import net.namesake.social.Memories;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The fix ladder. Hard rule 1 lives or dies here. */
class NpcSchemaTest {

    /**
     * The build-breaking half of hard rule 1: bumping {@link NpcSchema#CURRENT} without adding the
     * matching fix leaves a hole in the ladder, and every save written by the previous release
     * becomes unloadable. Walking each version up to current is what catches that in CI rather than
     * in someone's world.
     */
    @Test
    @DisplayName("every schema version between 1 and current can reach current")
    void theFixLadderHasNoHoles() {
        for (int from = 1; from < NpcSchema.CURRENT; from++) {
            CompoundTag tag = registryTagAtVersion(from);
            NpcSchema.Result result = NpcSchema.migrate(tag);

            assertFalse(result.refused(), "version " + from + " was refused");
            assertEquals(NpcSchema.CURRENT, result.resultVersion(),
                    "version " + from + " did not reach current");
            assertEquals(NpcSchema.CURRENT, tag.getInt(NpcSchema.KEY_VERSION),
                    "version " + from + " left a stale version stamp in the tag");
        }
    }

    @Test
    @DisplayName("a tag already at the current version is left alone")
    void currentVersionIsNotMigrated() {
        CompoundTag tag = registryTagAtVersion(NpcSchema.CURRENT);
        NpcSchema.Result result = NpcSchema.migrate(tag);

        assertFalse(result.migrated());
        assertEquals(0, result.recordsRewritten());
        assertFalse(result.refused());
    }

    /**
     * A missing version key must read as the oldest shape, not the newest. Guessing "current"
     * for a truncated file would skip every fix and load stale data as if it were fresh.
     */
    @Test
    @DisplayName("a registry with no version stamp is treated as the oldest known shape")
    void missingVersionIsTreatedAsOldest() {
        CompoundTag tag = registryTagAtVersion(1);
        tag.remove(NpcSchema.KEY_VERSION);

        NpcSchema.Result result = NpcSchema.migrate(tag);

        assertEquals(1, result.foundVersion());
        assertEquals(NpcSchema.CURRENT, result.resultVersion());
        assertFalse(result.refused());
    }

    /**
     * The schema 1 -> 2 fix specifically. Zero was the schema-1 sentinel for "no settlement"; it is
     * a legal id from schema 2 on, so every stored zero has to become {@link Persona#UNASSIGNED}.
     */
    @Test
    @DisplayName("schema 1 records come out of the fixer with the new unassigned sentinel")
    void schemaOneUnassignedSentinelIsMigrated() {
        CompoundTag tag = registryTagAtVersion(1);
        ListTag before = tag.getList(NpcSchema.KEY_NPCS, Tag.TAG_COMPOUND);
        assertEquals(0, before.getCompound(0).getInt("settlement"), "fixture must start at the old value");

        NpcSchema.Result result = NpcSchema.migrate(tag);

        assertEquals(1, result.foundVersion());
        assertEquals(NpcSchema.CURRENT, result.resultVersion(),
                "a schema 1 save must walk the whole ladder, not stop at 2");
        // Three records through two fixes: the 1 -> 2 sentinel rewrite and the 2 -> 3 culture one.
        assertEquals(6, result.recordsRewritten(),
                "three records rewritten by each of the two fixes on the way to current");

        ListTag after = tag.getList(NpcSchema.KEY_NPCS, Tag.TAG_COMPOUND);
        for (int i = 0; i < after.size(); i++) {
            assertEquals(Persona.UNASSIGNED, after.getCompound(i).getInt("settlement"));
            assertEquals(Persona.UNASSIGNED, after.getCompound(i).getInt("household"));
        }
    }

    /** A real settlement id must survive the fixer untouched — the fix only rewrites the sentinel. */
    @Test
    @DisplayName("the schema 1 fix leaves a non-zero settlement alone")
    void schemaOneFixDoesNotTouchRealIds() {
        CompoundTag tag = registryTagAtVersion(1);
        tag.getList(NpcSchema.KEY_NPCS, Tag.TAG_COMPOUND).getCompound(0).putInt("settlement", 12);

        NpcSchema.migrate(tag);

        assertEquals(12, tag.getList(NpcSchema.KEY_NPCS, Tag.TAG_COMPOUND).getCompound(0).getInt("settlement"));
    }

    /**
     * The schema 2 -> 3 fix, and the reason hard rule 1 exists.
     *
     * <p>Schema 2 wrote {@code culture = 0} meaning "none". Session 03 gives culture 0 to Vale. So
     * without this fix an existing world loads perfectly, throws nothing, and every villager on
     * the map is quietly Vale — same names, same palette, same disposition, in every settlement.
     * That is the shape of failure this project is built around: not a crash, a save that looks
     * like it worked.
     */
    @Test
    @DisplayName("schema 2 records come out of the fixer with no culture rather than the first one")
    void schemaTwoCultureSentinelIsMigrated() {
        CompoundTag tag = registryTagAtVersion(2);
        ListTag before = tag.getList(NpcSchema.KEY_NPCS, Tag.TAG_COMPOUND);
        assertEquals(0, before.getCompound(0).getByte("culture"), "fixture must start at the old value");

        NpcSchema.Result result = NpcSchema.migrate(tag);

        assertEquals(2, result.foundVersion());
        assertEquals(NpcSchema.CURRENT, result.resultVersion(),
                "a schema 2 save must walk the whole ladder, not stop at 3");
        assertEquals(3, result.recordsRewritten(),
                "three records rewritten by the culture fix, and none by anything above it");

        ListTag after = tag.getList(NpcSchema.KEY_NPCS, Tag.TAG_COMPOUND);
        for (int i = 0; i < after.size(); i++) {
            assertEquals(Persona.UNASSIGNED_CULTURE, after.getCompound(i).getByte("culture"));
            Persona migrated = Persona.CODEC.parse(NbtOps.INSTANCE, after.getCompound(i)).getOrThrow();
            assertFalse(migrated.isGenerated(),
                    "a migrated record must read as needing generation, not as a Vale villager");
        }
    }

    /** A real culture id must survive the fixer untouched — the fix only rewrites the sentinel. */
    @Test
    @DisplayName("the schema 2 fix leaves a real culture alone")
    void schemaTwoFixDoesNotTouchRealCultures() {
        CompoundTag tag = registryTagAtVersion(2);
        tag.getList(NpcSchema.KEY_NPCS, Tag.TAG_COMPOUND).getCompound(0).putByte("culture", (byte) 4);

        NpcSchema.migrate(tag);

        assertEquals(4, tag.getList(NpcSchema.KEY_NPCS, Tag.TAG_COMPOUND).getCompound(0).getByte("culture"));
    }

    /**
     * The other half of schema 3 is the settlement table, and it needs no rewrite at all: an older
     * tag simply has no {@code settlements} key. That absence has to read as "none detected yet"
     * rather than as damage, which is what makes the migration free.
     */
    @Test
    @DisplayName("a pre-schema-3 tag has no settlement table, and that loads as an empty one")
    void theSettlementTableIsAbsentBeforeSchemaThree() {
        CompoundTag tag = registryTagAtVersion(2);
        assertFalse(tag.contains("settlements"), "the fixture must not already have one");

        NpcSchema.migrate(tag);

        Settlements settlements = new Settlements();
        assertEquals(0, settlements.readFrom(tag), "an absent table is not an unreadable one");
        assertEquals(0, settlements.size());
        assertEquals(0, settlements.claimId(), "ids still start at zero in a migrated world");
    }

    /**
     * Schema 3 → 4, and the reason it looks like nothing.
     *
     * <p>The two fixes below this one both rewrote every record they touched, because both were the
     * same collision: a stored {@code 0} that used to mean "none" and now means a real value. This
     * one has no collision to fix — bonds are a table that did not exist — so the migration is the
     * <i>assumption</i> rather than the rewrite, and the assumption is what is asserted here.
     *
     * <p>Session 03 established that a fixer which runs, logs and changes nothing is a defect, by
     * breaking the 2 → 3 fix into exactly that and watching the build go red. So the distinction has
     * to be made explicitly rather than implied by a passing test: that fix was meant to rewrite,
     * this one is not, and what would actually break a world here is the absent key being read as
     * damage — which turns the registry read-only and stops the world saving.
     */
    @Test
    @DisplayName("a pre-schema-4 tag has no bond table, and that loads as an empty one")
    void theBondTableIsAbsentBeforeSchemaFour() {
        CompoundTag tag = registryTagAtVersion(3);
        assertFalse(tag.contains("bonds"), "the fixture must not already have one");

        NpcSchema.Result result = NpcSchema.migrate(tag);

        assertEquals(3, result.foundVersion());
        assertEquals(NpcSchema.CURRENT, result.resultVersion(),
                "a schema 3 save must walk the whole ladder, not stop at 4");
        assertEquals(0, result.recordsRewritten(), "there is nothing in a persona for bonds to fix");
        assertTrue(result.migrated(), "the version still moved, so the file must be marked dirty "
                + "and rewritten — otherwise every future load migrates again");

        Bonds bonds = new Bonds();
        assertEquals(0, bonds.readFrom(tag), "an absent table is not an unreadable one");
        assertEquals(0, bonds.size());

        // And the personas came through untouched. A migration that quietly rewrote a culture or a
        // settlement here would be the schema 2 -> 3 failure happening in the other direction.
        ListTag npcs = tag.getList(NpcSchema.KEY_NPCS, Tag.TAG_COMPOUND);
        assertEquals(3, npcs.size());
        for (int i = 0; i < npcs.size(); i++) {
            Persona survivor = Persona.CODEC.parse(NbtOps.INSTANCE, npcs.getCompound(i)).getOrThrow();
            assertEquals(new UUID(i, i), survivor.id());
            assertEquals(100L + i, survivor.birthTick());
        }
    }

    /**
     * Schema 4 → 5, the deed rings, and the second additive migration in a row.
     *
     * <p>Additive is a claim, so it is checked rather than announced. The check that matters is not
     * the rewrite count — zero is also what a fixer that does nothing at all returns, which session
     * 03 broke the 2 → 3 fix into on purpose and watched turn the build red. It is that an absent
     * {@code memories} key reads as an empty table and leaves the registry <b>writable</b>. Read as
     * damage instead, {@code NpcRegistry} goes read-only, and because there is one file that stops
     * the world saving its bonds and its settlements too.
     *
     * <p>One thing here is stronger than it was at schema 4, and it is worth stating. {@code Deed}
     * did not arrive with session 06 — the record and all seven of its fields shipped in session 05,
     * and what 06 added is a codec and a store. So there is no older shape of a deed anywhere on disk
     * to reconcile: a schema-4 save cannot contain one in any shape, because nothing could write one.
     */
    @Test
    @DisplayName("a pre-schema-5 tag has no deed rings, and that loads as an empty table on a writable registry")
    void theMemoryTableIsAbsentBeforeSchemaFive() {
        CompoundTag tag = registryTagAtVersion(4);
        assertFalse(tag.contains("memories"), "the fixture must not already have one");

        NpcSchema.Result result = NpcSchema.migrate(tag);

        assertEquals(4, result.foundVersion());
        assertEquals(NpcSchema.CURRENT, result.resultVersion());
        assertEquals(0, result.recordsRewritten(), "there is nothing in a persona for a ring to fix");
        assertTrue(result.migrated(), "the version still moved, so the file must be marked dirty "
                + "and rewritten — otherwise every future load migrates again");

        Memories memories = new Memories();
        assertEquals(0, memories.readFrom(tag), "an absent table is not an unreadable one");
        assertEquals(0, memories.size());
        assertEquals(0, memories.holders());

        // The whole hazard, spelled out: zero unreadable records is what keeps the registry
        // writable. NpcRegistryTest.aWorldWithNoMemoryTableStaysWritable runs the same claim through
        // the real load path.
        ListTag npcs = tag.getList(NpcSchema.KEY_NPCS, Tag.TAG_COMPOUND);
        assertEquals(3, npcs.size());
        for (int i = 0; i < npcs.size(); i++) {
            Persona survivor = Persona.CODEC.parse(NbtOps.INSTANCE, npcs.getCompound(i)).getOrThrow();
            assertEquals(new UUID(i, i), survivor.id());
            assertEquals(100L + i, survivor.birthTick());
        }
    }

    /**
     * Schema 5 → 6, the settlement gossip deques, and the third additive migration running.
     *
     * <p>What makes this one additive is the thing session 08 could most easily have made false, so
     * it is worth naming: a queued rumour is a {@code Deed} — the same record, the same seven fields,
     * the same codec that has been on disk since schema 5. <b>Session 08 added no field to any
     * persisted record.</b> The confidence a story has left was already there, and the hop count that
     * would otherwise have needed one is derived from it.
     *
     * <p>So the hazard is the same one, and it is the one asserted: an absent {@code gossip} key
     * reading as damage turns the registry read-only, and a world that has been played for a week
     * then stops saving its personas, settlements, bonds <i>and</i> rings, because there is one file.
     */
    @Test
    @DisplayName("a pre-schema-6 tag has no gossip deques, and that loads as an empty table")
    void theGossipTableIsAbsentBeforeSchemaSix() {
        CompoundTag tag = registryTagAtVersion(5);
        assertFalse(tag.contains("gossip"), "the fixture must not already have one");

        NpcSchema.Result result = NpcSchema.migrate(tag);

        assertEquals(5, result.foundVersion());
        assertEquals(NpcSchema.CURRENT, result.resultVersion());
        assertEquals(0, result.recordsRewritten(),
                "there is nothing in a persona for a gossip deque to fix");
        assertTrue(result.migrated(), "the version still moved, so the file must be marked dirty "
                + "and rewritten — otherwise every future load migrates again");

        Gossip gossip = new Gossip();
        assertEquals(0, gossip.readFrom(tag), "an absent table is not an unreadable one");
        assertEquals(0, gossip.size());
        assertEquals(0, gossip.settlements());

        ListTag npcs = tag.getList(NpcSchema.KEY_NPCS, Tag.TAG_COMPOUND);
        assertEquals(3, npcs.size());
        for (int i = 0; i < npcs.size(); i++) {
            Persona survivor = Persona.CODEC.parse(NbtOps.INSTANCE, npcs.getCompound(i)).getOrThrow();
            assertEquals(new UUID(i, i), survivor.id());
            assertEquals(100L + i, survivor.birthTick());
        }
    }

    /**
     * <b>Schema 7, and it is the first migration since schema 3 that rewrites rather than assumes.</b>
     *
     * <p>Three additive ones ran in a row and each said out loud that "additive" is a claim rather
     * than a default. This one is the other kind: the owner ruled 32 → 128 slots at the close of
     * session 08 and session 08 had already done the arithmetic — at the readable codec's 122 B a
     * deed that worst case is 6.26 MB against a 2 MB ceiling. So the format changed, and every ring
     * on disk has to be converted.
     *
     * <p><b>The assertion is on what would actually break rather than on the rewrite count</b>, for
     * the fourth time — although here the count is also a real number rather than the zero the three
     * additive fixes return, which is itself worth pinning. What breaks is silent: vanilla's
     * {@code CompoundTag.getByteArray} returns an empty array for a key holding the wrong tag type,
     * so a ring this fix did not convert loads as a villager who remembers nothing and is written
     * back that way.
     */
    @Test
    @DisplayName("a schema-6 deed ring is repacked, and every field survives the conversion")
    void deedRingsArePackedAtSchemaSeven() {
        CompoundTag tag = registryTagAtVersion(6);

        // A schema-6 ring, written exactly as that build wrote one: a ListTag of compounds through
        // Deed.CODEC, which is still the encoding the gossip deque uses.
        UUID holder = new UUID(0, 0);
        UUID actor = UUID.fromString("0a0a0a0a-1111-2222-3333-444444444444");
        ListTag ring = new ListTag();
        for (int day = 0; day < 5; day++) {
            Deed deed = Deed.of(day == 0 ? DeedType.KILLED_RESIDENT : DeedType.FED_HUNGRY,
                            actor, holder, 3, day)
                    .withSeverity((byte) (day == 0 ? 100 : 40));
            ring.add(Deed.CODEC.encodeStart(NbtOps.INSTANCE, deed).getOrThrow());
        }
        CompoundTag entry = new CompoundTag();
        entry.putIntArray("holder", net.minecraft.core.UUIDUtil.uuidToIntArray(holder));
        entry.put("ring", ring);
        ListTag memories = new ListTag();
        memories.add(entry);
        tag.put("memories", memories);

        NpcSchema.Result result = NpcSchema.migrate(tag);

        assertEquals(6, result.foundVersion());
        assertEquals(NpcSchema.CURRENT, result.resultVersion());
        // Five through the 6 -> 7 repack and five again through the 7 -> 8 deletion, because a
        // schema-6 save now walks two rewriting fixes rather than one. Stated as the sum rather than
        // as a constant, so the day a third rewriting fix lands this line says which one moved.
        assertEquals(5 + 5, result.recordsRewritten(),
                "both rewriting fixes ran: the repack at 7 and the field deletion at 8");

        // The whole hazard: read by this build, the converted ring has to come back as five deeds
        // rather than as nothing.
        Memories reloaded = new Memories();
        assertEquals(0, reloaded.readFrom(tag), "a converted ring is not damage");
        List<Deed> loaded = reloaded.of(holder);
        assertEquals(5, loaded.size(), "every slot survived, in order");
        assertEquals(DeedType.KILLED_RESIDENT, loaded.get(0).type());
        assertEquals(100, loaded.get(0).severity());
        assertEquals(40, loaded.get(1).severity(), "severity is not lost in the conversion");
        assertEquals(actor, loaded.get(1).actor(), "and the actor came through the palette");
        assertEquals(holder, loaded.get(1).subject());
        assertEquals(3, loaded.get(1).settlementId());
        assertEquals(Deed.FIRST_HAND, loaded.get(1).confidence());

        // The one field schema 6 could not know about takes the only value that is true of every
        // deed it could write: it happened once.
        assertEquals(1, reloaded.slotsOf(holder).get(1).repeats());
    }

    /**
     * <b>The seam between a frozen fixer and a living format, pinned where a future session will
     * find it.</b>
     *
     * <p>{@code NpcSchema.fixPackedDeedRings} writes schema 7's layout longhand rather than calling
     * {@code Memories.save}, because a datafixer describes a shape that is frozen in the past —
     * calling the current writer would make it mean "whatever the newest format is", and a schema-8
     * change would silently rewrite schema-6 saves into schema-8 bytes stamped schema 7. That loads,
     * and it is wrong.
     *
     * <p>The cost of freezing it is that two places now know the layout, and this is what stops them
     * drifting apart unnoticed. <b>When a later session changes the packed record, this test is meant
     * to go red</b> — and the fix is to give that session its own fixer, not to update the constant
     * in the schema-7 one.
     */
    @Test
    @DisplayName("the packed ring this fix writes is the one Memories reads")
    void thePackedRingThisFixWritesIsTheOneMemoriesReads() {
        UUID holder = new UUID(4, 4);
        UUID actor = UUID.fromString("0b0b0b0b-1111-2222-3333-444444444444");

        // What Memories itself produces for one deed...
        Memories live = new Memories();
        live.remember(holder, Deed.of(DeedType.GIFT_WANTED, actor, holder, 7, 11));
        CompoundTag written = new CompoundTag();
        live.save(written);
        int liveSlotBytes = written.getList("memories", Tag.TAG_COMPOUND).getCompound(0)
                .getByteArray("ring").length;

        // ...and what the fixer produces for the same deed, out of the schema-6 shape.
        CompoundTag old = registryTagAtVersion(6);
        ListTag ring = new ListTag();
        ring.add(Deed.CODEC.encodeStart(NbtOps.INSTANCE,
                Deed.of(DeedType.GIFT_WANTED, actor, holder, 7, 11)).getOrThrow());
        CompoundTag entry = new CompoundTag();
        entry.putIntArray("holder", net.minecraft.core.UUIDUtil.uuidToIntArray(holder));
        entry.put("ring", ring);
        ListTag memories = new ListTag();
        memories.add(entry);
        old.put("memories", memories);
        NpcSchema.migrate(old);
        int fixedSlotBytes = old.getList("memories", Tag.TAG_COMPOUND).getCompound(0)
                .getByteArray("ring").length;

        assertEquals(liveSlotBytes, fixedSlotBytes, """
                The schema 7 fixer and Memories disagree about how wide a packed slot is. The fixer \
                is frozen on purpose — it describes schema 7 for ever — so if the record has changed, \
                the change owes a schema 8 and a fixer of its own. Do not update the constant in \
                fixPackedDeedRings.""");

        Memories fromFixer = new Memories();
        assertEquals(0, fromFixer.readFrom(old));
        assertEquals(live.of(holder), fromFixer.of(holder),
                "and the two encodings have to decode to the same deed, not merely to the same size");
    }

    @Test
    @DisplayName("a registry from a newer build is refused, not downgraded")
    void newerSchemaIsRefused() {
        CompoundTag tag = registryTagAtVersion(NpcSchema.CURRENT + 1);
        NpcSchema.Result result = NpcSchema.migrate(tag);

        assertTrue(result.refused());
        assertEquals(NpcSchema.CURRENT + 1, result.resultVersion(), "the tag keeps its own version");
        assertEquals(NpcSchema.CURRENT + 1, tag.getInt(NpcSchema.KEY_VERSION),
                "migrate must not stamp its own version onto data it refused");
    }

    /**
     * A registry tag as some earlier build would have written it.
     *
     * <p>Every shape so far differs from the current one only in which sentinel a field held.
     * Schema 1 wrote {@code 0} into {@code settlement} and {@code household} for "none"; schema 1
     * and 2 both wrote {@code 0} into {@code culture} for the same reason. So an old fixture is
     * the current encoding with those fields forced back to zero — which is exactly what makes the
     * fixes testable, and exactly why each of them is a rewrite of zeros and nothing else.
     */
    private static CompoundTag registryTagAtVersion(int version) {
        CompoundTag root = new CompoundTag();
        root.putInt(NpcSchema.KEY_VERSION, version);

        ListTag npcs = new ListTag();
        for (int i = 0; i < 3; i++) {
            Persona persona = Persona.create(new UUID(i, i), 100L + i);
            CompoundTag entry = (CompoundTag) Persona.CODEC
                    .encodeStart(NbtOps.INSTANCE, persona).getOrThrow();
            if (version <= 1) {
                entry.putInt("settlement", 0);
                entry.putInt("household", 0);
            }
            if (version <= 2) {
                entry.putByte("culture", (byte) 0);
            }
            npcs.add(entry);
        }
        root.put(NpcSchema.KEY_NPCS, npcs);
        return root;
    }
}
