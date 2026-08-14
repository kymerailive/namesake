package net.namesake.sim;

/**
 * <b>What a player does, stated as an assumption rather than smuggled in as a constant.</b>
 *
 * <p>{@link Simulation} runs the shipped record layer forward a hundred in-game days. Everything
 * between a deed and the save file is production code; the two things the simulation has to
 * <i>invent</i> are who was standing close enough to see a deed, and this — how often somebody
 * plays, and what they do while they are there. So it is an enum with a note on every row, and the
 * report prints every row rather than one, because <b>a single number produced under a single
 * unstated model is exactly the shape of LNK's failure</b>: skill gates set at 35–205 against an
 * observed maximum of 32, because nothing had measured what players actually earned.
 *
 * <p><b>None of these is claimed to be right.</b> They bracket the space. If a real playtest lands
 * outside all five, the thresholds set from them are wrong and
 * {@code /namesake debug earnrate} — which computes the same numbers off a real save — is what says
 * so.
 */
public enum PlayerModel {

    /**
     * One kindness a day, every day, to the same handful of people. The player who has decided they
     * live here.
     */
    ATTENTIVE(1, 1, 0,
            "one deed a day, every day"),

    /**
     * Keeps going until nothing more lands. The grinder, and the reason
     * {@code Bond.DAILY_CAP} exists at all — this row is what the cap is measured against, so
     * whatever it produces is the <b>ceiling on how fast a bond can possibly move</b>.
     */
    SATURATING(1, 12, 0,
            "deeds until the day's allowance is spent, every day"),

    /** A visit every few days. What most of a long playthrough probably looks like. */
    INTERMITTENT(3, 2, 0,
            "a couple of deeds every third day"),

    /**
     * Somebody who passes through on the way somewhere. The row that matters most for
     * {@code DESIGN.md} §5's stranger pool: if this player never leaves the bottom band, the mod's
     * first hour is the stranger pool and nothing else.
     */
    PASSING_THROUGH(7, 1, 0,
            "one deed a week"),

    /**
     * Attentive, and occasionally violent. The only row that produces negative-axis data, which is
     * what session 12's hostile band has to be set from — and the only row where the asymmetry
     * between the capped positives and the uncapped negatives shows up over time.
     */
    CARELESS(1, 2, 8,
            "two deeds a day, and a blow every eighth visit");

    private final int visitEveryDays;
    private final int deedsPerVisit;
    private final int strikeEveryVisits;
    private final String note;

    PlayerModel(int visitEveryDays, int deedsPerVisit, int strikeEveryVisits, String note) {
        this.visitEveryDays = visitEveryDays;
        this.deedsPerVisit = deedsPerVisit;
        this.strikeEveryVisits = strikeEveryVisits;
        this.note = note;
    }

    /** One in-game day in every this many is a day the player is here. */
    public int visitEveryDays() {
        return visitEveryDays;
    }

    /**
     * How many deeds one visit produces.
     *
     * <p>Twelve is deliberately past any allowance the clamp permits — {@code Personality.MAX} times
     * {@code Bond.DAILY_CAP} is fourteen and a gift is worth at least one — so {@link #SATURATING}
     * stands for "kept going until it stopped working" rather than for a particular click count.
     */
    public int deedsPerVisit() {
        return deedsPerVisit;
    }

    /** Zero for a player who never hits anybody. Otherwise, one visit in this many carries a blow. */
    public int strikeEveryVisits() {
        return strikeEveryVisits;
    }

    public String note() {
        return note;
    }
}
