package net.namesake.command;

import net.namesake.culture.Culture;
import net.namesake.dialogue.Dialogue;
import net.namesake.dialogue.Lines;
import net.namesake.dialogue.Pool;
import net.namesake.dialogue.Register;
import net.namesake.dialogue.Voice;
import net.namesake.npc.NpcRegistry;
import net.namesake.npc.Persona;
import net.namesake.road.RoadEdge;
import net.namesake.road.RoadGraph;
import net.namesake.road.RoadProgress;
import net.namesake.sim.PlayerModel;
import net.namesake.sim.Reports;
import net.namesake.sim.Simulation;
import net.namesake.social.Bond;
import net.namesake.social.Deed;
import net.namesake.social.DeedType;
import net.namesake.social.DialogueStats;
import net.namesake.social.Memories;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.Map;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>A string nobody has measured against the space it has to sit in.</b>
 *
 * <p>This project has now shipped that defect three times. Session 02 put two full UUIDs into the
 * action bar, which does not wrap, so it clipped at both ends and was unreadable. Session 03
 * generated {@code "Hseingtsainhianng"} and had to make the grammar cap its own length. Session 06
 * printed a ninety-character deed row that wrapped in the middle of a table — and an owner's
 * screenshot of it is the only reason anybody noticed, because every instrument that reads these
 * commands reads them out of a log file with no width at all.
 *
 * <p>So the budget is measured here rather than hoped for. It is deliberately a <b>ratchet</b>
 * rather than a target: {@code deeds} is held to a real budget it comfortably meets, and
 * {@code bonds} is held to no worse than it is today, because nine columns of numbers beside a name
 * cannot fit any honest budget and the narrow view for one villager is {@code /namesake debug bond}.
 */
class CommandLayoutTest {

    /**
     * Roughly what vanilla chat shows before wrapping, at a common GUI scale.
     *
     * <p>Approximate by nature — chat is 320 scaled pixels of a variable-width font, so the real
     * number moves with the player's GUI scale and window. Sixty is what the owner's screenshots
     * wrapped at, and a budget that is roughly right beats no budget, which is what produced the
     * row this test was written for.
     */
    private static final int CHAT_WIDTH = 60;

    /** What {@code debug bonds} costs today. A ratchet: it may shrink, never grow. */
    private static final int BONDS_ROW_TODAY = 66;

    private static final UUID HOLDER = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final UUID ACTOR = UUID.fromString("0a0a0a0a-1111-2222-3333-444444444444");

    private static Persona holder() {
        return Persona.create(HOLDER, 0L).placed(3, 9, (byte) 1);
    }

    /**
     * The widest a deed row can be: the longest deed type, a saturated repeat count, an object with
     * a long name, and all three of the markers that only appear when a field is carrying
     * information.
     *
     * <p><b>Every one of those at once, including combinations the mod cannot currently emit.</b> A
     * repeat is first-hand by construction and an object only comes from a gift, which is always
     * nominal severity — so a row carrying a count, a confidence marker and a severity marker
     * together is unreachable today. Budgeting on that would be budgeting on an invariant a future
     * deed type could break without anybody noticing, which is why session 09 put the type, the
     * count and the object into one clipped cell: the row is the same width whatever is in it.
     */
    private static String widestRow() throws Exception {
        Deed worst = new Deed(DeedType.STRUCK_RESIDENT.id(), ACTOR, UUID.randomUUID(), 4, 12,
                (byte) 60, (byte) 70, "minecraft:enchanted_golden_apple");
        return describeRow(new Memories.Slot(worst.id(), worst, Memories.MAX_REPEATS), 112);
    }

    private static String describeRow(Memories.Slot slot, int today) throws Exception {
        Method describe = NamesakeCommands.class.getDeclaredMethod(
                "describeDeed", Memories.Slot.class, Persona.class, int.class);
        describe.setAccessible(true);
        return (String) describe.invoke(null, slot, holder(), today);
    }

    private static Memories.Slot once(Deed deed) {
        return new Memories.Slot(deed.id(), deed, 1);
    }

