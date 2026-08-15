package net.namesake.verb;

import java.util.function.Consumer;

/**
 * How a shared payload handler opens a screen without naming a client class.
 *
 * <p>The twin of {@link ClientPacketSink}, and it exists for the mirror-image reason. A clientbound
 * payload's handler is registered on <b>both</b> sides on NeoForge — it simply never runs on a
 * dedicated server — so a handler written as {@code NoticeBoardScreen::open} would resolve
 * {@code net.minecraft.client.gui.screens.Screen} during registration, on a machine that has no
 * client classes at all. That is the same trap {@code ClientPacketSink} was written for and the same
 * shape as session 01's {@code SavedData.Factory}: it compiles on both and throws on one.
 *
 * <p>Each loader's client bootstrap installs a method reference here. A dedicated server installs
 * nothing and the sink stays inert, which is exactly what should happen to a screen on a server.
 */
public final class ClientScreenSink {

    private static volatile Consumer<NoticeBoardPayload> noticeBoard;

    private ClientScreenSink() {
    }

    public static void installNoticeBoard(Consumer<NoticeBoardPayload> opener) {
        noticeBoard = opener;
    }

    /** No-op when nothing is installed. A server has no screen to open. */
    public static void openNoticeBoard(NoticeBoardPayload payload) {
        Consumer<NoticeBoardPayload> installed = noticeBoard;
        if (installed != null) {
            installed.accept(payload);
        }
    }
}
