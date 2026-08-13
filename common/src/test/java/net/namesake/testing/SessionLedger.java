package net.namesake.testing;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Which session the project is on, read out of {@code WORKPLAN.md}.
 *
 * <p>An exemption that expires "at session 05" needs something to compare against, and a constant
 * in a Java file is no good: the whole point is that it expires <i>without anyone remembering</i>,
 * and a constant only moves when someone remembers to move it.
 *
 * <p>The ledger is the one artefact that provably changes every session — {@code CLAUDE.md} makes
 * updating it the last act of a session, and hard rule 2 makes the push mandatory. So the status
 * board is the honest source for "what session is this".
 *
 * <p>The parse is strict on purpose. A malformed board fails the build rather than defaulting to
 * something, because every default here is a way for an exemption to quietly stop expiring.
 */
public final class SessionLedger {

    /** {@code | 02 | Authorization layer | **NEXT** |} */
    private static final Pattern ROW =
            Pattern.compile("^\\|\\s*(\\d{2})\\s*\\|[^|]*\\|\\s*(.+?)\\s*\\|\\s*$");

    private SessionLedger() {
    }

    /**
     * The session marked <b>NEXT</b> on the status board — the one being worked on now.
     *
     * <p>So an exemption written "expires after session 5" fails once the board says 06 is next,
     * which is the moment session 05 closes. The build goes red at the end of the session that owed
     * the consumer, not at the start of it, so {@code main} stays green while the work is done.
     */
    public static int currentSession() {
        Path workplan = ModClasses.repoRoot().resolve("WORKPLAN.md");
        if (!Files.isRegularFile(workplan)) {
            throw new IllegalStateException("WORKPLAN.md not found at " + workplan);
        }

        List<Integer> done = new ArrayList<>();
        List<Integer> next = new ArrayList<>();
        try {
            for (String line : Files.readAllLines(workplan)) {
                Matcher matcher = ROW.matcher(line);
                if (!matcher.matches()) {
                    continue;
                }
                int session = Integer.parseInt(matcher.group(1));
                String state = matcher.group(2);
                if (state.contains("**NEXT**")) {
                    next.add(session);
                } else if (state.contains("**done**")) {
                    done.add(session);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        if (next.size() != 1) {
            throw new IllegalStateException("The WORKPLAN.md status board marks " + next.size()
                    + " sessions **NEXT** " + next + "; it must mark exactly one.");
        }
        int current = next.get(0);
        for (int session = 0; session < current; session++) {
            if (!done.contains(session)) {
                throw new IllegalStateException("The WORKPLAN.md status board says session "
                        + current + " is next, but session " + session + " is not marked **done**.");
            }
        }
        return current;
    }
}
