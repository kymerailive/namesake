package net.namesake.fabric;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.namesake.harness.AttachBetHarness;
import net.namesake.harness.HarnessClient;
import net.namesake.harness.ProfilerHarness;
import net.namesake.verb.ClientPacketSink;

/** Fabric client bootstrap. */
public final class NamesakeFabricClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        // Shared code sends packets through this sink so it never names a loader's client API.
        ClientPacketSink.install(ClientPlayNetworking::send);

        // Either scripted run needs a client that walks itself into a world. Registering this for
        // only one of them is how the profiler sat at the title screen saying nothing.
        if (AttachBetHarness.enabled() || ProfilerHarness.enabled()) {
            ClientTickEvents.END_CLIENT_TICK.register(HarnessClient::onClientTick);
        }
    }
}
