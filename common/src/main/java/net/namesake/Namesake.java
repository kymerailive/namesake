package net.namesake;

import net.minecraft.SharedConstants;
import net.namesake.harness.AttachBetHarness;
import net.namesake.platform.PersonaLink;
import net.namesake.platform.Platform;
import net.namesake.verb.VerbNetwork;
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
        LOGGER.info("Namesake initialising on {} (Minecraft {}{})",
                Platform.get().loaderName(),
                SharedConstants.getCurrentVersion().getName(),
                Platform.get().isDevelopmentEnvironment() ? ", dev" : "");

        // Before anything reads a switch. It never throws: an unreadable config is defaults and a
        // log line, because a mod that refuses to start over a text file is worse than one running
        // on the settings it was designed against. See Config for why this is not a save.
        net.namesake.config.Config.load();

        // Force the loader's persona attachment to register NOW, during mod init. Both loaders
        // discard attachment data whose id is unknown at the moment entity NBT is read, so leaving
        // this to a lazy ServiceLoader lookup on first use would drop every persona link on the
        // first world load — and would look exactly like the attachment never worked.
        PersonaLink.get();

        // Registers every verb and hands them to the loader's networking, each wrapped in its own
        // authorization gate. Hard rule 6.
        VerbNetwork.bootstrap();

        if (AttachBetHarness.enabled()) {
            LOGGER.warn("Attach-bet harness ARMED, phase '{}'. This rewrites game rules, moves the "
                    + "player and kills a villager. Unset -D{} to disable.",
                    AttachBetHarness.phase(), AttachBetHarness.PROPERTY);
        }
    }
}
