package net.namesake.npc;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
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
     * <p>Schema 1 differs from later shapes only in the meaning of {@code settlement} and
     * {@code household}, which it wrote as {@code 0} for "none" — so a schema-1 fixture is the
     * current encoding with those two fields forced to zero.
     */
    private static CompoundTag registryTagAtVersion(int version) {
        CompoundTag root = new CompoundTag();
        root.putInt(NpcSchema.KEY_VERSION, version);

        ListTag npcs = new ListTag();
        for (int i = 0; i < 3; i++) {
            Persona persona = Persona.create(new UUID(i, i), 100L + i);
            CompoundTag entry = (CompoundTag) Persona.CODEC
                    .encodeStart(NbtOps.INSTANCE, persona).getOrThrow();
            if (version == 1) {
                entry.putInt("settlement", 0);
                entry.putInt("household", 0);
            }
            npcs.add(entry);
        }
        root.put(NpcSchema.KEY_NPCS, npcs);
        return root;
    }
}
