package net.namesake.harness;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
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
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.namesake.Namesake;
import net.namesake.culture.Names;
import net.namesake.npc.NpcRegistry;
import net.namesake.npc.NpcSchema;
import net.namesake.npc.Persona;
import net.namesake.platform.PersonaLink;
import net.namesake.settlement.Need;
import net.namesake.settlement.Settlement;
import net.namesake.settlement.Specialty;
import net.namesake.verb.ClientInteractionState;
import net.namesake.verb.ClientPacketSink;
import net.namesake.verb.GreetPayload;
import net.namesake.verb.Interactions;
import net.namesake.verb.VerbNetwork;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;

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

    /**
     * Machine-readable verdict, for CI.
     *
     * <p>The game exits 0 whether the harness passed or failed, so a CI job that only watched the
     * exit code would be green forever — session 00's "a green build proves almost nothing" wearing
     * a different hat. This file is stamped {@code RUNNING} the moment the script starts and only
     * becomes {@code PASS} or {@code FAIL} when it reaches a conclusion, so a crash halfway through
     * leaves {@code RUNNING} and fails the check rather than leaving a stale verdict behind.
     */
    public static final Path RESULT_FILE = Path.of("namesake-harness-result.txt");

    /** Far enough from world spawn that the chunks are not held by the permanent spawn ticket. */
    private static final int TEST_SITE_OFFSET = 800;

    /**
     * How far the built village sits from the test site.
     *
     * <p>Past the 128-block survey radius and the 128-block probe cell, so the wilderness survey
     * around the test site cannot see the village and the two never merge into one settlement.
     */
    private static final int VILLAGE_OFFSET = 300;

    /** How long to let Minecraft shut down before giving up on it. See the watchdog below. */
    private static final long SHUTDOWN_GRACE_MILLIS = 45_000L;

    private static final String PHASE = System.getProperty(PROPERTY, "").trim();

    private static int tick;
    private static int step;
    private static int resumeAt;
    private static int deadline;
    private static int lastReport;
    private static boolean finished;
    private static int registrySizeBeforeCure;
    private static int wireEntityId;
    private static long wireToken;
    private static long wireExpiryBefore;
    private static final List<String> RESULTS = new ArrayList<>();

    /** Filled by {@code setup}, reloaded from disk by {@code verify}. */
    private static final List<Subject> SUBJECTS = new ArrayList<>();
    private static BlockPos testSite;

    /** Session 03: the built village, and who came out of it. Empty in a pre-session-03 save. */
    private static final List<Resident> RESIDENTS = new ArrayList<>();
    private static BlockPos villageSite;
    private static Settlement registeredSettlement;

    /**
     * {@code birthTick} is recorded rather than stamped, and it is the probe that survives a
     * migration: session 03 backfills culture, household and traits into any persona that predates
     * it, so warmth is <i>expected</i> to move on a cross-build load while birthTick is not.
     */
    private record Subject(UUID personaId, byte warmth, long birthTick) {
    }

    /** A villager of the built village, and the name their persona fields derive. */
    private record Resident(UUID personaId, String name) {
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
            if (tick == 1) {
                writeResult("RUNNING", "the script started but never reached a conclusion");
            }
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
                // Generation is not instant: a villager with no known settlement asks for a survey,
                // and the survey spends a bounded number of chunks per tick. Stamping warmth before
                // it lands would have the roll overwrite the stamp a few ticks later — which is a
                // real ordering hazard in the mod, not just in the harness.
                beginAwait(2400);
            }
            case 2 -> {
                if (stillWaiting(server, () -> allGenerated(server, level), false,
                        "the wilderness survey to finish and the personas to be generated")) {
                    return;
                }
                List<Villager> villagers = villagersAt(level);
                record(villagers.size() == 3, "ATTACH spawned 3 villagers, found " + villagers.size());

                NpcRegistry registry = NpcRegistry.get(server);
                SUBJECTS.clear();
                byte warmth = 11;
                int generated = 0;
                for (Villager villager : villagers) {
                    UUID personaId = PersonaLink.get().personaId(villager).orElse(null);
                    if (personaId == null) {
                        record(false, "ATTACH villager " + villager.getUUID() + " has no persona");
                        continue;
                    }
                    Persona persona = registry.persona(personaId).orElseThrow(
                            () -> new IllegalStateException("persona " + personaId + " missing from registry"));
                    if (persona.isGenerated()) {
                        generated++;
                    }
                    Persona stamped = persona.withTrait(Persona.WARMTH, warmth);
                    registry.put(stamped);
                    SUBJECTS.add(new Subject(personaId, warmth, persona.birthTick()));
                    Namesake.LOGGER.info("[harness] subject persona={} entity={} warmth={} culture={}",
                            personaId, villager.getUUID(), warmth, persona.cultureId());
                    warmth += 11;
                }
                record(SUBJECTS.size() == 3, "ATTACH every villager carries a persona ("
                        + SUBJECTS.size() + "/3)");
                // A villager 800 blocks from anywhere still has to be somebody. No settlement is a
                // real answer, not a reason to leave a record blank.
                record(generated == 3, "GENERATE " + generated
                        + "/3 wilderness villagers were generated with a culture and no settlement");
                record(registry.settlements().size() == 0,
                        "GENERATE no settlement was invented for a place with no bell ("
                                + registry.settlements().size() + ")");
                writeSubjects(level);
                advance(server, 20);
            }
            case 3 -> {
                // Walk away. The test site is 800 blocks out, so nothing holds these chunks.
                ServerPlayer player = player(server);
                BlockPos spawn = level.getSharedSpawnPos();
                teleport(player, level, spawn.getX(), 250, spawn.getZ());
                // Waiting on a chunk ticket to expire, which is game time: sprint.
                beginAwait(4000);
            }
            case 4 -> {
                if (stillWaiting(server, () -> loadedSubjects(server) == 0, true,
                        "the test-site chunks to unload")) {
                    return;
                }
                long stillLoaded = loadedSubjects(server);
                record(stillLoaded == 0,
                        "UNLOAD test-site villagers unloaded (" + stillLoaded + " still resident)");
                ServerPlayer player = player(server);
                teleport(player, level, testSite.getX(), testSite.getY() + 2, testSite.getZ());
                // Waiting on chunk IO, not on game time. Sprinting here outruns the chunk loader.
                beginAwait(2400);
            }
            case 5 -> {
                if (stillWaiting(server, () -> subjectsIntact(server), false,
                        "the test-site chunks to load again")) {
                    return;
                }
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
                // Vanilla picks 3600-6000 ticks for the cure, and it is game time, so sprint in
                // bursts. One 16000-tick burst outruns the chunk loader and the zombie villager
                // never ticks at all — observed as entityTicks=0 while thousands of server ticks
                // went by, which looked exactly like a lost persona.
                beginAwait(16000);
            }
            case 8 -> {
                Subject subject = SUBJECTS.get(0);
                if (stillWaiting(server,
                        () -> boundEntity(server, subject.personaId()).orElse(null) instanceof Villager,
                        true, "the zombie villager to finish curing")) {
                    return;
                }
                Entity carrier = boundEntity(server, subject.personaId()).orElse(null);
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
                advance(server, 5);
            }
            case 9, 10, 11, 12 -> runWireCheck(server, level);
            case 13, 14, 15 -> runSettlementCheck(server, level);
            default -> finish(server, true);
        }
    }

    // --- session 03: a settlement detected from real POI blocks ------------------------------------

    /**
     * Builds a village and watches the mod notice it.
     *
     * <p><b>Why this earns a harness leg when the survey's arithmetic does not.</b> Everything
     * {@code SettlementSurvey} concludes is a pure function of counts and is unit tested. What no
     * unit test in {@code :common} can make a claim about is the engine behaviour underneath: that
     * placing a bell through {@code setBlockAndUpdate} actually reaches {@code ServerLevel#
     * onBlockStateChange} and registers a {@code MEETING} point of interest, that
     * {@code PoiManager#getInChunk} then returns it, that a bed's <i>head</i> half is the POI and
     * its foot is not, and that a scan spread over ticks converges while the world is running.
     * Sessions 00 and 01 both lost time to precisely that gap between "compiles" and "behaves", and
     * {@code WORKPLAN.md} says POI cluster detection may earn a leg for exactly this reason.
     *
     * <p>The two households are twenty blocks apart on purpose — one household cell is sixteen — so
     * "households are recognisably related" is checked by effect rather than assumed: same family
     * name inside a cell, different family name across the boundary.
     */
    private static void runSettlementCheck(MinecraftServer server, ServerLevel level) {
        switch (step) {
            case 13 -> {
                // Load the ground before asking how high it is. LevelReader#getHeight returns the
                // world floor for a chunk that is not loaded, so reading the heightmap first put
                // the village at y=-64 — inside the deepslate, where three of the six villagers
                // promptly suffocated and read as "not placed in the settlement". Session 01's
                // rule, third application: poll for the world, never assume it.
                villageSite = testSite.offset(0, 0, VILLAGE_OFFSET);
                teleport(player(server), level, villageSite.getX(), 200, villageSite.getZ());
                beginAwait(2400);
            }
            case 14 -> {
                ChunkPos chunk = new ChunkPos(villageSite);
                if (stillWaiting(server, () -> level.getChunkSource().hasChunk(chunk.x, chunk.z),
                        false, "the village site's chunks to load")) {
                    return;
                }
                villageSite = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, villageSite);
                // If this is still the world floor the chunk did not load, and every assertion
                // below would fail for a reason that has nothing to do with settlement detection.
                record(villageSite.getY() > level.getMinBuildHeight(),
                        "VILLAGE site is on real ground at y=" + villageSite.getY());
                buildVillage(level, villageSite);

                ServerPlayer player = player(server);
                teleport(player, level, villageSite.getX() + 12, villageSite.getY() + 1,
                        villageSite.getZ() + 2);

                RESIDENTS.clear();
                for (int i = 0; i < 6; i++) {
                    // Three in the cell at the bell, three twenty blocks east — two households.
                    int offsetX = (i < 3 ? 1 : 21) + (i % 3);
                    Villager villager = EntityType.VILLAGER.spawn(level,
                            villageSite.offset(offsetX, 1, 1), MobSpawnType.COMMAND);
                    if (villager == null) {
                        throw new IllegalStateException("could not spawn village villager " + i);
                    }
                    villager.setPersistenceRequired();
                    villager.setNoAi(true);
                    Namesake.LOGGER.info("[harness] spawned village villager {} at {} (in level: {})",
                            i, villager.blockPosition().toShortString(),
                            level.getEntity(villager.getUUID()) != null);
                }
                Namesake.LOGGER.info("[harness] {} villager(s) within 64 of the bell right after "
                        + "spawning", villagersNearTheBell(level).size());
                beginAwait(2400);
            }
            case 15 -> {
                NpcRegistry registry = NpcRegistry.get(server);
                // Two conditions, not one. Waiting only for the settlement passed on Fabric and
                // failed on NeoForge with three villagers missing, and the difference was pure
                // timing: laying the village stalls the server for half a second, it then runs a
                // dozen catch-up ticks, and the villagers one chunk east are briefly unloaded
                // while the chunk tickets around the player's new position settle. Personas are
                // never lost by that - they generate when their chunk comes back - but a check
                // that reads loaded entities has to wait for them. Session 01's rule, fourth
                // application: poll the condition you actually care about, with a deadline.
                if (stillWaiting(server, () -> registry.settlements().size() > 0
                                && villagersNearTheBell(level).size() >= 6,
                        false, "the bell to be surveyed and all six villagers to be loaded")) {
                    return;
                }

                record(registry.settlements().size() == 1,
                        "SETTLEMENT exactly one settlement registered from the bell we placed ("
                                + registry.settlements().size() + ")");
                if (registry.settlements().size() != 1) {
                    finish(server, false);
                    return;
                }
                registeredSettlement = registry.settlements().all().iterator().next();

                record(registeredSettlement.centre().equals(villageSite),
                        "SETTLEMENT centre is the bell at " + villageSite.toShortString()
                                + " (got " + registeredSettlement.centre().toShortString() + ")");
                // Three composters against one of everything else: the census has to see the
                // workstation blocks as well as the bell, and rank them.
                record(registeredSettlement.specialtyValue() == Specialty.FARMING,
                        "SURVEY the workstation census read a farming town (got "
                                + registeredSettlement.specialtyValue() + ")");
                record(registeredSettlement.need(Need.FOOD) == 0
                                && registeredSettlement.need(Need.TOOLS) > 0,
                        "SURVEY needs: food " + registeredSettlement.need(Need.FOOD)
                                + ", tools " + registeredSettlement.need(Need.TOOLS)
                                + " - a farming town feeds itself and is short of a smith");
                record(registeredSettlement.defensibility() > 40,
                        "SURVEY a compact village is defensible ("
                                + registeredSettlement.defensibility() + ")");

                collectResidents(server, level);
                writeSubjects(level);
                advance(server, 5);
            }
            default -> finish(server, true);
        }
    }

    /** Checks the six villagers came out placed, related and audibly of one culture. */
    private static void collectResidents(MinecraftServer server, ServerLevel level) {
        NpcRegistry registry = NpcRegistry.get(server);
        List<Villager> nearby = villagersNearTheBell(level);
        List<Persona> placed = new ArrayList<>();
        for (Villager villager : nearby) {
            Optional<Persona> persona = PersonaLink.get().personaId(villager)
                    .flatMap(registry::persona);
            persona.ifPresent(placed::add);
            Namesake.LOGGER.info("[harness] near the bell: entity {} at {} persona={} settlement={}",
                    villager.getUUID(), villager.blockPosition().toShortString(),
                    persona.map(p -> p.id().toString().substring(0, 8)).orElse("none"),
                    persona.map(Persona::settlementId).orElse(null));
        }

        long resident = placed.stream()
                .filter(persona -> persona.settlementId() == registeredSettlement.id())
                .count();
        // Says which of the three ways this can go wrong actually happened: villagers that never
        // spawned, villagers with no persona, or personas that were never placed.
        record(resident == 6, "RESIDENTS " + resident + "/6 villagers were placed in the settlement "
                + "the survey registered (" + nearby.size() + " villagers near the bell, "
                + placed.size() + " with a persona)");
        if (resident != 6) {
            return;
        }

        Set<Byte> cultures = new HashSet<>();
        Set<Integer> households = new HashSet<>();
        Map<Integer, Set<String>> surnames = new LinkedHashMap<>();
        RESIDENTS.clear();
        for (Persona persona : placed) {
            cultures.add(persona.cultureId());
            households.add(persona.householdId());
            surnames.computeIfAbsent(persona.householdId(), key -> new HashSet<>())
                    .add(Names.of(persona).family());
            RESIDENTS.add(new Resident(persona.id(), Names.of(persona).full()));
            Namesake.LOGGER.info("[harness] resident {} culture={} household={} traits={}",
                    Names.of(persona).full(), persona.cultureId(), persona.householdId(),
                    java.util.Arrays.toString(persona.traits()));
        }

        record(cultures.size() == 1, "CULTURE one village, one culture ("
                + cultures.size() + " seen: " + cultures + ")");
        record(households.size() == 2, "HOUSEHOLDS two cells twenty blocks apart made two "
                + "households (" + households.size() + ")");
        record(surnames.values().stream().allMatch(names -> names.size() == 1),
                "HOUSEHOLDS everyone in a household shares one family name " + surnames.values());
        record(surnames.values().stream().flatMap(Set::stream).distinct().count() == 2,
                "HOUSEHOLDS the two households do not share a family name");
    }

    /**
     * Lays a platform and places a bell, six workstations and six beds on it.
     *
     * <p>The platform is not cosmetic: without it the layout depends on whatever terrain the world
     * seed put here, and a workstation that lands in water or on a slope changes what the census
     * sees. A bed is two blocks and only the <i>head</i> is a point of interest, which is one of
     * the engine facts this leg exists to confirm rather than assume.
     */
    private static void buildVillage(ServerLevel level, BlockPos origin) {
        for (int x = -4; x <= 30; x++) {
            for (int z = -4; z <= 8; z++) {
                level.setBlockAndUpdate(origin.offset(x, 0, z), Blocks.STONE.defaultBlockState());
                // A villager is 1.95 blocks tall, so two blocks of air is not two blocks of
                // clearance. Three, or they suffocate into whatever the terrain put above them.
                for (int y = 1; y <= 3; y++) {
                    level.setBlockAndUpdate(origin.offset(x, y, z), Blocks.AIR.defaultBlockState());
                }
            }
        }

        level.setBlockAndUpdate(origin, Blocks.BELL.defaultBlockState());
        level.setBlockAndUpdate(origin.offset(2, 1, 0), Blocks.COMPOSTER.defaultBlockState());
        level.setBlockAndUpdate(origin.offset(4, 1, 0), Blocks.COMPOSTER.defaultBlockState());
        level.setBlockAndUpdate(origin.offset(6, 1, 0), Blocks.COMPOSTER.defaultBlockState());
        level.setBlockAndUpdate(origin.offset(8, 1, 0), Blocks.SMITHING_TABLE.defaultBlockState());
        level.setBlockAndUpdate(origin.offset(10, 1, 0), Blocks.CARTOGRAPHY_TABLE.defaultBlockState());
        level.setBlockAndUpdate(origin.offset(12, 1, 0), Blocks.LOOM.defaultBlockState());

        for (int i = 0; i < 6; i++) {
            BlockPos foot = origin.offset(1 + i * 5, 1, 5);
            level.setBlockAndUpdate(foot, Blocks.RED_BED.defaultBlockState()
                    .setValue(BedBlock.FACING, Direction.SOUTH)
                    .setValue(BedBlock.PART, BedPart.FOOT));
            level.setBlockAndUpdate(foot.relative(Direction.SOUTH), Blocks.RED_BED.defaultBlockState()
                    .setValue(BedBlock.FACING, Direction.SOUTH)
                    .setValue(BedBlock.PART, BedPart.HEAD));
        }
        Namesake.LOGGER.info("[harness] built a village at {}: bell, 6 workstations, 6 beds",
                origin.toShortString());
    }

    private static List<Villager> villagersNearTheBell(ServerLevel level) {
        return level.getEntitiesOfClass(Villager.class, new AABB(villageSite).inflate(64));
    }

    /** True once every migrated subject has been given a culture on load. */
    private static boolean subjectsBackfilled(MinecraftServer server) {
        NpcRegistry registry = NpcRegistry.get(server);
        return !SUBJECTS.isEmpty() && SUBJECTS.stream()
                .allMatch(subject -> registry.persona(subject.personaId())
                        .filter(Persona::isGenerated).isPresent());
    }

    /** True once every villager at the test site has been generated. */
    private static boolean allGenerated(MinecraftServer server, ServerLevel level) {
        NpcRegistry registry = NpcRegistry.get(server);
        List<Villager> villagers = villagersAt(level);
        if (villagers.size() < 3) {
            return false;
        }
        return villagers.stream()
                .map(villager -> PersonaLink.get().personaId(villager).flatMap(registry::persona))
                .allMatch(persona -> persona.filter(Persona::isGenerated).isPresent());
    }

    // --- session 02: the verb wire ---------------------------------------------------------------

    /**
     * Proves a packet crosses the real wire, in both directions, on this loader — and that a forged
     * one does not get through the gate.
     *
     * <p><b>Why this is here and not in a unit test.</b> Everything else about the authorization
     * layer is a pure function and is unit tested: the order of the checks, reach arithmetic, token
     * lifetime, rate buckets, and the registration gate. The one claim no unit test in
     * {@code :common} can make is that Fabric's {@code PayloadTypeRegistry} and NeoForge's deferred
     * {@code RegisterPayloadHandlersEvent} flush actually produce a payload that survives a round
     * trip — and sessions 00 and 01 both lost time to exactly that gap between "compiles" and
     * "works in a game". It is a step inside the existing setup phase, not a new leg: no new CI
     * job, no extra launch.
     *
     * <p><b>What it uses as evidence, and why.</b> A refused packet leaves no visible mark, so
     * asserting "nothing happened" after a fixed wait would pass just as well if the packet were
     * still in flight — the false-green session 01 kept hitting. So the forged packet is anchored
     * to something it <i>does</i> do: every packet that reaches the handler spends a rate token,
     * refused or not. Waiting for the rate bucket to appear proves the packet arrived; the
     * interaction's expiry being untouched then proves it was refused at the token check. The
     * legitimate packet is the mirror image — its acceptance is what moves the expiry.
     *
     * <p>Reading the client's state from the server thread only works because this is an integrated
     * server sharing one JVM. It is a harness, not a design.
     */
    private static void runWireCheck(MinecraftServer server, ServerLevel level) {
        switch (step) {
            case 9 -> {
                ServerPlayer player = player(server);
                Entity carrier = boundEntity(server, SUBJECTS.get(0).personaId()).orElse(null);
                if (!(carrier instanceof Villager villager)) {
                    record(false, "WIRE subject 0 is not a loaded villager to talk to");
                    finish(server, false);
                    return;
                }
                teleport(player, level, villager.getX() + 1, villager.getY(), villager.getZ());
                wireEntityId = villager.getId();
                ClientInteractionState.clear();

                // The server opens the interaction, exactly as the sneak-right-click gesture does,
                // and sends the token to the client through the loader's own networking.
                Interactions.onServerGesture(player, villager);
                beginAwait(200);
            }
            case 10 -> {
                if (stillWaiting(server, () -> ClientInteractionState.tokenFor(wireEntityId).isPresent(),
                        false, "the interaction token to reach the client")) {
                    return;
                }
                var held = ClientInteractionState.tokenFor(wireEntityId);
                record(held.isPresent(), "WIRE S2C the interaction token reached the client");
                if (held.isEmpty()) {
                    finish(server, false);
                    return;
                }
                wireToken = held.getAsLong();

                ServerPlayer player = player(server);
                wireExpiryBefore = VerbNetwork.runtime().tokens().current(player.getUUID())
                        .map(net.namesake.verb.InteractionTokens.Interaction::expiresAt).orElse(-1L);
                record(wireExpiryBefore >= 0, "WIRE the server holds an open interaction");

                // A token the server never issued. This is the MCA hole, sent down a real socket.
                sendFromClient(new GreetPayload(wireToken ^ 0x5A5A5A5A5A5AL, wireEntityId),
                        "the forged greet");
                beginAwait(200);
            }
            case 11 -> {
                // The rate bucket is the proof the packet arrived at all: refused or not, every
                // packet that reaches the handler spends one.
                if (stillWaiting(server, () -> VerbNetwork.runtime().rates().size() > 0,
                        false, "the forged greet to reach the server")) {
                    return;
                }
                record(VerbNetwork.runtime().rates().size() > 0,
                        "WIRE C2S the forged greet reached the server's handler");

                ServerPlayer player = player(server);
                long expiryNow = VerbNetwork.runtime().tokens().current(player.getUUID())
                        .map(net.namesake.verb.InteractionTokens.Interaction::expiresAt).orElse(-1L);
                record(expiryNow == wireExpiryBefore,
                        "GATE the forged token was refused (interaction expiry unmoved at "
                                + expiryNow + ")");

                sendFromClient(new GreetPayload(wireToken, wireEntityId), "the real greet");
                beginAwait(200);
            }
            case 12 -> {
                ServerPlayer player = player(server);
                if (stillWaiting(server, () -> expiryOf(server, player) > wireExpiryBefore,
                        false, "the real greet to be accepted")) {
                    return;
                }
                long expiryNow = expiryOf(server, player);
                record(expiryNow > wireExpiryBefore,
                        "GATE the real token was accepted (interaction expiry moved "
                                + wireExpiryBefore + " -> " + expiryNow + ")");
                advance(server, 5);
            }
            default -> finish(server, true);
        }
    }

    private static long expiryOf(MinecraftServer server, ServerPlayer player) {
        return VerbNetwork.runtime().tokens().current(player.getUUID())
                .map(net.namesake.verb.InteractionTokens.Interaction::expiresAt).orElse(-1L);
    }

    /**
     * Sends as the client would. {@code Connection#send} hands off to the channel's event loop when
     * called from another thread, so doing this from the server tick is safe; a failure is recorded
     * rather than thrown, because "the send blew up" is itself a result worth reading.
     */
    private static void sendFromClient(GreetPayload payload, String what) {
        try {
            ClientPacketSink.send(payload);
        } catch (RuntimeException e) {
            Namesake.LOGGER.error("[harness] sending {} failed", what, e);
            record(false, "WIRE sending " + what + " threw " + e);
        }
    }

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
                checkDataFixer(registry);
                teleport(player, level, testSite.getX(), testSite.getY() + 2, testSite.getZ());
                // Chunk IO again, so no sprint. A blind 200-tick sprint here is what made this
                // phase report "the persona lost its entity" on a slow runner while the records
                // themselves were perfectly intact.
                beginAwait(2400);
            }
            case 1 -> {
                boolean migrated = NpcRegistry.get(server).loadedSchemaVersion() < NpcSchema.CURRENT;
                // A migrated save is waiting on something different: not "the values came back"
                // but "the blank records were filled in", which needs the chunks to load and the
                // survey to run before it is true.
                if (stillWaiting(server, () -> migrated ? subjectsBackfilled(server) : subjectsIntact(server),
                        false, migrated ? "the migrated personas to be backfilled"
                                : "the subjects' chunks to load")) {
                    return;
                }
                NpcRegistry registry = NpcRegistry.get(server);
                int found = 0;
                for (Subject subject : SUBJECTS) {
                    Optional<Persona> persona = registry.persona(subject.personaId());
                    if (persona.isEmpty()) {
                        record(false, "RELOAD persona " + subject.personaId() + " is gone");
                        continue;
                    }
                    // A subject file written before session 03 has no birthTick column; it reads
                    // as -1 and this check stands down rather than failing the whole run.
                    if (subject.birthTick() >= 0 && persona.get().birthTick() != subject.birthTick()) {
                        record(false, "RELOAD persona " + subject.personaId() + " birthTick is "
                                + persona.get().birthTick() + ", expected " + subject.birthTick());
                        continue;
                    }
                    // A cross-build load is expected to move warmth: the persona predates culture
                    // and traits entirely, so schema 3 backfills it. Same build, same values.
                    if (!migrated && persona.get().trait(Persona.WARMTH) != subject.warmth()) {
                        record(false, "RELOAD persona " + subject.personaId() + " warmth is "
                                + persona.get().trait(Persona.WARMTH) + ", expected " + subject.warmth());
                        continue;
                    }
                    found++;
                }
                record(found == SUBJECTS.size(),
                        "RELOAD " + found + "/" + SUBJECTS.size()
                                + " personas survived save -> quit -> reload with the same id and values");
                if (migrated) {
                    checkBackfill(registry);
                } else {
                    record(subjectsIntact(server),
                            "RELOAD every persona is still attached to a live entity that agrees");
                }
                exerciseDebugCommands(server);

                if (RESIDENTS.isEmpty()) {
                    // A world written before session 03 has no village in it. Skipping is right;
                    // pretending otherwise would fail the cross-build migration run for a reason
                    // that has nothing to do with migration.
                    Namesake.LOGGER.info("[harness] no village recorded in this save; "
                            + "skipping the settlement legs");
                    finish(server, true);
                    return;
                }
                teleport(player(server), level, villageSite.getX() + 12, villageSite.getY() + 2,
                        villageSite.getZ() + 2);
                beginAwait(2400);
            }
            case 2 -> {
                NpcRegistry registry = NpcRegistry.get(server);
                if (stillWaiting(server, () -> registry.settlements().size() > 0, false,
                        "the settlement table to come back")) {
                    return;
                }
                checkSettlementSurvivedReload(registry);
                // The exit criterion's instrument, run where there is something to read: through
                // the real dispatcher, so argument parsing and the permission gate are covered too,
                // and into the log so a run leaves behind what a player would have seen.
                CommandSourceStack source = server.createCommandSourceStack()
                        .withPosition(net.minecraft.world.phys.Vec3.atCenterOf(villageSite));
                server.getCommands().performPrefixedCommand(source, "namesake debug settlements");
                server.getCommands().performPrefixedCommand(source, "namesake debug dump");
                finish(server, true);
            }
            default -> finish(server, true);
        }
    }

    /**
     * A persona written before session 03 has no culture, no household and zeroed traits. Loading
     * it has to <i>make</i> it somebody rather than leaving it blank or, worse, letting culture 0
     * read as the first culture.
     */
    private static void checkBackfill(NpcRegistry registry) {
        long backfilled = SUBJECTS.stream()
                .map(subject -> registry.persona(subject.personaId()))
                .filter(Optional::isPresent)
                .map(Optional::get)
                .filter(Persona::isGenerated)
                .filter(persona -> !java.util.Arrays.equals(persona.traits(), new byte[Persona.TRAIT_COUNT]))
                .count();
        record(backfilled == SUBJECTS.size(),
                "BACKFILL " + backfilled + "/" + SUBJECTS.size() + " migrated personas were given a "
                        + "culture and rolled traits on load, rather than staying blank");
    }

    /**
     * The settlement, and the names it produced, after a save and a reload.
     *
     * <p>Names are <i>derived</i> from the persona id, the household id and the culture id, so
     * "the same name came back" is a stronger claim than any single field comparison: all three
     * have to have persisted, and the settlement they were rolled against has to still be there.
     */
    private static void checkSettlementSurvivedReload(NpcRegistry registry) {
        record(registry.settlements().size() == 1,
                "SETTLEMENT RELOAD one settlement came back (" + registry.settlements().size() + ")");
        Settlement settlement = registry.settlements().all().stream().findFirst().orElse(null);
        if (settlement == null) {
            return;
        }
        record(settlement.equals(registeredSettlement),
                "SETTLEMENT RELOAD every field survived: " + settlement);

        int intact = 0;
        for (Resident resident : RESIDENTS) {
            Optional<Persona> persona = registry.persona(resident.personaId());
            if (persona.isEmpty()) {
                record(false, "SETTLEMENT RELOAD resident " + resident.name() + " is gone");
                continue;
            }
            String name = Names.of(persona.get()).full();
            if (!name.equals(resident.name())) {
                record(false, "SETTLEMENT RELOAD resident was " + resident.name()
                        + " and came back as " + name);
                continue;
            }
            if (persona.get().settlementId() != settlement.id()) {
                record(false, "SETTLEMENT RELOAD " + name + " is no longer a resident");
                continue;
            }
            intact++;
        }
        record(intact == RESIDENTS.size(), "SETTLEMENT RELOAD " + intact + "/" + RESIDENTS.size()
                + " residents came back with the same name, household and settlement");
    }

    /**
     * If the world on disk predates the current schema, prove the fixer ran <i>and</i> that the
     * data it touched actually changed. "It loaded without crashing" is not evidence of a
     * migration; a fixer that silently does nothing loads without crashing too.
     */
    private static void checkDataFixer(NpcRegistry registry) {
        int onDisk = registry.loadedSchemaVersion();
        if (onDisk >= NpcSchema.CURRENT) {
            Namesake.LOGGER.info("[harness] no migration expected: world is already at schema {}", onDisk);
            return;
        }
        record(true, "DATAFIXER world was written at schema " + onDisk
                + ", this build understands " + NpcSchema.CURRENT);

        long placement = SUBJECTS.stream()
                .map(subject -> registry.persona(subject.personaId()))
                .filter(Optional::isPresent)
                .map(Optional::get)
                .filter(persona -> persona.settlementId() == Persona.UNASSIGNED
                        && persona.householdId() == Persona.UNASSIGNED)
                .count();
        record(placement == SUBJECTS.size(),
                "DATAFIXER " + placement + "/" + SUBJECTS.size() + " records carry the schema-2 "
                        + "unassigned sentinel (" + Persona.UNASSIGNED + "); schema 1 wrote 0");

        // The schema 2 -> 3 fix, and the one that would be invisible without an assertion: schema 2
        // wrote culture 0 for "none" and culture 0 is now Vale. Read before any of these villagers
        // has loaded, so it is the fixer's output rather than a backfill's.
        long culture = SUBJECTS.stream()
                .map(subject -> registry.persona(subject.personaId()))
                .filter(Optional::isPresent)
                .map(Optional::get)
                .filter(persona -> persona.cultureId() == Persona.UNASSIGNED_CULTURE)
                .count();
        record(culture == SUBJECTS.size(),
                "DATAFIXER " + culture + "/" + SUBJECTS.size() + " records now read as having no "
                        + "culture (" + Persona.UNASSIGNED_CULTURE + ") rather than as Vale; "
                        + "schema 2 wrote 0");
    }

    /**
     * Runs the {@code /namesake debug} commands through the real dispatcher.
     *
     * <p>They are the instruments the rest of this session's evidence is read with, and an
     * instrument nothing exercises is an instrument nobody notices has broken. Going through
     * {@code performPrefixedCommand} covers argument parsing and the permission gate too, not just
     * the method bodies.
     *
     * <p>{@code settrait} is checked by effect: run the command, then read the registry directly.
     * {@code prune} is deliberately left out — it deletes personas whose entities are merely
     * unloaded, which is exactly what a harness should not do to its own subjects.
     */
    private static void exerciseDebugCommands(MinecraftServer server) {
        UUID personaId = SUBJECTS.get(0).personaId();
        UUID entityId = boundEntity(server, personaId).map(Entity::getUUID).orElse(null);
        if (entityId == null) {
            record(false, "COMMANDS subject 0 has no loaded entity to target");
            return;
        }

        CommandSourceStack source = server.createCommandSourceStack();
        server.getCommands().performPrefixedCommand(source, "namesake debug registry");
        server.getCommands().performPrefixedCommand(source, "namesake debug persona " + entityId);
        server.getCommands().performPrefixedCommand(source,
                "namesake debug settrait temper 7 " + entityId);

        byte temper = NpcRegistry.get(server).persona(personaId)
                .map(persona -> persona.trait(Persona.TEMPER))
                .orElse((byte) Byte.MIN_VALUE);
        record(temper == 7, "COMMANDS /namesake debug settrait wrote temper=" + temper + ", expected 7");
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

    /**
     * Moves to the next step and gives it up to {@code maxTicks} to see its condition come true.
     *
     * <p>Replaces "sprint N ticks and hope" for everything that waits on the world rather than on
     * the clock. A blind sprint cannot tell "not yet" from "never", so it reports a slow machine as
     * a lost persona — which is exactly how this harness failed on a two-core CI runner after
     * passing on a fast desktop.
     */
    private static void beginAwait(int maxTicks) {
        step++;
        resumeAt = 0;
        deadline = tick + maxTicks;
        lastReport = tick;
    }

    /**
     * True while the caller should keep waiting. Returns false once the condition holds or the
     * deadline passes, so the caller always runs its assertion and a timeout fails loudly.
     *
     * @param sprint whether to run the clock forward hard. Right for anything waiting on game time
     *               (a chunk ticket expiring, a cure counting down); <b>wrong</b> for anything
     *               waiting on chunk IO, because sprinting outruns the chunk loader and the mobs
     *               inside those chunks never start ticking at all.
     */
    private static boolean stillWaiting(MinecraftServer server, BooleanSupplier condition,
                                        boolean sprint, String what) {
        if (condition.getAsBoolean() || tick >= deadline) {
            return false;
        }
        if (sprint && !server.tickRateManager().isSprinting()) {
            server.tickRateManager().requestGameToSprint(200);
        }
        if (tick - lastReport >= 400) {
            lastReport = tick;
            Namesake.LOGGER.info("[harness] waiting for {} ({} ticks left)", what, deadline - tick);
        }
        return true;
    }

    /** How many subjects currently have a loaded entity that agrees it carries their persona. */
    private static long loadedSubjects(MinecraftServer server) {
        return SUBJECTS.stream()
                .map(subject -> boundEntity(server, subject.personaId()))
                .filter(Optional::isPresent)
                .count();
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
            lines.add("subject " + subject.personaId() + " " + subject.warmth()
                    + " " + subject.birthTick());
        }
        if (villageSite != null) {
            lines.add("village " + villageSite.getX() + " " + villageSite.getY()
                    + " " + villageSite.getZ());
        }
        if (registeredSettlement != null) {
            StringBuilder line = new StringBuilder("settlement ")
                    .append(registeredSettlement.id()).append(' ')
                    .append(registeredSettlement.dimension()).append(' ')
                    .append(registeredSettlement.centre().getX()).append(' ')
                    .append(registeredSettlement.centre().getY()).append(' ')
                    .append(registeredSettlement.centre().getZ()).append(' ')
                    .append(registeredSettlement.specialty()).append(' ')
                    .append(registeredSettlement.defensibility());
            for (byte need : registeredSettlement.needs()) {
                line.append(' ').append(need);
            }
            lines.add(line.toString());
        }
        for (Resident resident : RESIDENTS) {
            // Name last on the line: it contains a space, and everything before it does not.
            lines.add("resident " + resident.personaId() + " " + resident.name());
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
        RESIDENTS.clear();
        villageSite = null;
        registeredSettlement = null;
        for (String line : lines) {
            String[] parts = line.split(" ");
            switch (parts[0]) {
                case "site" -> testSite = new BlockPos(Integer.parseInt(parts[1]),
                        Integer.parseInt(parts[2]), Integer.parseInt(parts[3]));
                case "subject" -> SUBJECTS.add(new Subject(UUID.fromString(parts[1]),
                        Byte.parseByte(parts[2]),
                        // A pre-session-03 file has no birthTick column. Read it as "do not check"
                        // rather than failing to parse, so the cross-build run still gets to run.
                        parts.length > 3 ? Long.parseLong(parts[3]) : -1L));
                case "village" -> villageSite = new BlockPos(Integer.parseInt(parts[1]),
                        Integer.parseInt(parts[2]), Integer.parseInt(parts[3]));
                case "settlement" -> registeredSettlement = new Settlement(
                        Integer.parseInt(parts[1]),
                        ResourceLocation.parse(parts[2]),
                        new BlockPos(Integer.parseInt(parts[3]), Integer.parseInt(parts[4]),
                                Integer.parseInt(parts[5])),
                        Byte.parseByte(parts[6]),
                        Byte.parseByte(parts[7]),
                        new byte[]{Byte.parseByte(parts[8]), Byte.parseByte(parts[9]),
                                Byte.parseByte(parts[10]), Byte.parseByte(parts[11])});
                case "resident" -> RESIDENTS.add(new Resident(UUID.fromString(parts[1]),
                        String.join(" ", java.util.Arrays.copyOfRange(parts, 2, parts.length))));
                default -> Namesake.LOGGER.warn("[harness] unrecognised subject line '{}'", line);
            }
        }
        Namesake.LOGGER.info("[harness] read {} subject(s) and {} resident(s), site {}, village {}",
                SUBJECTS.size(), RESIDENTS.size(), testSite, villageSite);
    }

    private static void finish(MinecraftServer server, boolean reachedTheEnd) {
        finished = true;
        boolean allPassed = reachedTheEnd && RESULTS.stream().allMatch(line -> line.startsWith("PASS"));

        StringBuilder summary = new StringBuilder("\n==== namesake attach-bet harness: phase ")
                .append(PHASE).append(" ====\n");
        RESULTS.forEach(line -> summary.append("  ").append(line).append('\n'));
        summary.append("==== ").append(allPassed ? "ALL PASSED" : "FAILURES PRESENT").append(" ====");
        Namesake.LOGGER.info(summary.toString());

        // Order matters: everything under test is durable, and the verdict is on disk, before we
        // ask the game to stop. Whatever happens to the shutdown after this point, the next phase
        // reloads from disk and would fail if this save had not landed.
        server.saveEverything(true, true, true);
        writeResult(allPassed ? "PASS" : "FAIL", String.join("\n", RESULTS));
        Namesake.LOGGER.info("[harness] HARNESS COMPLETE phase={} result={}",
                PHASE, allPassed ? "PASS" : "FAIL");
        startShutdownWatchdog();
        server.halt(false);
    }

    /**
     * Hard-exits if Minecraft's own shutdown does not finish.
     *
     * <p>On the CI runner the integrated server reliably wedges after logging "Saving worlds" —
     * inside vanilla's {@code saveAllChunks}, which joins a chunk-IO future — and the process never
     * exits. Locally it shuts down in about three seconds. Nothing of ours is involved: the harness
     * has already finished, the world is already saved and the verdict is already written.
     *
     * <p>A test harness that can hang forever is worse than one that stops rudely, so this bounds
     * it. It is not silent: the warning below is the signal that the wedge is still happening. The
     * exit cannot corrupt anything under test, and the {@code verify} phase reloads from disk, so
     * a truncated save would be caught rather than hidden.
     */
    private static void startShutdownWatchdog() {
        Thread watchdog = new Thread(() -> {
            try {
                Thread.sleep(SHUTDOWN_GRACE_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            Namesake.LOGGER.warn(
                    "[harness] the game did not shut down within {}s. Exiting hard. Everything under "
                            + "test was saved and the verdict written before this point.",
                    SHUTDOWN_GRACE_MILLIS / 1000);
            Runtime.getRuntime().halt(0);
        }, "namesake-harness-shutdown-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
    }

    /** Stamps {@link #RESULT_FILE}. Never throws — a reporting failure must not fail the run. */
    private static void writeResult(String verdict, String detail) {
        try {
            Files.write(RESULT_FILE, List.of("RESULT " + verdict + " phase=" + PHASE, detail));
        } catch (IOException e) {
            Namesake.LOGGER.error("[harness] could not write {}", RESULT_FILE.toAbsolutePath(), e);
        }
    }
}
