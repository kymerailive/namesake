package net.namesake.dialogue;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * <b>The hundred and sixty lines.</b> Four {@link Pool}s × five {@link Register}s × eight.
 *
 * <h2>Authored, and that is an invariant rather than a stage we have not left yet</h2>
 *
 * <p>The question this file invites is "why write these by hand when a model could produce them?",
 * and {@code WORKPLAN.md} has already ruled the half that matters: <b>dialogue is selected from
 * authored pools by struct state, and a model may only decorate a line that is already complete and
 * shippable — never produce one.</b> That invariant costs nothing to hold and it keeps both delivery
 * options open, because enrichment can then be generated ahead of time and cached per
 * {@code (culture, pool, register)} rather than per utterance — which bounds the cost to thousands
 * of generations and keeps latency out of the interaction path entirely.
 *
 * <p>The delivery question itself is sealed until after session 10, deliberately: if the propagation
 * thesis fails ship-or-kill, it evaporates.
 *
 * <h2>How a line is written</h2>
 *
 * <p>{@code {you}} is the only slot, and it is the whole pitch: before residency it renders as the
 * culture's word for a stranger, and after it renders as the player's name. {@code DESIGN.md} §5 —
 * <i>one string swap, zero art, and it delivers the whole pitch in a single line.</i>
 *
 * <p>Lines are short on purpose. A villager's line has to sit inside the chat width with a culture's
 * opener in front of it, its tag question behind it, and a sixteen-character player name in the
 * middle — and this project has shipped a string nobody measured against the space it has to sit in
 * <b>six times</b>. {@code CommandLayoutTest} enumerates every pool, register, culture and residency
 * state and measures the result rather than sampling one.
 */
public final class Lines {

    /** How many lines each (pool, register) carries. */
    public static final int PER_REGISTER = 8;

    /** The slot the address term goes in — a stranger word, or the player's name. */
    public static final String ADDRESS = "{you}";

    private static final Map<Pool, Map<Register, List<String>>> BY_POOL = new EnumMap<>(Pool.class);

    private Lines() {
    }

    private static void pool(Pool pool, Map<Register, List<String>> registers) {
        BY_POOL.put(pool, registers);
    }

    private static Map<Register, List<String>> registers(
            List<String> greeting, List<String> smallTalk, List<String> aboutYou,
            List<String> aboutOthers, List<String> parting) {
        Map<Register, List<String>> map = new EnumMap<>(Register.class);
        map.put(Register.GREETING, List.copyOf(greeting));
        map.put(Register.SMALL_TALK, List.copyOf(smallTalk));
        map.put(Register.ABOUT_YOU, List.copyOf(aboutYou));
        map.put(Register.ABOUT_OTHERS, List.copyOf(aboutOthers));
        map.put(Register.PARTING, List.copyOf(parting));
        return map;
    }

