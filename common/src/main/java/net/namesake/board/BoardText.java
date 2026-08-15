package net.namesake.board;

import net.namesake.dialogue.Pool;
import net.namesake.social.Deed;
import net.namesake.social.DeedType;
import net.namesake.social.Residency;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * <b>The board, laid out — and the only place in this mod that measures a string in pixels.</b>
 *
 * <h2>The width budget in this repo is the wrong one for a screen</h2>
 *
 * <p>{@code CommandLayoutTest} holds 1,920 rendered sentences and twelve command states to a
 * <b>sixty-character</b> chat width. That budget is right for chat and wrong here twice over: a
 * lectern GUI is not chat, and a character is not a unit of width — {@code Illinois} and
 * {@code wwwwwwww} are eight characters and 26 against 48 pixels. Every guard this project wrote
 * after shipping an over-wide string measures the wrong space for a screen.
 *
 * <h2>So the budget is derived from the engine rather than chosen</h2>
 *
 * <p>{@code Window.calculateScale} raises the GUI scale only while
 * {@code framebufferWidth / (scale + 1) >= 320} and {@code framebufferHeight / (scale + 1) >= 240}.
 * <b>So 320×240 is the smallest effective GUI the game will ever present</b>, at any window size, at
 * any GUI scale setting, including a forced one — which makes it the one number a layout can be held
 * to that is a property of Minecraft rather than of somebody's monitor. {@link #PANEL_WIDTH} leaves
 * sixteen scaled pixels of margin on each side of that, and {@link #TEXT_WIDTH} is what is left
 * inside the panel's own padding.
 *
 * <h2>Two columns, and the right one is right-aligned</h2>
 *
 * <p>Chat can only pad with spaces, which is why {@code NamesakeCommands} measures a name column
 * against the villagers actually present. A screen can draw the right-hand cell against the right
 * edge for nothing, so a row's budget is {@code indent + left + GAP + right}, and a name never pays
 * for a longer name somebody else has.
 *
 * <h2>The advance table, and why a harness leg exists for it</h2>
 *
 * <p>{@link #width} is a pure function so that {@code CommandLayoutTest} can enumerate every state
 * this board has — including the absence branches, which is where session 07 shipped three over-wide
 * lines past a guard that measured a populated fixture. It can only be pure because the default
 * font's ASCII advances are constants, and {@link #ADVANCES} is those constants written down.
 * <b>A table written down is a table that can be wrong</b>, so the attach-bet harness measures every
 * printable character with the real {@code Font} in a real client and fails naming any character it
 * disagrees about. That is the pin: this class is the authority the unit tests use, and the engine is
 * the authority this class is held to.
 */
public final class BoardText {

    /** The smallest effective GUI Minecraft will ever present. {@code Window.calculateScale}. */
    public static final int MINIMUM_SCREEN_WIDTH = 320;

    /** The smallest effective GUI height, for the same reason. */
    public static final int MINIMUM_SCREEN_HEIGHT = 240;

    /** Scaled pixels of screen left either side of the panel at the minimum above. */
    public static final int MARGIN = 16;

    public static final int PANEL_WIDTH = MINIMUM_SCREEN_WIDTH - 2 * MARGIN;

    /**
     * The tallest the panel may grow before the rows inside it start scrolling.
     *
     * <p>Height is the forgiving direction — a board too tall scrolls, where a board too wide is a
     * row that reads as two — so this is a comfort bound rather than the budget {@link #TEXT_WIDTH}
     * is. It comes off the same guarantee.
     */
    public static final int MAX_PANEL_HEIGHT = MINIMUM_SCREEN_HEIGHT - 2 * MARGIN;

    /** Panel border and breathing room, inside which nothing is drawn. */
    public static final int PADDING = 8;

    /** <b>The budget.</b> Every row in every state must fit inside it. */
    public static final int TEXT_WIDTH = PANEL_WIDTH - 2 * PADDING;

    /** The least space allowed between a row's two columns, so they never read as one. */
    public static final int GAP = 12;

    /** One level of indent, in scaled pixels. Roughly one and a third characters. */
    public static final int INDENT = 8;

    /** Vanilla's line height for the default font, plus a pixel of air. */
    public static final int LINE_HEIGHT = 10;

    /**
     * The most deeds either history section will draw.
     *
     * <p><b>Not a silent cap.</b> A settlement of nine holds up to nine full rings, so the distinct
     * deeds one player is the actor of are bounded by {@code RING_CAPACITY × residents} rather than
     * by anything small — and a board a thousand rows long is not a board. What is shown is the
     * newest, and what is not shown is <b>counted and said</b>, because a truncation nobody mentions
     * reads as "that is everything" and this surface is the only place a player learns what the mod
     * keeps. {@code /namesake debug deeds} remains the unbounded view.
     */
    public static final int MAX_ROWS = 32;

    private BoardText() {
    }

    /** How loudly a row is drawn. Nothing here decides anything; it is three colours. */
    public enum Tone {
        HEADING,
        BODY,
        QUIET
    }

    /**
     * One row.
     *
     * @param indent how many {@link #INDENT} steps in from the text column's left edge
     * @param right  drawn against the right edge of the text column, or empty
     */
    public record Line(Tone tone, int indent, String left, String right) {

        public static final Line BLANK = new Line(Tone.QUIET, 0, "", "");

        static Line heading(String left, String right) {
            return new Line(Tone.HEADING, 0, left, right);
        }

        static Line body(int indent, String left, String right) {
            return new Line(Tone.BODY, indent, left, right);
        }

        static Line quiet(int indent, String left) {
            return new Line(Tone.QUIET, indent, left, "");
        }

        static Line quiet(int indent, String left, String right) {
            return new Line(Tone.QUIET, indent, left, right);
        }

        /** What this row costs, laid out. The number {@link #TEXT_WIDTH} is the budget for. */
        public int cost() {
            int cost = indent * INDENT + width(left);
            return right.isEmpty() ? cost : cost + GAP + width(right);
        }

        public boolean isBlank() {
            return left.isEmpty() && right.isEmpty();
        }

        /** The row as one string, for the carriage-return and ASCII guards. */
        public String flat() {
            return " ".repeat(indent) + left + (right.isEmpty() ? "" : "  " + right);
        }
    }

    // --- the layout ------------------------------------------------------------------------------

    /**
     * The whole board, top to bottom.
     *
     * <p><b>Every section prints its own absence</b>, which is {@code WORKPLAN.md}'s instruction for
     * this session and the rule session 03's settlements command and session 06's deed ring already
     * carry. It matters more here than anywhere else in the mod: this board is the entire onboarding
     * surface — {@code DESIGN.md} rules that there is no tutorial — so a player's <i>first</i> sight
     * of it is every section empty at once. A board that prints nothing on an empty village teaches
     * nothing and reads as broken.
     */
    public static List<Line> of(Board board) {
        List<Line> lines = new ArrayList<>();
        header(board, lines);
        lines.add(Line.BLANK);
        opinions(board, lines);
        lines.add(Line.BLANK);
        witnessed(board, lines);
        lines.add(Line.BLANK);
        hearsay(board, lines);
        lines.add(Line.BLANK);
        // The one sentence that names the mechanism rather than reporting a state. A player who
        // reads nothing else has still been told what this mod does.
        lines.add(Line.quiet(0, "Villages talk to each other."));
        lines.add(Line.quiet(0, "What you do here is heard down the road."));
        return lines;
    }

    private static void header(Board board, List<Line> lines) {
        lines.add(Line.heading(board.named() ? board.place() : "A village with no name",
                "day " + board.day()));
        lines.add(Line.quiet(1, board.culture().isEmpty()
                ? people(board.residents())
                : "A " + board.culture() + " village. " + people(board.residents())));

        Residency.Verdict residency = board.residency();
        if (residency.granted()) {
            lines.add(Line.quiet(1, "This village has taken you in."));
            lines.add(Line.quiet(1, residency.route() == Residency.Route.BAND
                    ? "Enough of them trust you."
                    : "You did something for them that settled it."));
            return;
        }
        lines.add(Line.quiet(1, "You are a guest here, not one of them."));
        // Both of DESIGN.md §5's routes, with their progress on them, because the board is the only
        // place a player is ever told what the threshold is for. Neither row prints a bond value:
        // "how many people" is a count of people, and §2's rule is about the axes themselves.
        lines.add(Line.quiet(2, "Residents who trust you enough",
                residency.residentsAtThreshold() + " of " + Residency.RESIDENTS_REQUIRED));
        lines.add(Line.quiet(2, "Hungry people you have fed",
                residency.feedings() + " of " + Residency.FEEDINGS_REQUIRED));
    }

    private static void opinions(Board board, List<Line> lines) {
        lines.add(Line.heading("What the people here think of you", ""));
        if (board.opinions().isEmpty()) {
            lines.add(Line.quiet(1, "Nobody here has an opinion of you yet."));
            lines.add(Line.quiet(1, "Do something where they can see it."));
        } else {
            for (Board.Neighbour neighbour : board.opinions()) {
                lines.add(Line.body(1, neighbour.name(), standing(neighbour.pool())));
            }
        }
        if (board.strangers() > 0) {
            lines.add(Line.quiet(1, "and " + board.strangers() + " who have never met you."));
        }
    }

    private static void witnessed(Board board, List<Line> lines) {
        lines.add(Line.heading("What they have seen you do", ""));
        if (board.witnessed().isEmpty()) {
            // DESIGN.md §5's exact words, so that acceptance step 3 is met in the letter as well as
            // in the spirit. Two lines rather than one because the pair is 268 of the 272 available
            // and a budget met by four pixels is a budget met by luck.
            lines.add(Line.quiet(1, "No history."));
            lines.add(Line.quiet(1, "Nobody here has watched you do anything."));
            return;
        }
        List<Board.Memory> shown = board.witnessed().subList(0,
                Math.min(MAX_ROWS, board.witnessed().size()));
        for (Board.Memory memory : shown) {
            lines.add(Line.body(1, phrase(memory), "day " + memory.day()));
            // The place goes on a line of its own rather than sharing one with the count, and that
            // is a defect being fixed rather than a preference. Sharing put "from Wreindhollow,
            // north-east" against a count that left it 137 pixels, and the clip took the "-east" —
            // so a story from the north-east was posted as coming from the north. A clip that
            // shortens an object is a detail degrading, which this mod is allowed to do; a clip that
            // turns one direction into a different direction is a lie, which it is not.
            if (!memory.origin().here()) {
                lines.add(Line.quiet(2, source(memory.origin())));
            }
            String right = memory.holders() + " of " + board.residents() + " remember";
            lines.add(Line.quiet(2, object(memory, right), right));
        }
        older(lines, board.witnessed().size() - shown.size());
    }

    private static void hearsay(Board board, List<Line> lines) {
        lines.add(Line.heading("What they have been told about you", ""));
        if (board.hearsay().isEmpty()) {
            lines.add(Line.quiet(1, "No word of you has come down the road."));
            return;
        }
        lines.add(Line.quiet(1, "Second-hand. Nobody here saw it happen."));
        List<Board.Memory> shown = board.hearsay().subList(0,
                Math.min(MAX_ROWS, board.hearsay().size()));
        for (Board.Memory memory : shown) {
            lines.add(Line.body(1, phrase(memory), "day " + memory.day()));
            String right = memory.holders() + " heard it";
            // Never clipped, for the reason on the witnessed row above.
            lines.add(Line.quiet(2, source(memory.origin()), right));
        }
        older(lines, board.hearsay().size() - shown.size());
    }

    /** What was left off, said out loud. See {@link #MAX_ROWS}. */
    private static void older(List<Line> lines, int dropped) {
        if (dropped > 0) {
            lines.add(Line.quiet(1, "and " + dropped + " older, not shown here."));
        }
    }

    // --- the words -------------------------------------------------------------------------------

    /**
     * <b>What a standing is called, and it is {@link Pool}'s answer rather than a fifth scheme.</b>
     *
     * <p>See {@link Board.Neighbour} for why, and for what session 12 has to replace. The switch is
     * exhaustive with no default on purpose: a fifth pool is a compile error here rather than a band
     * that silently renders as nothing.
     *
     * <p>Phrases rather than labels, because this board is the onboarding surface. A player reading
     * four different sentences beside four names infers <i>this mod tracks how each person feels
     * about me</i>; a player reading four category names has to be told what a category is.
     */
    static String standing(Pool pool) {
        return switch (pool) {
            case STRANGER -> "has not met you";
            case KNOWN -> "knows you";
            case WARM -> "warm to you";
            case HOSTILE -> "wary of you";
        };
    }

    /**
     * What a deed reads as on a notice board, in the village's own voice.
     *
     * <p>Exhaustive over {@link DeedType} with no default, for the reason above: a seventh deed type
     * arriving in session 16's grievance engine is a compile error rather than a row that says
     * {@code KILLED_RESIDENT} at somebody who has never opened a debug command.
     */
    static String describe(DeedType type) {
        return switch (type) {
            case GIFT_WANTED -> "gave a welcome gift";
            case GIFT_UNWANTED -> "gave something unwanted";
            case FED_HUNGRY -> "fed someone hungry";
            case STRUCK_RESIDENT -> "struck one of us";
            case KILLED_RESIDENT -> "killed one of us";
            case DEFENDED_RAID -> "stood with us in a raid";
        };
    }

    /** The first line of a deed: what it was, and how many times if that is more than once. */
    static String phrase(Board.Memory memory) {
        String phrase = describe(memory.type());
        return memory.repeats() > 1 ? phrase + ", " + memory.repeats() + " times" : phrase;
    }

    /**
     * <b>The object, and it is the only thing on this board that is ever clipped.</b>
     *
     * <p>Everything else is bounded by construction — the longest phrase is a constant, the counts
     * are bounded by a save, a place name is a one-syllable stem plus a place word, a bearing is one
     * of eight strings. A registry id is the one part a modpack can make arbitrarily long, which is
     * exactly the argument session 09 made for {@code NamesakeCommands.deedCell}, in pixels instead
     * of characters.
     *
     * <p><b>And a clip that leaves too little is dropped rather than shown.</b>
     * {@code "enchanted golden apple"} cut to {@code "ench"} is not a shorter fact, it is a different
     * one — the same objection that took the origin off this line.
     */
    static String object(Board.Memory memory, String right) {
        if (memory.item().equals(Deed.NO_ITEM)) {
            return "";
        }
        int room = TEXT_WIDTH - 2 * INDENT - (right.isEmpty() ? 0 : GAP + width(right));
        String clipped = clip(item(memory.item()), room);
        return clipped.length() >= MIN_OBJECT ? clipped : "";
    }

    /** Fewer characters than this is a fragment rather than a name. */
    static final int MIN_OBJECT = 5;

    /**
     * Where a story came from, as a person standing here would say it.
     *
     * <p>The name and the bearing together rather than one or the other, and that is not belt and
     * braces. A name is what a village <i>is</i>; a bearing is where it is, and a player who has
     * never been there has no way to turn the first into the second. When there is no name — a place
     * that has fallen out of the settlement table — the bearing stands alone, which is exactly the
     * sentence {@code Deed.UNKNOWN_ACTOR} has promised since session 08.
     */
    public static String source(Board.Origin origin) {
        if (origin.here()) {
            return "here";
        }
        if (origin.hasPlace()) {
            return origin.bearing().isEmpty()
                    ? "from " + origin.place()
                    : "from " + origin.place() + ", " + origin.bearing();
        }
        return origin.bearing().isEmpty()
                ? "from somewhere else"
                : "from the " + origin.bearing();
    }

    /**
     * An item's registry id as a person would read it.
     *
     * <p>The namespace goes because {@code minecraft:} is ten characters of nothing on every row —
     * session 06's ruling — and the underscores go because nobody outside this repository writes
     * {@code enchanted_golden_apple}.
     */
    static String item(String registryId) {
        String path = registryId.substring(registryId.indexOf(':') + 1);
        return path.replace('_', ' ');
    }

    /** Whole clauses rather than a noun and a verb glued together: "0 people live here" is not one. */
    private static String people(int count) {
        return switch (count) {
            case 0 -> "Nobody lives here now.";
            case 1 -> "1 person lives here.";
            default -> count + " people live here.";
        };
    }

    /** Drops whole characters off the end until what is left fits. Never an ellipsis: it costs 6px. */
    static String clip(String text, int available) {
        if (available <= 0) {
            return "";
        }
        String clipped = text;
        while (!clipped.isEmpty() && width(clipped) > available) {
            clipped = clipped.substring(0, clipped.length() - 1);
        }
        return clipped;
    }

    // --- measurement -----------------------------------------------------------------------------

    /**
     * The default font's advance, in scaled pixels, for every printable ASCII character.
     *
     * <p>Indexed by {@code c - ' '}, so the table covers {@code 0x20}–{@code 0x7E} and nothing else.
     * Everything this mod draws is plain ASCII by rule — session 06 shipped a hundred and sixty
     * chances of a missing-glyph box and {@code DialogueTest} has guarded it since — so a character
     * outside this range is a defect rather than a case, and {@link #width} says so by name instead
     * of guessing a width for it.
     *
     * <p><b>Five of these numbers were wrong when this table was first written, and the harness found
     * every one of them in a single run.</b> {@code "}, {@code '}, {@code *}, <code>{</code> and
     * <code>}</code> were each a pixel too wide. Not one of them appears on the board today, so no
     * row disagreed and nothing a unit test could reach was affected — which is precisely why the pin
     * is against the engine's own {@code Font} and reports <i>which</i> characters differ. A latent
     * wrong number in a measurement table is the confident-wrong report this project has now written
     * about in three different units.
     */
    static final int[] ADVANCES = {
            //     !  "  #  $  %  &  '  (  )  *  +  ,  -  .  /
            4, 2, 4, 6, 6, 6, 6, 2, 4, 4, 4, 6, 2, 6, 2, 6,
            //  0  1  2  3  4  5  6  7  8  9  :  ;  <  =  >  ?
            6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 2, 2, 5, 6, 5, 6,
            //  @  A  B  C  D  E  F  G  H  I  J  K  L  M  N  O
            7, 6, 6, 6, 6, 6, 6, 6, 6, 4, 6, 6, 6, 6, 6, 6,
            //  P  Q  R  S  T  U  V  W  X  Y  Z  [  \  ]  ^  _
            6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 4, 6, 4, 6, 6,
            //  `  a  b  c  d  e  f  g  h  i  j  k  l  m  n  o
            3, 6, 6, 6, 6, 6, 5, 6, 6, 2, 6, 5, 3, 6, 6, 6,
            //  p  q  r  s  t  u  v  w  x  y  z  {  |  }  ~
            6, 6, 6, 6, 4, 6, 6, 6, 6, 6, 6, 4, 2, 4, 7};

    /** The first and last characters {@link #ADVANCES} covers. */
    public static final char FIRST_PRINTABLE = ' ';
    public static final char LAST_PRINTABLE = '~';

    /**
     * How wide this string is when the default font draws it, in scaled pixels.
     *
     * <p>Agreement with {@code Font.width} is proven by the attach-bet harness rather than asserted
     * here — see {@link #ADVANCES}.
     *
     * @throws IllegalArgumentException on any character outside printable ASCII, because a board that
     *                                  cannot measure a string must not report a number for it
     */
    public static int width(String text) {
        int total = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c < FIRST_PRINTABLE || c > LAST_PRINTABLE) {
                throw new IllegalArgumentException(String.format(Locale.ROOT,
                        "The notice board can only measure printable ASCII, and '%s' holds U+%04X at "
                                + "index %d. Minecraft draws an unknown glyph as a box and this table "
                                + "would report a confident wrong width for it.", text, (int) c, i));
            }
            total += ADVANCES[c - FIRST_PRINTABLE];
        }
        return total;
    }
}