    @Test
    @DisplayName("the widest possible deed row fits the chat width")
    void aDeedRowFits() throws Exception {
        String row = widestRow();
        assertTrue(row.length() <= CHAT_WIDTH,
                () -> "a deed row is " + row.length() + " characters and the budget is "
                        + CHAT_WIDTH + ":\n" + row);
    }

    @Test
    @DisplayName("a nominal deed row carries no severity, confidence, settlement or count noise")
    void aNominalRowIsQuiet() throws Exception {
        Deed nominal = Deed.of(DeedType.FED_HUNGRY, ACTOR, HOLDER, 3, 12);
        String row = describeRow(once(nominal), 12);

        // Every deed anything currently emits is nominal severity, first-hand, and in the holder's
        // own settlement. A column that reads 100 on every row for two whole sessions is not
        // information, and it is what pushed the first version off the edge of the screen.
        assertFalse(row.contains("s100"), () -> "nominal severity must not print: " + row);
        assertFalse(row.contains("c100"), () -> "first-hand confidence must not print: " + row);
        assertFalse(row.contains("@s"), () -> "the holder's own settlement must not print: " + row);
        // Session 09's rule, applied to session 09's own new column: almost every memory happened
        // once, so "x1" on almost every row is the same noise session 06 stripped three columns for.
        assertFalse(row.contains("x1"), () -> "a memory that happened once must not print a count: " + row);
        assertTrue(row.contains("FED_HUNGRY"));
        assertTrue(row.contains("to them"), "the holder is the subject of this one");
    }

    /**
     * <b>Session 09's two new pieces of depth, on the row where a person actually reads them.</b>
     *
     * <p>The owner has asked for in-depth memories three times, and the two cheap routes are
     * <i>which object</i> and <i>how many times</i>. A ring that holds them and a command that does
     * not print them is the same as not holding them, from where the owner is sitting.
     */
    @Test
    @DisplayName("a memory that happened nine times, with bread, says both and still fits")
    void aRicherRowSaysWhatAndHowOften() throws Exception {
        Deed fed = Deed.of(DeedType.FED_HUNGRY, ACTOR, HOLDER, 3, 12, "minecraft:bread");
        String row = describeRow(new Memories.Slot(fed.id(), fed, 9), 12);

        assertTrue(row.contains("FED_HUNGRY"), () -> row);
        assertTrue(row.contains("x9"), () -> "nine times has to be on the row: " + row);
        assertTrue(row.contains("bread"), () -> "and what it was: " + row);
        assertFalse(row.contains("minecraft:"),
                () -> "the namespace is ten characters of nothing on every row: " + row);
        assertTrue(row.length() <= CHAT_WIDTH,
                () -> "a richer deed row is " + row.length() + " characters:\n" + row);
    }

    @Test
    @DisplayName("a deed carrying real information says so, and still fits")
    void anInformativeRowSaysWhatItIs() throws Exception {
        String row = widestRow();
        assertTrue(row.contains("s60"), () -> "a half-strength blow must show its severity: " + row);
        assertTrue(row.contains("c70"), () -> "second-hand must show its confidence: " + row);
        assertTrue(row.contains("@s4"), () -> "another village's business must say so: " + row);
        // "heard" replaces "saw it" rather than sitting beside it: a thing that happened to you is
        // never something you were told about, so the four states are exclusive and cost one column.
        assertTrue(row.contains("heard"), () -> "second-hand must read as heard, not saw it: " + row);
        assertFalse(row.contains("saw it"), () -> "and not both: " + row);
    }

