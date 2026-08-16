package net.namesake.client;

import net.namesake.culture.Culture;
import net.namesake.npc.Persona;

import java.util.ArrayList;
import java.util.List;

/**
 * <b>What one villager looks like, derived from an int.</b> {@code DESIGN.md} §9.
 *
 * <p>Pure arithmetic over {@link Persona#appearanceSeed}, a culture id and a profession key. No
 * Minecraft client type is named here at all, which is what lets every ruling in §9's table be a
 * unit test rather than a screenshot — the renderer is where a mistake costs a run of the game, and
 * this is where the mistakes actually are.
 *
 * <h2>Rendezvous hashing, and why a modulus was not good enough</h2>
 *
 * <p>§9 rules skins <b>datapack-loadable from v1</b>, which means the set of hairs, clothes and
 * faces is data. The obvious selection is {@code seed % variants.size()}, and it has a defect that
 * only shows up on the day somebody uses the feature: <b>appending one hair reshuffles every
 * villager in the world.</b> A player who knew their neighbour by her hair finds a stranger there
 * because a contributor's pull request was merged.
 *
 * <p>So a variant is chosen by hashing the seed against each candidate's <i>own id</i> and taking
 * the highest — highest-random-weight, or rendezvous, hashing. Adding a variant moves about one
 * villager in {@code n} onto it and leaves every other villager exactly as they were. Removing one
 * moves only the villagers who had it. It is six lines and it is the difference between "community
 * contributions cost only a merge" being true for the contributor and being true for the player.
 *
 * <p><b>Order does not matter</b>, which is the second dividend: a merge can add a line anywhere in
 * the manifest, and two packs that list the same variants in different orders produce the same
 * villagers.
 *
 * <h2>What each channel is derived from, and why they are not all the seed</h2>
 *
 * <ul>
 *   <li><b>Body, hair shape, face and the two colormap coordinates: the seed alone.</b> A pure
 *       function of the persona id, so it survives a zombification and a cure — which the entity
 *       UUID does not, and which is the whole reason {@code Persona.id} exists.</li>
 *   <li><b>Clothing shape: the profession.</b> This is ruling 2 in §9's table, and it is what
 *       re-earns session 13's headline after the renderer swap deletes vanilla's profession overlay.
 *       Vanilla's fifteen professions map onto eight shapes by the <i>kind</i> of work, because
 *       eight is the ruled budget and because a player learns five silhouettes far faster than
 *       fifteen.</li>
 *   <li><b>Clothing colour: the culture palette.</b> Which is §9's <i>tint clothing by culture
 *       palette rather than drawing outfits</i>, and it is the reason six cultures cost zero extra
 *       textures. {@link Culture} has carried a four-colour palette since session 03 with
 *       <b>no readers at all</b>; this is its first.</li>
 * </ul>
 */
public final class Appearance {

    /**
     * Which of {@link Culture#palette()}'s four entries means what.
     *
     * <p>Undefined until session 15 — the palettes were authored at session 03 and nothing has ever
     * read them, so the meaning of an index was carried in nobody's head. Named here rather than
     * left to the renderer, because "index 2" appearing in a draw call is exactly the shape of a
     * number that is wrong for a year.
     */
    public static final int CLOTH = 0;
    public static final int TRIM = 1;
    public static final int ACCENT = 2;
    public static final int LINEN = 3;

    /** The neutral a villager wears before their persona has been generated. */
    public static final int UNGENERATED_CLOTH = 0xB9AE96;

    /**
     * The eight clothing shapes, and the professions each one covers.
     *
     * <p><b>Grouped by the kind of work rather than one per profession</b>, because §9's budget is
     * eight and vanilla has fifteen. The grouping is the one a player can learn by watching: an
     * apron is somebody who handles food, a heavy apron is somebody who works metal or stone, a robe
     * is somebody who reads, and plain clothes are somebody with no trade. That is four silhouettes
     * doing most of the work and four more separating the cases that would otherwise collide.
     */
    public enum Clothing {
        /** No job, and the nitwit. Vanilla's own green coat, in spirit. */
        PLAIN("plain"),
        /** Farmer, fisherman. */
        SMOCK("smock"),
        /** Butcher, leatherworker, shepherd. */
        APRON("apron"),
        /** Armorer, toolsmith, weaponsmith. */
        FORGE("forge"),
        /** Mason. */
        DUST("dust"),
        /** Librarian, cartographer. */
        ROBE("robe"),
        /** Cleric. */
        VESTMENT("vestment"),
        /** Fletcher. */
        JERKIN("jerkin");

