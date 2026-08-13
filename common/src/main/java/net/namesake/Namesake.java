package net.namesake;

import net.namesake.platform.Platform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Common entrypoint. Called by each loader module once its own bootstrap has run.
 *
 * <p>Everything loader-agnostic lives under {@code net.namesake}; anything that genuinely differs
 * between Fabric and NeoForge goes behind an interface in {@link net.namesake.platform} and is
 * resolved with {@link java.util.ServiceLoader}. The target is MCA's ratio — 96% of the code in
 * {@code common}, with the loader modules carrying only the glue.
 */
public final class Namesake {

    /** Mod id. Must satisfy Minecraft's {@code ResourceLocation} namespace rules. */
    public static final String MOD_ID = "namesake";

    public static final Logger LOGGER = LoggerFactory.getLogger("Namesake");

    private Namesake() {
    }

    /** Idempotent by contract — a loader must call this exactly once. */
    public static void init() {
        LOGGER.info("Namesake initialising on {} (Minecraft {})",
                Platform.get().loaderName(), Platform.get().minecraftVersion());
    }
}
