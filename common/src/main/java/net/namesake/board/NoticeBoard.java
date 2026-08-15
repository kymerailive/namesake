package net.namesake.board;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LecternBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.namesake.Namesake;
import net.namesake.npc.NpcRegistry;
import net.namesake.platform.VerbTransport;
import net.namesake.settlement.Settlement;
import net.namesake.social.Deed;
import net.namesake.verb.NoticeBoardPayload;

import java.util.List;
import java.util.Optional;

/**
 * <b>The Notice Board: a lectern standing in a village, and nothing else.</b> Session 11, and the
 * first surface in this mod a player can read without typing a debug command.
 *
 * <h2>It has no block entity, and that is the argument this session opens on</h2>
 *
 * <p>{@code DESIGN.md} §9 and {@code WORKPLAN.md} both say <i>a lectern with a block entity — zero
 * new art</i>. The second half is honoured exactly; the first is not, and the reason is a
 * composition failure of the shape sessions 08 and 10 each opened on.
 *
 * <p><b>A block entity is a second persisted store.</b> Minecraft saves it into chunk data, with a
 * schema of its own, on a versioning ladder {@code NpcSchema} cannot see. This project has ruled
 * <i>one file, one version number that cannot disagree with itself</i> four times — settlements at
 * 03, bonds at 05, rings at 06, gossip at 08 — and hard rule 1 owes any persisted change a datafixer
 * and a load test against a pre-change save. There is no fixer ladder for chunk data here and there
 * would be no honest way to write one.
 *
 * <p>So the question the brief asks is the right one: <i>what would it store?</i> Everything on the
 * board is computed when the board is opened, so the only thing left is the single fact that this
 * lectern is a board — <b>and that is derivable.</b> A lectern standing inside a registered
 * settlement is a notice board; a lectern anywhere else is a lectern. That is the road graph's own
 * argument at session 10 and the residency threshold's at 09: a pure function of a table that has
 * been on disk since schema 3, which cannot drift from it because it <i>is</i> it.
 *
 * <p><b>Why not a registered block, a block state or a data component either.</b> All three are the
 * same trade in different clothes. A block state and a data component are still bytes in chunk data
 * and still need something to <i>write</i> them, where deriving needs no writer at all. A registered
 * block costs a registry entry, an item, a recipe, a blockstate file and a model file — it is no
 * longer zero art — and worse, it makes every lectern already standing in every existing world not a
 * board, so the surface that is supposed to teach a new player what this mod does would have to be
 * crafted before it could teach anything.
 *
 * <h2>The gesture is the one vanilla leaves free</h2>
 *
 * <p>{@code LecternBlock.useWithoutItem} returns {@code CONSUME} and does nothing at all when the
 * lectern has no book; putting a book on one goes through {@code useItemOn}, and a lectern that has a
 * book opens the book. <b>So an empty lectern, right-clicked with an empty hand, is a gesture vanilla
 * spends on nothing</b> — which is the only case this takes. Reading a book, placing a book and
 * taking a book are all untouched, and so is every lectern outside a village.
 *
 * <p>That last clause is the road materialiser's rule arriving in a different session: session 10
 * ruled that a player's build is invisible to it, and taking a vanilla gesture in somebody's own base
 * is the same intrusion with no blocks involved.
 *
 * <h2>Only the server opens it</h2>
 *
 * <p>{@code DESIGN.md} §2, ruled at session 02: the client cannot ask. The server sees a block
 * interaction it has already reach-checked, computes {@link Board} for <i>that player</i>, and sends
 * it. Hard rule 6 costs this session nothing, because there is no serverbound packet at all — a
 * reader of a notice board cannot ask it to do anything. No interaction token is minted either, for
 * the same reason: a token exists to authorize a verb, and there is no verb.
 */
public final class NoticeBoard {

    private NoticeBoard() {
    }

    /**
     * An empty lectern, right-clicked with an empty main hand.
     *
     * <p>Deliberately not sneak-gated, unlike the conversation gesture. Sneaking is what keeps
     * <i>trading</i> reachable on a villager; a lectern with no book has nothing to reach past, and
     * asking a new player to discover a modifier key on the one surface that exists to teach them
     * would be a tutorial for the thing that replaces the tutorial.
     */
    public static boolean isBoardGesture(Player player, InteractionHand hand, BlockState state) {
        return hand == InteractionHand.MAIN_HAND
                && player.getMainHandItem().isEmpty()
                && state.is(Blocks.LECTERN)
                && !state.getValue(LecternBlock.HAS_BOOK);
    }

    /**
     * Server side: work out what this village knows about this player, and send it to them.
     *
     * @return the board that was sent, or empty when this lectern is not in a registered settlement —
     *         in which case nothing was sent and vanilla's own behaviour is untouched
     */
    public static Optional<Board> onServerGesture(ServerPlayer player, BlockPos pos) {
        ServerLevel level = player.serverLevel();
        Optional<Board> board = boardAt(level, pos, player.getUUID());
        board.ifPresent(open -> {
            List<BoardText.Line> lines = BoardText.of(open);
            VerbTransport.get().sendTo(player, new NoticeBoardPayload(lines));
            Namesake.LOGGER.debug("Notice board at {} opened for {}: {} line(s), {} watched, {} heard",
                    pos, player.getGameProfile().getName(), lines.size(),
                    open.witnessed().size(), open.hearsay().size());
        });
        return board;
    }

    /**
     * What the village around {@code pos} knows about {@code viewer}, or empty if there is no village.
     *
     * <p>Separate from {@link #onServerGesture} so that a second viewer can be asked the same
     * question through the same code — which is what the harness leg for
     * {@code DESIGN.md} §10 step 7 does, and what makes "two players do not see each other's history"
     * a property of this method rather than a claim about the packet layer.
     */
    public static Optional<Board> boardAt(ServerLevel level, BlockPos pos, java.util.UUID viewer) {
        NpcRegistry registry = NpcRegistry.get(level.getServer());
        Optional<Settlement> here =
                registry.settlements().containing(level.dimension().location(), pos);
        return here.map(settlement ->
                Board.of(registry, settlement, viewer, Deed.dayOf(level)));
    }
}
