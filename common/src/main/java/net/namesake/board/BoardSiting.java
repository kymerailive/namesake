package net.namesake.board;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.namesake.Namesake;
import net.namesake.config.Config;
import net.namesake.npc.NpcRegistry;
import net.namesake.profile.Profiling;
import net.namesake.settlement.Settlement;
import net.namesake.settlement.Settlements;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;

/**
 * <b>Stands a lectern beside a village's bell when the village has none.</b> Session 15, and it is
 * the one ruling the exit criterion turned out to rest on rather than sit beside.
 *
 * <h2>Why it is built at all, which is arithmetic rather than taste</h2>
 *
 * <p>Session 15's criterion is <i>a stranger plays 45 minutes and can describe, unprompted,
 * something a villager remembered about them</i>. Forty-five minutes is about 2.25 in-game days
 * against a median of four to residency, so <b>the name swap cannot be what carries it</b> — a
 * stranger will not get there. What can is the Notice Board, which reads a bond of zero and needs no
 * threshold at all. And {@code DESIGN.md} §5 rules the board <i>the entire onboarding surface</i>
 * with no tutorial behind it.
 *
 * <p>So the criterion needs a stranger to find a board without being told where one is, and today a
 * village has a lectern only if vanilla happened to generate a library. <b>That is a criterion
 * resting on a coin.</b> The question was ruled open by the owner at the close of session 11 and
 * parked here; this is the answer, and it is yes.
 *
 * <h2>What it costs somebody's world, named rather than discovered</h2>
 *
 * <p>Session 10 put every block this mod lays behind a switch and this is the second one, so it is
 * behind {@code world.noticeBoard} for the same reason: <i>a laid block is a laid block</i>, and
 * nothing here remembers what was underneath. Three consequences, all of them stated in the config
 * file's own comments rather than only here:
 *
 * <ol>
 *   <li><b>A lectern is a librarian's workstation.</b> A village with a bell and no library may gain
 *       a librarian it would not have had. That is a real change to somebody's village and it is the
 *       largest thing this setting does. It is not dodged by placing the block somewhere a villager
 *       will not path to, because a point of interest is a point of interest wherever it stands.</li>
 *   <li><b>It is not reversed if the setting is turned off later.</b> Same as the roads.</li>
 *   <li><b>It can only ever add one.</b> Never a second, never a replacement, and never on top of
 *       anything — see {@link #site}.</li>
 * </ol>
 *
 * <h2>Nothing is persisted, and the world is what remembers</h2>
 *
 * <p>{@code DESIGN.md} §2 rules the board has no block entity because a block entity is a second
 * persisted store. The same argument one layer out decides this class: <b>"has this settlement got a
 * board" is a question the world answers</b>, so there is no "board placed" flag on the settlement,
 * no schema field, and nothing that can disagree with the block. Re-running is a no-op because the
 * lectern it would place is already the lectern it looks for.
 *
 * <p>The consequence, recorded: a player who breaks the lectern gets another one the next time the
 * server restarts and they walk back into the village. That is the correct behaviour for an
 * onboarding surface and the wrong behaviour for scenery, and it is the price of storing nothing.
 * {@code world.noticeBoard = false} is the answer for somebody who wants it gone.
 *
 * <h2>Driven by the settlement table's revision, not by the survey</h2>
 *
 * <p>Modelled on {@code RoadNetwork}, deliberately, rather than hooked into
 * {@code SettlementRegistrar.commit}. Three reasons and the third is the one that decides it: it
 * keeps the settlement package from having to know what a board is; a commit happens on a tick when
 * the bell's chunk may not be loaded, because a point of interest is read off disk; and <b>a
 * settlement loaded from a save is never committed at all</b>, so a commit hook would give boards
 * only to villages found after this build was installed.
 */
public final class BoardSiting {

    /**
     * How far from the bell a site may be. Small on purpose: the board is meant to read as the
     * village's own noticeboard, standing where the village gathers.
     */
    public static final int MAX_OFFSET = 5;

    /**
     * What may be underneath one. <b>An explicit list rather than a block tag</b>, for
     * {@code RoadNetwork.PAVEABLE}'s reason: a tag is a thing a modpack can add to, and eight names
     * in a file is a promise that reads the same in every modpack.
     *
     * <p>{@code DIRT_PATH} is on it because that is what a vanilla village square is made of, and it
     * is also what this mod's own roads lay — so a board can stand on a path and never on a floor,
     * a roof or a farm.
     */
    public static final Set<Block> GROUND = Set.of(
            Blocks.GRASS_BLOCK, Blocks.DIRT, Blocks.COARSE_DIRT, Blocks.PODZOL,
            Blocks.ROOTED_DIRT, Blocks.DIRT_PATH, Blocks.GRAVEL, Blocks.SAND);

