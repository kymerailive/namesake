package net.namesake.verb;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;

import java.util.UUID;

/**
 * What a verb acts on.
 *
 * <p>A target exposes geometry and validity but performs no checks of its own — the base class in
 * {@link ServerboundVerb} does all of that. A target that could answer "am I in reach" would be a
 * target that could lie about it.
 */
public interface VerbTarget {

    /**
     * Stable identity of the thing targeted. An interaction token is bound to this value, so it
     * must survive anything the target survives — for an NPC that means the persona id, not the
     * entity UUID, which changes on every zombification and cure.
     */
    UUID key();

    ResourceKey<Level> dimension();

    /** The box the reach check measures to, matching what vanilla measures against. */
    AABB bounds();

    /**
     * Still a legal target this tick: loaded, alive, and still the kind of thing it claimed to be.
     *
     * <p>Resolution and validity are separate because they fail for different reasons — a packet
     * naming an entity id that is not an NPC at all is a forged packet; one naming an NPC that died
     * two ticks ago is ordinary lag.
     */
    boolean stillValid();

    /** For log lines only. */
    String describe();
}
