package net.namesake.neoforge;

import net.namesake.Namesake;
import net.namesake.command.NamesakeCommands;
import net.namesake.harness.AttachBetHarness;
import net.namesake.npc.PersonaService;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.living.LivingConversionEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** NeoForge bootstrap. Mirror of {@code NamesakeFabric}, using NeoForge's equivalents. */
@Mod(Namesake.MOD_ID)
public final class NamesakeNeoForge {

    public NamesakeNeoForge(IEventBus modBus) {
        // Before Namesake.init(), which forces the PersonaLink implementation to load: the
        // DeferredRegister has to be on the mod bus before the registry event fires.
        NeoForgeAttachments.register(modBus);

        Namesake.init();

        // PersistentEntitySectionManager#addEntity fires this for chunk loads as well as fresh
        // spawns, so it covers the same ground as Fabric's ENTITY_LOAD.
        NeoForge.EVENT_BUS.addListener(NamesakeNeoForge::onEntityJoin);
        NeoForge.EVENT_BUS.addListener(NamesakeNeoForge::onConversion);
        NeoForge.EVENT_BUS.addListener(NamesakeNeoForge::onRegisterCommands);

        if (AttachBetHarness.enabled()) {
            NeoForge.EVENT_BUS.addListener(ServerTickEvent.Post.class, NamesakeNeoForge::onServerTick);
            if (FMLEnvironment.dist.isClient()) {
                // The class is only touched inside this branch, so a dedicated server never loads
                // it and never has to resolve net.minecraft.client.Minecraft.
                NamesakeNeoForgeClient.register();
            }
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

    private static void onServerTick(ServerTickEvent.Post event) {
        AttachBetHarness.onServerTick(event.getServer());
    }
}
