package net.namesake.fabric;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.namesake.harness.AttachBetHarness;
import net.namesake.harness.HarnessClient;
import net.namesake.client.NoticeBoardScreen;
import net.namesake.harness.ProfilerHarness;
import net.namesake.verb.ClientPacketSink;
import net.namesake.verb.ClientScreenSink;

/** Fabric client bootstrap. */
public final class NamesakeFabricClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        // Shared code sends packets through this sink so it never names a loader's client API.
        ClientPacketSink.install(ClientPlayNetworking::send);
        // And opens screens through this one, so the shared clientbound handler never resolves a
        // net.minecraft.client type. See ClientScreenSink.
        ClientScreenSink.installNoticeBoard(NoticeBoardScreen::open);
        // Session 15's appearance packet, through the same kind of seam and for the same reason.
        net.namesake.verb.ClientAppearanceSink.install(
                net.namesake.client.Appearances::accept,
                net.namesake.client.Appearances::forgetEverything);

        // Session 15: DESIGN.md §9's renderer swap. Two layer definitions rather than one, because
        // only PlayerModel.createMesh takes a slim flag — see VillagerLookModel.
        net.fabricmc.fabric.api.client.rendering.v1.EntityModelLayerRegistry.registerModelLayer(
                net.namesake.client.VillagerLookModel.WIDE,
                () -> net.namesake.client.VillagerLookModel.mesh(false));
        net.fabricmc.fabric.api.client.rendering.v1.EntityModelLayerRegistry.registerModelLayer(
                net.namesake.client.VillagerLookModel.SLIM,
                () -> net.namesake.client.VillagerLookModel.mesh(true));
        net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry.register(
                net.minecraft.world.entity.EntityType.VILLAGER,
                net.namesake.client.VillagerLookRenderer::new);

        // The variant manifest and the two colormaps, re-read on every resource reload — which is
        // what makes "datapack-loadable from v1" true rather than aspirational.
        net.fabricmc.fabric.api.resource.ResourceManagerHelper
                .get(net.minecraft.server.packs.PackType.CLIENT_RESOURCES)
                .registerReloadListener(new net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener() {
                    @Override
                    public net.minecraft.resources.ResourceLocation getFabricId() {
                        return net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(
                                net.namesake.Namesake.MOD_ID, "appearance");
                    }

                    @Override
                    public void onResourceManagerReload(
                            net.minecraft.server.packs.resources.ResourceManager manager) {
                        net.namesake.client.Appearances.reload(manager);
                    }
                });

        // Either scripted run needs a client that walks itself into a world. Registering this for
        // only one of them is how the profiler sat at the title screen saying nothing.
        if (AttachBetHarness.enabled() || ProfilerHarness.enabled()) {
            ClientTickEvents.END_CLIENT_TICK.register(HarnessClient::onClientTick);
        }
    }
}
