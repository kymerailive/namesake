package net.namesake.settlement;

import net.namesake.npc.Persona;

/**
 * What a settlement mostly does for a living, read off its workstations.
 *
 * <p>Derived once by {@link SettlementSurvey} from the POI census and then persisted, because the
 * census is expensive and the answer does not move. Its whole job is the {@code traitBias} below:
 * a settlement's trade is the first thing that pulls its people away from their culture's baseline,
 * which is why a Vale mining town and a Vale farming town are not the same place.
 *
 * <p>Ordinals are persisted, so <b>append only</b> — inserting a constant repaints every existing
 * settlement.
 */
public enum Specialty {

    /** No workstation class has a clear plurality, or there are too few to tell. */
    MIXED(new byte[]{0, 0, 0, 0, 0, 0, 0, 0}),

    /** Composters. Patient, rooted, and not especially interested in elsewhere. */
    FARMING(new byte[]{5, 8, 0, -5, 10, 0, 0, 0}),

    /** Smokers, looms, cauldrons. Animals and cloth; warmer and slower to anger. */
    PASTORAL(new byte[]{8, 4, 0, 0, 5, 0, -5, 5}),

    /** Blast furnaces, grindstones, smithing and fletching tables. Hot work, short tempers. */
    SMITHING(new byte[]{-3, 12, 5, 0, 0, 5, 8, -5}),

    /** Lecterns, cartography tables, brewing stands. Curious, and less bound by how it was done. */
    SCHOLARLY(new byte[]{0, 5, -5, 15, -8, 0, -5, 0}),

    /** Barrels. Boats and weather; bold, and used to waiting. */
    FISHING(new byte[]{0, 5, 10, 5, 5, 0, 0, 5}),

    /** Stonecutters. Slow, exact, conservative work. */
    MASONRY(new byte[]{-3, 10, 0, -8, 12, 0, 5, -3});

    private final byte[] traitBias;

    Specialty(byte[] traitBias) {
        if (traitBias.length != Persona.TRAIT_COUNT) {
            throw new IllegalArgumentException(name() + " needs exactly "
                    + Persona.TRAIT_COUNT + " trait axes");
        }
        this.traitBias = traitBias;
    }

    public byte id() {
        return (byte) ordinal();
    }

    public static Specialty byId(byte id) {
        Specialty[] all = values();
        if (id < 0 || id >= all.length) {
            throw new IllegalArgumentException("No specialty with id " + id);
        }
        return all[id];
    }

    /**
     * How this trade pulls the settlement mean, per axis. Deliberately small next to a culture's
     * baseline: the trade tilts a place, it does not define it.
     */
    public byte traitBias(int axis) {
        return traitBias[axis];
    }
}
