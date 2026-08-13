package net.namesake.settlement;

/**
 * What a settlement is short of, on a 0..100 scale where 100 is desperate.
 *
 * <p>The needs vector is the survey's third output and, with {@code defensibility}, the material
 * half of what makes one settlement's people unlike another's: a village that cannot feed itself
 * raises harder, greedier people than one that can. {@code TraitRoll} reads it directly.
 *
 * <p><b>There is deliberately no {@code DEFENCE} need.</b> It would be {@code 100 - defensibility}
 * and nothing else — two persisted numbers for one fact, which is the duplication rule 5 exists to
 * catch. Defensibility is its own axis on {@link Settlement} and needs stays four wide.
 *
 * <p>Ordinals index the persisted array, so <b>append only</b>.
 */
public enum Need {

    /** Composters, barrels, smokers, looms — anything that puts food on a table. */
    FOOD,

    /** Blast furnaces, grindstones, smithing tables, stonecutters. */
    TOOLS,

    /** Beds, measured against the number of job sites wanting someone to fill them. */
    SHELTER,

    /** Lecterns, cartography tables, brewing stands — the things a village trades outward. */
    TRADE_GOODS;

    public static final int COUNT = values().length;

    public int index() {
        return ordinal();
    }
}
