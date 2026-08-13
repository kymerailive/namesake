package net.namesake.profile;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The arithmetic behind every number session 04 reports.
 *
 * <p><b>This is where the instrument is calibrated against something whose answer is already
 * known.</b> The in-game calibration catches a clock read the wrong way round; this catches a
 * bucket layout that loses samples, a percentile that is off by a rank, and a mean that is really
 * a median. A profiler that measures the wrong thing reports a number just as confidently as one
 * that measures the right thing, and the arithmetic is the half of that which can be settled
 * without a game running.
 */
class HistogramTest {

    @Test
    @DisplayName("the bucket layout is contiguous and monotonic, with no value falling through it")
    void bucketsAreContiguousAndMonotonic() {
        int previous = -1;
        long cursor = 0;
        while (cursor < 4_000_000L) {
            final long value = cursor;
            final int bucket = Histogram.bucketOf(value);
            final int wasPreviously = previous;
            assertTrue(bucket >= wasPreviously,
                    () -> value + " landed in bucket " + bucket + ", behind the previous value's "
                            + wasPreviously + " — a layout that goes backwards mixes fast samples "
                            + "in with slow ones");
            // The bound reported for a bucket must actually bound the values in it, or every
            // percentile is quietly optimistic.
            assertTrue(Histogram.upperBoundOf(bucket) >= value,
                    () -> value + " is in bucket " + bucket + " whose upper bound is "
                            + Histogram.upperBoundOf(bucket));
            previous = bucket;
            cursor = cursor < 1024 ? cursor + 1 : cursor * 1009 / 1000;
        }
    }

    @Test
    @DisplayName("count, sum, min and max are exact — no bucketing error reaches them")
    void extremesAndSumAreExact() {
        Histogram histogram = new Histogram();
        long sum = 0;
        for (int i = 1; i <= 1000; i++) {
            long value = i * 37L + 11L;
            histogram.record(value);
            sum += value;
        }
        assertEquals(1000, histogram.count());
        assertEquals(sum, histogram.sum());
        assertEquals(48L, histogram.min());
        assertEquals(37_011L, histogram.max());
        assertEquals((double) sum / 1000, histogram.mean(), 1e-9);
    }

    @Test
    @DisplayName("percentiles of a known distribution land within the layout's 3% error")
    void percentilesAreWithinTheLayoutError() {
        Histogram histogram = new Histogram();
        // 1..10000 ns, one sample each: the p-th percentile is exactly p * 100.
        for (int value = 1; value <= 10_000; value++) {
            histogram.record(value);
        }
        assertWithin(5_000L, histogram.percentile(0.50), 0.04);
        assertWithin(9_500L, histogram.percentile(0.95), 0.04);
        assertWithin(9_900L, histogram.percentile(0.99), 0.04);
        assertEquals(10_000L, histogram.percentile(1.0),
                "p100 must be the largest sample exactly, not the top of its bucket");
    }

    /**
     * The reason this class exists rather than a running mean.
     *
     * <p>Minecraft's own profiler keeps a sum, a count and a max per section. A sweep that costs a
     * microsecond most ticks and a millisecond once a second has the same mean as one that costs
     * fifty microseconds every tick, and only one of them is a stutter. {@code WORKPLAN.md} asks
     * for the distribution for exactly this reason.
     */
    @Test
    @DisplayName("a spike that a mean would hide is visible at p99")
    void aSpikeSurvivesThePercentiles() {
        Histogram histogram = new Histogram();
        for (int i = 0; i < 980; i++) {
            histogram.record(1_000L);
        }
        for (int i = 0; i < 20; i++) {
            histogram.record(1_000_000L);
        }
        // The mean says twenty-one microseconds, which is a duration nothing here ever took.
        assertWithin(20_980L, (long) histogram.mean(), 0.01);
        assertWithin(1_000L, histogram.percentile(0.50), 0.04);
        assertWithin(1_000L, histogram.percentile(0.95), 0.04);
        assertWithin(1_000_000L, histogram.percentile(0.99), 0.04);
        assertEquals(1_000_000L, histogram.max());
    }

    @Test
    @DisplayName("an empty histogram says so rather than reporting zeroes as measurements")
    void emptyIsHonest() {
        Histogram histogram = new Histogram();
        assertEquals(0, histogram.count());
        assertEquals(0, histogram.max());
        assertEquals(0.0, histogram.mean());
        assertEquals("n=0", histogram.describeMicros());
    }

    @Test
    @DisplayName("clear wipes the exact fields as well as the buckets")
    void clearWipesEverything() {
        Histogram histogram = new Histogram();
        histogram.record(5_000L);
        histogram.clear();
        assertEquals(0, histogram.count());
        assertEquals(0, histogram.sum());
        assertEquals(0, histogram.min());
        assertEquals(0, histogram.max());
        assertEquals(0, histogram.percentile(0.5));
    }

    @Test
    @DisplayName("microsecond formatting keeps a significant figure at every scale we report")
    void formattingSaysSomethingAtEveryScale() {
        assertEquals("0.250 us", Histogram.micros(250));
        assertEquals("5.95 us", Histogram.micros(5950));
        assertEquals("18.00 ms", Histogram.micros(18_000_000));
    }

    private static void assertWithin(long expected, long actual, double tolerance) {
        double error = Math.abs(actual - expected) / (double) expected;
        assertTrue(error <= tolerance,
                () -> "expected about " + expected + " and got " + actual
                        + ", which is " + Math.round(error * 1000) / 10.0 + "% out");
    }
}