    /**
     * <b>The owner's half of session 08's exit criterion, held where a unit test can hold it.</b>
     *
     * <p><i>{@code /namesake debug deeds} must show me the difference between a villager who saw it
     * and one who was told, on screen, in a running game.</i> The running-game half is the harness's;
     * this is the half that says the three rows are actually distinguishable from each other rather
     * than three renderings of the same thing.
     */
    @Test
    @DisplayName("seeing it, being told, and hearing a rumour are three visibly different rows")
    void theThreeWaysOfKnowingLookDifferent() throws Exception {
        Deed firstHand = Deed.of(DeedType.KILLED_RESIDENT, ACTOR, UUID.randomUUID(), 3, 12);
        Deed heard = firstHand.retold();
        Deed rumour = heard.retold();

        String saw = describeRow(once(firstHand), 12);
        String told = describeRow(once(heard), 12);
        String rumoured = describeRow(once(rumour), 12);

        assertTrue(saw.contains("saw it"), () -> saw);
        assertFalse(saw.contains(" c"), () -> "first-hand carries no confidence marker: " + saw);
        assertTrue(saw.contains(ACTOR.toString().substring(0, 8)), () -> "and it names you: " + saw);

        assertTrue(told.contains("heard"), () -> told);
        assertTrue(told.contains("c70"), () -> "and says how well they know it: " + told);
        assertTrue(told.contains(ACTOR.toString().substring(0, 8)), () -> "and still names you: " + told);

        assertTrue(rumoured.contains("rumour"), () -> rumoured);
        assertTrue(rumoured.contains("c49"), () -> rumoured);
        assertTrue(rumoured.contains("nobody"), () -> "and nobody can say who: " + rumoured);
        assertFalse(rumoured.contains(ACTOR.toString().substring(0, 8)),
                () -> "a blurred row must not leak the actor it blurred: " + rumoured);

        for (String row : List.of(saw, told, rumoured)) {
            assertTrue(row.length() <= CHAT_WIDTH,
                    () -> "a deed row is " + row.length() + " characters:\n" + row);
        }
    }

    /**
     * The bug the owner's screenshot actually showed, and the reason it is worth a test of its own.
     *
     * <p>{@code String.format("%n")} is the <i>platform</i> line separator, so on Windows it emits
     * {@code \r\n}. Minecraft's font has no glyph for a carriage return and draws it as a missing
     * character box, so every row ended in a small square — invisible in the log, invisible in
     * every test that reads the string, and perfectly obvious the moment somebody looked at the
     * screen. Nothing in this file may use {@code %n}.
     */
    @Test
    @DisplayName("no command output contains a carriage return")
    void nothingEmitsACarriageReturn() throws Exception {
        assertFalse(widestRow().contains("\r"),
                "Minecraft draws a carriage return as a missing-glyph box. Use \\n, never %n.");
    }

    @Test
    @DisplayName("the deed column is wide enough for the longest deed type")
    void theDeedColumnFitsEveryType() {
        for (DeedType type : DeedType.values()) {
            assertTrue(type.name().length() < NamesakeCommands.DEED_COLUMN,
                    () -> type + " is " + type.name().length() + " characters and the column is "
                            + NamesakeCommands.DEED_COLUMN + ", so the row would not align");
        }
    }

    /**
     * {@code debug bonds} is a ratchet rather than a budget, and the number is stated rather than
     * derived so that widening it is a decision somebody makes.
     *
     * <p>Session 06 added a {@code mem} column to a table that was already over the chat width, and
     * the honest fix was not to squeeze it — nine columns beside a 27-character name cannot fit —
     * but to stop paying for padding no village uses. The name column now measures the report
     * rather than the worst case, which is what took it back under where it was.
     */
    /**
     * The bonds table is a ratchet rather than a budget, and the width it is measured at comes out
     * of the code rather than out of this file.
     *
     * <p>The first version of this test computed the row width from a formula written here, which
     * made it a test of its own arithmetic: reverting the fix in {@code NamesakeCommands} would not
     * have turned it red. That is the tautological test {@code CLAUDE.md} hard rule 3 names, and it
     * is worse than no test. It now calls {@link NamesakeCommands#nameColumnFor} for real.
     */
    @Test
    @DisplayName("the bonds table has not grown wider than it already was")
    void theBondsTableDoesNotGrow() {
        int nameColumn = NamesakeCommands.nameColumnFor(village());
        int row = 2 + nameColumn + BONDS_COLUMNS.length();
        assertTrue(row <= BONDS_ROW_TODAY,
                () -> "a bonds row is " + row + " characters, over the " + BONDS_ROW_TODAY
                        + "-character ratchet. Nine columns is already wide; take one away rather "
                        + "than raising this. /namesake debug bond is the narrow view.");
        assertTrue(BONDS_ROW_TODAY > CHAT_WIDTH,
                "if this table ever fits the chat width, delete the ratchet and use the budget");
    }

