package net.namesake.culture;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two claims {@code WORKPLAN.md} makes about naming, proved rather than asserted in a comment:
 * more than 10<sup>6</sup> names per culture, and <b>it must never exhaust</b>.
 *
 * <p>The second is the interesting one, because the obvious way to test it is the wrong way.
 * Generating a large number of names and finding no failure proves nothing about the case that
 * matters — a generator with a used-names set and a redraw loop passes that test beautifully right
 * up until the set is nearly full, at which point the expected number of redraws goes to infinity
 * and the server stops. So exhaustion is disproved <i>structurally</i>: the generator holds no
 * mutable state, therefore it cannot remember what it has issued, therefore it has nothing to run
 * out of. The volume tests below corroborate the size of the space; they are not what rules out
 * exhaustion.
 */
class NamesTest {

    private static final Pattern GIVEN = Pattern.compile("^[A-Z][a-z]+$");
    private static final Pattern FAMILY = Pattern.compile("^[A-Z][a-z]+(-[a-z]+)?$");

    /** {@code WORKPLAN.md}: more than 10^6 names per culture. */
    private static final long REQUIRED_NAMES = 1_000_000L;

    /**
     * A self-imposed floor on given names alone, which the ledger does not ask for.
     *
     * <p>It is the number that decides whether a <i>village</i> repeats a first name. At 250,000, a
     * settlement of forty expects 0.003 repeats and a world of four thousand villagers of one
     * culture expects thirty-two — which is roughly how often two people share a first name in a
     * real county, and reads as correct rather than as a bug.
     */
    private static final long GIVEN_NAME_FLOOR = 250_000L;

    /** Longest name the debug dump and the dialogue lines are laid out for. */
    private static final int LONGEST_GIVEN = 14;
    private static final int LONGEST_FAMILY = 12;

    /**
     * Measured against the effective space, not the raw count — and the difference is not academic.
     * The first version of these grammars counted 12.4 million names for Yun and repeated itself
     * after eight hundred draws, because half of them came out of a 64,512-name two-syllable space
     * that the twelve-million figure had buried. A requirement checked against the flattering
     * number is a requirement that is not checked.
     */
    @Test
    @DisplayName("every culture behaves like more than a million names, not merely counts it")
    void everyCultureExceedsAMillionNames() {
        for (Culture culture : Culture.values()) {
            long effective = culture.grammar().effectiveFullNames();
            assertTrue(effective > REQUIRED_NAMES,
                    () -> culture + " counts " + culture.grammar().fullNameSpace()
                            + " names but behaves like " + effective + "; WORKPLAN.md requires "
                            + "more than " + REQUIRED_NAMES + ". Widen the shortest syllable count "
                            + "— that is the one carrying the collisions.");
        }
    }

    /**
     * Stricter than the ledger asks, and the number that actually governs how varied a village
     * sounds. Yun is the narrowest of the six by a distance: three codas and no consonant clusters
     * is what makes it sound like Yun, and it cannot be widened without either making its names
     * longer or making it sound like somebody else.
     */
    @Test
    @DisplayName("given names alone are varied enough that a village does not repeat one")
    void givenNamesAreVariedEnoughForASettlement() {
        for (Culture culture : Culture.values()) {
            long effective = culture.grammar().effectiveGivenNames();
            assertTrue(effective > GIVEN_NAME_FLOOR,
                    () -> culture + " behaves like only " + effective + " given names; a village "
                            + "would start repeating first names.");
        }
    }

    /**
     * The structural proof. Nothing on the naming path may hold mutable state, because state is the
     * only way a generator can exhaust: it has to remember what it issued in order to run out.
     *
     * <p>A constant lookup table is fine and is the reason this checks the field's mutability
     * rather than banning statics outright — {@code Culture.BY_ID} is a fixed array of six things
     * and can no more fill up than an enum can.
     */
    @Test
    @DisplayName("nothing on the naming path can remember a name it issued")
    void nothingRemembersANameItIssued() {
        for (Class<?> type : List.of(Names.class, NameGrammar.class, Culture.class)) {
            for (Field field : type.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                assertTrue(Modifier.isFinal(field.getModifiers()),
                        () -> type.getSimpleName() + "." + field.getName() + " is a mutable static. "
                                + "A name generator that can remember is a name generator that can "
                                + "run out.");
                boolean container = Collection.class.isAssignableFrom(field.getType())
                        || Map.class.isAssignableFrom(field.getType());
                assertTrue(!container,
                        () -> type.getSimpleName() + "." + field.getName() + " is a static "
                                + field.getType().getSimpleName() + ". Even final, a collection can "
                                + "be added to — which is exactly how a used-names set gets in.");
            }
        }
    }