    /** One settlement considered per tick, at most. There is never a queue worth draining faster. */
    private static final int PER_TICK = 1;

    /** Settlement ids already considered this server run. Never persisted — see the class note. */
    private static final Set<Integer> CONSIDERED = new HashSet<>();
    private static final Deque<Integer> PENDING = new ArrayDeque<>();

    private static int knownRevision = -1;

    private BoardSiting() {
    }

    /** Whether this mod may stand a board up at all. */
    public static boolean stands() {
        return Config.get().noticeBoard();
    }

    /**
     * Considers at most one settlement, and only one whose bell is loaded.
     *
     * <p>On the overwhelming majority of ticks this reads an int and returns, which is
     * {@code Gossip.onServerTick}'s shape: the queue is empty and the revision has not moved.
     */
    public static void onServerTick(MinecraftServer server) {
        if (Profiling.MOD_INERT) {
            // Hard rule 4's baseline: the same world with none of our code in it.
            return;
        }
        if (!stands()) {
            return;
        }

        Settlements settlements = NpcRegistry.get(server).settlements();
        if (settlements.revision() != knownRevision) {
            knownRevision = settlements.revision();
            for (Settlement settlement : settlements.all()) {
                if (CONSIDERED.add(settlement.id())) {
                    PENDING.add(settlement.id());
                }
            }
        }
        if (PENDING.isEmpty()) {
            return;
        }

        for (int served = 0; served < PER_TICK && !PENDING.isEmpty(); served++) {
            int id = PENDING.poll();
            Settlement settlement = settlements.byId(id).orElse(null);
            if (settlement == null) {
                continue;
            }
            ServerLevel level = levelOf(server, settlement);
            if (level == null) {
                // A dimension that is not loaded is not a dimension we can look at. Dropped rather
                // than re-queued: the revision bump that brought it here will not come again, and a
                // settlement in an unloaded dimension has no player in it to onboard.
                continue;
            }
            BlockPos bell = settlement.centre();
            if (level.getChunkSource().getChunkNow(bell.getX() >> 4, bell.getZ() >> 4) == null) {
                // Put it back. This is the ordinary case — nobody is standing in that village — and
                // it costs one deque operation on a tick that would otherwise do nothing at all.
                PENDING.add(id);
                return;
            }
            stand(level, settlement, bell);
        }
    }

    /**
     * Places one, if this settlement has none.
     *
     * <p>Package-visible so the harness can drive it directly rather than by waiting for a tick it
     * cannot predict.
     */
    public static boolean stand(ServerLevel level, Settlement settlement, BlockPos bell) {
        if (hasABoard(level, bell)) {
            Namesake.LOGGER.debug("Settlement {} already has a lectern within {} of its bell; "
                    + "standing nothing up", settlement.id(), Settlements.MEMBERSHIP_RADIUS);
            return false;
        }
        BlockPos site = site(level, bell);
        if (site == null) {
            Namesake.LOGGER.info("Settlement {} has no Notice Board and nowhere within {} blocks of "
                    + "its bell to stand one; leaving it alone", settlement.id(), MAX_OFFSET);
            return false;
        }
        level.setBlockAndUpdate(site, Blocks.LECTERN.defaultBlockState());
        Namesake.LOGGER.info("Stood a Notice Board (a plain lectern) at {} for settlement {}, {} "
                        + "block(s) from its bell at {}. Right-click it empty-handed. Turn this off "
                        + "with {} = false.",
                site, settlement.id(), (int) Math.sqrt(site.distSqr(bell)), bell,
                Config.KEY_NOTICE_BOARD);
        return true;
    }

    /**
     * Whether a lectern already stands inside this settlement.
     *
     * <p>The radius is {@link Settlements#MEMBERSHIP_RADIUS} rather than {@link #MAX_OFFSET},
     * because that is the radius that makes a lectern a board: {@code NoticeBoard.boardAt} asks
     * {@code Settlements.containing}, so a library at the far edge of a village already works and
     * putting a second one by the bell would be this mod adding a block it does not need.
     *
     * <p>Read through the point-of-interest manager rather than by scanning block states. A lectern
     * registers under {@code minecraft:librarian}, and vanilla has already indexed it — a 96-block
     * disc is about 144 chunk columns of block states and one point-of-interest query.
     */
    public static boolean hasABoard(ServerLevel level, BlockPos bell) {
        return level.getPoiManager()
                .getInSquare(holder -> holder.is(PoiTypes.LIBRARIAN), bell,
                        Settlements.MEMBERSHIP_RADIUS, PoiManager.Occupancy.ANY)
                .findAny()
                .isPresent();
    }

