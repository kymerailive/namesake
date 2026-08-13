package net.namesake.npc;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.namesake.settlement.Settlements;
import net.namesake.social.Bonds;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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
        assertEquals(4, result.resultVersion());
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
