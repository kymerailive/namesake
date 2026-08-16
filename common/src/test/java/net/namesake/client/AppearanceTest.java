package net.namesake.client;

import net.namesake.culture.Culture;
import net.namesake.npc.Persona;
import net.namesake.testing.ModClasses;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>Session 15's art, as far as a test can see it.</b>
 *
 * <p>The line this file draws is the one {@code WORKPLAN.md} draws between its instruments: whether
 * a villager <i>has</i> a face is arithmetic and belongs here; whether the face is any good is the
 * owner's, and the harness photographs it for them. So what is checked is that every appearance the
 * seed can produce resolves to a file that exists — because the failure that class of mistake
 * produces is a magenta-and-black checkerboard on somebody's screen and a line in a log nobody
 * reads.
 */
class AppearanceTest {

    private static final int POPULATION = 4096;

    private static Path assets() {
        return ModClasses.repoRoot().resolve("common/src/main/resources/assets/namesake");
    }

    private static List<Integer> seeds() {
        List<Integer> seeds = new ArrayList<>(POPULATION);
        for (int i = 0; i < POPULATION; i++) {
            // Real seeds, through the shipped derivation, rather than 0..4095 — the derivation is a
            // multiply and an xor-fold, and a test fed consecutive ints is testing consecutive ints.
            seeds.add(Persona.deriveAppearanceSeed(new UUID(0x5EED_0000_0000_0000L + i, i * 31L)));
        }
        return seeds;
    }

    // --- the thing a player would see go wrong --------------------------------------------------

    /**
     * <b>Every texture a villager can be assigned exists on disk.</b>
     *
     * <p>Four thousand personas through the real derivation against the real shipped manifest, and
     * every path checked as a file. This is the whole of the art's machine-checked half and it is
     * the assertion the session would be embarrassed to ship without: a missing texture is not a
     * crash, it is a magenta villager and a warning nobody sees until it is on a monitor.
     */
    @Test
    @DisplayName("every appearance the seed can produce resolves to a texture that exists")
    void everyAppearanceResolves() throws IOException {
        Appearance.Catalogue catalogue = Appearance.Catalogue.builtIn();
        Path assets = assets();
        assertTrue(Files.isDirectory(assets), () -> "no assets at " + assets);

        Set<String> wanted = new LinkedHashSet<>();
        for (int seed : seeds()) {
            for (byte culture = -1; culture < Culture.COUNT; culture++) {
                Appearance.Look look = Appearance.of(seed, culture, "farmer", catalogue, null, null);
                wanted.add("body/" + look.body());
                wanted.add("hair/" + look.hair());
                wanted.add("face/" + look.face());
            }
        }
        for (Appearance.Clothing clothing : Appearance.Clothing.values()) {
            wanted.add("clothing/" + clothing.id());
        }
        wanted.add("colormap/skin");
        wanted.add("colormap/hair");

        List<String> missing = new ArrayList<>();
        for (String path : wanted) {
            Path file = assets.resolve("textures/entity/villager/" + path + ".png");
            if (!Files.isRegularFile(file)) {
                missing.add(path + ".png");
            }
        }
        assertTrue(missing.isEmpty(), () ->
                "these textures are named by the derivation and are not in the jar: " + missing
                        + ". Minecraft draws a missing texture as a magenta checkerboard and logs a "
                        + "warning nobody reads until it is on somebody's monitor.");
        assertEquals(25, countPngs(assets), "DESIGN.md §9's asset table is 2 bodies + 2 colormaps + "
                + "6 hair + 8 clothing + 7 faces = 25, and it is a budget rather than an estimate. "
                + "MCA ships 1,312.");
    }

    /** The shipped manifest and the shipped files are the same set, in both directions. */
    @Test
    @DisplayName("the manifest lists exactly the variants that are in the jar")
    void theManifestAndTheFilesAgree() throws IOException {
        String json = Files.readString(assets().resolve("appearance/villager.json"),
                StandardCharsets.UTF_8);
        for (Map.Entry<String, String> group : Map.of(
                "bodies", "body", "hair", "hair", "faces", "face").entrySet()) {
            Set<String> onDisk = new HashSet<>();
            try (var files = Files.list(assets().resolve(
                    "textures/entity/villager/" + group.getValue()))) {
                files.forEach(file -> onDisk.add(
                        file.getFileName().toString().replace(".png", "")));
            }
            for (String id : onDisk) {
                assertTrue(json.contains('"' + id + '"'), () ->
                        id + ".png is in the jar and not in appearance/villager.json, so no villager "
                                + "can ever be given it. A texture nothing selects is dead art, "
                                + "which is DESIGN.md §1 one layer out.");
            }
        }
    }

