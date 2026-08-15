package net.namesake.board;

import net.namesake.dialogue.Pool;
import net.namesake.social.DeedType;
import net.namesake.social.Standing;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>The words on the board, and the ruler they are measured with.</b>
 *
 * <p>Whether every row <i>fits</i> is enumerated in {@code CommandLayoutTest}, beside the twelve
 * command states and the 1,920 sentences — the brief's instruction, and the right one: a fifth guard
 * that samples one state is how session 07 shipped three over-wide absence branches past four
 * existing ones. This file is the other half: whether the ruler is a ruler, and whether the sentences
 * say anything.
 */
class BoardTextTest {

    // --- the ruler ---------------------------------------------------------------------------------

    /**
     * <b>The budget is read off the engine rather than chosen, and this is where that is written
     * down.</b>
     *
     * <p>{@code Window.calculateScale} raises the GUI scale only while the framebuffer divided by the
     * next scale is still at least 320×240, so that is the smallest effective GUI Minecraft will ever
     * present — at any window size, at any scale setting, forced or automatic. A layout inside it is
     * identical for every player; a layout outside it is a row that reads as two on somebody's
     * monitor and nobody's here.
     */
    @Test
    @DisplayName("the budget comes off Minecraft's own minimum GUI, with a margin left either side")
    void theBudgetIsDerivedRatherThanChosen() {
        assertEquals(320, BoardText.MINIMUM_SCREEN_WIDTH);
        assertEquals(240, BoardText.MINIMUM_SCREEN_HEIGHT);
        assertEquals(BoardText.MINIMUM_SCREEN_WIDTH - 2 * BoardText.MARGIN, BoardText.PANEL_WIDTH);
        assertEquals(BoardText.PANEL_WIDTH - 2 * BoardText.PADDING, BoardText.TEXT_WIDTH);
        assertTrue(BoardText.PANEL_WIDTH + 2 * BoardText.MARGIN <= BoardText.MINIMUM_SCREEN_WIDTH,
                "the panel has to fit the smallest GUI the game can present, with its margin");
        assertTrue(BoardText.MAX_PANEL_HEIGHT + 2 * BoardText.MARGIN_Y
                <= BoardText.MINIMUM_SCREEN_HEIGHT);
        // Twenty rows, and the number is asserted because it is what decides whether the first
        // hearsay row is on the screen or one line under the fold. Read off a screenshot of a real
        // far village's board, which is the only instrument that could have said so.
        assertEquals(20, (BoardText.MAX_PANEL_HEIGHT - 2 * BoardText.PADDING)
                / BoardText.LINE_HEIGHT);
    }

    @Test
    @DisplayName("the advance table covers exactly printable ASCII and nothing is zero wide")
    void theAdvanceTableIsWholeAndPositive() {
        assertEquals(BoardText.LAST_PRINTABLE - BoardText.FIRST_PRINTABLE + 1,
                BoardText.ADVANCES.length);
        for (char c = BoardText.FIRST_PRINTABLE; c <= BoardText.LAST_PRINTABLE; c++) {
            int advance = BoardText.ADVANCES[c - BoardText.FIRST_PRINTABLE];
            assertTrue(advance > 0 && advance <= 8,
                    () -> "'" + (char) 0 + "' has an implausible advance of " + advance);
        }
    }

    /**
     * Measured by hand from the table, so a change to it is a decision rather than a drift.
     *
     * <p>What holds the table itself to the truth is the attach-bet harness, which measures every
     * printable character with the real {@code Font} in a real client and fails naming any character
     * it disagrees about. A unit test cannot: there is no font without a resource pack and a GL
     * context, which is exactly the line {@code WORKPLAN.md} draws between its two instruments.
     */
    @Test
    @DisplayName("a proportional font is measured proportionally, not in characters")
    void widthIsPixelsRatherThanCharacters() {
        assertEquals(0, BoardText.width(""));
        assertEquals(4, BoardText.width(" "));
        // Eight characters each, and 32 pixels against 48. This is the whole reason the chat width's
        // sixty-character budget is the wrong instrument for a screen.
        assertEquals(32, BoardText.width("Illinois"));
        assertEquals(48, BoardText.width("wwwwwwww"));
        assertTrue(BoardText.width("Illinois") < BoardText.width("wwwwwwww"));
    }

    /**
     * Session 06's missing-glyph box, guarded at the measurement rather than at the string.
     *
     * <p>A carriage return has no glyph and Minecraft draws it as a box; every other non-ASCII
     * character may or may not, depending on the resource pack. Either way this table has no number
     * for it, and reporting a confident wrong width is worse than refusing.
     */
    @Test
    @DisplayName("anything the table cannot measure is refused rather than guessed at")
    void nothingOutsidePrintableAsciiIsMeasured() {
        assertThrows(IllegalArgumentException.class, () -> BoardText.width("done\r"));
        assertThrows(IllegalArgumentException.class, () -> BoardText.width("done\n"));
        assertThrows(IllegalArgumentException.class, () -> BoardText.width("café"));
    }

    @Test
    @DisplayName("clipping takes whole characters off the end until what is left fits")
    void clippingFits() {
        String long_ = "enchanted golden apple";
        assertEquals("", BoardText.clip(long_, 0));
        assertEquals("", BoardText.clip(long_, -20));
        assertEquals(long_, BoardText.clip(long_, 1000));
        for (int room = 1; room <= 140; room++) {
            String clipped = BoardText.clip(long_, room);
            assertTrue(BoardText.width(clipped) <= room,
                    () -> "clip left '" + clipped + "' which does not fit");
            assertTrue(long_.startsWith(clipped), "a clip must not rewrite what it keeps");
        }
    }

