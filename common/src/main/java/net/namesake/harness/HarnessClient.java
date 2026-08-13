package net.namesake.harness;

import net.minecraft.client.Minecraft;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.namesake.Namesake;

/**
 * Client half of {@link AttachBetHarness}: gets into a world without anyone clicking anything.
 *
 * <p>Only the integrated server can run the lifecycle under test, and the only way to reach one is
 * through the title screen. This creates the harness world on the first run and reopens it on every
 * run after, which is what makes "save, quit, come back" testable at all.
 *
 * <p>Client-only. Never referenced from anything that runs on a dedicated server.
 */
public final class HarnessClient {

    private static boolean entered;
    private static boolean quitting;
    private static int waited;

    private HarnessClient() {
    }

    public static void onClientTick(Minecraft minecraft) {
        if (!AttachBetHarness.enabled()) {
            return;
        }
        // The script halts the integrated server when it is done, which drops the client back to
        // the title screen. Close the game from there so the Gradle task exits on its own and the
        // run can be scripted end to end.
        if (AttachBetHarness.isFinished()) {
            if (!quitting && minecraft.level == null) {
                quitting = true;
                Namesake.LOGGER.info("[harness] closing the client");
                minecraft.stop();
            }
            return;
        }
        if (entered) {
            return;
        }
        // "Idle at a menu, done loading" rather than "showing TitleScreen": the two loaders do not
        // agree on which screen is up when client ticks start, and waiting for one specific class
        // meant the harness sat at the NeoForge menu forever without saying why.
        if (minecraft.level != null || minecraft.getOverlay() != null || minecraft.screen == null) {
            if (++waited % 200 == 0) {
                Namesake.LOGGER.info("[harness] waiting to enter a world: screen={} overlay={} level={}",
                        minecraft.screen == null ? "none" : minecraft.screen.getClass().getName(),
                        minecraft.getOverlay() == null ? "none" : minecraft.getOverlay().getClass().getName(),
                        minecraft.level == null ? "none" : "loaded");
            }
            return;
        }
        entered = true;
        Namesake.LOGGER.info("[harness] entering from screen {}", minecraft.screen.getClass().getName());

        String world = AttachBetHarness.WORLD_NAME;
        if (minecraft.getLevelSource().levelExists(world)) {
            Namesake.LOGGER.info("[harness] opening existing world '{}'", world);
            minecraft.createWorldOpenFlows().openWorld(world,
                    () -> Namesake.LOGGER.error("[harness] failed to open world '{}'", world));
        } else {
            Namesake.LOGGER.info("[harness] creating world '{}'", world);
            LevelSettings settings = new LevelSettings(
                    world,
                    GameType.CREATIVE,
                    false,
                    Difficulty.HARD,
                    true,
                    new GameRules(),
                    WorldDataConfiguration.DEFAULT);
            minecraft.createWorldOpenFlows().createFreshLevel(
                    world,
                    settings,
                    // A fixed seed so a failure can be reproduced on the same terrain.
                    new WorldOptions(20260813L, true, false),
                    WorldPresets::createNormalWorldDimensions,
                    null);
        }
    }
}