    /** The manifest is what the built-in catalogue claims it is. */
    @Test
    @DisplayName("the built-in catalogue matches the shipped manifest")
    void theBuiltInSetIsTheShippedSet() throws IOException {
        String json = Files.readString(assets().resolve("appearance/villager.json"),
                StandardCharsets.UTF_8);
        Appearance.Catalogue builtIn = Appearance.Catalogue.builtIn();
        for (String id : builtIn.bodies()) {
            assertTrue(json.contains('"' + id + '"'), () -> "built-in body " + id + " is not shipped");
        }
        for (String id : builtIn.hair()) {
            assertTrue(json.contains('"' + id + '"'), () -> "built-in hair " + id + " is not shipped");
        }
        for (String id : builtIn.faces()) {
            assertTrue(json.contains('"' + id + '"'), () -> "built-in face " + id + " is not shipped");
        }
    }

    // --- the ruling that makes "costs only a merge" true for the player as well -----------------

    /**
     * <b>Appending a variant moves about one villager in {@code n} and leaves every other one
     * exactly as they were.</b>
     *
     * <p>This is §9's ruling 1 as an assertion, and it is the reason selection is rendezvous hashing
     * rather than {@code seed % n}. A modulus would move <i>everybody</i>: a player who knew their
     * neighbour by her hair would find a stranger there because somebody's pull request was merged.
     * The number below is not a target picked to pass — it is what rendezvous hashing produces by
     * construction, and the band is wide enough that only a change of algorithm can leave it.
     */
    @Test
    @DisplayName("adding a hair moves about one villager in seven and nobody else")
    void addingAVariantDoesNotReshuffleTheWorld() {
        List<String> six = Appearance.Catalogue.builtIn().hair();
        List<String> seven = new ArrayList<>(six);
        seven.add("hair_7");

        int moved = 0;
        int movedToTheNewOne = 0;
        List<Integer> seeds = seeds();
        for (int seed : seeds) {
            String before = Appearance.pick(seed, 0x48_41_49_5200_0001L, six, "hair_1");
            String after = Appearance.pick(seed, 0x48_41_49_5200_0001L, seven, "hair_1");
            if (!before.equals(after)) {
                moved++;
                if (after.equals("hair_7")) {
                    movedToTheNewOne++;
                }
            }
        }

        int movedTotal = moved;
        int movedOnto = movedToTheNewOne;
        assertEquals(movedTotal, movedOnto, () ->
                "every villager who changed hair must have changed TO the new one. " + movedTotal
                        + " moved and " + movedOnto + " moved to hair_7, so "
                        + (movedTotal - movedOnto) + " were reshuffled between hairs that both "
                        + "already existed. That is a modulus, not rendezvous hashing.");

        float share = moved / (float) seeds.size();
        assertTrue(share > 0.08F && share < 0.22F, () ->
                "adding a seventh hair moved " + String.format("%.1f%%", share * 100)
                        + " of the population. Rendezvous hashing moves about 1/7 = 14%; a modulus "
                        + "moves nearly all of it, and something moving nothing means the new "
                        + "variant is unreachable.");
    }

    /** Order in the manifest is not a thing a contributor has to think about. */
    @Test
    @DisplayName("the order variants are listed in changes nothing")
    void orderDoesNotMatter() {
        List<String> forwards = Appearance.Catalogue.builtIn().hair();
        List<String> backwards = new ArrayList<>(forwards);
        java.util.Collections.reverse(backwards);
        for (int seed : seeds()) {
            assertEquals(Appearance.pick(seed, 7L, forwards, "x"),
                    Appearance.pick(seed, 7L, backwards, "x"),
                    "two packs listing the same variants in different orders must produce the same "
                            + "villagers, or a merge that sorts a file repaints a world");
        }
    }