    // --- the words ---------------------------------------------------------------------------------

    /**
     * <b>Four phrases and not four labels, because this board is the onboarding surface.</b>
     *
     * <p>{@code DESIGN.md} rules that there is no tutorial, so a player reading four different
     * sentences beside four names infers <i>this tracks how each person feels about me</i>, where a
     * player reading four category names has to be told what a category is.
     */
    @Test
    @DisplayName("every band has its own words, and none of them is a number")
    void everyStandingIsSaidDifferently() {
        Set<String> said = new LinkedHashSet<>();
        for (Standing band : Standing.values()) {
            // NEUTRAL is the one band that says two things, because it is the only band a player can
            // be in having never been seen at all. Every other band needs a bond somebody earned.
            for (Pool pool : band == Standing.NEUTRAL
                    ? List.of(Pool.STRANGER, Pool.KNOWN)
                    : List.of(Pool.KNOWN)) {
                String standing = BoardText.standing(band, pool);
                assertFalse(standing.isBlank(), () -> band + " says nothing");
                // DESIGN.md §2: bands, never raw integers. The board names a standing and prints no
                // number for it, which is what let session 12 replace Dialogue.poolFor and move the
                // board with it.
                assertTrue(standing.chars().noneMatch(Character::isDigit),
                        () -> band + " prints a number: " + standing);
                said.add(standing);
            }
        }
        assertEquals(Standing.values().length + 1, said.size(),
                () -> "two bands read the same: " + said);
    }

    /**
     * <b>The four phrases the owner ruled at the close of session 11 are kept word for word.</b>
     *
     * <p>They were read against the names in the owner's own village and ruled to read correctly,
     * and the ledger's note is explicit that five band names are <i>an improvement rather than a
     * fix, and the four phrases are the fallback if the five read worse</i>. So the two new ones are
     * additions beside them rather than a rewrite of them, and this is what says so.
     */
    @Test
    @DisplayName("session 11's four ruled phrases survive session 12 unchanged")
    void theRuledPhrasesAreUntouched() {
        assertEquals("has not met you", BoardText.standing(Standing.NEUTRAL, Pool.STRANGER));
        assertEquals("knows you", BoardText.standing(Standing.NEUTRAL, Pool.KNOWN));
        assertEquals("warm to you", BoardText.standing(Standing.WARM, Pool.WARM));
        assertEquals("wary of you", BoardText.standing(Standing.WARY, Pool.HOSTILE));
    }

    /**
     * A player who has never opened a debug command must not be shown {@code KILLED_RESIDENT}.
     *
     * <p>The switch behind this is exhaustive with no default, so session 16's grievance deeds are a
     * compile error here rather than a row of shouting.
     */
    @Test
    @DisplayName("every deed type reads as something a person would say")
    void everyDeedIsDescribedInWords() {
        Set<String> said = new LinkedHashSet<>();
        for (DeedType type : DeedType.values()) {
            String described = BoardText.describe(type);
            assertFalse(described.isBlank());
            assertFalse(described.contains("_"), () -> type + " leaked its enum name: " + described);
            assertNotEqualsIgnoringCase(type.name(), described);
            said.add(described);
        }
        assertEquals(DeedType.values().length, said.size(), () -> "two deeds read the same: " + said);
    }

    /**
     * The three things a source line can honestly be, and the ladder between them.
     *
     * <p>The bearing standing alone is the sentence {@code Deed.UNKNOWN_ACTOR} has promised since
     * session 08 and nothing had built.
     */
    @Test
    @DisplayName("a story says where it came from as precisely as it honestly can")
    void aSourceSaysWhatCanBeKnown() {
        assertEquals("here", BoardText.source(Board.Origin.HERE));
        assertEquals("from Skovadn, north-east",
                BoardText.source(new Board.Origin(false, "Skovadn", "north-east")));
        assertEquals("from Skovadn", BoardText.source(new Board.Origin(false, "Skovadn", "")));
        assertEquals("from the east", BoardText.source(new Board.Origin(false, "", "east")));
        assertEquals("from somewhere else", BoardText.source(new Board.Origin(false, "", "")));
    }

    /**
     * <b>The defect rendering the board found, pinned.</b>
     *
     * <p>The origin and the object shared one line, so the clip that exists for a modded registry id
     * ran over the place instead — and {@code "from wwwwwwwwwwww, north-east"} was posted as
     * {@code "from wwwwwwwwwwww, north"}. A shorter object is a detail degrading, which this mod does
     * everywhere and is allowed to. <b>A different compass point is a detail being invented</b>, which
     * is the one thing nothing in this mod may do — {@code DESIGN.md} §2, the gossip row: <i>distorts,
     * never lies.</i>
     *
     * <p>Every guard in the repository was green through it, and it was found by printing the board
     * and reading it. That is now four sessions running.
     */
    @Test
    @DisplayName("no clip can turn one direction into a different direction")
    void aClipNeverRewritesAPlace() {
        Board.Origin far = new Board.Origin(false, "w".repeat(12), "north-east");

        // The place is emitted whole or not at all, and never through clip().
        assertEquals("from wwwwwwwwwwww, north-east", BoardText.source(far));
        for (String direction : Board.COMPASS) {
            String source = BoardText.source(new Board.Origin(false, "Skovadn", direction));
            assertTrue(source.endsWith(direction),
                    () -> "'" + source + "' no longer ends in the bearing it was given");
        }
    }

    private static void assertNotEqualsIgnoringCase(String unwanted, String actual) {
        assertFalse(unwanted.equalsIgnoreCase(actual.replace(' ', '_')),
                () -> "'" + actual + "' is the enum name with the underscores taken out");
    }
}
