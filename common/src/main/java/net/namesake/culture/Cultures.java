package net.namesake.culture;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;

/**
 * Where each culture lives.
 *
 * <p><b>This function is standing risk 3.</b> If the next settlement over sounds like the last one,
 * the travel loop collapses around hour 45 and no amount of later content saves it. So the two
 * properties that matter are in tension and both are tested:
 *
 * <ul>
 *   <li><b>Neighbours usually differ.</b> Vanilla villages sit roughly 500 blocks apart, so the
 *       region size is 512 — far enough that one village rarely spans two regions, close enough
 *       that walking to the next village usually crosses a border.</li>
 *   <li><b>A region is coherent.</b> Two points a stone's throw apart are almost always the same
 *       culture, so a settlement and its outskirts agree and the world does not read as noise.</li>
 * </ul>
 *
 * <p><b>Voronoi, not a grid.</b> A plain 512-block grid puts culture borders on exact multiples of
 * 512 in both axes, which is visible the moment anyone notices two neighbouring villages differ
 * along a suspiciously straight line. Each cell instead gets one jittered anchor and a position
 * belongs to the nearest anchor among the nine cells around it, which costs nine hashes and makes
 * the borders irregular.
 *
 * <p><b>Climate leans, it does not decide.</b> A cold place is more likely to be Karsk than Ashani,
 * because a culture that correlates with terrain reads as <i>placed</i> rather than sprinkled. But
 * the bias is deliberately mild — a floor under every weight keeps all six reachable everywhere. If
 * climate decided outright, every temperate village in a thousand blocks would be the same culture,
 * which is the failure this function exists to avoid.
 */
public final class Cultures {

    /**
     * Blocks across one culture region. Vanilla's village spacing is 32 chunks with a separation of
     * 8, so villages average a little over 500 blocks apart.
     */
    public static final int REGION_SIZE = 512;

    /**
     * Divides a biome's base temperature into {@code 0..1}. Vanilla runs about -0.5 (snowy peaks)
     * to 2.0 (desert), but almost everything habitable sits under 1.2, so that is the top of the
     * useful range.
     */
    private static final float TEMPERATURE_SCALE = 1.2F;

    /**
     * Weight floor. Every culture keeps a real chance in every climate, so a run of temperate
     * villages does not become a run of one culture.
     */
    private static final float BASE_WEIGHT = 0.35F;

    private Cultures() {
    }

    /**
     * The culture of a place, from the world seed and the local climate.
     *
     * <p>Kept free of Minecraft types so it can be tested for the two properties above without a
     * running game. {@link #at(ServerLevel, BlockPos)} is the thin wrapper that reads them.
     *
     * @param temperature the biome's base temperature, on vanilla's scale
     */
    public static Culture at(long worldSeed, int blockX, int blockZ, float temperature) {
        int cellX = Math.floorDiv(blockX, REGION_SIZE);
        int cellZ = Math.floorDiv(blockZ, REGION_SIZE);

        int ownerX = cellX;
        int ownerZ = cellZ;
        long nearest = Long.MAX_VALUE;

        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                int candidateX = cellX + dx;
                int candidateZ = cellZ + dz;
                long jitter = hash(worldSeed, candidateX, candidateZ, 0x9E3779B9L);
                long anchorX = (long) candidateX * REGION_SIZE + Long.remainderUnsigned(jitter, REGION_SIZE);
                long anchorZ = (long) candidateZ * REGION_SIZE
                        + Long.remainderUnsigned(jitter >>> 21, REGION_SIZE);

                long deltaX = anchorX - blockX;
                long deltaZ = anchorZ - blockZ;
                long distance = deltaX * deltaX + deltaZ * deltaZ;
                if (distance < nearest) {
                    nearest = distance;
                    ownerX = candidateX;
                    ownerZ = candidateZ;
                }
            }
        }
        return inRegion(worldSeed, ownerX, ownerZ, temperature);
    }

    /** The culture of a place in a live world. */
    public static Culture at(ServerLevel level, BlockPos pos) {
        float temperature = level.getBiome(pos).value().getBaseTemperature();
        return at(level.getSeed(), pos.getX(), pos.getZ(), temperature);
    }

    /**
     * Which culture claimed one region. A weighted draw, not a modulo: modulo would make the
     * culture a pure function of the cell coordinates and throw the climate away.
     */
    private static Culture inRegion(long worldSeed, int cellX, int cellZ, float temperature) {
        float climate = Mth.clamp(temperature / TEMPERATURE_SCALE, 0.0F, 1.0F);

        float total = 0.0F;
        float[] weights = new float[Culture.COUNT];
        for (Culture culture : Culture.values()) {
            float affinity = 1.0F - Math.abs(climate - culture.preferredTemperature());
            float weight = BASE_WEIGHT + affinity * affinity;
            weights[culture.id()] = weight;
            total += weight;
        }

        // A million buckets is far finer than six weights need, and keeps the draw in integer
        // arithmetic until the last step so it cannot drift between platforms.
        long roll = Long.remainderUnsigned(hash(worldSeed, cellX, cellZ, 0x85EBCA6BL), 1_000_000L);
        float target = (float) roll / 1_000_000.0F * total;

        float running = 0.0F;
        for (Culture culture : Culture.values()) {
            running += weights[culture.id()];
            if (target < running) {
                return culture;
            }
        }
        // Only reachable through floating-point rounding at the very top of the range.
        return Culture.values()[Culture.COUNT - 1];
    }

    private static long hash(long worldSeed, int cellX, int cellZ, long salt) {
        long z = worldSeed ^ salt;
        z += (long) cellX * 0xC2B2AE3D27D4EB4FL;
        z += (long) cellZ * 0x165667B19E3779F9L;
        z += 0x9E3779B97F4A7C15L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }
}
