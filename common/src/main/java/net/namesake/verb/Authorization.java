package net.namesake.verb;

import java.util.Objects;

/**
 * One verb's answer about one sender and one target.
 *
 * <p>Deliberately not a {@code boolean}. A denial has to say why, because the reason is the only
 * thing that ends up in the server log when a gate rejects something, and "packet rejected" with no
 * reason is indistinguishable from a bug in the gate itself.
 *
 * <p>The reason is <b>not</b> sent to the client. A modified client that learns which check it
 * failed has been handed an oracle for probing the rest of them.
 */
public record Authorization(boolean allowed, String reason) {

    private static final Authorization ALLOWED = new Authorization(true, "");

    public Authorization {
        Objects.requireNonNull(reason, "authorization reason");
        if (!allowed && reason.isBlank()) {
            throw new IllegalArgumentException(
                    "A denial must state a reason; it is the only record of why the gate refused.");
        }
    }

    public static Authorization allow() {
        return ALLOWED;
    }

    public static Authorization deny(String reason) {
        return new Authorization(false, reason);
    }
}
