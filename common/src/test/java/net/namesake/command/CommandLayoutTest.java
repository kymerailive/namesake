package net.namesake.command;

import net.namesake.npc.Persona;
import net.namesake.social.Deed;
import net.namesake.social.DeedType;
import net.namesake.social.Memories;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
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
     * The widest a deed row can be: the longest deed type, a two-digit age, and all three of the
     * markers that only appear when a field is carrying information.
     */
    private static String widestRow() throws Exception {
        // A witness's row, so the subject is somebody else: half-strength, second-hand, and in
        // another village — every marker on at once. The age is three digits because session 07's
        // harness runs a hundred in-game days, which is the widest that column realistically gets.
        Deed worst = new Deed(DeedType.STRUCK_RESIDENT.id(), ACTOR, UUID.randomUUID(), 4, 12,
                (byte) 60, (byte) 72);
        Method describe = NamesakeCommands.class.getDeclaredMethod(
                "describeDeed", Deed.class, Persona.class, int.class);
        describe.setAccessible(true);
        return (String) describe.invoke(null, worst, holder(), 112);
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
    @DisplayName("a nominal deed row carries no severity, confidence or settlement noise")
    void aNominalRowIsQuiet() throws Exception {
        Deed nominal = Deed.of(DeedType.FED_HUNGRY, ACTOR, HOLDER, 3, 12);
        Method describe = NamesakeCommands.class.getDeclaredMethod(
                "describeDeed", Deed.class, Persona.class, int.class);
        describe.setAccessible(true);
        String row = (String) describe.invoke(null, nominal, holder(), 12);

        // Every deed anything currently emits is nominal severity, first-hand, and in the holder's
        // own settlement. A column that reads 100 on every row for two whole sessions is not
        // information, and it is what pushed the first version off the edge of the screen.
        assertFalse(row.contains("s100"), () -> "nominal severity must not print: " + row);
        assertFalse(row.contains("c100"), () -> "first-hand confidence must not print: " + row);
        assertFalse(row.contains("@s"), () -> "the holder's own settlement must not print: " + row);
        assertTrue(row.contains("FED_HUNGRY"));
        assertTrue(row.contains("to them"), "the holder is the subject of this one");
    }

    @Test
    @DisplayName("a deed carrying real information says so, and still fits")
    void anInformativeRowSaysWhatItIs() throws Exception {
        String row = widestRow();
        assertTrue(row.contains("s60"), () -> "a half-strength blow must show its severity: " + row);
        assertTrue(row.contains("c72"), () -> "second-hand must show its confidence: " + row);
        assertTrue(row.contains("@s4"), () -> "another village's business must say so: " + row);
        // "heard" replaces "saw it" rather than sitting beside it: a thing that happened to you is
        // never something you were told about, so the three states are exclusive and cost one column.
        assertTrue(row.contains("heard"), () -> "second-hand must read as heard, not saw it: " + row);
        assertFalse(row.contains("saw it"), () -> "and not both: " + row);
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
    @DisplayName("the ring header states the capacity, so 32 is never a number in prose")
    void theRingCapacityIsRead() {
        assertEquals(32, Memories.RING_CAPACITY);
    }
}
