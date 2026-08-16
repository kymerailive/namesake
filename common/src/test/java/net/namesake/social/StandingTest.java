package net.namesake.social;

import net.namesake.dialogue.Dialogue;
import net.namesake.dialogue.Pool;
import net.namesake.npc.NpcRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The five bands, their thresholds, the ladder they drive, and every absence branch.
 *
 * <p><b>Enumerated rather than sampled</b>, which is the instruction session 09 was given and the
 * reason session 07 shipped three over-wide absence branches: a guard that walks one fixture proves
 * something about that fixture. Every test here walks {@code Standing.values()} or the whole axis
 * range, so a sixth band or a moved threshold has to come and read this file.
 */
class StandingTest {

    private static final UUID PLAYER = new UUID(0x5EAF_0000_0000_0001L, 9);
    private static final UUID ANNA = new UUID(1, 1);

    private static Bond bond(int trust, int warmth) {
        return new Bond((byte) trust, (byte) warmth, (byte) 0, (short) 0, 0, (short) 0,
                (byte) warmth);
    }

    // --- the ladder ------------------------------------------------------------------------------

    /**
     * {@code WORKPLAN.md}'s five ruled multipliers, in order, against the five bands.
     *
     * <p>The values are the ledger's and are not re-derived here. What this session decided is which
     * axis selects between them; the numbers were ruled before it started.
     */
    @Test
    @DisplayName("the five ruled multipliers land on the five bands, worst to best")
    void theLadderIsTheRuledOne() {
        assertEquals(1.35F, Standing.RESENTED.priceMultiplier());
        assertEquals(1.15F, Standing.WARY.priceMultiplier());
        assertEquals(1.00F, Standing.NEUTRAL.priceMultiplier());
        assertEquals(0.90F, Standing.TRUSTED.priceMultiplier());
        assertEquals(0.75F, Standing.WARM.priceMultiplier());
        assertEquals(5, Standing.values().length, "five bands, and the brief says to resist a sixth");
    }

    @Test
    @DisplayName("the ladder is monotonic, so a better standing is never a worse price")
    void theLadderNeverGoesBackwards() {
        for (int i = 1; i < Standing.values().length; i++) {
            Standing worse = Standing.values()[i - 1];
            Standing better = Standing.values()[i];
            assertTrue(better.priceMultiplier() < worse.priceMultiplier(),
                    () -> better + " does not undercut " + worse + ", so the ladder has a flat step "
                            + "or a step backwards and a player climbing it would pay more");
        }
    }

    /**
     * <b>{@code DESIGN.md} §10 step 1, in the letter: <i>arrive at A, prices 1.00</i>.</b>
     *
     * <p>A stranger is {@link Standing#NEUTRAL} by construction — a bond that has never been
     * anything is all zeroes, which passes every test above the neutral band and fails every test
     * below it. So the acceptance script's opening price is not a special case anywhere in the code.
     */
    @Test
    @DisplayName("a bond that has never been anything is the standing price, exactly")
    void aStrangerPaysTheStandingPrice() {
        Standing standing = Standing.of(Bond.fresh(0));
        assertEquals(Standing.NEUTRAL, standing);
        assertEquals(1.00F, standing.priceMultiplier(), "DESIGN.md §10 step 1");
        assertFalse(standing.isAgainstYou());
        assertFalse(standing.teachesARecipe(), "a stranger is taught nothing");
    }

    // --- the thresholds --------------------------------------------------------------------------

    /**
     * <b>Every value of both axes the bands read, enumerated.</b>
     *
     * <p>Not three fixtures either side of each boundary: the whole range, both axes, so an
     * off-by-one anywhere in {@link Standing#of} is caught at the value it is off by rather than at
     * whichever value somebody happened to write down.
     */
    @Test
    @DisplayName("every trust value from the floor to the ceiling lands in exactly one band")
    void everyTrustValueIsBanded() {
        for (int trust = Bond.floorOf(Bond.TRUST); trust <= Bond.ceilingOf(); trust++) {
            Standing standing = Standing.of(bond(trust, 0));
            Standing expected = trust <= Standing.RESENTED_TRUST ? Standing.RESENTED
                    : trust < 0 ? Standing.WARY
                    : trust >= Standing.TRUSTED_TRUST ? Standing.TRUSTED
                    : Standing.NEUTRAL;
            int value = trust;
            assertEquals(expected, standing, () -> "trust " + value + " is in the wrong band");
        }
    }

