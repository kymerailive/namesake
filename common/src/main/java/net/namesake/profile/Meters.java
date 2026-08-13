package net.namesake.profile;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Every meter and counter in the mod, and the report they are read out of.
 *
 * <p>Two kinds of evidence, because this session needs both. A <b>meter</b> answers "how long"; a
 * <b>counter</b> answers "how often". Session 03 left a claim that needs the second kind —
 * {@code Personas.onPersonaLoaded} "returns on its first line for anyone already generated and
 * settled, which is everybody, almost always" — and no amount of timing settles it. If the cheap
 * branch is taken 3% of the time, a fast branch is worthless. So the branches are counted.
 *
 * <p><b>Server thread only.</b> Nothing here is synchronised. A meter written from two threads
 * would need a lock, and a lock in an instrument changes the thing being measured. The one piece of
 * our work that genuinely runs off-thread — the settlement scoring — is timed by handing its
 * duration back to the server thread instead of metering it in place.
 */
public final class Meters {

    private static final Map<String, Meter> METERS = new LinkedHashMap<>();
    private static final Map<String, long[]> COUNTERS = new LinkedHashMap<>();

    private Meters() {
    }

    public static boolean enabled() {
        return Profiling.ENABLED;
    }

    /** The clock, or zero when profiling is off so a guarded call site costs nothing. */
    public static long now() {
        return Profiling.ENABLED ? System.nanoTime() : 0L;
    }

    /**
     * The meter with this name, created on first use.
     *
     * <p>Call sites should hold the returned meter in a {@code static final} field rather than
     * looking it up per call — a hash lookup inside a 300 ns measurement is a measurable share of
     * it.
     */
    public static Meter meter(String name) {
        return METERS.computeIfAbsent(name, Meter::new);
    }

    public static void count(String name) {
        count(name, 1L);
    }

    public static void count(String name, long by) {
        COUNTERS.computeIfAbsent(name, key -> new long[1])[0] += by;
    }

    public static long counter(String name) {
        long[] slot = COUNTERS.get(name);
        return slot == null ? 0L : slot[0];
    }

    public static List<String> counterNames() {
        return List.copyOf(COUNTERS.keySet());
    }

    /** Wipes every measurement. Called between windows so a cell measures only itself. */
    public static void reset() {
        METERS.values().forEach(Meter::clear);
        COUNTERS.values().forEach(slot -> slot[0] = 0L);
    }

    /** Every meter that has seen a sample, and every counter that has been incremented. */
    public static List<String> report() {
        List<String> lines = new ArrayList<>();
        for (Meter meter : METERS.values()) {
            if (meter.histogram().count() > 0) {
                lines.add(String.format(Locale.ROOT, "  %-46s %s",
                        meter.name(), meter.histogram().describeMicros()));
            }
        }
        for (Map.Entry<String, long[]> entry : COUNTERS.entrySet()) {
            if (entry.getValue()[0] > 0) {
                lines.add(String.format(Locale.ROOT, "  %-46s %d", entry.getKey(), entry.getValue()[0]));
            }
        }
        if (lines.isEmpty()) {
            // DESIGN.md §11's rule, applied to an instrument: an empty report that says nothing
            // reads as "our code costs nothing" when it means "nothing was measured".
            lines.add("  (no meter recorded a sample and no counter moved)");
        }
        return lines;
    }
}
