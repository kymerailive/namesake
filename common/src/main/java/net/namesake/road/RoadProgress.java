package net.namesake.road;

/**
 * How much of one road exists as blocks, as values rather than as a sentence.
 *
 * <p><b>A record rather than a formatted row, and the reason is a compile-time fact rather than
 * taste.</b> {@code RoadNetwork} names {@code Blocks.GRASS_BLOCK} in a static field, so touching it
 * from a plain unit test runs Minecraft's registry bootstrap and throws before the first assertion.
 * {@code CommandLayoutTest} has to be able to build the widest row this command can print without a
 * running game — session 07 shipped three over-width absence branches past a guard that could only
 * reach a populated fixture — so the road package reports facts and {@code NamesakeCommands} does
 * the layout.
 *
 * @param routed false when no route was found at all, as opposed to one found and refused as too
 *               hard. Two different sentences, and a player who reads "no road" wants to know which.
 */
public record RoadProgress(RoadEdge edge, int laid, int columns, int refused, int chunks,
                           double roughness, boolean routed) {

    /** An edge with no road worth laying along it. See {@link RoadPath#IMPASSABLE_RATIO}. */
    public static RoadProgress unbuilt(RoadEdge edge, boolean routed, double roughness) {
        return new RoadProgress(edge, 0, 0, 0, 0, roughness, routed);
    }

    public boolean buildable() {
        return columns > 0;
    }
}
