package net.namesake.verb;

import java.util.List;

/**
 * Every verb this mod ships, in one list.
 *
 * <p>Separate from {@link VerbRegistry} so that a test can enumerate the mod's verbs without
 * depending on whether mod initialisation has run. The registry holds process-wide state; this
 * holds a fact about the source tree.
 */
public final class VerbCatalog {

    private VerbCatalog() {
    }

    /** Fresh instances each call — verbs are stateless, and a test must not share the registry's. */
    public static List<ServerboundVerb<?, ?>> all() {
        return List.of(new GreetVerb());
    }
}
