package net.namesake.npc;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.namesake.Namesake;

import java.util.List;
import java.util.function.ToIntFunction;

/**
 * The persisted shape of {@link NpcRegistry}, and the migrations between shapes.
 *
 * <p>Hard rule 1: no persisted schema change ships without a fix that has been watched to run
 * against a save written before the change. MCA shipped a release that was not backwards compatible
 * and told players to back up their worlds; this class exists so that cannot happen here.
 *
 * <p>To add a schema version:
 * <ol>
 *   <li>bump {@link #CURRENT},</li>
 *   <li>add a {@link Fix} whose {@code from} is the previous version,</li>
 *   <li>load a world saved before the bump and read the migration line out of the log.</li>
 * </ol>
 * The fix list is walked one step at a time, so a save from any earlier version reaches current.
 */
public final class NpcSchema {

    /** Bump this and add a {@link Fix} in the same commit. Never one without the other. */
    public static final int CURRENT = 2;

    /**
     * A registry written before {@link #KEY_VERSION} existed cannot occur — the key has been
     * written since the first save — but a corrupt or truncated file might lack it. Treat that as
     * the oldest known shape so it walks the whole fix chain rather than being taken for current.
     */
    private static final int ASSUMED_WHEN_MISSING = 1;

    public static final String KEY_VERSION = "schemaVersion";
    public static final String KEY_NPCS = "npcs";

    /**
     * One step up the ladder. {@code apply} mutates the root tag in place and returns the number of
     * records it actually rewrote, which is what gets logged — "ran" and "changed something" are
     * different claims and the log has to be able to tell them apart.
     */
    private record Fix(int from, String description, ToIntFunction<CompoundTag> apply) {
        int to() {
            return from + 1;
        }
    }

    private static final List<Fix> FIXES = List.of(
            new Fix(1, "settlement/household 0 now means unassigned (-1)", NpcSchema::fixUnassignedSentinel)
    );

    private NpcSchema() {
    }

    /** What {@link #migrate} did, so callers can log it and tests can assert on it. */
    public record Result(int foundVersion, int resultVersion, int recordsRewritten, boolean refused) {
        public boolean migrated() {
            return foundVersion != resultVersion;
        }
    }

    /**
     * Walks {@code root} up to {@link #CURRENT}, mutating it in place.
     *
     * <p>If the data is <i>newer</i> than this build understands, nothing is touched and
     * {@link Result#refused()} is true. The caller must then refuse to write the file back —
     * silently rewriting a newer save in an older shape is how save corruption ships.
     */
    public static Result migrate(CompoundTag root) {
        int found = root.contains(KEY_VERSION, Tag.TAG_INT) ? root.getInt(KEY_VERSION) : ASSUMED_WHEN_MISSING;

        if (found > CURRENT) {
            Namesake.LOGGER.error(
                    "NPC registry on disk is schema {} but this build only understands {}. Refusing to "
                            + "migrate or overwrite it. Load this world with a newer Namesake.",
                    found, CURRENT);
            return new Result(found, found, 0, true);
        }

        if (found == CURRENT) {
            return new Result(found, found, 0, false);
        }

        int version = found;
        int rewritten = 0;
        while (version < CURRENT) {
            Fix fix = fixFrom(version);
            if (fix == null) {
                // A gap in the ladder is a build error, not user data corruption. Fail loudly
                // rather than load half-migrated records and write them back.
                throw new IllegalStateException(
                        "No NpcSchema fix from version " + version + "; the fix chain has a hole between "
                                + version + " and " + CURRENT);
            }
            int touched = fix.apply.applyAsInt(root);
            rewritten += touched;
            Namesake.LOGGER.info("NPC registry datafixer: schema {} -> {} ({}) rewrote {} record(s)",
                    fix.from(), fix.to(), fix.description(), touched);
            version = fix.to();
        }

        root.putInt(KEY_VERSION, CURRENT);
        return new Result(found, CURRENT, rewritten, false);
    }

    private static Fix fixFrom(int version) {
        for (Fix fix : FIXES) {
            if (fix.from() == version) {
                return fix;
            }
        }
        return null;
    }

    // --- fixes ---------------------------------------------------------------------------------

    /**
     * Schema 1 wrote {@code settlement} and {@code household} as {@code 0} to mean "belongs to
     * neither". That collides: settlement ids in session 03 come off a counter starting at zero, so
     * zero is a legal id and cannot also be the sentinel. Schema 2 uses {@link Persona#UNASSIGNED}.
     *
     * <p>Rewriting every zero is safe precisely because schema 1 never assigned a settlement to
     * anyone — nothing existed to do the assigning. A record holding zero holds it because it means
     * "none", and this is the last version where that is true.
     */
    private static int fixUnassignedSentinel(CompoundTag root) {
        ListTag npcs = root.getList(KEY_NPCS, Tag.TAG_COMPOUND);
        int touched = 0;
        for (int i = 0; i < npcs.size(); i++) {
            CompoundTag entry = npcs.getCompound(i);
            boolean changed = false;
            if (entry.getInt("settlement") == 0) {
                entry.putInt("settlement", Persona.UNASSIGNED);
                changed = true;
            }
            if (entry.getInt("household") == 0) {
                entry.putInt("household", Persona.UNASSIGNED);
                changed = true;
            }
            if (changed) {
                touched++;
            }
        }
        return touched;
    }
}