    /**
     * Somewhere to put one, or {@code null}.
     *
     * <p>Searched outward from the bell so a board lands as close to where the village gathers as
     * the ground allows. Every guard here is {@code RoadNetwork.pave}'s, and the reason is the same:
     * this mod may add to somebody's world and may never take anything out of it.
     *
     * <ul>
     *   <li>The <b>height comes from the heightmap</b>, never from the bell's own y. A bell in a
     *       tower and a bell on the ground are the same block at different heights, and
     *       {@code RoadTrail}'s javadoc records what happens when a y is assumed: a village got
     *       built at −64 inside the deepslate.</li>
     *   <li>The target must be <b>replaceable</b> — air, grass, snow — so nothing is destroyed.</li>
     *   <li>The block <b>below</b> must be in {@link #GROUND}, which is what keeps a board off a
     *       roof, a floor, a bridge and a farm.</li>
     *   <li>Nothing with a <b>block entity</b> is touched, and nothing under water.</li>
     *   <li>And the site must have <b>air above it</b>, because a lectern with a block on its head
     *       is a lectern nobody can click.</li>
     * </ul>
     */
    public static BlockPos site(ServerLevel level, BlockPos bell) {
        for (int ring = 2; ring <= MAX_OFFSET; ring++) {
            for (int dx = -ring; dx <= ring; dx++) {
                for (int dz = -ring; dz <= ring; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) {
                        continue;
                    }
                    BlockPos candidate = level.getHeightmapPos(
                            Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, bell.offset(dx, 0, dz));
                    if (isASite(level, candidate)) {
                        return candidate;
                    }
                }
            }
        }
        return null;
    }

    /** {@link #site}'s per-position test, split out so a unit test can reach it. */
    static boolean isASite(ServerLevel level, BlockPos pos) {
        if (pos.getY() <= level.getMinBuildHeight() + 1
                || pos.getY() >= level.getMaxBuildHeight() - 1) {
            return false;
        }
        BlockState here = level.getBlockState(pos);
        if (!here.canBeReplaced() || here.hasBlockEntity()) {
            return false;
        }
        if (!level.getFluidState(pos).isEmpty() || !level.getFluidState(pos.above()).isEmpty()) {
            return false;
        }
        if (!level.getBlockState(pos.above()).canBeReplaced()) {
            return false;
        }
        BlockState below = level.getBlockState(pos.below());
        return GROUND.contains(below.getBlock()) && !below.hasBlockEntity();
    }

    private static ServerLevel levelOf(MinecraftServer server, Settlement settlement) {
        for (ServerLevel level : server.getAllLevels()) {
            if (level.dimension().location().equals(settlement.dimension())) {
                return level;
            }
        }
        return null;
    }

    /** Forgets what it has considered. Called when a server stops, like every other run-scoped map. */
    public static void onServerStopping() {
        CONSIDERED.clear();
        PENDING.clear();
        knownRevision = -1;
    }

    /** How many settlements are still waiting for a player to walk into them. */
    public static int pending() {
        return PENDING.size();
    }

    /**
     * Puts one settlement back in the queue. <b>For the harness, and it is here because the first
     * version of that leg failed by fighting this class rather than watching it.</b>
     *
     * <p>A settlement is considered once per server run, so a leg that breaks a village's lectern to
     * see one replaced gets nothing — correctly, and that is the shipped behaviour: <i>a player who
     * breaks the board gets another one the next time the server restarts.</i> Without this the leg
     * has to call {@link #stand} directly, which tests the placement and <b>not the thing that
     * decides when to place</b>; and it raced the tick hook, because teleporting a player to the
     * bell is exactly what loads the chunk this class is waiting for.
     *
     * <p>So the harness asks for a restart's worth of forgetting rather than reaching past the
     * mechanism, and the leg then watches the ordinary path do the ordinary thing.
     */
    public static void reconsider(int settlementId) {
        CONSIDERED.remove(settlementId);
        knownRevision = -1;
    }
}
