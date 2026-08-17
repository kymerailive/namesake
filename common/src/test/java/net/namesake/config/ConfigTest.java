package net.namesake.config;

import net.namesake.social.Standing;
import net.namesake.social.Trading;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>The three rules that make a config file not a save, plus the one that keeps rule 5 honest.</b>
 *
 * <p>{@code Config}'s javadoc argues that hard rule 1 does not apply to it because there is no
 * reading of an old file that produces a <i>wrong</i> value, only a defaulted one. That is an
 * argument about behaviour, so it is a test rather than a paragraph — the paragraph is where the
 * reasoning goes and this is where the claim lives.
 */
class ConfigTest {

    @AfterEach
    void restoreTheDefaults() {
        // The config is process-wide state. Every test here that touches it puts it back, because a
        // test that leaves priceMarkup off would silently soften TradingTest's assertions.
        Config.set(Config.Values.defaults());
    }

    // --- rule 1: a missing key takes its default -----------------------------------------------

    /**
     * A file written by <b>any earlier build</b> loads, because every key it has never heard of is
     * one it does not have.
     */
    @Test
    @DisplayName("an empty file is the defaults, key for key")
    void aMissingKeyTakesItsDefault() {
        assertEquals(Config.Values.defaults(), Config.parse(new Properties()),
                "an empty config must be exactly the defaults. Anything else means a key without a "
                        + "default, which is a file an earlier build's user cannot load.");
    }

    /** And one key present does not disturb the others. */
    @Test
    @DisplayName("one key set leaves every other key at its default")
    void oneKeyDoesNotMoveTheRest() {
        Properties one = new Properties();
        one.setProperty(Config.KEY_ROADS, "false");
        Config.Values values = Config.parse(one);

        assertFalse(values.roads(), "the key that was set must be honoured");
        Config.Values reference = Config.Values.defaults();
        assertEquals(reference.noticeBoard(), values.noticeBoard());
        assertEquals(reference.nightWatch(), values.nightWatch());
        assertEquals(reference.priceMarkup(), values.priceMarkup());
        assertEquals(reference.harmTravels(), values.harmTravels());
    }

    // --- rule 2: an unknown key is ignored -----------------------------------------------------

    /**
     * A file written by <b>any later build</b> loads too, which is the half people forget. A server
     * owner who rolls a mod back should not be met with a crash about a key from the future.
     */
    @Test
    @DisplayName("a key from a future build is ignored rather than refused")
    void anUnknownKeyIsIgnored() {
        Properties future = new Properties();
        future.setProperty("social.grievanceEscalation", "false");
        future.setProperty("era.ladder", "3");
        future.setProperty(Config.KEY_NIGHT_WATCH, "false");

        Config.Values values = Config.parse(future);
        assertFalse(values.nightWatch(), "the keys this build does know must still be read");
        assertEquals(Config.Values.defaults().roads(), values.roads(),
                "and the unknown ones must change nothing");
    }

    // --- rule 3: a malformed value warns and defaults -------------------------------------------

    /**
     * A typo costs a log line, never a startup. Note what is <i>not</i> accepted:
     * {@code Boolean.parseBoolean} answers {@code false} to "yes", "on" and "1", which is a silent
     * wrong answer where this is a visible one.
     */
    @Test
    @DisplayName("a value that is not true or false takes the default rather than throwing")
    void aMalformedValueTakesItsDefault() {
        for (String nonsense : new String[]{"yes", "on", "1", "", "  ", "TRUEish", "0"}) {
            Properties bad = new Properties();
            bad.setProperty(Config.KEY_PRICE_MARKUP, nonsense);
            Config.Values values = Config.parse(bad);
            assertTrue(values.priceMarkup(), () ->
                    "'" + nonsense + "' is not a boolean, so social.priceMarkup must fall back to "
                            + "its default of true rather than to false. A setting that silently "
                            + "reads as OFF because somebody wrote 'yes' is worse than one that "
                            + "warns.");
        }
    }

