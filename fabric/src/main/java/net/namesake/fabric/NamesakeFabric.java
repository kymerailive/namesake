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
import net.minecraft.world.entity.npc.Villager;
import net.namesake.Namesake;
import net.namesake.command.NamesakeCommands;
import net.namesake.harness.AttachBetHarness;
import net.namesake.harness.ProfilerHarness;
import net.namesake.npc.PersonaService;
import net.namesake.settlement.SettlementRegistrar;
import net.namesake.road.RoadNetwork;
import net.namesake.social.Gossip;
import net.namesake.social.SocialEvents;
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
            if (Interactions.isConversationGesture(player, hand, entity)) {
                if (level.isClientSide()) {
                    // PASS, not CONSUME: the vanilla interact packet still has to reach the server,
                    // or the server never learns the gesture happened and never opens the
                    // interaction. Cancelling the trade is the server's job — see Interactions.
                    Interactions.onClientGesture(entity);
                    return InteractionResult.PASS;
                }
                if (player instanceof ServerPlayer serverPlayer) {
                    Interactions.onServerGesture(serverPlayer, entity);
                }
                return InteractionResult.CONSUME;
            }
            // The same gesture with the hand full. Session 05: this is where three of the six deed
            // types come from. Same client/server split, and for the same reason.
            if (SocialEvents.isGiveGesture(player, hand, entity)) {
                if (level.isClientSide()) {
                    return InteractionResult.PASS;
                }
                if (player instanceof ServerPlayer serverPlayer && entity instanceof Villager villager) {
                    SocialEvents.onGive(serverPlayer, hand, villager);
                }
                return InteractionResult.CONSUME;
            }
            return InteractionResult.PASS;
        });

        // The other three deed types. Both fire after the engine has already applied the damage, so
        // a killing blow reads as dead here and becomes KILLED_RESIDENT rather than a strike and a
        // killing both.
        ServerLivingEntityEvents.AFTER_DAMAGE.register(
                (entity, source, baseDamageTaken, damageTaken, blocked) ->
                        SocialEvents.onHurt(entity, source, damageTaken));
        ServerLivingEntityEvents.AFTER_DEATH.register(SocialEvents::onDeath);

        // Tokens, rate buckets and surveyed-area memory do not outlive a server. In single player,
        // leaving one world and opening another reuses the process.
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            VerbNetwork.onServerStopping();
            SettlementRegistrar.onServerStopping();
            RoadNetwork.onServerStopping();
        });

        // Spends whatever settlement survey a villager's arrival asked for, a few chunks at a
        // time; from session 08, retells one story per settlement that has one, every 250 ticks;
        // and from session 10, routes one road off-thread and lays a bounded number of its blocks.
        // All three return on their first line when there is nothing to do, which is the usual
        // case; see Gossip for why the drain is bounded by construction rather than measured, and
        // RoadNetwork for what a road will and will not replace.
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            SettlementRegistrar.onServerTick(server);
            Gossip.onServerTick(server);
            RoadNetwork.onServerTick(server);
            AttachBetHarness.onServerTick(server);
            ProfilerHarness.onServerTick(server);
        });
    }
}
