package net.namesake.culture;

import net.namesake.npc.Persona;

import java.util.UUID;

/**
 * Seed in, name out. Nothing else.
 *
 * <p><b>This class can never run out of names, and that is a structural property rather than a
 * large number.</b> Generation is a total function: a seed is decomposed into one draw per
 * syllable, every draw lands inside its inventory by construction, and there is no rejection loop,
 * no retry-until-unique and no record of what has already been issued. Two villagers may share a
 * given name — people do — and a settlement of ten thousand would still be named in constant time
 * per villager.
 *
 * <p>The alternative, which is what a "unique names" requirement usually produces, is a set of used
 * names and a loop that redraws on collision. That works beautifully until the set is nearly full,
 * at which point the loop's expected iterations go to infinity and the server stops. {@code
 * NamesTest} asserts the absence of that machinery directly — no mutable static state — rather than
 * generating a million names and inferring it.
 *
 * <p><b>Determinism.</b> A name is a pure function of the seed, so it is not stored. That keeps a
 * villager's name out of the save file entirely: it is derived from the persona id and the
 * household id every time it is asked for, and cannot drift from them.
 */
public final class Names {

    /**
     * A villager's name. Given name is theirs; family name belongs to the household, which is what
     * makes the "households are recognisably related" half of this session's exit criterion visible
     * at a glance.
     */
    public record Name(String given, String family) {
        public String full() {
            return given + " " + family;
        }

        @Override
        public String toString() {
            return full();
        }
    }

    private Names() {
    }

    /** The name of a generated persona. */
    public static Name of(Persona persona) {
        Culture culture = Culture.byId(persona.cultureId());
        return new Name(
                given(culture, personaSeed(persona.id())),
                family(culture, householdSeed(persona.householdId(), persona.cultureId())));
    }

    public static String given(Culture culture, long seed) {
        NameGrammar grammar = culture.grammar();
        return capitalise(word(grammar, seed, grammar.minSyllables(), grammar.maxSyllables()));
    }

    public static String family(Culture culture, long seed) {
        NameGrammar grammar = culture.grammar();
        String stem = word(grammar, seed, grammar.minFamilyStem(), grammar.maxFamilyStem());
        String[] suffixes = grammar.familySuffixes();
        String suffix = suffixes[index(mix(seed ^ 0x5DEECE66DL), suffixes.length)];
        return capitalise(tidy(stem + suffix));
    }

    /**
     * <b>A settlement's name — session 11, and it costs zero persisted bytes.</b>
     *
     * <p>{@code Settlement} is six fields and none of them is a name: session 03 deleted its culture
     * for being derivable and would have deleted this for the same reason. So it is derived, exactly
     * as a villager's is, and the two arguments for that are the same two. A stored name is a cache
     * that can drift from the bell it was rolled against; and a persisted field whose only consumer
     * is a display is the shape {@code DESIGN.md} §1 forbids — where a <i>derived</i> one has nothing
     * to classify, which is the standing this class has held since session 03.
     *
     * <p><b>It reuses {@link #family} rather than {@link #given}, and that is the grammar being read
     * rather than a shortcut.</b> Every culture's family suffixes are already place words — Vale's are
     * literally {@code wood}, {@code field}, {@code ford}, {@code combe}, {@code mere}; Karsk's are
     * {@code grad}, {@code vor}, {@code din} — because a family name and a place name are the same
     * shape in every language this table was modelled on. A one-syllable stem plus one of those is a
     * place; three syllables of given-name phonotactics is a person.
     *
     * <p><b>Seeded from the bell, so it is stable for the life of the settlement.</b> The id is not
     * usable: it comes from a counter that increments in the order a player happens to visit, so two
     * saves of one world seed would name the same village differently. The bell does not move, and it
     * is what {@code Personas} already derives the settlement's culture from — so the name and the
     * tongue it is in come from one point on the map.
     *
     * @param culture the culture at the bell, which every resident shares by construction — see
     *                {@code Personas.generate}, which reads it at the settlement centre rather than
     *                at the villager's feet for exactly that reason
     */
    public static String ofSettlement(Culture culture, int bellX, int bellZ) {
        return family(culture, settlementSeed(bellX, bellZ));
    }

