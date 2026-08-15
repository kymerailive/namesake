package net.namesake.neoforge;

import net.minecraft.client.Minecraft;
import net.namesake.Namesake;
import net.namesake.harness.AttachBetHarness;
import net.namesake.harness.HarnessClient;
import net.namesake.client.NoticeBoardScreen;
import net.namesake.harness.ProfilerHarness;
import net.namesake.verb.ClientPacketSink;
import net.namesake.verb.ClientScreenSink;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.PacketDistributor;

/** NeoForge client bootstrap. Loaded only from a {@code Dist.CLIENT} branch. */
public final class NamesakeNeoForgeClient {

    private NamesakeNeoForgeClient() {
    }

    static void register() {
        // Shared code sends packets through this sink so it never names a loader's client API.
        ClientPacketSink.install(PacketDistributor::sendToServer);
        // And opens screens through this one. It matters more here than on Fabric: NeoForge
        // registers a clientbound payload's handler on the dedicated server too, so a handler that
        // named the screen directly would resolve a net.minecraft.client type on a machine that has
        // none. See ClientScreenSink.
        ClientScreenSink.installNoticeBoard(NoticeBoardScreen::open);

        // Either scripted run needs a client that walks itself into a world. Registering this for
        // only one of them is how the profiler sat at the title screen saying nothing.
        if (AttachBetHarness.enabled() || ProfilerHarness.enabled()) {
            // A named method rather than a lambda: NeoForge's bus resolves the event type from the
            // handler's signature, and a method reference makes that unambiguous.
            NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post.class, NamesakeNeoForgeClient::onClientTick);
            Namesake.LOGGER.info("[harness] NeoForge client tick hook registered");
        }
    }

    private static void onClientTick(ClientTickEvent.Post event) {
        HarnessClient.onClientTick(Minecraft.getInstance());
    }
}