    @Test
    @DisplayName("warmth at the mark is warm, and one below it is not")
    void warmthDecidesTheTopBand() {
        for (int warmth = 0; warmth <= Bond.ceilingOf(); warmth++) {
            Standing standing = Standing.of(bond(0, warmth));
            int value = warmth;
            assertEquals(warmth >= Standing.WARM_WARMTH ? Standing.WARM : Standing.NEUTRAL, standing,
                    () -> "warmth " + value + " is in the wrong band");
        }
    }

    /**
     * <b>Hostility beats warmth, which is session 09's ordering kept rather than re-decided.</b>
     *
     * <p>A villager you have been kind to and then struck is wary of you, not warm to you. Warmth
     * cannot express that on its own — it decays a point a day toward four tenths of its peak, so a
     * player who was generous last week is still warm today whatever they did this morning. Trust
     * going negative is what says "and then you did this".
     */
    @Test
    @DisplayName("a blow outranks a kindness: negative trust beats any amount of warmth")
    void hostilityOutranksWarmth() {
        assertEquals(Standing.WARY, Standing.of(bond(-1, 100)));
        assertEquals(Standing.RESENTED, Standing.of(bond(Standing.RESENTED_TRUST, 100)));
        assertTrue(Standing.of(bond(-1, 100)).isAgainstYou(),
                "DESIGN.md §10 step 6: prices rise for you");
    }

    /**
     * <b>{@code RESENTED} is where one witnessed killing puts a person, and the comparison is
     * {@code <=} for a reason.</b>
     *
     * <p>Measured at session 12 through the shipped record layer: a villager who watches you kill a
     * neighbour lands at −40 and the person you killed at −54, so a witness to a murder is twice
     * inside this band. Nothing else gets there quickly — one blow is −1 to −7 depending on the
     * weapon, and twenty bare-handed punches to one person reach −19.
     */
    @Test
    @DisplayName("the bottom band includes its own threshold, and one blow never reaches it")
    void theBottomBandIsWhereAKillingPutsYou() {
        assertEquals(Standing.RESENTED, Standing.of(bond(Standing.RESENTED_TRUST, 0)),
                "a villager beaten to exactly the mark is in the band the mark names");
        assertEquals(Standing.WARY, Standing.of(bond(Standing.RESENTED_TRUST + 1, 0)));

        // What a real killing costs its witnesses, through the shipped arithmetic rather than a
        // number copied out of a report: nominal severity, full witness share.
        int witnessed = Deeds.deltaFor(
                Deed.of(DeedType.KILLED_RESIDENT, PLAYER, new UUID(2, 2), 0, 1),
                net.namesake.npc.Persona.create(ANNA, 0L).placed(0, 0, (byte) 0))[Bond.TRUST];
        assertTrue(witnessed <= Standing.RESENTED_TRUST,
                () -> "one killing costs a witness " + witnessed + " trust, which does not reach the "
                        + "band the threshold was measured against");

        // And a single blow does not, at any severity the game can produce.
        for (float damage : new float[]{1F, 4F, 6F, 8F, 20F}) {
            int struck = Deeds.deltaFor(
                    Deed.of(DeedType.STRUCK_RESIDENT, PLAYER, ANNA, 0, 1)
                            .withSeverity(SocialEvents.severityOf(damage)),
                    net.namesake.npc.Persona.create(ANNA, 0L).placed(0, 0, (byte) 0))[Bond.TRUST];
            assertTrue(struck > Standing.RESENTED_TRUST,
                    () -> "one blow at " + damage + " damage is worth " + struck + " trust and would "
                            + "put its subject straight into the band a murder is for");
        }
    }

