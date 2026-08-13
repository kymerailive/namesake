package net.namesake.fabric;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.namesake.harness.AttachBetHarness;
import net.namesake.harness.HarnessClient;

/**
 * Fabric client bootstrap. Exists only to give the attach-bet harness a way into a world; there is
 * no client-side mod behaviour yet.
 */
public final class NamesakeFabricClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        if (AttachBetHarness.enabled()) {
            ClientTickEvents.END_CLIENT_TICK.register(HarnessClient::onClientTick);
        }
    }
}
