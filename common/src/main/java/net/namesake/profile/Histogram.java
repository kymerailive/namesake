package net.namesake.profile;

import java.util.Locale;

/**
 * A distribution of nanosecond durations, kept in log-spaced buckets.
 *
 * <p><b>Why a distribution and not a mean.</b> {@code WORKPLAN.md}'s exit criterion for session 04
 * asks for "the distribution behind them, not one sample on a warm JIT", and a mean is exactly the
 * statistic that hides the thing a tick budget cares about. A sweep that costs 4 µs on average and
 * 900 µs once a second is not a 4 µs sweep; it is a stutter. Minecraft's own profiler keeps sum,
 * count, min and max per section and nothing else, which cannot tell those two apart.
 *
 * <p><b>Exact where it can be, bucketed where it cannot.</b> {@link #count}, {@link #sum},
 * {@link #min} and {@link #max} are exact — no bucketing error reaches the mean or the extremes.
 * Only the percentiles are read out of buckets, and the layout below bounds their error at ~3%:
 * values under {@value #SUB} ns get a bucket each, and every octave above that is split into
 * {@value #SUB} even steps. A percentile is reported as the <i>top</i> of its bucket, so the number
 * printed is never smaller than the truth.
 *
 * <p><b>Single-writer.</b> Everything that records into one of these does so from the server thread.
 * Nothing here is synchronised, deliberately: an instrument that takes a lock is an instrument that
 * changes what it measures.
 */
public final class Histogram {

    /** Buckets per octave, and the count of exactly-represented values at the bottom. */
    private static final int SUB = 32;

    private static final int LOG_SUB = 5;

    /**
     * Highest octave with buckets of its own — 2^40 ns is about eighteen minutes. Anything longer
     * lands in the top bucket, which costs nothing real: {@link #max} is exact, so a value past the
     * end is still reported truthfully as the maximum.
     */
    private static final int MAX_MSB = 40;

    private static final int BUCKETS = (MAX_MSB - LOG_SUB + 1) * SUB + SUB;

    private final long[] buckets = new long[BUCKETS];

    private long count;
    private long sum;
    private long min = Long.MAX_VALUE;
    private long max = Long.MIN_VALUE;

    /**
     * Which bucket a duration falls in.
     *
     * <p>Below {@value #SUB} the value is its own bucket. Above it, the bucket is the octave plus
     * the top {@value #LOG_SUB} bits below the leading one, which makes the layout contiguous:
     * bucket 31 is 31 ns, bucket 32 is 32 ns, and bucket 63 is 63 ns.
     */
    static int bucketOf(long nanos) {
        if (nanos < SUB) {
            return (int) Math.max(nanos, 0L);
        }
        int msb = 63 - Long.numberOfLeadingZeros(nanos);
        if (msb > MAX_MSB) {
            return BUCKETS - 1;
        }
        int sub = (int) ((nanos >>> (msb - LOG_SUB)) & (SUB - 1));
        return (msb - LOG_SUB + 1) * SUB + sub;
    }

    /** The largest duration that lands in {@code bucket}. Used to read percentiles conservatively. */
    static long upperBoundOf(int bucket) {
        if (bucket < SUB) {
            return bucket;
        }
        int octave = bucket / SUB - 1 + LOG_SUB;
        int sub = bucket % SUB;
        long step = 1L << (octave - LOG_SUB);
        return (1L << octave) + (long) (sub + 1) * step - 1;
    }

    public void record(long nanos) {
        count++;
        sum += nanos;
        if (nanos < min) {
            min = nanos;
        }
        if (nanos > max) {
            max = nanos;
        }
        buckets[bucketOf(nanos)]++;
    }

    public void clear() {
        java.util.Arrays.fill(buckets, 0L);
        count = 0;
        sum = 0;
        min = Long.MAX_VALUE;
        max = Long.MIN_VALUE;
    }

    public long count() {
        return count;
    }

    public long sum() {
        return sum;
    }

    public long min() {
        return count == 0 ? 0 : min;
    }

    public long max() {
        return count == 0 ? 0 : max;
    }

    /** Exact, because {@link #sum} is exact. */
    public double mean() {
        return count == 0 ? 0.0 : (double) sum / count;
    }

    /**
     * The smallest duration at or below which {@code fraction} of the samples fall, rounded up to
     * the top of its bucket.
     *
     * @param fraction 0.0 to 1.0
     */
    public long percentile(double fraction) {
        if (count == 0) {
            return 0;
        }
        // Ceiling, so p50 of two samples is the larger and p100 is the last sample rather than a
        // rank one past the end.
        long rank = (long) Math.ceil(fraction * count);
        if (rank < 1) {
            rank = 1;
        }
        long seen = 0;
        for (int bucket = 0; bucket < buckets.length; bucket++) {
            seen += buckets[bucket];
            if (seen >= rank) {
                // The exact max beats the bucket's upper bound whenever the bucket holds it, which
                // keeps p100 honest rather than rounded up.
                return Math.min(upperBoundOf(bucket), max);
            }
        }
        return max;
    }

    /** One line: count, mean, median, p95, p99 and max, in microseconds. */
    public String describeMicros() {
        if (count == 0) {
            return "n=0";
        }
        return String.format(Locale.ROOT,
                "n=%d  mean %s  p50 %s  p95 %s  p99 %s  max %s",
                count, micros(mean()), micros(percentile(0.50)),
                micros(percentile(0.95)), micros(percentile(0.99)), micros(max));
    }

    /** Microseconds, with enough decimals to still say something at the nanosecond scale. */
    public static String micros(double nanos) {
        double us = nanos / 1000.0;
        if (us < 1.0) {
            return String.format(Locale.ROOT, "%.3f us", us);
        }
        if (us < 1000.0) {
            return String.format(Locale.ROOT, "%.2f us", us);
        }
        return String.format(Locale.ROOT, "%.2f ms", us / 1000.0);
    }
}
