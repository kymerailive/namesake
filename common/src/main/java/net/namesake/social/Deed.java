package net.namesake.social;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.UUIDUtil;
import net.minecraft.world.level.Level;

import java.util.Objects;
import java.util.UUID;

/**
 * One thing that happened, as a struct. {@code DESIGN.md} §3.
 *
 * <p>A deed is emitted, witnessed, and turned into bond movement inside the same tick. It is the
 * unit the whole social system is built out of, and the thesis rests on it staying a struct:
 * because a deed is seven fields rather than a sentence, {@link Memories} can dedupe a ring on
 * {@code (npc, deedId)} exactly, and session 08 can degrade a rumour's {@link #confidence} without
 * ever inventing a fact.
 *
 * <p><b>Persisted from session 06</b>, inside the 32-entry ring {@link Memories} keeps per NPC.
 * Session 05 shipped this record without a codec on purpose: its store did not exist yet, and the
 * alternatives were an unbounded per-NPC list — a leak rather than a feature — or building the ring
 * against a bond system nobody had watched work. Every field is held to rule 5 by
 * {@code SocialValueLedgerTest}, which ledgers this record by name rather than by whether it
 * happens to declare a codec.
 *
 * <p><b>{@code actor} and {@code subject} are bare UUIDs, so a deed is already general.</b> Nothing
 * here says the actor is a player. That is what makes the NPC-to-NPC deeds of session 16's
 * grievance engine a matter of emitting one, rather than of a schema change — see {@link Bonds}
 * for the other half of that decision, and for the guard that keeps the population restricted
 * until something reads it.
 */