        private final String id;

        Clothing(String id) {
            this.id = id;
        }

        public String id() {
            return id;
        }

        /**
         * The shape for a vanilla profession key — the path of
         * {@code villager.getVillagerData().getProfession()}'s registry id.
         *
         * <p>An unknown key, which is what an addon or a modpack profession is, gets
         * {@link #PLAIN}. That is deliberate rather than a fallback: a profession this mod has never
         * heard of is not a profession it can claim to make legible, and a wrong silhouette is worse
         * than a neutral one.
         */
        public static Clothing forProfession(String key) {
            return switch (key) {
                case "farmer", "fisherman" -> SMOCK;
                case "butcher", "leatherworker", "shepherd" -> APRON;
                case "armorer", "toolsmith", "weaponsmith" -> FORGE;
                case "mason" -> DUST;
                case "librarian", "cartographer" -> ROBE;
                case "cleric" -> VESTMENT;
                case "fletcher" -> JERKIN;
                default -> PLAIN;
            };
        }
    }

    /**
     * The variants a client actually has, read from the resource manager.
     *
     * <p>A record rather than static lists, because §9 rules the set is data: a resource pack that
     * adds a hair produces a different catalogue, and every derivation below has to work against
     * whichever one this client is holding.
     */
    public record Catalogue(List<String> bodies, List<String> hair, List<String> faces) {

        public Catalogue {
            bodies = List.copyOf(bodies);
            hair = List.copyOf(hair);
            faces = List.copyOf(faces);
        }

        /**
         * What ships in the mod's own jar. Used when no manifest can be read at all, so a broken
         * resource pack costs a player their variety rather than their villagers.
         */
        public static Catalogue builtIn() {
            return new Catalogue(
                    List.of("wide", "slim"),
                    ids("hair", 6),
                    ids("face", 7));
        }

        private static List<String> ids(String prefix, int count) {
            List<String> all = new ArrayList<>(count);
            for (int i = 1; i <= count; i++) {
                all.add(prefix + "_" + i);
            }
            return List.copyOf(all);
        }

        /** True when this catalogue can dress anybody at all. */
        public boolean isUsable() {
            return !bodies.isEmpty() && !hair.isEmpty() && !faces.isEmpty();
        }
    }

    /**
     * One villager's whole appearance.
     *
     * @param slim        whether to draw the three-pixel arms
     * @param body        the body texture's variant id
     * @param hair        the hair shape's variant id
     * @param face        the face variant id
     * @param clothing    the clothing shape
     * @param skinTint    ARGB, sampled from the skin colormap
     * @param hairTint    ARGB, sampled from the hair colormap
     * @param clothTint   ARGB, from the culture palette
     */
    public record Look(boolean slim, String body, String hair, String face, Clothing clothing,
                       int skinTint, int hairTint, int clothTint) {
    }

    /** Where the seed's bits go. Named so nothing has to count shifts at a call site. */
    private static final long SLIM_SALT = 0x5311_4D00_0000_0001L;
    private static final long BODY_SALT = 0x42_4F_44_5900_0001L;
    private static final long HAIR_SALT = 0x48_41_49_5200_0001L;
    private static final long FACE_SALT = 0x46_41_43_4500_0001L;
    private static final long SKIN_SALT = 0x53_4B_49_4E00_0001L;

    private Appearance() {
    }