    /**
     * <b>{@code DESIGN.md} §5's sentence, as a property of the code.</b>
     *
     * <p><i>"Residency also grants the trusted price band."</i> {@link Standing#TRUSTED_TRUST} is
     * {@link Residency#TRUST_THRESHOLD} rather than a copy of its value, so the two cannot drift —
     * and this walks the composition rather than asserting the identity, because two constants that
     * are equal by assignment still need somebody to check they mean the same thing.
     */
    @Test
    @DisplayName("a village that has taken you in is a village whose residents give you the trusted price")
    void residencyGrantsTheTrustedBand() {
        NpcRegistry registry = new NpcRegistry();
        registry.putSettlement(new net.namesake.settlement.Settlement(0,
                net.minecraft.resources.ResourceLocation.withDefaultNamespace("overworld"),
                new net.minecraft.core.BlockPos(0, 64, 0),
                net.namesake.settlement.Specialty.FARMING.id(), (byte) 70,
                new byte[]{20, 20, 20, 20}));
        for (int i = 0; i < Residency.RESIDENTS_REQUIRED; i++) {
            UUID resident = new UUID(77, i);
            registry.put(net.namesake.npc.Persona.create(resident, 0L).placed(0, 0, (byte) 0));
            registry.putBond(resident, PLAYER, bond(Residency.TRUST_THRESHOLD, 0));
        }

        assertTrue(Residency.isResident(registry, 0, PLAYER, 0),
                "three residents at the threshold is DESIGN.md §5's band");
        for (int i = 0; i < Residency.RESIDENTS_REQUIRED; i++) {
            assertEquals(Standing.TRUSTED,
                    Standing.of(registry.bonds().at(new UUID(77, i), PLAYER, 0)),
                    "and each of them is the band §5 calls the trusted price band");
        }
        assertEquals(0.90F, Standing.TRUSTED.priceMultiplier());
    }

    // --- what the bands drive --------------------------------------------------------------------

    @Test
    @DisplayName("a recipe is taught by the two discount bands and by nothing below them")
    void onlyTheDiscountBandsTeach() {
        for (Standing band : Standing.values()) {
            assertEquals(band.priceMultiplier() < Standing.NEUTRAL.priceMultiplier(),
                    band.teachesARecipe(),
                    () -> band + " disagrees with its own price about whether it likes you");
        }
        assertFalse(Standing.NEUTRAL.teachesARecipe(),
                "having no opinion of somebody is not a reason to teach them your trade");
    }

    /**
     * <b>Session 11's promise, kept and checked: pool selection is behaviourally unchanged.</b>
     *
     * <p>The board and the villager agree because there is one implementation, and session 12
     * replaced that implementation. What this asserts is the stronger claim the ledger made — that
     * replacing it moved <i>nothing a player was already hearing</i>: {@code RESENTED} and
     * {@code WARY} are the two halves of session 09's {@code trust < 0}, {@code TRUSTED} sits inside
     * what was one undifferentiated {@code KNOWN}, and {@code WARM} keeps its own threshold.
     */
    @Test
    @DisplayName("every band maps to the pool session 09's four comparisons would have chosen")
    void poolSelectionIsUnchanged() {
        for (int trust = Bond.floorOf(Bond.TRUST); trust <= Bond.ceilingOf(); trust++) {
            for (int warmth = 0; warmth <= Bond.ceilingOf(); warmth += 5) {
                for (boolean remembers : new boolean[]{false, true}) {
                    Bond bond = bond(trust, warmth);
                    Pool sessionNine = trust < 0 ? Pool.HOSTILE
                            : warmth >= Standing.WARM_WARMTH ? Pool.WARM
                            : !bond.isNothing() || remembers ? Pool.KNOWN : Pool.STRANGER;
                    assertEquals(sessionNine, Dialogue.poolFor(bond, remembers),
                            () -> "trust " + bond.trust() + " warmth " + bond.warmth()
                                    + " remembers " + remembers + " changed pool at session 12");
                }
            }
        }
    }

