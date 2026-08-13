package net.namesake.profile;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.namesake.culture.Culture;
import net.namesake.npc.Persona;
import net.namesake.npc.TraitRoll;
import net.namesake.settlement.Settlement;
import net.namesake.settlement.Settlements;
import net.namesake.settlement.Specialty;

import java.util.ArrayList;
import java.util.List;

/**
 * Fixture personas, and the settlements they claim to come from.
 *
 * <p><b>Where they live and how they are removed, decided before any were built.</b> They are held
 * in the list this class returns and nowhere else. They are never offered to {@link
 * net.namesake.npc.NpcRegistry}, which refuses their id range at both its write door and its save
 * door, and they are gone when the list is. There is nothing to clean up, because nothing durable
 * is ever created — which is the only version of "removed afterwards" that survives a run that
 * crashes halfway through. The profiler proves it at the end by reading the registry <i>and</i> the
 * file on disk back and finding none, rather than by having meant to.
 *
 * <p><b>They are rolled, not zeroed.</b> A sweep's cost depends on what its payload reads, and a
 * record of eight zeroes with no settlement behind it exercises a branch pattern no real population
 * has. So the fixtures are placed in synthetic settlements, gathered into households of about
 * three, and run through the real {@link TraitRoll} — the same arithmetic session 03 ships.
 */
public final class SyntheticPersonas {

    /** Roughly a vanilla village. Used to derive a settlement count from a population. */
    public static final int RESIDENTS_PER_SETTLEMENT = 20;

    /** About three to a house, which is what session 03's 16-block household cell produces. */
    private static final int HOUSEHOLD_SIZE = 3;

    /**
     * How many records belong to nobody's village.
     *
     * <p>One in ten, which is deliberately generous. A wilderness record is the case where session
     * 03's load path does <i>not</i> return on its first line, and a fixture population with none
     * of them would flatter the measurement.
     */
    private static final int UNSETTLED_IN = 10;

    private SyntheticPersonas() {
    }

    /**
     * A synthetic settlement table, one entry per {@value #RESIDENTS_PER_SETTLEMENT} records.
     *
     * <p>Spread on a 512-block grid so {@code Settlements.containing} sees the same shape it would
     * in a world — one candidate in range and the rest rejected on distance.
     */
    public static Settlements settlements(int population) {
        Settlements settlements = new Settlements();
        int count = Math.max(1, population / RESIDENTS_PER_SETTLEMENT);
        for (int i = 0; i < count; i++) {
            int id = settlements.claimId();
            settlements.put(new Settlement(
                    id,
                    ResourceLocation.withDefaultNamespace("overworld"),
                    new BlockPos((i % 16) * 512, 68, (i / 16) * 512),
                    Specialty.values()[i % Specialty.values().length].id(),
                    (byte) (40 + (i * 7) % 55),
                    new byte[]{
                            (byte) ((i * 13) % 60),
                            (byte) ((i * 29) % 60),
                            (byte) ((i * 41) % 60),
                            (byte) ((i * 53) % 60)}));
        }
        return settlements;
    }

    /** {@code count} fixture personas placed against {@code settlements}. */
    public static List<Persona> build(int count, Settlements settlements) {
        int villages = Math.max(1, settlements.size());
        List<Persona> personas = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            Persona minted = Persona.create(Persona.profilingId(i), 0L);
            boolean unsettled = i % UNSETTLED_IN == UNSETTLED_IN - 1;
            int settlementId = unsettled ? Persona.UNASSIGNED : i % villages;
            int householdId = unsettled ? Persona.UNASSIGNED : i / HOUSEHOLD_SIZE;
            byte cultureId = (byte) (settlementId == Persona.UNASSIGNED
                    ? i % Culture.COUNT
                    : settlementId % Culture.COUNT);
            Persona placed = minted.placed(settlementId, householdId, cultureId);
            personas.add(placed.withTraits(TraitRoll.roll(placed, settlements)));
        }
        return personas;
    }

    /**
     * A stand-in for the per-record work sessions 05 to 14 will put in the sweep, so the ledger can
     * record a budget rather than a fake number.
     *
     * <p>Eight axis reads and a handful of branches, which is the shape of a personality-weighted
     * decision and of a day-plan slot choice. It is <b>not</b> a prediction of what that work will
     * cost — nobody knows yet. It is a probe of known shape whose own cost is measured alongside the
     * empty frame, so the two numbers together say how much room there is.
     */
    public static void probePayload(Persona persona) {
        int score = 0;
        for (int axis = 0; axis < Persona.TRAIT_COUNT; axis++) {
            byte value = persona.trait(axis);
            if (value > 40) {
                score += value;
            } else if (value < -40) {
                score -= value;
            }
        }
        if (persona.settlementId() != Persona.UNASSIGNED) {
            score += persona.householdId() & 0xFF;
        }
        // A static sink the JIT cannot prove nobody reads, so the loop above cannot be deleted. The
        // profiler logs it; a measurement of an optimised-away loop is the purest form of a number
        // that looks just as confident as a real one.
        SINK += score;
    }

    /** Read and logged by the profiler purely so {@link #probePayload} cannot be eliminated. */
    public static long SINK;
}
