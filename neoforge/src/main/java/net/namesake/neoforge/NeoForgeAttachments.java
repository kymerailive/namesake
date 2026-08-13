package net.namesake.neoforge;

import net.minecraft.core.UUIDUtil;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;
import net.namesake.Namesake;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * NeoForge's registry-backed equivalent of Fabric's attachment type.
 *
 * <p>The default value supplier returns {@code null}, which is safe only because nothing ever calls
 * {@code getData} on this type — {@code getData} would store the null default in the holder and
 * make {@code hasData} start answering true. {@link NeoForgePersonaLink} uses
 * {@code getExistingDataOrNull} exclusively.
 *
 * <p>{@code copyOnDeath()} is deliberately not set; see {@code FabricPersonaLink} for why the two
 * loaders share one explicit conversion path instead.
 */
public final class NeoForgeAttachments {

    public static final DeferredRegister<AttachmentType<?>> ATTACHMENT_TYPES =
            DeferredRegister.create(NeoForgeRegistries.Keys.ATTACHMENT_TYPES, Namesake.MOD_ID);

    public static final Supplier<AttachmentType<UUID>> PERSONA_ID = ATTACHMENT_TYPES.register(
            "persona_id",
            () -> AttachmentType.<UUID>builder(() -> null)
                    .serialize(UUIDUtil.CODEC)
                    .build());

    private NeoForgeAttachments() {
    }

    static void register(IEventBus modBus) {
        ATTACHMENT_TYPES.register(modBus);
    }
}