public record Deed(
        short typeId,
        UUID actor,
        UUID subject,
        int settlementId,
        int gameDay,
        byte severity,
        byte confidence) {

    /** Witnessed first-hand. Session 08 is what makes this less than a hundred. */
    public static final byte FIRST_HAND = 100;

    /** The severity of a deed with nothing to scale it — a gift is a gift. */
    public static final byte NOMINAL = 100;

    /**
     * Field names are the long readable ones the rest of this file uses, and that is a decision with
     * a number behind it rather than a default. Four hundred personas each holding a full ring is
     * about 1.5 MB of uncompressed NBT, of which roughly a third is these seven key names repeated
     * twelve thousand times; short keys would save half a megabyte of a tag tree that is built once
     * per save and gzipped on the way out. {@code MemoriesTest} measures the real figure and holds it
     * to a ceiling, so the trade is visible and a future session that widens this record finds out at
     * build time rather than in somebody's save.
     */
    public static final Codec<Deed> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.SHORT.fieldOf("type").forGetter(Deed::typeId),
            UUIDUtil.CODEC.fieldOf("actor").forGetter(Deed::actor),
            UUIDUtil.CODEC.fieldOf("subject").forGetter(Deed::subject),
            Codec.INT.fieldOf("settlement").forGetter(Deed::settlementId),
            Codec.INT.fieldOf("day").forGetter(Deed::gameDay),
            Codec.BYTE.fieldOf("severity").forGetter(Deed::severity),
            Codec.BYTE.fieldOf("confidence").forGetter(Deed::confidence)
    ).apply(instance, Deed::new));

    public Deed {
        Objects.requireNonNull(actor, "deed actor");
        Objects.requireNonNull(subject, "deed subject");
    }

    /**
     * A first-hand deed at nominal severity.
     *
     * @param subject who it was done to. For a deed with no single victim — defending a raid — this
     *                is the actor themself, which reads as "nobody in particular gets the subject's
     *                share" rather than needing a null nobody remembers to check.
     */
    public static Deed of(DeedType type, UUID actor, UUID subject, int settlementId, int gameDay) {
        return new Deed(type.id(), actor, subject, settlementId, gameDay, NOMINAL, FIRST_HAND);
    }

    public DeedType type() {
        return DeedType.byId(typeId);
    }

    /**
     * What this deed <i>is</i>, as sixty-four bits. The key {@link Memories} dedupes a ring on.
     *
     * <h2>Ruled at the open of session 06: derived, not assigned</h2>
     *
     * <p>The question a deed id answers is not "which emit was this" but <b>"are two identical
     * feedings on the same day one deed or two?"</b> — and the two candidate answers give opposite
     * ones. A counter or a random UUID keeps both. A hash of the deed's own fields collapses them,
     * because the ring becomes content-addressed and a deed <i>is</i> its fields.
     *
     * <p><b>Collapsing them is what stops the ring being grindable, which is the property the ring
     * exists to have.</b> The store is a memory, not a log. With assigned ids an afternoon of
     * standing in the square handing out bread evicts every distinct thing an NPC knows about you and
     * replaces it with thirty-two copies of one gift — the exact failure {@link Bond#DAILY_CAP}
     * exists to stop one level down, arriving through a door the cap does not watch. Content
     * addressing is the ring's version of that cap, and it costs nothing to hold.
     *
     * <p><b>Nothing is softened by it.</b> A second identical blow still moves the bond: negatives
     * bypass the cap entirely and {@code Bond.apply} has already run by the time this is consulted.
     * The bond is the tally of how much; the ring is the record of what. Collapsing a repeat in one
     * does not forgive it in the other.
     *
     * <p><b>Derived also costs zero bytes.</b> It is a pure function of fields that are already on
     * disk, so it is never persisted — storing it would be a cache, and session 03 deleted
     * {@code Settlement.culture} for being exactly that. {@code DESIGN.md} §3's "~24 B, 32-entry ring
     * ≈ 768 B" stays true rather than becoming 40 B and 1,280.
     *
     * <h2>What it costs, plainly — three things</h2>
     *
     * <ol>
     *   <li><b>{@link #confidence} is deliberately not in it, and that is the session 08 decision
     *       this makes.</b> A rumour retold is the same event known less well, so the same deed
     *       arriving twice by different routes collapses to one ring entry instead of two rows for
     *       one murder. Which of the two copies survives is session 08's to rule; {@link Memories}
     *       keeps the one it already has and does not reorder for a duplicate.</li>
     *   <li><b>Blurring the actor produces a different id.</b> Session 08 blurs an actor below
     *       confidence 50, and a blurred deed will not dedupe against the first-hand one — a villager
     *       could hold both "you killed the smith" and "someone from the north killed the smith".
     *       Bounded rather than solved: with max two hops from first-hand, confidence floors at 72,
     *       so within the propagation session 08 ships the blur cannot fire at all. If that changes,
     *       this is the paragraph to come back to.</li>
     *   <li><b>The derivation is part of the behaviour, so it must not drift.</b> Changing the mix
     *       below re-partitions every ring in every existing save: no corruption, but yesterday's
     *       duplicates become distinct. {@code DeedTest} pins the id of a fixed deed to a literal, so
     *       that becomes a decision somebody makes rather than a side effect of tidying.</li>
     * </ol>
     */
    public long id() {
        long hash = ID_SEED;
        hash = mix(hash, typeId);
        hash = mix(hash, actor.getMostSignificantBits());
        hash = mix(hash, actor.getLeastSignificantBits());
        hash = mix(hash, subject.getMostSignificantBits());
        hash = mix(hash, subject.getLeastSignificantBits());
        hash = mix(hash, settlementId);
        hash = mix(hash, gameDay);
        return mix(hash, severity);
    }

    private static final long ID_SEED = 0x9E37_79B9_7F4A_7C15L;

    /** One round of a murmur3-style finalizer. Written out so it cannot change by inheritance. */
    private static long mix(long hash, long value) {
        long mixed = hash ^ value;
        mixed *= 0xFF51_AFD7_ED55_8CCDL;
        mixed ^= mixed >>> 33;
        mixed *= 0xC4CE_B9FE_1A85_EC53L;
        return mixed ^ (mixed >>> 33);
    }

    public Deed withSeverity(byte newSeverity) {
        return new Deed(typeId, actor, subject, settlementId, gameDay, newSeverity, confidence);
    }

    /**
     * Which in-game day it is, counted from {@code getGameTime} rather than {@code getDayTime}.
     *
     * <p>Game time is monotonic; day time is a mutable world property that {@code /time set} and
     * the sleep skip both move backwards. A daily cap keyed on a clock that can go backwards is a
     * daily cap you can reset with a command, and a decay keyed on one would run in reverse.
     */
    public static int dayOf(Level level) {
        return (int) (level.getGameTime() / 24000L);
    }

    @Override
    public String toString() {
        return "Deed[" + type()
                + " actor=" + actor
                + " subject=" + subject
                + " settlement=" + settlementId
                + " day=" + gameDay
                + " severity=" + severity
                + " confidence=" + confidence + ']';
    }
}
