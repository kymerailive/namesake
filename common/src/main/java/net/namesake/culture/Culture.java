package net.namesake.culture;

import net.namesake.npc.Persona;

/**
 * The six cultures, and everything that makes one sound and behave unlike another.
 *
 * <p>Standing risk 3 in {@code WORKPLAN.md} is that settlement two feels like settlement one, and
 * that the travel loop then collapses around hour 45. Nothing else in the design saves it, so a
 * culture has to differ on more than a colour: it carries its own phonotactics, its own baseline
 * disposition, and its own tolerance for people who deviate from that baseline.
 *
 * <p><b>Not persisted, and not a record.</b> A persona stores a culture <i>id</i>; this table is
 * code. That matters twice over — the palette is presentation under {@code DESIGN.md} §9 and must
 * never reach a save file, and a table that is not persisted cannot drift from a save that is.
 * The one number that is persisted is {@link #id()}, so ids are declared explicitly below rather
 * than taken from {@code ordinal()}: reordering this enum would otherwise silently repaint and
 * rename every villager in every existing world.
 *
 * <h2>The three mechanical outputs</h2>
 * <ul>
 *   <li>{@link #traitBase()} — where this culture's people start, before the settlement's own
 *       circumstances and before household and individual variation. Read by
 *       {@code TraitRoll.roll}.</li>
 *   <li>{@link #conformity()} — how tightly individuals cluster around their household. Also read
 *       by {@code TraitRoll.roll}, and the reason a persona stores its own culture id rather than
 *       borrowing its settlement's.</li>
 *   <li>{@link #grammar()} — the syllable inventories {@link Names} draws from.</li>
 * </ul>
 * The palette is the fourth output and is the only one that is purely for the eye.
 */
public enum Culture {

    /**
     * Temperate farmland. Settled, hospitable, incurious, and quietly certain that things are done
     * a particular way here. Soft onsets, open vowels, names that end in a consonant you can lean on.
     */
    VALE(0, "vale", 0.55F, 0.75F,
            new byte[]{30, 15, -10, -5, 20, -10, -15, 25},
            new int[]{0x6E8B4F, 0xC9BE9B, 0x8A5B3A, 0xE3DCC4},
            new NameGrammar(
                    new String[]{"", "b", "br", "d", "dr", "f", "g", "gr", "h", "k", "l", "m", "n",
                            "r", "s", "st", "t", "th", "w", "wr"},
                    new String[]{"b", "d", "f", "g", "l", "m", "n", "r", "s", "t", "th", "v", "w", "dr"},
                    new String[]{"a", "e", "i", "o", "u"},
                    new String[]{"a", "e", "i", "o", "u", "ae", "ea", "ei"},
                    new String[]{"", "l", "m", "n", "r", "s"},
                    new String[]{"", "ck", "l", "ld", "ll", "m", "n", "nd", "r", "s", "th"},
                    2, 3,
                    // The suffixes are whole words, so the stem stays one syllable: Branwood, not
                    // Wreinseickford.
                    new String[]{"wood", "field", "brook", "ford", "combe", "hollow", "mere", "stead"},
                    1, 1),
            "Vale"),

    /**
     * Cold high country. Hard, proud, long-memoried, quick to anger and slow to forgive. Consonant
     * clusters that stack, and almost no soft vowels.
     */
    KARSK(1, "karsk", 0.05F, 0.70F,
            new byte[]{-20, 25, 30, -15, 30, 0, 30, -20},
            new int[]{0x4A5560, 0x8C4A32, 0x2E3B44, 0xB9C2C8},
            new NameGrammar(
                    new String[]{"v", "z", "g", "gr", "kr", "dr", "sv", "zh", "m", "n", "r", "t",
                            "tr", "gv", "vl", "st"},
                    new String[]{"v", "z", "g", "k", "d", "m", "n", "r", "t", "zh", "sk", "kr"},
                    new String[]{"a", "o", "u", "y", "i"},
                    new String[]{"a", "o", "u", "y", "i", "e", "ya", "yu"},
                    new String[]{"", "r", "k", "n", "s", "z"},
                    new String[]{"k", "sk", "r", "rn", "zh", "v", "n", "t", "rk", "st"},
                    2, 3,
                    new String[]{"ov", "eva", "sk", "grad", "vor", "din"},
                    1, 1),
            "Karsk"),