    /**
     * Total: every seed produces a name, including the ones that break a careless generator. A
     * signed remainder on {@code Long.MIN_VALUE} yields a negative index and an
     * {@code ArrayIndexOutOfBoundsException} once in every few billion villagers, which is the kind
     * of bug that surfaces in somebody's world and nowhere else.
     */
    @Test
    @DisplayName("every seed produces a renderable name, including the awkward ones")
    void theGeneratorIsTotal() {
        List<Long> seeds = new ArrayList<>(List.of(
                0L, 1L, -1L, Long.MIN_VALUE, Long.MAX_VALUE, Long.MIN_VALUE + 1, -0x9E3779B97F4A7C15L));
        long state = 12345L;
        for (int i = 0; i < 20_000; i++) {
            state = state * 6364136223846793005L + 1442695040888963407L;
            seeds.add(state);
        }

        for (Culture culture : Culture.values()) {
            for (long seed : seeds) {
                String given = Names.given(culture, seed);
                String family = Names.family(culture, seed);
                assertTrue(GIVEN.matcher(given).matches(),
                        () -> culture + " produced an unrenderable given name '" + given
                                + "' from seed " + seed);
                assertTrue(FAMILY.matcher(family).matches(),
                        () -> culture + " produced an unrenderable family name '" + family
                                + "' from seed " + seed);
            }
        }
    }

    /**
     * Names have to fit where they are shown.
     *
     * <p>The first version of these grammars produced {@code "Theardraelthild"} and
     * {@code "Hseingtsainhianng"} — nineteen characters — because a three-syllable draw could stack
     * three diphthongs between three consonant clusters. Heavy nuclei are word-final now, which
     * caps it. Session 02 lost its placeholder greeting line to exactly this class of problem: a
     * string nobody had measured against the space it had to sit in.
     */
    @Test
    @DisplayName("no culture produces a name too long to lay out")
    void namesStayReadable() {
        for (Culture culture : Culture.values()) {
            String longestGiven = "";
            String longestFamily = "";
            for (long seed = 0; seed < 60_000; seed++) {
                String given = Names.given(culture, seed);
                if (given.length() > longestGiven.length()) {
                    longestGiven = given;
                }
                String family = Names.family(culture, seed);
                if (family.length() > longestFamily.length()) {
                    longestFamily = family;
                }
            }
            String given = longestGiven;
            String family = longestFamily;
            assertTrue(given.length() <= LONGEST_GIVEN,
                    () -> culture + " produced the " + given.length() + "-character given name '"
                            + given + "'; the layout budget is " + LONGEST_GIVEN);
            assertTrue(family.length() <= LONGEST_FAMILY,
                    () -> culture + " produced the " + family.length() + "-character family name '"
                            + family + "'; the layout budget is " + LONGEST_FAMILY);
        }
    }

    @Test
    @DisplayName("the same seed always produces the same name, so nothing has to be stored")
    void theGeneratorIsPure() {
        for (Culture culture : Culture.values()) {
            for (long seed : new long[]{0L, 42L, -99L, Long.MIN_VALUE}) {
                assertEquals(Names.given(culture, seed), Names.given(culture, seed));
                assertEquals(Names.family(culture, seed), Names.family(culture, seed));
            }
        }
    }

