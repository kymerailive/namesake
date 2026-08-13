package net.namesake.culture;

/**
 * One culture's phonotactics: which sounds it uses, and where in a word they are allowed.
 *
 * <p>A name is built by drawing a syllable count and then, per syllable, an onset, a nucleus and a
 * coda. The inventories are split by <i>position</i> rather than being one flat list, because a
 * flat list is what produces unpronounceable junk: the clusters that can open a word are not the
 * ones that can sit between two vowels, and the clusters that can close a word are different again.
 * Separate lists cost nothing and remove the whole class of {@code "wrthll"} outputs.
 *
 * <p><b>Why the nuclei are split too.</b> The first version used one vowel list everywhere and
 * produced {@code "Theardraelthild"} — fifteen characters, because a three-syllable draw can stack
 * three diphthongs between three consonant clusters. Heavy nuclei are now word-final only, which is
 * a real phonotactic pattern and caps a name at about eleven characters. The fix had to be a rule
 * rather than "redraw if it comes out too long": a redraw is a loop, and a loop is the thing
 * {@link Names} is not allowed to have.
 *
 * <p><b>Why the space is computed rather than asserted by hand.</b> {@code WORKPLAN.md} requires
 * more than 10<sup>6</sup> names per culture. A comment claiming that is worth nothing — it is the
 * kind of number that is true when written and false three edits later. {@link #givenNameSpace()}
 * derives it from the tables themselves, so the test that guards it is measuring the grammar that
 * actually ships.
 *
 * <p><b>Why it cannot exhaust.</b> Nothing here, and nothing in {@link Names}, remembers a name it
 * has issued. Generation is a total function from a seed to a string: there is no rejection loop,
 * no retry-until-unique, and no set of used names to run out of. Two villagers may share a given
 * name, exactly as two people do. That is the property {@code NamesTest} pins down structurally,
 * rather than by generating a large number and hoping.
 */
