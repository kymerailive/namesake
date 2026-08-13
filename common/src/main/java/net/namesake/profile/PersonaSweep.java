package net.namesake.profile;

import net.namesake.npc.Persona;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * The rotating record sweep of {@code DESIGN.md} §8: twenty buckets, one advanced per tick, so
 * every persona record is visited about once a second whether or not it has an entity in the world.
 *
 * <p><b>Why the buckets are pre-partitioned and not computed on the way past.</b> The cheap version
 * — walk the whole registry and skip records whose index does not match this tick — costs a pass
 * over every record every tick, which is the twenty-fold saving thrown away for tidiness. The other
 * cheap version — take index {@code i, i+20, i+40 …} of a snapshot list — is twenty times faster
 * and quietly wrong: the registry is a map, its iteration order shifts when a persona is added or
 * removed, and a record can slide across a bucket boundary and be visited twice or not at all. Two
 * missed visits a minute is exactly the kind of defect that never produces a stack trace.
 *
 * <p>So a persona's bucket is a function of its identity, and the partition is rebuilt when the
 * population changes rather than recomputed per tick. {@link #rebuild} is metered separately for
 * that reason: the walk is the cost this session is measuring, and the upkeep is the cost that
 * comes with it.
 *
 * <p><b>What this does not have yet, on purpose.</b> There is no per-record work here. The visitor
 * is supplied by the caller, and the only caller today is the profiler. Sessions 05 to 14 supply
 * the payload — day-plan slots, needs, gossip participation — and what session 04 hands them is a
 * measured frame plus a budget the payload has to fit inside.
 */
public final class PersonaSweep {

    /** {@code DESIGN.md} §8: twenty buckets is ~1 visit/second/record at 20 ticks a second. */
    public static final int BUCKETS = 20;

    @FunctionalInterface
    public interface Visitor {
        void visit(Persona persona);
    }

    private final List<List<Persona>> buckets = new ArrayList<>(BUCKETS);
    private int size;

    public PersonaSweep() {
        for (int i = 0; i < BUCKETS; i++) {
            buckets.add(new ArrayList<>());
        }
    }

    /**
     * Which bucket a persona belongs to, forever.
     *
     * <p>Derived from the persona id, which never changes — not when the villager is zombified,
     * not when the record is rewritten, not when another record is removed from the registry. That
     * is the whole point: a bucket derived from a position in a collection is a bucket that moves.
     */
    public static int bucketOf(UUID personaId) {
        return Math.floorMod(personaId.hashCode(), BUCKETS);
    }

    /** Repartitions from scratch. Called when the population changes, not per tick. */
    public void rebuild(Collection<Persona> personas) {
        for (List<Persona> bucket : buckets) {
            bucket.clear();
        }
        for (Persona persona : personas) {
            buckets.get(bucketOf(persona.id())).add(persona);
        }
        size = personas.size();
    }

    /**
     * Visits the bucket this tick is responsible for.
     *
     * @return how many records were visited
     */
    public int advance(int tick, Visitor visitor) {
        List<Persona> bucket = buckets.get(Math.floorMod(tick, BUCKETS));
        // Indexed rather than enhanced-for: an iterator per tick is an allocation per tick, and a
        // sweep that allocates 20 times a second is a sweep that shows up in the GC log rather
        // than in the profile.
        for (int i = 0; i < bucket.size(); i++) {
            visitor.visit(bucket.get(i));
        }
        return bucket.size();
    }

    public int size() {
        return size;
    }

    public int bucketSize(int bucket) {
        return buckets.get(Math.floorMod(bucket, BUCKETS)).size();
    }
}