    /** Case and surrounding whitespace are not a typo. */
    @Test
    @DisplayName("TRUE, False and a padded value are all read")
    void caseAndPaddingAreForgiven() {
        Properties mixed = new Properties();
        mixed.setProperty(Config.KEY_ROADS, "  FALSE ");
        mixed.setProperty(Config.KEY_NOTICE_BOARD, "TRUE");
        Config.Values values = Config.parse(mixed);
        assertFalse(values.roads());
        assertTrue(values.noticeBoard());
    }

    /** An unknown preset is a warning and the defaults, not a refusal. */
    @Test
    @DisplayName("an unknown preset name falls back to everything on")
    void anUnknownPresetIsTheDefaults() {
        Properties bad = new Properties();
        bad.setProperty(Config.KEY_PRESET, "brutal");
        assertEquals(Config.Values.defaults(), Config.parse(bad));
    }

    // --- rule 5's half: every gate is reachable out of the box ----------------------------------

    /**
     * <b>{@code DESIGN.md} §2: content gating, all on, with a documented gentle preset.</b>
     *
     * <p>This is not tidiness. Rule 5 asks every persisted social value to name a non-display
     * consumer, and a consumer that is switched off in the shipped configuration is a consumer in
     * name only — the failure mode {@code Config}'s javadoc names as the one thing a config
     * genuinely could do to the rule. So the defaults are all-on and it is a build failure to make
     * one of them off.
     */
    @Test
    @DisplayName("every gate defaults on, so no rule 5 consumer is unreachable out of the box")
    void everyGateDefaultsOn() {
        Config.Values defaults = Config.Values.defaults();
        assertTrue(defaults.roads(), "world.roads");
        assertTrue(defaults.noticeBoard(), "world.noticeBoard");
        assertTrue(defaults.nightWatch(), "village.nightWatch");
        assertTrue(defaults.priceMarkup(), "social.priceMarkup");
        assertTrue(defaults.harmTravels(), "social.harmTravels");
        assertEquals(Config.Preset.DEFAULT, defaults.preset());
    }

    // --- the preset -----------------------------------------------------------------------------

    /** What "gentle" gates, asserted rather than described. */
    @Test
    @DisplayName("gentle turns off the price markup and the harm border, and nothing else")
    void gentleGatesExactlyWhatItSays() {
        Properties gentle = new Properties();
        gentle.setProperty(Config.KEY_PRESET, "gentle");
        Config.Values values = Config.parse(gentle);

        assertFalse(values.priceMarkup(), "gentle must stop a standing raising a price");
        assertFalse(values.harmTravels(), "gentle must keep a harmful deed in the village it "
                + "happened in");

        // And it must NOT be a difficulty slider. The three world/village settings are a different
        // axis — how much of itself the mod puts into somebody's world — and folding them in would
        // mean a player who wanted a softer village silently lost the onboarding surface.
        assertTrue(values.roads(), "gentle must not touch world.roads");
        assertTrue(values.noticeBoard(), "gentle must not take away the Notice Board — DESIGN.md §5 "
                + "rules it the entire onboarding surface, and there is no tutorial behind it");
        assertTrue(values.nightWatch(), "gentle must not touch village.nightWatch");
    }

    /** A preset supplies defaults; an explicit key beside it still wins. */
    @Test
    @DisplayName("a key written beside a preset overrides it")
    void anExplicitKeyBeatsThePreset() {
        Properties both = new Properties();
        both.setProperty(Config.KEY_PRESET, "gentle");
        both.setProperty(Config.KEY_PRICE_MARKUP, "true");
        Config.Values values = Config.parse(both);

        assertTrue(values.priceMarkup(), "'gentle, but leave the prices alone' has to be expressible "
                + "in two lines, or nobody uses a preset");
        assertFalse(values.harmTravels(), "and the rest of the preset still applies");
        assertEquals(Config.Preset.GENTLE, values.preset());
    }

    /** No preset may soften a threshold — see {@code Config.Preset}'s javadoc. */
    @Test
    @DisplayName("no preset moves a measured number")
    void noPresetTouchesAThreshold() {
        for (Config.Preset preset : Config.Preset.values()) {
            Config.set(Config.Values.of(preset));
            assertEquals(28, net.namesake.social.Residency.TRUST_THRESHOLD,
                    "a preset that moved the residency threshold would make two servers disagree "
                            + "about what every measured table in WORKPLAN.md means");
            assertEquals(0.90F, Standing.TRUSTED.priceMultiplier(),
                    "the five ruled multipliers are the five ruled multipliers on every server");
            assertEquals(0.75F, Standing.WARM.priceMultiplier());
        }
    }