    /**
     * The fix that bought the width back: measure the report, not the worst case a generator can
     * produce.
     *
     * <p>Session 03's budget allows a 27-character full name, and the table was padding every row
     * to 28 to allow for one. A village of nineteen-character names was paying nine characters a
     * row for nobody.
     */
    @Test
    @DisplayName("the name column measures the villagers present, not the longest name possible")
    void theNameColumnMeasuresTheReport() {
        int forThisVillage = NamesakeCommands.nameColumnFor(village());
        assertTrue(forThisVillage < 28,
                () -> "padding to " + forThisVillage + " means the column is still sized for a name "
                        + "nobody in this report has");
        assertTrue(forThisVillage > 3, "and it still has to fit the names that are here");

        // An empty report must not produce a zero-width column that swallows the header.
        assertTrue(NamesakeCommands.nameColumnFor(List.of()) >= 4,
                "a report with nobody in it still has a 'who' header to lay out");
    }

    /** Everything to the right of the name in a bonds row, as {@code dumpBonds} lays it out. */
    private static final String BONDS_COLUMNS = "trust warmth respect fear   cap   gift×  mem";

    /** Six villagers with names the length a real generated village actually produces. */
    private static List<Persona> village() {
        return java.util.stream.IntStream.range(0, 6)
                .mapToObj(i -> Persona.create(new UUID(i, i), 0L).placed(0, i / 3, (byte) 1))
                .toList();
    }

    @Test
    @DisplayName("the ring header states the capacity, so 128 is never a number in prose")
    void theRingCapacityIsRead() {
        assertEquals(128, Memories.RING_CAPACITY);
    }

    // --- session 07's three commands ---------------------------------------------------------------

    /**
     * A settlement with more people and more days than any playtest will produce, so the columns are
     * measured at the width real numbers reach rather than at the width a two-villager fixture does.
     *
     * <p>That distinction is the whole reason this file exists. Session 06's deed row passed every
     * test in the repo and wrapped in a running game, because the tests measured fixtures and the
     * game measured a village.
     */
    private static Simulation.Outcome atScale() {
        Simulation.Plan plan = new Simulation.Plan(20260814L, 200, 40, 8,
                net.namesake.culture.Culture.KARSK.id(),
                net.namesake.settlement.Specialty.MASONRY.id(), (byte) 40,
                PlayerModel.SATURATING, 3, 0.9F, true, 1);
        return Simulation.run(plan);
    }

    private static DialogueStats statsAtScale(Simulation.Outcome outcome) {
        return DialogueStats.of(outcome.registry(), outcome.player(), outcome.plan().days() - 1);
    }

