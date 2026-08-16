package net.namesake.social;

/**
 * <b>Where one person stands with one villager, as one of five named bands.</b> Session 12, and the
 * one place in the mod that turns a bond into an answer.
 *
 * <h2>What reads it, and what does not</h2>
 *
 * <p>{@code WORKPLAN.md} rules <b>exactly three consumers</b> and says to resist a fourth:
 *
 * <ol>
 *   <li>the <b>trade price multiplier</b> — {@link #priceMultiplier}, spent by {@link Trading};</li>
 *   <li><b>whether one recipe is taught</b> — {@link #teachesARecipe}, spent by {@link Teaching};</li>
 *   <li><b>which dialogue pool is selected</b> — {@code Dialogue.poolFor}, which is now a switch
 *       over this enum rather than four comparisons of its own.</li>
 * </ol>
 *
 * <p>The third is why session 11's Notice Board needed no edit to follow the bands: it asks
 * {@code Dialogue.poolFor}, the same call {@code Dialogue.speak} makes, so replacing one method
 * moved the board with it. That was session 11's whole argument for asking rather than inventing a
 * naming scheme, and it is a promise this session had to keep.
 *
 * <h2>Which axes it reads, and the measurement that decided it</h2>
 *
 * <p><b>Trust and warmth. Not respect, and not fear.</b> The rule 5 ledger had said since session 05
 * that {@code Bond.respect}'s exemption would be paid by "the trade price band", and the instrument
 * session 07 exists to prevent exactly this refused it:
 *
 * <table border="1">
 *   <caption>observed maximum respect, 100 in-game days, nine residents, seed 20260815</caption>
 *   <tr><th>ATTENTIVE</th><th>SATURATING</th><th>INTERMITTENT</th><th>PASSING_THROUGH</th>
 *       <th>CARELESS</th></tr>
 *   <tr><td>0</td><td>0</td><td>0</td><td>0</td><td><b>4</b></td></tr>
 * </table>
 *
 * <p>Four of five player models never write the axis at all, the fifth reaches <b>4</b> — on one
 * villager of nine, with the other eight on zero — and a witness sweep from nobody watching to
 * everybody watching does not move it by a point, because a strike's {@code +1} respect times a
 * witness's half share rounds to nothing. {@code DialogueStats.LADDER}'s lowest mark is <b>20</b>.
 * A band on that axis is LNK's failure with worse numbers: gates from 35 to 205 against an observed
 * maximum of 32, which is why {@code DialogueStats} exists at all.
 *
 * <p><b>And it is not a matter of the numbers being small — it is that they cannot be decisive.</b>
 * Respect has exactly two writers. {@code DEFENDED_RAID} gives {@code +12} respect and {@code +8}
 * trust, and both are held to the same per-axis daily allowance, so no raid ever raises respect
 * without raising trust beside it. {@code STRUCK_RESIDENT} gives {@code +1}, which is the only way
 * respect can rise while trust falls, and one is the value that a personality weight below neutral
 * rounds to zero. So a threshold on respect is reached no later than the same threshold on trust in
 * every case a player will ever be in.
 *
 * <p>Making more deeds write it was the offered escape and it is refused on design grounds rather
 * than on measurement: the respect column can be changed without perturbing a single number sessions
 * 07, 08 and 09 measured, because {@code Deeds.deltaFor} computes the four axes independently — but
 * <b>no deed in the table honestly earns deference except the raid defence that already does</b>.
 * Deference is not bought with bread. See the session 12 log.
 *
 * <h2>The five boundaries, and where each number comes from</h2>
 *
 * <table border="1">
 *   <caption>the ladder</caption>
 *   <tr><th>band</th><th>test</th><th>price</th><th>measured</th></tr>
 *   <tr><td>{@link #RESENTED}</td><td>trust ≤ −20</td><td>×1.35</td>
 *       <td>one killing puts every witness at −40 and the subject at −54; no single blow reaches
 *           −20 and twenty bare-handed punches reach −19</td></tr>
 *   <tr><td>{@link #WARY}</td><td>trust &lt; 0</td><td>×1.15</td>
 *       <td>session 09's hostile-pool boundary, unchanged</td></tr>
 *   <tr><td>{@link #NEUTRAL}</td><td>—</td><td>×1.00</td>
 *       <td>{@code DESIGN.md} §10 step 1 pins a stranger's price at 1.00</td></tr>
 *   <tr><td>{@link #TRUSTED}</td><td>trust ≥ 28</td><td>×0.90</td>
 *       <td>§5's residency mark, raised from 20 at session 15 and re-measured there: the first
 *           resident crosses on day 15, the third on day 41 — which is the day residency is
 *           granted — and all nine <b>never</b> do inside a hundred days</td></tr>
 *   <tr><td>{@link #WARM}</td><td>warmth ≥ 20</td><td>×0.75</td>
 *       <td>session 09's {@code WARM_WARMTH}, unchanged: 1–3 residents of nine at a hundred days,
 *           against a village median of 0–3</td></tr>
 * </table>
 *
 * <p><b>The two discounts are deliberately different in kind, and that is the whole design.</b>
 * {@link #TRUSTED} reads trust, which never decays, so a discount earned by turning up for a month
 * is kept. {@link #WARM} reads warmth, which falls a point an in-game day toward four tenths of its
 * high-water mark, so the best price in the village <b>lapses if you stop coming</b>. The discount
 * you keep is the one you earned by consistency; the discount you have to keep earning is the one
 * you get by being kind lately. That is the one thing warmth can express and trust cannot, which is
 * the argument session 09 chose warmth for the gift gate on, arriving in a price.
 *
 * <p><b>{@link #TRUSTED} is literally §5's "trusted price band".</b> Residency counts three
 * residents at {@link Residency#TRUST_THRESHOLD} trust; this band is one resident at it. So the
 * sentence <i>"residency also grants the trusted price band"</i> is true by construction rather than
 * by two thresholds that happen to agree, and moving one moves the other. <b>Session 15 moved it —
 * 20 to 28 — and this file is where that arrived without a single test going red</b>, which is why
 * the numbers in the table above are dates rather than values now.
 *
 * <h2>The two thresholds stopped being the same number, and it changes the ladder's shape</h2>
 *
 * <p>{@link #TRUSTED_TRUST} was 20 and {@link #WARM_WARMTH} is 20, so until session 15 the two
 * discounts sat on the same mark of two different axes. They are now eight points apart, and
 * {@link #of} tests warmth <i>first</i> — ruled at session 12, because when both cross on the same
 * gift the better band is the honest answer. So a player who fills their daily allowance every day
 * reaches {@code WARM} before they ever reach {@code TRUSTED}, and skips the middle rung.
 *
 * <p><b>Measured rather than reasoned, because the arithmetic predicted worse than the instrument
 * found.</b> On paper every gifting player skips {@code TRUSTED}; measured over the five player
 * models by {@code StandingTest.theBandLadderIsWalked}, only the one who <i>saturates</i> does.
 * A villager treated to one kindness a day still walks {@code NEUTRAL → TRUSTED} nine times in nine,
 * exactly as at 20; it is the twelve-gifts-a-day model that goes straight to {@code WARM}, seven of
 * nine where it was two of nine. That reads as the design rather than against it — <i>somebody
 * dumping a dozen gifts in an afternoon is buying warmth, and warmth is the discount that lapses.</i>
 * The one place it genuinely costs is the far end: the once-a-week player reached {@code TRUSTED} on
 * one villager of nine at 20 and reaches it on none at 28, which is what <i>"three in-game days to a
 * discount is too fast"</i> was asking to be made to cost.
 *
 * <h2>What it does not read, and why the list is short</h2>
 *
 * <p>Not {@code Bond.fear}: it is written only by violence, so reading it would mean a player who
 * hits people gets <i>cheaper</i> goods, and {@code DESIGN.md} §10 step 6 rules the opposite —
 * <i>prices rise for you</i>. Fear's consumer is §6's force resolution and its exemption stands at
 * session 16, untouched.
 *
 * <p>Not the deed ring: a band is a function of a bond alone, which is what makes it cheap enough to
 * ask on every interaction. The one thing the ring decides is which <i>pool</i> {@link #NEUTRAL}
 * maps to, and that is session 06's finding — a villager whose allowance was spent when you fed them
 * has an empty bond and remembers you perfectly well.
 */
