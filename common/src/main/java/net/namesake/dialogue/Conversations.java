package net.namesake.dialogue;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * How many things a villager has said to you in this conversation.
 *
 * <p>Exists for one reason: <b>{@link Register#PARTING} needs a state that selects it, and the other
 * four have one.</b> Greeting, small talk, about-you and about-others all fall out of what a ring
 * holds; "we are done talking" does not fall out of anything a bond or a ring can say. An authored
 * register nothing can select is dead content, which is the same failure as a persisted field nothing
 * reads one layer up — so either this exists or the fifth register is a lie.
 *
 * <p><b>Volatile, and deliberately so.</b> Nothing here is persisted, nothing is ledgered, and a
 * restart forgets every conversation in progress — which is correct, because a conversation does not
 * survive a server restart either. It is the same reasoning session 07 used to keep
 * {@code DialogueStats} out of the save file, arriving at the opposite answer for the opposite
 * reason: that was derivable and this is genuinely transient.
 *
 * <p><b>Bounded.</b> One entry per player, and the map drops its oldest entry past
 * {@link #REMEMBERED_PLAYERS} — the same shape {@code InteractionTokens} uses for the same reason. A
 * per-player map that only ever grows is a leak on a long-running server, and a leak in a
 * presentational counter would be an embarrassing place to find one.
 */
public final class Conversations {

    /** How many players' conversations are tracked at once. */
    private static final int REMEMBERED_PLAYERS = 128;

    /** After this many turns a villager is finished with you. */
    public static final int TURNS_BEFORE_PARTING = 3;

    private record Turn(UUID target, int said) {
    }

    private static final Map<UUID, Turn> BY_PLAYER =
            new LinkedHashMap<>(16, 0.75F, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<UUID, Turn> eldest) {
                    return size() > REMEMBERED_PLAYERS;
                }
            };

    private Conversations() {
    }

    /**
     * The index of the thing about to be said, counting from zero.
     *
     * <p>Resets when the player turns to a different villager: talking to somebody new is a new
     * conversation, and carrying the count across would have the second villager you meet open with
     * a goodbye.
     */
    public static synchronized int turn(UUID player, UUID target) {
        Turn previous = BY_PLAYER.get(player);
        int said = previous != null && previous.target().equals(target) ? previous.said() : 0;
        BY_PLAYER.put(player, new Turn(target, said + 1));
        return said;
    }

    /** Forgets one player's conversation. Called when their interaction closes or they log out. */
    public static synchronized void forget(UUID player) {
        BY_PLAYER.remove(player);
    }

    /** Forgets everything. For tests, which must not inherit each other's conversations. */
    public static synchronized void clear() {
        BY_PLAYER.clear();
    }
}
