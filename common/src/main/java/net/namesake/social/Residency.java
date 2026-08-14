package net.namesake.social;

import net.namesake.npc.NpcRegistry;
import net.namesake.npc.Persona;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * <b>Whether this settlement counts you as one of its own.</b> {@code DESIGN.md} §5, the first
 * threshold, and the one the whole pitch turns on: <i>before residency they call you stranger; after
 * it, they use your name.</i>
 *
 * <h2>The band reads {@code trust}, and it was measured rather than chosen</h2>
 *
 * <p>§5 was written saying "the <i>known</i> band with ≥3 residents" without saying which axis a
 * band is made of, and the obvious reading was warmth — <i>do they like you</i>. Session 07 ran a
 * settlement forward a hundred in-game days and found that reading was unpassable: a witness's share
 * of a gift is one point and warmth decays one point an in-game day, so the two cancel exactly and
 * <b>no three residents ever reach 20 warmth, at any mark, ever</b>. Session 08 closed off the
 * plausible fix by measuring it — gossip moves the village's median warmth from 0 to 1 and its
 * maximum from 56 to 60, and leaves every warmth mark reading {@code never}.
 *
 * <p><b>That is LNK's failure caught two sessions before it would have shipped.</b> LNK set skill
 * gates from 35 to 205 against an observed maximum affinity of 32; zero players ever reached the
 * lowest one and nobody noticed for months, because nothing in that codebase could answer "what do
 * players actually earn". The owner ruled the axis at the close of session 08 against the table:
 * <b>trust</b>, which does not decay, so it only ever climbs. The third resident crosses 20 on day
 * <b>28</b>.
 *
 * <p>What that means for how residency <i>feels</i> is the part worth keeping: it is earned by
 * consistency rather than by intensity. You cannot buy your way in over an afternoon, because
 * {@link Bond#DAILY_CAP} bounds what one day can be worth; and you cannot lose it by going away,
 * because trust does not decay.
 *
 * <h2>Derived, never persisted, and that is a ruling</h2>
 *
 * <p>There is no residency table and no granted flag. Every input is already on disk and already in
 * the rule 5 ledger: bonds carry trust, rings carry deeds. Storing the answer as well would be a
 * cache — session 03 deleted {@code Settlement.culture} for being exactly that, and session 07 kept
 * {@code DialogueStats} derived for the same reason.
 *
 * <p><b>And the honest version of that argument is the second one, not the first.</b> A persisted
 * residency flag would be a persisted social value whose only consumer today is a line of dialogue,
 * which {@code SocialValueLedgerTest} would refuse — correctly, and for the exact reason the owner
 * warned about before this session started. Deriving it means there is nothing to classify, no
 * schema field, and no possibility of the flag disagreeing with the bonds that justify it.
 *
 * <p><b>What it costs, stated rather than found later.</b> Route B reads the rings, and a ring
 * evicts — so a player who took the fast route and then stopped visiting could in principle have the
 * deeds that proved it pushed out. Three things bound that and none of them is luck: route A is
 * monotonic, because trust never decays, so anybody who has been around long enough for route B to
 * lapse has usually crossed the band anyway; {@link Memories#evictionWeight} holds a
 * {@code DEFENDED_RAID} above the gifts that would displace it; and session 09 raised the ring to a
 * hundred and twenty-eight slots, which is four times the room it had when this was written.
 */
public final class Residency {

    /**
     * How much trust one resident must hold. {@code DESIGN.md} §5's <i>known</i> band.
     *
     * <p>Twenty, which is the first mark on {@link DialogueStats#LADDER} and therefore the number
     * every report session 07 and 08 produced has already been read against. A threshold this file
     * invented would be a threshold nobody had measured; this one has a day attached to it — 28,
     * one deed a day, nine residents, thirty-five percent witnessing.
     */
    public static final int TRUST_THRESHOLD = 20;

    /** How many residents have to hold it. {@code DESIGN.md} §5's "≥3 residents". */
    public static final int RESIDENTS_REQUIRED = 3;

    /** {@code DESIGN.md} §5's second route: {@code FED_HUNGRY} ×3, counted as distinct deeds. */
    public static final int FEEDINGS_REQUIRED = 3;

    private Residency() {
    }

    /** Which of §5's two routes granted it, or that neither did. */
    public enum Route {
        /** Nobody here counts you as one of them. */
        NONE,
        /** The band: {@link #RESIDENTS_REQUIRED} residents at {@link #TRUST_THRESHOLD} trust. */
        BAND,
        /** One significant deed: a raid defended, or three hungry people fed. */
        DEED
    }

    /**
     * What this settlement thinks, and the numbers behind it.
     *
     * @param residentsAtThreshold how many residents hold {@link #TRUST_THRESHOLD} trust or more
     * @param feedings             how many distinct {@code FED_HUNGRY} deeds by this player this
     *                             settlement still remembers
     * @param defendedARaid        whether anybody here remembers them defending it
     */
    public record Verdict(Route route, int residentsAtThreshold, int feedings,
                          boolean defendedARaid) {

        public boolean granted() {
            return route != Route.NONE;
        }
    }

    /**
     * Whether this player is a resident of this settlement, as of {@code day}.
     *
     * <p>Pure over the registry — no level, no entities, no server — so every clause of §5 is a unit
     * test rather than six minutes of CI, which is the line {@code WORKPLAN.md} draws between its two
     * instruments. It is also what lets session 07's headless simulation report the day each of the
     * five player models crosses it, which is a claim about time and belongs there.
     *
     * <p><b>Never polled, and it runs in two passes so the common case is cheap.</b> This is asked
     * when somebody speaks to a villager — an interaction rather than a tick, the same discipline the
     * witness scan has carried since session 05 — but "not on a tick" is not the same as "free". The
     * band is one map lookup per resident; the deed route is a walk of every ring in the settlement,
     * which at {@code DESIGN.md} §8's population and session 09's capacity is tens of thousands of
     * deeds. So the bands are counted first and the rings are only read <b>if the band has not
     * already been met</b>.
     *
     * <p>The consequence is stated rather than hidden: for a settlement that has taken you in by the
     * band, {@link Verdict#feedings()} and {@link Verdict#defendedARaid()} report nothing rather than
     * what they would have found. A village that has already decided does not need a second reason,
     * and {@link Verdict#granted()} — which is what every caller actually asks — is unaffected.
     */
    public static Verdict verdict(NpcRegistry registry, int settlementId, UUID player, int day) {
        if (settlementId == Persona.UNASSIGNED || player == null) {
            // Nowhere is not a place you can be a resident of.
            return new Verdict(Route.NONE, 0, 0, false);
        }

        int atThreshold = 0;
        for (Persona resident : registry.all()) {
            if (resident.settlementId() == settlementId
                    && registry.bonds().at(resident.id(), player, day).trust() >= TRUST_THRESHOLD) {
                atThreshold++;
            }
        }
        if (atThreshold >= RESIDENTS_REQUIRED) {
            return new Verdict(Route.BAND, atThreshold, 0, false);
        }

        Set<Long> feedings = new HashSet<>();
        boolean defended = false;
        for (Persona resident : registry.all()) {
            if (resident.settlementId() != settlementId) {
                continue;
            }
            for (Deed deed : registry.memories().of(resident.id())) {
                // Deeds are counted across the whole settlement and deduped by id, so one feeding
                // watched by four people is one feeding. That is only possible because a deed id is
                // derived from the deed rather than assigned — session 06's decision, paying off in
                // a place it was not written for.
                if (!player.equals(deed.actor()) || deed.settlementId() != settlementId) {
                    continue;
                }
                if (deed.type() == DeedType.DEFENDED_RAID) {
                    defended = true;
                } else if (deed.type() == DeedType.FED_HUNGRY) {
                    feedings.add(deed.id());
                }
            }
        }

        Route route = defended || feedings.size() >= FEEDINGS_REQUIRED ? Route.DEED : Route.NONE;
        return new Verdict(route, atThreshold, feedings.size(), defended);
    }

    /** The common question, when the reasons are not wanted. */
    public static boolean isResident(NpcRegistry registry, int settlementId, UUID player, int day) {
        return verdict(registry, settlementId, player, day).granted();
    }
}
