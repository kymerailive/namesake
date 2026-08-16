package net.namesake.board;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.namesake.config.Config;
import net.namesake.road.RoadNetwork;
import net.namesake.settlement.Settlements;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Everything about standing a Notice Board up that does not need a running world.
 *
 * <p>The placement itself needs a {@code ServerLevel} — a heightmap, a point-of-interest manager and
 * a loaded chunk — so it is a harness leg rather than a unit test, which is the line
 * {@code WORKPLAN.md} draws between its two instruments. What is here is the part that decides
 * whether this mod may touch somebody's world at all, and it is the part that would be wrong
 * quietly.
 */
class BoardSitingTest {

    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @AfterEach
    void restoreTheDefaults() {
        Config.set(Config.Values.defaults());
    }

    /**
     * <b>Session 10's ruling, at its second block.</b> Every block this mod lays is behind a switch,
     * because a laid block is a laid block and nothing here remembers what was underneath.
     */
    @Test
    @DisplayName("a board is only stood up when the config says so")
    void theSwitchGatesIt() {
        Config.set(Config.Values.defaults());
        assertTrue(BoardSiting.stands(), "DESIGN.md §2 rules content gating all on by default, and "
                + "§5 rules the board the entire onboarding surface — so this is on out of the box");

        Config.set(new Config.Values(Config.Preset.DEFAULT, true, false, true, true, true));
        assertFalse(BoardSiting.stands(), "world.noticeBoard = false must stop it entirely");
    }

    /**
     * <b>The gentle preset must not take the board away</b>, and this is the assertion that says so
     * from the board's side rather than from the config's.
     *
     * <p>They are different axes: gentle is about how sharply villagers treat you, and the board is
     * about whether the mod explains itself at all. A player who asked for a softer village and
     * silently lost the only surface that tells them what is happening has been given the opposite
     * of what they asked for.
     */
    @Test
    @DisplayName("the gentle preset leaves the Notice Board standing")
    void gentleKeepsTheBoard() {
        Config.set(Config.Values.of(Config.Preset.GENTLE));
        assertTrue(BoardSiting.stands());
    }

    /**
     * <b>What may be under a board is a list of names, not a tag.</b> {@code RoadNetwork.PAVEABLE}'s
     * argument: a tag is a thing a modpack can add to, and {@code #minecraft:dirt} already contains
     * farmland. Eight names in a file is a promise that reads the same in every modpack.
     */
    @Test
    @DisplayName("the ground a board may stand on is natural ground and a village path, and nothing else")
    void theGroundIsAnAllowlist() {
        assertTrue(BoardSiting.GROUND.contains(Blocks.DIRT_PATH),
                "a vanilla village square is dirt path, which is the commonest case there is — and "
                        + "it is also what this mod's own roads lay");
        for (Block paveable : RoadNetwork.PAVEABLE) {
            if (paveable == Blocks.MYCELIUM || paveable == Blocks.MOSS_BLOCK) {
                continue;
            }
            assertTrue(BoardSiting.GROUND.contains(paveable), () ->
                    "a road may be laid over " + paveable + " but a board may not stand on it. The "
                            + "two lists are allowed to differ, but not by accident.");
        }

        for (Block forbidden : new Block[]{
                Blocks.FARMLAND, Blocks.OAK_PLANKS, Blocks.COBBLESTONE, Blocks.STONE_BRICKS,
                Blocks.HAY_BLOCK, Blocks.WATER, Blocks.OAK_LEAVES, Blocks.CHEST}) {
            assertFalse(BoardSiting.GROUND.contains(forbidden), () ->
                    "a board must never stand on " + forbidden + ". Farmland is somebody's crop, "
                            + "planks and stone bricks are somebody's floor, and a chest has a block "
                            + "entity. This mod may add to a world and may never take from one.");
        }
    }

    /**
     * The radius that decides "does this village already have one" is the radius that makes a
     * lectern a board, and they are the same constant rather than two that agree.
     *
     * <p>If they drifted, a village with a library at its far edge would get a second lectern by the
     * bell — one more block in somebody's world for nothing, because the library already works.
     */
    @Test
    @DisplayName("a board is looked for at the radius that makes a lectern a board")
    void theSearchRadiusIsMembership() {
        assertTrue(Settlements.MEMBERSHIP_RADIUS > BoardSiting.MAX_OFFSET,
                "a board is placed within " + BoardSiting.MAX_OFFSET + " of the bell but looked for "
                        + "within " + Settlements.MEMBERSHIP_RADIUS + ". The second is what "
                        + "NoticeBoard.boardAt asks through Settlements.containing; the first is "
                        + "just how close to the middle it lands. Placing further out than the "
                        + "search radius would mean standing one up beside a board that exists.");
    }
}
