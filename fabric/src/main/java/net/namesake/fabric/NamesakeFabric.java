package net.namesake.fabric;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.block.state.BlockState;
import net.namesake.Namesake;
import net.namesake.board.NoticeBoard;
import net.namesake.command.NamesakeCommands;
import net.namesake.day.Steering;
import net.namesake.harness.AttachBetHarness;
import net.namesake.harness.ProfilerHarness;
import net.namesake.npc.PersonaService;
import net.namesake.settlement.SettlementRegistrar;
import net.namesake.road.RoadNetwork;
import net.namesake.social.Gossip;
import net.namesake.social.SocialEvents;
import net.namesake.social.Trading;
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
        ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> {
            PersonaService.onEntityLoad(entity);
            // After the mint, never before: session 13's roster only wants villagers that already
            // have a generated persona, and the line above is what generates one.
            Steering.onVillagerLoaded(entity);
        });
        ServerEntityEvents.ENTITY_UNLOAD.register((entity, level) -> Steering.onVillagerUnloaded(entity));

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
            // Session 12's standing bands, on the plain right-click — the one both gestures above
            // have declined, and therefore the one on its way to Villager#mobInteract and the trade
            // screen. PASS, not CONSUME: vanilla proceeds, and its own updateSpecialPrices adds the
            // gossip reputation on top of the multiplier we just set. See Trading.
            if (!level.isClientSide() && player instanceof ServerPlayer serverPlayer
                    && entity instanceof Villager villager) {
                Trading.onVillagerInteract(serverPlayer, villager);
            }
            return InteractionResult.PASS;
        });

        // Session 11's Notice Board: an empty lectern in a registered settlement, right-clicked with
        // an empty hand. Vanilla spends that gesture on nothing — LecternBlock.useWithoutItem returns
        // CONSUME and does nothing when there is no book — so reading, placing and taking a book are
        // all untouched, and so is every lectern outside a village. There is no packet from the
        // client here at all: the server sees an interaction it has already reach-checked and sends
        // one player their own board.
        UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
            BlockState state = level.getBlockState(hit.getBlockPos());
            if (!NoticeBoard.isBoardGesture(player, hand, state)) {
                return InteractionResult.PASS;
            }
            if (level.isClientSide()) {
                // PASS so the vanilla block-use packet still reaches the server, which is the only
                // thing that knows whether this lectern is in a settlement. Session 02's rule.
                return InteractionResult.PASS;
            }
            return player instanceof ServerPlayer serverPlayer
                    && NoticeBoard.onServerGesture(serverPlayer, hit.getBlockPos()).isPresent()
                    ? InteractionResult.CONSUME
                    : InteractionResult.PASS;
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
            Steering.onServerStopping();
            net.namesake.board.BoardSiting.onServerStopping();
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
            // Session 15. Reads an int and returns on every tick but the handful where a player has
            // just walked into a village that has no lectern in it. See BoardSiting.
            net.namesake.board.BoardSiting.onServerTick(server);
            // Session 13. Deliberately at the END of the tick, and it is the whole mechanism: the
            // brain runs during the entity tick, so a walk target written by a vanilla behaviour
            // this tick is not acted on by MoveToTargetSink until the next one. This hook is the
            // window in between. See Steering.declineTheJobSite.
            Steering.onServerTick(server);
            AttachBetHarness.onServerTick(server);
            ProfilerHarness.onServerTick(server);
        });
    }
}