    /**
     * Warm coast. Mercantile, voluble, and the least interested of the six in what its grandparents
     * would have thought. Vowel-heavy, with diphthongs where another culture would put a consonant.
     */
    MERIDIAN(2, "meridian", 0.72F, 1.00F,
            new byte[]{15, 0, 10, 20, -15, 35, 5, 30},
            new int[]{0xC1663A, 0xE0B44A, 0xF2E6D0, 0x7A3B2E},
            new NameGrammar(
                    new String[]{"b", "c", "d", "f", "l", "m", "n", "p", "r", "s", "t", "v", "gi", "vi"},
                    new String[]{"b", "c", "d", "l", "m", "n", "r", "s", "t", "v", "ll", "ss"},
                    new String[]{"a", "e", "i", "o", "u"},
                    new String[]{"a", "e", "i", "o", "ia", "io", "ea", "ue", "au"},
                    new String[]{"", "l", "m", "n", "r", "s"},
                    new String[]{"", "a", "o", "n", "l", "r", "s", "ne"},
                    2, 3,
                    new String[]{"di", "della", "ito", "esca", "oni", "aro"},
                    1, 1),
            "Meridian"),

    /**
     * Hot lowland. Communal, patient, slow to take offence and very hard to hurry. Open syllables,
     * long vowels, almost nothing that closes hard.
     */
    ASHANI(3, "ashani", 1.00F, 0.80F,
            new byte[]{25, 10, -5, 15, 10, -20, -25, 15},
            new int[]{0x334E8C, 0xE09B2D, 0xC46A2C, 0xF0E2C2},
            new NameGrammar(
                    new String[]{"b", "d", "j", "k", "l", "m", "n", "p", "r", "s", "t", "y", "h",
                            "nd", "mb"},
                    new String[]{"b", "d", "j", "k", "l", "m", "n", "r", "s", "t", "y", "nd", "mb",
                            "ng"},
                    new String[]{"a", "i", "u", "e", "o"},
                    new String[]{"a", "i", "u", "aa", "ii", "e", "o", "uu", "ee", "ai", "ua", "ie"},
                    new String[]{"", "m", "n"},
                    new String[]{"", "a", "i", "n", "m", "ya", "we", "o", "u", "la"},
                    2, 3,
                    new String[]{"we", "adi", "nka", "toro", "sese", "bala"},
                    1, 1),
            "Ashani"),

    /**
     * Dry uplands. Austere, watchful, and the most conformist of the six — a Tal-Qir household
     * produces people who resemble each other. Back-of-the-throat onsets and clipped finals.
     */
    TALQIR(4, "talqir", 0.90F, 0.60F,
            new byte[]{-10, 20, 15, -10, 35, 10, 10, -15},
            new int[]{0xC8A96B, 0xEDE3CC, 0x8B2E2E, 0x5A4632},
            new NameGrammar(
                    new String[]{"q", "kh", "h", "z", "s", "sh", "t", "d", "r", "m", "n", "b",
                            "gh", "th", "j", "f", "w"},
                    new String[]{"q", "kh", "h", "z", "s", "sh", "t", "d", "r", "m", "n", "l",
                            "b", "f", "w"},
                    new String[]{"a", "i", "u"},
                    new String[]{"a", "i", "u", "aa", "ii", "uu", "ay", "aw"},
                    new String[]{"", "r", "l", "n", "s", "m"},
                    new String[]{"r", "n", "l", "m", "d", "q", "sh", "", "th", "z", "kh"},
                    2, 3,
                    new String[]{"-al", "-ir", "-um", "-esh", "-qan", "-had"},
                    1, 1),
            "Tal-Qir"),