    // --- what the gates actually do -------------------------------------------------------------

    /**
     * <b>Verified by effect, at the site that matters.</b> A preset that sets a boolean nothing
     * reads is the shape rule 5 exists to refuse, one layer out.
     */
    @Test
    @DisplayName("with the markup off, a discount survives and a markup becomes 1.00")
    void theMarkupGateReachesThePrice() {
        Config.set(Config.Values.defaults());
        assertEquals(1.35F, Trading.multiplierFor(Standing.RESENTED));
        assertEquals(1.15F, Trading.multiplierFor(Standing.WARY));
        assertEquals(0.90F, Trading.multiplierFor(Standing.TRUSTED));

        Config.set(Config.Values.of(Config.Preset.GENTLE));
        assertEquals(1.00F, Trading.multiplierFor(Standing.RESENTED),
                "gentle must stop a hostile band charging more");
        assertEquals(1.00F, Trading.multiplierFor(Standing.WARY));
        assertEquals(0.90F, Trading.multiplierFor(Standing.TRUSTED),
                "and it must leave every discount exactly where it is — a clamp, not a scale");
        assertEquals(0.75F, Trading.multiplierFor(Standing.WARM));
        assertEquals(1.00F, Trading.multiplierFor(Standing.NEUTRAL));
    }

    /** The border gate is per deed type, and kindness crosses either way. */
    @Test
    @DisplayName("with harm travel off, a killing stops at the border and a gift does not")
    void theHarmGateReachesTheBorder() {
        net.namesake.social.Deed killing = net.namesake.social.Deed.of(
                net.namesake.social.DeedType.KILLED_RESIDENT,
                new java.util.UUID(1, 1), new java.util.UUID(2, 2), 0, 5);
        net.namesake.social.Deed gift = net.namesake.social.Deed.of(
                net.namesake.social.DeedType.FED_HUNGRY,
                new java.util.UUID(1, 1), new java.util.UUID(2, 2), 0, 5);

        Config.set(Config.Values.defaults());
        assertTrue(net.namesake.social.Gossip.mayCrossABorder(killing),
                "by default everything travels — that is DESIGN.md §4 step 7 and it is the thesis");
        assertTrue(net.namesake.social.Gossip.mayCrossABorder(gift));

        Config.set(Config.Values.of(Config.Preset.GENTLE));
        assertFalse(net.namesake.social.Gossip.mayCrossABorder(killing),
                "gentle must keep a harmful deed in the village it happened in");
        assertTrue(net.namesake.social.Gossip.mayCrossABorder(gift),
                "and kindness must cross regardless, or DESIGN.md §10's acceptance script — which "
                        + "is one gift — stops working on a gentle server");
    }

    // --- the file ------------------------------------------------------------------------------

    /** A missing file writes the documented template and loads as the defaults. */
    @Test
    @DisplayName("a missing file writes the template and reads as the defaults")
    void aMissingFileWritesTheTemplate(@TempDir Path directory) throws IOException {
        Path file = directory.resolve("nested").resolve(Config.FILE_NAME);
        assertFalse(Files.exists(file));

        assertEquals(Config.Values.defaults(), Config.loadFrom(file));
        assertTrue(Files.isRegularFile(file), "the template must be written where it was expected");

        // And reading the file it just wrote gives the same answer, which is the round trip.
        assertEquals(Config.Values.defaults(), Config.loadFrom(file));
    }

    /** Every key the template documents is a key this build reads, and the reverse. */
    @Test
    @DisplayName("the template documents exactly the keys that exist")
    void theTemplateAndTheKeysAgree() {
        String template = Config.template();
        for (String key : Config.KEYS) {
            assertTrue(template.contains(key), () -> "the template does not mention " + key
                    + ". A key read but not documented is the one nobody finds.");
        }
        // And nothing it documents is a key this build cannot read.
        for (String line : template.split("\n")) {
            String bare = line.startsWith("# ") ? line.substring(2).trim() : line.trim();
            int equals = bare.indexOf('=');
            if (equals <= 0 || bare.startsWith("#") || !bare.substring(0, equals).trim()
                    .matches("[a-zA-Z.]+")) {
                continue;
            }
            String key = bare.substring(0, equals).trim();
            assertTrue(Config.KEYS.contains(key), () ->
                    "the template documents '" + key + "', which this build does not read. A key "
                            + "documented but not read is a promise nothing keeps.");
        }
    }