    /**
     * <b>Every state both commands can be in, not the one a fixture happens to produce.</b>
     *
     * <p>The first version of this measured a populated table and nothing else, and it was green
     * while three lines were over the budget — all three of them in the <i>absence</i> branches,
     * which is the state a real player meets first: walk into a village that has never seen you and
     * `debug earnrate` answers with a 68-character apology. The owner found it in ten seconds by
     * running the command in a world nobody had done anything in.
     *
     * <p>That is the fifth time this project has shipped a string nobody measured against the space
     * it has to sit in, and the third time in two sessions that the guard existed and the fixture
     * never reached it. So the guard now enumerates the states rather than sampling one: a populated
     * world, a world nobody has met you in, and the console with no viewer at all.
     */
    private static List<Map.Entry<String, List<String>>> everyStateOfBothCommands() {
        Simulation.Outcome outcome = atScale();
        NpcRegistry empty = new NpcRegistry();
        UUID viewer = UUID.fromString("0a0a0a0a-1111-2222-3333-444444444444");
        DialogueStats none = DialogueStats.of(empty, viewer, 0);
        DialogueStats console = DialogueStats.of(empty, null, 0);

        // A village that exists and has met nobody — the state a player is in for their first hour,
        // and the one that was never measured.
        NpcRegistry unmet = new NpcRegistry();
        for (Persona resident : outcome.residents()) {
            unmet.put(resident);
        }
        DialogueStats strangers = DialogueStats.of(unmet, viewer, 40);

        // Session 08's rows, in the four states a ring can be in. The last two are the ones that
        // did not exist before this session: a villager who was told, and a villager who was told
        // by somebody who had already forgotten who did it.
        Persona holder = outcome.residents().get(0);
        Deed firstHand = Deed.of(DeedType.KILLED_RESIDENT, viewer, holder.id(),
                holder.settlementId(), 198);
        Deed heard = firstHand.retold();
        Deed rumour = heard.retold();

        return List.of(
                Map.entry("stats, populated", NamesakeCommands.statRows(
                        statsAtScale(outcome), outcome.registry(), outcome.player())),
                Map.entry("stats, a village that has not met you",
                        NamesakeCommands.statRows(strangers, unmet, viewer)),
                Map.entry("stats, an empty world", NamesakeCommands.statRows(none, empty, viewer)),
                Map.entry("stats, no viewer", NamesakeCommands.statRows(console, empty, null)),
                Map.entry("earnrate, populated", NamesakeCommands.earnRateRows(
                        statsAtScale(outcome), outcome.player())),
                Map.entry("earnrate, a village that has not met you",
                        NamesakeCommands.earnRateRows(strangers, viewer)),
                Map.entry("earnrate, an empty world", NamesakeCommands.earnRateRows(none, viewer)),
                Map.entry("earnrate, no viewer", NamesakeCommands.earnRateRows(console, null)),
                Map.entry("deeds, a full ring", NamesakeCommands.deedRows(holder,
                        outcome.registry().memories().slotsOf(holder.id()), outcome.plan().days() - 1)),
                Map.entry("deeds, a villager who has seen nothing",
                        NamesakeCommands.deedRows(holder, List.of(), 0)),
                Map.entry("deeds, a story they were told", NamesakeCommands.deedRows(
                        holder, List.of(once(firstHand), once(heard)), 200)),
                Map.entry("deeds, a story nobody can attribute", NamesakeCommands.deedRows(
                        holder, List.of(once(rumour)), 200)),
                // Session 09's two new pieces of depth, in the states that carry them.
                Map.entry("deeds, an afternoon that happened nine times",
                        NamesakeCommands.deedRows(holder, List.of(new Memories.Slot(firstHand.id(),
                                firstHand, 9)), 200)),
                Map.entry("deeds, a gift with an object on it", NamesakeCommands.deedRows(holder,
                        List.of(once(Deed.of(DeedType.GIFT_WANTED, viewer, holder.id(),
                                holder.settlementId(), 198, "minecraft:enchanted_golden_apple"))),
                        200)),
                // Session 10's command, in the four states it has. The first two are absences and
                // are the ones this guard exists for: session 07 shipped three over-width absence
                // branches past a version of it that measured a populated fixture.
                Map.entry("roads, a world with no settlements at all",
                        NamesakeCommands.roadRows(0, RoadGraph.EMPTY, true, List.of(), List.of())),
                Map.entry("roads, one village and nobody to be a neighbour of",
                        NamesakeCommands.roadRows(1, RoadGraph.EMPTY, true, List.of(), List.of())),
                Map.entry("roads, a network with nothing laid yet",
                        NamesakeCommands.roadRows(9, roadNetwork(), true, List.of(), List.of())),
                Map.entry("roads, block laying switched off",
                        NamesakeCommands.roadRows(9, roadNetwork(), false, List.of(), List.of())),
                Map.entry("roads, a network half built", NamesakeCommands.roadRows(9, roadNetwork(),
                        true,
                        List.of(new RoadProgress(new RoadEdge(1998, 1999), 9999, 9999, 999, 99,
                                        9.9, true),
                                RoadProgress.unbuilt(new RoadEdge(1998, 1999), true, 9.9),
                                RoadProgress.unbuilt(new RoadEdge(1998, 1999), false, 0)),
                        List.of(new RoadEdge(1998, 1999)))));
    }