public enum Standing {

    /**
     * They have watched you do something they will not forgive.
     *
     * <p><b>Measured, and the number is what one killing costs.</b> A villager who watches you kill
     * a neighbour lands at −40 and the person you killed at −54, so every witness to a murder is
     * twice inside this band the moment it happens. Nothing else reaches it quickly: one blow is
     * −1 to −7 depending on the weapon, twenty bare-handed punches to one person reach −19, and it
     * takes about eight full-strength blows to one villager before that villager is here.
     *
     * <p>The comparison is {@code ≤} rather than {@code <} deliberately. Two of the three witnesses
     * to a killing land exactly on −40 and one on −42; the subject on −54. A strict inequality
     * changes nothing at those values, but at the boundary itself — a villager beaten to precisely
     * −20 — it is the difference between a band that includes its own name and one that does not.
     */
    RESENTED(1.35F),

    /**
     * They have seen you do something. Trust below zero is an opinion, not an absence of one.
     *
     * <p>Session 09's hostile-pool boundary, unchanged and re-used rather than re-derived: a single
     * blow puts its subject here and keeps them here, because trust does not decay in either
     * direction. {@code DESIGN.md} §10 step 6 — <i>prices rise for you</i> — is this band.
     */
    WARY(1.15F),

    /**
     * Nothing has happened either way. The standing price, and the band a stranger is in.
     *
     * <p>{@code DESIGN.md} §10 step 1 pins it: <i>arrive at A, every villager speaks a stranger
     * line, prices 1.00</i>. It is also where a villager who has heard about you sits — a story
     * retold across a border is worth about one point of trust to its hearer, measured, so hearsay
     * changes what they <b>say</b> and not what they charge. See the session 12 log on
     * {@code DESIGN.md} §10 step 5's 0.95.
     */
    NEUTRAL(1.00F),

