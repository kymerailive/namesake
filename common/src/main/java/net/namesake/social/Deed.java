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
     * What a retelling keeps of the confidence the teller had. <b>Ruled at the open of session 08,
     * and it is the one number of the three that moved.</b>
     *
     * <p>{@code DESIGN.md} §4 step 7 carried {@code confidence × 0.85} from before there was any
     * code to run it against, alongside two other clauses — <i>max 2 hops</i> and <i>identity blurs
     * below 50</i>. Session 06 did the arithmetic and wrote the consequence into {@link #id()}: at
     * 0.85 a two-hop story stands at 72, so <b>the blur could never fire at all</b> and one of the
     * three clauses was dead. {@code WORKPLAN.md}'s exit criterion for this session asks for a
     * holder who cannot name the actor, which the design as ruled could not produce.
     *
     * <p><b>Three of the four ways out break session 10, which is the argument.</b> Raising the hop
     * cap needs five hops before 0.85 crosses 50, and a story five settlements deep is a different
     * mod. Raising the blur threshold to catch 72 makes a two-hop story anonymous — and the
     * acceptance script's step 5 is <i>"someone says they've heard your name, referencing A"</i>,
     * which is a two-hop story. Leaving the criterion unmet ships a distortion mechanic that never
     * distorts.
     *
     * <p><b>Lowering the retention is the only one that fixes the criterion and protects the
     * thesis</b>, because it is the only lever that moves the two hops in <i>opposite</i>
     * directions relative to the threshold. The window is arithmetic rather than taste: hop one must
     * stay attributed and hop two must not, so {@code r ≥ 0.50} and {@code r² < 0.50}, which is
     * {@code [0.50, 0.707)}. Seven tenths is the top of that window and therefore the gentlest
     * change that works — <b>100 → 70 → 49</b>.
     *
     * <p>What it costs is real and is smaller than it looks. {@code Deeds.deltaFor} scales the whole
     * delta by confidence, so a rumour is now worth 0.70 of first-hand where it was 0.85. That
     * number is dominated by {@link DeedType#witnessShare()} — a third for a gift — and by the
     * outsider weight, so the visible effect on a bond is usually the same integer. Where it is not,
     * 0.70 is the more defensible figure anyway: hearing about a murder should not move you 85% as
     * much as watching one.
     *
     * <p>{@code GossipTest} pins every clause of this paragraph, including that the pair below still
     * produces exactly {@link #MAX_HOPS}.
     */
    public static final float RETOLD = 0.70F;

    /**
     * Below this, the holder no longer knows who did it. {@code DESIGN.md} §2, unchanged.
     *
     * <p>It does two jobs and they are the same rule read twice. A story you cannot attribute is a
     * story you cannot pass on, so this is also the floor on retelling — which is what makes
     * {@link #MAX_HOPS} fall out of {@link #RETOLD} rather than needing a counter of its own. That
     * matters beyond tidiness: a hop count would be an eighth field on a record session 06
     * deliberately kept at seven, persisted in every ring in every save, and derivable from a field
     * already there.
     */
    public static final byte ATTRIBUTED = 50;

    /**
     * How many retellings a first-hand deed survives. {@code DESIGN.md} §4 step 7's "max 2 hops".
     *
     * <p><b>Not enforced — derived, and then asserted.</b> {@link #RETOLD} and {@link #ATTRIBUTED}
     * between them permit exactly two: 100 and 70 are attributed and may be retold, 49 is not.
     * Nothing counts hops anywhere in the pipeline. {@code GossipTest} walks the chain and fails if
     * this constant and that arithmetic ever disagree, so changing the retention without meaning to
     * change the reach turns the build red.
     */
    public static final int MAX_HOPS = 2;

    /**
     * The actor of a story nobody can attribute. <b>"Someone from the north."</b>
     *
     * <p>The nil UUID, which is structurally disjoint from every id this mod can mint: a persona id
     * comes from {@code UUID.randomUUID} and a player's is version 3 or 4, so both carry a non-zero
     * version nibble that this leaves at zero.
     *
     * <p><b>Blurring replaces the actor rather than hiding them, and that is what gives the blur an
     * {@code if} statement rather than a caption.</b> A deed whose actor is this moves no bond — see
     * {@link Deeds#deltaFor}'s caller in {@code DeedBus} — because a villager who cannot say who
     * killed the smith has no reason to think worse of <i>you</i>. It is still remembered, which is
     * session 06's asymmetry arriving from the other side: seeing something is not the same as it
     * changing your mind about somebody, and neither is hearing it.
     *
     * <p><b>The direction needs no field.</b> "From the north" is a function of where the deed
     * happened, which {@link #settlementId()} already carries, and where the holder lives, which
     * their persona already carries. Session 06 declined an eighth field on this record and session
     * 08 declines a ninth for the same reason: the struct already contains the answer.
     */
    public static final UUID UNKNOWN_ACTOR = new UUID(0L, 0L);

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
     *       duplicates become distinct. {@code MemoriesTest.theDerivationIsPinned} holds the id of a
     *       fixed deed to a literal computed outside this codebase, so that becomes a decision
     *       somebody makes rather than a side effect of tidying.</li>
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

    /**
     * One round of a murmur3-style finalizer. Written out so it cannot change by inheritance.
     *
     * <p>Package-visible so {@link Gossip} can derive its transfer coin from the same finalizer
     * rather than carrying a second copy of one. Nothing about {@link #id()} changes by exposing it,
     * and {@code MemoriesTest.theDerivationIsPinned} would say so if it did.
     */
    static long mix(long hash, long value) {
        long mixed = hash ^ value;
        mixed *= 0xFF51_AFD7_ED55_8CCDL;
        mixed ^= mixed >>> 33;
        mixed *= 0xC4CE_B9FE_1A85_EC53L;
        return mixed ^ (mixed >>> 33);
    }

    public Deed withSeverity(byte newSeverity) {
        return new Deed(typeId, actor, subject, settlementId, gameDay, newSeverity, confidence);
    }

    // --- session 08: what happens to a story when it is passed on --------------------------------

    /**
     * Whether the holder of this copy can say who did it.
     *
     * <p>Two conditions rather than one, and they are belt and braces on purpose: a copy is
     * attributed while its confidence is at least {@link #ATTRIBUTED} <b>and</b> its actor is a real
     * person. {@link #retold()} keeps the two in step, so the second only fires for a deed somebody
     * built by hand — which is exactly the case a guard is for.
     */
    public boolean isAttributed() {
        return confidence >= ATTRIBUTED && !UNKNOWN_ACTOR.equals(actor);
    }

    /**
     * This deed as the next person to hear about it holds it.
     *
     * <p>One rule, applied to itself: keep {@link #RETOLD} of what the teller believed, and if that
     * leaves too little to name anybody, drop the name. <b>Nothing is invented</b> — every field but
     * confidence and the actor is carried through untouched, and the actor only ever goes from a
     * person to nobody. A rumour in this mod cannot acquire a detail, only lose one.
     *
     * <p>The retold copy keeps the same {@link #id()} while it is still attributed, because
     * confidence is deliberately outside the derivation: <b>a story retold is the same event known
     * less well</b>, so it dedupes against the deed it retells rather than becoming a second row for
     * one murder. A blurred copy is a different id, and that is not a leak either — it is a
     * genuinely different claim, and {@code Gossip} declines to hand it to anybody who already holds
     * the attributed one.
     */
    public Deed retold() {
        byte next = (byte) Math.round(confidence * RETOLD);
        Deed told = withConfidence(next);
        return next >= ATTRIBUTED ? told : told.blurred();
    }

    /** The same event, with the actor's name gone. Never called on its own — see {@link #retold()}. */
    Deed blurred() {
        return new Deed(typeId, UNKNOWN_ACTOR, subject, settlementId, gameDay, severity, confidence);
    }

    /**
     * The id this event has once nobody can attribute it.
     *
     * <p>Two ids for one event is the cost session 06 named and bounded when it put the actor into
     * {@link #id()}, and it is real rather than theoretical now that the blur can fire. Anything
     * that wants to count how many people hold a story has to ask about both, so the second id is a
     * method rather than a fact everybody has to rediscover.
     */
    public long blurredId() {
        return blurred().id();
    }

    Deed withConfidence(byte newConfidence) {
        return new Deed(typeId, actor, subject, settlementId, gameDay, severity, newConfidence);
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