    /**
     * Cool wet valleys. Orderly, industrious, reserved, and curious in a way that stays private.
     * Nasal finals and no consonant clusters at all in the coda.
     */
    YUN(5, "yun", 0.30F, 0.85F,
            new byte[]{0, 30, -15, 25, 15, 5, -20, -10},
            new int[]{0x3F7A63, 0x59606B, 0x7A3E5C, 0xDCE4DE},
            new NameGrammar(
                    // Yun keeps only three codas — that is its sound — so the width it needs to
                    // stay past a million effective names comes out of its vowels instead.
                    new String[]{"ch", "h", "j", "k", "l", "m", "n", "p", "s", "t", "w", "y", "x",
                            "zh", "q", "sh", "hs", "ts"},
                    new String[]{"ch", "h", "j", "k", "l", "m", "n", "s", "t", "y", "zh", "q",
                            "x", "w", "hs", "ts"},
                    new String[]{"a", "e", "i", "o", "u"},
                    new String[]{"a", "e", "i", "o", "u", "ai", "ao", "ei", "ia", "ou", "ue",
                            "iu", "uo", "ui"},
                    new String[]{"", "n"},
                    new String[]{"", "n", "ng"},
                    2, 3,
                    new String[]{"-shan", "-lo", "-wei", "-tan", "-jin", "-hai"},
                    1, 1),
            "Yun");

    /** Ids are contiguous from zero and this is checked below, so an id is also an array index. */
    public static final int COUNT = values().length;

    private static final Culture[] BY_ID = new Culture[COUNT];

    static {
        for (Culture culture : values()) {
            if (culture.id < 0 || culture.id >= COUNT || BY_ID[culture.id] != null) {
                throw new IllegalStateException(
                        "Culture ids must be unique and contiguous from 0; " + culture + " has " + culture.id);
            }
            BY_ID[culture.id] = culture;
        }
    }

    private final byte id;
    private final String key;
    private final float preferredTemperature;
    private final float conformity;
    private final byte[] traitBase;
    private final int[] palette;
    private final NameGrammar grammar;
    private final String displayName;

    Culture(int id, String key, float preferredTemperature, float conformity,
            byte[] traitBase, int[] palette, NameGrammar grammar, String displayName) {
        if (traitBase.length != Persona.TRAIT_COUNT) {
            throw new IllegalArgumentException(
                    key + " needs exactly " + Persona.TRAIT_COUNT + " trait axes");
        }
        this.id = (byte) id;
        this.key = key;
        this.preferredTemperature = preferredTemperature;
        this.conformity = conformity;
        this.traitBase = traitBase;
        this.palette = palette;
        this.grammar = grammar;
        this.displayName = displayName;
    }

    /**
     * Resolves a persisted culture id.
     *
     * @throws IllegalArgumentException for {@link Persona#UNASSIGNED_CULTURE} or anything else out
     *                                  of range. A persona with no culture is a persona that has
     *                                  not been generated yet, and quietly substituting culture 0
     *                                  would hide that — which is exactly the bug the schema 2 → 3
     *                                  fix exists to make visible.
     */
    public static Culture byId(byte id) {
        if (id < 0 || id >= COUNT) {
            throw new IllegalArgumentException("No culture with id " + id
                    + (id == Persona.UNASSIGNED_CULTURE ? " (that is the unassigned sentinel)" : ""));
        }
        return BY_ID[id];
    }

    public static boolean isAssigned(byte id) {
        return id >= 0 && id < COUNT;
    }

    public byte id() {
        return id;
    }

    /** Stable identifier for translation keys and the debug dump. */
    public String key() {
        return key;
    }

    public String displayName() {
        return displayName;
    }

    /** Biome temperature this culture settles most readily at. Used by {@link Cultures}. */
    public float preferredTemperature() {
        return preferredTemperature;
    }

    /**
     * How tightly individuals cluster around their household, in {@code (0, 1]}.
     *
     * <p>Scales the individual layer of the three-layer roll. 1.0 spends the full ±25 the ledger
     * specifies; 0.6 spends ±15. It only ever narrows, so the ruled bound still holds for every
     * culture.
     */
    public float conformity() {
        return conformity;
    }

    /** Defensive copy — a caller must not be able to edit this culture for everyone. */
    public byte[] traitBase() {
        return traitBase.clone();
    }

    public byte traitBase(int axis) {
        return traitBase[axis];
    }

    /** Presentation only. {@code DESIGN.md} §9: clothing is tinted, not drawn. */
    public int[] palette() {
        return palette.clone();
    }

    public NameGrammar grammar() {
        return grammar;
    }
}