    @Test
    @DisplayName("only the neutral band can be a stranger, and it takes the ring to say so")
    void onlyTheNeutralBandCanBeAStranger() {
        Set<Standing> canBeStranger = new LinkedHashSet<>();
        for (Standing band : Standing.values()) {
            Bond bond = switch (band) {
                case RESENTED -> bond(Standing.RESENTED_TRUST, 0);
                case WARY -> bond(-1, 0);
                case NEUTRAL -> Bond.fresh(0);
                case TRUSTED -> bond(Standing.TRUSTED_TRUST, 0);
                case WARM -> bond(0, Standing.WARM_WARMTH);
            };
            assertEquals(band, Standing.of(bond), "the fixture is in the band it claims");
            if (Dialogue.poolFor(bond, false) == Pool.STRANGER) {
                canBeStranger.add(band);
            }
        }
        assertEquals(Set.of(Standing.NEUTRAL), canBeStranger,
                "every band but the neutral one requires a bond somebody had to earn");

        // Session 06's finding, which is why poolFor takes a second argument at all: a villager whose
        // allowance was spent when you fed them holds an empty bond and remembers you perfectly well.
        assertEquals(Pool.KNOWN, Dialogue.poolFor(Bond.fresh(0), true));
    }

    // --- the axes it does not read ----------------------------------------------------------------

    /**
     * <b>The measurement that decided the session, as a test rather than as a note.</b>
     *
     * <p>{@code Bond.respect} was deleted at schema 8 and cannot be read here, so what is pinned is
     * the property that made it undeletable-in-name-only: <b>no deed in the table raises deference
     * without raising trust at least as fast.</b> That is what made a threshold on respect reachable
     * no later than the same threshold on trust in every state a player could be in, and it is the
     * claim a session 16 that re-adds the axis has to break on purpose.
     */
    @Test
    @DisplayName("no deed raises anything faster than the axis the band actually reads")
    void nothingOutrunsTrust() {
        for (DeedType type : DeedType.values()) {
            if (type.delta(Bond.TRUST) <= 0) {
                continue;
            }
            assertTrue(type.delta(Bond.FEAR) <= type.delta(Bond.TRUST),
                    () -> type + " raises fear faster than trust, so a band on fear would open "
                            + "before the one on trust and this session's whole argument moves");
        }
    }

    /**
     * <b>Fear is not read, and {@code DESIGN.md} §10 step 6 is why.</b>
     *
     * <p><i>Strike a resident before 4 witnesses. Prices rise for you.</i> Fear is written only by
     * violence, so a band that read it would make hitting people a route to <i>cheaper</i> goods.
     * The fixture is a bond carrying nothing but fear, which is the state a player who has only ever
     * been violent leaves behind.
     */
    @Test
    @DisplayName("being feared buys nothing at the counter")
    void fearIsNotADiscount() {
        for (int fear = 0; fear <= Bond.ceilingOf(); fear++) {
            Bond afraid = new Bond((byte) 0, (byte) 0, (byte) fear, (short) 0, 0, (short) 0, (byte) 0);
            assertEquals(Standing.NEUTRAL, Standing.of(afraid),
                    "fear alone must not move a price in either direction");
        }
    }

    // --- the report the thresholds came out of -----------------------------------------------------