    /**
     * The seed for a settlement's name. Both coordinates are folded through the mixer separately,
     * because a village at {@code (x, z)} and one at {@code (z, x)} are different places and a plain
     * exclusive-or would give them one name.
     */
    public static long settlementSeed(int bellX, int bellZ) {
        return mix(bellX * 0x9E3779B97F4A7C15L) ^ mix(bellZ * 0xC2B2AE3D27D4EB4FL);
    }

    /**
     * The seed for a persona's given name. Folds both halves of the UUID, because two personas
     * minted in the same millisecond differ mostly in the low bits.
     */
    public static long personaSeed(UUID personaId) {
        return personaId.getMostSignificantBits() * 0x9E3779B97F4A7C15L
                ^ personaId.getLeastSignificantBits();
    }

    /** The seed for a household's family name. Culture is folded in so ids do not rhyme across it. */
    public static long householdSeed(int householdId, byte cultureId) {
        return (long) householdId * 0xC2B2AE3D27D4EB4FL + cultureId * 0x165667B19E3779F9L;
    }

    // --- assembly ----------------------------------------------------------------------------------

    /**
     * One word of between {@code min} and {@code max} syllables.
     *
     * <p>Position decides which inventory each draw comes from: the first syllable takes an initial
     * onset, the last takes the heavy nuclei and the final codas, and everything between takes the
     * medial forms. That is what stops a word opening on a cluster that only works word-finally,
     * and what keeps a three-syllable name from stacking three diphthongs.
     */
    private static String word(NameGrammar grammar, long seed, int min, int max) {
        long state = mix(seed);
        int syllables = min + index(state, max - min + 1);

        StringBuilder out = new StringBuilder(16);
        for (int i = 0; i < syllables; i++) {
            boolean last = i == syllables - 1;
            String[] onsets = i == 0 ? grammar.initialOnsets() : grammar.medialOnsets();
            String[] vowels = last ? grammar.nuclei() : grammar.medialNuclei();
            String[] codas = last ? grammar.finalCodas() : grammar.medialCodas();

            state = mix(state);
            out.append(onsets[index(state, onsets.length)]);
            state = mix(state);
            out.append(vowels[index(state, vowels.length)]);
            state = mix(state);
            out.append(codas[index(state, codas.length)]);
        }
        return tidy(out.toString());
    }

    /**
     * Collapses a run of three or more identical letters to two.
     *
     * <p>A medial coda and the next onset can legitimately be the same letter — {@code "kar" +
     * "ro"} — and twice is fine in a name. Three times is not, and it is the only ugly output the
     * position-split inventories do not already prevent. Deliberately a rewrite rather than a
     * redraw: a redraw is a loop, and a loop is the thing this class is not allowed to have.
     */
    private static String tidy(String word) {
        StringBuilder out = new StringBuilder(word.length());
        int run = 0;
        char previous = 0;
        for (int i = 0; i < word.length(); i++) {
            char c = word.charAt(i);
            run = c == previous ? run + 1 : 0;
            previous = c;
            if (run < 2) {
                out.append(c);
            }
        }
        return out.toString();
    }

    private static String capitalise(String word) {
        if (word.isEmpty()) {
            // Unreachable: a nucleus is never empty and NameGrammar refuses one that is. Kept
            // because "unreachable" and "cannot happen" are different claims, and a name that came
            // back empty would otherwise crash somewhere far away from here.
            throw new IllegalStateException("a name grammar produced an empty word");
        }
        return Character.toUpperCase(word.charAt(0)) + word.substring(1);
    }

    /** SplitMix64. Fast, allocation-free, and well distributed in every bit. */
    private static long mix(long value) {
        long z = value + 0x9E3779B97F4A7C15L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    /**
     * An index into an inventory. Unsigned remainder, so a negative state cannot produce a negative
     * index — the classic way a generator like this throws once in every two billion villagers.
     */
    private static int index(long state, int bound) {
        return (int) Long.remainderUnsigned(state, bound);
    }
}
