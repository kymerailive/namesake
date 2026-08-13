package net.namesake.harness;

import net.minecraft.client.GraphicsStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.ParticleStatus;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.namesake.Namesake;

/**
 * Client half of the harnesses: gets into a world without anyone clicking anything.
 *
 * <p>Only the integrated server can run what these scripts test, and the only way to reach one is
 * through the title screen. This creates the harness world on the first run and reopens it on every
 * run after, which is what makes "save, quit, come back" testable at all.
 *
 * <p>Client-only. Never referenced from anything that runs on a dedicated server.
 */
public final class HarnessClient {

    private static boolean entered;
    private static boolean quitting;
    private static boolean tuned;
    private static int waited;

    private HarnessClient() {
    }

    private static boolean armed() {
        return AttachBetHarness.enabled() || ProfilerHarness.enabled();
    }

    private static boolean scriptFinished() {
        return AttachBetHarness.enabled()
                ? AttachBetHarness.isFinished()
                : ProfilerHarness.isFinished();
    }

    private static String worldName() {
        return AttachBetHarness.enabled() ? AttachBetHarness.WORLD_NAME : ProfilerHarness.worldName();
    }

    private static long seed() {
        return AttachBetHarness.enabled() ? 20260813L : ProfilerHarness.WORLD_SEED;
    }

    public static void onClientTick(Minecraft minecraft) {
        if (!armed()) {
            return;
        }
        // The script halts the integrated server when it is done, which drops the client back to
        // the title screen. Close the game from there so the Gradle task exits on its own and the
        // run can be scripted end to end.
        if (scriptFinished()) {
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
        unattended(minecraft);
        if (ProfilerHarness.enabled()) {
            tuneForMeasurement(minecraft);
        }
        Namesake.LOGGER.info("[harness] entering from screen {}", minecraft.screen.getClass().getName());

        String world = worldName();
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
                    new WorldOptions(seed(), true, false),
                    WorldPresets::createNormalWorldDimensions,
                    null);
        }
    }

    /**
     * The two things an unattended run needs from a client nobody is sitting in front of.
     * <b>Every armed run, both harnesses, always.</b>
     *
     * <h2>Muted</h2>
     *
     * <p>Not politeness. A harness run is a script that opens a real client on somebody's machine
     * while they are working, and a mod that puts noise in their speakers uninvited is a mod that
     * gets run less often — which costs far more than the sound is worth. On a CI runner there is no
     * audio device at all, so it is one more way for a launch to spend time failing at something
     * nobody asked for.
     *
     * <h2>Never paused, and this one is a defect this project has already lost hours to</h2>
     *
     * <p>{@code GameRenderer.render} calls {@code Minecraft.pauseGame(false)} when the window has
     * been inactive for 500 ms and {@code pauseOnLostFocus} is set — and in single player a
     * {@code PauseScreen} sets {@code Minecraft.pause}, which <b>stops the integrated server
     * ticking</b>. Every deadline this harness has is counted in server ticks, so the script simply
     * stops: no error, no timeout, no last line in the log. The process sits at a few seconds of CPU
     * per minute of wall clock, which is exactly what a wedge looks like.
     *
     * <p><b>That is what session 05's undiagnosed mid-run wedge was.</b> Its log entry records the
     * symptoms — "no further output for four minutes, the process alive at 2.4 seconds of CPU across
     * ninety seconds of wall clock — idle, not working" — and that a re-run passed first time. Both
     * follow: a re-run passes because a freshly launched window has focus. It read as a NeoForge
     * problem because of when the owner happened to click away, and it was never one.
     *
     * <p>Both are set through the option objects rather than by writing {@code options.txt}, because
     * the volume option's own update hook is what reaches {@code SoundManager}. Editing the file
     * would take effect on the next launch, which is the launch after the one making the noise.
     */
    private static void unattended(Minecraft minecraft) {
        minecraft.options.getSoundSourceOptionInstance(SoundSource.MASTER).set(0.0D);
        minecraft.options.pauseOnLostFocus = false;
        minecraft.options.save();
        Namesake.LOGGER.info("[harness] unattended: muted, and pause-on-lost-focus off so clicking "
                + "away from the window cannot stop the integrated server ticking");
    }

    /**
     * Quiets the client down before a measurement run.
     *
     * <p>Session 04 measures the <b>server</b> tick, and in single player the renderer is a second
     * thread on the same machine competing for the same cores. Left uncapped it will run at
     * whatever framerate the GPU allows and add noise to every number in the report. Set before the
     * world is opened, because the integrated server takes its view distance from the client's
     * render distance option at load.
     *
     * <p>Simulation distance is set <i>separately and higher</i> than render distance on purpose:
     * {@code IntegratedServer} reads the two from different options, and entity ticking follows the
     * simulation one. Sixteen measurement sites have to be inside it or the villagers in them are
     * loaded and not ticking — which reports a cost of nothing, confidently.
     */
    private static void tuneForMeasurement(Minecraft minecraft) {
        if (tuned) {
            return;
        }
        tuned = true;
        minecraft.options.framerateLimit().set(30);
        minecraft.options.enableVsync().set(false);
        minecraft.options.renderDistance().set(10);
        minecraft.options.simulationDistance().set(10);
        minecraft.options.graphicsMode().set(GraphicsStatus.FAST);
        minecraft.options.particles().set(ParticleStatus.MINIMAL);
        minecraft.options.entityShadows().set(false);
        minecraft.options.save();
        Namesake.LOGGER.info("[profile] client tuned for measurement: 30 fps cap, no vsync, "
                + "render distance {}, simulation distance {}",
                minecraft.options.renderDistance().get(), minecraft.options.simulationDistance().get());
    }
}