    /**
     * <b>The shipped template must not override the preset it documents.</b>
     *
     * <p>Found at session 15's close by editing the real file the way a server owner would: the
     * template's first version wrote all six keys <i>live</i> at their defaults, and an explicit key
     * beats a preset — which is a rule this file's own {@link #anExplicitKeyBeatsThePreset} exists to
     * hold. So setting {@code preset = gentle} was read, applied, and then overridden line by line by
     * the very file documenting it. **It did nothing at all, and it did it silently.**
     *
     * <p>The two behaviours are both correct and they compose into a defect, which is the only kind
     * of bug a unit test suite of this shape cannot see: every test here passed. What was never
     * asked was what the <i>shipped artefact</i> does, and this asks exactly that.
     */
    @Test
    @DisplayName("setting the preset in the shipped template actually changes something")
    void theShippedTemplateDoesNotDefeatItsOwnPreset() throws IOException {
        String asShipped = Config.template();
        String edited = asShipped.replace(
                Config.KEY_PRESET + " = " + Config.Preset.DEFAULT.key(),
                Config.KEY_PRESET + " = " + Config.Preset.GENTLE.key());
        assertNotEquals(asShipped, edited, "the template must contain the preset line to edit");

        Properties properties = new Properties();
        properties.load(new java.io.StringReader(edited));
        Config.Values values = Config.parse(properties);

        assertEquals(Config.Values.of(Config.Preset.GENTLE), values, () ->
                "editing one line of the shipped file to 'preset = gentle' produced " + values
                        + " rather than " + Config.Values.of(Config.Preset.GENTLE) + ". Every other "
                        + "key in the template must stay commented out, or the file overrides the "
                        + "preset it is documenting and the easiest setting in the mod does nothing.");
    }

    /** A file this mod never rewrites is a file whose comments stay the operator's. */
    @Test
    @DisplayName("an existing file is read and never rewritten")
    void anExistingFileIsNeverRewritten(@TempDir Path directory) throws IOException {
        Path file = directory.resolve(Config.FILE_NAME);
        String mine = "# my own note, which nothing may eat\n"
                + Config.KEY_NIGHT_WATCH + " = false\n";
        Files.writeString(file, mine, StandardCharsets.UTF_8);

        Config.Values values = Config.loadFrom(file);
        assertFalse(values.nightWatch());
        assertEquals(mine, Files.readString(file, StandardCharsets.UTF_8),
                "the file must come back byte for byte. A mod that rewrites an operator's config to "
                        + "add a key it invented is a mod that eats their comments.");
    }

    /** An unreadable path is defaults and a log line, never a throw. */
    @Test
    @DisplayName("a directory where a file should be is defaults rather than a crash")
    void anUnreadableFileIsTheDefaults(@TempDir Path directory) throws IOException {
        Path file = directory.resolve(Config.FILE_NAME);
        Files.createDirectory(file);
        assertEquals(Config.Values.defaults(), Config.loadFrom(file),
                "a mod that refuses to start over a text file, on somebody's server, is worse than "
                        + "one running on the settings it was designed against");
    }

    /** Values is not a persisted record, and the ledger's own discovery rule is what says so. */
    @Test
    @DisplayName("the config declares no codec, so it is not persisted state of the mod's")
    void theConfigIsNotPersistedState() {
        for (java.lang.reflect.Field field : Config.Values.class.getDeclaredFields()) {
            assertNotEquals("com.mojang.serialization.Codec", field.getType().getName(),
                    "Config.Values must never declare a Codec. SocialValueLedgerTest discovers "
                            + "persisted records by looking for one, and a config that looked "
                            + "persisted would owe rule 5 a consumer for a value that is an operand.");
        }
    }
}