    /**
     * They would rely on you. {@code DESIGN.md} §5's <i>trusted price band</i>, at §5's own number.
     *
     * <p>Reached by consistency rather than intensity, because trust is the axis that does not
     * decay. <b>Re-measured at session 15, when the mark went from 20 to 28</b>, over one kindness a
     * day for a hundred in-game days: the first resident of nine crosses on <b>day 15</b>, the third
     * on <b>day 41</b> — which is the day residency is granted — and <b>all nine never do</b>.
     * At 20 the same run put the third on day 28 and the whole village here by day 100, so what the
     * raise bought is the far end of the curve rather than the near one.
     *
     * <p>A player doing one deed a week now gets <b>nobody</b> here in a hundred days, where at 20
     * they got one villager of nine. That is the sharpest single consequence of the change and it is
     * the one the owner asked for: turning up occasionally no longer earns a discount at all.
     */
    TRUSTED(0.90F),

    /**
     * They are glad to see you, <i>lately</i>. The best price in the village, and the only one that
     * can be lost by staying away.
     *
     * <p>Session 09's {@code Dialogue.WARM_WARMTH}, moved here unchanged, which is why every
     * dialogue and board test written against that boundary still holds. Measured, it is rare and
     * should be: warmth's median across a village is 0–3 against an observed maximum of 55–88, so
     * this is the handful of people a player actually gives things to.
     */
    WARM(0.75F);

    /**
     * How far below zero trust has to fall before a villager stops dealing with you fairly.
     *
     * <p>Not chosen: it is where one witnessed killing puts a person, halved. See {@link #RESENTED}.
     */
    public static final int RESENTED_TRUST = -20;

    /**
     * The trust {@link #TRUSTED} begins at — the number {@link Residency#TRUST_THRESHOLD} counts
     * three residents of.
     *
     * <p><b>It was {@code DialogueStats.LADDER}'s first mark until session 15 and is not on the
     * ladder any more.</b> That is deliberate and it is ruled in {@code Residency}: the ladder is
     * the <i>instrument's</i> scale, and a ruler that follows the thing it measures makes every
     * table in this ledger describe a different world from the one before it.
     * {@code Reports.dayTheThirdResidentCrossed} is what reads the threshold's own mark instead.
     *
     * <p><b>It is that constant rather than a copy of its value</b>, for session 11's reason one
     * layer down: {@code DESIGN.md} §5's sentence <i>"residency also grants the trusted price
     * band"</i> is only true while the two agree, and two constants that happen to be equal are not
     * the same constant. Residency counts three residents at this mark; this band is one resident at
     * it. Move §5's threshold and the price band moves with it, which is what makes the sentence a
     * property of the code rather than a coincidence somebody has to maintain.
     */
    public static final int TRUSTED_TRUST = Residency.TRUST_THRESHOLD;

    /**
     * The warmth {@link #WARM} begins at. <b>Session 09's number, moved rather than re-decided.</b>
     *
     * <p>It lived on {@code Dialogue} as two lines rather than a system precisely so that this
     * session could replace it cheaply, and replacing it turned out to mean moving it: the value is
     * unchanged, so pool selection is behaviourally identical to session 09's and every test written
     * against the old boundary passes without being touched.
     */
    public static final int WARM_WARMTH = 20;

    private final float priceMultiplier;

    Standing(float priceMultiplier) {
        this.priceMultiplier = priceMultiplier;
    }

    /**
     * Which band this bond puts somebody in.
     *
     * <p><b>Order matters and it is not the enum's order.</b> Hostility is tested before warmth,
     * which is session 09's ordering kept deliberately: a villager you have been kind to and then
     * struck is wary of you, not warm to you, and warmth cannot decay fast enough to express that on
     * its own. Then warmth before trust, because {@link #WARM} is the higher band.
     */
    public static Standing of(Bond bond) {
        if (bond.trust() <= RESENTED_TRUST) {
            return RESENTED;
        }
        if (bond.trust() < 0) {
            return WARY;
        }
        if (bond.warmth() >= WARM_WARMTH) {
            return WARM;
        }
        if (bond.trust() >= TRUSTED_TRUST) {
            return TRUSTED;
        }
        return NEUTRAL;
    }

    /**
     * What this villager multiplies a trade's cost by, for this player. Consumer one of three.
     *
     * <p>The five values are {@code WORKPLAN.md}'s, ruled before this session started and not
     * re-derived here. What this session had to decide is which axis selects between them, and
     * whether they replace vanilla's own reputation adjustment or add to it — see {@link Trading}.
     */
    public float priceMultiplier() {
        return priceMultiplier;
    }

    /**
     * Whether this villager will show you how they make things. Consumer two of three.
     *
     * <p>The two discount bands and nothing below them. A villager who merely has no opinion about
     * you does not teach you their trade, and one who is wary of you certainly does not.
     */
    public boolean teachesARecipe() {
        return ordinal() >= TRUSTED.ordinal();
    }

    /** True while this band charges more than the standing price. */
    public boolean isAgainstYou() {
        return priceMultiplier > NEUTRAL.priceMultiplier;
    }
}