    static {
        // --- STRANGER: every line about you is authored as an explicit negative ------------------
        pool(Pool.STRANGER, registers(
                List.of(
                        "I don't know you, {you}.",
                        "You're not from here, {you}.",
                        "A face I don't know. Good day.",
                        "I've not seen you before, {you}.",
                        "We've not met, {you}.",
                        "You're new. I know nothing of you.",
                        "No one's told me about you, {you}.",
                        "I don't know your business here."),
                List.of(
                        "The bell rings at dusk. That's all.",
                        "Weather holds. For now.",
                        "It's a quiet place, mostly.",
                        "Work to do. As ever.",
                        "The road's long, wherever you came from.",
                        "I keep to my own, {you}.",
                        "Nothing much happens here.",
                        "Ask someone else. I'm busy."),
                List.of(
                        "I've nothing to say about you.",
                        "You've done nothing I've seen.",
                        "I couldn't tell you what you are.",
                        "No one here owes you anything.",
                        "You've left no mark on me, {you}.",
                        "I've no story of you to tell.",
                        "Nothing of yours has reached me.",
                        "I know your face and not one thing more."),
                List.of(
                        "Talk here is of people I know. Not you.",
                        "The village says nothing of you.",
                        "I hear things. None about you.",
                        "Nobody's mentioned you, {you}.",
                        "Your name hasn't reached me.",
                        "Whatever's said, it isn't of you.",
                        "News passes through. You aren't in it.",
                        "I've heard nothing about you at all."),
                List.of(
                        "Good day, {you}.",
                        "Mind yourself.",
                        "Go on, then.",
                        "That's all from me.",
                        "Safe roads, {you}.",
                        "I've work.",
                        "Good day to you.",
                        "On your way.")));

        // --- KNOWN --------------------------------------------------------------------------------
        pool(Pool.KNOWN, registers(
                List.of(
                        "Ah, {you}.",
                        "You again, {you}. Good.",
                        "Back, are you?",
                        "Well met, {you}.",
                        "I know your face now.",
                        "Morning, {you}.",
                        "You're about early.",
                        "There you are, {you}."),
                List.of(
                        "Same work, different day.",
                        "The smith's short of iron again.",
                        "Bell's late. Someone forgot.",
                        "Harvest looks fair this year.",
                        "Roof wants mending before winter.",
                        "Quiet week. I'll take it.",
                        "Someone's cow got into the wheat.",
                        "Nothing's changed since you asked."),
                List.of(
                        "I remember what you did, {you}.",
                        "You've been about. I've noticed.",
                        "You keep turning up. That counts.",
                        "I've seen you work, {you}.",
                        "You've done more than most visitors.",
                        "I could tell someone about you now.",
                        "You're a face I can put a deed to.",
                        "Not a stranger any more, {you}."),
                List.of(
                        "Word gets round, {you}.",
                        "Someone mentioned you, in passing.",
                        "I heard about you before I met you.",
                        "There's talk. Nothing bad.",
                        "The village has your name now.",
                        "You come up in conversation.",
                        "I was told of you by someone else.",
                        "Your name's been said here, {you}."),
                List.of(
                        "Until next time, {you}.",
                        "Go well.",
                        "Come back when you can.",
                        "Right. Work.",
                        "See you about, {you}.",
                        "Don't be long.",
                        "Take care on the road.",
                        "Good day, {you}.")));

        // --- WARM ---------------------------------------------------------------------------------
        pool(Pool.WARM, registers(
                List.of(
                        "{you}! Good to see you.",
                        "There's a face I like.",
                        "Come in, {you}, come in.",
                        "You're welcome here, {you}.",
                        "I hoped you'd come by.",
                        "{you}. It's been too long.",
                        "Sit a while, {you}.",
                        "You're always welcome, you know."),
                List.of(
                        "Sit. I've bread if you want it.",
                        "My knee aches. Rain coming.",
                        "The garden's doing well this year.",
                        "My sister's boy is walking now.",
                        "I saved you the good stool.",
                        "You'll stay for the bell?",
                        "Nothing new, and I'm glad of it.",
                        "Tell me where you've been."),
                List.of(
                        "You were kind to me, {you}.",
                        "I've not forgotten what you did.",
                        "I tell people about you, {you}.",
                        "You've been good to this place.",
                        "I'd vouch for you, if asked.",
                        "You're one of the good ones.",
                        "I owe you, and I know it.",
                        "What you did stayed with me."),
                List.of(
                        "They speak well of you here.",
                        "I said as much to the smith.",
                        "Your name comes up kindly, {you}.",
                        "Even the elder's heard of you.",
                        "Folk ask after you when you're gone.",
                        "I told them what you did for me.",
                        "You've a good name in this village.",
                        "They believe me now, about you."),
                List.of(
                        "Come back soon, {you}.",
                        "You'll be missed.",
                        "Safe roads, my friend.",
                        "Don't be a stranger, {you}.",
                        "Mind how you go.",
                        "I'll keep the stool warm.",
                        "Until next time, {you}.",
                        "Go well, and come back.")));

        // --- HOSTILE ------------------------------------------------------------------------------
        pool(Pool.HOSTILE, registers(
                List.of(
                        "You.",
                        "I'd rather you moved along, {you}.",
                        "What do you want?",
                        "Not you again.",
                        "Say it and go, {you}.",
                        "I've nothing for you.",
                        "Keep your distance.",
                        "I know who you are, {you}."),
                List.of(
                        "I'll not make talk with you.",
                        "Ask someone who'll answer.",
                        "The weather's fine. Goodbye.",
                        "I've work, and you're in it.",
                        "Nothing to say to you.",
                        "That's my business, not yours.",
                        "Don't stand so close.",
                        "You're wasting my day."),
                List.of(
                        "I remember exactly what you did.",
                        "You've a debt here, {you}.",
                        "I saw it. Don't pretend.",
                        "You did that in front of me.",
                        "I've not forgiven it, {you}.",
                        "Some things don't wash off.",
                        "You cost this village something.",
                        "I know what you are now."),
                List.of(
                        "Everyone's heard, {you}.",
                        "They talk about you. Not kindly.",
                        "Word travels. Yours has.",
                        "I was warned about you.",
                        "The whole village knows, {you}.",
                        "Ask them yourself what they say.",
                        "Your name's said like a curse here.",
                        "You'll find no welcome now."),
                List.of(
                        "Go.",
                        "We're done, {you}.",
                        "Don't come back.",
                        "Get off my step.",
                        "That's the end of it.",
                        "Away with you.",
                        "I'll not say it twice.",
                        "Leave, {you}.")));
    }

    /** Every line of one (pool, register), in a stable order. */
    public static List<String> of(Pool pool, Register register) {
        return BY_POOL.get(pool).get(register);
    }

    /** One line of one (pool, register), chosen by an already-mixed seed. */
    public static String at(Pool pool, Register register, long seed) {
        List<String> lines = of(pool, register);
        return lines.get((int) Long.remainderUnsigned(seed, lines.size()));
    }

    /** How many lines exist in total. {@code DialogueTest} holds it to the ledger's figure. */
    public static int count() {
        int total = 0;
        for (Pool pool : Pool.values()) {
            for (Register register : Register.values()) {
                total += of(pool, register).size();
            }
        }
        return total;
    }
}
