package net.namesake.npc;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.namesake.Namesake;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    public static final int CURRENT = 7;

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
            new Fix(1, "settlement/household 0 now means unassigned (-1)", NpcSchema::fixUnassignedSentinel),
            new Fix(2, "culture 0 now means unassigned (-1); settlements added", NpcSchema::fixUnassignedCulture),
            new Fix(3, "bonds added; nothing to rewrite, an absent table means nobody has met anyone",
                    NpcSchema::fixBondTableAdded),
            new Fix(4, "deed rings added; nothing to rewrite, an absent table means nobody has "
                    + "witnessed anything", NpcSchema::fixMemoryTableAdded),
            new Fix(5, "settlement gossip deques added; nothing to rewrite, an absent table means "
                    + "no rumour was in flight", NpcSchema::fixGossipTableAdded),
            new Fix(6, "deed rings repacked to fixed-width records behind an actor and an item "
                    + "palette; every ring on disk is rewritten", NpcSchema::fixPackedDeedRings)
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

    /**
     * Schema 2 wrote {@code culture} as {@code 0}, meaning "none" — there was no culture table to
     * point at. Session 03 adds one, and culture ids start at zero, so from schema 3 on a stored
     * {@code 0} means <i>the first culture</i>.
     *
     * <p><b>This is the migration that matters, and it is the quiet kind.</b> Without it a world
     * from the previous build loads without a single error and every villager in it silently
     * becomes Vale — same names, same palette, same disposition, in every settlement on the map.
     * It would look like the culture system working, which is precisely why hard rule 1 exists:
     * the failure MCA shipped was not a crash either.
     *
     * <p>Rewriting every zero is safe for the same reason it was at schema 1 → 2: nothing before
     * this version could assign a culture, so a record holding zero holds it because it means
     * "none". This is the last version where that is true.
     *
     * <p>The other half of schema 3 — the settlement table — needs no rewrite at all. An older tag
     * simply has no {@code settlements} key, which reads as "no settlements have been detected
     * yet", which is exactly what was true.
     */
    /**
     * Schema 4 adds the bond table, and there is genuinely nothing to rewrite.
     *
     * <p><b>Said out loud rather than left to be inferred, because "the fixer did nothing" is a
     * defect this project has already shipped once.</b> Session 03 deliberately broke the 2 → 3 fix
     * to run, log and change nothing, and watched the build go red — a fixer that silently does
     * nothing loads without crashing too. So the distinction has to be stated: that fix was *meant*
     * to rewrite, and this one is not.
     *
     * <p>The two migrations before this one were both the same shape — a stored {@code 0} that used
     * to mean "none" and now means a real value — and both had to rewrite every record that held
     * one. This has no such collision, because bonds are a table that did not exist. An older tag
     * simply has no {@code bonds} key, and the whole content of the migration is the assumption that
     * its absence reads as <i>nobody has met anyone</i> rather than as damage. That is the same free
     * migration the settlement half of schema 3 was, and it is not free by luck: it is free because
     * {@link net.namesake.social.Bonds#readFrom} was written to return zero unreadable records for
     * an absent list rather than to fail on one.
     *
     * <p>Which is exactly where the risk actually sits, so that is where the test is. Read as
     * damage, the registry goes read-only and a world with bonds in it silently stops saving them.
     * {@code NpcSchemaTest} pins the absence, and it has been reverted and watched to fail.
     *
     * @return zero, always, and the log line says so
     */
    private static int fixBondTableAdded(CompoundTag root) {
        return 0;
    }

    /**
     * Schema 5 adds the per-NPC deed ring, and — like schema 4 — there is genuinely nothing to
     * rewrite. <b>Said out loud, because "additive" is a claim and not a default.</b>
     *
     * <p>Two of the four fixes before this one were the same collision: a stored {@code 0} that used
     * to mean "none" and now means a real value, rewritten record by record. The other two, and this
     * one, add a table that did not exist, and their whole content is an <i>assumption</i> — that an
     * absent key reads as "nothing has happened yet" rather than as damage.
     *
     * <p>What makes this one additive is checkable rather than asserted, and it is worth stating
     * because {@link net.namesake.social.Deed} did not arrive with session 06. The record and all
     * seven of its fields shipped in session 05; what session 06 added is a {@code Codec} and a
     * store. So there is no older shape of a deed on disk anywhere to reconcile — a schema-4 save
     * cannot contain a deed at all, in any shape, because nothing could write one.
     *
     * <p>Which is also where the only real risk sits, so that is where the test is. Read as damage,
     * {@code NpcRegistry} goes read-only and a world that has memories in it silently stops saving
     * them — and it stops saving its bonds and its settlements with them, because there is one file.
     * {@code NpcSchemaTest} pins the absence, and it has been reverted and watched to fail.
     *
     * <p><b>The assertion is deliberately not on the rewrite count.</b> Zero is what a fix that does
     * nothing at all also returns — session 03 broke the 2 → 3 fix to do exactly that and turned the
     * build red for it. Counting rewrites here would prove nothing either way, so the evidence is on
     * the thing that would actually break: {@link net.namesake.social.Memories#readFrom} returning an
     * empty table and a writable registry for a tag with no {@code memories} key.
     *
     * @return zero, always, and the log line says so
     */
    private static int fixMemoryTableAdded(CompoundTag root) {
        return 0;
    }

    /**
     * Schema 6 adds the per-settlement gossip deque, and there is nothing to rewrite.
     * <b>The third additive migration running, and it is still stated rather than assumed.</b>
     *
     * <p>What makes it additive is the same thing that made schema 5 additive, and it is worth
     * naming because session 08 could easily have made it false. A queued rumour is a
     * {@link net.namesake.social.Deed} — the same record, the same seven fields, the same codec that
     * has been on disk since schema 5. <b>Session 08 added no field to any persisted record.</b> The
     * confidence a story has left was already a field, and the hop count that would otherwise have
     * needed one is derived from it, so there is no older shape of anything on disk to reconcile: a
     * schema-5 save simply has no {@code gossip} key.
     *
     * <p>Which is where the only real risk sits, so that is where the test is. Read as damage,
     * {@link NpcRegistry} goes read-only and a world that has been played for a week silently stops
     * saving its personas, settlements, bonds <i>and</i> rings — because there is one file.
     * {@code NpcSchemaTest} pins the absence, and it has been reverted and watched to fail.
     *
     * <p><b>The assertion is deliberately not on the rewrite count</b>, for the third time: zero is
     * also what a fix that does nothing at all returns, and session 03 broke the 2 → 3 fix into
     * exactly that and turned the build red for it. The evidence is
     * {@link net.namesake.social.Gossip#readFrom} returning an empty table and a writable registry
     * for a tag with no {@code gossip} key, and the deed rings a schema-5 build wrote coming through
     * beside it.
     *
     * @return zero, always, and the log line says so
     */
    private static int fixGossipTableAdded(CompoundTag root) {
        return 0;
    }

    /**
     * Schema 7 repacks every deed ring, and <b>it is the first migration since schema 3 that has to
     * rewrite something rather than assume something.</b>
     *
     * <p>Three additive migrations ran in a row — bonds at 4, rings at 5, gossip at 6 — and each one
     * said out loud that "additive" is a claim rather than a default. This one is the other kind, and
     * it says so with the same emphasis. The owner ruled memory depth at the close of session 08:
     * richer per memory, a repeat count, and 32 → 128 slots, all three. The first two add a field
     * each and the third multiplies the table by four, and session 08 had already done the
     * arithmetic: at the readable codec's 122 B a deed the worst case goes to <b>6.26 MB of NBT
     * against a 2 MB ceiling</b>. So the format changed, and a format change is a rewrite.
     *
     * <p><b>What it converts.</b> Schema 6 wrote each holder's ring as a {@code ListTag} of
     * {@code CompoundTag}s, one per deed, through {@code Deed.CODEC}. Schema 7 writes one
     * {@code ByteArrayTag} per holder at twenty-five bytes a slot, with the UUIDs and the item ids
     * held once each in two palettes at the root of the table. Every field is carried across; the two
     * that did not exist take their defaults, which are <i>no particular object</i> and <i>it
     * happened once</i>. Both are true of every deed a schema-6 build could write, because neither
     * field existed for it to be wrong about.
     *
     * <p><b>Written out longhand here rather than calling {@code Memories}, and that is deliberate.</b>
     * A datafixer describes a shape that is frozen in the past. Calling the current writer would make
     * this fix mean "whatever the newest format happens to be", so a schema-8 format change would
     * silently rewrite schema-6 saves into schema-8 bytes and stamp them schema 7 —
     * which loads, and is wrong. The constants below are schema 7's, for ever.
     * {@code NpcSchemaTest.thePackedRingThisFixWritesIsTheOneMemoriesReads} is what pins the two
     * together <i>today</i>, and it is the test a future session has to come and look at rather than
     * a claim it can quietly break.
     *
     * <p><b>The hazard, so the test is pointed at the right thing.</b> Vanilla's
     * {@code CompoundTag.getByteArray} returns an <i>empty array</i> for a key holding the wrong tag
     * type. So if this fix does not run, or runs and writes nothing, every ring in the world reads
     * back as a villager who remembers nothing — no error, no crash, and then written back that way
     * at the next autosave. {@code Memories.readFrom} refuses that shape by name and counts it as
     * damage precisely so the silent version cannot happen.
     *
     * @return how many deeds were rewritten, which is a real number here rather than the zero the
     * three additive fixes return
     */
    private static int fixPackedDeedRings(CompoundTag root) {
        ListTag rings = root.getList(KEY_MEMORIES, Tag.TAG_COMPOUND);
        if (rings.isEmpty()) {
            // A world where nobody has witnessed anything. Nothing to convert, and the palettes are
            // still written so the table has the shape schema 7 expects rather than half of it.
            root.putIntArray(KEY_MEMORY_ACTORS, new int[0]);
            root.put(KEY_MEMORY_ITEMS, itemPalette(List.of("")));
            return 0;
        }

        List<int[]> actors = new ArrayList<>();
        Map<String, Integer> actorIndex = new LinkedHashMap<>();
        int rewritten = 0;

        for (int i = 0; i < rings.size(); i++) {
            CompoundTag entry = rings.getCompound(i);
            ListTag ring = entry.getList(KEY_RING, Tag.TAG_COMPOUND);
            byte[] packed = new byte[ring.size() * PACKED_RECORD_BYTES];
            ByteBuffer buffer = ByteBuffer.wrap(packed);
            for (int slot = 0; slot < ring.size(); slot++) {
                CompoundTag deed = ring.getCompound(slot);
                buffer.putShort(deed.getShort("type"));
                buffer.putInt(internActor(deed.getIntArray("actor"), actors, actorIndex));
                buffer.putInt(internActor(deed.getIntArray("subject"), actors, actorIndex));
                buffer.putInt(deed.getInt("settlement"));
                buffer.putInt(deed.getInt("day"));
                buffer.put(deed.getByte("severity"));
                buffer.put(deed.getByte("confidence"));
                // No schema-6 deed knows what object it was about, and none happened more than once
                // as far as anything on disk can say. Index 0 is the empty item id; 1 is "once".
                buffer.putInt(0);
                buffer.put((byte) 1);
                rewritten++;
            }
            entry.remove(KEY_RING);
            entry.putByteArray(KEY_RING, packed);
        }

        int[] flattened = new int[actors.size() * 4];
        for (int i = 0; i < actors.size(); i++) {
            System.arraycopy(actors.get(i), 0, flattened, i * 4, 4);
        }
        root.putIntArray(KEY_MEMORY_ACTORS, flattened);
        root.put(KEY_MEMORY_ITEMS, itemPalette(List.of("")));
        return rewritten;
    }

    /**
     * Schema 7's packed slot width. <b>Frozen — see {@link #fixPackedDeedRings}.</b> If a later
     * schema changes the record, this constant does not move with it.
     */
    private static final int PACKED_RECORD_BYTES = 25;

    private static final String KEY_MEMORIES = "memories";
    private static final String KEY_RING = "ring";
    private static final String KEY_MEMORY_ACTORS = "memoryActors";
    private static final String KEY_MEMORY_ITEMS = "memoryItems";

    private static ListTag itemPalette(List<String> items) {
        ListTag tags = new ListTag();
        for (String item : items) {
            tags.add(StringTag.valueOf(item));
        }
        return tags;
    }

    /** A UUID's four ints, interned into the palette. Keyed on its printable form so it can hash. */
    private static int internActor(int[] uuid, List<int[]> palette, Map<String, Integer> index) {
        if (uuid.length != 4) {
            // A deed whose actor cannot be read is one the packed form cannot represent. Writing the
            // nil UUID rather than dropping the slot keeps the ring's length and its order, and
            // Deed.UNKNOWN_ACTOR is already the value that means "nobody can say who".
            uuid = new int[4];
        }
        String key = uuid[0] + ":" + uuid[1] + ":" + uuid[2] + ":" + uuid[3];
        Integer existing = index.get(key);
        if (existing != null) {
            return existing;
        }
        int next = palette.size();
        palette.add(uuid.clone());
        index.put(key, next);
        return next;
    }

    private static int fixUnassignedCulture(CompoundTag root) {
        ListTag npcs = root.getList(KEY_NPCS, Tag.TAG_COMPOUND);
        int touched = 0;
        for (int i = 0; i < npcs.size(); i++) {
            CompoundTag entry = npcs.getCompound(i);
            if (entry.getByte("culture") == 0) {
                entry.putByte("culture", Persona.UNASSIGNED_CULTURE);
                touched++;
            }
        }
        return touched;
    }
}
