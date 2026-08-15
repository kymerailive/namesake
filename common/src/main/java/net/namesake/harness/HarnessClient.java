package net.namesake.harness;

import net.minecraft.client.GraphicsStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.ParticleStatus;
import net.minecraft.client.Screenshot;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.namesake.Namesake;
import net.namesake.board.BoardText;
import net.namesake.client.NoticeBoardScreen;

import java.util.ArrayList;
import java.util.List;

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
    private static int boards;

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
        if (BoardProbe.isRequested()) {
            answerTheBoardProbe(minecraft);
        }
        String shot = BoardProbe.takeShotRequest();
        if (shot != null) {
            grab(minecraft, shot);
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
     * <b>What is actually on the screen, measured with the real font.</b> Session 11.
     *
     * <p>Two claims no unit test in {@code common} can make, and they are different claims. The first
     * is that a lectern opened by a real player through the loader's own interaction hook puts a
     * board on the screen at all — the packet crossed, the handler ran, the screen was set. The
     * second is that {@code BoardText.ADVANCES} is <i>true</i>: it is a table of the default font's
     * ASCII advances written down, and only a running client has a {@code Font} to hold it to.
     *
     * <p>Every printable character is checked rather than the ones the board happens to use, and each
     * disagreement is reported with both numbers — because a run that says "the table is wrong"
     * without saying where costs a second six-minute launch to act on.
     */
    private static void answerTheBoardProbe(Minecraft minecraft) {
        if (!(minecraft.screen instanceof NoticeBoardScreen board)) {
            return;
        }
        List<String> rows = new ArrayList<>();
        List<String> disagreements = new ArrayList<>();
        int widest = 0;
        for (BoardText.Line line : board.lines()) {
            rows.add(line.flat());
            int drawn = line.indent() * BoardText.INDENT + minecraft.font.width(line.left());
            if (!line.right().isEmpty()) {
                drawn += BoardText.GAP + minecraft.font.width(line.right());
            }
            widest = Math.max(widest, drawn);
            if (drawn != line.cost()) {
                disagreements.add("row \"" + line.flat() + "\" draws at " + drawn
                        + " and the table says " + line.cost());
            }
        }
        for (char c = BoardText.FIRST_PRINTABLE; c <= BoardText.LAST_PRINTABLE; c++) {
            int engine = minecraft.font.width(String.valueOf(c));
            int table = BoardText.width(String.valueOf(c));
            if (engine != table) {
                disagreements.add("'" + c + "' (U+" + String.format("%04X", (int) c) + ") draws at "
                        + engine + " and the table says " + table);
            }
        }
        BoardProbe.answer(new BoardProbe.Answer(true, rows, widest, disagreements));
        Namesake.LOGGER.info("[harness] notice board on screen: {} row(s), widest {}px, "
                + "{} font disagreement(s)", rows.size(), widest, disagreements.size());
        // Logged here as well as returned, so a run that never reaches the verdict — a step routed
        // wrongly, a deadline missed — still leaves behind the one thing a second launch would be
        // spent finding out. The whole table is corrected from one run or from none.
        for (String disagreement : disagreements) {
            Namesake.LOGGER.warn("[harness] font: {}", disagreement);
        }
        grabTheBoard(minecraft);
    }

    /**
     * <b>A picture of the board, because the rows are not the screen.</b>
     *
     * <p>Every guard in this repository reads strings, and strings are what a right-aligned column, a
     * panel that is too small, a colour nobody can read against the background and a scrollbar in the
     * wrong place all look like when they are fine. Session 09's last two defects and two of session
     * 10's were correct string concatenation found by somebody looking at the output; this is the
     * cheapest available way to make "look at it" a thing a run leaves behind rather than a thing
     * somebody has to remember to do.
     *
     * <p>Best effort by construction. A failed grab is logged and changes no verdict — a screenshot is
     * evidence for a person, not an assertion, and a headless runner that cannot produce one has not
     * found a defect in the board.
     */
    private static void grabTheBoard(Minecraft minecraft) {
        // Named by phase as well as by order: setup and verify are two launches into one run
        // directory, and a bare counter meant the second overwrote the first — which is how a
        // picture of a board with no history on it was read as the populated one.
        grab(minecraft, "namesake-board-" + AttachBetHarness.phase() + "-" + ++boards);
    }

    /** One picture, under a name the server chose. Never fails a run. */
    private static void grab(Minecraft minecraft, String name) {
        try {
            Screenshot.grab(minecraft.gameDirectory, name + ".png",
                    minecraft.getMainRenderTarget(),
                    message -> Namesake.LOGGER.info("[harness] {}", message.getString()));
        } catch (RuntimeException e) {
            Namesake.LOGGER.warn("[harness] could not grab the screenshot '{}'", name, e);
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
