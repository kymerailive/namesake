package net.namesake.neoforge;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.block.state.BlockState;
import net.namesake.Namesake;
import net.namesake.board.NoticeBoard;
import net.namesake.command.NamesakeCommands;
import net.namesake.harness.AttachBetHarness;
import net.namesake.harness.ProfilerHarness;
import net.namesake.npc.PersonaService;
import net.namesake.platform.VerbTransport;
import net.namesake.settlement.SettlementRegistrar;
import net.namesake.road.RoadNetwork;
import net.namesake.social.Gossip;
import net.namesake.social.SocialEvents;
import net.namesake.social.Trading;
import net.namesake.verb.Interactions;
import net.namesake.verb.VerbNetwork;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.living.LivingConversionEvent;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** NeoForge bootstrap. Mirror of {@code NamesakeFabric}, using NeoForge's equivalents. */
@Mod(Namesake.MOD_ID)
public final class NamesakeNeoForge {

    public NamesakeNeoForge(IEventBus modBus) {
        // Before Namesake.init(), which forces the PersonaLink implementation to load: the
        // DeferredRegister has to be on the mod bus before the registry event fires.
        NeoForgeAttachments.register(modBus);

        Namesake.init();

        // NeoForge only accepts payload registrations inside RegisterPayloadHandlersEvent, which
        // fires after every mod constructor. Namesake.init() has already queued them all; this is
        // where they are flushed.
        modBus.addListener(RegisterPayloadHandlersEvent.class,
                ((NeoForgeVerbTransport) VerbTransport.get())::onRegisterPayloadHandlers);

        // PersistentEntitySectionManager#addEntity fires this for chunk loads as well as fresh
        // spawns, so it covers the same ground as Fabric's ENTITY_LOAD.
        NeoForge.EVENT_BUS.addListener(NamesakeNeoForge::onEntityJoin);
        NeoForge.EVENT_BUS.addListener(NamesakeNeoForge::onConversion);
        NeoForge.EVENT_BUS.addListener(NamesakeNeoForge::onRegisterCommands);
        NeoForge.EVENT_BUS.addListener(NamesakeNeoForge::onEntityInteract);
        NeoForge.EVENT_BUS.addListener(NamesakeNeoForge::onBlockInteract);
        // Session 05's other two deed doors. Post rather than Pre: the damage is already applied,
        // so a killing blow reads as dead and becomes KILLED_RESIDENT rather than both.
        NeoForge.EVENT_BUS.addListener(LivingDamageEvent.Post.class, NamesakeNeoForge::onDamaged);
        NeoForge.EVENT_BUS.addListener(LivingDeathEvent.class, NamesakeNeoForge::onDeath);
        NeoForge.EVENT_BUS.addListener(NamesakeNeoForge::onServerStopping);
        NeoForge.EVENT_BUS.addListener(ServerTickEvent.Post.class, NamesakeNeoForge::onServerTick);

        if (FMLEnvironment.dist.isClient()) {
            // The class is only touched inside this branch, so a dedicated server never loads it
            // and never has to resolve net.minecraft.client.Minecraft.
            NamesakeNeoForgeClient.register();
        }
    }

