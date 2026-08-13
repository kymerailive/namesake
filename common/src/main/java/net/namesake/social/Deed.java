package net.namesake.social;

import net.minecraft.world.level.Level;

import java.util.Objects;
import java.util.UUID;

/**
 * One thing that happened, as a struct. {@code DESIGN.md} §3.
 *
 * <p>A deed is emitted, witnessed, and turned into bond movement inside the same tick. It is the
 * unit the whole social system is built out of, and the thesis rests on it staying a struct:
 * because a deed is six fields rather than a sentence, session 06 can dedupe a ring on
 * {@code (npc, deed)} exactly, and session 08 can degrade a rumour's {@link #confidence} without
 * ever inventing a fact.
 *
 * <p><b>Deliberately not persisted yet, and that is a scope decision rather than an oversight.</b>
 * A deed's store is the 32-entry ring in session 06; giving this record a {@code Codec} now would
 * either mean an unbounded per-NPC list — which is a leak, not a feature — or building the ring
 * early. What session 05 persists is {@link Bond}, which is where a deed's effect lands and where
 * it has to survive a reload. Every field below is still held to rule 5 by
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
