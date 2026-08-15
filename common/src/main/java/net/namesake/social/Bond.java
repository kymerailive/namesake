package net.namesake.social;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/**
 * What one NPC feels about one other party. {@code DESIGN.md} §3.
 *
 * <p>Four signed axes and a debt scalar, plus three fields of bookkeeping that make the axes behave
 * like a relationship rather than like a score: a daily cap so attention cannot be ground out, a
 * last-seen day so absence costs something, and a high-water mark so it never costs everything.
 *
 * <p><b>Immutable, and every change goes through {@link #apply}.</b> That is not style. The cap,
 * the floors, the ceiling and the high-water mark are four invariants that have to hold on every
 * write, and a mutable field with four separate setters is four places to forget one of them.
 *
 * <h2>The three rules that are easy to get subtly wrong</h2>
 *
 * <ol>
 *   <li><b>A negative is never softened.</b> Not by the daily cap, not by a forgiving personality,
 *       not by rounding. You cannot spend your allowance for the day and then hit someone for free,
 *       and a placid villager does not charge less for a murder. {@link Personality} may still make
 *       a negative <i>worse</i> — a hot temper takes a blow harder — which is the whole reason the
 *       weight table has a column for the harmful deeds at all.</li>
 *   <li><b>The floors are asymmetric, and deliberately.</b> Warmth and fear bottom out at zero:
 *       there is no such thing as negative warmth, only its absence, and "unafraid" is not a
 *       feeling. Trust runs to −64, because active distrust is a real state that a villager can be
 *       in and can act on — and {@code Standing.RESENTED} is the {@code if} statement that reads
 *       it there.</li>
 *   <li><b>Decay is lazy and has a floor of its own.</b> Nothing ticks a bond. Warmth is brought up
 *       to the current day by {@link #decayedTo} the moment anyone looks at it, and it falls only
 *       as far as {@link #DECAY_TARGET} of the warmest it has ever been. Someone who mattered to you
 *       and then went away for a year is not a stranger when they come back.</li>
 * </ol>
 *
 * <h2>{@code respect} was a fourth axis and is gone at schema 8</h2>
 *
 * <p>{@code DESIGN.md} §3 gave it a line — <i>do they defer to you</i> — and the rule 5 ledger gave
 * it an exemption expiring at the close of session 12, to be paid by the trade price band. Session
 * 12 ran the instrument session 07 exists for and the band could not honestly read it:
 *
 * <ul>
 *   <li><b>Measured, it is not a quantity anybody has.</b> Across a hundred in-game days and five
 *       player models the observed maximum is <b>zero, zero, zero, zero and four</b> — the four on
 *       one villager of nine — and a witness sweep from nobody watching to everybody watching does
 *       not move it by a point. {@code DialogueStats.LADDER}'s lowest mark is twenty. That is LNK's
 *       failure with worse numbers, in the session whose own instrument was built to prevent it.</li>
 *   <li><b>Structurally, it could never be decisive.</b> {@code DEFENDED_RAID} wrote {@code +12}
 *       respect and {@code +8} trust and both were held to the same per-axis daily allowance, so no
 *       raid raised respect without raising trust beside it; {@code STRUCK_RESIDENT} wrote
 *       {@code +1}, which a personality weight below neutral rounds to nothing. There was no state
 *       of the world in which a threshold on respect said something a threshold on trust did
 *       not.</li>
 *   <li><b>Making more deeds write it was refused on design grounds.</b> The respect column could
 *       have moved without perturbing a single number sessions 07, 08 and 09 measured, because
 *       {@link Deeds#deltaFor} computes the axes independently — but no deed in the table honestly
 *       earns deference except the raid defence that already did. Deference is not bought with
 *       bread.</li>
 * </ul>
 *
 * <p><b>The cost is real and is recorded rather than argued away.</b> {@code DESIGN.md} §6's
 * force-resolution wants <i>deference up</i>, and session 16 will have to add an axis back and pay
 * hard rule 1 for it. That is the price of the rule in {@code DESIGN.md} §1, and the rule is what
 * both reference codebases died without: <i>moving the number is the one thing the mechanism exists
 * to stop.</i> {@link #fear} and {@link #debt} carry the same mechanic's exemption to session 16 and
 * are untouched, because their dates have not come.
 */
