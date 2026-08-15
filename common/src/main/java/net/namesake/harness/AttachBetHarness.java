package net.namesake.harness;

import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
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
import net.minecraft.core.GlobalPos;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.item.trading.MerchantOffer;
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
import net.minecraft.world.phys.BlockHitResult;
import net.namesake.Namesake;
import net.namesake.board.Board;
import net.namesake.board.BoardText;
import net.namesake.board.NoticeBoard;
import net.namesake.culture.Culture;
import net.namesake.culture.Names;
import net.namesake.dialogue.Dialogue;
import net.namesake.dialogue.Pool;
import net.namesake.dialogue.Register;
import net.namesake.dialogue.Voice;
import net.namesake.npc.NpcRegistry;
import net.namesake.npc.NpcSchema;
import net.namesake.npc.Persona;
import net.namesake.npc.PersonaService;
import net.namesake.platform.PersonaLink;
import net.namesake.road.RoadEdge;
import net.namesake.road.RoadGraph;
import net.namesake.road.RoadNetwork;
import net.namesake.road.RoadPath;
import net.namesake.road.RoadProgress;
import net.namesake.road.RoadTrail;
import net.namesake.road.Roads;
import net.namesake.sim.PlayerModel;
import net.namesake.sim.Reports;
import net.namesake.sim.Simulation;
import net.namesake.settlement.Need;
import net.namesake.settlement.Settlement;
import net.namesake.settlement.Specialty;
import net.namesake.social.Bond;
import net.namesake.social.Deed;
import net.namesake.social.DeedBus;
import net.namesake.social.DeedType;
import net.namesake.social.Gossip;
import net.namesake.social.Memories;
import net.namesake.social.Personality;
import net.namesake.social.Residency;
import net.namesake.social.Standing;
import net.namesake.social.Teaching;
import net.namesake.social.Trading;
import net.namesake.verb.ClientInteractionState;
import net.namesake.verb.ClientPacketSink;
import net.namesake.verb.GreetPayload;
import net.namesake.verb.Interactions;
import net.namesake.verb.VerbNetwork;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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

    /**
     * How far the second village sits from the first. <b>Session 10's fixture.</b>
     *
     * <p>Past {@code Settlements.MEMBERSHIP_RADIUS} twice over, so neither village's residents can be
     * mistaken for the other's — and close enough that a player standing between them holds both in
     * the loaded area, which is what the road needs to be laid at all and what makes it possible to
     * feed somebody in one village and then look at the other.
     */
    private static final int NEIGHBOUR_OFFSET = 192;

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
     * Session 12: the player who did everything in the {@code setup} phase.
     *
     * <p>Written into the subjects file, because the {@code verify} phase cannot otherwise tell
     * whether the person running it is the same one. On NeoForge they always are; on Fabric
     * {@code runClient} mints a fresh {@code PlayerNNN} and therefore a fresh offline UUID on every
     * launch, so they never are — which is a defect from every other leg's point of view and is
     * exactly what {@code DESIGN.md} §10 step 7 asks for. See {@code checkStepSeven}.
     */
    private static UUID actingPlayer;

    /** Session 11: the two lecterns this run stood up, and the far village's bell. */
    private static BlockPos homeBoard;
    private static BlockPos awayBoard;
    private static BlockPos farBell;

    /** Session 10: the village down the road, and the ground the road was laid over. */
    private static BlockPos neighbourSite;
    private static RoadEdge roadEdge;
    private static final List<BlockPos> ROAD_GRASS = new ArrayList<>();
    private static final List<BlockPos> ROAD_PLANKS = new ArrayList<>();
    private static int storiesSentDownTheRoad;
    private static int dayTheDeedsHappened;

    /** Session 05: the six roles of the witness leg, and the bonds it produced. */
    private static UUID deedSubject;
    private static final List<UUID> WITNESSES_IN_SIGHT = new ArrayList<>();
    private static UUID witnessBehindAWall;
    private static UUID witnessOutOfRange;
    private static final List<BondRow> BONDS = new ArrayList<>();

    /** Session 06: the deed rings the same leg produced. */
    private static final List<MemoryRow> MEMORIES = new ArrayList<>();

    /**
     * One bond as the setup phase left it, so {@code verify} compares against what was actually
     * written rather than against a constant.
     *
     * <p>{@code about} is recorded rather than assumed: the dev client picks a fresh player name —
     * and therefore a fresh offline UUID — on every launch, so the player who ran {@code setup} is
     * not the player who runs {@code verify}. A check keyed on the live player would read every
     * bond as absent and call it a pass.
     */
    private record BondRow(UUID personaId, UUID about, byte trust, byte warmth, byte fear) {
    }

    /**
     * One NPC's ring as the setup phase left it: {@code deedId:confidence} per slot, in order.
     *
     * <p>All seven fields of every deed are covered by those two tokens and nothing is restated.
     * {@code Deed.id()} is derived from six of them — type, actor, subject, settlement, day and
     * severity — so an id that comes back the same is six fields that came back the same, and
     * confidence is the seventh, carried beside it because it is deliberately outside the
     * derivation. Order is the list's own.
     */
    private record MemoryRow(UUID personaId, List<String> ring) {
    }

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
                actingPlayer = player.getUUID();
                Namesake.LOGGER.info("[harness] this launch's player is {} ({})",
                        player.getGameProfile().getName(), actingPlayer);
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
            case 16, 17 -> runWitnessCheck(server, level);
            case 18 -> runGossipCheck(server, level);
            case 19, 20, 21, 22, 23, 24 -> runRoadCheck(server, level);
            case 25, 26, 27, 28, 29 -> runNoticeBoardCheck(server, level);
            case 30, 31, 32 -> runStandingBandCheck(server, level);
            case 33, 34, 35, 36, 37, 38 -> runDayPlanCheck(server, level);
            default -> finish(server, true);
        }
    }

    // --- session 13: the day plan, at real workstations --------------------------------------------

    /** How far from the counter the workshop sits, so neither leg can hear the other. */
    private static final int WORKSHOP_OFFSET = 200;

    /** Six villagers and six workstations: three who work and three who do not. */
    private static final int WORKERS = 6;

    /** Ten blocks apart, so one villager's standoff cannot land on another's workstation. */
    private static final int BENCH_SPACING = 10;

    private static BlockPos workshopSite;
    private static final List<Villager> WORKERS_PRESENT = new ArrayList<>();
    private static final List<BlockPos> BENCHES = new ArrayList<>();
    private static final Set<UUID> DILIGENT = new HashSet<>();

    /** The widest split the transition wave was ever seen to produce, in a running game. */
    private static int waveSplit;

    /** The tick the camera is allowed to have settled by, before the picture is taken. */
    private static int lookAt;

    /** Each worker's tickCount at 09:00, so "did their brain actually run" is a fact rather than a hope. */
    private static final List<Integer> TICKS_AT_0900 = new ArrayList<>();

    /**
     * The workers by <b>UUID</b>, and {@link #WORKERS_PRESENT} is re-resolved from it every time it
     * is read.
     *
     * <p>Holding entity references across thousands of ticks is session 03's lesson with the
     * consequence spelled out: a villager whose chunk unloads is {@code isRemoved()} and a stale
     * reference to it <b>keeps answering</b> — same position, same memories, same {@code tickCount},
     * for ever. Every assertion in this leg would then read a frozen villager as a villager standing
     * perfectly still, which is exactly what the standoff is supposed to look like. The tick-count
     * assertion is what caught it; this is what fixes it.
     */
    private static final List<UUID> WORKER_IDS = new ArrayList<>();

    /**
     * <b>Session 13's exit criterion, and the two halves of it that a machine can hold.</b>
     *
     * <p><i>At 09:00 every workstation has someone within arm's reach — except the ones eight blocks
     * away doing nothing, and you can hear which is which. Lazy villagers demonstrably do not
     * restock.</i>
     *
     * <h2>What this leg polls for, and why it is not a number of ticks</h2>
     *
     * <p>{@code WorkAtPoi} has a {@code CHECK_COOLDOWN} of 300 ticks and then a
     * {@code level.random.nextInt(2) != 0} coin on top of it, so a villager standing <i>on</i> its
     * workstation starts work about once every <b>six hundred</b> ticks. <b>"Every workstation has
     * someone within arm's reach" is therefore a claim about a thirty-second average and not about
     * any given second</b>, and a leg that sampled positions on one tick would be measuring a coin.
     *
     * <p>So the observable is not a position. It is {@code LAST_WORKED_AT_POI} — the memory
     * {@code WorkAtPoi.start} stamps in the same breath as it plays the work sound and calls
     * {@code restock()}. All three are one gate, so <b>reaching that memory is reaching all three</b>,
     * and the leg waits for the memory rather than for the clock.
     *
     * <p>And the lazy half cannot be polled for at all, because it is an <i>absence</i>. So it is
     * measured against an event instead of against a deadline: <b>wait until every diligent villager
     * has worked, then assert that no lazy one has.</b> If the three who are supposed to work have
     * all worked, enough time has passed that a fourth would have too. That is the session 12 lesson
     * one turn further — poll for the condition the thing you are about to do actually needs — with
     * the extra step that the condition for asserting a negative is somebody else's positive.
     */
    private static void runDayPlanCheck(MinecraftServer server, ServerLevel level) {
        switch (step) {
            case 33 -> {
                workshopSite = new BlockPos(villageSite.getX() - WORKSHOP_OFFSET, 200,
                        villageSite.getZ() + WORKSHOP_OFFSET);
                teleport(player(server), level, workshopSite.getX(), 200, workshopSite.getZ());
                beginAwait(2400);
            }
            case 34 -> {
                if (stillWaiting(server, () -> level.isLoaded(workshopSite), false,
                        "empty ground for the workshop")) {
                    return;
                }
                workshopSite = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                        workshopSite);
                // BEFORE the villagers exist, and that ordering is the fixture's own bug fixed.
                // configure() freezes this world at 18000, and a villager spawned then resolves
                // its activity to REST on its first brain tick. Activity.WORK is registered with
                // `ImmutableSet.of(Pair.of(JOB_SITE, VALUE_PRESENT))`, so a villager with no
                // workstation cannot enter WORK at all — and GoToPotentialJobSite, the behaviour
                // that walks an unemployed villager to the workstation it is about to claim,
                // refuses to run outside IDLE, WORK and PLAY. A villager who starts in REST is
                // therefore stuck: no job, so no WORK; no WORK, so no job hunt.
                //
                // The first two runs of this leg watched five of six villagers stand beside a
                // lectern for six thousand ticks. Setting the clock first means they resolve to
                // IDLE instead, which job-hunts.
                level.setDayTime(2500);
                buildWorkshop(level);
                teleport(player(server), level, workshopSite.getX(), workshopSite.getY(),
                        workshopSite.getZ());
                record(BENCHES.size() == WORKERS,
                        "PLAN stood " + BENCHES.size() + " workstation(s) up on empty ground, "
                                + BENCH_SPACING + " blocks apart, with no bell anywhere near them");
                beginAwait(6000);
            }
            case 35 -> {
                // Four conditions, and each one is needed by something below rather than by the
                // clock. Findable by id is session 12's lesson. A JOB_SITE is what there is to
                // stand off *from*. An offer is what a restock resets — a villager with no trade
                // cannot demonstrate not restocking.
                //
                // AND A **GENERATED** PERSONA, WHICH IS NOT THE SAME CONDITION AS A PERSONA, and
                // getting that wrong cost this leg a run. A persona is minted the moment a villager
                // loads and generated once its settlement is known, and the survey that decides
                // that takes thousands of ticks out here in the wilderness — session 03's leg waits
                // 2,400 for exactly this. The day plan reads `industry` and `tradition`, which an
                // ungenerated persona does not have, so it leaves those villagers alone. Polling
                // for "has a persona" is the session 12 defect in a new place: two conditions that
                // are both "the villager is there", populated at different moments, and only one of
                // them is what the thing under test actually asks.
                if (stillWaiting(server, () -> refreshWorkers(level) == WORKERS
                                && WORKERS_PRESENT.stream().allMatch(v ->
                                        level.getEntity(v.getId()) == v
                                                && PersonaService.personaOf(v)
                                                        .filter(Persona::isGenerated).isPresent()
                                                && net.namesake.day.Steering.jobSiteOf(v) != null
                                                && !v.getOffers().isEmpty()),
                        true, "six villagers to be generated, take a workstation and stock a counter")) {
                    return;
                }
                record(WORKERS_PRESENT.stream().allMatch(v -> PersonaService.personaOf(v)
                                .filter(Persona::isGenerated).isPresent()),
                        "PLAN all six personas are generated, which is what the plan reads and is "
                                + "not the same thing as having one");
                // Written before the assertion rather than after it failed. Session 12 lost three
                // red mains to a leg that read a plausible wrong answer and had nothing recorded
                // about what it was assuming; what found it in the end was an assertion on the
                // assumption, not a guess at the cause. This is that, one session later.
                for (Villager villager : WORKERS_PRESENT) {
                    Namesake.LOGGER.info("[harness] worker {} at {}: profession={} job={} "
                                    + "potential={} activity={} offers={} baby={}",
                            villager.getId(), villager.blockPosition().toShortString(),
                            villager.getVillagerData().getProfession(),
                            net.namesake.day.Steering.jobSiteOf(villager),
                            villager.getBrain().getMemory(
                                    MemoryModuleType.POTENTIAL_JOB_SITE).orElse(null),
                            villager.getBrain().getActiveNonCoreActivity().orElse(null),
                            villager.getOffers().size(), villager.isBaby());
                }
                long lecterns = BENCHES.stream()
                        .filter(bench -> level.getPoiManager().getType(bench).isPresent()).count();
                record(lecterns == BENCHES.size(),
                        "PLAN all " + BENCHES.size() + " lecterns registered as points of interest ("
                                + lecterns + ") — setBlockAndUpdate is what reaches "
                                + "ServerLevel#onBlockStateChange, and without it these are "
                                + "decorative furniture nobody can take a job at");
                record(WORKERS_PRESENT.stream().allMatch(v ->
                                net.namesake.day.Steering.jobSiteOf(v) != null),
                        "PLAN all six villagers claimed a workstation of their own");

                assignIndustry(server);
                // Uses are raised on everybody's offers now, so a reset later is evidence that
                // WorkAtPoi.start ran rather than that nothing ever needed resetting.
                for (Villager villager : WORKERS_PRESENT) {
                    villager.getOffers().forEach(offer -> {
                        offer.resetUses();
                        offer.increaseUses();
                    });
                }
                // Let the clock run for the first time this world has had one, so the boundary at
                // 2000 is crossed by villagers rather than by a setDayTime.
                server.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(true, server);
                level.setDayTime(net.namesake.day.DaySlot.LABOUR_I.startsAt() - 40);
                waveSplit = 0;
                beginAwait(1200);
            }
            case 36 -> {
                // Sampled every tick on the way past, because the wave is the one thing here that
                // only exists *during* a boundary. A tick later there is nothing left to see.
                refreshWorkers(level);
                sampleTheWave(server, level);
                // ARRIVED, not merely assigned — and the difference is a whole clause of the exit
                // criterion. A lazy villager's standoff is five to eight blocks from the bench they
                // are standing at when the boundary passes, so for the seconds it takes to walk out
                // they are still inside WorkAtPoi's 1.73 m and its coin can land. The criterion is
                // about "the ones eight blocks away doing nothing"; a villager still walking is not
                // yet eight blocks away, so the window opens when they get there.
                if (stillWaiting(server, () -> refreshWorkers(level) == WORKERS
                                && WORKERS_PRESENT.stream()
                                .filter(v -> !isDiligent(v))
                                .allMatch(v -> net.namesake.day.Steering.postureOf(v)
                                        == net.namesake.day.Steering.Posture.STANDOFF),
                        false, "every lazy villager to reach the spot they were sent to")) {
                    return;
                }
                // Written whether or not the poll succeeded, and that is the point: the standoff has
                // five ways to decline and from outside they all look like a villager at their
                // workstation. Session 12's lesson — what finds it is an assertion on the
                // assumption, not a guess at the cause.
                for (Villager villager : WORKERS_PRESENT) {
                    Namesake.LOGGER.info("[harness] standoff for {}: {}", villager.getId(),
                            net.namesake.day.Steering.explainStandoff(level, villager));
                }
                record(waveSplit > 0,
                        "WAVE the village crossed the 2000 boundary over several ticks rather than "
                                + "in one — at its widest, " + waveSplit + " of " + WORKERS
                                + " villager(s) had crossed while the rest had not");

                // Nine o'clock, and frozen there. WorkAtPoi needs about six hundred ticks a
                // villager and LABOUR_I is only three thousand long, so a running clock would end
                // the slot before the slowest coin landed and the leg would be measuring the
                // schedule instead of the mechanic.
                server.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false, server);
                level.setDayTime(3000);
                refreshWorkers(level);
                // THE MEASUREMENT WINDOW OPENS HERE, AND NOT ONE TICK EARLIER — which is the
                // difference between measuring the standoff and measuring the hour before it.
                //
                // Every villager has been standing at their bench since step 34, in LABOUR_I, while
                // the settlement survey that generates their personas finished. The plan cannot
                // call anybody lazy until it knows who they are, so during that window vanilla was
                // doing what vanilla does and ALL SIX worked. The leg read that as "three lazy
                // villagers restocked" and blamed the mechanic. So both observables are cleared now
                // that the standoffs are actually in place: the memory WorkAtPoi.start stamps, and
                // the offer uses a restock resets.
                for (Villager villager : WORKERS_PRESENT) {
                    villager.getBrain().eraseMemory(MemoryModuleType.LAST_WORKED_AT_POI);
                    villager.getOffers().forEach(offer -> {
                        offer.resetUses();
                        offer.increaseUses();
                    });
                }
                TICKS_AT_0900.clear();
                WORKERS_PRESENT.forEach(v -> TICKS_AT_0900.add(v.tickCount));
                // Six thousand rather than twenty-four. WorkAtPoi averages ~600 ticks a villager, so
                // three of them all firing wants a couple of thousand; twenty-four thousand is
                // twenty minutes of real time on this machine and longer on a runner, and a leg
                // that takes that long to fail is a leg nobody can iterate on.
                beginAwait(6000);
            }
            case 37 -> {
                // THE POLL THIS WHOLE LEG TURNS ON. Not a tick count, and not a position: the
                // memory WorkAtPoi.start stamps. See the method note.
                //
                // AND IT DOES NOT SPRINT, which is the difference between this leg working and this
                // leg reporting that the standoff broke the whole village. A long `/tick sprint`
                // outruns the chunk loader, mobs never enter the entity tick list, and a villager
                // whose brain is not ticking never leaves the activity registerBrainGoals left it
                // in — which is IDLE, because JOB_SITE is set a line after refreshBrain. Five of
                // six villagers sat at Manhattan 1 from their workstation, in IDLE, having never
                // moved and never worked; the sixth, standing where the player was teleported,
                // worked perfectly. This repository has written that trap down once already.
                if (stillWaiting(server, () -> refreshWorkers(level) == WORKERS
                                && WORKERS_PRESENT.stream()
                                .filter(AttachBetHarness::isDiligent)
                                .allMatch(v -> v.getBrain()
                                        .hasMemoryValue(MemoryModuleType.LAST_WORKED_AT_POI)),
                        false, "every diligent villager to reach WorkAtPoi.start at least once")) {
                    return;
                }
                // Stand the player back and above, looking down the row of six, and photograph it.
                // Session 11's sixth instrument — the one that asserts nothing — pointed at a
                // deliverable that has no rows in it at all. Every assertion below reads positions;
                // whether six villagers three of whom are eight blocks out *looks* like anything is
                // a question only a picture can put to a person.
                ServerPlayer watcher = player(server);
                watcher.teleportTo(level, workshopSite.getX() + 0.5, workshopSite.getY() + 9,
                        workshopSite.getZ() - 26.5, 0.0F, 22.0F);
                lookAt = tick + 20;
                beginAwait(200);
            }
            case 38 -> {
                if (stillWaiting(server, () -> tick >= lookAt, false,
                        "the camera to settle before the picture")) {
                    return;
                }
                refreshWorkers(level);
                BoardProbe.requestShot("namesake-dayplan-" + PHASE);
                for (Villager villager : WORKERS_PRESENT) {
                    BlockPos job = net.namesake.day.Steering.jobSiteOf(villager);
                    Namesake.LOGGER.info("[harness] at 0900 {}: {} activity={} workRequirementMet={} "
                                    + "lastWorked={} at {} job {} manhattan={} posture={}",
                            villager.getId(), isDiligent(villager) ? "diligent" : "lazy",
                            villager.getBrain().getActiveNonCoreActivity().orElse(null),
                            villager.getBrain().checkMemory(MemoryModuleType.JOB_SITE,
                                    net.minecraft.world.entity.ai.memory.MemoryStatus.VALUE_PRESENT),
                            villager.getBrain().getMemory(MemoryModuleType.LAST_WORKED_AT_POI)
                                    .orElse(null),
                            villager.blockPosition().toShortString(),
                            job == null ? "none" : job.toShortString(),
                            job == null ? -1 : job.distManhattan(villager.blockPosition()),
                            net.namesake.day.Steering.postureOf(villager));
                }
                checkTheStandoffHeld(server, level);
                restoreTheClock(server, level);
                writeSubjects(level);
                advance(server, 20);
            }
            default -> finish(server, true);
        }
    }

    private static boolean isDiligent(Villager villager) {
        return PersonaService.personaOf(villager).map(p -> DILIGENT.contains(p.id())).orElse(false);
    }

    /**
     * Re-resolves the six workers from their UUIDs. Called at the head of every step that reads
     * them; see {@link #WORKER_IDS} for why a held reference is a lie.
     *
     * @return how many of them the level can currently find
     */
    private static int refreshWorkers(ServerLevel level) {
        WORKERS_PRESENT.clear();
        for (UUID id : WORKER_IDS) {
            if (level.getEntity(id) instanceof Villager villager && !villager.isRemoved()) {
                WORKERS_PRESENT.add(villager);
            }
        }
        return WORKERS_PRESENT.size();
    }

    /**
     * The transition wave, caught in the act.
     *
     * <p>{@code DayPlan.offsetOf} is measured over 4,536 personas in a unit test; what a unit test
     * cannot say is whether the runtime <i>reads</i> it. This is the check that it does: at some
     * tick during the crossing, some of these six villagers are in {@code LABOUR_I} and the rest are
     * still in {@code DAWN}. If the offset were computed and ignored — which is exactly what this
     * session shipped for an hour before a test caught it — every villager would cross on the same
     * tick and this would read zero.
     */
    private static void sampleTheWave(MinecraftServer server, ServerLevel level) {
        long dayTime = level.getDayTime();
        int crossed = 0;
        int total = 0;
        for (Villager villager : WORKERS_PRESENT) {
            Persona persona = PersonaService.personaOf(villager).orElse(null);
            if (persona == null || !persona.isGenerated()) {
                continue;
            }
            total++;
            if (net.namesake.day.DayPlan.slotFor(persona, dayTime) == net.namesake.day.DaySlot.LABOUR_I) {
                crossed++;
            }
        }
        if (crossed > 0 && crossed < total) {
            waveSplit = Math.max(waveSplit, crossed);
        }
    }

    /** Half the village industrious, half not — written onto the personas the plan actually reads. */
    private static void assignIndustry(MinecraftServer server) {
        NpcRegistry registry = NpcRegistry.get(server);
        DILIGENT.clear();
        int index = 0;
        for (Villager villager : WORKERS_PRESENT) {
            Persona persona = PersonaService.personaOf(villager).orElseThrow();
            boolean diligent = index++ % 2 == 0;
            // Well clear of the threshold on both sides, so the leg is testing the mechanic rather
            // than the boundary. DayPlanDistributionTest is where the threshold itself is measured.
            registry.put(persona.withTrait(Persona.INDUSTRY, (byte) (diligent ? 60 : -40)));
            if (diligent) {
                DILIGENT.add(persona.id());
            }
        }
    }

    private static void restoreTheClock(MinecraftServer server, ServerLevel level) {
        server.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false, server);
        // Back to where configure() left it, so the verify phase opens on the world it expects.
        level.setDayTime(18000);
    }

    /**
     * <b>The exit criterion, clause by clause.</b>
     *
     * <p>Every assertion here is by effect on something vanilla wrote — {@code LAST_WORKED_AT_POI},
     * an offer's use count, a block position — rather than on a flag of ours. A spent flag reads as
     * success; a reset use count is a restock that actually happened.
     */
    private static void checkTheStandoffHeld(MinecraftServer server, ServerLevel level) {
        // FIRST, AND IT DECIDES WHETHER ANYTHING BELOW IS EVIDENCE. Session 01 lost three CI runs
        // to entities that were loaded and not ticking, and this leg lost one to the same thing:
        // a villager whose brain never runs is a villager who never works, never strolls and never
        // leaves the activity it was registered in — and every assertion below would read that as
        // the standoff working perfectly on everybody.
        int leastTicked = Integer.MAX_VALUE;
        int mostTicked = 0;
        for (int i = 0; i < WORKERS_PRESENT.size() && i < TICKS_AT_0900.size(); i++) {
            int advanced = WORKERS_PRESENT.get(i).tickCount - TICKS_AT_0900.get(i);
            leastTicked = Math.min(leastTicked, advanced);
            mostTicked = Math.max(mostTicked, advanced);
        }
        // Compared against each other rather than against a constant, so the assertion does not
        // depend on how long the poll above happened to take. What it is about is a villager who
        // is frozen while the others run — the failure that reads as the standoff working on
        // everybody, and the reason this is the first line of this method.
        record(leastTicked >= mostTicked * 9 / 10,
                "PLAN every villager ran its brain at the same rate since 09:00 — least-ticked "
                        + leastTicked + ", most-ticked " + mostTicked + ". A villager whose chunk "
                        + "stopped ticking stands perfectly still, which is what a standoff looks "
                        + "like from every other assertion here");

        List<Villager> diligent = WORKERS_PRESENT.stream()
                .filter(AttachBetHarness::isDiligent).toList();
        List<Villager> lazy = WORKERS_PRESENT.stream()
                .filter(v -> !isDiligent(v)).toList();

        long worked = diligent.stream()
                .filter(v -> v.getBrain().hasMemoryValue(MemoryModuleType.LAST_WORKED_AT_POI))
                .count();
        record(worked == diligent.size(),
                "PLAN every industrious villager reached WorkAtPoi.start — the sound, the "
                        + "LAST_WORKED_AT_POI stamp and the restock are one gate (" + worked
                        + " of " + diligent.size() + ")");

        long lazyWorked = lazy.stream()
                .filter(v -> v.getBrain().hasMemoryValue(MemoryModuleType.LAST_WORKED_AT_POI))
                .count();
        record(lazyWorked == 0,
                "PLAN no lazy villager reached WorkAtPoi.start, measured after every industrious "
                        + "one already had (" + lazyWorked + " of " + lazy.size() + " did)");

        // "Lazy villagers demonstrably do not restock", and this is the demonstration rather than
        // the inference: restock() resets every offer's use count, the uses were raised before the
        // slot began, and a counter whose uses are still up is a counter nobody restocked.
        long restocked = diligent.stream().filter(AttachBetHarness::wasRestocked).count();
        long lazyRestocked = lazy.stream().filter(AttachBetHarness::wasRestocked).count();
        record(restocked > 0,
                "RESTOCK " + restocked + " of " + diligent.size() + " industrious villager(s) had "
                        + "their offer uses reset, so the claim below is about something rather "
                        + "than about nothing");
        record(lazyRestocked == 0,
                "RESTOCK no lazy villager restocked — their counters still hold the uses this leg "
                        + "put on them (" + lazyRestocked + " of " + lazy.size() + " reset)");

        // And where everybody is standing, which is the half a player sees. Asserted on both vanilla
        // metrics, because that is what the standoff is an intersection of.
        int atWork = 0;
        for (Villager villager : diligent) {
            BlockPos job = net.namesake.day.Steering.jobSiteOf(villager);
            if (job != null && net.namesake.day.DayPlan.isWithinArmsReach(job,
                    villager.blockPosition())) {
                atWork++;
            }
        }
        record(atWork > 0, "PLAN " + atWork + " of " + diligent.size() + " industrious villager(s) "
                + "are inside WorkAtPoi's 1.73 m right now — a snapshot rather than the criterion, "
                + "because they stroll between spells of work");

        int standingOff = 0;
        int wrongPlace = 0;
        for (Villager villager : lazy) {
            BlockPos job = net.namesake.day.Steering.jobSiteOf(villager);
            BlockPos where = villager.blockPosition();
            if (job == null) {
                continue;
            }
            if (net.namesake.day.DayPlan.isWithinArmsReach(job, where)) {
                wrongPlace++;
            } else if (job.distManhattan(where) <= net.namesake.day.DayPlan.VANILLA_JOB_SITE_TOLERANCE) {
                standingOff++;
            }
        }
        record(wrongPlace == 0,
                "STANDOFF no lazy villager is within arm's reach of their workstation, which is the "
                        + "position WorkAtPoi's own gate reads (" + wrongPlace + " were)");
        record(standingOff > 0,
                "STANDOFF " + standingOff + " of " + lazy.size() + " lazy villager(s) are parked "
                        + "inside vanilla's Manhattan-9 tolerance and outside 1.73 — the annulus "
                        + "the whole mechanic is built on");

        // The veto, which is what holds them there. Without this the leg would pass on a world
        // where the standoff happened to survive because nothing had got round to contesting it.
        int vetoes = net.namesake.day.Steering.vetoCount(level);
        record(vetoes > 0,
                "VETO vanilla offered the job site back and it was declined " + vetoes + " time(s) — "
                        + net.namesake.day.Steering.describe(level) + ". StrollToPoi re-asserts the "
                        + "workstation every 80 ticks for anybody within ten metres, so a standoff "
                        + "that was never contested is not a standoff that is holding, it is one "
                        + "that is merely early");

        // And the two postures that say a silent villager was meant to be silent. This is the
        // clause the inherited bug makes necessary: a stuck villager is silent exactly like a lazy
        // one, so without a name for it "you can hear which is which" is met by a false positive.
        long stuck = WORKERS_PRESENT.stream()
                .map(net.namesake.day.Steering::postureOf)
                .filter(p -> p == net.namesake.day.Steering.Posture.STUCK
                        || p == net.namesake.day.Steering.Posture.BELL_LOCKED)
                .count();
        record(stuck == 0,
                "PLAN no villager in this workshop is stuck or bell-locked (" + stuck + "), so "
                        + "every silence above is a silence the plan chose");
    }

    /** True if this villager's offers have been reset since the leg raised them. */
    private static boolean wasRestocked(Villager villager) {
        return !villager.getOffers().isEmpty()
                && villager.getOffers().stream().allMatch(offer -> offer.getUses() == 0);
    }

    /**
     * A flat platform, six workstations ten blocks apart, and six villagers.
     *
     * <p><b>No bell, deliberately.</b> A bell writes {@code HEARD_BELL_TIME} into everything within
     * 32 blocks with no expiry, and {@code ReactToBell} then holds them in {@code HIDE} — which is
     * the mechanism behind the villagers stuck in their own houses. Putting one here would make this
     * leg intermittently measure that instead of the standoff, which is exactly the shape of defect
     * session 12's counter leg cost three red {@code main}s to.
     */
    private static void buildWorkshop(ServerLevel level) {
        // Wide enough for six benches and the widest standoff ring around the outermost pair, and
        // cleared TWELVE blocks up rather than three. The heightmap is what resolves a standoff
        // point, and MOTION_BLOCKING_NO_LEAVES answers with the first air above the topmost solid
        // block — so a single block of hillside left standing over the platform puts the resolved
        // ground metres above the workstation, which is Manhattan ten and refused. Three blocks of
        // clearance is enough for a villager to stand in and not enough for the heightmap to agree
        // with the floor, and the difference is invisible until six villagers will not stand
        // anywhere.
        int half = BENCH_SPACING * WORKERS / 2 + 10;
        for (int dx = -half; dx <= half; dx++) {
            for (int dz = -12; dz <= 12; dz++) {
                level.setBlockAndUpdate(workshopSite.offset(dx, -1, dz),
                        Blocks.STONE.defaultBlockState());
                for (int dy = 0; dy <= 12; dy++) {
                    level.setBlockAndUpdate(workshopSite.offset(dx, dy, dz),
                            Blocks.AIR.defaultBlockState());
                }
            }
        }
        // Asserted rather than assumed, at the four corners and the middle. A platform the
        // heightmap does not agree is flat is a platform no standoff can be chosen on.
        int flat = 0;
        for (int[] probe : new int[][]{{-half, -12}, {half, -12}, {-half, 12}, {half, 12}, {0, 0}}) {
            BlockPos where = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                    workshopSite.offset(probe[0], 0, probe[1]));
            if (where.getY() == workshopSite.getY()) {
                flat++;
            }
        }
        record(flat == 5, "PLAN the workshop platform is flat to the heightmap at all five probes ("
                + flat + "/5) — the standoff resolves through MOTION_BLOCKING_NO_LEAVES, so terrain "
                + "left standing over the floor is terrain no villager can be sent to");

        BENCHES.clear();
        WORKERS_PRESENT.clear();
        WORKER_IDS.clear();
        for (int i = 0; i < WORKERS; i++) {
            int x = (i - WORKERS / 2) * BENCH_SPACING;
            BlockPos bench = workshopSite.offset(x, 0, 0);
            // setBlockAndUpdate, because that path is what reaches ServerLevel#onBlockStateChange
            // and registers the point of interest. Session 03's finding, and without it these are
            // decorative lecterns and nobody takes a job.
            level.setBlockAndUpdate(bench, Blocks.LECTERN.defaultBlockState());
            BENCHES.add(bench);

            Villager villager = EntityType.VILLAGER.spawn(level, bench.offset(0, 0, 1),
                    MobSpawnType.COMMAND);
            if (villager == null) {
                throw new IllegalStateException("could not spawn workshop villager " + i);
            }
            villager.setPersistenceRequired();
            employ(level, villager, bench);
            WORKER_IDS.add(villager.getUUID());
            WORKERS_PRESENT.add(villager);
        }
    }

    /**
     * Gives one villager a trade and a workstation outright, rather than waiting for vanilla to
     * hand them one.
     *
     * <p><b>Two runs of this leg were spent measuring the wrong mechanism.</b> Vanilla's job hunt is
     * three behaviours deep — {@code AcquirePoi} finds a point of interest it can <i>path</i> to,
     * {@code GoToPotentialJobSite} walks there and refuses to run outside IDLE, WORK and PLAY, and
     * {@code AssignProfessionFromJobSite} needs the villager within two metres — and {@code WORK}
     * itself is registered with {@code JOB_SITE VALUE_PRESENT} as a requirement, so an unemployed
     * villager cannot enter the activity that would employ them. Six villagers stood beside six
     * lecterns for six thousand ticks, twice, and the leg reported the standoff as broken.
     *
     * <p>None of that is what session 13 built. The standoff is about a villager who <b>has</b> a
     * workstation, so the fixture hands them one and the leg measures the mechanic it is named
     * after. Everything set here is state vanilla writes itself in the same order:
     * {@code setVillagerData} then {@code refreshBrain} is exactly what
     * {@code AssignProfessionFromJobSite} does, the point-of-interest ticket is taken rather than
     * squatted, and {@code ValidateNearbyPoi} — which would erase a job site pointing at nothing —
     * checks only that the point of interest <i>exists</i>, so it leaves this alone.
     */
    private static void employ(ServerLevel level, Villager villager, BlockPos bench) {
        villager.setVillagerData(villager.getVillagerData()
                .setProfession(VillagerProfession.LIBRARIAN).setLevel(2));
        villager.setVillagerXp(20);
        villager.refreshBrain(level);
        // The profession's own predicate rather than a POI key spelled out here. A lectern's point
        // of interest is registered under `librarian`, not `lectern`, and a key written by hand is
        // a second answer to a question VillagerProfession already answers.
        level.getPoiManager().take(VillagerProfession.LIBRARIAN.heldJobSite(),
                (type, pos) -> pos.equals(bench), bench, 1);
        // After refreshBrain, which rebuilds the behaviour lists. Memories survive it —
        // copyWithoutBehaviors keeps them — but writing before it would be relying on that.
        villager.getBrain().setMemory(MemoryModuleType.JOB_SITE,
                GlobalPos.of(level.dimension(), bench));
    }

    // --- session 12: the bands, at a real counter -------------------------------------------------

    /**
     * The two villagers the band legs use. Spawned by the harness, so each starts with no bond and
     * no vanilla gossip at all.
     *
     * <p><b>Two rather than one, and the second is a defect being fixed rather than tidiness.</b>
     * Punching the first would give it a vanilla {@code MINOR_NEGATIVE} gossip about this player, and
     * {@code updateSpecialPrices} would then add a markup of its own to every later reading — which
     * is correct behaviour and made every band on the ladder read two emeralds high. The ladder is
     * measured on a villager nobody has touched; the blow lands on one of its own.
     */
    private static Villager trader;
    private static Villager punchbag;

    /** Whether the last simulated right-click actually reached the villager. See priceAtTheCounter. */
    private static boolean theWindowOpened;

    /**
     * <b>The two consumers that only a running game can show.</b>
     *
     * <p>The band arithmetic, the ladder, the thresholds and every absence branch are pure and are
     * in {@code StandingTest} and {@code TradingTest} — {@code WORKPLAN.md} draws that line and it
     * cuts here. What is left needs a world for three reasons, and each of them has broken something
     * in this project before:
     *
     * <ol>
     *   <li><b>The interaction has to arrive the way a click does.</b> Both loaders hook the
     *       <i>packet handler</i>, not {@code Player.interactOn}, so this leg drives a real
     *       {@code ServerboundInteractPacket} through {@code connection.handleInteract} rather than
     *       calling {@code Trading} directly. Session 11 made the same choice for the board and gave
     *       the same reason: calling the method proves the method and skips the seam.</li>
     *   <li><b>Vanilla has to still be able to add its own adjustment on top.</b> This session
     *       ruled that the band <i>adds to</i> {@code Villager#updateSpecialPrices} rather than
     *       replacing it, and the only place that claim is testable is a real
     *       {@code MerchantOffer} on a real villager after real {@code startTrading}.</li>
     *   <li><b>A price lives on the offer rather than on the viewer</b>, which makes it the one
     *       consumer of the three that could leak between players. {@code DESIGN.md} §10 step 7 is
     *       this session's exit criterion, so the leak is driven in the order that would produce it:
     *       a trusted player prices the counter, and then somebody who has done nothing opens it.</li>
     * </ol>
     */
    /**
     * Where the counter is set up: empty ground, well away from everything else this run built.
     *
     * <p><b>Two hundred blocks, and the number is a defect being fixed rather than a preference.</b>
     * The first version stood the counter up beside the bell, and one real punch there emitted a deed
     * that three village residents witnessed — which moved bonds the snapshot at case 24 had already
     * recorded, and turned four unrelated legs red in the <i>verify</i> phase. A witness scan is
     * {@code AABB.inflate(24)}, so anything past that is out of earshot; two hundred also puts it
     * outside the settlement's ninety-six block membership radius, so the traders belong to nowhere
     * and no village can hear about them.
     */
    private static final int COUNTER_OFFSET = 200;

    private static BlockPos counterSite;

    private static void runStandingBandCheck(MinecraftServer server, ServerLevel level) {
        switch (step) {
            case 30 -> {
                ServerPlayer player = player(server);
                counterSite = new BlockPos(villageSite.getX() + COUNTER_OFFSET, 200,
                        villageSite.getZ() + COUNTER_OFFSET);
                teleport(player, level, counterSite.getX(), 200, counterSite.getZ());
                beginAwait(2400);
            }
            case 31 -> {
                if (stillWaiting(server, () -> level.isLoaded(counterSite), false,
                        "empty ground away from the village to set a counter up on")) {
                    return;
                }
                counterSite = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                        counterSite);
                layAFloor(level);
                teleport(player(server), level, counterSite.getX(), counterSite.getY(),
                        counterSite.getZ());
                trader = standUpATrader(level, 2);
                punchbag = standUpATrader(level, -2);
                record(trader != null && punchbag != null,
                        "BAND stood two villagers up on empty ground two hundred blocks from the "
                                + "village, so nothing this run already measured can hear the blow");
                beginAwait(1200);
            }
            case 32 -> {
                // A poll rather than an assumption, and CI said so three times before this condition
                // was right. The first draft read the personas in the tick it spawned; the second
                // polled for the personas, which is a *different* condition and not the one a
                // right-click needs.
                //
                // A villager is minted a persona from `onTrackingStart`, which vanilla fires while
                // the entity is being added to its section — but `level.getEntity(id)` reads the
                // *visible* storage, which is only populated once that chunk's entity status reaches
                // TICKING. So there is a window, one tick wide on this machine and evidently wider on
                // a runner, in which the villager has a persona and cannot be found by id. And
                // `ServerGamePacketListenerImpl.handleInteract` resolves its target by id and returns
                // **silently** when it cannot, so every price in the leg reads as untouched.
                //
                // Session 01's rule, and the part of it that keeps being the hard bit: it is not
                // enough to poll instead of sleeping — **poll for the condition the thing you are
                // about to do actually needs.**
                if (stillWaiting(server, () -> trader != null && punchbag != null
                                && level.getEntity(trader.getId()) == trader
                                && level.getEntity(punchbag.getId()) == punchbag
                                && PersonaService.personaOf(trader).isPresent()
                                && PersonaService.personaOf(punchbag).isPresent(),
                        false, "the two traders to be findable by id and minted a persona each")) {
                    return;
                }
                checkTheCounter(server, level);
                writeSubjects(level);
                advance(server, 5);
            }
            default -> finish(server, true);
        }
    }

    /**
     * Stands one librarian up on empty ground and prices their counter.
     *
     * <p>A librarian at the top level, because it is the profession whose offers are emeralds for
     * books — a cost big enough that every band on the ladder moves it by a whole item, which is the
     * property {@code TradingTest.theCheapestTradeCannotMove} says a one-item cost does not have.
     */
    /**
     * A floor to stand the counter on, because two hundred blocks from a village is whatever the
     * generator felt like and <b>CI generates a different world every run.</b>
     *
     * <p>Both attempts at this were wrong in the same direction and CI said so both times. A bare
     * spawn on the heightmap put a villager in whatever was there; a one-block apron with two blocks
     * of clearance was better and still let the surroundings back in. <b>A villager is 1.95 blocks
     * tall, so two blocks of air is not two blocks of clearance</b> — this repository has written
     * that down once already — and water flows back into a hole the tick after you dig it. So the
     * floor is a proper platform: solid all the way round, three blocks of air above it, and wide
     * enough that nothing can reach the middle before the leg is finished.
     */
    private static void layAFloor(ServerLevel level) {
        for (int dx = -4; dx <= 4; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                level.setBlockAndUpdate(counterSite.offset(dx, -1, dz),
                        Blocks.STONE.defaultBlockState());
                for (int dy = 0; dy <= 2; dy++) {
                    level.setBlockAndUpdate(counterSite.offset(dx, dy, dz),
                            Blocks.AIR.defaultBlockState());
                }
            }
        }
    }

    private static Villager standUpATrader(ServerLevel level, int offset) {
        BlockPos where = counterSite.offset(offset, 0, 0);
        Villager villager = EntityType.VILLAGER.spawn(level, where, MobSpawnType.COMMAND);
        if (villager == null) {
            return null;
        }
        villager.setNoAi(true);
        villager.setVillagerData(villager.getVillagerData()
                .setProfession(VillagerProfession.LIBRARIAN).setLevel(5));
        villager.setVillagerXp(250);
        return villager;
    }

    private static void checkTheCounter(MinecraftServer server, ServerLevel level) {
        ServerPlayer player = player(server);
        if (trader == null || punchbag == null) {
            record(false, "BAND could not stand a villager up to trade with");
            return;
        }
        List<Integer> base = new ArrayList<>();
        for (MerchantOffer offer : trader.getOffers()) {
            base.add(offer.getBaseCostA().getCount());
        }
        record(!base.isEmpty(), "BAND the villager has " + base.size() + " offer(s) to price");
        // Alive AND findable by id, because the second is what a right-click actually needs and it
        // is not the same claim. ServerGamePacketListenerImpl.handleInteract resolves the target out
        // of the level and returns silently when it cannot — so a trader that drowned during the
        // wait would make every band on the ladder read the base price and the failure would say
        // "the band is wrong". CI reported exactly that, twice, before this leg existed.
        record(trader.isAlive() && punchbag.isAlive()
                        && level.getEntity(trader.getId()) == trader
                        && level.getEntity(punchbag.getId()) == punchbag,
                "BAND and both are still alive and findable by id, which is what a right-click needs"
                        + " (alive " + trader.isAlive() + "/" + punchbag.isAlive()
                        + ", findable " + (level.getEntity(trader.getId()) == trader)
                        + "/" + (level.getEntity(punchbag.getId()) == punchbag) + ")");
        UUID persona = PersonaService.personaOf(trader).map(Persona::id).orElse(null);
        UUID bruised = PersonaService.personaOf(punchbag).map(Persona::id).orElse(null);
        record(persona != null && bruised != null,
                "BAND and a persona of their own, minted on sight");
        if (persona == null || bruised == null || base.isEmpty()) {
            return;
        }

        NpcRegistry registry = NpcRegistry.get(server);
        int today = Deed.dayOf(level);

        // A villager spawned a moment ago has no bond with anybody and no vanilla gossip either,
        // which is the acceptance script's step 1 as a fixture rather than as a fresh world:
        // "arrive at A, prices 1.00".
        List<Integer> neutral = priceAtTheCounter(server, level, trader);
        // The window opening is what says the right-click arrived — and it has to be asserted
        // separately from the price, because a NEUTRAL band and an interaction that never happened
        // produce the same number. That is how a real defect reported itself as "the band is wrong"
        // on one loader in CI while every band on the ladder read the base price.
        record(theWindowOpened,
                "BAND the right-click reached the villager and vanilla opened a trade window, which "
                        + "is the only reason a NEUTRAL reading of the base price means anything");
        record(neutral.equals(base),
                "BAND STEP 1 a villager who has never met you charges exactly the standing price "
                        + neutral + " against a base of " + base);
        record(!player.getRecipeBook().contains(
                        ResourceLocation.withDefaultNamespace("lectern")),
                "BAND and teaches you nothing: a stranger does not show you their trade");

        for (Standing band : List.of(Standing.TRUSTED, Standing.WARM, Standing.RESENTED)) {
            registry.putBond(persona, player.getUUID(), bondFor(band, today));
            List<Integer> charged = priceAtTheCounter(server, level, trader);
            List<Integer> wanted = new ArrayList<>(base.size());
            for (int cost : base) {
                wanted.add(cost + Trading.adjustmentFor(cost, band.priceMultiplier()));
            }
            record(charged.equals(wanted),
                    "BAND " + band + " (x" + band.priceMultiplier() + ") charges " + charged
                            + " against a base of " + base);
        }

        // The recipe, and the state that makes it a mechanic rather than a message: it is in the
        // player's own recipe book, which vanilla persists per player, and calling it twice teaches
        // nothing a second time.
        record(player.getRecipeBook().contains(ResourceLocation.withDefaultNamespace("lectern")),
                "BAND a librarian who is warm to you has taught you to make a lectern — which is a "
                        + "notice board, and nothing arranged that");
        record(Teaching.teach(player, trader, Standing.WARM) == Teaching.Outcome.ALREADY_KNOWN,
                "BAND and teaches it once: a recipe already known is not taught again");

        checkAStrikeReachesThePrice(server, level, punchbag, bruised);
        checkTheCounterDoesNotLeak(server, level, persona, base);
    }

    /**
     * <b>{@code DESIGN.md} §10 step 6, end to end and through no fixture at all.</b>
     *
     * <p>A real punch: vanilla's own damage path, the loader's damage hook, {@code SocialEvents},
     * {@code DeedBus}, a bond, the band, and the price of a real offer. Session 05 checks the first
     * half of that chain and the ladder above checks the second, and <b>neither of them checks that
     * they are joined</b> — which is the shape of defect this project keeps finding.
     *
     * <p><b>And it is where the "we add to vanilla" ruling stops being a paragraph.</b> The first
     * version of this leg asserted the final price equals base plus <i>our</i> adjustment, and went
     * red at exactly two emeralds over on every reading — because a punch also gives the villager a
     * vanilla {@code MINOR_NEGATIVE} gossip, and {@code updateSpecialPrices} adds a markup of its own
     * on top of ours. That is the composition working, so the assertion was wrong rather than the
     * code: what is checked is that <b>our</b> contribution is exactly the band's, and that the
     * player who threw the punch pays at least that much more — with vanilla's surplus printed
     * rather than absorbed, because it is the thing a player will actually see.
     */
    private static void checkAStrikeReachesThePrice(MinecraftServer server, ServerLevel level,
                                                    Villager villager, UUID persona) {
        ServerPlayer player = player(server);
        NpcRegistry registry = NpcRegistry.get(server);
        int today = Deed.dayOf(level);
        List<Integer> base = new ArrayList<>();
        for (MerchantOffer offer : villager.getOffers()) {
            base.add(offer.getBaseCostA().getCount());
        }

        int before = registry.bonds().at(persona, player.getUUID(), today).trust();
        player.attack(villager);
        int after = registry.bonds().at(persona, player.getUUID(), today).trust();
        record(after < before && after < 0,
                "BAND STEP 6 one real blow took trust from " + before + " to " + after
                        + ", unclipped and through the whole pipeline");
        Standing standing = Standing.of(registry.bonds().at(persona, player.getUUID(), today));
        record(standing == Standing.WARY && standing.isAgainstYou(),
                "BAND STEP 6 which puts them in " + standing + ", a band that charges more");

        // Our own contribution, read off the return value rather than off the screen, so the two
        // systems are separable. Then the screen, which is both of them.
        Trading.Applied ours = Trading.onTradeOpening(player, villager);
        int ourDiff = ours.totalDiff();
        List<Integer> charged = priceAtTheCounter(server, level, villager);
        int rose = 0;
        for (int i = 0; i < base.size(); i++) {
            rose += charged.get(i) - base.get(i);
        }
        record(ourDiff == Trading.adjustmentFor(base.get(0), Standing.WARY.priceMultiplier())
                        * base.size(),
                "BAND STEP 6 and our own contribution is exactly the band's: +" + ourDiff);
        record(rose >= ourDiff && rose > 0,
                "BAND STEP 6 and the price rose for the player who threw it — " + charged
                        + " against a base of " + base + ": +" + ourDiff + " from the band and +"
                        + (rose - ourDiff) + " from vanilla's own gossip, which agrees with us "
                        + "about violence and is added rather than replaced");
    }

    /**
     * <b>{@code DESIGN.md} §10 step 7, on the one surface where it could go wrong.</b>
     *
     * <p>Bonds are keyed on (holder, viewer) and a board is computed per viewer, so neither can leak
     * — sessions 05 and 11 hold both, in unit tests. A <b>price</b> is different: it is written onto
     * a {@code MerchantOffer} that belongs to the villager rather than to anybody looking at them, so
     * two players share the object. That is the new risk this session introduces, and this is it
     * driven in the order that would produce it.
     *
     * <p><b>The second player here is a real {@code ServerPlayer} on the live integrated server</b> —
     * its own UUID, its own recipe book, its own everything — constructed without a connection,
     * because a scripted single-client run cannot produce a second login. What that costs is stated
     * rather than glossed: no packet reaches them, so this proves the <i>server</i> answers a second
     * person correctly and not that a second person's screen draws it. The other half of step 7 is
     * checked in {@code verify}, where on one of the two loaders the player genuinely <i>is</i>
     * somebody else — see {@code checkStepSeven}.
     */
    private static void checkTheCounterDoesNotLeak(MinecraftServer server, ServerLevel level,
                                                   UUID persona, List<Integer> base) {
        ServerPlayer stranger;
        try {
            stranger = new ServerPlayer(server, level, new com.mojang.authlib.GameProfile(
                    UUID.nameUUIDFromBytes("a second player who has done nothing"
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                    "Nobody"), net.minecraft.server.level.ClientInformation.createDefault());
        } catch (RuntimeException failure) {
            record(false, "BAND STEP 7 this loader would not build a second player without a "
                    + "connection: " + failure);
            return;
        }

        // The trusted player prices the counter first, and leaves it priced. If our contribution
        // were not reset per viewer, this is the state the next person would walk into.
        NpcRegistry.get(server).putBond(persona, player(server).getUUID(),
                bondFor(Standing.WARM, Deed.dayOf(level)));
        Trading.Applied mine = Trading.onTradeOpening(player(server), trader);
        record(mine.priced() && mine.standing() == Standing.WARM,
                "BAND STEP 7 the first player prices the counter at " + mine.standing());

        Trading.Applied theirs = Trading.onTradeOpening(stranger, trader);
        List<Integer> charged = new ArrayList<>();
        for (MerchantOffer offer : trader.getOffers()) {
            charged.add(offer.getCostA().getCount());
        }
        record(theirs.standing() == Standing.NEUTRAL,
                "BAND STEP 7 a second player who has done nothing stands " + theirs.standing()
                        + " at a counter the first player stands " + mine.standing() + " at");
        record(charged.equals(base),
                "BAND STEP 7 and pays 1.00 at a counter the first player just discounted "
                        + charged + " against a base of " + base);

        // And the guard that keeps it true while somebody is actually looking at the screen: a
        // villager already trading has that player's offers open, so rewriting them would change
        // what a different person pays, invisibly, after their client has drawn the numbers.
        trader.setTradingPlayer(player(server));
        Trading.Applied refused = Trading.onTradeOpening(stranger, trader);
        record(!refused.priced(),
                "BAND STEP 7 and the counter is not repriced under an open screen somebody else has");
        trader.setTradingPlayer(null);
    }

    /** A bond that lands in exactly this band, built through the record rather than through apply. */
    private static Bond bondFor(Standing band, int day) {
        return switch (band) {
            case RESENTED -> new Bond((byte) Standing.RESENTED_TRUST, (byte) 0, (byte) 0,
                    (short) 0, day, (short) 0, (byte) 0);
            case WARY -> new Bond((byte) -1, (byte) 0, (byte) 0, (short) 0, day, (short) 0, (byte) 0);
            case NEUTRAL -> Bond.fresh(day);
            case TRUSTED -> new Bond((byte) Standing.TRUSTED_TRUST, (byte) 0, (byte) 0,
                    (short) 0, day, (short) 0, (byte) 0);
            // peakWarmth at the same value, so the lazy decay does not eat the fixture on the way
            // out of the registry — session 09's GiftPolicyTest learned this the same way.
            case WARM -> new Bond((byte) 0, (byte) Standing.WARM_WARMTH, (byte) 0, (short) 0, day,
                    (short) 0, (byte) Standing.WARM_WARMTH);
        };
    }

    /**
     * One real right-click, and what the offers cost afterwards.
     *
     * <p>Through {@code connection.handleInteract} with a real {@code ServerboundInteractPacket},
     * which is the path a click actually takes: both loaders hook the packet handler rather than
     * {@code Player.interactOn}, so anything short of this measures our own method instead of the
     * seam. The screen is closed afterwards, which is what makes vanilla's own
     * {@code resetSpecialPrices} run — so each reading starts from the same place a player's would.
     */
    private static List<Integer> priceAtTheCounter(MinecraftServer server, ServerLevel level,
                                                   Villager villager) {
        ServerPlayer player = player(server);
        player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        player.setShiftKeyDown(false);
        player.connection.handleInteract(ServerboundInteractPacket.createInteractionPacket(
                villager, false, InteractionHand.MAIN_HAND));
        theWindowOpened = villager.getTradingPlayer() == player;
        List<Integer> charged = new ArrayList<>();
        for (MerchantOffer offer : villager.getOffers()) {
            charged.add(offer.getCostA().getCount());
        }
        player.closeContainer();
        return charged;
    }

    // --- session 05: the witness scan, in a running level ------------------------------------------

    /**
     * Feeds one villager in front of five others and reads the bonds back.
     *
     * <p><b>Why this earns the session's one harness leg.</b> Everything else about bonds is
     * arithmetic over immutable values and is a ten-millisecond unit test: the cap, the floors, the
     * decay curve, the personality weighting, the witness share. The single claim no unit test in
     * {@code :common} can make is the one {@code DESIGN.md} §4 step 2 actually rests on — that
     * {@code AABB.inflate(24)} and {@code hasLineOfSight} pick out the right villagers in a real
     * level, with real blocks in the way and a real entity index underneath. So the leg is built
     * around exactly the two facts a game is needed for:
     *
     * <ul>
     *   <li>one villager is put <b>behind a stone wall</b>, well inside the box. If it records the
     *       deed, the line-of-sight filter is not running;</li>
     *   <li>one villager is put <b>forty blocks up</b>, in clear air with nothing in the way. If it
     *       records the deed, the box is not bounding anything.</li>
     * </ul>
     *
     * <p>The two failures are deliberately opposite: the walled one has range and no sight, the high
     * one has sight and no range. A single mistake cannot pass both.
     *
     * <p><b>Traits are zeroed first, and that is the point rather than a convenience.</b>
     * {@code WORKPLAN.md}'s exit criterion is "+3 subject, +1 each witness", which is the structural
     * arithmetic with the personality weight standing at neutral. Left rolled, these six would each
     * produce a different and perfectly correct number, and the leg would be asserting the weight
     * table rather than the scan. The weight table is proven next door, in {@code DeedsTest}, where
     * it can be asserted exactly.
     */
    private static void runWitnessCheck(MinecraftServer server, ServerLevel level) {
        switch (step) {
            case 16 -> {
                if (RESIDENTS.size() < 6) {
                    record(false, "WITNESS needs six residents to cast, found " + RESIDENTS.size());
                    finish(server, false);
                    return;
                }
                ServerPlayer player = player(server);
                BlockPos stand = villageSite.offset(12, 1, 2);
                teleport(player, level, stand.getX() + 0.5, stand.getY(), stand.getZ() + 0.5);

                // A wall between the fifth villager and the player. Three blocks tall, because an
                // eye is at 1.62 and a two-block wall is a wall you can see over. Laid on the
                // negative-z side: everything the leg touches has to stay on the platform this
                // harness built, or the fixture's floor is whatever terrain the seed happened to
                // put there — and a villager standing in a hillside is a leg that fails for a
                // reason that has nothing to do with line of sight.
                for (int x = -3; x <= 3; x++) {
                    for (int y = 0; y <= 2; y++) {
                        level.setBlockAndUpdate(stand.offset(x, y, -3), Blocks.STONE.defaultBlockState());
                    }
                }

                WITNESSES_IN_SIGHT.clear();
                deedSubject = RESIDENTS.get(0).personaId();
                place(server, level, deedSubject, stand.getX() + 1.5, stand.getY(), stand.getZ() + 0.5);
                for (int i = 1; i <= 3; i++) {
                    UUID personaId = RESIDENTS.get(i).personaId();
                    WITNESSES_IN_SIGHT.add(personaId);
                    place(server, level, personaId,
                            stand.getX() + 1.5 + i, stand.getY(), stand.getZ() + 1.5);
                }
                witnessBehindAWall = RESIDENTS.get(4).personaId();
                place(server, level, witnessBehindAWall, stand.getX() + 0.5, stand.getY(), stand.getZ() - 5.5);
                witnessOutOfRange = RESIDENTS.get(5).personaId();
                place(server, level, witnessOutOfRange, stand.getX() + 0.5, stand.getY() + 40, stand.getZ() + 0.5);

                // Typical personalities, so the numbers under test are the structural ones.
                //
                // Deliberately Personality.typical() rather than eight zeroes. Since the close of
                // session 05 the weight table is centred on the population the generator actually
                // produces, so a villager with no personality at all scores *below* nominal — the
                // reference point is the average villager, not an impossible one. Zeroing here
                // would make the exit criterion read +2/+1 and look like a broken cap.
                NpcRegistry registry = NpcRegistry.get(server);
                for (Resident resident : RESIDENTS) {
                    registry.persona(resident.personaId()).ifPresent(persona ->
                            registry.put(persona.withTraits(Personality.typical())));
                }
                beginAwait(400);
            }
            case 17 -> {
                ServerPlayer player = player(server);
                BlockPos stand = villageSite.offset(12, 1, 2);
                if (stillWaiting(server, () -> castIsInPlace(server, level, stand), false,
                        "the six villagers to settle into their places")) {
                    return;
                }
                record(castIsInPlace(server, level, stand),
                        "WITNESS the cast is in place: five villagers inside the 24-block box, one "
                                + "forty blocks up outside it");

                NpcRegistry registry = NpcRegistry.get(server);
                Villager subject = (Villager) boundEntity(server, deedSubject).orElse(null);
                if (subject == null) {
                    record(false, "WITNESS the subject villager is not loaded");
                    finish(server, false);
                    return;
                }
                UUID actor = player.getUUID();
                int day = Deed.dayOf(level);

                DeedBus.Result fed = DeedBus.emit(level, DeedType.FED_HUNGRY, player, subject);
                record(fed.witnesses() == 3, "WITNESS the scan found " + fed.witnesses()
                        + " witnesses; three could see it, one was behind a wall and one was out of range");

                Bond onSubject = registry.bonds().at(deedSubject, actor, day);
                record(onSubject.trust() == 3 && onSubject.warmth() == 3,
                        "DEED the villager who was fed gained +3 (trust " + onSubject.trust()
                                + ", warmth " + onSubject.warmth() + ")");

                int moved = 0;
                for (UUID witness : WITNESSES_IN_SIGHT) {
                    Bond bond = registry.bonds().at(witness, actor, day);
                    if (bond.trust() == 1 && bond.warmth() == 1) {
                        moved++;
                    } else {
                        record(false, "DEED witness " + witness + " gained " + bond
                                + ", expected +1/+1");
                    }
                }
                record(moved == 3, "DEED " + moved + "/3 witnesses who could see it gained +1");

                record(registry.bonds().stored(witnessBehindAWall, actor).isEmpty(),
                        "CANSEE the villager behind the wall recorded nothing, though it was five "
                                + "blocks away and well inside the box");
                record(registry.bonds().stored(witnessOutOfRange, actor).isEmpty(),
                        "RANGE the villager forty blocks up recorded nothing, though nothing at all "
                                + "was in its way");

                // WORKPLAN.md's second exit criterion: nine feedings in one day, and the cap holds.
                for (int i = 0; i < 8; i++) {
                    DeedBus.emit(level, DeedType.FED_HUNGRY, player, subject);
                }
                Bond capped = registry.bonds().at(deedSubject, actor, day);
                record(capped.warmth() == Bond.DAILY_CAP && capped.trust() == Bond.DAILY_CAP,
                        "CAP nine feedings in one day left trust " + capped.trust() + " and warmth "
                                + capped.warmth() + ", cap " + Bond.DAILY_CAP);
                record(Deed.dayOf(level) == day,
                        "CAP all nine landed on the same in-game day (" + day + ")");

                // Session 06, and it needs no new fixture because the nine feedings above already
                // are one. Same type, same actor, same subject, same settlement, same day, same
                // severity — so they are nine emits of ONE deed, and a content-addressed ring holds
                // one of them. That is the dedupe running through the real emit path in a real game.
                record(registry.memories().of(deedSubject).size() == 1,
                        "RING nine identical feedings left "
                                + registry.memories().of(deedSubject).size()
                                + " memory rather than nine — the ring is content-addressed");

                // A genuinely different deed on the same day, so the ring has an order to lose
                // across the reload rather than a single entry that cannot be got wrong.
                DeedBus.Result gift = DeedBus.emit(level, DeedType.GIFT_WANTED, player, subject);
                List<Deed> ring = registry.memories().of(deedSubject);
                record(ring.size() == 2, "RING a different kind of deed on the same day is a second "
                        + "memory (" + ring.size() + ")");
                record(ring.size() == 2 && ring.get(0).type() == DeedType.FED_HUNGRY
                                && ring.get(1).type() == DeedType.GIFT_WANTED,
                        "RING the ring is ordered oldest first " + ring.stream()
                                .map(entry -> entry.type().name()).toList());
                record(gift.remembered() == gift.witnesses() + 1,
                        "RING the subject and all " + gift.witnesses() + " witnesses recorded it ("
                                + gift.remembered() + ")");

                // The one claim in this session that no unit test in :common can make, because it is
                // about DeedBus rather than about Memories: step 3 does not depend on step 4. Every
                // one of these five has spent their whole daily allowance on the nine feedings, so
                // this gift moves nothing at all — and is remembered by all five anyway. Move the
                // ring append below the bond guard in DeedBus.emit and this line goes red on its own.
                record(gift.bondsMoved() == 0 && gift.remembered() == gift.witnesses() + 1,
                        "RING the gift moved " + gift.bondsMoved() + " bonds — every allowance was "
                                + "already spent — and was remembered by " + gift.remembered()
                                + " people regardless. Seeing something is not the same as it "
                                + "changing your mind about somebody");
                record(registry.memories().of(witnessBehindAWall).isEmpty(),
                        "RING the villager behind the wall remembers nothing either — no bond and "
                                + "no memory");

                // The instruments the owner reads the criteria with, run where there is something in
                // them, and through the real dispatcher so the whole command is exercised.
                server.getCommands().performPrefixedCommand(
                        player.createCommandSourceStack(), "namesake debug bonds");
                server.getCommands().performPrefixedCommand(
                        player.createCommandSourceStack(), "namesake debug deeds "
                                + subject.getUUID());

                // Session 08. Three more feedings, one at each villager who could see, so the
                // settlement has five distinct stories to tell rather than two. That is not padding:
                // whether one particular villager takes one particular telling is a coin at
                // Gossip.TRANSFER_CHANCE over persona ids a real game mints at random, and a leg
                // that runs on every push cannot be a 3% coin. Five stories against two hearers is
                // twenty independent flips, which puts "nobody heard anything" at two in ten
                // thousand — and the assertion below is on the deterministic half besides.
                for (UUID witness : WITNESSES_IN_SIGHT) {
                    boundEntity(server, witness)
                            .filter(entity -> entity instanceof Villager)
                            .ifPresent(entity -> DeedBus.emit(
                                    level, DeedType.FED_HUNGRY, player, (Villager) entity));
                }
                gossipQueuedAtStart = registry.gossip().size();
                record(gossipQueuedAtStart > 0,
                        "GOSSIP step 6: " + gossipQueuedAtStart + " story(s) entered the "
                                + "settlement's deque when the deeds were emitted");
                record(registry.memories().of(witnessBehindAWall).isEmpty()
                                && registry.memories().of(witnessOutOfRange).isEmpty(),
                        "GOSSIP neither villager who could not see it knows anything yet");

                // The absence branch, rendered here rather than after the drain — because after the
                // drain there may be nobody left in the village who has seen nothing, and a state
                // that has nobody to render is a state nobody has measured. That is session 07's
                // defect exactly, and the first attempt at this walked into it: the guard reported
                // honestly that it had found no empty ring, which is better than a false green and
                // is still not a measurement. Right now the wall villager's ring is empty by
                // assertion, one line above.
                emptyRingRows.clear();
                boundEntity(server, witnessBehindAWall).ifPresent(entity ->
                        server.getCommands().performPrefixedCommand(
                                capturing(server, player, emptyRingRows),
                                "namesake debug deeds " + entity.getUUID()));

                // Five stories, two drains each, one drain per settlement per 250 ticks. Waiting on
                // game time rather than on chunk IO, so sprinting is the right instrument here —
                // and it is a poll against the condition with a deadline, never a blind sprint,
                // because a blind sprint cannot tell "not yet" from "never".
                beginAwait(6000);
            }
            default -> finish(server, true);
        }
    }

    /** How many stories the settlement had to tell before the drain was given any ticks. */
    private static int gossipQueuedAtStart;

    /** What a villager who has seen nothing looks like on a screen. Captured before the drain runs. */
    private static final List<String> emptyRingRows = new ArrayList<>();

    /**
     * How long a hundred simulated in-game days may take before it is a regression rather than a
     * slow machine. Ten ticks, against 21 ms measured on the owner's machine at the close of
     * session 08 — loose on purpose. See the note at the assertion.
     */
    private static final long SIMULATION_CEILING_MILLIS = 500L;

    /**
     * <b>Session 08's one leg, and it is the claim no unit test in {@code :common} can make.</b>
     *
     * <p>The propagation <i>curve</i> — 60% of a village within two in-game days — is a claim about
     * time, and {@code WORKPLAN.md} rules that those belong in {@code net.namesake.sim}, which is
     * where it is. What only a running game can show is everything on the other side of the seam:
     * that a loader's server-tick hook is actually wired to {@link Gossip#onServerTick}, that the
     * 250-tick cadence fires against a real {@code MinecraftServer}'s tick count, and that a deque
     * written by an emit is spent by a tick rather than by a test calling a method.
     *
     * <p><b>And the assertion is the thesis in one line.</b> Session 05 proved the villager behind a
     * wall records nothing, five blocks away and well inside the box. This proves that two in-game
     * hours later they have heard about it anyway — at a confidence that says they were told rather
     * than that they saw it. The wall stops them seeing; it does not stop the village talking.
     */
    private static void runGossipCheck(MinecraftServer server, ServerLevel level) {
        NpcRegistry registry = NpcRegistry.get(server);
        if (stillWaiting(server, () -> registry.gossip().isEmpty(), true,
                "the settlement to finish telling its " + gossipQueuedAtStart + " story(s)")) {
            return;
        }

        record(registry.gossip().isEmpty(),
                "GOSSIP the drain ran on the server tick hook and spent every story: "
                        + registry.gossip().size() + " left of " + gossipQueuedAtStart);

        List<Deed> wall = registry.memories().of(witnessBehindAWall);
        List<Deed> high = registry.memories().of(witnessOutOfRange);
        int toldOf = wall.size() + high.size();
        record(toldOf > 0,
                "GOSSIP the villager behind the wall and the one forty blocks up were told about "
                        + toldOf + " thing(s) they could not possibly have seen");
        record(wall.stream().noneMatch(deed -> deed.confidence() == Deed.FIRST_HAND)
                        && high.stream().noneMatch(deed -> deed.confidence() == Deed.FIRST_HAND),
                "GOSSIP and not one of them at first hand — they were told, they did not see it");

        // Every confidence anywhere in the world, which is the "descending" half of the criterion
        // and the bound on how far a story travels, read off the save rather than off the constants.
        Set<Integer> confidences = new java.util.TreeSet<>();
        for (Persona persona : registry.all()) {
            registry.memories().of(persona.id())
                    .forEach(deed -> confidences.add((int) deed.confidence()));
        }
        record(confidences.stream().allMatch(c -> c == Deed.FIRST_HAND || c == 70 || c == 49),
                "GOSSIP the whole world holds three confidences and no others: " + confidences
                        + " — 100 watched it, 70 was told, 49 cannot name who");

        long unattributed = registry.all().stream()
                .flatMap(persona -> registry.memories().of(persona.id()).stream())
                .filter(deed -> !deed.isAttributed())
                .count();
        record(registry.all().stream().noneMatch(persona ->
                        registry.bonds().stored(persona.id(), Deed.UNKNOWN_ACTOR).isPresent()),
                "GOSSIP " + unattributed + " unattributed rumour(s) in this village and not one "
                        + "bond about nobody — a story you cannot attribute moves no opinion");

        // The instrument the owner's half of the exit criterion is read with, on a villager who was
        // told rather than one who watched, through the real dispatcher — and measured.
        //
        // Session 07 shipped three over-width lines because every guard it wrote measured a
        // POPULATED table, and the branches that fire when there is nothing to report were never
        // rendered at all. So both states go through the dispatcher here: a ring with a rumour in
        // it, and one of a villager who has seen nothing. The second is the state a player is in
        // for their first hour.
        ServerPlayer player = player(server);
        List<String> emitted = new ArrayList<>(emptyRingRows);
        boundEntity(server, witnessBehindAWall).ifPresent(entity ->
                server.getCommands().performPrefixedCommand(
                        capturing(server, player, emitted),
                        "namesake debug deeds " + entity.getUUID()));

        record(emptyRingRows.stream().anyMatch(line -> line.contains("have not seen anything")),
                "GOSSIP the empty-ring branch was rendered to a player before anybody was told "
                        + "anything — the state a real player meets first, and the one session 07 "
                        + "shipped over the chat width because no fixture ever reached it");
        record(!emitted.isEmpty(), "GOSSIP the deed ring reached a player through the real "
                + "dispatcher in " + emitted.size() + " line(s), in both of its states");
        String widest = emitted.stream().max(Comparator.comparingInt(String::length)).orElse("");
        record(widest.length() <= Reports.CHAT_WIDTH,
                "GOSSIP the widest deed row a player sees is " + widest.length() + " characters, "
                        + "against a " + Reports.CHAT_WIDTH + "-character chat width: |" + widest + "|");
        record(emitted.stream().noneMatch(line -> line.contains("\r")),
                "GOSSIP no deed row carries a carriage return");

        runResidencyCheck(server, player);
        runTheSimulation(server, player);
        writeSubjects(level);
        advance(server, 5);
    }

    // --- session 09: the name swap, in a running game ----------------------------------------------

    /**
     * <b>Residency earned, and the sentence changing because of it.</b> Session 09's one leg.
     *
     * <p>{@code WORKPLAN.md} draws the line and this session sits right on it. The residency
     * arithmetic is pure and is a unit test; the pool and register selection are pure and are a unit
     * test; every one of the hundred and sixty lines is measured against the chat width by
     * {@code CommandLayoutTest}. <b>What only a running game can show is that a threshold crossed on
     * one day is still crossed after the world has been to disk and back at a new schema, and that
     * the villager's line is different on the other side.</b> That is what this records and what the
     * verify phase checks.
     *
     * <p>The deeds go through {@code DeedBus.record} — the shipped door, the one session 07's
     * simulation uses — rather than through a bond written by hand, because "the villager trusts you"
     * and "the villager trusts you <i>because of something you did</i>" are different claims and only
     * the second one is the mod working.
     *
     * <p><b>Gifts rather than feedings, deliberately.</b> {@code DESIGN.md} §5 grants residency two
     * ways, and {@code FED_HUNGRY} ×3 is the other one — so feeding would satisfy the deed route
     * within three days and this leg would never exercise the band the owner ruled. A wanted gift is
     * neither of §5's significant deeds, so the only thing that can grant residency here is three
     * residents crossing {@link Residency#TRUST_THRESHOLD}.
     */
    /**
     * The "before" half, captured the moment the village exists and <b>before anybody has been
     * fed</b>.
     *
     * <p><b>This ran later on the first attempt and the leg turned red, which is the leg working.</b>
     * Session 05's witness legs feed a villager nine times and then feed three more, and
     * {@code DESIGN.md} §5's <i>second</i> route grants residency on {@code FED_HUNGRY} ×3 — so by
     * the time the gossip legs are finished this player is already a resident, by the deed route,
     * with no trust band anywhere near. The stranger state is real and it is early, so this is where
     * it has to be read.
     */
    private static void recordStrangerBefore(MinecraftServer server) {
        NpcRegistry registry = NpcRegistry.get(server);
        ServerPlayer player = player(server);
        if (registeredSettlement == null || player == null) {
            return;
        }
        Persona speaker = residencySpeakerIn(registry, registeredSettlement.id());
        if (speaker == null) {
            return;
        }
        int settlementId = registeredSettlement.id();
        int today = Deed.dayOf(server.overworld());

        residencySpeaker = speaker.id();
        residencyPlayer = player.getUUID();
        residencyName = player.getGameProfile().getName();
        residencySettlement = settlementId;

        Dialogue.Spoken spoken = Dialogue.speak(registry, speaker, residencyPlayer, residencyName,
                0, today, RESIDENCY_SEED);
        String strangerWord = Voice.of(Culture.byId(speaker.cultureId())).strangerAddress();

        record(!Residency.isResident(registry, settlementId, residencyPlayer, today),
                "RESIDENCY nobody in this village has done anything with the player yet, so it has "
                        + "not taken them in");
        record(strangerWord.equals(spoken.address()),
                "RESIDENCY before it, " + Names.of(speaker).full() + " calls them '"
                        + spoken.address() + "' — this culture's word for somebody from elsewhere");
        record(spoken.pool() == net.namesake.dialogue.Pool.STRANGER,
                "RESIDENCY and speaks to them out of the stranger pool: "
                        + String.join(" | ", Dialogue.rows(speaker, spoken)));
    }

    /**
     * <b>Residency earned by the band, and the sentence changing because of it.</b>
     *
     * <p>The stranger half is {@link #recordStrangerBefore}, which runs before session 05's legs feed
     * anybody. This is the other end: gifts until three residents cross
     * {@link Residency#TRUST_THRESHOLD}, and then the same villager asked again.
     *
     * <p><b>Gifts rather than feedings, deliberately.</b> {@code DESIGN.md} §5 grants residency two
     * ways, and {@code FED_HUNGRY} ×3 is the other one — which the harness's own earlier legs have
     * already satisfied by this point, and which is a finding rather than an inconvenience: the deed
     * route is <i>much</i> cheaper than the band, and it is recorded here as a number. A wanted gift
     * is neither of §5's significant deeds, so the only thing it can move is the band the owner
     * ruled at the close of session 08.
     */
    private static void runResidencyCheck(MinecraftServer server, ServerPlayer player) {
        NpcRegistry registry = NpcRegistry.get(server);
        if (registeredSettlement == null || residencySpeaker == null) {
            Namesake.LOGGER.info("[harness] no settlement registered; skipping the residency legs");
            return;
        }
        int settlementId = registeredSettlement.id();
        UUID viewer = player.getUUID();
        String playerName = player.getGameProfile().getName();
        Persona speaker = registry.persona(residencySpeaker).orElse(null);
        if (speaker == null) {
            record(false, "RESIDENCY the villager the stranger line was read from is gone");
            return;
        }

        List<Persona> locals = localsOf(registry, settlementId);
        List<Persona> befriended = locals.subList(0, Residency.RESIDENTS_REQUIRED);
        int today = Deed.dayOf(server.overworld());

        // The finding this leg turned up on its first run, kept as evidence rather than tidied away.
        Residency.Verdict already = Residency.verdict(registry, settlementId, viewer, today);
        record(already.granted() && already.route() == Residency.Route.DEED,
                "RESIDENCY §5's second route is much the cheaper of the two: " + already.feedings()
                        + " hungry people fed has already granted it, with only "
                        + already.residentsAtThreshold() + " resident(s) at "
                        + Residency.TRUST_THRESHOLD + " trust");

        // Four gifts an in-game day is what fills a typical villager's allowance, and the loop runs
        // until three of them are over the threshold rather than for a number of days somebody
        // guessed: how long it takes is a property of their personalities.
        int days = 0;
        while (days < RESIDENCY_DAY_LIMIT && Residency.verdict(registry, settlementId, viewer,
                today + days).residentsAtThreshold() < Residency.RESIDENTS_REQUIRED) {
            for (Persona resident : befriended) {
                for (int gift = 0; gift < 4; gift++) {
                    DeedBus.record(registry, Deed.of(DeedType.GIFT_WANTED, viewer, resident.id(),
                                    settlementId, today + days),
                            List.of(resident), 0);
                }
            }
            days++;
            // Spent through the shipped drain rather than left in the deque, so the state written to
            // disk is a settled one. The tick hook is already proven by the gossip legs above; this
            // is the same method it calls.
            //
            // Run to exhaustion, and that it *reaches* exhaustion is bounded by construction rather
            // than hoped for: a deque holds at most Gossip.DEQUE_CAPACITY stories and a story is
            // spent after two drains, so 64 is the worst case against the 96 an in-game day allows.
            // It matters because recordMemories runs immediately after this and the verify phase
            // compares against what it recorded — a story still travelling when the world saved
            // would change a ring after it had been written down, and the ring-reload leg would fail
            // for a reason that has nothing to do with reloading.
            for (int drain = 0; drain < Gossip.DRAINS_PER_DAY && !registry.gossip().isEmpty(); drain++) {
                Gossip.drainEverySettlement(registry, today + days);
            }
        }

        Residency.Verdict verdict = Residency.verdict(registry, settlementId, viewer, today + days);
        record(verdict.residentsAtThreshold() >= Residency.RESIDENTS_REQUIRED
                        && verdict.route() == Residency.Route.BAND,
                "RESIDENCY and the band is met too after " + days + " in-game day(s) of gifts: "
                        + verdict.residentsAtThreshold() + " resident(s) at "
                        + Residency.TRUST_THRESHOLD + " trust, which is the route session 08 ruled");

        Dialogue.Spoken spoken = Dialogue.speak(registry, speaker, viewer, playerName,
                0, today + days, RESIDENCY_SEED);
        record(playerName.equals(spoken.address()),
                "RESIDENCY and now " + Names.of(speaker).full() + " calls them '" + spoken.address()
                        + "' rather than '"
                        + Voice.of(Culture.byId(speaker.cultureId())).strangerAddress() + "'");

        List<String> after = withTheirName(registry, speaker, viewer, playerName, today + days);
        record(String.join(" ", after).contains(playerName),
                "RESIDENCY on screen, in a running game: " + String.join(" | ", after));
        record(after.stream().allMatch(line -> line.length() <= Reports.CHAT_WIDTH),
                "RESIDENCY the widest thing they say is "
                        + after.stream().mapToInt(String::length).max().orElse(0)
                        + " characters, against a " + Reports.CHAT_WIDTH + "-character chat width");
        record(after.stream().noneMatch(line -> line.contains("\r")),
                "RESIDENCY no line carries a carriage return");

        // On the screen as well as in the verdict file, so a run leaves behind what a player sees.
        after.forEach(line -> player.sendSystemMessage(Component.literal(line)));
    }

    /**
     * The speaker: deliberately the resident the player will <b>not</b> befriend.
     *
     * <p>What is being shown is a villager who has personally never met you using your name because
     * the village has taken you in. {@code DESIGN.md} §5's swap belongs to the settlement rather than
     * to one relationship, and that is the thesis in miniature — what one villager did changed what a
     * different one calls you.
     */
    private static Persona residencySpeakerIn(NpcRegistry registry, int settlementId) {
        List<Persona> locals = localsOf(registry, settlementId);
        if (locals.size() < Residency.RESIDENTS_REQUIRED + 1) {
            record(false, "RESIDENCY the village has " + locals.size() + " generated resident(s), "
                    + "which is too few to earn residency from and still have a stranger to ask");
            return null;
        }
        return locals.get(locals.size() - 1);
    }

    private static List<Persona> localsOf(NpcRegistry registry, int settlementId) {
        return registry.all().stream()
                .filter(persona -> persona.settlementId() == settlementId)
                .filter(Persona::isGenerated)
                .sorted(Comparator.comparing(persona -> persona.id().toString()))
                .toList();
    }

    /**
     * A line that actually contains the address, for the log and the screen.
     *
     * <p>Not every authored line carries {@code {you}} — plenty of them are "Back, are you?" — so a
     * run that happened to land on one of those would print a perfectly correct sentence that shows
     * nothing. The <i>assertion</i> is on {@code Spoken.address()}, which is the swap itself; this is
     * for the human reading the verdict file, and it says so rather than pretending the search is
     * part of the test.
     */
    private static List<String> withTheirName(NpcRegistry registry, Persona speaker, UUID viewer,
                                              String playerName, int day) {
        for (long seed = 0; seed < 64; seed++) {
            Dialogue.Spoken spoken = Dialogue.speak(registry, speaker, viewer, playerName, 0, day, seed);
            if (spoken.line().contains(playerName)) {
                return Dialogue.rows(speaker, spoken);
            }
        }
        return Dialogue.rows(speaker, Dialogue.speak(registry, speaker, viewer, playerName,
                0, day, RESIDENCY_SEED));
    }

    /** However many in-game days it takes; a bound so a bad roll cannot hang the run. */
    private static final int RESIDENCY_DAY_LIMIT = 90;

    /** One seed, so the before and the after are the same villager saying the same kind of thing. */
    private static final long RESIDENCY_SEED = 20260815L;

    /** Which villager the verify phase should ask again after the reload. */
    private static UUID residencySpeaker;
    private static UUID residencyPlayer;
    private static String residencyName;
    private static int residencySettlement = Persona.UNASSIGNED;


    // --- session 07: the simulation, inside a real server ------------------------------------------

    /**
     * Runs a hundred in-game days from inside a running game, and proves it changed nothing here.
     *
     * <p><b>Not a new leg, and that is a decision rather than a shortcut.</b> {@code WORKPLAN.md}
     * draws the line: anything a unit test can prove belongs in a unit test, and session 07's report
     * layout, percentiles, bucketing and arithmetic are all pure and all covered there. The harness
     * grows one leg per session that <i>has</i> one, and this session's numbers do not need a game.
     * So this is four extra assertions inside a phase that was already running — no new launch, no
     * new CI job, and about a millisecond of the six minutes the phase already costs.
     *
     * <p><b>Three claims, and none of them is checkable in {@code :common}.</b>
     *
     * <ol>
     *   <li><b>A hundred days completes without wedging a server.</b> The simulation runs
     *       synchronously on the server thread, so a slow one is a stall a player would feel. The
     *       unit test measures it against the ledger's one-minute criterion; this measures it against
     *       a tick, which is the number that actually matters.</li>
     *   <li><b>It does not touch this world.</b> Structurally it cannot — its registry is built with
     *       {@code new} and never handed to a {@code DimensionDataStorage} — but "structurally
     *       cannot" is a claim, and this project queries rather than claiming. By this point the live
     *       registry holds nine personas, a settlement, four bonds and eight deeds across four rings,
     *       which is the state a mistake here would damage.</li>
     *   <li><b>The commands survive the real dispatcher.</b> {@code CommandLayoutTest} measures the
     *       row builders; this measures what Brigadier actually emits to a player, which is the thing
     *       somebody reads off a screen. Session 06 shipped a carriage return that was invisible to
     *       every instrument in the repo because they all read a log file, and a log file has no
     *       width.</li>
     * </ol>
     */
    private static void runTheSimulation(MinecraftServer server, ServerPlayer player) {
        NpcRegistry registry = NpcRegistry.get(server);
        String before = registry.size() + "/" + registry.bonds().size() + "/"
                + registry.memories().size();

        long begun = System.nanoTime();
        Simulation.Outcome outcome = Simulation.run(
                Simulation.Plan.standard(20260814L, 100, PlayerModel.ATTENTIVE));
        long millis = (System.nanoTime() - begun) / 1_000_000L;

        record(outcome.chronicle().size() == 100,
                "SIMULATE a hundred in-game days emitted " + outcome.chronicle().size()
                        + " deeds on the server thread in " + millis + " ms");
        // Recorded rather than asserted against a tick, and session 04 already ruled why: a
        // wall-clock number from a shared runner whose neighbours we cannot see is not evidence.
        // Session 07 wrote this as `millis < 50` because a hundred days cost 6-8 ms and the margin
        // made the question look free; session 08 took it to 21 ms on this machine and CI turned
        // red at 51 on a runner. The useful number is the one measured on a known machine and
        // written into the ledger with its conditions. What stays in CI is a ceiling loose enough
        // that only an order-of-magnitude regression trips it on any machine — which is the same
        // line WORKPLAN.md draws between the profiler and the simulation, applied to a leg that
        // predates it mattering.
        record(millis < SIMULATION_CEILING_MILLIS,
                "SIMULATE the run cost " + millis + " ms, against a " + SIMULATION_CEILING_MILLIS
                        + " ms ceiling. A tick is 50 ms and this machine's number belongs in the "
                        + "ledger with its conditions; a shared runner's does not");

        String after = registry.size() + "/" + registry.bonds().size() + "/"
                + registry.memories().size();
        record(before.equals(after), "SIMULATE this world is untouched at " + after
                + " persona(s)/bond(s)/deed(s) — the simulation's registry is its own");

        List<String> emitted = new ArrayList<>();
        CommandSourceStack capturing = capturing(server, player, emitted);

        for (String command : List.of("namesake debug stats", "namesake debug earnrate",
                "namesake debug simulate 100 ATTENTIVE")) {
            server.getCommands().performPrefixedCommand(capturing, command);
        }

        record(!emitted.isEmpty(), "SIMULATE the three session 07 commands emitted "
                + emitted.size() + " line(s) through the real dispatcher");
        String widest = emitted.stream().max(Comparator.comparingInt(String::length)).orElse("");
        record(widest.length() <= Reports.CHAT_WIDTH,
                "SIMULATE the widest line a player sees is " + widest.length() + " characters, "
                        + "against a " + Reports.CHAT_WIDTH + "-character chat width: |" + widest + "|");
        record(emitted.stream().noneMatch(line -> line.contains("\r")),
                "SIMULATE no line carries a carriage return, which Minecraft draws as a "
                        + "missing-glyph box");
    }

    /**
     * A command source that collects every line the dispatcher would put on a player's screen.
     *
     * <p>The reason this exists rather than reading the log: <b>a log file has no width.</b> Session
     * 06 shipped a ninety-character deed row and a carriage return, and every instrument in the repo
     * was green because every one of them read the log. This reads what Brigadier actually emits,
     * split on the newlines the client would break on.
     */
    private static CommandSourceStack capturing(MinecraftServer server, ServerPlayer player,
                                                List<String> into) {
        CommandSource sink = new CommandSource() {
            @Override
            public void sendSystemMessage(Component component) {
                into.addAll(List.of(component.getString().split("\n", -1)));
            }

            @Override
            public boolean acceptsSuccess() {
                return true;
            }

            @Override
            public boolean acceptsFailure() {
                return true;
            }

            @Override
            public boolean shouldInformAdmins() {
                return false;
            }
        };
        return new CommandSourceStack(sink, player.position(), player.getRotationVector(),
                player.serverLevel(), 4, "Harness", Component.literal("Harness"), server, player);
    }

    // --- session 10: the road, the border, and the first blocks this mod has ever placed ----------

    /**
     * <b>Ship-or-kill, in a running game.</b> A second village, a road between them as real blocks,
     * and a villager who has never met you saying they have heard of you.
     *
     * <p>{@code WORKPLAN.md} draws the line and this session sits on both sides of it. The graph is
     * arithmetic and is {@code RoadGraphTest}; the A* is arithmetic over a fixture heightmap and is
     * {@code RoadPathTest}; the border's confidence, its delay and its rates are arithmetic and are
     * {@code GossipTest}; the propagation curve across two settlements is a claim about time and is
     * {@code SimulationTest}. <b>Three things are left, and every one of them is a property of a
     * running world:</b>
     *
     * <ol>
     *   <li>That a second bell produces a second settlement, and that the graph joins them
     *       <i>through the shipped registry</i> rather than through a list of points in a test.</li>
     *   <li>That the materialiser turns natural ground into road — and <b>leaves a player's floor
     *       alone</b>. That is the claim the ledger makes about what this will not touch, and it is
     *       the one no unit test can make, because the whole of it is what
     *       {@code MOTION_BLOCKING_NO_LEAVES} answers over blocks somebody put there.</li>
     *   <li>That a story crosses on the server's own tick hook and is <i>not</i> told on the day it
     *       happened, and that a villager in the far village then selects
     *       {@code Register.ABOUT_OTHERS} — the sentence session 09 wrote and session 10 only
     *       delivered.</li>
     * </ol>
     *
     * <p><b>The fixture predicts the route rather than guessing where it will run.</b> The router is
     * a pure function of two chunk positions and {@code RoadNetwork.terrainOf}, so the harness calls
     * exactly that and lays its ground over the answer. A second copy of the terrain lambda here
     * would be a fixture testing itself; this way, a route that moves takes the fixture with it.
     */
    private static void runRoadCheck(MinecraftServer server, ServerLevel level) {
        NpcRegistry registry = NpcRegistry.get(server);
        switch (step) {
            case 19 -> {
                // Midway between the two bells, so both villages and the whole road are inside the
                // loaded area. Nothing here may load a chunk on purpose — see RoadNetwork.
                neighbourSite = villageSite.offset(NEIGHBOUR_OFFSET, 0, 0);
                teleport(player(server), level, villageSite.getX() + NEIGHBOUR_OFFSET / 2, 200,
                        villageSite.getZ());
                beginAwait(2400);
            }
            case 20 -> {
                ChunkPos chunk = new ChunkPos(neighbourSite);
                if (stillWaiting(server, () -> level.getChunkSource().hasChunk(chunk.x, chunk.z),
                        false, "the second village's chunks to load")) {
                    return;
                }
                // Session 03's lesson, fifth application: read the heightmap only once the chunk is
                // actually here, or the village is built at y = -64 inside the deepslate.
                neighbourSite = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                        neighbourSite);
                record(neighbourSite.getY() > level.getMinBuildHeight(),
                        "ROAD the second village site is on real ground at y=" + neighbourSite.getY());

                prepareTheGround(level);
                buildVillage(level, neighbourSite);
                for (int i = 0; i < 6; i++) {
                    Villager villager = EntityType.VILLAGER.spawn(level,
                            neighbourSite.offset(1 + (i % 3) + (i < 3 ? 0 : 20), 1, 1),
                            MobSpawnType.COMMAND);
                    if (villager == null) {
                        throw new IllegalStateException("could not spawn neighbour villager " + i);
                    }
                    villager.setPersistenceRequired();
                    villager.setNoAi(true);
                }
                beginAwait(3000);
            }
            case 21 -> {
                if (stillWaiting(server, () -> registry.settlements().size() >= 2
                                && neighbours(registry).size() >= 4, false,
                        "the second bell to be surveyed and its villagers to be generated")) {
                    return;
                }
                record(registry.settlements().size() == 2,
                        "ROAD a second bell made a second settlement ("
                                + registry.settlements().size() + ")");
                if (registry.settlements().size() < 2) {
                    finish(server, false);
                    return;
                }
                int far = registry.settlements().all().stream()
                        .map(Settlement::id)
                        .filter(id -> id != registeredSettlement.id())
                        .findFirst().orElseThrow();
                roadEdge = new RoadEdge(registeredSettlement.id(), far);

                RoadGraph graph = Roads.graphOf(registry.settlements());
                record(graph.joins(roadEdge.a(), roadEdge.b()),
                        "ROAD the two settlements are neighbours in the graph a story crosses: "
                                + graph.edges());
                record(neighbours(registry).size() >= 4, "ROAD the far village has "
                        + neighbours(registry).size() + " resident(s) who have never met the player");

                // A fresh in-game day, so these feedings are new deeds rather than the same content-
                // addressed ones session 05's legs already emitted and the village already spent.
                bumpTheDay(level, 1);
                dayTheDeedsHappened = Deed.dayOf(level);
                // Every villager in the first village, not a couple of them — for session 08's
                // reason, restated because it bites harder here. Whether one resident of the far
                // village takes one telling is a coin at Gossip.ARRIVES_BY_ROAD, so a leg that runs
                // on every push cannot rest on one story reaching six people: that is a 62% test,
                // and CI proved it by failing on exactly that after this tightening was written
                // once, lost to a breakage script's `git checkout -- .`, and not restored. Six
                // stories against six hearers is thirty-six independent flips, which puts "nobody
                // there heard anything" at three in a thousand.
                ServerPlayer player = player(server);
                int fed = 0;
                for (Villager villager : villagersNearTheBell(level)) {
                    if (DeedBus.emit(level, DeedType.FED_HUNGRY, player, villager).happened()) {
                        fed++;
                    }
                }
                storiesSentDownTheRoad = registry.gossip().of(registeredSettlement.id()).size();
                record(fed >= 6 && storiesSentDownTheRoad >= 6,
                        "ROAD " + fed + " feeding(s) in the first village put "
                                + storiesSentDownTheRoad + " story(s) in its deque on day "
                                + dayTheDeedsHappened);
                // Waiting on the drain, which is game time: sprinting is the right instrument, and
                // it is a poll against the condition rather than a blind run.
                beginAwait(9000);
            }
            case 22 -> {
                int home = registeredSettlement.id();
                int far = roadEdge.other(home);
                // Every story spent at home, which is also every story that was ever going to set
                // out. Waiting only for the first arrival leaves the rest still crossing while the
                // day turns underneath them, and leaves the leg resting on one coin.
                if (stillWaiting(server, () -> registry.gossip().of(home).isEmpty()
                                && !registry.gossip().of(far).isEmpty(), true,
                        "the first village to finish telling its " + storiesSentDownTheRoad
                                + " story(s), and every one of them to set out down the road")) {
                    return;
                }
                List<Deed> travelling = registry.gossip().of(far);
                record(travelling.size() == storiesSentDownTheRoad,
                        "BORDER all " + travelling.size() + " of the first village's "
                                + storiesSentDownTheRoad + " story(s) crossed into the far one's "
                                + "deque on the server tick hook, with nothing scheduled and "
                                + "nothing persisted that was not already");
                record(travelling.stream().allMatch(deed ->
                                deed.settlementId() == registeredSettlement.id()),
                        "BORDER and every one of them still says where it happened, which is how the "
                                + "far village knows it is not its own business");
                record(travelling.stream().allMatch(deed -> deed.confidence() == Deed.FIRST_HAND),
                        "BORDER what crossed is the carrier's own copy, so the far village's first "
                                + "telling degrades it exactly as this one's did");

                int heldAbroad = ringsHolding(registry, far);
                record(heldAbroad == 0,
                        "BORDER and on the day it happened not one of the far village's residents "
                                + "has heard it (" + heldAbroad + ") — a player can outwalk the news");

                bumpTheDay(level, 1);
                beginAwait(9000);
            }
            case 23 -> {
                int far = roadEdge.other(registeredSettlement.id());
                // Until the far village has told everything that arrived, not until the first
                // resident holds something: the first telling of each story is the only attributed
                // one, and stopping at the first holder can leave five of the six untold.
                if (stillWaiting(server, () -> registry.gossip().of(far).isEmpty(), true,
                        "the day to turn and the far village to finish telling what arrived")) {
                    return;
                }
                checkTheFarVillageHeard(server, level, far);
                // The road is on its own wait, because it is on its own thread. See case 24.
                beginAwait(9000);
            }
            case 24 -> {
                // <b>CI found this and this machine could not have.</b> The router runs one edge per
                // tick on Util.backgroundExecutor(), which a dedicated runner shares with world
                // generation — so on a two-core machine the route had not come back by the time the
                // border legs finished, and the road legs read `the router answered 0 edge(s)`.
                // Nothing was wrong with the road; the leg had simply assumed a thing had happened
                // rather than waiting for it, which is session 01's rule and the fifth time this
                // project has been bitten by it.
                //
                // Sprinting is right here: what is being waited on is server ticks (the materialiser
                // spends a budget per tick) and a background job, not chunk IO — and the chunks
                // between the two villages are held by the player standing between them.
                if (stillWaiting(server, () -> !RoadNetwork.progress().isEmpty()
                                && RoadNetwork.columnsLaid() > 0, true,
                        "the router to answer and the road to be laid ("
                                + RoadNetwork.progress().size() + " routed, "
                                + RoadNetwork.unrouted().size() + " waiting, "
                                + RoadNetwork.columnsLaid() + " column(s) laid)")) {
                    return;
                }
                checkTheRoadExists(server, level);

                NpcRegistry registry2 = NpcRegistry.get(server);
                // The bonds and rings the verify phase will be held to, snapshotted <b>here</b>
                // rather than at the end of the gossip section — which is where session 08 put it
                // and where session 10 found it, red, on the first verify run. Nothing was wrong
                // with the mod: this section feeds six more villagers and every one of those bonds
                // and rings moved after the snapshot was taken. <b>A snapshot taken before the
                // state stops moving is not a snapshot</b>, and this project has now written that
                // sentence about a cached instrument three times.
                //
                // Taking it last is also strictly stronger: the ring-reload check now covers deeds
                // that crossed a border, which is the thing this session added.
                recordBonds(registry2, player(server).getUUID());
                recordMemories(registry2);
                writeSubjects(level);
                advance(server, 5);
            }
            default -> finish(server, true);
        }
    }

    /**
     * <b>The exit criterion, on a screen.</b> A villager who has never seen the player, in a village
     * the player has never done anything in, with the player's name attached to something they did
     * somewhere else.
     */
    private static void checkTheFarVillageHeard(MinecraftServer server, ServerLevel level, int far) {
        NpcRegistry registry = NpcRegistry.get(server);
        ServerPlayer player = player(server);
        int held = 0;
        int named = 0;
        Persona speaker = null;
        Set<Integer> confidences = new java.util.TreeSet<>();
        for (Persona resident : neighbours(registry)) {
            List<Deed> ring = registry.memories().of(resident.id());
            if (ring.isEmpty()) {
                continue;
            }
            held++;
            for (Deed deed : ring) {
                confidences.add((int) deed.confidence());
            }
            if (Dialogue.registerFor(ring, player.getUUID(), 1) == Register.ABOUT_OTHERS) {
                named++;
                if (speaker == null) {
                    speaker = resident;
                }
            }
        }

        record(held > 0, "BORDER the day turned and " + held + " resident(s) of the far village "
                + "hold a story about something that happened five hundred blocks away");
        record(!confidences.contains((int) Deed.FIRST_HAND),
                "BORDER and not one of them holds it first-hand: " + confidences
                        + " — 70 was told, 49 cannot name who");
        record(named > 0, "BORDER " + named + " of them can still say who did it, which is what "
                + "Register.ABOUT_OTHERS needs and what acceptance step 5 is made of");

        if (speaker == null) {
            return;
        }
        // Turn 1 is the second thing said in a conversation, which is where the register that is
        // not a greeting lives. See Dialogue.registerFor.
        Dialogue.Spoken spoken = Dialogue.speak(registry, speaker, player.getUUID(),
                player.getGameProfile().getName(), 1, Deed.dayOf(level), level.getGameTime());
        record(spoken.register() == Register.ABOUT_OTHERS,
                "BORDER on screen, in a running game: " + Names.of(speaker).full() + " |  "
                        + spoken.line());
        record(spoken.pool() != Pool.STRANGER,
                "BORDER and they are no longer a stranger to you, in a village you have never "
                        + "done anything in (" + spoken.pool() + ")");
        record(spoken.line().length() <= Reports.CHAT_WIDTH,
                "BORDER the line is " + spoken.line().length() + " characters against a "
                        + Reports.CHAT_WIDTH + "-character chat width");
        record(!spoken.line().contains("\r"), "BORDER and it carries no carriage return");

        List<String> emitted = new ArrayList<>();
        CommandSourceStack capturing = capturing(server, player, emitted);
        boundEntity(server, speaker.id()).ifPresent(entity ->
                server.getCommands().performPrefixedCommand(capturing,
                        "namesake debug deeds " + entity.getUUID()));
        server.getCommands().performPrefixedCommand(capturing, "namesake debug roads");

        record(emitted.stream().anyMatch(line -> line.contains("@s" + registeredSettlement.id())),
                "BORDER the deed row says which village it happened in, which is the instrument "
                        + "step 5's 'referencing A' is read with until session 11's board");
        record(emitted.stream().anyMatch(line -> line.contains("neighbours")),
                "ROAD /namesake debug roads printed the graph through the real dispatcher");
        String widest = emitted.stream().max(Comparator.comparingInt(String::length)).orElse("");
        record(widest.length() <= Reports.CHAT_WIDTH,
                "ROAD the widest line either command emits is " + widest.length() + " characters, "
                        + "against a " + Reports.CHAT_WIDTH + "-character chat width: |" + widest + "|");
        record(emitted.stream().noneMatch(line -> line.contains("\r")),
                "ROAD no line carries a carriage return");
    }

    /**
     * <b>The first blocks this mod has ever placed, and the ones it refused to.</b>
     *
     * <p>The plank floor is the whole point. Every bound the ledger claims for the materialiser comes
     * down to one thing — that the surface it tests is whatever
     * {@code MOTION_BLOCKING_NO_LEAVES} finds, so a build hides the natural ground under it — and
     * that is a property of a world with blocks in it rather than of any function.
     */
    private static void checkTheRoadExists(MinecraftServer server, ServerLevel level) {
        if (!RoadNetwork.materialises()) {
            record(true, "ROAD block laying is switched off for this run; the graph is unaffected");
            return;
        }
        int path = 0;
        for (BlockPos column : ROAD_GRASS) {
            if (level.getBlockState(column).is(Blocks.DIRT_PATH)) {
                path++;
            }
        }
        int intact = 0;
        for (BlockPos plank : ROAD_PLANKS) {
            if (level.getBlockState(plank).is(Blocks.OAK_PLANKS)) {
                intact++;
            }
        }

        record(!ROAD_GRASS.isEmpty() && !ROAD_PLANKS.isEmpty(),
                "ROAD the fixture laid " + ROAD_GRASS.size() + " column(s) of natural ground and "
                        + ROAD_PLANKS.size() + " of somebody's floor across the route");
        record(path > 0, "ROAD " + path + " of " + ROAD_GRASS.size() + " natural column(s) on the "
                + "route are dirt path — this mod changed the world for the first time in ten "
                + "sessions");
        record(intact == ROAD_PLANKS.size(),
                "ROAD and " + intact + " of " + ROAD_PLANKS.size() + " planks are untouched: a road "
                        + "does not go through somebody's floor");

        List<RoadProgress> progress = RoadNetwork.progress();
        record(!progress.isEmpty(), "ROAD the router answered " + progress.size() + " edge(s)");
        for (RoadProgress road : progress) {
            record(road.buildable(), "ROAD " + road.edge() + ": " + road.chunks()
                    + " chunk(s), " + road.laid() + " column(s) laid, " + road.refused()
                    + " refused, " + String.format(Locale.ROOT, "%.1f", road.roughness())
                    + "x the cost of flat ground");
        }
    }

    // --- session 11: the Notice Board ------------------------------------------------------------

    /**
     * <b>A lectern placed in a village, opened by a real player, drawn through the real screen.</b>
     *
     * <p>Everything about the board that is arithmetic — the layout, the absence branches, the
     * standing naming, the direction — is a unit test, which is the line {@code WORKPLAN.md} draws.
     * Four things are not, and all four are here:
     *
     * <ol>
     *   <li><b>The loader's own hook is wired.</b> The click goes through
     *       {@code ServerPlayerGameMode.useItemOn}, which is vanilla's real path, so Fabric's
     *       {@code UseBlockCallback} and NeoForge's {@code RightClickBlock} are actually exercised
     *       rather than assumed. Sessions 00, 01 and 02 each lost time to the gap between "compiles"
     *       and "works in a game", and this is that gap.</li>
     *   <li><b>The screen is on the screen.</b> The packet crossed, the shared handler ran, and the
     *       client set a {@code NoticeBoardScreen} — three things no test in {@code common} can
     *       see.</li>
     *   <li><b>The advance table is true.</b> {@code BoardText.ADVANCES} is measured against the real
     *       {@code Font}, character by character and row by row. Only a running client has one.</li>
     *   <li><b>It renders a real save's history</b>, in two villages: the one the player did things in
     *       and the one down the road that only heard about it.</li>
     * </ol>
     *
     * <p>Run <b>after</b> the bond and ring snapshot of case 24 rather than before, and that is safe
     * rather than lucky: opening a board writes nothing. It reads the registry and places one vanilla
     * block. Session 10's defect 3 is the reason that sentence is here at all.
     */
    private static void runNoticeBoardCheck(MinecraftServer server, ServerLevel level) {
        switch (step) {
            case 25 -> {
                ServerPlayer player = player(server);
                teleport(player, level, villageSite.getX() + 6, villageSite.getY() + 2,
                        villageSite.getZ() + 6);
                // Chunk IO, so no sprint: session 03 lost a whole leg to getHeight answering with
                // the world floor for a chunk that was not loaded, and a lectern placed at the world
                // floor is a lectern nobody can click.
                beginAwait(2400);
            }
            case 26 -> {
                if (stillWaiting(server, () -> level.isLoaded(villageSite), false,
                        "the village's chunks to come back")) {
                    return;
                }
                homeBoard = putUpABoard(level, registeredSettlement.centre());
                openTheBoard(server, level, homeBoard);
                beginAwait(1200);
            }
            case 27 -> {
                if (stillWaiting(server, () -> BoardProbe.answer().isPresent(), false,
                        "the notice board to reach the client's screen")) {
                    return;
                }
                checkTheBoard(server, level, homeBoard, "HOME", true);

                int far = roadEdge == null ? Persona.UNASSIGNED
                        : roadEdge.other(registeredSettlement.id());
                farBell = NpcRegistry.get(server).settlements().byId(far)
                        .map(Settlement::centre).orElse(null);
                if (farBell == null) {
                    Namesake.LOGGER.info("[harness] no second village in this world; "
                            + "skipping the far board leg");
                    advance(server, 5);
                    return;
                }
                teleport(player(server), level, farBell.getX() + 6, farBell.getY() + 2,
                        farBell.getZ() + 6);
                beginAwait(2400);
            }
            case 28 -> {
                if (stillWaiting(server, () -> level.isLoaded(farBell), false,
                        "the far village's chunks to come back")) {
                    return;
                }
                awayBoard = putUpABoard(level, farBell);
                openTheBoard(server, level, awayBoard);
                beginAwait(1200);
            }
            case 29 -> {
                if (stillWaiting(server, () -> BoardProbe.answer().isPresent(), false,
                        "the far village's notice board to reach the client's screen")) {
                    return;
                }
                checkTheBoard(server, level, awayBoard, "AWAY", true);
                checkTheFarBoardNamesWhereItCameFrom(server, level);
                checkASecondPlayerSeesTheirOwnBoard(level, player(server).getUUID());
                writeSubjects(level);
                advance(server, 5);
            }
            default -> finish(server, true);
        }
    }

    /**
     * Stands a lectern up beside the bell, on whatever the ground turns out to be.
     *
     * <p>A plain vanilla {@code Blocks.LECTERN} and nothing else, which is the whole of session 11's
     * answer to "what does the block entity store": <b>there is no block entity and no registered
     * block.</b> A lectern inside a registered settlement is a notice board because it is standing
     * there, and this leg is that sentence as a fixture — the harness places the same block a player
     * would craft, and the board opens.
     */
    private static BlockPos putUpABoard(ServerLevel level, BlockPos bell) {
        BlockPos beside = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                bell.offset(3, 0, 3));
        level.setBlockAndUpdate(beside, Blocks.LECTERN.defaultBlockState());
        Namesake.LOGGER.info("[harness] stood a lectern at {} ({} blocks from the bell at {})",
                beside, (int) Math.sqrt(beside.distSqr(bell)), bell);
        return beside;
    }

    /**
     * The real click. Empty main hand, right-click, through vanilla's own interaction path.
     *
     * <p>Not a direct call to {@code NoticeBoard.onServerGesture}: that would prove the board and
     * skip the loader hook, which is the half a unit test cannot reach and the half that has broken
     * before.
     */
    private static void openTheBoard(MinecraftServer server, ServerLevel level, BlockPos lectern) {
        ServerPlayer player = player(server);
        // The negatives first, and they are what "vanilla is untouched" actually means. A hand with
        // something in it is somebody putting a book on a lectern or building with it, and a block
        // that is not a lectern is not a notice board however close to the bell it is standing.
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.WRITABLE_BOOK));
        record(!NoticeBoard.isBoardGesture(player, InteractionHand.MAIN_HAND,
                        level.getBlockState(lectern)),
                "BOARD a hand with a book in it is somebody using a lectern, and is left alone");
        player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        record(!NoticeBoard.isBoardGesture(player, InteractionHand.MAIN_HAND,
                        level.getBlockState(registeredSettlement.centre())),
                "BOARD the bell is not a notice board, however central it is");
        record(NoticeBoard.isBoardGesture(player, InteractionHand.MAIN_HAND,
                        level.getBlockState(lectern)),
                "BOARD an empty lectern with an empty hand is the gesture, and vanilla spends it on "
                        + "nothing");
        BoardProbe.request();
        player.gameMode.useItemOn(player, level, ItemStack.EMPTY, InteractionHand.MAIN_HAND,
                new BlockHitResult(net.minecraft.world.phys.Vec3.atCenterOf(lectern), Direction.UP,
                        lectern, false));
    }

    private static void checkTheBoard(MinecraftServer server, ServerLevel level, BlockPos lectern,
                                      String which, boolean expectHistory) {
        BoardProbe.Answer seen = BoardProbe.answer().orElse(null);
        record(seen != null && seen.open(), "BOARD " + which
                + " a lectern in the village put a notice board on the client's screen");
        if (seen == null) {
            return;
        }

        // The table this mod measures every board row with, against the font that draws them. A
        // table written down is a table that can be wrong, and this is the only place it can be
        // held to the truth.
        record(seen.disagreements().isEmpty(), "BOARD " + which + " the advance table agrees with "
                + "the real font on every printable character and every row"
                + (seen.disagreements().isEmpty() ? ""
                        : ": " + String.join("; ", seen.disagreements())));
        record(seen.widest() <= BoardText.TEXT_WIDTH, "BOARD " + which + " the widest row draws at "
                + seen.widest() + "px against the " + BoardText.TEXT_WIDTH + "px budget");

        Board board = NoticeBoard.boardAt(level, lectern, player(server).getUUID()).orElse(null);
        record(board != null, "BOARD " + which + " the lectern is inside a registered settlement");
        if (board == null) {
            return;
        }
        String rendered = String.join("\n", seen.rows());
        record(board.named() && rendered.contains(board.place()),
                "BOARD " + which + " the village on the board has a name and it is on the screen: "
                        + board.place());
        if (expectHistory) {
            record(board.hasHistory(), "BOARD " + which
                    + " the board is showing a real save's history: " + board.witnessed().size()
                    + " thing(s) seen, " + board.hearsay().size() + " heard about");
        }
        // <b>"No history." belongs to the section that has none, not to the board</b>, and this
        // assertion is stated against what the live player's board actually holds rather than
        // against what the phase expects — twice over, because it was wrong both ways round first.
        //
        // The far village turned the first version red: it has heard six stories about the player
        // and watched them do nothing, so exactly one of its two sections should be saying so.
        // Then the verify phase turned the second version red on NeoForge only, and nothing was
        // wrong with the board: <b>the two loaders' dev clients disagree about whether you are the
        // same person in two launches.</b> Fabric mints PlayerNNN and therefore a fresh offline
        // UUID every time, so the player who runs verify has done nothing; NeoForge is always Dev,
        // so they are the player who wrote the save and their board is full. Both are correct, and
        // a leg that assumed either one is a leg that measured the launcher. The fourth cross-loader
        // asymmetry this project has been bitten by, and the same lesson each time: read what the
        // two sides actually do rather than what one of them did.
        record(board.witnessed().isEmpty() == rendered.contains("No history."), "BOARD " + which
                + " the section with nothing in it is the one printing its own absence, for the "
                + "player who is actually holding the mouse (" + board.witnessed().size()
                + " thing(s) seen)");
        for (String row : seen.rows()) {
            if (!row.isBlank()) {
                Namesake.LOGGER.info("[board {}] {}", which, row);
            }
        }
    }

    /**
     * <b>Session 10's loose end, on a screen.</b> {@code /namesake debug deeds} says {@code @s0}; the
     * far village's board says which village the story came from and which way it is.
     */
    private static void checkTheFarBoardNamesWhereItCameFrom(MinecraftServer server,
                                                            ServerLevel level) {
        Board board = NoticeBoard.boardAt(level, awayBoard, player(server).getUUID()).orElse(null);
        if (board == null || board.hearsay().isEmpty()) {
            record(false, "BOARD AWAY the far village holds nothing it was told about the player");
            return;
        }
        Board.Origin origin = board.hearsay().get(0).origin();
        record(!origin.here() && origin.hasPlace() && !origin.bearing().isEmpty(),
                "BOARD AWAY a hearsay row names where the story came from: "
                        + BoardText.source(origin));
        String rendered = String.join("\n",
                BoardText.of(board).stream().map(BoardText.Line::flat).toList());
        record(rendered.contains(BoardText.source(origin)),
                "BOARD AWAY and it is on the board rather than only in the record");
    }

    /**
     * <b>{@code DESIGN.md} §10 step 7, as far as one client can prove it.</b>
     *
     * <p>The same lectern, the same server-side function, a different UUID. A genuine second client
     * is session 12's exit criterion and is not available to a scripted single-player run — what is
     * available is the claim underneath it, which is that <b>nothing about a board is shared</b>:
     * nothing caches one, and the only input that decides its contents is the viewer handed in.
     */
    /**
     * @param whoDidIt the player whose history this world actually holds. <b>Passed in rather than
     *                 read off the server</b>, because the two loaders' dev clients disagree about
     *                 whether the player in the verify phase is the one who wrote the save — see
     *                 {@link #checkTheBoard}.
     */
    private static void checkASecondPlayerSeesTheirOwnBoard(ServerLevel level, UUID whoDidIt) {
        UUID nobody = UUID.nameUUIDFromBytes("a second player who has done nothing".getBytes(
                java.nio.charset.StandardCharsets.UTF_8));
        Board mine = NoticeBoard.boardAt(level, homeBoard, whoDidIt).orElseThrow();
        Board theirs = NoticeBoard.boardAt(level, homeBoard, nobody).orElseThrow();

        record(mine.hasHistory() && !theirs.hasHistory(),
                "BOARD two players at one lectern do not see each other's history (mine: "
                        + mine.witnessed().size() + " seen, theirs: " + theirs.witnessed().size()
                        + ")");
        record(theirs.opinions().isEmpty() && theirs.strangers() == theirs.residents(),
                "BOARD and everybody in the village is a stranger to the one who has done nothing ("
                        + theirs.strangers() + " of " + theirs.residents() + ")");
        record(!theirs.residency().granted(),
                "BOARD and the village has not taken them in either");
        // DESIGN.md §10 step 3, in its own words, laid out rather than asserted about a record —
        // and against a synthetic viewer rather than whoever the launcher happens to have minted,
        // which is what makes it the same claim on both loaders.
        String rendered = String.join("\n",
                BoardText.of(theirs).stream().map(BoardText.Line::flat).toList());
        record(rendered.contains("No history."),
                "BOARD and a player who has done nothing here reads \"No history.\"");
    }

    /**
     * Lays the ground the road will be built over: natural cover, and a floor somebody built.
     *
     * <p>Run <b>before</b> the second bell exists, so the router has not started and nothing has been
     * laid yet. The route is predicted with the shipped function rather than guessed, so the fixture
     * is on the road wherever the terrain puts it.
     */
    private static void prepareTheGround(ServerLevel level) {
        ROAD_GRASS.clear();
        ROAD_PLANKS.clear();
        RoadPath.Route route = RoadPath.between(new ChunkPos(villageSite),
                new ChunkPos(neighbourSite), RoadNetwork.terrainOf(level));
        if (!route.buildable()) {
            Namesake.LOGGER.warn("[harness] the predicted route between the two villages is not "
                    + "buildable ({}); the road legs will say so", route.roughness());
            return;
        }
        List<BlockPos> paving = RoadTrail.paving(RoadTrail.centreLine(route.chunks()),
                RoadTrail.WIDTH);
        // The middle third, which is well clear of both villages' own platforms and is the part a
        // player standing between the two bells has loaded.
        int from = paving.size() / 3;
        int to = paving.size() * 2 / 3;
        for (int i = from; i < to; i++) {
            BlockPos column = paving.get(i);
            if (level.getChunkSource().getChunkNow(column.getX() >> 4, column.getZ() >> 4) == null) {
                continue;
            }
            BlockPos surface = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                    column).below();
            if (surface.getY() <= level.getMinBuildHeight()) {
                continue;
            }
            // A contiguous run in the exact middle is somebody's floor. Contiguous rather than
            // scattered, because a road three wide would otherwise step round a single column and
            // the guard would pass without ever being tested.
            boolean floor = i >= (from + to) / 2 - 12 && i < (from + to) / 2 + 12;
            level.setBlock(surface, floor
                    ? Blocks.OAK_PLANKS.defaultBlockState()
                    : Blocks.GRASS_BLOCK.defaultBlockState(), 2);
            (floor ? ROAD_PLANKS : ROAD_GRASS).add(surface);
        }
        Namesake.LOGGER.info("[harness] prepared {} natural column(s) and {} of somebody's floor "
                + "along the predicted route ({} chunks)", ROAD_GRASS.size(), ROAD_PLANKS.size(),
                route.chunks().size());
    }

    /** Everyone who lives in a settlement that is not the first village. */
    private static List<Persona> neighbours(NpcRegistry registry) {
        return registry.all().stream()
                .filter(persona -> persona.settlementId() != Persona.UNASSIGNED
                        && persona.settlementId() != registeredSettlement.id())
                .toList();
    }

    private static int ringsHolding(NpcRegistry registry, int settlement) {
        int held = 0;
        for (Persona persona : registry.all()) {
            if (persona.settlementId() == settlement && !registry.memories().of(persona.id()).isEmpty()) {
                held++;
            }
        }
        return held;
    }

    /**
     * Moves the world's clock on by whole in-game days.
     *
     * <p>The alternative is sprinting twenty-four thousand ticks, which is four hundred times what
     * any other wait in this harness costs and would outrun the chunk loader — session 01's defect,
     * for the fifth time. <b>Game time rather than day time</b>, because {@code Deed.dayOf} divides
     * {@code getGameTime}: day time is a mutable world property that {@code /time set} and the sleep
     * skip both move backwards, and a delay keyed on a clock that can go backwards is a delay you
     * can undo with a command.
     */
    private static void bumpTheDay(ServerLevel level, int days) {
        long moved = level.getLevelData().getGameTime() + days * 24_000L;
        ((net.minecraft.world.level.storage.ServerLevelData) level.getLevelData()).setGameTime(moved);
        Namesake.LOGGER.info("[harness] the clock moved to in-game day {}", Deed.dayOf(level));
    }

    /** Moves one persona's villager, and stops it falling out of the sky. */
    private static void place(MinecraftServer server, ServerLevel level, UUID personaId,
                              double x, double y, double z) {
        Entity entity = boundEntity(server, personaId).orElse(null);
        if (entity == null) {
            record(false, "WITNESS persona " + personaId + " has no loaded villager to place");
            return;
        }
        entity.setNoGravity(true);
        entity.teleportTo(x, y, z);
    }

    /**
     * True once five of the six are inside the witness box and the sixth is not.
     *
     * <p>Polls the condition the leg actually depends on rather than a tick count — session 01's
     * rule, and the sixth application of it. A scan run before the entity index has caught up with
     * six teleports would find the wrong villagers and blame the AABB.
     */
    private static boolean castIsInPlace(MinecraftServer server, ServerLevel level, BlockPos stand) {
        Entity subject = boundEntity(server, deedSubject).orElse(null);
        if (subject == null) {
            return false;
        }
        AABB box = new AABB(subject.position(), subject.position()).inflate(DeedBus.WITNESS_RADIUS);
        long inside = level.getEntitiesOfClass(Villager.class, box).size();
        Entity far = boundEntity(server, witnessOutOfRange).orElse(null);
        return inside == 5 && far != null && !box.contains(far.position());
    }

    /** Snapshots every bond the leg produced, for {@code verify} to look for after a reload. */
    private static void recordBonds(NpcRegistry registry, UUID actor) {
        BONDS.clear();
        for (Resident resident : RESIDENTS) {
            registry.bonds().stored(resident.personaId(), actor).ifPresent(bond ->
                    BONDS.add(new BondRow(resident.personaId(), actor, bond.trust(), bond.warmth(),
                            bond.fear())));
        }
        Namesake.LOGGER.info("[harness] recorded {} bond(s) for the verify phase", BONDS.size());
    }

    /** Snapshots every ring the leg produced, for {@code verify} to look for after a reload. */
    private static void recordMemories(NpcRegistry registry) {
        MEMORIES.clear();
        int deeds = 0;
        for (Resident resident : RESIDENTS) {
            List<Deed> ring = registry.memories().of(resident.personaId());
            if (ring.isEmpty()) {
                continue;
            }
            MEMORIES.add(new MemoryRow(resident.personaId(), slotsOf(ring)));
            deeds += ring.size();
        }
        Namesake.LOGGER.info("[harness] recorded {} ring(s) holding {} deed(s) for the verify phase",
                MEMORIES.size(), deeds);
    }

    private static List<String> slotsOf(List<Deed> ring) {
        return ring.stream().map(deed -> deed.id() + ":" + deed.confidence()).toList();
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
                // Before session 05's legs feed anybody. See recordStrangerBefore.
                recordStrangerBefore(server);
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
                // Two different questions, and conflating them is how a migration test goes green
                // for the wrong reason. A world written before schema 3 has no cultures and no
                // traits at all, so its personas must be *backfilled* and warmth is expected to
                // move. A world written at schema 3 already has both, so nothing about a persona
                // may move on the way to schema 4 — and if it did, the fixer is rewriting records
                // it has no business touching.
                boolean preCultures = NpcRegistry.get(server).loadedSchemaVersion() < 3;
                if (stillWaiting(server,
                        () -> preCultures ? subjectsBackfilled(server) : subjectsIntact(server),
                        false, preCultures ? "the migrated personas to be backfilled"
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
                    if (!preCultures && persona.get().trait(Persona.WARMTH) != subject.warmth()) {
                        record(false, "RELOAD persona " + subject.personaId() + " warmth is "
                                + persona.get().trait(Persona.WARMTH) + ", expected " + subject.warmth());
                        continue;
                    }
                    found++;
                }
                record(found == SUBJECTS.size(),
                        "RELOAD " + found + "/" + SUBJECTS.size()
                                + " personas survived save -> quit -> reload with the same id and values");
                if (preCultures) {
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
                checkBondsSurvivedReload(registry);
                checkMemoriesSurvivedReload(registry);
                checkResidencySurvivedReload(server, registry);
                checkTheRoadSurvivedReload(registry);
                // The exit criterion's instrument, run where there is something to read: through
                // the real dispatcher, so argument parsing and the permission gate are covered too,
                // and into the log so a run leaves behind what a player would have seen.
                CommandSourceStack source = server.createCommandSourceStack()
                        .withPosition(net.minecraft.world.phys.Vec3.atCenterOf(villageSite));
                server.getCommands().performPrefixedCommand(source, "namesake debug settlements");
                server.getCommands().performPrefixedCommand(source, "namesake debug dump");

                homeBoard = putUpABoard(level, registeredSettlement.centre());
                openTheBoard(server, level, homeBoard);
                beginAwait(1200);
            }
            case 3 -> {
                if (stillWaiting(server, () -> BoardProbe.answer().isPresent(), false,
                        "the notice board to reach the client's screen")) {
                    return;
                }
                checkTheBoardSurvivedReload(server, level);
                checkStepSeven(server, level);
                // Session 13. The workshop is recomputed from the village the subjects file
                // carries, which is how every other reload leg finds what it is looking for.
                workshopSite = new BlockPos(villageSite.getX() - WORKSHOP_OFFSET, 200,
                        villageSite.getZ() + WORKSHOP_OFFSET);
                teleport(player(server), level, workshopSite.getX(), 200, workshopSite.getZ());
                beginAwait(2400);
            }
            case 4 -> {
                if (stillWaiting(server, () -> level.isLoaded(workshopSite), false,
                        "the workshop chunks to come back")) {
                    return;
                }
                workshopSite = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                        workshopSite);
                teleport(player(server), level, workshopSite.getX(), workshopSite.getY() + 1,
                        workshopSite.getZ());
                // Nine o'clock, frozen, exactly as the setup phase measured at.
                server.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false, server);
                level.setDayTime(3000);
                beginAwait(4800);
            }
            case 5 -> {
                // The villagers, not the chunks. `isLoaded` said the blocks were back; entities
                // arrive on their own schedule and this leg read zero of them because it asked the
                // first question and asserted on the second. Session 03's rule, and the sixth time
                // this run has been the one to notice.
                // The villagers, not the chunks, and then the plan's answer about them rather than
                // their mere presence. `isLoaded` said the blocks were back and this leg read zero
                // villagers; then it read villagers and zero standoffs, because a crossing goes
                // through the path gate at one tick in seven and the governor at eight a tick, and
                // the check was running the tick after they arrived. Both were the same mistake
                // twice — asserting on a state that had not been waited for.
                if (stillWaiting(server, () -> {
                    List<Villager> back = level.getEntitiesOfClass(Villager.class,
                            new AABB(workshopSite).inflate(64));
                    return !back.isEmpty() && back.stream()
                            .anyMatch(v -> net.namesake.day.Steering.standoffOf(v).isPresent());
                }, false, "the workshop's villagers to come back and be sent to a standoff again")) {
                    return;
                }
                checkTheDayPlanSurvivedReload(server, level);
                finish(server, true);
            }
            default -> finish(server, true);
        }
    }

    /**
     * <b>The day plan, after a save, a quit and a reload — and it is not a persistence check.</b>
     *
     * <p>Nothing about the plan is on disk, which is the point of it, so there is no record to
     * compare. What a reload can break is the <b>roster</b>: {@code Steering} learns about a villager
     * from the entity-load hook and from nowhere else, so a villager who comes back and is not picked
     * up is a villager the plan never touches again, silently, for the life of the world.
     *
     * <p><b>That is not hypothetical — it is the defect this session shipped for an hour.</b> The
     * hook filtered on {@code Persona.isGenerated}, which is false for almost every villager at the
     * moment it fires, so the roster was empty in any real save. This leg is the guard that would
     * have caught it on the way out rather than on the way in.
     */
    private static void checkTheDayPlanSurvivedReload(MinecraftServer server, ServerLevel level) {
        List<Villager> back = level.getEntitiesOfClass(Villager.class,
                new AABB(workshopSite).inflate(64));
        record(!back.isEmpty(), "PLAN RELOAD " + back.size() + " workshop villager(s) came back");
        if (back.isEmpty()) {
            return;
        }

        long generated = back.stream()
                .filter(v -> PersonaService.personaOf(v).filter(Persona::isGenerated).isPresent())
                .count();
        record(generated == back.size(),
                "PLAN RELOAD every villager that came back carries a generated persona ("
                        + generated + " of " + back.size() + ")");

        // The roster, read through the one thing that can see it: a posture that is not OFF_DUTY
        // means Steering found this villager, resolved their slot and had an opinion about them.
        long known = back.stream()
                .map(net.namesake.day.Steering::postureOf)
                .filter(posture -> posture != net.namesake.day.Steering.Posture.OFF_DUTY)
                .count();
        record(known > 0,
                "PLAN RELOAD the day plan picked " + known + " of " + back.size() + " villager(s) up "
                        + "again after a save and a reload — the roster is rebuilt from the "
                        + "entity-load hook and from nothing else, so a villager it misses is one it "
                        + "never touches again");

        long standingOff = back.stream()
                .filter(v -> net.namesake.day.Steering.standoffOf(v).isPresent())
                .count();
        record(standingOff > 0,
                "PLAN RELOAD " + standingOff + " villager(s) were sent back to a standoff on the "
                        + "reloaded world, so the plan is derived rather than remembered — nothing "
                        + "about it is on disk and it comes back anyway");
        for (Villager villager : back) {
            Namesake.LOGGER.info("[harness] reload standoff for {}: {}", villager.getId(),
                    net.namesake.day.Steering.explainStandoff(level, villager));
        }

        CommandSourceStack source = server.createCommandSourceStack()
                .withPosition(net.minecraft.world.phys.Vec3.atCenterOf(workshopSite));
        server.getCommands().performPrefixedCommand(source, "namesake debug dayplan");
    }

    /**
     * <b>The Notice Board after a save, a quit and a reload — and it stored nothing to survive.</b>
     *
     * <p>This is the leg that makes session 11's central decision checkable rather than merely
     * argued. There is no block entity, no registered block and no persisted field anywhere in the
     * board; a lectern is a notice board because it is standing inside a registered settlement, and
     * everything on it is computed when it opens. So <b>the whole surface has to come back from a
     * save that knows nothing about it</b>, out of a settlement table that has been on disk since
     * schema 3 and rings that have been there since 6.
     *
     * <p>Two players, deliberately. The live one is whoever the dev client minted this launch and has
     * done nothing here, so their board reads {@code DESIGN.md} §10 step 3's own words. The one whose
     * history is checked is the UUID the <i>setup</i> phase recorded — a check keyed on the live
     * player would read every board as empty and call that a pass, which is session 01's lesson about
     * this exact file.
     */
    private static void checkTheBoardSurvivedReload(MinecraftServer server, ServerLevel level) {
        checkTheBoard(server, level, homeBoard, "RELOAD", false);

        UUID whoDidIt = residencyPlayer != null ? residencyPlayer
                : BONDS.isEmpty() ? null : BONDS.get(0).about();
        if (whoDidIt == null) {
            Namesake.LOGGER.info("[harness] this save records no player, so there is no history to "
                    + "hold the board to; skipping");
            return;
        }
        Board board = NoticeBoard.boardAt(level, homeBoard, whoDidIt).orElse(null);
        record(board != null && board.hasHistory(),
                "BOARD RELOAD the board still holds what the player who wrote this save did: "
                        + (board == null ? "no settlement" : board.witnessed().size()
                        + " thing(s) seen, " + board.hearsay().size() + " heard about")
                        + " — out of a save that stores nothing about a notice board");
        checkASecondPlayerSeesTheirOwnBoard(level, whoDidIt);
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
        // Two from session 10, and one from every save written before it. The subjects file says
        // which, exactly as session 09's residency row does: a world archived at schema 6 or 7 has
        // one village in it, and expecting two would fail the cross-build load test for a reason
        // that has nothing to do with persistence.
        int expected = roadEdge == null ? 1 : 2;
        record(registry.settlements().size() == expected,
                "SETTLEMENT RELOAD " + expected + " settlement(s) came back ("
                        + registry.settlements().size() + ")");
        Settlement settlement = registry.settlements().byId(registeredSettlement.id()).orElse(null);
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
     * The bonds the setup phase wrote, after a save and a full reload.
     *
     * <p><b>The check {@code CLAUDE.md} names for this session by name.</b> A {@code SavedData} is
     * only written when it is dirty, so a bond updated without {@code setDirty} is a bond that
     * exists until the world reloads and then does not — session 01 shipped exactly that in the
     * datafixer and found it only by loading one world twice. Nothing in the setup phase would have
     * noticed: every assertion there reads the same in-memory table it just wrote.
     *
     * <p>The player's UUID is read out of the subject file rather than off the live player, because
     * the dev client picks a new name and therefore a new offline UUID on every launch. Keyed on the
     * live player, every bond would read as absent and this would pass by finding nothing.
     */
    private static void checkBondsSurvivedReload(NpcRegistry registry) {
        if (BONDS.isEmpty()) {
            // A world written before session 05 has no bonds in it. Skipping is right; asserting
            // would fail the cross-build migration run for a reason that is not about migration.
            Namesake.LOGGER.info("[harness] no bonds recorded in this save; skipping the bond legs");
            return;
        }
        int intact = 0;
        for (BondRow row : BONDS) {
            Optional<Bond> stored = registry.bonds().stored(row.personaId(), row.about());
            if (stored.isEmpty()) {
                record(false, "BOND RELOAD " + row.personaId() + " has forgotten " + row.about()
                        + " entirely — a bond written without setDirty is a bond that never "
                        + "reached the file");
                continue;
            }
            Bond bond = stored.get();
            if (bond.trust() != row.trust() || bond.warmth() != row.warmth()
                    || bond.fear() != row.fear()) {
                record(false, "BOND RELOAD " + row.personaId() + " came back as " + bond
                        + ", expected trust " + row.trust() + " warmth " + row.warmth()
                        + " fear " + row.fear());
                continue;
            }
            intact++;
        }
        record(intact == BONDS.size(), "BOND RELOAD " + intact + "/" + BONDS.size()
                + " bonds survived save -> quit -> reload with every axis intact");
    }

    /**
     * The deed rings the setup phase wrote, after a save and a full reload.
     *
     * <p><b>Session 06's one harness change, and it is deliberately not a new leg.</b>
     * {@code WORKPLAN.md} grows the harness by one leg per session <i>that has one</i>, and a ring is
     * arithmetic: overflow, dedupe and ordering are all pure and all proven in
     * {@code MemoriesTest} in ten milliseconds rather than six minutes of CI. What a unit test
     * cannot claim is that the ring reached the file — {@code Memories} in a test is saved by the
     * test itself, and the failure this catches is that nothing marked the registry dirty, so
     * Minecraft never asked it to save at all. That is precisely the instrument that already exists
     * one method up, so the rings ride it.
     *
     * <p>The player's UUID does not appear here, unlike the bond check, and that is worth noticing
     * rather than explaining away: a ring is keyed on the holder alone, so nothing about it depends
     * on the dev client picking a new offline UUID on every launch. The deed ids inside it do
     * contain the actor, which is why they are compared as recorded rather than recomputed.
     */
    private static void checkMemoriesSurvivedReload(NpcRegistry registry) {
        if (MEMORIES.isEmpty()) {
            // A world written before session 06 has no rings in it. Skipping is right; asserting
            // would fail the cross-build migration run for a reason that is not about migration.
            Namesake.LOGGER.info("[harness] no deed rings recorded in this save; skipping the memory legs");
            return;
        }
        int intact = 0;
        int deeds = 0;
        for (MemoryRow row : MEMORIES) {
            List<String> came = slotsOf(registry.memories().of(row.personaId()));
            if (came.isEmpty()) {
                record(false, "MEMORY RELOAD " + row.personaId() + " has forgotten everything it "
                        + "saw — a ring appended without setDirty is a ring that never reached the "
                        + "file");
                continue;
            }
            if (!came.equals(row.ring())) {
                record(false, "MEMORY RELOAD " + row.personaId() + " came back holding " + came
                        + ", expected " + row.ring());
                continue;
            }
            intact++;
            deeds += came.size();
        }
        record(intact == MEMORIES.size(), "MEMORY RELOAD " + intact + "/" + MEMORIES.size()
                + " deed rings survived save -> quit -> reload holding " + deeds
                + " deed(s), in order, every field intact");
    }

    /**
     * <b>Session 09's leg, on the far side of the disk.</b>
     *
     * <p>Everything about residency that is arithmetic is a unit test. This is the half that is not:
     * a threshold crossed in one session, written to a file at a <i>new schema version</i>, and read
     * back by a fresh server — with the villager's sentence different on the other side.
     *
     * <p><b>The villager asked is one who never met the player</b>, which is what makes the swap
     * worth a leg rather than a comment: residency belongs to the settlement, so the proof is a
     * stranger using your name because three of their neighbours decided you live here.
     *
     * <p>Skipped, with a line, when the world on disk predates session 09 — the cross-build migration
     * run loads a schema-6 save whose subjects file has no residency row, and failing that run for a
     * reason that has nothing to do with migration is what session 03's settlement legs already
     * taught this harness not to do.
     */
    private static void checkResidencySurvivedReload(MinecraftServer server, NpcRegistry registry) {
        if (residencySpeaker == null) {
            Namesake.LOGGER.info("[harness] no residency recorded in this save; skipping the "
                    + "residency legs");
            return;
        }
        Optional<Persona> speaker = registry.persona(residencySpeaker);
        if (speaker.isEmpty()) {
            record(false, "RESIDENCY the villager who used the player's name is gone from the save");
            return;
        }

        int today = Deed.dayOf(server.overworld());
        Residency.Verdict verdict =
                Residency.verdict(registry, residencySettlement, residencyPlayer, today);
        record(verdict.granted() && verdict.route() == Residency.Route.BAND,
                "RESIDENCY survived save -> quit -> reload at schema " + NpcSchema.CURRENT + ": "
                        + verdict.residentsAtThreshold() + " resident(s) still hold "
                        + Residency.TRUST_THRESHOLD + " trust");

        Dialogue.Spoken spoken = Dialogue.speak(registry, speaker.get(), residencyPlayer,
                residencyName, 0, today, RESIDENCY_SEED);
        record(residencyName.equals(spoken.address()),
                "RESIDENCY and on the other side of the reload " + Names.of(speaker.get()).full()
                        + " still calls them '" + spoken.address() + "' rather than '"
                        + Voice.of(Culture.byId(speaker.get().cultureId())).strangerAddress() + "'");

        List<String> said = withTheirName(registry, speaker.get(), residencyPlayer, residencyName, today);
        record(String.join(" ", said).contains(residencyName),
                "RESIDENCY and it reaches a sentence: " + String.join(" | ", said));
        said.forEach(row -> Namesake.LOGGER.info("[harness] {}", row));
    }

    /**
     * If the world on disk predates the current schema, prove the fixer ran <i>and</i> that the
     * data it touched actually changed. "It loaded without crashing" is not evidence of a
     * migration; a fixer that silently does nothing loads without crashing too.
     *
     * <p><b>Which claim is the right one depends on which version the world was written at</b>, and
     * they are opposites. Schema 1 and 2 wrote sentinels that have to be <i>rewritten</i>, so the
     * evidence is that records changed. Schema 3 → 4 adds a table and must rewrite nothing at all,
     * so the evidence is that records did <i>not</i> change — plus the one thing that genuinely
     * could go wrong, which is the absent bond table being read as damage and turning the registry
     * read-only.
     */
    /**
     * <b>The road network came back and nothing on disk carried it.</b> Session 10's verify leg.
     *
     * <p>This is the assertion the whole "no schema bump" argument rests on, and it is worth having
     * as a leg rather than only as a claim in a log. The graph is a pure function of the settlement
     * table; the routes are a pure function of the world seed. Neither is written anywhere. So a
     * world that comes back from disk with the same two settlements has to come back with the same
     * one road between them — and the story that was still on the road when the world closed has to
     * be a queued rumour in a schema-7 table and nothing else.
     */
    private static void checkTheRoadSurvivedReload(NpcRegistry registry) {
        if (roadEdge == null) {
            // A save written before session 10 has one village in it and no road to rebuild.
            Namesake.LOGGER.info("[harness] no road recorded in this save; skipping the road legs");
            return;
        }
        RoadGraph graph = Roads.graphOf(registry.settlements());
        record(graph.joins(roadEdge.a(), roadEdge.b()),
                "ROAD RELOAD the road came back without one byte of it being persisted: "
                        + graph.edges() + " — the graph is derived from the settlement table, which "
                        + "has been on disk since schema 3");

        int far = roadEdge.other(registeredSettlement.id());
        int abroad = 0;
        Set<Integer> confidences = new java.util.TreeSet<>();
        for (Persona persona : registry.all()) {
            if (persona.settlementId() != far) {
                continue;
            }
            for (Deed deed : registry.memories().of(persona.id())) {
                abroad++;
                confidences.add((int) deed.confidence());
            }
        }
        record(abroad > 0, "ROAD RELOAD " + abroad + " deed(s) that crossed a border are still in "
                + "the far village's rings after a save, a quit and a reload");
        record(!confidences.contains((int) Deed.FIRST_HAND),
                "ROAD RELOAD and still not one of them first-hand: " + confidences);
    }

    private static void checkDataFixer(NpcRegistry registry) {
        int onDisk = registry.loadedSchemaVersion();
        if (onDisk >= NpcSchema.CURRENT) {
            Namesake.LOGGER.info("[harness] no migration expected: world is already at schema {}", onDisk);
            return;
        }
        record(true, "DATAFIXER world was written at schema " + onDisk
                + ", this build understands " + NpcSchema.CURRENT);

        if (onDisk >= 3) {
            if (onDisk < 6) {
                checkAdditiveMigration(registry, onDisk);
            }
            checkRepackMigration(registry, onDisk);
            return;
        }

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
     * <b>Schema 6 → 7, and it is the first migration since schema 3 that rewrites rather than
     * assumes.</b>
     *
     * <p>Three additive ones ran in a row and each of them said out loud that "additive" is a claim
     * rather than a default. This one is the other kind: the owner ruled the deed ring from
     * thirty-two slots to a hundred and twenty-eight at the close of session 08, session 08 had
     * already priced the readable encoding at 6.26 MB against a 2 MB ceiling, and so the format
     * changed. Every ring on disk is converted.
     *
     * <p><b>What it would look like if it were wrong is the reason this is a leg rather than only a
     * unit test.</b> Vanilla's {@code CompoundTag.getByteArray} returns an <i>empty array</i> for a
     * key holding the wrong tag type — so a ring the fixer did not convert loads as a villager who
     * remembers nothing. No error, no crash, and then written back that way at the next autosave.
     * That is MCA's failure exactly, in a save file rather than a release note, so the evidence has
     * to be that the deeds a previous build wrote are <i>here</i>, on a writable registry, read back
     * through a real load path.
     */
    private static void checkRepackMigration(NpcRegistry registry, int onDisk) {
        String step = onDisk + "->" + NpcSchema.CURRENT;
        if (onDisk >= 5) {
            record(registry.memories().size() > 0,
                    "DATAFIXER " + step + " the " + registry.memories().size() + " deed(s) across "
                            + registry.memories().holders() + " ring(s) the schema-" + onDisk
                            + " build wrote came through the repack — an unconverted ring reads as "
                            + "a villager who remembers nothing, silently");
            record(registry.memories().slotsOf(registry.all().stream()
                            .filter(persona -> !registry.memories().of(persona.id()).isEmpty())
                            .map(Persona::id).findFirst().orElse(UUID.randomUUID()))
                            .stream().allMatch(slot -> slot.repeats() >= Memories.Slot.ONCE),
                    "DATAFIXER " + step + " and every converted slot carries a repeat count of at "
                            + "least one — a schema-6 build could only have meant 'it happened', and "
                            + "a count of zero would read as a memory of nothing");
        }
        record(!registry.isReadOnly(),
                "DATAFIXER " + step + " the registry is writable, so the repacked file will be "
                        + "written back at schema " + NpcSchema.CURRENT + " rather than migrating "
                        + "again on every load");
    }

    /**
     * The additive migrations — 3 → 4, 4 → 5 and 5 → 6 — read out of a world genuinely written at
     * that version.
     *
     * <p>Everything asserted here is a negative, which is unusual and is the point. Both of these
     * migrations add a table rather than rewriting a value, so the ways they can be wrong are: they
     * rewrote something they should not have; the absent table was read as damage and the registry
     * is now refusing to save; or the version was not stamped, in which case the world migrates
     * again on every load for ever — session 01's defect 1, found only by loading one world twice.
     *
     * <p><b>The bond table is the assertion that changes with the version on disk, and it is the one
     * that matters at 4 → 5.</b> A schema-3 world has no bonds and must load as having none; a
     * schema-4 world has the four the previous build's setup phase wrote, and they must all still be
     * there. Which means the failure this catches is not abstract: read the absent {@code memories}
     * key as damage and the registry goes read-only, and because settlements, bonds and rings share
     * one file, a world somebody has played for a week silently stops saving all three. So the
     * evidence is <i>bonds present and the registry writable</i> rather than a rewrite count —
     * zero rewrites is also what a fixer that does nothing at all reports.
     */
    private static void checkAdditiveMigration(NpcRegistry registry, int onDisk) {
        String step = onDisk + "->" + NpcSchema.CURRENT;
        long untouched = SUBJECTS.stream()
                .map(subject -> registry.persona(subject.personaId()))
                .filter(Optional::isPresent)
                .map(Optional::get)
                .filter(Persona::isGenerated)
                .filter(persona -> persona.settlementId() == Persona.UNASSIGNED)
                .count();
        record(untouched == SUBJECTS.size(), "DATAFIXER " + step + " " + untouched + "/"
                + SUBJECTS.size() + " personas kept the culture and placement the old build gave "
                + "them; these migrations add a table and must rewrite nothing");

        record(registry.settlements().size() == 1,
                "DATAFIXER " + step + " the settlement table came through the migration ("
                        + registry.settlements().size() + ")");
        // The ring table is the assertion that changed at 5 → 6, exactly as the bond table changed
        // at 4 → 5, and for the same reason: a world written at schema 5 has rings in it, and they
        // are what an absent gossip table read as damage would silently cost this world.
        if (onDisk >= 5) {
            record(registry.memories().size() > 0,
                    "DATAFIXER " + step + " the " + registry.memories().size() + " deed(s) across "
                            + registry.memories().holders() + " ring(s) the schema-5 build wrote "
                            + "came through intact");
        } else {
            record(registry.memories().size() == 0,
                    "DATAFIXER " + step + " a world with no deed rings loads as having none, not as "
                            + "damaged (" + registry.memories().size() + ")");
        }
        record(registry.gossip().size() == 0,
                "DATAFIXER " + step + " a world written before schema 6 has no rumours in flight, "
                        + "and an absent gossip table reads as that rather than as damage ("
                        + registry.gossip().size() + ")");
        if (onDisk >= 4) {
            record(registry.bonds().size() > 0,
                    "DATAFIXER " + step + " the " + registry.bonds().size() + " bond(s) the "
                            + "schema-4 build wrote came through intact — which is what an absent "
                            + "ring table being read as damage would have cost this world");
        } else {
            record(registry.bonds().size() == 0,
                    "DATAFIXER " + step + " a world with no bond table loads as having no bonds, "
                            + "not as damaged (" + registry.bonds().size() + ")");
        }
        record(!registry.isReadOnly(),
                "DATAFIXER " + step + " the registry is writable, so the migrated file will be "
                        + "written back at schema " + NpcSchema.CURRENT + " rather than migrating "
                        + "again on every load");
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
        server.getCommands().performPrefixedCommand(source, "namesake debug bond " + entityId);
        // Run from the console, so there is no viewer. Its own absence branch is the thing under
        // test here — a section that prints nothing reads as a broken command, DESIGN.md §11.
        server.getCommands().performPrefixedCommand(source, "namesake debug bonds");
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

    /**
     * <b>{@code DESIGN.md} §10 step 7, and this is the closest a scripted run can honestly get.</b>
     *
     * <p><i>A second player who has done nothing gets stranger lines and 1.00 prices everywhere.</i>
     * Session 11 checked it against a synthetic viewer and said out loud that a same-process second
     * UUID is not a second player. It still is not. What this leg adds is that <b>on Fabric it does
     * not have to be synthetic</b>: {@code runClient} mints a fresh {@code PlayerNNN} and therefore a
     * fresh offline UUID on every launch, so the person running {@code verify} is a genuinely
     * different player — a different profile, a different login, a different connection — reading a
     * world somebody else played. That is step 7 with a real second connection, arriving free, out of
     * the launcher quirk session 11 recorded as a nuisance.
     *
     * <p><b>On NeoForge the player is always {@code Dev}</b>, so this run has no second person in it
     * and the leg says which case it is rather than quietly asserting the weaker one. That is the
     * fourth cross-loader asymmetry this project has been bitten by, used on purpose for once.
     *
     * <p>What is <b>not</b> covered either way, stated so it is not read as covered: two players
     * connected <i>at the same time</i>. {@code runServer} needs an EULA that is not ours to accept
     * and a scripted run has one client, so the simultaneous case is the owner's to play. The
     * server-side half of it — one villager's offers, priced for one viewer and then another — is
     * checked in {@code setup} by {@code checkTheCounterDoesNotLeak}.
     */
    private static void checkStepSeven(MinecraftServer server, ServerLevel level) {
        ServerPlayer player = player(server);
        UUID viewer = player.getUUID();
        boolean genuinelySomebodyElse = actingPlayer != null && !actingPlayer.equals(viewer);

        record(true, "STEP 7 this launch's player is " + player.getGameProfile().getName()
                + " and the save was played by "
                + (actingPlayer == null ? "a build that did not record who" : actingPlayer)
                + (genuinelySomebodyElse
                ? " — a genuinely different player, so step 7 is checked against a real second "
                + "connection on this loader"
                : " — the same player, so step 7 is checked against a second persona-less viewer "
                + "instead, and this loader cannot do better in one client"));

        // Whoever it is, ask the questions of the viewer this run actually has. On the loader where
        // that is a second player the answers are step 7; on the other they are the invariant that
        // step 7 rests on, which is worth checking either way.
        UUID stranger = genuinelySomebodyElse ? viewer
                : UUID.nameUUIDFromBytes("a second player who has done nothing"
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));

        NpcRegistry registry = NpcRegistry.get(server);
        int today = Deed.dayOf(level);
        int villagers = 0;
        int strangers = 0;
        int atTheStandingPrice = 0;
        for (Persona persona : registry.all()) {
            if (!persona.isGenerated()) {
                continue;
            }
            villagers++;
            Bond bond = registry.bonds().at(persona.id(), stranger, today);
            Standing standing = Standing.of(bond);
            if (standing == Standing.NEUTRAL && standing.priceMultiplier() == 1.00F) {
                atTheStandingPrice++;
            }
            if (Dialogue.poolFor(bond, Dialogue.remembersThem(
                    registry.memories().of(persona.id()), stranger)) == Pool.STRANGER) {
                strangers++;
            }
        }

        record(villagers > 0, "STEP 7 there are " + villagers + " generated villager(s) to ask");
        record(strangers == villagers,
                "STEP 7 every one of them speaks a stranger line to somebody who has done nothing ("
                        + strangers + " of " + villagers + ")");
        record(atTheStandingPrice == villagers,
                "STEP 7 and every one of them charges them the standing price ("
                        + atTheStandingPrice + " of " + villagers + " at x1.00)");

        // "Everywhere" is the word in the criterion, so it is asked of both villages rather than of
        // the one the player is standing in. Session 09's ResidencyTest holds the per-settlement
        // half in a unit test; this is the same claim over a real save with two settlements in it.
        int settlements = registry.settlements().size();
        int untouched = 0;
        for (Settlement settlement : registry.settlements().all()) {
            if (!Residency.isResident(registry, settlement.id(), stranger, today)) {
                untouched++;
            }
        }
        record(untouched == settlements,
                "STEP 7 and no settlement in the world has taken them in (" + untouched + " of "
                        + settlements + "), which is what 'everywhere' means");

        // And the half that is about this session rather than about scoping: the player who DID do
        // something is still owed what they earned, so a green step 7 cannot be a mod that stopped
        // working. A run where nobody is above NEUTRAL would pass every assertion above.
        if (actingPlayer != null) {
            int earned = 0;
            for (Persona persona : registry.all()) {
                if (Standing.of(registry.bonds().at(persona.id(), actingPlayer, today))
                        != Standing.NEUTRAL) {
                    earned++;
                }
            }
            record(earned > 0,
                    "STEP 7 and the player who earned something still has it (" + earned
                            + " villager(s) above the standing price), so this is per-player scoping "
                            + "rather than a mod that has stopped working");
        }
    }

    private static void writeSubjects(ServerLevel level) {
        List<String> lines = new ArrayList<>();
        lines.add("site " + testSite.getX() + " " + testSite.getY() + " " + testSite.getZ());
        if (actingPlayer != null) {
            // Session 12: who did all of this. The verify phase needs it to answer a question no
            // earlier session had to ask — whether the player running *this* launch is the same
            // person, which is what decides whether this run can check DESIGN.md §10 step 7 for
            // real. See checkStepSeven.
            lines.add("actor " + actingPlayer);
        }
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
        for (BondRow bond : BONDS) {
            lines.add("bond " + bond.personaId() + " " + bond.about() + " " + bond.trust()
                    + " " + bond.warmth() + " " + bond.fear());
        }
        for (MemoryRow memory : MEMORIES) {
            lines.add("memory " + memory.personaId() + " " + String.join(" ", memory.ring()));
        }
        for (Resident resident : RESIDENTS) {
            // Name last on the line: it contains a space, and everything before it does not.
            lines.add("resident " + resident.personaId() + " " + resident.name());
        }
        if (residencySpeaker != null) {
            // Player name last, for the same reason a villager's is: everything before it is a
            // token with no spaces in it.
            lines.add("residency " + registeredSettlement.id() + " " + residencyPlayer + " "
                    + residencySpeaker + " " + residencyName);
        }
        if (roadEdge != null) {
            // Session 10's row, and its absence is load-bearing: a save written before this session
            // has one settlement in it, and the verify phase has to expect one rather than two.
            // Session 09's residency row carries the same rule and for the same reason.
            lines.add("road " + roadEdge.a() + " " + roadEdge.b());
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
        BONDS.clear();
        MEMORIES.clear();
        villageSite = null;
        registeredSettlement = null;
        roadEdge = null;
        residencySpeaker = null;
        residencyPlayer = null;
        residencyName = null;
        residencySettlement = Persona.UNASSIGNED;
        for (String line : lines) {
            String[] parts = line.split(" ");
            switch (parts[0]) {
                case "site" -> testSite = new BlockPos(Integer.parseInt(parts[1]),
                        Integer.parseInt(parts[2]), Integer.parseInt(parts[3]));
                // Absent from a file written before session 12, and its absence is load-bearing the
                // way session 10's road row is: a cross-build run against an older archive has to
                // report "this save does not say who wrote it" rather than fail to parse.
                case "actor" -> actingPlayer = UUID.fromString(parts[1]);
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
                case "bond" -> BONDS.add(new BondRow(UUID.fromString(parts[1]),
                        UUID.fromString(parts[2]), Byte.parseByte(parts[3]),
                        Byte.parseByte(parts[4]), Byte.parseByte(parts[5])));
                case "memory" -> MEMORIES.add(new MemoryRow(UUID.fromString(parts[1]),
                        List.of(java.util.Arrays.copyOfRange(parts, 2, parts.length))));
                case "resident" -> RESIDENTS.add(new Resident(UUID.fromString(parts[1]),
                        String.join(" ", java.util.Arrays.copyOfRange(parts, 2, parts.length))));
                case "residency" -> {
                    residencySettlement = Integer.parseInt(parts[1]);
                    residencyPlayer = UUID.fromString(parts[2]);
                    residencySpeaker = UUID.fromString(parts[3]);
                    residencyName = String.join(" ",
                            java.util.Arrays.copyOfRange(parts, 4, parts.length));
                }
                case "road" -> roadEdge = new RoadEdge(Integer.parseInt(parts[1]),
                        Integer.parseInt(parts[2]));
                default -> Namesake.LOGGER.warn("[harness] unrecognised subject line '{}'", line);
            }
        }
        Namesake.LOGGER.info("[harness] read {} subject(s), {} resident(s), {} bond(s) and "
                + "{} ring(s), site {}, village {}",
                SUBJECTS.size(), RESIDENTS.size(), BONDS.size(), MEMORIES.size(),
                testSite, villageSite);
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
