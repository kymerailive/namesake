package net.namesake.culture;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Standing risk 3, measured.
 *
 * <p>{@code WORKPLAN.md}: <i>if settlement two sounds and behaves like settlement one, the travel
 * loop collapses around hour 45 and no era ladder saves it.</i> The culture map is the first
 * defence, and it has two failure modes that pull against each other:
 *
 * <ul>
 *   <li><b>Too coherent</b> and every village within a thousand blocks is the same culture, so
 *       walking to the next one changes nothing.</li>
 *   <li><b>Too noisy</b> and culture reads as a die roll per village rather than as somewhere
 *       people are from.</li>
 * </ul>
 *
 * <p>Vanilla villages average a little over 500 blocks apart, so the numbers below are chosen
 * against that spacing: two points a village apart should usually differ, two points inside one
 * village's reach should usually not. These are properties of a distribution, so they are measured
 * over thousands of samples rather than asserted on one.
 */
class CulturesTest {

    private static final long SEED = 0x4E414D4553414B45L;

    /** Roughly plains: hot enough to be habitable, not enough to favour one culture outright. */
    private static final float TEMPERATE = 0.8F;

    @Test
    @DisplayName("two points a village apart usually belong to different cultures")
    void neighbouringVillagesUsuallyDiffer() {
        int samples = 4000;
        int differ = 0;
        for (int i = 0; i < samples; i++) {
            int x = scatter(i, 1);
            int z = scatter(i, 2);
            // A village apart, in a direction that is not axis-aligned, so a grid artefact would
            // not be able to hide behind the sampling.
            int dx = 640 - (i % 7) * 40;
            int dz = (i % 5) * 90 - 180;
            if (Cultures.at(SEED, x, z, TEMPERATE) != Cultures.at(SEED, x + dx, z + dz, TEMPERATE)) {
                differ++;
            }
        }
        double rate = (double) differ / samples;
        assertTrue(rate > 0.60,
                () -> "only " + Math.round(rate * 100) + "% of village-spaced pairs differ in "
                        + "culture. Standing risk 3: the second village sounds like the first.");
    }

    @Test
    @DisplayName("two points inside one village's reach usually share a culture")
    void aRegionIsCoherent() {
        int samples = 4000;
        int same = 0;
        for (int i = 0; i < samples; i++) {
            int x = scatter(i, 3);
            int z = scatter(i, 4);
            if (Cultures.at(SEED, x, z, TEMPERATE) == Cultures.at(SEED, x + 48, z + 24, TEMPERATE)) {
                same++;
            }
        }
        double rate = (double) same / samples;
        assertTrue(rate > 0.75,
                () -> "only " + Math.round(rate * 100) + "% of neighbouring points share a culture. "
                        + "Culture is reading as noise rather than as a place people are from.");
    }

    @Test
    @DisplayName("every culture is reachable, in every climate")
    void everyCultureIsReachableEverywhere() {
        for (float temperature : new float[]{-0.5F, 0.0F, 0.5F, 0.8F, 1.2F, 2.0F}) {
            Map<Culture, Integer> counts = census(temperature, 3000);
            for (Culture culture : Culture.values()) {
                assertTrue(counts.getOrDefault(culture, 0) > 0,
                        () -> culture + " never appears at temperature " + temperature
                                + ". A culture that cannot occur somewhere is one fewer surprise "
                                + "for a player who travels.");
            }
        }
    }

    /**
     * Climate has to tilt the map — a culture that correlates with terrain reads as placed rather
     * than sprinkled — without deciding it, because a climate that decides makes every temperate
     * village in a thousand blocks the same culture.
     */
    @Test
    @DisplayName("climate leans the map without deciding it")
    void climateLeansButDoesNotDecide() {
        Map<Culture, Integer> cold = census(0.0F, 6000);
        Map<Culture, Integer> hot = census(2.0F, 6000);

        assertTrue(cold.get(Culture.KARSK) > hot.get(Culture.KARSK),
                "the cold-weather culture must be commoner in the cold");
        assertTrue(hot.get(Culture.ASHANI) > cold.get(Culture.ASHANI),
                "the hot-weather culture must be commoner in the heat");

        for (Map<Culture, Integer> census : java.util.List.of(cold, hot)) {
            for (Map.Entry<Culture, Integer> entry : census.entrySet()) {
                double share = entry.getValue() / 6000.0;
                assertTrue(share < 0.45,
                        () -> entry.getKey() + " holds " + Math.round(share * 100)
                                + "% of one climate. Climate is deciding, not leaning.");
            }
        }
    }

    @Test
    @DisplayName("the map is a pure function of seed, position and climate")
    void theMapIsDeterministic() {
        for (int i = 0; i < 500; i++) {
            int x = scatter(i, 5);
            int z = scatter(i, 6);
            assertEquals(Cultures.at(SEED, x, z, TEMPERATE), Cultures.at(SEED, x, z, TEMPERATE));
        }
    }

    @Test
    @DisplayName("two worlds do not get the same culture map")
    void theWorldSeedMatters() {
        int samples = 2000;
        int differ = 0;
        for (int i = 0; i < samples; i++) {
            int x = scatter(i, 7);
            int z = scatter(i, 8);
            if (Cultures.at(SEED, x, z, TEMPERATE) != Cultures.at(SEED + 1, x, z, TEMPERATE)) {
                differ++;
            }
        }
        int measured = differ;
        assertTrue(measured > samples / 2,
                () -> "only " + measured + "/" + samples + " positions differ between two world "
                        + "seeds; the map is barely seeded at all");
    }

    @Test
    @DisplayName("negative coordinates behave like positive ones")
    void theMapIsSymmetricAboutTheOrigin() {
        // Math.floorDiv rather than integer division is the whole point: -1 / 512 is 0, which would
        // glue the four quadrants around the origin into one oversized region.
        Map<Culture, Integer> negative = new EnumMap<>(Culture.class);
        for (int x = -8000; x < 0; x += 137) {
            for (int z = -8000; z < 0; z += 149) {
                negative.merge(Cultures.at(SEED, x, z, TEMPERATE), 1, Integer::sum);
            }
        }
        assertEquals(Culture.COUNT, negative.size(),
                "the negative quadrant should show every culture, as the positive one does");
    }

    private static Map<Culture, Integer> census(float temperature, int samples) {
        Map<Culture, Integer> counts = new EnumMap<>(Culture.class);
        for (Culture culture : Culture.values()) {
            counts.put(culture, 0);
        }
        for (int i = 0; i < samples; i++) {
            // Stride by more than a region so consecutive samples are independent draws.
            counts.merge(Cultures.at(SEED, i * 613, i * 719, temperature), 1, Integer::sum);
        }
        return counts;
    }

    /** Spreads sample points over a wide area without a repeating stride. */
    private static int scatter(int index, int salt) {
        long z = (long) index * 0x9E3779B97F4A7C15L + salt * 0xC2B2AE3D27D4EB4FL;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return (int) ((z ^ (z >>> 31)) % 200_000);
    }
}