public record Bond(
        byte trust,
        byte warmth,
        byte fear,
        short debt,
        int lastSeenDay,
        short gainedToday,
        byte peakWarmth) {

    public static final int TRUST = 0;
    public static final int WARMTH = 1;
    public static final int FEAR = 2;

    public static final int AXIS_COUNT = 3;

    /** Index-aligned with the axis constants. Used by {@code /namesake debug bond}. */
    public static final String[] AXIS_NAMES = {"trust", "warmth", "fear"};

    /**
     * The bottom of each axis, in axis order.
     *
     * <p>Warmth and fear floor at zero and trust at −64 — see rule 2 above. The ceiling is 100
     * everywhere, which is why there is no matching array for it.
     */
    private static final int[] FLOOR = {-64, 0, 0};

    private static final int CEILING = 100;

    /**
     * How much one axis may gain from one party in one in-game day, <b>for a typical villager</b>.
     *
     * <p>The single most load-bearing number in the social system: without it the shortest path to
     * a village that loves you is to stand in the square handing out bread until it does, and every
     * threshold session 07 measures would be measuring how long somebody was willing to click.
     *
     * <p><b>A base rather than a constant, from the close of session 05.</b> Personality scales it —
     * see {@link Personality#allowance} — so a receptive villager's day is worth about ten and a
     * closed one's about six. That is where personality lives now, because scaling only the
     * per-deed step was erased the moment a player gave enough gifts to fill the cap: everybody
     * converged on the same eight and personality decided nothing but how many gifts it took.
     *
     * <p>Eight is provisional and is <b>due for calibration against session 07's earn-rate
     * histogram</b>, not against anyone's intuition — that is the mistake LNK made when it set skill
     * gates at 35–205 against an observed maximum of 32.
     */
    public static final int DAILY_CAP = 8;

    /**
     * Warmth decays toward this fraction of the highest it has ever reached.
     *
     * <p>{@code DESIGN.md} §3 words it as "decay target = peak * 0.4". It is what stops absence from
     * being a reset button: a villager who once counted you a friend and has not seen you in a
     * season is cooler, not blank.
     */
    public static final float DECAY_TARGET = 0.4F;

    /**
     * How many days of decay a single lazy catch-up may apply.
     *
     * <p>On {@code WORKPLAN.md}'s never-cut list. Without it a bond touched once and then left for
     * an in-game decade catches up in one step from a {@code dayDelta} in the tens of thousands,
     * which overflows nothing and quietly means "everyone you ever met is at the floor". With it,
     * the worst a single catch-up can do is bounded and testable.
     */
    public static final int MAX_DAY_DELTA = 64;

    /** How many days of absence it takes to lose one point of warmth. */
    private static final int WARMTH_LOST_PER_DAY = 1;

    /** One counter of 0..{@link #DAILY_CAP} per axis, packed four bits each. */
    private static final int NIBBLE_BITS = 4;
    private static final int NIBBLE_MASK = 0xF;

    public static final Codec<Bond> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.BYTE.fieldOf("trust").forGetter(Bond::trust),
            Codec.BYTE.fieldOf("warmth").forGetter(Bond::warmth),
            Codec.BYTE.fieldOf("fear").forGetter(Bond::fear),
            Codec.SHORT.fieldOf("debt").forGetter(Bond::debt),
            Codec.INT.fieldOf("lastSeenDay").forGetter(Bond::lastSeenDay),
            Codec.SHORT.fieldOf("gainedToday").forGetter(Bond::gainedToday),
            Codec.BYTE.fieldOf("peakWarmth").forGetter(Bond::peakWarmth)
    ).apply(instance, Bond::new));

    /** A bond that has never been anything, as of {@code day}. */
    public static Bond fresh(int day) {
        return new Bond((byte) 0, (byte) 0, (byte) 0, (short) 0, day, (short) 0, (byte) 0);
    }

    public byte axis(int axis) {
        return switch (axis) {
            case TRUST -> trust;
            case WARMTH -> warmth;
            case FEAR -> fear;
            default -> throw new IllegalArgumentException("No bond axis " + axis);
        };
    }

    /** How much this axis has already gained today, before {@link #decayedTo} is applied. */
    public int gainedToday(int axis) {
        return (gainedToday >> (axis * NIBBLE_BITS)) & NIBBLE_MASK;
    }

    /** True while nothing has ever happened between these two — the state that is not worth storing. */
    public boolean isNothing() {
        return trust == 0 && warmth == 0 && fear == 0 && debt == 0;
    }

    /**
     * This bond as it stands on {@code day}: warmth brought down toward its floor, and the daily
     * allowance reset if the day has turned.
     *
     * <p><b>Lazy, pure and idempotent.</b> Nothing ticks; this is called by every read and by every
     * write. Because it stamps {@code lastSeenDay}, calling it twice for the same day is the same
     * as calling it once — which is what makes it safe for a read path to return a decayed view
     * without persisting it. A read that had to write would mark the whole registry dirty every
     * time anyone looked at a villager.
     *
     * <p>A day earlier than {@code lastSeenDay} returns this bond untouched rather than decaying
     * backwards. Game time is monotonic so it should not happen; a restored backup is how it would.
     */
    public Bond decayedTo(int day) {
        // Through the accessors rather than the fields, and that is not a style choice: the rule 5
        // ledger proves a consumer by reading its bytecode for a call, and a record touching its
        // own field compiles to a getfield that no such check can see. These three fields are
        // bookkeeping whose only reader is the rule they implement, so this method is genuinely
        // where they are consumed — and it has to be visible as such.
        if (day <= lastSeenDay()) {
            return this;
        }
        int days = Math.min(day - lastSeenDay(), MAX_DAY_DELTA);
        int floor = Math.round(peakWarmth() * DECAY_TARGET);
        // Only ever downward. A bond knocked below its own decay floor by a negative deed must not
        // be healed by the player staying away, which is what an unconditional max() would do.
        int decayed = warmth > floor
                ? Math.max(floor, warmth - days * WARMTH_LOST_PER_DAY)
                : warmth;
        return new Bond(trust, (byte) decayed, fear, debt, day, (short) 0, peakWarmth());
    }

    /**
     * Adds a per-axis delta, enforcing every invariant this record has.
     *
     * <p>Order matters and is the order {@code DESIGN.md} §4 step 4 states: the bond is first
     * brought up to {@code day} — which resets the allowance if the day turned — and only then is
     * the delta spent against what remains of it.
     *
     * @param delta     four signed values in axis order, already weighted by {@link Deeds#deltaFor}
     * @param day       the in-game day the deed happened on
     * @param allowance how much this axis may gain today — {@link Personality#allowance} for a real
     *                  villager, {@link #DAILY_CAP} for anything that has no persona to ask.
     *                  Clamped to what the four-bit counters can hold; the build-time guard that
     *                  the clamp can never engage is in {@code BondTest}.
     */
    public Bond apply(int[] delta, int day, int allowance) {
        if (delta.length != AXIS_COUNT) {
            throw new IllegalArgumentException("A bond delta has " + AXIS_COUNT + " axes, got " + delta.length);
        }
        int capped = Math.max(0, Math.min(NIBBLE_MASK, allowance));
        Bond base = decayedTo(day);

        int[] axes = {base.trust, base.warmth, base.fear};
        // The accessor, for the reason given in decayedTo: this is where gainedToday is consumed
        // and a getfield would be invisible to the check that proves it.
        short gained = base.gainedToday();

        for (int axis = 0; axis < AXIS_COUNT; axis++) {
            int change = delta[axis];
            if (change == 0) {
                continue;
            }
            if (change < 0) {
                // No cap and no counter. A negative is not an expenditure of anyone's goodwill
                // allowance, and letting it consume one would mean a strike today made tomorrow's
                // apology cheaper.
                axes[axis] = clamp(axes[axis] + change, axis);
                continue;
            }
            int already = (gained >> (axis * NIBBLE_BITS)) & NIBBLE_MASK;
            int allowed = Math.min(change, capped - already);
            if (allowed <= 0) {
                continue;
            }
            axes[axis] = clamp(axes[axis] + allowed, axis);
            gained = (short) ((gained & ~(NIBBLE_MASK << (axis * NIBBLE_BITS)))
                    | ((already + allowed) << (axis * NIBBLE_BITS)));
        }

        byte newWarmth = (byte) axes[WARMTH];
        byte newPeak = (byte) Math.max(base.peakWarmth, newWarmth);

        return new Bond((byte) axes[TRUST], newWarmth, (byte) axes[FEAR],
                base.debt, day, gained, newPeak);
    }

    private static int clamp(int value, int axis) {
        return Math.max(FLOOR[axis], Math.min(CEILING, value));
    }

    /** The bottom of an axis. Exposed so a test asserts against this rather than restating it. */
    public static int floorOf(int axis) {
        return FLOOR[axis];
    }

    public static int ceilingOf() {
        return CEILING;
    }

    @Override
    public String toString() {
        return "Bond[trust=" + trust + " warmth=" + warmth + " fear=" + fear
                + " debt=" + debt + " lastSeenDay=" + lastSeenDay
                + " gainedToday=" + gainedToday(TRUST) + "/" + gainedToday(WARMTH)
                + "/" + gainedToday(FEAR)
                + " peakWarmth=" + peakWarmth + ']';
    }
}
