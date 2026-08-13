package net.namesake.profile;

/**
 * One named thing being timed, and the distribution of how long it took.
 *
 * <p>Recording is two calls around the work rather than a closure, so a measurement allocates
 * nothing and the timed region is exactly the code between them. The pattern at a call site is:
 *
 * <pre>{@code
 * long begun = Meters.now();
 * ... the work ...
 * meter.end(begun);
 * }</pre>
 *
 * <p>{@link Meters#now()} returns zero when profiling is off, so the guard at a call site can be a
 * single folded branch rather than a nanosecond clock read nobody asked for.
 */
public final class Meter {

    private final String name;
    private final Histogram histogram = new Histogram();

    Meter(String name) {
        this.name = name;
    }

    public String name() {
        return name;
    }

    public Histogram histogram() {
        return histogram;
    }

    /** Records the time since {@code begunAt}, which must have come from {@link Meters#now()}. */
    public void end(long begunAt) {
        histogram.record(System.nanoTime() - begunAt);
    }

    /** Records a duration measured somewhere else. */
    public void record(long nanos) {
        histogram.record(nanos);
    }

    public void clear() {
        histogram.clear();
    }

    @Override
    public String toString() {
        return name + "  " + histogram.describeMicros();
    }
}
