package net.namesake.client;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.namesake.Namesake;
import net.namesake.npc.Persona;
import net.namesake.verb.AppearancePayload;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * <b>What the client knows about how villagers look.</b> Session 15.
 *
 * <p>Three things, all of them thrown away rather than persisted: which villager has which
 * appearance seed, what variants this client's resource packs offer, and the two colormaps sampled
 * out of their PNGs.
 *
 * <h2>The map is keyed on the network id and is never cleaned up on a timer</h2>
 *
 * <p>An entry costs twelve bytes and a villager that despawns leaves one behind. That is deliberate:
 * a sweep would be a per-tick cost on the client for a map that is bounded by <i>entities a player
 * has been near since they joined</i>, and it is cleared wholesale when they disconnect. The
 * alternative — hooking entity removal on two loaders — is two more seams for a leak measured in
 * kilobytes.
 */
public final class Appearances {

    private static final Map<Integer, AppearancePayload> KNOWN = new ConcurrentHashMap<>();

    private static volatile Appearance.Catalogue catalogue = Appearance.Catalogue.builtIn();
    private static volatile Appearance.Colormap skin;
    private static volatile Appearance.Colormap hair;

    private Appearances() {
    }

    /** The receiver installed into {@code ClientAppearanceSink} by each loader's client bootstrap. */
    public static void accept(AppearancePayload payload) {
        KNOWN.put(payload.entityId(), payload);
    }

    /** Dropped wholesale when a player leaves a world. */
    public static void forgetEverything() {
        KNOWN.clear();
    }

    /**
     * How this villager is drawn, or {@code null} if the server has not said yet.
     *
     * <p>A null is a real answer rather than an error: a villager whose tracking packet has not
     * arrived, or one on a server that does not have this mod, has no appearance and the renderer
     * says so by drawing the neutral. It is what makes the swap safe on a mismatched connection.
     */
    public static Appearance.Look lookOf(int entityId, String professionKey) {
        AppearancePayload known = KNOWN.get(entityId);
        if (known == null) {
            return Appearance.of(0, Persona.UNASSIGNED_CULTURE, professionKey, catalogue,
                    skin, hair);
        }
        return Appearance.of(known.appearanceSeed(), known.cultureId(), professionKey, catalogue,
                skin, hair);
    }

    /** True once the server has told us about this villager. Only a test and a log line ask. */
    public static boolean knows(int entityId) {
        return KNOWN.containsKey(entityId);
    }

    public static Appearance.Catalogue catalogue() {
        return catalogue;
    }

    /**
     * Re-reads the manifest and the two colormaps.
     *
     * <p>Called on every resource reload, which is what makes §9's <i>datapack-loadable from v1</i>
     * true rather than aspirational: a pack dropped in at runtime is picked up by the reload the
     * game already performs, with no restart and no code change.
     *
     * <p><b>Never throws.</b> A pack with a broken manifest costs a player their variety and not
     * their villagers, so every failure below falls back to what the mod's own jar ships.
     */
    public static void reload(ResourceManager resources) {
        catalogue = Manifest.read(resources);
        skin = Colormaps.read(resources, texture("colormap/skin"));
        hair = Colormaps.read(resources, texture("colormap/hair"));
        Namesake.LOGGER.info("Appearance: {} bod{}, {} hair, {} face(s){}",
                catalogue.bodies().size(), catalogue.bodies().size() == 1 ? "y" : "ies",
                catalogue.hair().size(), catalogue.faces().size(),
                skin == null || hair == null ? " — a colormap is missing, so tints are off" : "");
    }

    /** Installs the reload into whichever client is running. Both loaders call it. */
    public static void reloadFrom(Minecraft minecraft) {
        reload(minecraft.getResourceManager());
    }

    /** {@code namesake:textures/entity/villager/<path>.png}, the one place a path is built. */
    public static ResourceLocation texture(String path) {
        return ResourceLocation.fromNamespaceAndPath(
                Namesake.MOD_ID, "textures/entity/villager/" + path + ".png");
    }

    /** Reads a PNG into an ARGB table once, so a draw call never touches the resource manager. */
    static final class Colormaps {

        private Colormaps() {
        }

        static Appearance.Colormap read(ResourceManager resources, ResourceLocation where) {
            try (InputStream in = resources.open(where)) {
                com.mojang.blaze3d.platform.NativeImage image =
                        com.mojang.blaze3d.platform.NativeImage.read(in);
                int width = image.getWidth();
                int height = image.getHeight();
                int[] pixels = new int[width * height];
                for (int y = 0; y < height; y++) {
                    for (int x = 0; x < width; x++) {
                        // NativeImage stores ABGR; every consumer of a tint here wants ARGB.
                        int abgr = image.getPixelRGBA(x, y);
                        pixels[y * width + x] = (abgr & 0xFF00FF00)
                                | ((abgr & 0x00FF0000) >>> 16)
                                | ((abgr & 0x000000FF) << 16);
                    }
                }
                image.close();
                return (u, v) -> {
                    int x = Math.min(width - 1, Math.max(0, (int) (u * width)));
                    int y = Math.min(height - 1, Math.max(0, (int) (v * height)));
                    return pixels[y * width + x];
                };
            } catch (IOException | RuntimeException e) {
                Namesake.LOGGER.warn("No usable colormap at {}; villagers will not be tinted", where);
                return null;
            }
        }
    }

    /**
     * The variant manifest — {@code assets/namesake/appearance/villager.json}.
     *
     * <p>Parsed by hand rather than through a codec, and the reason is the failure mode rather than
     * the effort: a codec's error path for a pack somebody else wrote is an exception at a moment
     * the game is loading resources, and what this wants is to ignore the bad line and keep the good
     * ones.
     */
    static final class Manifest {

        static final ResourceLocation WHERE = ResourceLocation.fromNamespaceAndPath(
                Namesake.MOD_ID, "appearance/villager.json");

        private Manifest() {
        }

        static Appearance.Catalogue read(ResourceManager resources) {
            try (InputStream in = resources.open(WHERE)) {
                com.google.gson.JsonObject root = com.google.gson.JsonParser
                        .parseReader(new java.io.InputStreamReader(in,
                                java.nio.charset.StandardCharsets.UTF_8))
                        .getAsJsonObject();
                Appearance.Catalogue read = new Appearance.Catalogue(
                        list(root, "bodies"), list(root, "hair"), list(root, "faces"));
                if (read.isUsable()) {
                    return read;
                }
                Namesake.LOGGER.warn("{} lists no usable variants; using the built-in set", WHERE);
            } catch (IOException | RuntimeException e) {
                Namesake.LOGGER.warn("Could not read {}; using the built-in set", WHERE, e);
            }
            return Appearance.Catalogue.builtIn();
        }

        private static java.util.List<String> list(com.google.gson.JsonObject root, String key) {
            java.util.List<String> values = new java.util.ArrayList<>();
            if (!root.has(key) || !root.get(key).isJsonArray()) {
                return values;
            }
            for (com.google.gson.JsonElement element : root.getAsJsonArray(key)) {
                if (element.isJsonPrimitive()) {
                    String id = element.getAsString().trim();
                    if (!id.isEmpty() && !values.contains(id)) {
                        values.add(id);
                    }
                }
            }
            return values;
        }
    }
}