    /**
     * A graph wide enough that the adjacency rows are measured at a width a real world reaches.
     *
     * <p>A ring of nine villages: every one of them is a neighbour of two others, which is the row
     * shape, and the ids run into four digits because a long playthrough registers hundreds of
     * settlements and the column has to still be a column then.
     */
    private static RoadGraph roadNetwork() {
        List<net.namesake.settlement.Settlement> ring = new java.util.ArrayList<>();
        for (int i = 0; i < 9; i++) {
            double angle = Math.PI * 2 * i / 9;
            ring.add(new net.namesake.settlement.Settlement(1990 + i,
                    net.minecraft.resources.ResourceLocation.withDefaultNamespace("overworld"),
                    new net.minecraft.core.BlockPos((int) (Math.cos(angle) * 4000), 64,
                            (int) (Math.sin(angle) * 4000)),
                    net.namesake.settlement.Specialty.FARMING.id(), (byte) 50,
                    new byte[]{0, 0, 0, 0}));
        }
        return RoadGraph.of(ring);
    }

    /**
     * <b>Every line a villager can say, in every state it can be said in.</b>
     *
     * <p>{@code WORKPLAN.md}'s instruction for session 09 was to add these rows to the guard that
     * already enumerates rather than to write a fifth guard that samples one. Four pools × five
     * registers × six cultures × two residency states × eight lines is 1,920 rendered sentences, and
     * every one of them has to sit inside the chat width with a culture's opener in front of it, its
     * tag question behind it, and a sixteen-character player name in the middle.
     *
     * <p>That is not paranoia about arithmetic. <b>This project has shipped a string nobody measured
     * against the space it has to sit in six times</b> — session 02's action bar, session 03's name
     * grammars, session 06's deed row, session 07's three absence branches, session 08's ring header
     * — and five of the six were found by a person looking at a screen rather than by a test.
     */
    private static List<Map.Entry<String, List<String>>> everyLineAVillagerCanSay() {
        List<Map.Entry<String, List<String>>> states = new java.util.ArrayList<>();
        NpcRegistry registry = new NpcRegistry();
        for (Pool pool : Pool.values()) {
            for (Register register : Register.values()) {
                List<String> rendered = new java.util.ArrayList<>();
                for (Culture culture : Culture.values()) {
                    Voice voice = Voice.of(culture);
                    Persona speaker = Persona.create(new UUID(culture.id(), 7L), 0L)
                            .placed(0, 4096, culture.id());
                    for (String address : List.of(voice.strangerAddress(), LONGEST_PLAYER_NAME)) {
                        for (String template : Lines.of(pool, register)) {
                            String line = template.replace(Lines.ADDRESS, address);
                            // Both coins forced on: the opener attached and the tag hung, which is
                            // the widest a culture can make any sentence.
                            String widest = voice.opener() + " " + (line.endsWith(".")
                                    ? line.substring(0, line.length() - 1) + ", " + voice.tag()
                                    : line);
                            rendered.addAll(Dialogue.rows(speaker,
                                    new Dialogue.Spoken(pool, register, address, widest)));
                        }
                    }
                }
                states.add(Map.entry("dialogue, " + pool + " " + register, rendered));
            }
        }
        // And the real selector, so the rows above are not the only thing measured: what the engine
        // actually produces for a villager who has met nobody.
        Persona stranger = Persona.create(new UUID(11L, 11L), 0L).placed(0, 4096, Culture.VALE.id());
        registry.put(stranger);
        states.add(Map.entry("dialogue, as the selector actually renders it",
                Dialogue.rows(stranger, Dialogue.speak(registry, stranger,
                        UUID.fromString("0a0a0a0a-1111-2222-3333-444444444444"),
                        LONGEST_PLAYER_NAME, 0, 0, 20260815L))));
        return states;
    }

    /** Sixteen characters, which is vanilla's cap and {@code GreetVerb}'s budget. */
    private static final String LONGEST_PLAYER_NAME = "Kymerailive_1234";