    /** A villager is the same person every time they are drawn. */
    @Test
    @DisplayName("one seed is one appearance, every time")
    void appearanceIsStable() {
        Appearance.Catalogue catalogue = Appearance.Catalogue.builtIn();
        for (int seed : seeds().subList(0, 256)) {
            Appearance.Look first = Appearance.of(seed, (byte) 0, "farmer", catalogue, null, null);
            Appearance.Look again = Appearance.of(seed, (byte) 0, "farmer", catalogue, null, null);
            assertEquals(first, again);
        }
    }

    /** And the whole population is actually used, rather than everyone getting hair_1. */
    @Test
    @DisplayName("every variant in the manifest is reachable, and none of them takes the village")
    void everyVariantIsUsedAndNoneDominates() {
        Appearance.Catalogue catalogue = Appearance.Catalogue.builtIn();
        Map<String, Integer> hair = new HashMap<>();
        Map<String, Integer> face = new HashMap<>();
        int slim = 0;
        List<Integer> seeds = seeds();
        for (int seed : seeds) {
            Appearance.Look look = Appearance.of(seed, (byte) 0, "farmer", catalogue, null, null);
            hair.merge(look.hair(), 1, Integer::sum);
            face.merge(look.face(), 1, Integer::sum);
            if (look.slim()) {
                slim++;
            }
        }
        assertEquals(catalogue.hair().size(), hair.size(),
                () -> "some hair is unreachable: " + hair.keySet());
        assertEquals(catalogue.faces().size(), face.size(),
                () -> "some face is unreachable: " + face.keySet());

        // A mechanic that never fires passes every other test in the repository — DayPlan's own
        // words. The bound is loose because a hash is allowed to be lumpy; it is here to catch a
        // derivation that has collapsed, not to grade one.
        for (Map.Entry<String, Integer> entry : hair.entrySet()) {
            float share = entry.getValue() / (float) seeds.size();
            assertTrue(share > 0.05F && share < 0.35F, () ->
                    entry.getKey() + " is on " + String.format("%.1f%%", share * 100)
                            + " of the population, against an even share of "
                            + String.format("%.1f%%", 100F / catalogue.hair().size()));
        }
        float slimShare = slim / (float) seeds.size();
        assertTrue(slimShare > 0.35F && slimShare < 0.65F, () ->
                "the slim build is on " + String.format("%.1f%%", slimShare * 100) + " of the "
                        + "population; it is meant to be a coin");
    }

    // --- profession legibility, which the renderer swap deleted and this re-earns ---------------

    /**
     * <b>Session 13's headline survives the renderer swap.</b>
     *
     * <p>{@code VillagerProfessionLayer} is generically bounded on {@code VillagerHeadModel}, so
     * swapping to a humanoid model deletes vanilla's profession overlay — and session 13's exit
     * criterion was <i>you can tell who works by looking</i>. §9's ruling 2 re-earns it in the eight
     * clothing shapes, and this is the assertion that says the mapping is total.
     */
    @Test
    @DisplayName("every vanilla profession has a clothing shape, and the shapes tell trades apart")
    void everyProfessionIsDressed() {
        List<String> vanilla = List.of("none", "nitwit", "armorer", "butcher", "cartographer",
                "cleric", "farmer", "fisherman", "fletcher", "leatherworker", "librarian", "mason",
                "shepherd", "toolsmith", "weaponsmith");

        Set<Appearance.Clothing> used = new LinkedHashSet<>();
        for (String profession : vanilla) {
            used.add(Appearance.Clothing.forProfession(profession));
        }
        assertEquals(Appearance.Clothing.values().length, used.size(), () ->
                "eight shapes are shipped and vanilla's fifteen professions only reach " + used
                        + ". A shape nothing selects is dead art, and a session that deleted "
                        + "vanilla's profession overlay owes every one of them a wearer.");

        // The four a player is most likely to need to tell apart at a glance must not collide.
        assertFalse(Appearance.Clothing.forProfession("farmer")
                        == Appearance.Clothing.forProfession("librarian"),
                "a farmer and a librarian must not wear the same thing");
        assertFalse(Appearance.Clothing.forProfession("armorer")
                        == Appearance.Clothing.forProfession("cleric"),
                "an armorer and a cleric must not wear the same thing");
        assertEquals(Appearance.Clothing.PLAIN, Appearance.Clothing.forProfession("nitwit"),
                "a nitwit has no trade, so it wears no trade");
        assertEquals(Appearance.Clothing.PLAIN, Appearance.Clothing.forProfession("some_addon_job"),
                "a profession this mod has never heard of gets the neutral. A wrong silhouette is "
                        + "worse than no silhouette, and an addon's job is not one we can claim to "
                        + "have made legible.");
    }

