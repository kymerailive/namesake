package net.namesake.testing;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>{@code DESIGN.md}'s count of ruled decisions is counted rather than maintained.</b>
 *
 * <p>Session 15 found three documents each holding a different number for one thing: this file's
 * source said <b>85</b>, {@code CLAUDE.md} said <b>41</b> and {@code README.md} said <b>45</b>.
 * <i>One file, one number that cannot disagree with itself</i> is the ruling this project has made
 * five times — about a settlement name, about a block entity, about a road graph, about a residency
 * flag and about a measurement tally — and it had never been applied to its own paperwork.
 *
 * <p>So the number has a definition now, and a test. The other two documents stopped carrying one.
 *
 * <p><b>It is the same instrument {@code SessionLedger} is</b>, and for the same reason: a claim in
 * a markdown file that nothing reads is a claim that drifts. That one reads the status board out of
 * {@code WORKPLAN.md} so an exemption can expire without anybody remembering; this one reads a
 * count out of {@code DESIGN.md} so a decision cannot be added without the header noticing.
 */
class DesignCountTest {

    /** {@code 85 decisions ruled, 0 open.} */
    private static final Pattern HEADER =
            Pattern.compile("^(\\d+) decisions ruled, (\\d+) open\\.$");

    /** A decision. Not a separator, and not one of §2's empty two-column headers. */
    private static boolean isDecisionRow(String line) {
        return line.startsWith("|")
                && !line.matches("^\\|\\s*-+.*")
                && !line.matches("^\\|\\s*\\|\\s*\\|\\s*$");
    }

    @Test
    @DisplayName("DESIGN.md's own count of ruled decisions is the number of ruled decisions in it")
    void theCountIsTheCount() throws IOException {
        Path design = ModClasses.repoRoot().resolve("DESIGN.md");
        assertTrue(Files.isRegularFile(design), () -> "no DESIGN.md at " + design);
        List<String> lines = Files.readAllLines(design, StandardCharsets.UTF_8);

        int claimed = -1;
        int open = -1;
        for (String line : lines) {
            Matcher header = HEADER.matcher(line.trim());
            if (header.matches()) {
                claimed = Integer.parseInt(header.group(1));
                open = Integer.parseInt(header.group(2));
                break;
            }
        }
        assertTrue(claimed >= 0, "DESIGN.md no longer opens with 'N decisions ruled, M open.' — "
                + "which is the line this test exists to keep true, so its absence is the failure "
                + "rather than a reason to skip");

        // §2's tables have no column headings — their header row is the empty `| | |` that
        // isDecisionRow already drops — so every row of them is a decision. §9's rulings table is
        // numbered and does have a heading row, so its rulings are counted by that number.
        int counted = count(lines, "^## 2\\.", "^## 3\\.", DesignCountTest::isDecisionRow)
                + count(lines, "^### The seven rulings", "^## 10\\.",
                        line -> line.matches("^\\|\\s*\\d+\\s*\\|.*"));

        int finalCounted = counted;
        int finalClaimed = claimed;
        assertEquals(finalCounted, finalClaimed, () ->
                "DESIGN.md says " + finalClaimed + " decisions ruled and there are " + finalCounted
                        + ". A ruled decision is a row of §2's tables or a row of §9's rulings "
                        + "table. Update the header line — do NOT change this definition to make "
                        + "the number fit, which is how it came to disagree with CLAUDE.md and "
                        + "README.md in the first place.");
        assertEquals(0, open, "DESIGN.md carries no open decisions by construction: an open "
                + "question is parked in WORKPLAN.md's after-the-slice table, where it has a "
                + "session number against it, rather than left in this document with none.");
    }

    /** Neither of the other two documents may carry a competing number. */
    @Test
    @DisplayName("no other document repeats the count")
    void nothingElseCarriesTheNumber() throws IOException {
        for (String name : List.of("CLAUDE.md", "README.md")) {
            String text = Files.readString(ModClasses.repoRoot().resolve(name),
                    StandardCharsets.UTF_8);
            Matcher stray = Pattern.compile("\\b\\d+ ruled decisions\\b").matcher(text);
            assertTrue(!stray.find(), () -> name + " carries its own count of ruled decisions ("
                    + stray.group() + "). Three documents held three different numbers until "
                    + "session 15 — 41, 45 and 85 — and the fix is that only DESIGN.md counts. "
                    + "Point at it instead.");
        }
    }

    private static int count(List<String> lines, String from, String to,
                             java.util.function.Predicate<String> counts) {
        int start = -1;
        int rows = 0;
        for (String line : lines) {
            if (start < 0) {
                if (line.matches(from + ".*")) {
                    start = 1;
                }
                continue;
            }
            if (line.matches(to + ".*")) {
                break;
            }
            if (counts.test(line)) {
                rows++;
            }
        }
        return rows;
    }
}