    @Test
    @DisplayName("every row of both commands fits the chat width, in every state they can be in")
    void bothCommandsFitInEveryState() {
        List<Map.Entry<String, List<String>>> states =
                new java.util.ArrayList<>(everyStateOfBothCommands());
        states.addAll(everyLineAVillagerCanSay());

        for (Map.Entry<String, List<String>> state : states) {
            assertFalse(state.getValue().isEmpty(),
                    () -> state.getKey() + " printed nothing at all, which reads as a broken command");
            for (String row : state.getValue()) {
                assertTrue(row.length() <= CHAT_WIDTH,
                        () -> "a row of '" + state.getKey() + "' is " + row.length()
                                + " characters, over the " + CHAT_WIDTH + "-character budget:\n"
                                + row);
            }
        }
    }

    /**
     * <b>The row measured against the widest name the generator can make, not the widest one this
     * fixture happened to roll.</b>
     *
     * <p>Written because the first breakage run found the gap: widening the name cap from eighteen
     * to forty-eight turned nothing red, because no villager in the forty-resident fixture had a
     * name longer than eighteen characters. Session 03's layout budget allows twenty-seven —
     * fourteen given plus twelve family plus a space — and the numbers beside it are at their widest
     * too. That is the row that has to fit.
     */
    @Test
    @DisplayName("an earnrate row fits at the widest name and the widest numbers the game can make")
    void theWidestEarnRateRowFits() {
        String longest = "Theardraelthild Hseingtsai";
        assertEquals(26, longest.length(), "session 03 caps a full name at 27 characters");

        DialogueStats.Standing worst = new DialogueStats.Standing(
                UUID.randomUUID(), longest,
                Bond.fresh(0).apply(new int[]{0, 100, 0, 0}, 0, 15),
                9999, 99999, Memories.RING_CAPACITY, 1.8F, 14);
        int column = NamesakeCommands.earnNameColumn(List.of(worst));
        String row = NamesakeCommands.earnRateRow(worst, column);

        assertTrue(row.length() <= CHAT_WIDTH,
                () -> "the widest earnrate row is " + row.length() + " characters and the budget is "
                        + CHAT_WIDTH + ":\n" + row);
        assertTrue(row.contains("Theardraelthild"),
                () -> "the name has to still be recognisable after clipping:\n" + row);
    }

    @Test
    @DisplayName("the simulate summary fits the chat width; the rest of it goes to a file")
    void theSimulateSummaryFits() {
        // Only the summary is held to the budget. The chronicle, the earn-rate table and the ring
        // dump are all wider than chat by design and go to a file — which is the right answer rather
        // than a compromise, because squeezing a ring dump into sixty characters would cost the
        // thing it is for.
        for (String row : Reports.summary(atScale())) {
            assertTrue(row.length() <= CHAT_WIDTH,
                    () -> "a simulate summary row is " + row.length() + " characters, over the "
                            + CHAT_WIDTH + "-character budget:\n" + row);
        }
    }

    /**
     * <b>The defect the harness found, held where a unit test can hold it.</b>
     *
     * <p>{@code /namesake debug simulate} told chat where it had written its report, as an absolute
     * path. In a development worktree that is 130 characters against a sixty-character chat width,
     * so the last line of the message arrived as three rows with the directory split across two of
     * them. Every unit test in the repo was green: the path depends on where the game is running and
     * nothing in {@code :common} knows it.
     *
     * <p>So the line is built by a method that can be handed a path far worse than any real one,
     * and the guard is that it does not grow with it. Put {@code toAbsolutePath()} back and this
     * turns red.
     */
    @Test
    @DisplayName("chat is told the report's name, never its path, however deep the path is")
    void theReportLineDoesNotGrowWithThePath() {
        Path buried = Path.of("C:", "a directory with a long name", "and another one",
                "and a third for luck", "worktrees", "headless-simulation-harness-46e01a",
                "fabric", "run", "namesake-simulation-report.txt");
        assertTrue(buried.toString().length() > CHAT_WIDTH,
                "the fixture path has to be over the budget or this proves nothing");

        String line = NamesakeCommands.reportLine(buried);
        assertTrue(line.length() <= CHAT_WIDTH,
                () -> "the report line is " + line.length() + " characters:\n" + line);
        assertTrue(line.contains("namesake-simulation-report.txt"),
                () -> "it still has to say which file:\n" + line);
    }

