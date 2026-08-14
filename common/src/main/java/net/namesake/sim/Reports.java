package net.namesake.sim;

import net.namesake.culture.Culture;
import net.namesake.culture.Names;
import net.namesake.dialogue.Lines;
import net.namesake.dialogue.Pool;
import net.namesake.dialogue.Register;
import net.namesake.dialogue.Voice;
import net.namesake.npc.Persona;
import net.namesake.social.Bond;
import net.namesake.social.Deed;
import net.namesake.social.DeedType;
import net.namesake.social.DialogueStats;
import net.namesake.social.Memories;
import net.namesake.social.Personality;
import net.namesake.social.Residency;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * What a run says, in words. Session 07's three artefacts: <b>a readable chronicle</b>, <b>a
 * bond-earn-rate report</b>, and <b>a real deed ring</b>.
 *
 * <p><b>Pure, and that is why it is in {@code main} rather than in a test.</b> Every method here
 * takes values and returns lines. That makes the layout, the percentiles, the bucketing and the
 * arithmetic all unit-testable — which is the line {@code WORKPLAN.md} draws between its two
 * instruments, and the reason this session earns no new harness leg for its numbers. What only a
 * running game can show is that a hundred days completes without wedging a server, which is a claim
 * about the runner and is checked by {@code /namesake debug simulate} in a real client.
 *
 * <p><b>Every line is under {@code CommandLayoutTest}'s chat budget where a person will read it in
 * chat, and deliberately not where they will read it in a file.</b> This project has shipped a table
 * that wrapped mid-row three times — session 02's action bar, session 03's name grammars, session
 * 06's deed row — and each time it was found by looking at a screen, because every instrument in the
 * repo reads these commands out of a log file and a log file has no width. So the wide reports go to
 * a file and {@link #summary} is what goes to chat.
 *
 * <p><b>Never {@code String.format("%n")}.</b> It is the platform separator and emits {@code \r\n}
 * on Windows, which Minecraft draws as a missing-glyph box at the end of every row. Session 06
 * shipped that. Lines are returned as a list and joined by the caller with {@code \n}.
 */
public final class Reports {

    /** How wide the chat-facing rows are allowed to be. {@code CommandLayoutTest} holds it. */
    public static final int CHAT_WIDTH = 60;

    /** Widest {@link DeedType} name, plus a space. */
    private static final int TYPE_COLUMN = 17;

    private Reports() {
    }

    // --- the whole thing ---------------------------------------------------------------------------

    /** Every section, for the file. */
    public static List<String> full(Simulation.Outcome outcome) {
        List<String> lines = new ArrayList<>(conditions(outcome));
        lines.add("");
        lines.addAll(chronicle(outcome));
        lines.add("");
        lines.addAll(earnRate(outcome));
        lines.add("");
        lines.addAll(ringDump(outcome));
        lines.add("");
        lines.addAll(ringTruncation(outcome));
        lines.add("");
        lines.addAll(residencyThreshold(outcome));
        lines.add("");
        lines.addAll(dialogueSample(outcome.plan().cultureId()));
        return lines;
    }

    /**
     * <b>A page of what villagers actually say, so the lines can be read without playing.</b>
     *
     * <p>Session 09 ships a hundred and sixty authored sentences, and whether they are any good is
     * the owner's ruling and nothing in this repo can have an opinion about it. What this repo
     * <i>can</i> do is put them somewhere they can be read — and the memory of how this owner
     * engages is explicit: they said plainly that an abstract "does 32 feel right" question did not
     * mean anything to them, and that the concrete before-and-after is what they engage with. A
     * report is the cheapest before-and-after available.
     *
     * <p>Rendered rather than listed: the culture's opener and tag are applied, and the address slot
     * is filled both ways, because <b>the swap is the thing being judged</b>. Two cultures rather
     * than six, so it is a page rather than a chapter — this settlement's own, and the one furthest
     * from it in formality.
     */
    public static List<String> dialogueSample(byte cultureId) {
        Culture own = Culture.byId(cultureId);
        Culture other = own == Culture.MERIDIAN ? Culture.TALQIR : Culture.MERIDIAN;

        List<String> lines = new ArrayList<>();
        lines.add("--- what a villager actually says ---");
        lines.add("  160 authored lines: four pools x five registers x eight. Selected by bond state");
        lines.add("  and by what their ring holds; never generated. One line of each, from two");
        lines.add("  cultures, with the address slot filled both ways — the swap is the whole pitch.");

        for (Culture culture : List.of(own, other)) {
            Voice voice = Voice.of(culture);
            lines.add("");
            lines.add(String.format(Locale.ROOT,
                    "  %s — opens '%s', tags '%s', calls a stranger '%s', formality %.2f",
                    culture.displayName(), voice.opener(), voice.tag(), voice.strangerAddress(),
                    voice.formality()));
            for (Pool pool : Pool.values()) {
                lines.add("");
                for (Register register : Register.values()) {
                    // A fixed seed per cell, so two runs of one report read the same. The address is
                    // the stranger word before residency and a name after it, and both are shown on
                    // the same sentence wherever the line has a slot to put one in.
                    long seed = (long) pool.ordinal() * 31L + register.ordinal();
                    String template = Lines.at(pool, register, seed);
                    lines.add(String.format(Locale.ROOT, "    %-13s %s",
                            register, voice.inflect(
                                    template.replace(Lines.ADDRESS, voice.strangerAddress()),
                                    register, seed)));
                    if (template.contains(Lines.ADDRESS)) {
                        lines.add(String.format(Locale.ROOT, "    %-13s %s", "  ...resident",
                                voice.inflect(template.replace(Lines.ADDRESS, "Kymerailive"),
                                        register, seed)));
                    }
                }
                lines.add("    ^ " + pool);
            }
        }
        lines.add("");
        lines.add("  the stranger pool says it does not know you in so many words, in every line of");
        lines.add("  its greeting, about-you and about-others registers. Silence reads as permission.");
        return lines;
    }

    // --- session 08: how far one deed travels ------------------------------------------------------

    /**
     * <b>{@code WORKPLAN.md}'s exit criterion for session 08, as a table.</b>
     *
     * <p><i>A deed emitted in a settlement is held by 60%+ of its residents within two in-game days
     * at descending confidence, with at least one holder unable to name the actor.</i> That is a
     * claim about time and about a population, which is what session 07 built an instrument for — so
     * it is measured here rather than in the attach-bet harness, which is for what only a running
     * game can show.
     *
     * <p>One deed, deliberately. The standard plan emits one a day and the rows would be a hundred
     * overlapping stories; {@code PASSING_THROUGH} over three days emits exactly one, on day zero,
     * and then leaves the village alone to talk about it.
     *
     * <p><b>Two rows nobody asked for and both are the point.</b> The witness fraction sweep is here
     * because the criterion's 60% is partly paid by the people who were standing there — session 07
     * called that the least grounded input in the instrument, and a criterion that only clears at
     * 35% witnesses is a criterion that depends on a guess. And the gossip-off row is the control:
     * without step 7 the number is exactly the witnesses, which is the sentence this whole session
     * exists to stop being true.
     */
    public static List<String> propagation(Simulation.Plan plan) {
        Simulation.Plan one = plan.withDays(3).with(PlayerModel.PASSING_THROUGH);
        List<String> lines = new ArrayList<>();
        lines.add("--- how far one deed travels, and how well it survives the telling ---");
        lines.add("  one deed, emitted on day 0 in a settlement of " + one.residents()
                + ", then nobody visits.");
        lines.add(String.format(Locale.ROOT, "  a retelling keeps %.0f%% of the teller's confidence;"
                        + " below %d nobody can name the actor.",
                Deed.RETOLD * 100F, Deed.ATTRIBUTED));
        lines.add("");
        lines.add(String.format(Locale.ROOT, "  %-22s %7s %7s %7s %9s %9s",
                "witnesses / gossip", "day 0", "day 1", "day 2", "of village", "unnamed"));

        for (float fraction : new float[]{0F, 0.35F, 1F}) {
            lines.add(propagationRow(one.withWitnessFraction(fraction).withGossip(true),
                    Math.round(fraction * 100F) + "% seen it, told"));
        }
        lines.add(propagationRow(one.withWitnessFraction(Simulation.TYPICAL_WITNESS_FRACTION)
                .withGossip(false), "35% seen it, silent"));

        lines.add("");
        lines.add("  'unnamed' is holders who cannot say who did it, at the close of day 2.");
        lines.add("  the silent row is the control: with step 7 off, a deed reaches the people who");
        lines.add("  were standing there and stops. That is what this session exists to end.");
        lines.addAll(confidenceLadder(one.withGossip(true)));
        return lines;
    }

    /** One row of {@link #propagation}: a whole run, reduced to how far its one deed got. */
    private static String propagationRow(Simulation.Plan plan, String label) {
        Simulation.Outcome outcome = Simulation.run(plan);
        if (outcome.spread().isEmpty()) {
            return String.format(Locale.ROOT, "  %-22s %7s", label, "no deed");
        }
        Simulation.Spread story = outcome.spread().get(0);
        int last = plan.days() - 1;
        return String.format(Locale.ROOT, "  %-22s %7d %7d %7d %8.0f%% %9d",
                label, story.holders()[0], story.holders()[1], story.holders()[2],
                story.coverage(last, plan.residents()) * 100F, story.unattributed()[last]);
    }

    /**
     * What confidences a village actually ends up holding, which is the "descending" half.
     *
     * <p>Read off the rings rather than predicted from the constants, because the constants are what
     * is under test. Three values are reachable and a fourth would mean the hop bound has moved.
     */
    private static List<String> confidenceLadder(Simulation.Plan plan) {
        Simulation.Outcome outcome = Simulation.run(plan);
        Map<Integer, Integer> byConfidence = new java.util.TreeMap<>(java.util.Comparator.reverseOrder());
        for (Persona resident : outcome.residents()) {
            for (Deed deed : outcome.registry().memories().of(resident.id())) {
                byConfidence.merge((int) deed.confidence(), 1, Integer::sum);
            }
        }
        List<String> lines = new ArrayList<>();
        lines.add("");
        lines.add("  every copy the village holds, by confidence:");
        for (Map.Entry<Integer, Integer> entry : byConfidence.entrySet()) {
            lines.add(String.format(Locale.ROOT, "    %3d  %-24s %3d holder(s)",
                    entry.getKey(),
                    entry.getKey() >= Deed.ATTRIBUTED ? "names the actor" : "someone from the north",
                    entry.getValue()));
        }
        lines.add("  a fourth row here would mean the two-hop bound has moved. Nothing counts hops:");
        lines.add("  it falls out of the retention and the attribution floor, and GossipTest says so.");
        return lines;
    }

    /**
     * <b>The one question session 09 inherits, answered rather than handed over.</b>
     *
     * <p>Session 07 measured that no three residents ever reach 20 warmth in a hundred in-game days,
     * because a witness's share of a gift is one point and warmth decays one point a day — the two
     * cancel exactly. Gossip is plausibly the fix and testing it is nearly free: more villagers
     * holding a deed is more villagers with contact days, and contact days are what stop the decay
     * eating what was earned. So the same plan is run both ways and the two tables are printed side
     * by side.
     *
     * <p>Nothing is concluded here. The numbers go next to each other and the ledger is where the
     * reading goes, exactly as session 07 handled the finding that produced the question.
     */
    public static List<String> residencyWithAndWithoutGossip(Simulation.Plan plan) {
        List<String> lines = new ArrayList<>();
        lines.add("--- does gossip make the residency threshold reachable? ---");
        lines.add("  session 07: a witness's share of a gift is one point and warmth decays one a");
        lines.add("  day, so no three residents ever reach 20 warmth. Gossip gives more villagers");
        lines.add("  more contact days, and contact days are what stop the decay. Same plan, twice:");
        lines.add("  the in-game day the THIRD resident crossed each mark.");

        StringBuilder header = new StringBuilder("    axis     gossip  ");
        for (int mark : DialogueStats.LADDER) {
            header.append(String.format(Locale.ROOT, "%8d", mark));
        }
        lines.add(header.toString());

        for (boolean spreading : new boolean[]{false, true}) {
            Simulation.Outcome outcome = Simulation.run(plan.withGossip(spreading));
            String label = spreading ? "on " : "off";
            lines.add(comparisonRow(outcome, "warmth", label, Simulation.History::warmthByDay));
            lines.add(comparisonRow(outcome, "trust", label, Simulation.History::trustByDay));
        }
        lines.add("  'never' means no three residents reached it in " + plan.days() + " days.");
        return lines;
    }

    /** One row of {@link #residencyWithAndWithoutGossip}. */
    private static String comparisonRow(Simulation.Outcome outcome, String axis, String gossip,
                                        java.util.function.Function<Simulation.History, int[]> series) {
        StringBuilder row = new StringBuilder(String.format(Locale.ROOT, "    %-8s %-6s ", axis, gossip));
        for (int mark : DialogueStats.LADDER) {
            row.append(String.format(Locale.ROOT, "%8s", dayThirdCrossed(outcome, series, mark)));
        }
        return row.toString();
    }

    /** The first day three residents held {@code mark} at once, or {@code never}. */
    private static String dayThirdCrossed(Simulation.Outcome outcome,
                                          java.util.function.Function<Simulation.History, int[]> series,
                                          int mark) {
        for (int day = 0; day < outcome.plan().days(); day++) {
            int at = day;
            long held = outcome.histories().values().stream()
                    .filter(history -> series.apply(history)[at] >= mark)
                    .count();
            if (held >= 3) {
                return String.valueOf(day);
            }
        }
        return "never";
    }

    /**
     * <b>What session 09's residency threshold would actually cost, on each axis.</b>
     *
     * <p>{@code DESIGN.md} §5 grants residency at a <i>known</i> band with <b>three</b> residents,
     * and {@code Bond.trust} and {@code Bond.warmth} are the two exemptions that expire at the close
     * of session 09 — so this is the one report session 07 owes 09 directly rather than owing 12.
     *
     * <p>The two axes are printed side by side because they do not behave alike over a hundred days
     * and nothing before this could show it: warmth decays a point a day and trust does not, so a
     * threshold that reads warmth is a threshold only the people a player directly gives things to
     * can ever cross. Which axis residency reads is session 09's ruling; this is the table it is
     * ruled against.
     */
    public static List<String> residencyThreshold(Simulation.Outcome outcome) {
        List<String> lines = new ArrayList<>();
        lines.add("--- what a residency threshold would cost, per axis ---");
        lines.add("  DESIGN.md §5 grants residency on a known band with three residents, and");
        lines.add("  Bond.trust and Bond.warmth are the two exemptions that expire at session 09.");
        lines.add("  The in-game day the THIRD resident crossed each mark:");

        StringBuilder header = new StringBuilder("    mark    ");
        for (int mark : DialogueStats.LADDER) {
            header.append(String.format(Locale.ROOT, "%8d", mark));
        }
        lines.add(header.toString());
        lines.add(axisRow(outcome, "warmth", Simulation.History::warmthByDay));
        lines.add(axisRow(outcome, "trust", Simulation.History::trustByDay));
        lines.add("  'never' means no three residents reached it in " + outcome.plan().days()
                + " days at this model.");
        return lines;
    }

    /** One axis's row: the day a third resident first held each mark at once. */
    private static String axisRow(Simulation.Outcome outcome, String axis,
                                  java.util.function.Function<Simulation.History, int[]> series) {
        StringBuilder row = new StringBuilder(String.format(Locale.ROOT, "    %-8s", axis));
        for (int mark : DialogueStats.LADDER) {
            row.append(String.format(Locale.ROOT, "%8s", dayThirdCrossed(outcome, series, mark)));
        }
        return row.toString();
    }

    /**
     * The conditions the numbers were produced under.
     *
     * <p>Session 04's rule, applied to a different instrument: <i>a number without its conditions is
     * the same mistake in a different unit</i>. The witness fraction is called out as the weakest
     * input rather than listed beside the others as though it were as solid as the rest.
     */
    public static List<String> conditions(Simulation.Outcome outcome) {
        Simulation.Plan plan = outcome.plan();
        List<String> lines = new ArrayList<>();
        lines.add("=== namesake headless simulation ===");
        lines.add(String.format(Locale.ROOT,
                "  seed %d - %d in-game days - %d residents in %d households",
                plan.seed(), plan.days(), plan.residents(), plan.households()));
        lines.add(String.format(Locale.ROOT,
                "  %s, %s, defensibility %d",
                Culture.byId(plan.cultureId()).displayName(),
                outcome.settlement().specialtyValue(), plan.defensibility()));
        lines.add(String.format(Locale.ROOT,
                "  player   %-15s %s, focused on %d resident(s)",
                plan.model(), plan.model().note(), plan.focus()));
        lines.add(String.format(Locale.ROOT,
                "  witness  %.0f%% of the village sees a deed, capped at %d",
                plan.witnessFraction() * 100F, net.namesake.social.DeedBus.MAX_WITNESSES));
        lines.add("           this is the least grounded input here - see the sweep below");
        lines.add(String.format(Locale.ROOT,
                "  ran %d in-game days in %s", plan.days(), outcome.elapsed()));
        lines.add("  the record layer's only input from time is an integer day, so a hundred days");
        lines.add("  here is a hundred days exactly. What is modelled is the player and the witnesses.");
        return lines;
    }

    /** The chat-facing summary: nine lines, none of them wider than the chat budget. */
    public static List<String> summary(Simulation.Outcome outcome) {
        DialogueStats stats = statsOf(outcome);
        List<String> lines = new ArrayList<>();
        lines.add(String.format(Locale.ROOT, "%d days, %s, %d residents - ran in %s",
                outcome.plan().days(), outcome.plan().model(), outcome.plan().residents(),
                outcome.elapsed()));
        lines.add(String.format(Locale.ROOT, "  %d deeds, %d remembered, %d bond moves",
                outcome.chronicle().size(),
                outcome.chronicle().stream().mapToInt(Simulation.Moment::remembered).sum(),
                outcome.chronicle().stream().mapToInt(Simulation.Moment::bondsMoved).sum()));
        lines.add(String.format(Locale.ROOT, "  warmth  max %d  p50 %d  p05 %d  (of 100)",
                stats.observedMaximum(Bond.WARMTH), stats.percentile(Bond.WARMTH, 50),
                stats.percentile(Bond.WARMTH, 5)));
        lines.add(String.format(Locale.ROOT, "  trust   max %d  p50 %d  respect max %d",
                stats.observedMaximum(Bond.TRUST), stats.percentile(Bond.TRUST, 50),
                stats.observedMaximum(Bond.RESPECT)));
        float[] rates = stats.ratesPerContactDay();
        float best = rates.length == 0 ? 0F : rates[rates.length - 1];
        lines.add(String.format(Locale.ROOT, "  earn    %.2f warmth/contact day at best, %.2f median",
                best, rates.length == 0 ? 0F : rates[rates.length / 2]));
        lines.add(String.format(Locale.ROOT, "  rings   %d full of %d, %d%% of all slots used",
                stats.fullRings(), stats.rings().size(),
                Math.round(stats.ringOccupancy() * 100F)));
        stats.deepestRing().ifPresent(ring -> lines.add(String.format(Locale.ROOT,
                "  deepest %d/%d slots, reaching back %d days",
                ring.slots(), Memories.RING_CAPACITY, ring.reachDays())));
        lines.add(String.format(Locale.ROOT, "  spread  %d%% of the village held a deed by day 2",
                Math.round(medianCoverageByDayTwo(outcome) * 100F)));
        // The best rate rather than the median, because the median is routinely zero — a bystander
        // gains one warmth and the decay takes it back the next day — and a row of "never" tells
        // you nothing about how long the fastest relationship in the village takes.
        lines.add("  contact days to reach, at the fastest rate here:");
        StringBuilder ladder = new StringBuilder("   ");
        for (int target : DialogueStats.LADDER) {
            int days = best <= 0F ? -1 : (int) Math.ceil(target / best);
            ladder.append(String.format(Locale.ROOT, " %d:%s", target, days < 0 ? "never" : days));
        }
        lines.add(ladder.toString());
        return lines;
    }

    /**
     * The median story's coverage two days after it happened, across every deed in the run.
     *
     * <p>The median rather than the best, because the best is whichever deed the most people happened
     * to witness and says nothing about propagation. Stories emitted in the last two days of a run
     * are skipped: they have not had their two days yet, and counting them would drag the median down
     * with an answer to a different question.
     */
    static float medianCoverageByDayTwo(Simulation.Outcome outcome) {
        List<Float> coverage = new ArrayList<>();
        for (Simulation.Spread story : outcome.spread()) {
            int day = story.emittedOnDay() + 2;
            if (day >= outcome.plan().days()) {
                continue;
            }
            coverage.add(story.coverage(day, outcome.plan().residents()));
        }
        if (coverage.isEmpty()) {
            return 0F;
        }
        java.util.Collections.sort(coverage);
        return coverage.get(coverage.size() / 2);
    }

    // --- the chronicle -----------------------------------------------------------------------------

    /** In-game days to a week. Vanilla has no weeks; this is the chronicle's own grain. */
    private static final int WEEK = 7;

    /**
     * <b>What happened, in the order it happened, at a grain a person can read.</b>
     *
     * <p>A hundred days of one gift a day is a hundred nearly identical rows, and a hundred nearly
     * identical rows is a log rather than a chronicle — the difference being that somebody reads a
     * chronicle to find out <i>when things changed</i>. So this digests the quiet weeks and prints
     * the three kinds of day that are not quiet, in full and in place:
     *
     * <ul>
     *   <li>every day somebody crossed a mark on {@link DialogueStats#LADDER};</li>
     *   <li>every harmful deed, because there are few of them and each one is the interesting thing
     *       that week;</li>
     *   <li>the first week, which is the one a player actually experiences as the mod introducing
     *       itself, and where {@code DESIGN.md} §5's stranger pool lives.</li>
     * </ul>
     *
     * <p>{@link #chronicleInFull} is the every-deed version, for when a week is not fine enough.
     */
    public static List<String> chronicle(Simulation.Outcome outcome) {
        Map<Integer, List<String>> crossings = crossings(outcome);
        Map<Integer, List<Simulation.Moment>> byDay = new java.util.TreeMap<>();
        for (Simulation.Moment moment : outcome.chronicle()) {
            byDay.computeIfAbsent(moment.day(), key -> new ArrayList<>()).add(moment);
        }

        List<String> lines = new ArrayList<>();
        lines.add("--- chronicle ---");
        lines.add(String.format(Locale.ROOT, "  day   0  you arrive. %d residents; nobody has met you.",
                outcome.plan().residents()));

        int weekDeeds = 0;
        int weekWitnessed = 0;
        java.util.Set<UUID> weekSubjects = new java.util.LinkedHashSet<>();
        for (int day = 0; day < outcome.plan().days(); day++) {
            for (Simulation.Moment moment : byDay.getOrDefault(day, List.of())) {
                weekDeeds++;
                weekWitnessed += moment.witnesses();
                weekSubjects.add(moment.subject());
                boolean notable = moment.type().isHarmful() || day < WEEK;
                if (notable) {
                    lines.add(String.format(Locale.ROOT,
                            "  day %3d  %s %-16s  %d saw it, %d remembered, %d moved",
                            moment.day(), verbFor(moment.type()),
                            shortName(outcome, moment.subject()),
                            moment.witnesses(), moment.remembered(), moment.bondsMoved()));
                }
            }
            lines.addAll(crossings.getOrDefault(day, List.of()));

            if (day % WEEK == WEEK - 1 || day == outcome.plan().days() - 1) {
                if (weekDeeds > 0 && day >= WEEK) {
                    lines.add(String.format(Locale.ROOT,
                            "  wk %3d   %2d deed(s) to %d resident(s), %d witnessed; "
                                    + "warmth best %d, village %d",
                            day / WEEK, weekDeeds, weekSubjects.size(), weekWitnessed,
                            best(outcome, day), villageMedian(outcome, day)));
                }
                weekDeeds = 0;
                weekWitnessed = 0;
                weekSubjects.clear();
            }
        }
        return lines;
    }

    /** Every deed, one row each. The grain {@link #chronicle} digests away. */
    public static List<String> chronicleInFull(Simulation.Outcome outcome) {
        List<String> lines = new ArrayList<>();
        lines.add("--- chronicle, every deed ---");
        for (Simulation.Moment moment : outcome.chronicle()) {
            lines.add(String.format(Locale.ROOT,
                    "  day %3d  %s %-16s  %d saw it, %d remembered, %d moved",
                    moment.day(), verbFor(moment.type()), shortName(outcome, moment.subject()),
                    moment.witnesses(), moment.remembered(), moment.bondsMoved()));
        }
        return lines;
    }

    private static int best(Simulation.Outcome outcome, int day) {
        return outcome.histories().values().stream()
                .mapToInt(history -> history.warmthByDay()[day]).max().orElse(0);
    }

    private static int villageMedian(Simulation.Outcome outcome, int day) {
        int[] warmth = outcome.histories().values().stream()
                .mapToInt(history -> history.warmthByDay()[day]).sorted().toArray();
        return warmth.length == 0 ? 0 : warmth[warmth.length / 2];
    }

    /**
     * The day each resident first crossed each mark on {@link DialogueStats#LADDER}.
     *
     * <p>Read off the per-day warmth history rather than off the bond, because a bond only ever says
     * where somebody <i>is</i>. The crossings are what a person reads a chronicle to find, and
     * hunting for them in a hundred rows of gifts is what makes a log unreadable rather than long.
     */
    private static Map<Integer, List<String>> crossings(Simulation.Outcome outcome) {
        Map<Integer, List<String>> byDay = new java.util.TreeMap<>();
        for (Map.Entry<UUID, Simulation.History> entry : outcome.histories().entrySet()) {
            int[] warmth = entry.getValue().warmthByDay();
            int mark = 0;
            for (int day = 0; day < warmth.length && mark < DialogueStats.LADDER.length; day++) {
                while (mark < DialogueStats.LADDER.length && warmth[day] >= DialogueStats.LADDER[mark]) {
                    byDay.computeIfAbsent(day, key -> new ArrayList<>()).add(String.format(Locale.ROOT,
                            "  day %3d  * %s reaches %d warmth",
                            day, shortName(outcome, entry.getKey()), DialogueStats.LADDER[mark]));
                    mark++;
                }
            }
        }
        return byDay;
    }

    private static String verbFor(DeedType type) {
        return switch (type) {
            case FED_HUNGRY -> "you feed  ";
            case GIFT_WANTED -> "you give  ";
            case GIFT_UNWANTED -> "you offer ";
            case STRUCK_RESIDENT -> "YOU STRIKE";
            case KILLED_RESIDENT -> "YOU KILL  ";
            case DEFENDED_RAID -> "you defend";
        };
    }

    // --- the earn rate -----------------------------------------------------------------------------

    /**
     * <b>The report every band threshold from here is set from.</b>
     *
     * <p>The unit is ruled in {@link DialogueStats}: warmth per in-game day of contact, because the
     * daily cap, the decay and {@code lastSeenDay} are all per in-game day. The second column is the
     * same warmth per day <i>elapsed</i>, which is what the decay bites into and what a player who
     * visits fortnightly actually experiences.
     *
     * <p>The line that matters most is {@code observed maximum}. LNK set gates from 35 to 205 against
     * an observed maximum of 32 and nobody noticed for months. A threshold above this number is a
     * band nobody is ever in.
     */
    public static List<String> earnRate(Simulation.Outcome outcome) {
        DialogueStats stats = statsOf(outcome);
        List<String> lines = new ArrayList<>();
        lines.add("--- earn rate: warmth per in-game day of contact ---");
        lines.add(String.format(Locale.ROOT, "  %-20s %6s %6s %6s %6s %6s %8s %8s",
                "who", "warmth", "peak", "trust", "days", "span", "/contact", "/elapsed"));

        for (DialogueStats.Standing standing : stats.standings()) {
            Simulation.History history = outcome.histories().get(standing.holder());
            lines.add(String.format(Locale.ROOT, "  %-20s %6d %6d %6d %6d %6d %8.2f %8.2f",
                    clip(standing.name(), 20), standing.bond().warmth(),
                    history == null ? 0 : history.peakWarmth(), standing.bond().trust(),
                    standing.contactDays(), standing.spanDays(),
                    standing.perContactDay(), standing.perElapsedDay()));
        }

        lines.add("");
        lines.add(String.format(Locale.ROOT, "  %-18s %8s %8s %8s %8s",
                "", "warmth", "trust", "respect", "fear"));
        lines.add(String.format(Locale.ROOT, "  %-18s %8d %8d %8d %8d", "observed maximum",
                stats.observedMaximum(Bond.WARMTH), stats.observedMaximum(Bond.TRUST),
                stats.observedMaximum(Bond.RESPECT), stats.observedMaximum(Bond.FEAR)));
        lines.add(String.format(Locale.ROOT, "  %-18s %8d %8d %8d %8d", "observed median",
                stats.percentile(Bond.WARMTH, 50), stats.percentile(Bond.TRUST, 50),
                stats.percentile(Bond.RESPECT, 50), stats.percentile(Bond.FEAR, 50)));
        lines.add(String.format(Locale.ROOT, "  %-18s %8d %8d %8d %8d", "observed minimum",
                stats.observedMinimum(Bond.WARMTH), stats.observedMinimum(Bond.TRUST),
                stats.observedMinimum(Bond.RESPECT), stats.observedMinimum(Bond.FEAR)));
        lines.add("  a threshold above the maximum is a band nobody is ever in. LNK shipped five.");

        lines.addAll(whereTheWarmthGoes(outcome, stats));

        lines.add("");
        lines.add("  in-game days of contact to reach, at three observed rates:");
        float[] rates = stats.ratesPerContactDay();
        lines.add(ladderRow("target", null, stats));
        lines.add(ladderRow("best", rates.length == 0 ? 0F : rates[rates.length - 1], stats));
        lines.add(ladderRow("median", rates.length == 0 ? 0F : rates[rates.length / 2], stats));
        lines.add(ladderRow("worst", rates.length == 0 ? 0F : rates[0], stats));
        lines.add("  these are marks on a 0-100 axis, not bands. Session 12 owns the bands.");
        lines.add("  'never' is a real answer, and it is the LNK failure with the arithmetic done:");
        lines.add("  a threshold on a row that reads never is a band that villager never enters.");
        return lines;
    }

    /** One row of the days-to-reach table. A null rate prints the header instead. */
    private static String ladderRow(String label, Float rate, DialogueStats stats) {
        StringBuilder row = new StringBuilder(String.format(Locale.ROOT, "    %-8s", label));
        for (int target : DialogueStats.LADDER) {
            if (rate == null) {
                row.append(String.format(Locale.ROOT, "%8d", target));
                continue;
            }
            int days = rate <= 0F ? -1 : (int) Math.ceil(target / rate);
            row.append(String.format(Locale.ROOT, "%8s", days < 0 ? "never" : days));
        }
        return row.toString();
    }

    /**
     * <b>The diagnostic that makes the run's most important finding impossible to miss.</b>
     *
     * <p>Warmth is the only axis that decays — {@code DESIGN.md} §3, a point an in-game day toward
     * four tenths of its high-water mark — and a witness's share of a gift is small. Whether those
     * two numbers cancel is not something anybody has previously been able to ask, because nothing
     * in this repo could run a bond forward past a day. So it is computed here rather than argued:
     * how many residents witnessed something and hold no warmth for it at all.
     *
     * <p>Nothing is concluded. The two numbers are printed next to each other and the ledger is
     * where the reading goes.
     */
    private static List<String> whereTheWarmthGoes(Simulation.Outcome outcome, DialogueStats stats) {
        int met = 0;
        int everRose = 0;
        int holdNothing = 0;
        int highest = 0;
        int highestDay = -1;
        for (DialogueStats.Standing standing : stats.standings()) {
            if (standing.contactDays() == 0) {
                continue;
            }
            met++;
            Simulation.History history = outcome.histories().get(standing.holder());
            int peak = history == null ? 0 : history.peakWarmth();
            if (peak > 0) {
                everRose++;
            }
            if (standing.bond().warmth() == 0) {
                holdNothing++;
            }
            if (peak > highest) {
                highest = peak;
                highestDay = history == null ? -1 : history.peakDay();
            }
        }

        List<String> lines = new ArrayList<>();
        lines.add("");
        lines.add("  warmth is the only axis that decays: one point an in-game day, toward four");
        lines.add("  tenths of its high-water mark. So it accumulates only while one contact is");
        lines.add("  worth more than the gap since the last; trust has no such condition.");
        lines.add(String.format(Locale.ROOT,
                "    residents who met the player:                 %3d", met));
        lines.add(String.format(Locale.ROOT,
                "    ...whose warmth ever rose above zero:         %3d", everRose));
        lines.add(String.format(Locale.ROOT,
                "    ...who hold none of it after %3d days:        %3d",
                outcome.plan().days(), holdNothing));
        lines.add(String.format(Locale.ROOT,
                "    highest warmth anybody ever held:             %3d  (day %d)",
                highest, highestDay));
        lines.add(String.format(Locale.ROOT,
                "    trust, which does not decay:              p50 %3d, max %d",
                stats.percentile(Bond.TRUST, 50), stats.observedMaximum(Bond.TRUST)));
        return lines;
    }

    /**
     * How each player model earns, side by side.
     *
     * <p><b>Five rows rather than one, because one row under one unstated model is LNK's failure
     * exactly.</b> The saturating row is the ceiling — the fastest a bond can possibly move, since
     * the daily allowance is what stops it — and the passing-through row is the floor a first hour
     * actually looks like.
     */
    public static List<String> acrossModels(Simulation.Plan plan) {
        List<String> lines = new ArrayList<>();
        lines.add("--- earn rate by player model, " + plan.days() + " in-game days ---");
        lines.add(String.format(Locale.ROOT, "  %-16s %6s %7s %7s %7s %7s %7s %9s",
                "model", "deeds", "warmth", "w p50", "trust", "t p50", "rings", "resident"));
        for (PlayerModel model : PlayerModel.values()) {
            Simulation.Outcome outcome = Simulation.run(plan.with(model));
            DialogueStats stats = statsOf(outcome);
            int granted = residencyDay(outcome);
            lines.add(String.format(Locale.ROOT, "  %-16s %6d %7d %7d %7d %7d %7s %9s",
                    model, outcome.chronicle().size(),
                    stats.observedMaximum(Bond.WARMTH), stats.percentile(Bond.WARMTH, 50),
                    stats.observedMaximum(Bond.TRUST), stats.percentile(Bond.TRUST, 50),
                    stats.fullRings() + "/" + stats.rings().size(),
                    granted < 0 ? "never" : "day " + granted));
        }
        lines.add("  warmth and trust are the maximum any resident reached; p50 is the median of");
        lines.add("  everyone who met the player at all. SATURATING is the ceiling by construction:");
        lines.add("  it is what Bond.DAILY_CAP is measured against, so nothing can beat that row.");
        lines.addAll(residencyNote(plan));
        return lines;
    }

    /**
     * <b>The in-game day this player became a resident.</b> Session 09's column, and the one the
     * owner asked for by name: <i>which day each of the five player models crosses the threshold is a
     * claim about time, and that is the instrument session 07 built.</i>
     *
     * <p>Both of {@code DESIGN.md} §5's routes, evaluated as of each day rather than at the end:
     * {@link Residency#RESIDENTS_REQUIRED} residents at {@link Residency#TRUST_THRESHOLD} trust, or
     * one significant deed. The constants come out of {@link Residency} rather than being restated
     * here, so a threshold that moves moves this table with it.
     *
     * <p><b>One honest difference from what a live world computes, stated rather than buried.</b>
     * {@code Residency} counts the significant deeds a settlement <i>still remembers</i>; this counts
     * the ones that <i>happened</i>, from the chronicle. The two only diverge once a ring has evicted
     * one, which can make this column read a day or two early for a model that fills rings fast. The
     * band route has no such gap — trust does not decay and the histories are exact — and it is the
     * route the owner ruled.
     *
     * @return the day, or −1 for never
     */
    public static int residencyDay(Simulation.Outcome outcome) {
        java.util.Set<Long> feedings = new java.util.LinkedHashSet<>();
        boolean defended = false;
        int deedFrom = -1;

        for (int day = 0; day < outcome.plan().days(); day++) {
            int at = day;
            long atThreshold = outcome.histories().values().stream()
                    .filter(history -> history.trustByDay()[at] >= Residency.TRUST_THRESHOLD)
                    .count();
            if (atThreshold >= Residency.RESIDENTS_REQUIRED) {
                return day;
            }
            if (deedFrom >= 0) {
                return deedFrom;
            }
            for (Simulation.Moment moment : outcome.chronicle()) {
                if (moment.day() != day) {
                    continue;
                }
                if (moment.type() == DeedType.DEFENDED_RAID) {
                    defended = true;
                } else if (moment.type() == DeedType.FED_HUNGRY) {
                    feedings.add(moment.deedId());
                }
            }
            if (defended || feedings.size() >= Residency.FEEDINGS_REQUIRED) {
                deedFrom = day;
            }
        }
        return deedFrom;
    }

    private static List<String> residencyNote(Simulation.Plan plan) {
        List<String> lines = new ArrayList<>();
        lines.add("");
        lines.add(String.format(Locale.ROOT,
                "  'resident' is DESIGN.md §5: %d resident(s) at %d trust, or one significant deed",
                Residency.RESIDENTS_REQUIRED, Residency.TRUST_THRESHOLD));
        lines.add("  (a raid defended, or " + Residency.FEEDINGS_REQUIRED + " hungry people fed). "
                + "The band reads trust because");
        lines.add("  warmth is unreachable: session 07 measured it never, at every mark, and");
        lines.add("  session 08 measured gossip not fixing it. This is the day it flips, and it is");
        lines.add("  the day a villager stops calling this player stranger and uses their name.");
        lines.add("  The deed route is counted on deeds that happened rather than deeds still");
        lines.add("  remembered, so it can read a day early where a live world's rings have evicted.");
        return lines;
    }

    /**
     * How much the answer depends on the one input nobody has measured.
     *
     * <p>The witness fraction stands in for {@code DeedBus.witnesses} — one 48-block cube and a dozen
     * ray casts over whatever shape a village happens to be. Nobody has measured it, so rather than
     * pick a number and report one answer, this reports the answer across the whole range. A
     * threshold that survives 0% to 100% is a threshold that does not depend on the guess; one that
     * does not survive it is a threshold that has to wait for session 15's playtest.
     */
    public static List<String> witnessSensitivity(Simulation.Plan plan) {
        List<String> lines = new ArrayList<>();
        lines.add("--- sensitivity to the one input nobody has measured ---");
        lines.add(String.format(Locale.ROOT, "  %-10s %8s %8s %8s %8s",
                "witnesses", "max", "p50", "met you", "rings full"));
        for (float fraction : new float[]{0F, 0.25F, 0.35F, 0.5F, 0.75F, 1F}) {
            Simulation.Outcome outcome = Simulation.run(plan.withWitnessFraction(fraction));
            DialogueStats stats = statsOf(outcome);
            lines.add(String.format(Locale.ROOT, "  %-10s %8d %8d %8d %8d",
                    Math.round(fraction * 100F) + "%",
                    stats.observedMaximum(Bond.WARMTH), stats.percentile(Bond.WARMTH, 50),
                    stats.metTheViewer(), stats.fullRings()));
        }
        lines.add("  the subject's own column is unaffected by this; every other resident's is not.");
        return lines;
    }

    // --- the ring ----------------------------------------------------------------------------------

    /**
     * <b>A real deed ring after a hundred in-game days.</b>
     *
     * <p>The owner declined at the close of session 06 to rule on the ring's depth or on its eviction
     * policy against a two-entry harness fixture, and asked for this instead: the deepest and the
     * median ring in the settlement, the deed-type mix, and how many slots are actually in use. Two
     * rulings are parked on it and they are theirs to make. It is a report, not a mechanic.
     */
    public static List<String> ringDump(Simulation.Outcome outcome) {
        DialogueStats stats = statsOf(outcome);
        List<String> lines = new ArrayList<>();
        lines.add("--- the deed ring after " + outcome.plan().days() + " in-game days ---");
        lines.add(String.format(Locale.ROOT,
                "  %d of %d villagers remember anything; %d ring(s) are full at %d",
                stats.rings().size(), outcome.plan().residents(), stats.fullRings(),
                Memories.RING_CAPACITY));
        lines.add(String.format(Locale.ROOT,
                "  %d deeds held across the settlement, %d%% of its total ring capacity",
                stats.deedsHeld(), Math.round(stats.ringOccupancy() * 100F)));

        lines.add("");
        lines.add("  occupancy, slots used -> how many villagers:");
        StringBuilder occupancy = new StringBuilder("   ");
        for (Map.Entry<Integer, Integer> entry : stats.occupancyHistogram().entrySet()) {
            occupancy.append(String.format(Locale.ROOT, " %d:%d", entry.getKey(), entry.getValue()));
        }
        lines.add(occupancy.toString());

        lines.add("");
        lines.add("  the whole settlement's deed mix:");
        for (Map.Entry<DeedType, Integer> entry : stats.deedMix()) {
            lines.add(String.format(Locale.ROOT, "    %-17s %5d", entry.getKey(), entry.getValue()));
        }

        stats.deepestRing().ifPresent(ring -> lines.addAll(oneRing(outcome, "deepest", ring)));
        stats.medianRing().ifPresent(ring -> lines.addAll(oneRing(outcome, "median", ring)));
        return lines;
    }

    /** One villager's whole ring, printed the way {@code /namesake debug deeds} prints it. */
    private static List<String> oneRing(Simulation.Outcome outcome, String which,
                                        DialogueStats.Ring ring) {
        List<String> lines = new ArrayList<>();
        lines.add("");
        lines.add(String.format(Locale.ROOT,
                "  the %s ring - %s, %d/%d slots, days %d to %d (%d days of reach)",
                which, ring.name(), ring.slots(), Memories.RING_CAPACITY,
                ring.oldestDay(), ring.newestDay(), ring.reachDays()));

        // What the eviction ruling is actually about, and the one number a live save can never
        // produce: a deed pushed out of a ring leaves no trace of having been there. The
        // simulation kept the chronicle, so it can say how much of this villager's life they no
        // longer hold.
        long reached = outcome.chronicle().stream()
                .filter(moment -> moment.reached().contains(ring.holder()))
                .count();
        lines.add(String.format(Locale.ROOT,
                "    %d deed(s) happened in front of them; they hold %d and have forgotten %d",
                reached, ring.slots(), Math.max(0, reached - ring.slots())));
        StringBuilder mix = new StringBuilder("    mix:");
        for (Map.Entry<DeedType, Integer> entry : ring.byFrequency()) {
            mix.append(' ').append(entry.getKey()).append(' ').append(entry.getValue());
        }
        lines.add(mix.toString());

        Persona holder = outcome.resident(ring.holder());
        lines.add(String.format(Locale.ROOT,
                "    a wanted gift is worth x%.2f to them; their day is worth %d",
                Personality.scale(holder, DeedType.GIFT_WANTED), Personality.allowance(holder)));
        lines.add("    day   " + pad("deed", TYPE_COLUMN) + pad("times", 7) + "how");
        List<Memories.Slot> slots = outcome.registry().memories().slotsOf(ring.holder());
        for (int slot = slots.size() - 1; slot >= 0; slot--) {
            Deed deed = slots.get(slot).deed();
            lines.add(String.format(Locale.ROOT, "    %3d   %s%s%s",
                    deed.gameDay(), pad(deed.type().name(), TYPE_COLUMN),
                    // Session 09's repeat count, printed because it is the number the eviction
                    // policy reads and therefore the number that decides what this villager still
                    // holds. A ring that says "x1" everywhere is a ring nothing repeated in.
                    pad("x" + slots.get(slot).repeats(), 7),
                    holder.id().equals(deed.subject()) ? "to them" : "saw it"));
        }
        return lines;
    }

    /**
     * <b>What the ring costs an instrument that has to read it back.</b>
     *
     * <p>A hundred days of contact does not fit in thirty-two slots, so
     * {@code /namesake debug earnrate} on a real save can only measure the window the ring still
     * covers. The simulation knows the truth, so this is the only place the two can be put side by
     * side — and the gap between them is what a session 12 threshold set from a live save would be
     * wrong by.
     */
    public static List<String> ringTruncation(Simulation.Outcome outcome) {
        DialogueStats stats = statsOf(outcome);
        List<String> lines = new ArrayList<>();
        lines.add("--- what the ring hides from a live save ---");
        lines.add(String.format(Locale.ROOT, "  %-20s %10s %10s %10s",
                "who", "true days", "ring days", "rate error"));
        for (DialogueStats.Standing standing : stats.standings()) {
            Simulation.History history = outcome.histories().get(standing.holder());
            if (history == null || history.contactDays() == 0) {
                continue;
            }
            float trueRate = (float) standing.bond().warmth() / history.contactDays();
            float ringRate = standing.perContactDay();
            lines.add(String.format(Locale.ROOT, "  %-20s %10d %10d %9.0f%%",
                    clip(standing.name(), 20), history.contactDays(), standing.contactDays(),
                    trueRate == 0F ? 0F : (ringRate - trueRate) / trueRate * 100F));
        }
        lines.add("  a ring holds " + Memories.RING_CAPACITY + " deeds. Any playthrough longer than "
                + "that overstates its own earn rate,");
        lines.add("  because the warmth is cumulative and the days it is divided by are not.");
        return lines;
    }

    // --- helpers -----------------------------------------------------------------------------------

    /** The stats a report is read off. Same class the live world's {@code debug stats} uses. */
    public static DialogueStats statsOf(Simulation.Outcome outcome) {
        return DialogueStats.of(outcome.registry(), outcome.player(), outcome.plan().days() - 1);
    }

    private static String shortName(Simulation.Outcome outcome, UUID personaId) {
        Persona persona = outcome.resident(personaId);
        return clip(persona.isGenerated() ? Names.of(persona).given() : personaId.toString(), 16);
    }

    static String clip(String value, int width) {
        return value.length() <= width ? value : value.substring(0, width);
    }

    private static String pad(String value, int width) {
        return value.length() >= width ? value : value + " ".repeat(width - value.length());
    }
}