    /**
     * Everything about how one villager is drawn.
     *
     * @param seed          {@link Persona#appearanceSeed}
     * @param cultureId     the persona's culture, or {@link Persona#UNASSIGNED_CULTURE}
     * @param professionKey the path of the villager's vanilla profession id
     * @param catalogue     what this client has
     * @param skin          the skin colormap, or {@code null} for none
     * @param hairMap       the hair colormap, or {@code null} for none
     */
    public static Look of(int seed, byte cultureId, String professionKey, Catalogue catalogue,
                          Colormap skin, Colormap hairMap) {
        // Fifty-fifty and stable: the same villager is the same build for the life of the world,
        // which is what makes an appearance a thing about a person rather than weather.
        boolean slim = (mix(seed, SLIM_SALT) & 1L) == 0L;

        String body = slim ? "slim" : "wide";
        if (!catalogue.bodies().contains(body)) {
            body = pick(seed, BODY_SALT, catalogue.bodies(), body);
        }

        return new Look(
                slim,
                body,
                pick(seed, HAIR_SALT, catalogue.hair(), "hair_1"),
                pick(seed, FACE_SALT, catalogue.faces(), "face_1"),
                Clothing.forProfession(professionKey),
                skin == null ? 0xFFFFFFFF : skin.at(melanin(seed), hemoglobin(seed)),
                hairMap == null ? 0xFFFFFFFF : hairMap.at(melanin(seed), grey(seed)),
                clothTint(cultureId));
    }

    /**
     * <b>Rendezvous hashing.</b> The candidate whose {@code (seed, id)} pair hashes highest wins.
     *
     * <p>Deterministic on both sides of the wire and on every client, because it is arithmetic over
     * a string and an int rather than over a list index — which is the property a modulus does not
     * have and the reason this method exists.
     */
    public static String pick(int seed, long salt, List<String> candidates, String fallback) {
        if (candidates.isEmpty()) {
            return fallback;
        }
        String best = null;
        long bestScore = Long.MIN_VALUE;
        for (String candidate : candidates) {
            long score = mix(seed ^ candidate.hashCode() * 0x9E3779B1, salt);
            if (score > bestScore) {
                bestScore = score;
                best = candidate;
            }
        }
        return best;
    }

    /** The culture's cloth colour, or a neutral for a persona that has not been generated. */
    public static int clothTint(byte cultureId) {
        if (!Culture.isAssigned(cultureId)) {
            return 0xFF000000 | UNGENERATED_CLOTH;
        }
        return 0xFF000000 | Culture.byId(cultureId).palette()[CLOTH];
    }

    /** The colormap's horizontal coordinate, in {@code [0, 1)}. */
    public static float melanin(int seed) {
        return unitOf(mix(seed, SKIN_SALT));
    }

    /** The colormap's vertical coordinate, in {@code [0, 1)}. */
    public static float hemoglobin(int seed) {
        return unitOf(mix(seed, SKIN_SALT ^ 0xFFFF_FFFFL));
    }

    /** How grey the hair is. Hair uses melanin for hue and this for value. */
    public static float grey(int seed) {
        return unitOf(mix(seed, HAIR_SALT ^ 0x5555_5555L));
    }

    /**
     * Twenty-four bits of a mixed hash, as a fraction of one.
     *
     * <p><b>Taken from the middle rather than the top, and that is a bug fix rather than a
     * preference.</b> {@link #mix} returns {@code value >>> 1}, so its top bit is always zero — and
     * the first version of this method took {@code hash >>> 40}, which is twenty-three bits and a
     * guaranteed zero. Every coordinate it produced was in {@code [0, 0.5)}, so <b>half of every
     * colormap was unreachable</b>: an artist repainting the darker half of the skin table would
     * have been painting a region no villager is ever drawn from, and nothing about the villagers
     * would have looked broken. Found by {@code AppearanceTest.theColormapIsActuallySampled}, which
     * was written to check §9's claim rather than to catch this.
     */
    private static float unitOf(long hash) {
        return (float) (((hash >>> 20) & 0xFF_FFFFL) / (double) (1L << 24));
    }

    private static long mix(int seed, long salt) {
        long value = (seed & 0xFFFF_FFFFL) * 0x9E37_79B9_7F4A_7C15L ^ salt;
        value ^= value >>> 33;
        value *= 0xFF51_AFD7_ED55_8CCDL;
        value ^= value >>> 33;
        return value >>> 1;
    }

    /**
     * A 2D colour table, sampled at a point.
     *
     * <p>An interface rather than a class so the derivation above can be unit-tested with a
     * function, and so the client's PNG-backed one can live beside the code that owns a
     * {@code NativeImage}.
     */
    public interface Colormap {
        /** ARGB at {@code (u, v)}, both in {@code [0, 1)}. */
        int at(float u, float v);
    }
}