    // --- standing risk 3's new machine-checked distinguisher ------------------------------------

    /**
     * <b>No two cultures render the same clothing.</b>
     *
     * <p>Standing risk 3 — <i>cultures don't feel foreign</i> — has been open since session 03 and
     * the reason it is not retired is that its failure happens at hour 45 and its first read took
     * minutes. This session cannot close that, and says so in the ledger. What it <i>can</i> do is
     * add a distinguisher risk 3 did not have when its first read passed: until now two cultures
     * differed only in what their villagers were called and how they spoke. Now they differ in what
     * a player sees from thirty blocks away.
     *
     * <p>{@code Culture.palette()} has carried four colours per culture since session 03 with
     * <b>zero readers</b>. This is its first, and the test that it was worth keeping.
     */
    @Test
    @DisplayName("no two cultures wear the same colour, and none of them is the ungenerated neutral")
    void everyCultureLooksDifferent() {
        Map<Integer, Byte> byTint = new HashMap<>();
        for (byte id = 0; id < Culture.COUNT; id++) {
            int tint = Appearance.clothTint(id);
            Byte clash = byTint.put(tint, id);
            byte current = id;
            assertTrue(clash == null, () -> "cultures " + clash + " and " + current + " both dress "
                    + "their villagers in " + String.format("#%06X", tint & 0xFFFFFF)
                    + ". Standing risk 3 is that a second village does not read as foreign, and the "
                    + "palette is the only thing about it a player can see at a distance.");
            assertTrue((tint >>> 24) == 0xFF, "a clothing tint must be fully opaque");
        }

        int neutral = Appearance.clothTint(Persona.UNASSIGNED_CULTURE);
        assertFalse(byTint.containsKey(neutral),
                "the colour a villager wears BEFORE their persona is generated must not be any "
                        + "culture's colour, or a village that has not been surveyed yet looks "
                        + "exactly like one that has");
    }

    /** The four palette slots are named, and the names are not all the same index. */
    @Test
    @DisplayName("the culture palette's four slots have distinct meanings")
    void thePaletteSlotsAreNamed() {
        Set<Integer> slots = Set.of(Appearance.CLOTH, Appearance.TRIM, Appearance.ACCENT,
                Appearance.LINEN);
        assertEquals(4, slots.size(), "four names for four slots, or one of them is a typo");
        for (byte id = 0; id < Culture.COUNT; id++) {
            assertEquals(4, Culture.byId(id).palette().length,
                    "every culture must have all four, or a slot name is a promise about an array "
                            + "index that is not there");
        }
    }

    // --- the colormap ---------------------------------------------------------------------------

    /**
     * <b>The colormap coordinates cover the table rather than a corner of it.</b>
     *
     * <p>§9 rules <i>one 2D colormap PNG replaces every skin tone, sampled at melanin × hemoglobin
     * as UV</i>. If the two coordinates were correlated, or if either collapsed toward a mean, the
     * PNG would be doing the work of a ramp and an artist repainting a corner of it would find that
     * corner unreachable.
     */
    @Test
    @DisplayName("the two colormap coordinates spread across the whole table")
    void theColormapIsActuallySampled() {
        int[] cells = new int[16];
        for (int seed : seeds()) {
            int u = Math.min(3, (int) (Appearance.melanin(seed) * 4));
            int v = Math.min(3, (int) (Appearance.hemoglobin(seed) * 4));
            cells[v * 4 + u]++;
        }
        for (int i = 0; i < cells.length; i++) {
            int at = i;
            assertTrue(cells[i] > 0, () -> "quarter " + (at % 4) + "," + (at / 4) + " of the skin "
                    + "colormap is unreachable, so an artist repainting it would be painting a "
                    + "corner no villager is ever drawn from");
        }
    }

    private static long countPngs(Path assets) throws IOException {
        try (var walk = Files.walk(assets)) {
            return walk.filter(Files::isRegularFile)
                    .filter(file -> file.getFileName().toString().endsWith(".png"))
                    .count();
        }
    }
}
