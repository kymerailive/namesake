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

    /**
     * @param modBus session 15's renderer swap needs it and nothing before session 15 did.
     *               {@code EntityRenderersEvent.RegisterRenderers} and
     *               {@code RegisterLayerDefinitions} are {@code IModBusEvent}s — <b>registering
     *               them on the game bus fails silently</b>, which is a swap that compiles, runs,
     *               logs nothing and draws vanilla villagers.
     */
    static void register(net.neoforged.bus.api.IEventBus modBus) {
        // Shared code sends packets through this sink so it never names a loader's client API.
        ClientPacketSink.install(PacketDistributor::sendToServer);
        // And opens screens through this one. It matters more here than on Fabric: NeoForge
        // registers a clientbound payload's handler on the dedicated server too, so a handler that
        // named the screen directly would resolve a net.minecraft.client type on a machine that has
        // none. See ClientScreenSink.
        ClientScreenSink.installNoticeBoard(NoticeBoardScreen::open);
        // Session 15's appearance packet, through the same kind of seam and for the same reason.
        net.namesake.verb.ClientAppearanceSink.install(
                net.namesake.client.Appearances::accept,
                net.namesake.client.Appearances::forgetEverything);

        // Session 15: DESIGN.md §9's renderer swap. On the MOD bus, deliberately — see this
        // method's own parameter note.
        modBus.addListener(net.neoforged.neoforge.client.event.EntityRenderersEvent
                .RegisterLayerDefinitions.class, event -> {
            event.registerLayerDefinition(net.namesake.client.VillagerLookModel.WIDE,
                    () -> net.namesake.client.VillagerLookModel.mesh(false));
            event.registerLayerDefinition(net.namesake.client.VillagerLookModel.SLIM,
                    () -> net.namesake.client.VillagerLookModel.mesh(true));
        });
        modBus.addListener(net.neoforged.neoforge.client.event.EntityRenderersEvent
                .RegisterRenderers.class, event ->
                event.registerEntityRenderer(net.minecraft.world.entity.EntityType.VILLAGER,
                        net.namesake.client.VillagerLookRenderer::new));

        // The variant manifest and the two colormaps, re-read on every resource reload.
        modBus.addListener(
                net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent.class,
                event -> event.registerReloadListener(
                        (net.minecraft.server.packs.resources.ResourceManagerReloadListener)
                                net.namesake.client.Appearances::reload));

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