public record NameGrammar(
        String[] initialOnsets,
        String[] medialOnsets,
        String[] medialNuclei,
        String[] nuclei,
        String[] medialCodas,
        String[] finalCodas,
        int minSyllables,
        int maxSyllables,
        String[] familySuffixes,
        int minFamilyStem,
        int maxFamilyStem) {

    public NameGrammar {
        require(initialOnsets, "initialOnsets");
        require(medialOnsets, "medialOnsets");
        require(medialNuclei, "medialNuclei");
        require(nuclei, "nuclei");
        require(medialCodas, "medialCodas");
        require(finalCodas, "finalCodas");
        require(familySuffixes, "familySuffixes");
        for (String nucleus : medialNuclei) {
            if (nucleus.isEmpty()) {
                throw new IllegalArgumentException("a nucleus may not be empty");
            }
        }
        if (minSyllables < 1 || maxSyllables < minSyllables) {
            throw new IllegalArgumentException(
                    "syllable range " + minSyllables + ".." + maxSyllables + " is not usable");
        }
        if (minFamilyStem < 1 || maxFamilyStem < minFamilyStem) {
            throw new IllegalArgumentException(
                    "family stem range " + minFamilyStem + ".." + maxFamilyStem + " is not usable");
        }
        for (String nucleus : nuclei) {
            if (nucleus.isEmpty()) {
                // A syllable with no nucleus is not a syllable, and an all-empty draw would produce
                // an empty name — the one output the caller cannot render.
                throw new IllegalArgumentException("a nucleus may not be empty");
            }
        }
    }

    /** How many distinct given names this grammar can produce. */
    public long givenNameSpace() {
        long total = 0;
        for (int syllables = minSyllables; syllables <= maxSyllables; syllables++) {
            total = Math.addExact(total, wordSpace(syllables));
        }
        return total;
    }

    /**
     * The size the space <i>behaves</i> like, which is the number that matters and is not the one
     * above.
     *
     * <p>{@link #givenNameSpace()} adds the lengths together, and a three-syllable space dwarfs a
     * two-syllable one — but the syllable count is drawn uniformly, so half of all names come out
     * of the small space. The first version of this grammar counted 12.4 million names for Yun and
     * repeated itself once every eight hundred villagers, because 64,512 of those names were
     * carrying half the traffic.
     *
     * <p>So this reports the collision-equivalent size: the {@code N} for which two independent
     * draws collide as often as they actually do. With lengths drawn uniformly over {@code k}
     * options of sizes {@code S_i}, the collision probability is {@code Σ (1/k)² / S_i}, and the
     * equivalent size is its reciprocal — dominated by the <i>smallest</i> length rather than the
     * largest. This is what the "more than 10⁶ names" requirement is tested against, and
     * {@code NamesTest} then checks a hundred thousand real draws against this figure rather than
     * against a threshold somebody picked.
     */
    public long effectiveGivenNames() {
        int lengths = maxSyllables - minSyllables + 1;
        double collisionProbability = 0.0;
        for (int syllables = minSyllables; syllables <= maxSyllables; syllables++) {
            collisionProbability += 1.0 / ((double) lengths * lengths * wordSpace(syllables));
        }
        return Math.round(1.0 / collisionProbability);
    }

    /** How many distinct family names this grammar can produce — stem × suffix. */
    public long familyNameSpace() {
        long stems = 0;
        for (int syllables = minFamilyStem; syllables <= maxFamilyStem; syllables++) {
            stems = Math.addExact(stems, wordSpace(syllables));
        }
        return Math.multiplyExact(stems, familySuffixes.length);
    }

    /**
     * Given × family. This is the number a player would have to exhaust to see a repeat, and it is
     * the honest headline figure, because a villager is addressed by both.
     */
    public long fullNameSpace() {
        return Math.multiplyExact(givenNameSpace(), familyNameSpace());
    }

    /** {@link #effectiveGivenNames()} for family names. */
    public long effectiveFamilyNames() {
        int lengths = maxFamilyStem - minFamilyStem + 1;
        double collisionProbability = 0.0;
        for (int syllables = minFamilyStem; syllables <= maxFamilyStem; syllables++) {
            collisionProbability += 1.0 / ((double) lengths * lengths * wordSpace(syllables));
        }
        return Math.round(familySuffixes.length / collisionProbability);
    }

    /**
     * How many distinct <i>names</i> this culture behaves like having.
     *
     * <p>This is the figure {@code WORKPLAN.md}'s "more than 10⁶ names per culture" is measured
     * against, because a name in this system is what a villager is called — Bram Ashwood, not Bram.
     * Given and family names are drawn from independent seeds (the persona id and the household id),
     * so the collision-equivalent sizes multiply.
     */
    public long effectiveFullNames() {
        return Math.multiplyExact(effectiveGivenNames(), effectiveFamilyNames());
    }

    /**
     * The number of distinct words of exactly {@code syllables} syllables.
     *
     * <p>First syllable takes an initial onset; the last takes a final coda; a one-syllable word is
     * both at once.
     */
    private long wordSpace(int syllables) {
        long space = 1;
        for (int i = 0; i < syllables; i++) {
            String[] onsets = i == 0 ? initialOnsets : medialOnsets;
            boolean last = i == syllables - 1;
            String[] vowels = last ? nuclei : medialNuclei;
            String[] codas = last ? finalCodas : medialCodas;
            space = Math.multiplyExact(space, (long) onsets.length * vowels.length * codas.length);
        }
        return space;
    }

    /** Every sound this culture uses in an onset or a coda — its audible signature. */
    public java.util.Set<String> consonantInventory() {
        java.util.Set<String> sounds = new java.util.LinkedHashSet<>();
        for (String[] list : new String[][]{initialOnsets, medialOnsets, medialCodas, finalCodas}) {
            for (String sound : list) {
                if (!sound.isEmpty()) {
                    sounds.add(sound);
                }
            }
        }
        return sounds;
    }

    private static void require(String[] list, String what) {
        if (list == null || list.length == 0) {
            throw new IllegalArgumentException(what + " must have at least one entry");
        }
    }
}