    /**
     * <b>Every threshold in this class is inside what a player actually reaches.</b>
     *
     * <p>LNK set skill gates at 35–205 against an observed maximum affinity of 32; zero players ever
     * reached the lowest one and nobody noticed for months. {@code DialogueStats.observedMaximum} is
     * the question that failure is the absence of, and this asks it of a real run rather than of a
     * table: a hundred in-game days of one deed a day, through the shipped record layer.
     */
    @Test
    @DisplayName("both discount bands are reached by a hundred days of ordinary play")
    void theBandsArePopulated() {
        net.namesake.sim.Simulation.Outcome outcome = net.namesake.sim.Simulation.run(
                net.namesake.sim.Simulation.Plan.standard(20260815L, 100,
                        net.namesake.sim.PlayerModel.ATTENTIVE));
        DialogueStats stats = DialogueStats.of(outcome.registry(), outcome.player(), 99);

        assertTrue(stats.observedMaximum(Bond.TRUST) >= Standing.TRUSTED_TRUST,
                () -> "nobody reaches TRUSTED in a hundred days: observed maximum trust is "
                        + stats.observedMaximum(Bond.TRUST));
        assertTrue(stats.observedMaximum(Bond.WARMTH) >= Standing.WARM_WARMTH,
                () -> "nobody reaches WARM in a hundred days: observed maximum warmth is "
                        + stats.observedMaximum(Bond.WARMTH));

        // And both are still bands rather than states everybody is in. The median resident of a
        // village after a hundred days of daily kindness is not the best price in the shop.
        assertTrue(stats.percentile(Bond.WARMTH, 50) < Standing.WARM_WARMTH,
                "the top band must not be where a whole village sits");

        List<Standing> reached = outcome.residents().stream()
                .map(resident -> Standing.of(
                        outcome.registry().bonds().at(resident.id(), outcome.player(), 99)))
                .distinct()
                .toList();
        assertTrue(reached.contains(Standing.TRUSTED) || reached.contains(Standing.WARM),
                () -> "a hundred days of one deed a day reached only " + reached);
        assertNotEquals(1, reached.size(),
                () -> "a village that is all one band is not banded: " + reached);
    }

    /**
     * <b>{@code Standing.TRUSTED_TRUST} and {@code Residency.TRUST_THRESHOLD} are one number, tested
     * at the boundary rather than by comparing two constants.</b>
     *
     * <p>Session 13's breakage pass recorded that giving {@code TRUSTED_TRUST} its own literal
     * turned {@link #residencyGrantsTheTrustedBand} red. <b>Session 15's found that it no longer
     * does</b>, and the reason is this session's own change: that test seats three residents at
     * {@code Residency.TRUST_THRESHOLD} and asks whether each is {@code TRUSTED}. While both
     * constants were 20 any de-aliasing moved the band away from the seat. Now the threshold is 28,
     * and a de-aliased {@code TRUSTED_TRUST = 20} leaves 28 comfortably inside the band — <b>the
     * guard passes while the property it guards is gone.</b>
     *
     * <p>That is the exact failure this project exists to refuse, arriving in a guard rather than in
     * a mechanic, and it is why this asks a different question. A comparison of the two constants
     * would be worse than useless: both are compile-time constants, so javac folds
     * {@code assertEquals(A, B)} into {@code assertEquals(28, 28)} in the <i>test's</i> bytecode and
     * it proves nothing about the class under test — which is the same trap
     * {@code DayPlanTest}'s {@code SPREAD >= SPREAD_FLOOR} line sits in.
     *
     * <p>So it goes through {@link Standing#of} at runtime, at the boundary, in both directions.
     * Either constant moving away from the other puts one of these two on the wrong side.
     */
    @Test
    @DisplayName("the band begins exactly at the residency threshold, from below and from on it")
    void theBandBeginsAtTheResidencyThreshold() {
        assertNotEquals(Standing.TRUSTED, Standing.of(bond(Residency.TRUST_THRESHOLD - 1, 0)),
                "one point below the residency threshold must NOT be the trusted price band. If it "
                        + "is, Standing.TRUSTED_TRUST has drifted below Residency.TRUST_THRESHOLD "
                        + "and DESIGN.md §5's 'residency also grants the trusted price band' is a "
                        + "coincidence rather than a property.");
        assertEquals(Standing.TRUSTED, Standing.of(bond(Residency.TRUST_THRESHOLD, 0)),
                "the residency threshold itself must be the trusted price band. If it is not, "
                        + "TRUSTED_TRUST has drifted above it and a village can take you in while "
                        + "still charging you the stranger's price.");
    }

