package net.namesake.neoforge;

import net.minecraft.client.Minecraft;
import net.namesake.Namesake;
import net.namesake.harness.HarnessClient;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;

/**
 * NeoForge client bootstrap. Exists only to give the attach-bet harness a way into a world; there
 * is no client-side mod behaviour yet. Loaded only from a {@code Dist.CLIENT} branch.
 */
public final class NamesakeNeoForgeClient {

    private NamesakeNeoForgeClient() {
    }

    static void register() {
        // A named method rather than a lambda: NeoForge's bus resolves the event type from the
        // handler's signature, and a method reference makes that unambiguous.
        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post.class, NamesakeNeoForgeClient::onClientTick);
        Namesake.LOGGER.info("[harness] NeoForge client tick hook registered");
    }

    private static void onClientTick(ClientTickEvent.Post event) {
        HarnessClient.onClientTick(Minecraft.getInstance());
    }
}
