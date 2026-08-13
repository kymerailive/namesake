package net.namesake.harness;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.monster.ZombieVillager;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.levelgen.Heightmap;
import net.namesake.Namesake;
import net.namesake.npc.NpcRegistry;
import net.namesake.npc.NpcSchema;
import net.namesake.npc.Persona;
import net.namesake.platform.PersonaLink;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * A scripted run of the session 01 exit criteria, inside a real game.
 *
 * <p>Everything the architecture rests on is a claim about a live entity's whole lifecycle, and
 * none of it can be proven by a unit test or by a mod that merely loads. This drives an actual
 * integrated server through the lifecycle and prints a pass/fail line per leg.
 *
 * <p><b>Entirely inert unless {@code -Dnamesake.harness=<phase>} is set on the JVM.</b> It moves
 * the player, rewrites game rules and kills a villager; it must never run in someone's world.
 *
 * <p>Two phases, because one of the legs is "quit the game and come back":
 * <ul>
 *   <li>{@code setup} — build the subjects, prove chunk unload/reload and zombify/cure, save, halt.</li>
 *   <li>{@code verify} — reopen the same world and prove the subjects came back unchanged.</li>
 * </ul>
 * The subjects are handed between phases through {@link #SUBJECT_FILE} in the run directory, so
 * the second phase compares against what the first phase actually recorded rather than against a
 * constant written into the source.
 */
public final class AttachBetHarness {

    public static final String PROPERTY = "namesake.harness";
    public static final String WORLD_NAME = "namesake_attachbet";
    public static final Path SUBJECT_FILE = Path.of("namesake-harness-subjects.txt");

    /** Far enough from world spawn that the chunks are not held by the permanent spawn ticket. */
    private static final int TEST_SITE_OFFSET = 800;

    private static final String PHASE = System.getProperty(PROPERTY, "").trim();

    private static int tick;
    private static int step;
    private static int resumeAt;
    private static int deadline;
    private static int lastReport;
    private static boolean finished;
    private static final List<String> RESULTS = new ArrayList<>();

    /** Filled by {@code setup}, reloaded from disk by {@code verify}. */
    private static final List<Subject> SUBJECTS = new ArrayList<>();
    private static BlockPos testSite;

    private record Subject(UUID personaId, byte warmth) {
    }

    private AttachBetHarness() {
    }

    public static boolean enabled() {
        return !PHASE.isEmpty();
    }

    public static String phase() {
        return PHASE;
    }

    /** True once the script has run to a conclusion and halted the server. */
    public static boolean isFinished() {
        return finished;
    }

    /** Hooked to the end of every server tick by both loaders. */
    public static void onServerTick(MinecraftServer server) {
        if (!enabled() || finished) {
            return;
        }
        tick++;
        if (tick < resumeAt) {
            return;
        }
        try {
            if ("setup".equals(PHASE)) {
                runSetup(server);
            } else if ("verify".equals(PHASE)) {
                runVerify(server);
            } else {
                Namesake.LOGGER.error("[harness] unknown phase '{}'; expected setup or verify", PHASE);
                finish(server, false);
            }
        } catch (Exception e) {
            Namesake.LOGGER.error("[harness] step {} threw", step, e);
            record(false, "step " + step + " threw " + e);
            finish(server, false);
        }
    }

    // --- phase: setup --------------------------------------------------------------------------

    private static void runSetup(MinecraftServer server) {
        ServerLevel level = server.overworld();
        switch (step) {
            case 0 -> {
                ServerPlayer player = player(server);
                if (player == null) {
                    return; // still joining
                }
                Namesake.LOGGER.info("[harness] phase setup starting");
                configure(server, level, player);
                BlockPos spawn = level.getSharedSpawnPos();
                testSite = new BlockPos(spawn.getX() + TEST_SITE_OFFSET, spawn.getY(), spawn.getZ());
                teleport(player, level, testSite.getX(), 200, testSite.getZ());
                advance(server, 100);
            }
            case 1 -> {
                // The site chunk is loaded now, so the heightmap is real rather than generated
                // on demand under our feet.
                testSite = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, testSite);
                ServerPlayer player = player(server);
                teleport(player, level, testSite.getX(), testSite.getY(), testSite.getZ());
                for (int i = 0; i < 3; i++) {
                    Villager villager = EntityType.VILLAGER.spawn(level,
                            testSite.offset(i * 2, 0, 0), MobSpawnType.COMMAND);
                    if (villager == null) {
                        throw new IllegalStateException("could not spawn villager " + i);
                    }
                    villager.setPersistenceRequired();
                    // The subjects are fixtures, not actors. With AI they panic away from the
                    // zombie and can leave the loaded area during the cure, which reads as "the
                    // persona was lost" when it only means "the villager ran off".
                    villager.setNoAi(true);
                }
                advance(server, 20);
            }
            case 2 -> {
                List<Villager> villagers = villagersAt(level);
                record(villagers.size() == 3, "ATTACH spawned 3 villagers, found " + villagers.size());

                NpcRegistry registry = NpcRegistry.get(server);
                SUBJECTS.clear();
                byte warmth = 11;
                for (Villager villager : villagers) {
                    UUID personaId = PersonaLink.get().personaId(villager).orElse(null);
                    if (personaId == null) {
                        record(false, "ATTACH villager " + villager.getUUID() + " has no persona");
                        continue;
                    }
                    Persona persona = registry.persona(personaId).orElseThrow(
                            () -> new IllegalStateException("persona " + personaId + " missing from registry"));
                    Persona stamped = persona.withTrait(Persona.WARMTH, warmth);
                    registry.put(stamped);
                    SUBJECTS.add(new Subject(personaId, warmth));
                    Namesake.LOGGER.info("[harness] subject persona={} entity={} warmth={}",
                            personaId, villager.getUUID(), warmth);
                    warmth += 11;
                }
                record(SUBJECTS.size() == 3, "ATTACH every villager carries a persona ("
                        + SUBJECTS.size() + "/3)");
                writeSubjects(level);
                advance(server, 20);
            }
            case 3 -> {
                // Walk away. The test site is 800 blocks out, so nothing holds these chunks.
                ServerPlayer player = player(server);
                BlockPos spawn = level.getSharedSpawnPos();
                teleport(player, level, spawn.getX(), 250, spawn.getZ());
                advance(server, 400);
            }
            case 4 -> {
                long stillLoaded = SUBJECTS.stream()
                        .map(subject -> boundEntity(server, subject.personaId()))
                        .filter(Optional::isPresent)
                        .count();
                record(stillLoaded == 0,
                        "UNLOAD test-site villagers unloaded (" + stillLoaded + " still resident)");
                ServerPlayer player = player(server);
                teleport(player, level, testSite.getX(), testSite.getY() + 2, testSite.getZ());
                advance(server, 200);
            }
            case 5 -> {
                record(subjectsIntact(server), "CHUNK RELOAD personas identical after chunk unload/reload");
                advance(server, 20);
            }
            case 6 -> {
                Subject subject = SUBJECTS.get(0);
                Villager villager = (Villager) boundEntity(server, subject.personaId()).orElseThrow(
                        () -> new IllegalStateException("subject 0 not loaded"));
                Zombie zombie = EntityType.ZOMBIE.spawn(level, villager.blockPosition().above(), MobSpawnType.COMMAND);
                if (zombie == null) {
                    throw new IllegalStateException("could not spawn the zombie");
                }
                zombie.setPersistenceRequired();
                // The real path: a zombie kills a villager on hard difficulty, which is what runs
                // Zombie#killedEntity -> Villager#convertTo. Calling convertTo ourselves would skip
                // the exact line NeoForge patched its conversion event into.
                villager.setHealth(1.0F);
                zombie.setTarget(villager);
                zombie.doHurtTarget(villager);
                // Its one job is done. Left alive it hunts the other two subjects for the whole
                // length of the cure.
                zombie.discard();
                advance(server, 20);
            }
            case 7 -> {
                Subject subject = SUBJECTS.get(0);
                Entity carrier = boundEntity(server, subject.personaId()).orElse(null);
                boolean zombified = carrier instanceof ZombieVillager;
                record(zombified, "ZOMBIFY persona rode the villager onto the zombie villager"
                        + (carrier == null ? " (no carrier found)" : " (" + EntityType.getKey(carrier.getType()) + ")"));
                if (!zombified) {
                    finish(server, false);
                    return;
                }
                ZombieVillager zombieVillager = (ZombieVillager) carrier;
                record(warmthOf(server, subject.personaId()) == subject.warmth(),
                        "ZOMBIFY trait value intact across the conversion");

                ServerPlayer player = player(server);
                teleport(player, level, zombieVillager.getX(), zombieVillager.getY(), zombieVillager.getZ() + 2);
                zombieVillager.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, 6000, 0));
                player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.GOLDEN_APPLE));
                zombieVillager.mobInteract(player, InteractionHand.MAIN_HAND);
                record(zombieVillager.isConverting(), "CURE conversion started");
                registrySizeBeforeCure = NpcRegistry.get(server).size();
                // Vanilla picks 3600-6000 ticks for the cure. Poll rather than wait a fixed span:
                // a fixed wait cannot tell "the persona was lost" from "the game had not got there
                // yet", and those are opposite conclusions about the architecture.
                deadline = tick + 16000;
                lastReport = tick;
                step++;
            }
            case 8 -> {
                Subject subject = SUBJECTS.get(0);
                Entity carrier = boundEntity(server, subject.personaId()).orElse(null);
                if (!(carrier instanceof Villager) && tick < deadline) {
                    // Sprint in short bursts. One 16000-tick burst outruns the chunk loader, and a
                    // mob in a chunk that has not finished loading does not tick at all.
                    if (!server.tickRateManager().isSprinting()) {
                        server.tickRateManager().requestGameToSprint(200);
                    }
                    if (tick - lastReport >= 1000) {
                        lastReport = tick;
                        Namesake.LOGGER.info(
                                "[harness] waiting on the cure: carrier={} entityTicks={} converting={} budget={}",
                                carrier == null ? "none" : EntityType.getKey(carrier.getType()),
                                carrier == null ? -1 : carrier.tickCount,
                                carrier instanceof ZombieVillager zv && zv.isConverting(),
                                deadline - tick);
                    }
                    return;
                }
                record(carrier instanceof Villager,
                        "CURE persona rode the zombie villager back onto a villager"
                                + (carrier == null ? " (no carrier found)"
                                : " (" + EntityType.getKey(carrier.getType()) + ")"));
                record(warmthOf(server, subject.personaId()) == subject.warmth(),
                        "CURE trait value intact across the cure");
                int after = NpcRegistry.get(server).size();
                record(after == registrySizeBeforeCure,
                        "NO STRAY registry holds " + after + " persona(s), was " + registrySizeBeforeCure);
                record(subjectsIntact(server), "SUBJECTS all three personas still correct");
                finish(server, true);
            }
            default -> finish(server, true);
        }
    }

    private static int registrySizeBeforeCure;

    // --- phase: verify -------------------------------------------------------------------------

    private static void runVerify(MinecraftServer server) {
        ServerLevel level = server.overworld();
        switch (step) {
            case 0 -> {
                ServerPlayer player = player(server);
                if (player == null) {
                    return;
                }
                Namesake.LOGGER.info("[harness] phase verify starting");
                readSubjects();
                NpcRegistry registry = NpcRegistry.get(server);
                Namesake.LOGGER.info("[harness] registry schema on disk {}, this build writes {}",
                        registry.loadedSchemaVersion(), NpcSchema.CURRENT);
                record(!registry.isReadOnly(), "SCHEMA registry is writable (not refused as too new)");
                teleport(player, level, testSite.getX(), testSite.getY() + 2, testSite.getZ());
                advance(server, 200);
            }
            case 1 -> {
                NpcRegistry registry = NpcRegistry.get(server);
                int found = 0;
                for (Subject subject : SUBJECTS) {
                    Optional<Persona> persona = registry.persona(subject.personaId());
                    if (persona.isEmpty()) {
                        record(false, "RELOAD persona " + subject.personaId() + " is gone");
                        continue;
                    }
                    if (persona.get().trait(Persona.WARMTH) != subject.warmth()) {
                        record(false, "RELOAD persona " + subject.personaId() + " warmth is "
                                + persona.get().trait(Persona.WARMTH) + ", expected " + subject.warmth());
                        continue;
                    }
                    found++;
                }
                record(found == SUBJECTS.size(),
                        "RELOAD " + found + "/" + SUBJECTS.size()
                                + " personas survived save -> quit -> reload with the same id and values");
                record(subjectsIntact(server),
                        "RELOAD every persona is still attached to a live entity that agrees");
                finish(server, true);
            }
            default -> finish(server, true);
        }
    }

    // --- helpers -------------------------------------------------------------------------------

    private static void configure(MinecraftServer server, ServerLevel level, ServerPlayer player) {
        server.setDifficulty(Difficulty.HARD, true);
        GameRules rules = server.getGameRules();
        rules.getRule(GameRules.RULE_DOMOBSPAWNING).set(false, server);
        rules.getRule(GameRules.RULE_DAYLIGHT).set(false, server);
        rules.getRule(GameRules.RULE_WEATHER_CYCLE).set(false, server);
        rules.getRule(GameRules.RULE_MOBGRIEFING).set(false, server);
        rules.getRule(GameRules.RULE_DO_TRADER_SPAWNING).set(false, server);
        player.setGameMode(GameType.CREATIVE);
        // Night, frozen. A zombie villager left in daylight burns to death in about 600 ticks and
        // the cure takes up to 6000, which would turn a timing accident into a persona that
        // "vanished".
        level.setDayTime(18000);
    }

    private static ServerPlayer player(MinecraftServer server) {
        List<ServerPlayer> players = server.getPlayerList().getPlayers();
        return players.isEmpty() ? null : players.get(0);
    }

    private static void teleport(ServerPlayer player, ServerLevel level, double x, double y, double z) {
        player.teleportTo(level, x, y, z, player.getYRot(), player.getXRot());
    }

    /**
     * Runs {@code ticks} server ticks as fast as the machine allows, then moves to the next step.
     * The cure alone is 3600-6000 ticks; at 20 tps that is five minutes of waiting for one
     * assertion. {@code /tick sprint} is vanilla and does not change any of the logic under test.
     */
    private static void advance(MinecraftServer server, int ticks) {
        step++;
        resumeAt = tick + ticks;
        server.tickRateManager().requestGameToSprint(ticks);
    }

    private static List<Villager> villagersAt(ServerLevel level) {
        List<Villager> found = new ArrayList<>();
        for (Entity entity : level.getAllEntities()) {
            if (entity instanceof Villager villager) {
                found.add(villager);
            }
        }
        return found;
    }

    /** The entity currently carrying this persona, if it is loaded and agrees that it is. */
    private static Optional<Entity> boundEntity(MinecraftServer server, UUID personaId) {
        NpcRegistry registry = NpcRegistry.get(server);
        return registry.boundEntity(personaId)
                .map(entityId -> server.overworld().getEntity(entityId))
                .filter(entity -> entity != null && !entity.isRemoved())
                .filter(entity -> PersonaLink.get().personaId(entity).filter(personaId::equals).isPresent());
    }

    private static byte warmthOf(MinecraftServer server, UUID personaId) {
        return NpcRegistry.get(server).persona(personaId)
                .map(persona -> persona.trait(Persona.WARMTH))
                .orElse((byte) Byte.MIN_VALUE);
    }

    private static boolean subjectsIntact(MinecraftServer server) {
        for (Subject subject : SUBJECTS) {
            if (warmthOf(server, subject.personaId()) != subject.warmth()) {
                Namesake.LOGGER.error("[harness] persona {} warmth is {}, expected {}",
                        subject.personaId(), warmthOf(server, subject.personaId()), subject.warmth());
                return false;
            }
            if (boundEntity(server, subject.personaId()).isEmpty()) {
                Namesake.LOGGER.error("[harness] persona {} has no loaded entity that agrees it holds it",
                        subject.personaId());
                return false;
            }
        }
        return !SUBJECTS.isEmpty();
    }

    private static void record(boolean pass, String what) {
        String line = (pass ? "PASS  " : "FAIL  ") + what;
        RESULTS.add(line);
        if (pass) {
            Namesake.LOGGER.info("[harness] {}", line);
        } else {
            Namesake.LOGGER.error("[harness] {}", line);
        }
    }

    private static void writeSubjects(ServerLevel level) {
        List<String> lines = new ArrayList<>();
        lines.add("site " + testSite.getX() + " " + testSite.getY() + " " + testSite.getZ());
        for (Subject subject : SUBJECTS) {
            lines.add("subject " + subject.personaId() + " " + subject.warmth());
        }
        try {
            Files.write(SUBJECT_FILE, lines);
            Namesake.LOGGER.info("[harness] wrote {} subject(s) to {}",
                    SUBJECTS.size(), SUBJECT_FILE.toAbsolutePath());
        } catch (IOException e) {
            throw new IllegalStateException("could not write " + SUBJECT_FILE.toAbsolutePath(), e);
        }
    }

    private static void readSubjects() {
        List<String> lines;
        try {
            lines = Files.readAllLines(SUBJECT_FILE);
        } catch (IOException e) {
            throw new IllegalStateException("could not read " + SUBJECT_FILE.toAbsolutePath()
                    + "; run the setup phase first", e);
        }
        SUBJECTS.clear();
        for (String line : lines) {
            String[] parts = line.split(" ");
            if (parts[0].equals("site")) {
                testSite = new BlockPos(
                        Integer.parseInt(parts[1]), Integer.parseInt(parts[2]), Integer.parseInt(parts[3]));
            } else if (parts[0].equals("subject")) {
                SUBJECTS.add(new Subject(UUID.fromString(parts[1]), Byte.parseByte(parts[2])));
            }
        }
        Namesake.LOGGER.info("[harness] read {} subject(s), site {}", SUBJECTS.size(), testSite);
    }

    private static void finish(MinecraftServer server, boolean reachedTheEnd) {
        finished = true;
        boolean allPassed = reachedTheEnd && RESULTS.stream().allMatch(line -> line.startsWith("PASS"));

        StringBuilder summary = new StringBuilder("\n==== namesake attach-bet harness: phase ")
                .append(PHASE).append(" ====\n");
        RESULTS.forEach(line -> summary.append("  ").append(line).append('\n'));
        summary.append("==== ").append(allPassed ? "ALL PASSED" : "FAILURES PRESENT").append(" ====");
        Namesake.LOGGER.info(summary.toString());

        server.saveEverything(true, true, true);
        Namesake.LOGGER.info("[harness] HARNESS COMPLETE phase={} result={}",
                PHASE, allPassed ? "PASS" : "FAIL");
        server.halt(false);
    }
}
