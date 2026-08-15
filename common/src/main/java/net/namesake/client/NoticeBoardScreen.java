package net.namesake.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.namesake.board.BoardText;
import net.namesake.verb.NoticeBoardPayload;

import java.util.List;

/**
 * <b>The Notice Board, drawn.</b> Session 11.
 *
 * <p>This class does no layout and holds no state about the world. Every row arrived already
 * measured — see {@link BoardText} for why the laying out happens on the server — so all that is left
 * here is a panel, a colour per tone, and a scroll offset.
 *
 * <h2>Zero art, and the panel is drawn rather than textured</h2>
 *
 * <p>{@code DESIGN.md} §9 says GUIs use vanilla nine-slice backgrounds, and the intent behind that
 * clause is that this mod draws no new art. A panel made of four {@code fill} calls draws even less:
 * it is the construction vanilla's own tooltips use, it needs no texture at all, and — the deciding
 * reason — <b>a sprite name that is wrong is a failure nothing catches until the screen is on
 * somebody's monitor</b>, which is precisely the class of defect this project has lost six strings
 * to. There is nothing here that can be missing.
 *
 * <h2>It must not pause the game, and that is not a preference</h2>
 *
 * <p>{@code Screen.isPauseScreen} defaults to <b>true</b>, and {@code Minecraft.runTick} sets
 * {@code pause} whenever a pausing screen is up in single player — which <b>stops the integrated
 * server ticking.</b> Every deadline the attach-bet harness has is counted in server ticks, so a
 * board that paused would wedge a scripted run with no error and no last line in the log. That is
 * session 05's undiagnosed wedge exactly, and {@code HarnessClient.unattended} carries the diagnosis.
 * It would also be wrong on its own terms: a village whose clock stops while you read about it is not
 * a village.
 */
public final class NoticeBoardScreen extends Screen {

    private static final int HEADING = 0xFFFFF3DC;
    private static final int BODY = 0xFFD9D2C6;
    private static final int QUIET = 0xFF9A9184;

    private static final int OUTLINE = 0xFF000000;
    private static final int PANEL = 0xF0140F0B;
    private static final int TOP_RULE = 0xFF6E5B44;
    private static final int BOTTOM_RULE = 0xFF3A2F22;
    private static final int SCROLLBAR = 0xFF6E5B44;

    private final List<BoardText.Line> lines;

    private int panelX;
    private int panelY;
    private int panelHeight;
    private int scroll;
    private int maxScroll;

    private NoticeBoardScreen(List<BoardText.Line> lines) {
        super(Component.literal("Notice Board"));
        this.lines = lines;
    }

    /** Installed into {@code ClientScreenSink} by each loader's client bootstrap. */
    public static void open(NoticeBoardPayload payload) {
        Minecraft.getInstance().setScreen(new NoticeBoardScreen(payload.lines()));
    }

    /** What is on the board. Read by the harness, which asserts against a real save's history. */
    public List<BoardText.Line> lines() {
        return lines;
    }

    @Override
    protected void init() {
        int content = lines.size() * BoardText.LINE_HEIGHT;
        panelHeight = Math.min(BoardText.MAX_PANEL_HEIGHT, content + 2 * BoardText.PADDING);
        panelX = (width - BoardText.PANEL_WIDTH) / 2;
        panelY = (height - panelHeight) / 2;
        maxScroll = Math.max(0, content - (panelHeight - 2 * BoardText.PADDING));
        scroll = Math.min(scroll, maxScroll);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (maxScroll > 0) {
            scroll = Math.clamp(scroll - (int) (scrollY * BoardText.LINE_HEIGHT), 0, maxScroll);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);

        int right = panelX + BoardText.PANEL_WIDTH;
        int bottom = panelY + panelHeight;
        graphics.fill(panelX - 1, panelY - 1, right + 1, bottom + 1, OUTLINE);
        graphics.fill(panelX, panelY, right, bottom, PANEL);
        graphics.fill(panelX, panelY, right, panelY + 1, TOP_RULE);
        graphics.fill(panelX, bottom - 1, right, bottom, BOTTOM_RULE);

        int textLeft = panelX + BoardText.PADDING;
        int textTop = panelY + BoardText.PADDING;
        int textBottom = bottom - BoardText.PADDING;

        graphics.enableScissor(textLeft, textTop, textLeft + BoardText.TEXT_WIDTH, textBottom);
        int y = textTop - scroll;
        for (BoardText.Line line : lines) {
            if (!line.isBlank() && y + BoardText.LINE_HEIGHT > textTop && y < textBottom) {
                int colour = colourOf(line.tone());
                graphics.drawString(font, line.left(),
                        textLeft + line.indent() * BoardText.INDENT, y, colour);
                if (!line.right().isEmpty()) {
                    graphics.drawString(font, line.right(),
                            textLeft + BoardText.TEXT_WIDTH - font.width(line.right()), y, colour);
                }
            }
            y += BoardText.LINE_HEIGHT;
        }
        graphics.disableScissor();

        if (maxScroll > 0) {
            int track = textBottom - textTop;
            int thumb = Math.max(8, track * track / (track + maxScroll));
            int offset = (track - thumb) * scroll / maxScroll;
            graphics.fill(right - 3, textTop + offset, right - 1, textTop + offset + thumb, SCROLLBAR);
        }
    }

    private static int colourOf(BoardText.Tone tone) {
        return switch (tone) {
            case HEADING -> HEADING;
            case BODY -> BODY;
            case QUIET -> QUIET;
        };
    }
}
