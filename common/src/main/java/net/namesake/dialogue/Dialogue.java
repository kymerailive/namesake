package net.namesake.dialogue;

import net.namesake.culture.Culture;
import net.namesake.culture.Names;
import net.namesake.npc.NpcRegistry;
import net.namesake.npc.Persona;
import net.namesake.social.Bond;
import net.namesake.social.Deed;
import net.namesake.social.Residency;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * <b>What a villager actually says, and why that sentence rather than another one.</b> Session 09,
 * and the first time in nine sessions that anything a player hears depends on anything the mod has
 * been recording.
 *
 * <p>Everything sessions 05 to 08 built — four bond axes, a personality weight table, a deed ring, a
 * settlement deque, a confidence that degrades — has until now been read by arithmetic, by a debug
 * command, and by nothing else. This class is where it reaches a person's ears. Nothing here decides
 * anything; it <i>selects</i>, from {@link Lines}, on state that already exists.
 *
 * <h2>This is a display, and it is registered as one</h2>
 *
 * <p>{@code net.namesake.dialogue} is in {@code SocialValueLedgerTest}'s {@code DISPLAY_PACKAGES}, so
 * <b>no persisted field can ever name anything in this package as its consumer</b> — the build goes
 * red if one tries. That is not modesty. {@code Bond.trust}'s own ledger entry has ruled since
 * session 05 that <i>naming the pool selection instead would be naming a display</i>, session 03
 * caught {@code cultureId} telling exactly that lie about the syllable grammar, and session 09 is the
 * session most likely to tell it again — because it is the first whose deliverable is words, and it
 * arrives carrying fields that need a reader. Putting the package on the list makes that a compile-
 * time impossibility rather than a thing somebody has to remember.
 *
 * <h2>The name swap</h2>
 *
 * <p>{@code DESIGN.md} §5: <i>before residency they call you stranger. After, they use your name.
 * One string swap, zero art, and it delivers the whole pitch in a single line.</i> That swap is
 * {@link #addressFor}, and it reads {@link Residency}, which reads {@code trust} — so the pitch and
 * the threshold are the same mechanism seen from two ends.
 *
 * <p><b>A villager who has never met you can still use your name, and that is correct rather than a
 * bug.</b> Residency is a property of the settlement, not of one relationship, so a resident of a
 * village that has taken you in will use your name in the very line where they say they do not know
 * you. That is the thesis in miniature: what one villager did changed what a different one calls you.
 */
public final class Dialogue {

    /**
     * How much warmth puts a villager in the {@link Pool#WARM} pool.
     *
     * <p>Twenty, the first mark on {@code DialogueStats.LADDER}, so it is a number every report
     * sessions 07 and 08 produced has already been read against rather than one invented here.
     * Measured, it is rare and it should be: warmth's median across a village is 0–1 and only the
     * residents a player actually gives things to ever hold much of it. A warm villager is somebody
     * you have been kind to <i>lately</i>, because warmth is the only axis that decays.
     *
     * <p><b>Session 12 owns the bands and will move this.</b> Pool selection is one of its three
     * ruled consumers; until then this and the trust test below are the interim, and they are two
     * lines rather than a system so that replacing them is cheap.
     */
    public static final int WARM_WARMTH = 20;

    private Dialogue() {
    }

    /** One utterance: the line, and everything that decided it. */
    public record Spoken(Pool pool, Register register, String address, String line) {
    }

    /**
     * What this villager says to this player, now.
     *
     * <p>Pure over the registry — no level, no entities, no server — which is what lets
     * {@code CommandLayoutTest} enumerate every pool, register, culture and residency state and
     * measure the rendered result against the chat width, rather than sampling whichever state a
     * fixture happens to produce. This project has shipped a string nobody measured against the space
     * it has to sit in six times, and five of those were found by a person looking at a screen.
     *
     * @param turn how many things this villager has already said in this conversation — see
     *             {@link Conversations}
     * @param seed varies the line within a register. The caller passes game time; a test passes a
     *             constant, because a report that cannot be reproduced is not evidence
     */
    public static Spoken speak(NpcRegistry registry, Persona speaker, UUID player, String playerName,
                               int turn, int day, long seed) {
        Culture culture = Culture.byId(speaker.cultureId());
        Voice voice = Voice.of(culture);

        List<Deed> ring = registry.memories().of(speaker.id());
        Bond bond = registry.bonds().at(speaker.id(), player, day);
        Pool pool = poolFor(bond, remembersThem(ring, player));
        Register register = registerFor(ring, player, turn);

        String address = addressFor(registry, speaker, player, playerName, day, voice);
        // Both halves of the persona id, for the reason Names.personaSeed folds both: two personas
        // minted in the same millisecond differ mostly in the low bits, and a village whose ids
        // share a high half would greet a player with one sentence between nine of them.
        long mixed = Voice.mix(Voice.mix(speaker.id().getMostSignificantBits() * 0x9E3779B97F4A7C15L
                ^ speaker.id().getLeastSignificantBits() ^ seed)
                ^ ((long) pool.ordinal() * 31L + register.ordinal() + (long) turn * 1009L));
        String line = Lines.at(pool, register, mixed).replace(Lines.ADDRESS, address);
        return new Spoken(pool, register, address, voice.inflect(line, mixed));
    }

    /**
     * <b>The name swap.</b> A stranger word before residency, the player's own name after it.
     *
     * <p>Read through {@link Residency}, which reads {@code trust} — session 08's ruling, measured
     * rather than chosen: warmth can never reach a threshold and trust crosses 20 on day 28.
     */
    public static String addressFor(NpcRegistry registry, Persona speaker, UUID player,
                                    String playerName, int day, Voice voice) {
        return Residency.isResident(registry, speaker.settlementId(), player, day)
                ? playerName
                : voice.strangerAddress();
    }

    /**
     * Which pool a bond puts somebody in.
     *
     * <p>Deliberately four comparisons rather than a system. Session 12 owns the five standing bands
     * and their thresholds, set from session 07's earn-rate data; this is the interim and it is
     * written so that replacing it is one method.
     *
     * <p><b>{@code KNOWN} is reachable two ways and both are needed.</b> A bond that is not nothing
     * means they have an opinion; a ring holding one of your deeds means they know of you even if
     * every axis rounded to zero. Session 06 proved those diverge — <i>the gift moved 0 bonds and was
     * remembered by 4 people regardless</i> — so a villager whose allowance was spent when you fed
     * them would otherwise still be greeting you as a stranger.
     */
    public static Pool poolFor(Bond bond, boolean remembersYou) {
        if (bond.trust() < 0) {
            return Pool.HOSTILE;
        }
        if (bond.warmth() >= WARM_WARMTH) {
            return Pool.WARM;
        }
        return !bond.isNothing() || remembersYou ? Pool.KNOWN : Pool.STRANGER;
    }

    /**
     * Which kind of thing they say, from what they hold and how long you have been talking.
     *
     * <p><b>The second-hand line comes before the first-hand one, and that ordering is the thesis.</b>
     * A villager who watched you do something is session 05; a villager who was <i>told</i> about it
     * is session 08, and it is the only sentence in this mod that could not have been said before the
     * drain existed. {@code DESIGN.md} §10 step 5 — <i>someone says they've heard your name,
     * referencing A</i> — is this branch, one settlement further along.
     *
     * <p><b>An unattributed rumour selects nothing, and that is the blur working.</b> A blurred deed
     * carries {@code Deed.UNKNOWN_ACTOR}, so it never matches the player and the villager holding it
     * has genuinely nothing to say about <i>you</i> — they remember that something happened, and they
     * cannot tell you it was you. Session 08 made that an {@code if} statement rather than a caption
     * in the bond arithmetic; this is the same rule arriving in the words.
     */
    public static Register registerFor(List<Deed> ring, UUID player, int turn) {
        if (turn >= Conversations.TURNS_BEFORE_PARTING) {
            return Register.PARTING;
        }
        if (turn <= 0) {
            return Register.GREETING;
        }
        boolean heard = false;
        boolean saw = false;
        for (Deed deed : ring) {
            if (!player.equals(deed.actor())) {
                continue;
            }
            if (deed.confidence() == Deed.FIRST_HAND) {
                saw = true;
            } else {
                heard = true;
            }
        }
        if (turn == 1) {
            return heard ? Register.ABOUT_OTHERS : saw ? Register.ABOUT_YOU : Register.SMALL_TALK;
        }
        return heard && saw ? Register.ABOUT_YOU : Register.SMALL_TALK;
    }

    /** Whether this ring holds anything this player did — attributed, since a blur names nobody. */
    static boolean remembersThem(List<Deed> ring, UUID player) {
        for (Deed deed : ring) {
            if (player.equals(deed.actor())) {
                return true;
            }
        }
        return false;
    }

    /**
     * The rows a player sees, name first.
     *
     * <p><b>Two lines rather than one, and session 08 already paid for the lesson.</b> A villager's
     * full name runs to twenty-seven characters under session 03's layout budget, and a name plus a
     * sentence on one line is the sixty-four character deed header that had been shipping since
     * session 06 and was invisible because nothing rendered it. The name goes on its own line, the
     * sentence is indented under it, and both are measured.
     */
    public static List<String> rows(Persona speaker, Spoken spoken) {
        List<String> rows = new ArrayList<>(2);
        rows.add(speaker.isGenerated()
                ? Names.of(speaker).full()
                : "(ungenerated) " + speaker.id().toString().substring(0, 8));
        rows.add("  " + spoken.line());
        return rows;
    }
}
