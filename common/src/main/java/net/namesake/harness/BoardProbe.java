package net.namesake.harness;

import java.util.List;
import java.util.Optional;

/**
 * How the harness asks the <b>client</b> what is actually on the screen.
 *
 * <p>{@link AttachBetHarness} runs on the server tick and names no {@code net.minecraft.client} type,
 * which is what lets it be loaded on a dedicated server at all. But the one claim session 11 has that
 * no unit test can make is about a screen: <i>a lectern placed in the world, opened by a real player,
 * rendering a real save's history through the real font.</i>
 *
 * <p>So the two halves talk through this — the server step asks, {@link HarnessClient} answers on the
 * client tick with plain strings and integers, and the server step turns them into verdict lines.
 * Same shape as {@code ClientPacketSink} and {@code ClientScreenSink}, and for the same reason.
 *
 * <p><b>The font is the point of it.</b> {@code BoardText.ADVANCES} is a table of the default font's
 * ASCII advances written down, and a table written down can be wrong. Only a running client has a
 * {@code Font}, so this is where the table is held to the engine — and it reports <i>which
 * characters</i> disagree rather than only that some did, because a measurement that says "wrong" and
 * not "wrong here" costs a whole run to act on.
 */
public final class BoardProbe {

    /** What the client saw. */
    public record Answer(boolean open, List<String> rows, int widest, List<String> disagreements) {

        public Answer {
            rows = List.copyOf(rows);
            disagreements = List.copyOf(disagreements);
        }
    }

    private static volatile boolean requested;
    private static volatile Answer answer;

    private BoardProbe() {
    }

    /** Server side: ask the client to look at its screen on its next tick. */
    public static void request() {
        answer = null;
        requested = true;
    }

    public static boolean isRequested() {
        return requested;
    }

    /** Client side: what is on the screen, measured with the real font. */
    public static void answer(Answer seen) {
        answer = seen;
        requested = false;
    }

    /** Server side: what came back, or empty while the client has not looked yet. */
    public static Optional<Answer> answer() {
        return Optional.ofNullable(answer);
    }

    public static void clear() {
        requested = false;
        answer = null;
    }
}