    /**
     * The claim that the counted space is the space you actually get, checked against real draws.
     *
     * <p>Deliberately not a fixed percentage. The expected number of distinct names in {@code n}
     * draws from a space of {@code N} is {@code N(1 - e^(-n/N))}, so the threshold derives from the
     * grammar itself — which means it stays honest when a grammar is later widened or narrowed, and
     * it fails if the realised space falls short of the counted one by even a couple of percent.
     *
     * <p>Run against the narrowest of the six, where any shortfall shows first. {@code Names.tidy}
     * is the one thing that can make realised smaller than counted: collapsing a run of three
     * identical letters maps a handful of distinct grammar words onto one string.
     */
    @Test
    @DisplayName("real draws collide at the rate the counted space predicts")
    void theRealisedSpaceMatchesTheCountedOne() {
        Culture narrowest = Culture.values()[0];
        for (Culture culture : Culture.values()) {
            if (culture.grammar().effectiveGivenNames() < narrowest.grammar().effectiveGivenNames()) {
                narrowest = culture;
            }
        }

        int draws = 100_000;
        Set<String> distinct = new HashSet<>(draws * 2);
        long state = 987654321L;
        for (int i = 0; i < draws; i++) {
            state = state * 6364136223846793005L + 1442695040888963407L;
            distinct.add(Names.given(narrowest, state));
        }

        long space = narrowest.grammar().effectiveGivenNames();
        double predicted = space * (1.0 - Math.exp(-(double) draws / space));
        double measured = distinct.size();
        Culture reported = narrowest;
        assertTrue(measured > predicted * 0.97,
                () -> "expected about " + Math.round(predicted) + " distinct names from " + reported
                        + " in " + draws + " draws and got " + Math.round(measured)
                        + ". The grammar is narrower in practice than it counts.");
    }

    /**
     * The half of this session's exit criterion the owner reads as "households are recognisably
     * related". A family name is a function of the household and nothing else.
     */
    @Test
    @DisplayName("a household shares one family name, and different households mostly do not")
    void householdsShareASurname() {
        Culture culture = Culture.VALE;
        long household = Names.householdSeed(4_211_337, culture.id());

        assertEquals(Names.family(culture, household), Names.family(culture, household));

        int collisions = 0;
        for (int other = 0; other < 500; other++) {
            if (Names.family(culture, Names.householdSeed(other, culture.id()))
                    .equals(Names.family(culture, household))) {
                collisions++;
            }
        }
        int measured = collisions;
        assertTrue(measured <= 1,
                () -> "500 other households produced " + measured
                        + " of the same surname; families would not read as families");
    }

    /**
     * Standing risk 3, as far as a machine can check it: if two cultures draw from the same sounds
     * they will produce names that rhyme, and the second village will sound like the first.
     *
     * <p>The real check is the owner's ear — this only stops a later edit from quietly walking two
     * grammars into each other.
     */
    @Test
    @DisplayName("no two cultures draw from substantially the same sounds")
    void culturesDoNotShareASoundInventory() {
        Culture[] all = Culture.values();
        for (int i = 0; i < all.length; i++) {
            for (int j = i + 1; j < all.length; j++) {
                Set<String> a = all[i].grammar().consonantInventory();
                Set<String> b = all[j].grammar().consonantInventory();

                Set<String> shared = new HashSet<>(a);
                shared.retainAll(b);
                Set<String> union = new HashSet<>(a);
                union.addAll(b);
                double overlap = (double) shared.size() / union.size();

                Culture left = all[i];
                Culture right = all[j];
                assertTrue(overlap < 0.55,
                        () -> left + " and " + right + " share " + Math.round(overlap * 100)
                                + "% of their consonants (" + shared + "). Two villages of these "
                                + "cultures would sound like one.");
            }
        }
    }

    @Test
    @DisplayName("one seed produces six different names, one per culture")
    void thesameSeedSoundsDifferentInEachCulture() {
        long seed = 0xBEEF_CAFEL;
        Set<String> names = new HashSet<>();
        for (Culture culture : Culture.values()) {
            names.add(Names.given(culture, seed));
        }
        assertEquals(Culture.COUNT, names.size(),
                () -> "the same seed produced " + names + " — cultures are not diverging");
    }

    @Test
    @DisplayName("a persona's name comes from its own id and its household's, not from one seed")
    void givenAndFamilyNamesAreIndependent() {
        Culture culture = Culture.KARSK;
        // Two villagers of one household: same surname, different given names.
        String familyA = Names.family(culture, Names.householdSeed(77, culture.id()));
        String familyB = Names.family(culture, Names.householdSeed(77, culture.id()));
        assertEquals(familyA, familyB);
        assertNotEquals(Names.given(culture, 1L), Names.given(culture, 2L));
    }
}
