package net.namesake.config;

import net.namesake.Namesake;
import net.namesake.platform.Platform;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * <b>The first config this project has had, at session 15, and every decision in it is about what a
 * config may <i>not</i> do.</b>
 *
 * <h2>Is a config file a save? No — and the reason is not that it is small</h2>
 *
 * <p>Hard rule 1 says never ship a persisted schema change without a datafixer and a load test. A
 * config file <i>is</i> state on disk that a later build reads, so the question is real and it is
 * answered rather than assumed: <b>it is not a save, because there is no reading of an old one that
 * produces a wrong value — only a defaulted one.</b>
 *
 * <p>That is a property, not a hope, and it rests on exactly three rules which
 * {@code ConfigTest} holds:
 *
 * <ol>
 *   <li><b>A missing key takes its default.</b> So a file written by any earlier build loads.</li>
 *   <li><b>An unknown key is ignored.</b> So a file written by any <i>later</i> build loads.</li>
 *   <li><b>A malformed value warns and takes its default.</b> So a typo costs a log line rather than
 *       a crash on somebody's server.</li>
 * </ol>
 *
 * <p>Together those make the file <b>version-free by construction</b>: there is no
 * {@code configVersion} key, no fixer ladder and no {@code NpcSchema} involvement, because there is
 * no state transition to get wrong. Contrast the thing that genuinely needed the ladder: a schema-7
 * packed ring slot read as a schema-8 one is every field after the first two shifted by four bytes —
 * *silently wrong bytes*, which no default can rescue. A config key is a name.
 *
 * <p><b>And the one clause that would turn this back into a save is guarded rather than trusted:</b>
 * a config value must never decide how a persisted byte is <i>interpreted</i>. The moment it does,
 * a save written under one config becomes unreadable under another and hard rule 1 applies in full.
 * {@code NeverCutTest.theSchemaLayerCannotSeeTheConfig} reads the bytecode of every class in
 * {@code net.namesake.npc} and fails if one of them mentions this package.
 *
 * <h2>Can a config value be rule 5's named non-display consumer? No, and the instrument is the
 * wrong shape for saying so</h2>
 *
 * <p>The tempting move is to add {@code net.namesake.config} to {@code SocialValueLedgerTest}'s
 * {@code DISPLAY_PACKAGES}. It is wrong: that set's own javadoc says <i>"rule 5 is precisely about
 * values that terminate in something that shows them to a person"</i>, and a config file shows
 * nothing to anybody. It is read, not written to.
 *
 * <p>The real answer is a category the ledger did not have. <b>A config value is never a consumer
 * because it is never the <i>subject</i> of the comparison — it is the operand.</b> Rule 5 asks for
 * the {@code if} statement a field feeds. In {@code if (bond.trust() >= Residency.TRUST_THRESHOLD)},
 * trust's consumer is the method containing that {@code if}; the threshold is the constant on the
 * right of it, and swapping a literal for a config lookup changes which number is compared and
 * nothing about what is doing the comparing. So a config method can never pay an exemption, for a
 * different reason than a renderer can never pay one, and folding the two into one set would make
 * that set's javadoc false. {@code SocialValueLedgerTest.OPERAND_PACKAGES} is the second set.
 *
 * <p><b>The failure mode this does leave open is named and closed separately:</b> a field whose only
 * consumer is a mechanic a config can switch off. That is why <b>every gate here defaults to on</b>
 * and {@code ConfigTest.everyGateDefaultsOn} holds it — {@code DESIGN.md} §2 rules content gating
 * <i>"all on, with a documented gentle preset"</i>, and a rule 5 consumer that is unreachable out of
 * the box is a consumer in name only.
 *
 * <h2>What may not become a key, and how that is enforced</h2>
 *
 * <p>{@code WORKPLAN.md}'s never-cut list is seven walls long and every one of them looks exactly
 * like a tuning knob. Session 13 built {@code DayPlanTest.theSpreadFloorIsNotConfigurable} to hold
 * one of them, and it reads {@code DayPlan}'s own {@code <clinit>} for four JDK property doors —
 * <b>which cannot see a config file read from anywhere else at all.</b> A
 * {@code Config.get().spread()} called from that same initialiser records as
 * {@code net/namesake/config/Config#spread} and passes every assertion in it.
 *
 * <p>So the guard is generalised at the level the claim actually holds:
 * {@code NeverCutTest.everyWallIsACompileTimeConstant} asserts each wall is a {@code static final}
 * primitive carrying a {@code ConstantValue} attribute in the class file. javac inlines such a field
 * at every use site, so <b>there is no runtime read to redirect</b> — no matter where somebody
 * writes the redirect. Making one configurable means removing {@code final} or making the
 * initialiser non-constant, and either turns the test red naming the wall.
 *
 * <h2>Why a hand-rolled properties file rather than either loader's config system</h2>
 *
 * <p>NeoForge has {@code ModConfigSpec}; Fabric has nothing, so a loader-native answer is two
 * implementations of one file format that must agree, which is the shape this document has ruled
 * against six times. One file, read once, through the one seam the two loaders genuinely disagree
 * about ({@link Platform#configDir()}).
 *
 * <p>{@code .properties} rather than JSON for one reason that is not taste: <b>a documented preset
 * has to be documented in the file</b>, and JSON cannot carry a comment. The template below is the
 * documentation, and it is written only when the file is absent — an existing file is never
 * rewritten, so nothing this mod does can lose an operator's comments or reorder their keys.
 */
public final class Config {

    /** The file, in the loader's own config directory. */
    public static final String FILE_NAME = "namesake.properties";

    /**
     * The key naming a preset. {@code default} or {@code gentle}.
     *
     * <p>A preset supplies <i>defaults</i>; any key written explicitly beside it still wins. That
     * ordering is the whole reason a preset is worth having — an operator can say "gentle, except
     * leave the roads on" in two lines rather than by knowing every key.
     *
     * <p><b>Which is exactly why the shipped template writes every other key COMMENTED OUT, and that
     * is a bug fix rather than a style choice.</b> The template's first version wrote all six live at
     * their defaults — so {@code preset = gentle} was read, applied, and then overridden key by key
     * by the very file that was documenting it. <b>Setting the preset did nothing at all, and it did
     * it silently</b>, which is the worst available outcome for the one setting whose whole job is to
     * be the easy one.
     *
     * <p>Both behaviours are correct on their own and every unit test passed; what nobody had asked
     * was what the <i>shipped artefact</i> does when a person edits one line of it. Found at session
     * 15's close by editing the real file the way a server owner would, and held now by
     * {@code ConfigTest.theShippedTemplateDoesNotDefeatItsOwnPreset}.
     */
    public static final String KEY_PRESET = "preset";

    /** Everything this mod may place in a world, and everything it may take away from one. */
    public static final String KEY_ROADS = "world.roads";
    public static final String KEY_NOTICE_BOARD = "world.noticeBoard";

    /** The day plan's one switchable behaviour. Session 14 asked for this by name. */
    public static final String KEY_NIGHT_WATCH = "village.nightWatch";

    /** {@code DESIGN.md} §2's content gating: the two harsh edges that exist today. */
    public static final String KEY_PRICE_MARKUP = "social.priceMarkup";
    public static final String KEY_HARM_TRAVELS = "social.harmTravels";

    /** Every key this build understands, in the order the template writes them. */
    public static final List<String> KEYS = List.of(
            KEY_PRESET, KEY_ROADS, KEY_NOTICE_BOARD, KEY_NIGHT_WATCH,
            KEY_PRICE_MARKUP, KEY_HARM_TRAVELS);

    /**
     * The presets. {@code DESIGN.md} §2: <i>content gating, all on, with a documented "gentle"
     * preset</i>.
     *
     * <p><b>What "gentle" gates is the two harsh edges the mod actually has today, and nothing
     * else.</b> It is deliberately not "turn the mod down": roads, the notice board and the night
     * watch are questions about how much of itself this mod puts into somebody's world, which is a
     * different axis from how sharply it treats them, and mixing the two would mean a player who
     * wanted a softer village silently lost the onboarding surface.
     *
     * <ul>
     *   <li><b>{@code social.priceMarkup=false}</b> — standing can lower a price and never raise
     *       one. A mistake stops costing emeralds; the discounts still have to be earned. Note what
     *       it does <i>not</i> do: vanilla's own gossip markup is untouched, because that is
     *       vanilla's and §2 rules we add to {@code updateSpecialPrices} rather than replace it.</li>
     *   <li><b>{@code social.harmTravels=false}</b> — a harmful deed is remembered where it
     *       happened and does not cross a settlement border. The village you hurt still remembers;
     *       the next one down the road never hears. Kindness crosses either way, so the propagation
     *       thesis is intact and §10's acceptance script — which is one gift — runs unchanged.</li>
     * </ul>
     *
     * <p>Neither is a difficulty slider and neither touches a threshold. That matters: a preset that
     * moved {@code Residency.TRUST_THRESHOLD} would make two servers disagree about what a measured
     * number means, and every table in this ledger is written against one.
     */
    public enum Preset {
        DEFAULT("default"),
        GENTLE("gentle");

        private final String key;

        Preset(String key) {
            this.key = key;
        }

        public String key() {
            return key;
        }

        static Preset byKey(String value) {
            for (Preset preset : values()) {
                if (preset.key.equalsIgnoreCase(value)) {
                    return preset;
                }
            }
            return null;
        }
    }

    /**
     * The loaded values.
     *
     * <p>A record rather than a bag of statics so that a test can build one without a disk, and so
     * that {@code ConfigTest} can compare two whole configurations rather than six fields. It
     * declares <b>no {@code Codec}</b>, deliberately: {@code SocialValueLedgerTest} discovers
     * persisted records by looking for one, and this is not persisted state of the mod's — it is an
     * operator's file.
     */
    public record Values(Preset preset, boolean roads, boolean noticeBoard, boolean nightWatch,
                         boolean priceMarkup, boolean harmTravels) {

        /** Everything on. {@code DESIGN.md} §2: <i>all on, with a documented gentle preset</i>. */
        public static Values defaults() {
            return new Values(Preset.DEFAULT, true, true, true, true, true);
        }

        /** The defaults a preset supplies before any explicit key is read over the top. */
        public static Values of(Preset preset) {
            return switch (preset) {
                case DEFAULT -> defaults();
                case GENTLE -> new Values(Preset.GENTLE, true, true, true, false, false);
            };
        }
    }

    private static volatile Values current = Values.defaults();

    private Config() {
    }

    /**
     * What this server is running with. Never null, and correct before any world loads because
     * {@link #load()} runs in {@code Namesake.init}.
     *
     * <p>Read live rather than snapshotted into a constant, for {@code RoadNetwork.materialises}'
     * reason at session 10: a switch that is read fresh can be reloaded later without touching a
     * single read site.
     */
    public static Values get() {
        return current;
    }

    /**
     * Replaces the live configuration. For tests and for a future reload; the game calls
     * {@link #load()}.
     */
    public static void set(Values values) {
        current = values;
    }

    /**
     * Reads the config file, writing the documented template first if there is none.
     *
     * <p>Never throws. A directory that cannot be created, a file that cannot be read and a file
     * full of nonsense all end the same way — <b>defaults, and a line in the log saying so</b> —
     * because the alternative is a mod that refuses to start over a text file, on somebody's server,
     * at the moment they least want to debug one.
     */
    public static void load() {
        Path directory;
        try {
            directory = Platform.get().configDir();
        } catch (RuntimeException e) {
            Namesake.LOGGER.warn("No config directory available; running on defaults", e);
            current = Values.defaults();
            return;
        }
        current = loadFrom(directory.resolve(FILE_NAME));
        describe().forEach(Namesake.LOGGER::info);
    }

    /** {@link #load()}'s body, against an explicit path so a test can hand it a temporary one. */
    public static Values loadFrom(Path file) {
        Properties properties = new Properties();
        try {
            if (!Files.isRegularFile(file)) {
                writeTemplate(file);
                return Values.defaults();
            }
            try (var in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                properties.load(in);
            }
        } catch (IOException | IllegalArgumentException e) {
            Namesake.LOGGER.warn("Could not read {}; running on defaults", file, e);
            return Values.defaults();
        }
        return parse(properties);
    }

    /**
     * Turns a property set into values. <b>The whole of rules 1 to 3 lives here</b>, and it is
     * separated from the file so that every one of them is a unit test rather than a temp directory.
     */
    public static Values parse(Properties properties) {
        Values base = Values.defaults();
        String presetValue = properties.getProperty(KEY_PRESET);
        if (presetValue != null && !presetValue.isBlank()) {
            Preset named = Preset.byKey(presetValue.trim());
            if (named == null) {
                warn(KEY_PRESET, presetValue, "one of " + presetKeys());
            } else {
                base = Values.of(named);
            }
        }

        // An unknown key is ignored rather than refused, and that is rule 2: it is what makes a file
        // written by a LATER build load in an earlier one. It is logged at debug, not warn, because
        // a downgrade is a thing people do on purpose and a wall of warnings would read as damage.
        for (String name : properties.stringPropertyNames()) {
            if (!KEYS.contains(name)) {
                Namesake.LOGGER.debug("Ignoring unknown config key '{}' — this build does not know "
                        + "it. Nothing is rewritten, so a newer build will still read it.", name);
            }
        }

        return new Values(
                base.preset(),
                bool(properties, KEY_ROADS, base.roads()),
                bool(properties, KEY_NOTICE_BOARD, base.noticeBoard()),
                bool(properties, KEY_NIGHT_WATCH, base.nightWatch()),
                bool(properties, KEY_PRICE_MARKUP, base.priceMarkup()),
                bool(properties, KEY_HARM_TRAVELS, base.harmTravels()));
    }

    /** Rules 1 and 3: absent takes the default, unparseable warns and takes the default. */
    private static boolean bool(Properties properties, String key, boolean fallback) {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        String trimmed = value.trim();
        if (trimmed.equalsIgnoreCase("true")) {
            return true;
        }
        if (trimmed.equalsIgnoreCase("false")) {
            return false;
        }
        // Deliberately strict about what counts as a boolean. Boolean.parseBoolean answers false to
        // "yes", "on" and "1", which is a silent wrong answer where this is a visible one.
        warn(key, value, "true or false");
        return fallback;
    }

    private static void warn(String key, String value, String expected) {
        Namesake.LOGGER.warn("Config key '{}' is '{}', which is not {}. Using the default.",
                key, value.trim(), expected);
    }

    private static String presetKeys() {
        List<String> keys = new ArrayList<>();
        for (Preset preset : Preset.values()) {
            keys.add(preset.key());
        }
        return String.join(", ", keys);
    }

    /**
     * What this server is actually running, as log lines.
     *
     * <p>Only what differs from the defaults, plus a preset if one is named — because a config
     * summary nobody reads is a config summary that is wrong, and six lines of "true" on every
     * startup is how it gets there.
     */
    public static List<String> describe() {
        Values values = current;
        Values reference = Values.defaults();
        Map<String, String> changed = new LinkedHashMap<>();
        if (values.preset() != Preset.DEFAULT) {
            changed.put(KEY_PRESET, values.preset().key());
        }
        if (values.roads() != reference.roads()) {
            changed.put(KEY_ROADS, String.valueOf(values.roads()));
        }
        if (values.noticeBoard() != reference.noticeBoard()) {
            changed.put(KEY_NOTICE_BOARD, String.valueOf(values.noticeBoard()));
        }
        if (values.nightWatch() != reference.nightWatch()) {
            changed.put(KEY_NIGHT_WATCH, String.valueOf(values.nightWatch()));
        }
        if (values.priceMarkup() != reference.priceMarkup()) {
            changed.put(KEY_PRICE_MARKUP, String.valueOf(values.priceMarkup()));
        }
        if (values.harmTravels() != reference.harmTravels()) {
            changed.put(KEY_HARM_TRAVELS, String.valueOf(values.harmTravels()));
        }
        if (changed.isEmpty()) {
            return List.of("Config: everything on (" + FILE_NAME + " is at its defaults)");
        }
        List<String> lines = new ArrayList<>();
        lines.add("Config: " + changed.size() + " setting(s) away from the defaults");
        changed.forEach((key, value) -> lines.add("  " + key + " = " + value));
        return lines;
    }

    /**
     * Writes the commented template, once, when there is no file.
     *
     * <p><b>Never rewrites an existing one</b>, which is what keeps an operator's comments and key
     * order theirs. A build that adds a key therefore does not add it to files already on disk —
     * correctly: rule 1 means the absent key already reads as its default, and the alternative is
     * this mod editing a file a person wrote.
     */
    private static void writeTemplate(Path file) throws IOException {
        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(file, template(), StandardCharsets.UTF_8);
        Namesake.LOGGER.info("Wrote a default {} — every setting is on. See the comments in it for "
                + "the 'gentle' preset.", file);
    }

    /** The file as shipped. Public so a test can assert every key it documents is a real one. */
    public static String template() {
        return String.join("\n",
                "# Namesake — server configuration.",
                "#",
                "# Every setting below is COMMENTED OUT and shown at its default. Uncomment a line to",
                "# change it. They are commented rather than live for a reason worth knowing: a key",
                "# written explicitly OVERRIDES the preset, so a file that listed all six would make",
                "# 'preset = gentle' do nothing at all.",
                "#",
                "# Every setting here is a BOOLEAN: true or false. Nothing else is accepted, and a",
                "# value that is neither logs a warning and uses the default rather than failing to",
                "# start. A key you delete takes its default; a key this build does not recognise is",
                "# ignored and left alone, so this file is safe to carry between mod versions in",
                "# either direction. There is deliberately no version number in it.",
                "#",
                "# This file is written once, when it is missing. Namesake never rewrites it, so",
                "# your comments and your key order stay yours.",
                "",
                "# ---------------------------------------------------------------------------------",
                "# preset — shorthand for a group of the settings below. Anything you write",
                "# explicitly still wins, so \"gentle, but leave the prices alone\" is two lines.",
                "#",
                "#   default   everything on. This is what the mod is designed and measured against.",
                "#   gentle    " + Preset.GENTLE.key() + ": " + KEY_PRICE_MARKUP + "=false and "
                        + KEY_HARM_TRAVELS + "=false.",
                "#             Villagers can still like you enough to give you a discount; they can",
                "#             no longer charge you MORE for something you did. And a harmful deed",
                "#             stays in the village it happened in instead of travelling down the",
                "#             road ahead of you. Kindness still travels either way.",
                "# ---------------------------------------------------------------------------------",
                KEY_PRESET + " = " + Preset.DEFAULT.key(),
                "",
                "# ---------------------------------------------------------------------------------",
                "# world — what Namesake is allowed to put into your world. Both of these place real",
                "# blocks, and neither is undone if you switch it off later: a laid block is a laid",
                "# block. Nothing about villagers remembering you depends on either.",
                "# ---------------------------------------------------------------------------------",
                "",
                "# Lay dirt paths between villages that are near each other, as you travel between",
                "# them. Never above ground level, never over anything you built, loaded chunks only.",
                "# The road NETWORK is arithmetic and is unaffected — gossip crosses the graph, not",
                "# the blocks — so turning this off changes what you see and not what villagers know.",
                "# " + KEY_ROADS + " = true",
                "",
                "# Stand a lectern beside a village's bell if there is not one within the village",
                "# already. That lectern is the Notice Board: right-click an empty one with an empty",
                "# hand and it tells you what the village knows about you. It is the only place the",
                "# mod explains itself, so switching this off means a new player has to know to",
                "# place their own. One consequence worth knowing: a lectern is a librarian's",
                "# workstation, so a village that had no library may gain a librarian.",
                "# " + KEY_NOTICE_BOARD + " = true",
                "",
                "# ---------------------------------------------------------------------------------",
                "# village — how villagers spend their day.",
                "# ---------------------------------------------------------------------------------",
                "",
                "# One villager in four stays out at the meeting point from dusk until dawn instead",
                "# of going to bed. Set this to false and everybody sleeps. Two things follow if you",
                "# leave it on: those villagers do not contribute to iron golem spawning while they",
                "# are awake, and your village looks occupied at midnight.",
                "# " + KEY_NIGHT_WATCH + " = true",
                "",
                "# ---------------------------------------------------------------------------------",
                "# social — how sharply villagers react to you. This is the group the 'gentle' preset",
                "# is about.",
                "# ---------------------------------------------------------------------------------",
                "",
                "# Let standing raise a price as well as lower one. With this on, a villager who has",
                "# watched you hurt someone charges you more; with it off, standing can only ever",
                "# give a discount. Vanilla's own reputation pricing is untouched either way.",
                "# " + KEY_PRICE_MARKUP + " = true",
                "",
                "# Let a harmful deed cross a village border. With this off, what you did is",
                "# remembered where you did it and the next village down the road never hears about",
                "# it. Kindness travels regardless, so villages still recognise your name for the",
                "# right reasons.",
                "# " + KEY_HARM_TRAVELS + " = true",
                "");
    }

}