    /**
     * <b>Which bands a real player actually walks through, in order, day by day.</b>
     *
     * <p>Added at session 15, and it exists because {@link #theBandsArePopulated} above cannot see
     * the thing this session broke. That test asks whether each band is <i>reachable</i> and joins
     * its two halves with an {@code ||}, so a run in which <b>no villager is ever {@code TRUSTED}</b>
     * passes it comfortably. A band nobody passes through is {@code Bond.respect}'s failure wearing
     * a price tag, and the instrument that exists to catch that was looking one question to the left.
     *
     * <p>The question this asks instead is the ladder's own: <b>a villager's band over a hundred
     * days is a sequence, not a value.</b> {@link Standing#of} tests {@code warmth} before
     * {@code trust} — ruled at session 12, because when both cross on the same gift the better band
     * is the honest answer — so which rungs a player sees depends on the <i>gap</i> between
     * {@link Standing#TRUSTED_TRUST} and {@link Standing#WARM_WARMTH}, and those two stopped being
     * the same number at this session. That is not a defect and it is not free: it is the shape of
     * the ladder, and it belongs in a table rather than in somebody's head.
     *
     * <p>Reported rather than asserted for the table itself, for
     * {@code PersonalityDistributionTest}'s reason — the numbers are what a threshold is chosen
     * <i>against</i>, and an assertion on them would be this session grading its own homework. What
     * <b>is</b> asserted is the one claim a price ladder cannot survive losing: that every band with
     * a discount on it is somewhere a player actually stands.
     */
    @Test
    @DisplayName("the bands a hundred days of daily kindness walks through, in order")
    void theBandLadderIsWalked() {
        StringBuilder report = new StringBuilder("\n=== bands walked over a hundred in-game days ===\n")
                .append(String.format(java.util.Locale.ROOT,
                        "  TRUSTED_TRUST is %d, WARM_WARMTH is %d, and Standing.of tests warmth "
                                + "first%n", Standing.TRUSTED_TRUST, Standing.WARM_WARMTH))
                .append("  model              residents  bands walked\n");

        Set<Standing> everywhere = new LinkedHashSet<>();
        for (net.namesake.sim.PlayerModel model : net.namesake.sim.PlayerModel.values()) {
            net.namesake.sim.Simulation.Outcome outcome = net.namesake.sim.Simulation.run(
                    net.namesake.sim.Simulation.Plan.standard(20260815L, 100, model));

            java.util.Map<List<Standing>, Integer> walks = new java.util.LinkedHashMap<>();
            for (net.namesake.sim.Simulation.History history : outcome.histories().values()) {
                List<Standing> walk = new java.util.ArrayList<>();
                for (int day = 0; day < history.trustByDay().length; day++) {
                    // Standing.of reads two bytes and nothing else, so the day's sampled trust and
                    // warmth ARE the bond as far as banding is concerned. No decay to re-apply:
                    // the history is already what the record layer held at the close of that day.
                    Standing band = Standing.of(
                            bond(history.trustByDay()[day], history.warmthByDay()[day]));
                    if (walk.isEmpty() || walk.get(walk.size() - 1) != band) {
                        walk.add(band);
                    }
                }
                walks.merge(walk, 1, Integer::sum);
                everywhere.addAll(walk);
            }
            for (java.util.Map.Entry<List<Standing>, Integer> walk : walks.entrySet()) {
                report.append(String.format(java.util.Locale.ROOT, "  %-18s %9d  %s%n",
                        model.name(), walk.getValue(), walk.getKey()));
            }
        }
        report.append("  every band any villager stood in: ").append(everywhere).append('\n');
        System.out.println(report);

        for (Standing band : Standing.values()) {
            if (band == Standing.RESENTED || band == Standing.WARY) {
                // Both are earned by violence and no PlayerModel above strikes anybody except
                // CARELESS, which does so rarely. Their reachability is theBottomBandIsWhereA-
                // KillingPutsYou's job, off the deed table rather than off a hundred days.
                continue;
            }
            assertTrue(everywhere.contains(band), () ->
                    "no villager in any player model ever stands in " + band + " — it has a price "
                            + "multiplier of " + band.priceMultiplier() + " that nothing can reach. "
                            + "Bands walked: " + everywhere + ". TRUSTED_TRUST is "
                            + Standing.TRUSTED_TRUST + " and WARM_WARMTH is " + Standing.WARM_WARMTH
                            + "; Standing.of tests warmth first, so a TRUSTED_TRUST far above "
                            + "WARM_WARMTH makes the middle rung unreachable on the way up.");
        }
    }
}
