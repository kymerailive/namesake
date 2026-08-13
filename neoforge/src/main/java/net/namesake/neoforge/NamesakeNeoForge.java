package net.namesake.neoforge;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.namesake.Namesake;
import net.namesake.command.NamesakeCommands;
import net.namesake.harness.AttachBetHarness;
import net.namesake.npc.PersonaService;
import net.namesake.platform.VerbTransport;
import net.namesake.verb.Interactions;
import net.namesake.verb.VerbNetwork;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.living.LivingConversionEvent;
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
        NeoForge.EVENT_BUS.addListener(NamesakeNeoForge::onServerStopping);

        if (FMLEnvironment.dist.isClient()) {
            // The class is only touched inside this branch, so a dedicated server never loads it
            // and never has to resolve net.minecraft.client.Minecraft.
            NamesakeNeoForgeClient.register();
        }

        if (AttachBetHarness.enabled()) {
            NeoForge.EVENT_BUS.addListener(ServerTickEvent.Post.class, NamesakeNeoForge::onServerTick);
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
        if (!Interactions.isConversationGesture(event.getEntity(), event.getHand(), event.getTarget())) {
            return;
        }
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
    }

    private static void onServerStopping(ServerStoppingEvent event) {
        VerbNetwork.onServerStopping();
    }

    private static void onServerTick(ServerTickEvent.Post event) {
        AttachBetHarness.onServerTick(event.getServer());
    }
}
