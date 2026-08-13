package net.namesake.fabric;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.namesake.Namesake;
import net.namesake.command.NamesakeCommands;
import net.namesake.harness.AttachBetHarness;
import net.namesake.npc.PersonaService;
import net.namesake.settlement.SettlementRegistrar;
import net.namesake.verb.Interactions;
import net.namesake.verb.VerbNetwork;

/**
 * Fabric bootstrap. Subscribes the lifecycle hooks the attach bet rests on, the conversation
 * gesture, and hands everything else to {@code common}.
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

        UseEntityCallback.EVENT.register((player, level, hand, entity, hitResult) -> {
            if (!Interactions.isConversationGesture(player, hand, entity)) {
                return InteractionResult.PASS;
            }
            if (level.isClientSide()) {
                // PASS, not CONSUME: the vanilla interact packet still has to reach the server, or
                // the server never learns the gesture happened and never opens the interaction.
                // Cancelling the trade is the server's job — see Interactions.
                Interactions.onClientGesture(entity);
                return InteractionResult.PASS;
            }
            if (player instanceof ServerPlayer serverPlayer) {
                Interactions.onServerGesture(serverPlayer, entity);
            }
            return InteractionResult.CONSUME;
        });

        // Tokens, rate buckets and surveyed-area memory do not outlive a server. In single player,
        // leaving one world and opening another reuses the process.
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            VerbNetwork.onServerStopping();
            SettlementRegistrar.onServerStopping();
        });

        // Spends whatever settlement survey a villager's arrival asked for, a few chunks at a
        // time. Returns on its first line with nothing queued, which is the usual case.
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            SettlementRegistrar.onServerTick(server);
            AttachBetHarness.onServerTick(server);
        });
    }
}