    private static void onEntityJoin(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide()) {
            return;
        }
        PersonaService.onEntityLoad(event.getEntity());
    }

    /**
     * NeoForge fires this from the conversion <i>call sites</i> ({@code Zombie#killedEntity} and
     * {@code ZombieVillager#finishConversion}), which is after the new entity has already joined
     * the level — so {@link #onEntityJoin} has already minted it a persona. {@code PersonaService}
     * reaps that stray.
     */
    private static void onConversion(LivingConversionEvent.Post event) {
        if (event.getEntity().level().isClientSide()) {
            return;
        }
        PersonaService.onConversion(event.getEntity(), event.getOutcome());
    }

    private static void onRegisterCommands(RegisterCommandsEvent event) {
        NamesakeCommands.register(event.getDispatcher());
    }

    /**
     * The conversation gesture.
     *
     * <p>{@code EntityInteract} rather than {@code EntityInteractSpecific}: both fire, but only
     * this one sits on the path to {@code Villager#mobInteract}, so listening to it alone means the
     * handler runs once per click on each side rather than twice.
     */
    private static void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        if (Interactions.isConversationGesture(event.getEntity(), event.getHand(), event.getTarget())) {
            if (event.getLevel().isClientSide()) {
                // Not cancelled on the client: the vanilla interact packet still has to reach the
                // server, or the server never learns the gesture happened.
                Interactions.onClientGesture(event.getTarget());
                return;
            }
            if (event.getEntity() instanceof ServerPlayer player) {
                Interactions.onServerGesture(player, event.getTarget());
            }
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.CONSUME);
            return;
        }
        // The same gesture with the hand full — session 05's give. Same client/server split.
        if (!SocialEvents.isGiveGesture(event.getEntity(), event.getHand(), event.getTarget())) {
            // Session 12's standing bands, on the plain right-click — the one both gestures above
            // have declined, and therefore the one on its way to Villager#mobInteract and the trade
            // screen. Deliberately not cancelled: vanilla proceeds, and its own updateSpecialPrices
            // adds the gossip reputation on top of the multiplier we just set. See Trading.
            if (!event.getLevel().isClientSide()
                    && event.getEntity() instanceof ServerPlayer player
                    && event.getTarget() instanceof Villager villager) {
                Trading.onVillagerInteract(player, villager);
            }
            return;
        }
        if (event.getLevel().isClientSide()) {
            return;
        }
        if (event.getEntity() instanceof ServerPlayer player
                && event.getTarget() instanceof Villager villager) {
            SocialEvents.onGive(player, event.getHand(), villager);
        }
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.CONSUME);
    }

    /**
     * Session 11's Notice Board — the mirror of Fabric's {@code UseBlockCallback} registration.
     *
     * <p>An empty lectern in a registered settlement, right-clicked with an empty hand. Vanilla
     * spends that gesture on nothing, so nothing vanilla does is taken away; and a lectern outside a
     * village is left entirely alone, which is session 10's rule about a player's build applied to a
     * gesture instead of to a block.
     */
    private static void onBlockInteract(PlayerInteractEvent.RightClickBlock event) {
        BlockState state = event.getLevel().getBlockState(event.getPos());
        if (!NoticeBoard.isBoardGesture(event.getEntity(), event.getHand(), state)) {
            return;
        }
        if (event.getLevel().isClientSide()) {
            // Not cancelled: the vanilla block-use packet has to reach the server, which is the only
            // side that knows whether this lectern is in a settlement.
            return;
        }
        if (event.getEntity() instanceof ServerPlayer player
                && NoticeBoard.onServerGesture(player, event.getPos()).isPresent()) {
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.CONSUME);
        }
    }

    private static void onDamaged(LivingDamageEvent.Post event) {
        if (event.getEntity().level().isClientSide()) {
            return;
        }
        SocialEvents.onHurt(event.getEntity(), event.getSource(), event.getNewDamage());
    }

    private static void onDeath(LivingDeathEvent event) {
        if (event.getEntity().level().isClientSide()) {
            return;
        }
        SocialEvents.onDeath(event.getEntity(), event.getSource());
    }

    private static void onServerStopping(ServerStoppingEvent event) {
        VerbNetwork.onServerStopping();
        SettlementRegistrar.onServerStopping();
        RoadNetwork.onServerStopping();
    }

    /**
     * Spends whatever settlement survey a villager's arrival asked for, a few chunks at a time;
     * from session 08, retells one story per settlement that has one, every 250 ticks; and from
     * session 10, routes one road off-thread and lays a bounded number of its blocks. Every callee
     * returns on its first line when there is nothing to do, which is the usual case; see
     * {@code Gossip} for why the drain is bounded by construction rather than measured, and
     * {@code RoadNetwork} for what a road will and will not replace.
     */
    private static void onServerTick(ServerTickEvent.Post event) {
        SettlementRegistrar.onServerTick(event.getServer());
        Gossip.onServerTick(event.getServer());
        RoadNetwork.onServerTick(event.getServer());
        AttachBetHarness.onServerTick(event.getServer());
        ProfilerHarness.onServerTick(event.getServer());
    }
}
