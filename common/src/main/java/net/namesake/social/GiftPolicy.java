package net.namesake.social;

import net.namesake.npc.NpcRegistry;

import java.util.UUID;

/**
 * <b>Whether a villager will take what you are holding out to them.</b>
 *
 * <p>This class exists for one reason and it is worth stating at the top rather than burying:
 * <b>{@link Bond#warmth()} is written by every kindness in the mod and, from the moment the residency
 * threshold reads {@code trust}, was read by nothing.</b> That is the exact shape {@code DESIGN.md}
 * §1 forbids and the one both reference codebases died of — and it arrived from a direction session
 * 05 did not predict, which is why {@code WORKPLAN.md} standing risk 5 records it in those words: not
 * "nobody got round to it" but "the consumer we were both relying on turned out to want the other
 * axis". The owner declined both escapes at the close of session 08 — moving the expiry, and deleting
 * the field — so warmth owed a real mechanic by the close of 09.
 *
 * <h2>The mechanic: a stranger's rubbish is refused, and a friend's is not</h2>
 *
 * <p>Three kinds of gift, and only one of them is gated:
 *
 * <ul>
 *   <li><b>{@link DeedType#FED_HUNGRY}</b> — vanilla's own {@code wantsMoreFood} says they are
 *       hungry. <b>Never gated.</b></li>
 *   <li><b>{@link DeedType#GIFT_WANTED}</b> — vanilla's own {@code wantsToPickUp} says they want it.
 *       <b>Never gated.</b></li>
 *   <li><b>{@link DeedType#GIFT_UNWANTED}</b> — they have no use for it, and whether they take it
 *       anyway is a question about how they feel about <i>you</i>. Gated on
 *       {@link #WARMTH_TO_ACCEPT_UNWANTED}.</li>
 * </ul>
 *
 * <h2>Four things this had to not break, and the design is what it is because of them</h2>
 *
 * <ol>
 *   <li><b>Onboarding.</b> {@code DESIGN.md} §10's acceptance script, step 2, is a player who has
 *       just arrived feeding a hungry villager. If warmth gated that, the script would be
 *       unpassable and the first hour of the mod would be a closed door.</li>
 *   <li><b>The deadlock.</b> A gate on <i>all</i> giving is a gate on the only thing that raises the
 *       axis it reads: warmth would be unreachable from zero, for ever, and {@code DESIGN.md}'s
 *       "always recoverable, slowly" would be false. The two ungated routes are exactly the two that
 *       raise warmth, so the door out is always open.</li>
 *   <li><b>The threshold has to be low, and the instruments say how low.</b> Session 07 measured
 *       warmth's median across a village at <b>0–1</b> and its observed maximum at <b>56–60</b>;
 *       session 08 confirmed both with propagation on. A gate above that range is LNK's failure
 *       exactly — gates at 35–205 against an observed 32, which nobody ever crossed and nobody
 *       noticed. {@link #WARMTH_TO_ACCEPT_UNWANTED} is <b>4</b>, which is seven percent of the
 *       observed maximum and is what one feeding and a bit buys: a typical villager gains three
 *       warmth from being fed once.</li>
 *   <li><b>It has to be a mechanic rather than a line.</b> A refusal here means the item does not
 *       leave the player's hand, no deed is emitted, no bond moves and no memory is written. Four
 *       consequences, none of them a sentence. {@code Bond.trust}'s own ledger entry already rules
 *       that naming the dialogue pool selection would be naming a display, and session 03 caught
 *       {@code cultureId} telling exactly that lie.</li>
 * </ol>
 *
 * <h2>Why warmth rather than trust, which is the part that makes it warmth's mechanic</h2>
 *
 * <p>Trust does not decay — that is the whole reason session 08 ruled the residency band onto it. So
 * a gate on trust is a door that opens once and never closes: <i>he was kind to me a season ago, so
 * I will take his rubbish for ever.</i> Warmth decays a point an in-game day toward four tenths of
 * its high-water mark, so this gate closes again if you stop turning up. <b>That is the one thing
 * warmth can express that no other axis can</b>, and it is why this is warmth's consumer rather than
 * a consumer that happens to read warmth.
 */
public final class GiftPolicy {

    /**
     * How much warmth it takes before a villager will accept something they have no use for.
     *
     * <p><b>Four, and every digit of it is measured rather than chosen.</b> A typical villager gains
     * three warmth from one feeding — session 05's exit criterion, still asserted by the harness —
     * so this is a second act of kindness and nothing more. Against session 07 and 08's tables it
     * sits at seven percent of the highest warmth any resident reached in a hundred in-game days,
     * and above the 0–1 the median villager holds.
     *
     * <p>It is deliberately not one. One point of warmth is a bystander's share of a single gift,
     * which the decay takes back the next day; a gate at one would be a gate on having stood nearby
     * once, which is not a relationship. Four takes two things done to <i>them</i>.
     */
    public static final int WARMTH_TO_ACCEPT_UNWANTED = 4;

    private GiftPolicy() {
    }

    /**
     * What happens when the item is offered. Three outcomes rather than two, because "they cannot"
     * and "they will not" are different things and a player has to be able to tell them apart.
     */
    public enum Verdict {
        /** It changes hands. */
        ACCEPTED,
        /** Their inventory is full. Nothing to do with how they feel about anybody. */
        NO_ROOM,
        /** They have no use for it and no reason to take it from you. */
        REFUSED_STRANGER
    }

    /**
     * Whether this villager takes this gift from this player.
     *
     * <p>Pure over the registry: no level, no entities, no server, so every clause is a unit test
     * rather than six minutes of CI. {@code SocialEvents.onGive} is the one caller, and it asks
     * vanilla which of the three kinds of gift this is before asking here.
     *
     * @param kind   which kindness vanilla decided this is
     * @param hasRoom whether the villager's inventory can take it at all
     * @param day    the in-game day, so the warmth read is the decayed one — the number any consumer
     *               of a bond actually sees, and the reason this gate closes again
     */
    public static Verdict verdict(NpcRegistry registry, UUID holder, UUID giver, DeedType kind,
                                  boolean hasRoom, int day) {
        if (!hasRoom) {
            return Verdict.NO_ROOM;
        }
        if (kind != DeedType.GIFT_UNWANTED) {
            return Verdict.ACCEPTED;
        }
        Bond bond = registry.bonds().at(holder, giver, day);
        return bond.warmth() >= WARMTH_TO_ACCEPT_UNWANTED
                ? Verdict.ACCEPTED
                : Verdict.REFUSED_STRANGER;
    }
}
