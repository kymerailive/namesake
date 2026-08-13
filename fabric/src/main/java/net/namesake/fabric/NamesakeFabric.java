package net.namesake.fabric;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.namesake.Namesake;
import net.namesake.command.NamesakeCommands;
import net.namesake.harness.AttachBetHarness;
import net.namesake.npc.PersonaService;

/**
 * Fabric bootstrap. Subscribes the two lifecycle hooks the attach bet rests on and hands everything
 * else to {@code common}.
 */
public final class NamesakeFabric implements ModInitializer {

    @Override
    public void onInitialize() {
        Namesake.init();

        // Fires for fresh spawns and for chunk loads alike (ServerLevel.EntityCallbacks#onTrackingStart).
        ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> PersonaService.onEntityLoad(entity));

        // Fires inside Mob.convertTo, before the new entity is added to the level. Covers both
        // villager -> zombie villager and the cure back, since both go through convertTo.
        ServerLivingEntityEvents.MOB_CONVERSION.register(
                (previous, converted, keepEquipment) -> PersonaService.onConversion(previous, converted));

        CommandRegistrationCallback.EVENT.register(
                (dispatcher, registryAccess, environment) -> NamesakeCommands.register(dispatcher));

        if (AttachBetHarness.enabled()) {
            ServerTickEvents.END_SERVER_TICK.register(AttachBetHarness::onServerTick);
        }
    }
}
