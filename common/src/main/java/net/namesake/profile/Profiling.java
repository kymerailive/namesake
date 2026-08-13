package net.namesake.profile;

/**
 * Whether this JVM is being measured, and how.
 *
 * <p><b>Entirely inert unless {@code -Dnamesake.profile=<phase>} is set.</b> Both constants below
 * are {@code static final} and read once at class initialisation, so every {@code if} that guards a
 * measurement folds away in a normal game rather than costing a branch. That is not tidiness: an
 * instrument that costs something when it is switched off has already changed the thing it exists
 * to measure.
 *
 * <p>The phases, all driven by {@code ProfilerHarness}:
 * <ul>
 *   <li>{@code vanilla} — our entity and tick hooks are turned off, and the run measures a
 *       Minecraft with villagers in it and none of our code. Hard rule 4's baseline.</li>
 *   <li>{@code namesake} — the same world, the same villager counts, our hooks live. The
 *       difference between the two is what we cost.</li>
 *   <li>{@code sections} — {@code vanilla}'s last cell on its own, for reading Minecraft's own
 *       section tree. Its metrics recorder stops itself ten seconds in, so the other five cells are
 *       twenty minutes of nothing when that tree is the only question.</li>
 *   <li>{@code world} — a real generated world, visited village by village, to find out what
 *       population a save actually produces and what the session 03 costs do in one.</li>
 * </ul>
 */
public final class Profiling {

    public static final String PROPERTY = "namesake.profile";

    private static final String PHASE = System.getProperty(PROPERTY, "").trim();

    /** True when any profiling phase is armed. Guards every meter call site in the mod. */
    public static final boolean ENABLED = !PHASE.isEmpty();

    /**
     * True when the mod's own hooks must do nothing.
     *
     * <p><b>Hard rule 4 has no other honest implementation.</b> "400 loaded vanilla villagers with
     * zero mod code" and "the same 400 with ours" have to be the same world, the same terrain, the
     * same JVM and the same JIT state, or the difference between them is measuring the machine
     * rather than the mod. The only way to get that is to be able to switch ourselves off, so this
     * switch is part of the mod rather than part of the harness.
     */
    public static final boolean MOD_INERT = "vanilla".equals(PHASE) || "sections".equals(PHASE);

    private Profiling() {
    }

    public static String phase() {
        return PHASE;
    }
}