    /**
     * Session 06's defect, at the class level rather than at the instance level.
     *
     * <p>{@code String.format("%n")} is the platform separator and emits {@code \r\n} on Windows;
     * Minecraft draws a carriage return as a missing-glyph box. It was invisible in the log, in every
     * test that read the string, and perfectly obvious on the screen. So every line session 07 adds
     * is checked, not only the one somebody remembered to check.
     */
    @Test
    @DisplayName("no session 07 output contains a carriage return")
    void nothingNewEmitsACarriageReturn() {
        Simulation.Outcome outcome = atScale();
        List<String> everything = new java.util.ArrayList<>();
        everything.addAll(NamesakeCommands.statRows(
                statsAtScale(outcome), outcome.registry(), outcome.player()));
        everything.addAll(NamesakeCommands.earnRateRows(statsAtScale(outcome), outcome.player()));
        everything.addAll(Reports.full(outcome));
        everything.addAll(Reports.acrossModels(outcome.plan()));
        everything.addAll(Reports.witnessSensitivity(outcome.plan()));
        everything.addAll(Reports.propagation(outcome.plan()));
        everything.addAll(Reports.crossSettlement(outcome.plan()));
        everything.addAll(Reports.residencyWithAndWithoutGossip(outcome.plan().withDays(30)));

        for (String line : everything) {
            assertFalse(line.contains("\r"),
                    () -> "Minecraft draws a carriage return as a missing-glyph box. Use \\n, never "
                            + "%n:\n" + line);
            assertFalse(line.contains("\n"),
                    () -> "a report line must be one line, or the caller's join produces a row that "
                            + "is not a row:\n" + line);
        }
    }

    /**
     * {@code DESIGN.md} §11: <b>every section prints its own absence.</b>
     *
     * <p>An empty report that says nothing reads as a broken command, and a broken command is what a
     * player will report instead of the thing that is actually wrong. Session 03's settlements
     * command and session 06's deed ring both carry this; these two have to as well, because a fresh
     * world is exactly when somebody runs them.
     */
    @Test
    @DisplayName("an empty world says so rather than printing an empty table")
    void everySectionPrintsItsOwnAbsence() {
        NpcRegistry empty = new NpcRegistry();
        UUID viewer = UUID.fromString("0a0a0a0a-1111-2222-3333-444444444444");
        DialogueStats stats = DialogueStats.of(empty, viewer, 0);

        String statsReport = String.join("\n", NamesakeCommands.statRows(stats, empty, viewer));
        assertTrue(statsReport.contains("nobody in this world has met you"),
                () -> "an empty stats report must say so:\n" + statsReport);
        assertTrue(statsReport.contains("nobody remembers anything yet"),
                () -> "an empty ring section must say so:\n" + statsReport);

        String earnReport = String.join("\n", NamesakeCommands.earnRateRows(stats, viewer));
        assertTrue(earnReport.contains("nobody has met you"),
                () -> "an empty earn-rate report must say so:\n" + earnReport);

        // And from the console, where there is no "you" at all.
        assertTrue(String.join("\n", NamesakeCommands.statRows(
                        DialogueStats.of(empty, null, 0), empty, null)).contains("no viewer"),
                "run from the console there is no viewer, and that is a different absence");
    }

    @Test
    @DisplayName("the earn rate's unit is stated on the report, not only in a javadoc")
    void theUnitIsPrinted() {
        // The unit becomes what every session 12 threshold is expressed in. A number whose unit
        // lives only in a comment is a number somebody will read in a different one.
        Simulation.Outcome outcome = atScale();
        String report = String.join("\n",
                NamesakeCommands.earnRateRows(statsAtScale(outcome), outcome.player()));
        assertTrue(report.contains("warmth per in-game day of contact"),
                () -> "the unit has to be on the report:\n" + report);
    }
}
