package net.namesake.verb;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.namesake.Namesake;
import net.namesake.board.BoardText;

import java.util.List;

/**
 * <b>One player's notice board, already laid out.</b> Session 11.
 *
 * <p>Clientbound and nothing else, so hard rule 6 costs this session nothing: there is no serverbound
 * verb here to gate, because there is nothing a reader of a board can ask it to do. The board is
 * computed when the server sees the vanilla block interaction it has already reach-checked, sent
 * once, and never updated — {@code WORKPLAN.md}'s "computed on GUI open, never ticked".
 *
 * <p><b>The rows are laid out on the server, and that is the decision worth recording.</b> The
 * alternative is sending the structure and laying it out on the client, which would put the one thing
 * this session has to get right — a string measured against the space it has to sit in — on the far
 * side of a seam where no unit test in {@code common} can reach it. Laid out here,
 * {@code CommandLayoutTest} measures the real rows in every state the board has, and the client draws
 * what it is given.
 *
 * <p><b>Two players opening one lectern do not see each other's history</b>, and it is structural
 * rather than checked. {@code Board.of} takes the viewer's own UUID, nothing caches the result, and
 * this payload goes to one player through {@code VerbTransport.sendTo}. There is nowhere for a board
 * to be stored, so there is nowhere for the wrong person to read one from. {@code DESIGN.md} §2's
 * 80/20 personal split and session 12's step 7 both rest on that being true by construction.
 */
public record NoticeBoardPayload(List<BoardText.Line> lines) implements ClientboundPayload {

    public static final CustomPacketPayload.Type<NoticeBoardPayload> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(Namesake.MOD_ID, "notice_board"));

    /**
     * A protocol bound rather than a display bound.
     *
     * <p>What actually keeps a board readable is {@code NoticeBoard.MAX_ROWS}, which shows the newest
     * rows and <b>says how many it did not show</b>. This number exists so a malformed or hostile
     * packet cannot ask a client to allocate an unbounded list, and it sits far above anything the
     * layout can produce.
     */
    private static final int MAX_LINES = 512;

    private static final BoardText.Tone[] TONES = BoardText.Tone.values();

    private static final StreamCodec<ByteBuf, BoardText.Line> LINE = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, line -> line.tone().ordinal(),
            ByteBufCodecs.VAR_INT, BoardText.Line::indent,
            ByteBufCodecs.STRING_UTF8, BoardText.Line::left,
            ByteBufCodecs.STRING_UTF8, BoardText.Line::right,
            (tone, indent, left, right) -> new BoardText.Line(
                    TONES[Math.floorMod(tone, TONES.length)], Math.max(0, indent), left, right));

    public static final StreamCodec<ByteBuf, NoticeBoardPayload> CODEC =
            LINE.apply(ByteBufCodecs.list(MAX_LINES))
                    .map(NoticeBoardPayload::new, NoticeBoardPayload::lines);

    public NoticeBoardPayload {
        lines = List.copyOf(lines);
    }

    @Override
    public CustomPacketPayload.Type<NoticeBoardPayload> type() {
        return TYPE;
    }
}
