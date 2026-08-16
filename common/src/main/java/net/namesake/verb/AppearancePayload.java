package net.namesake.verb;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.namesake.Namesake;

/**
 * <b>What one villager looks like, on the wire.</b> Session 15, and it is the first thing this mod
 * has ever told a client about a persona.
 *
 * <h2>Why there has to be a packet at all</h2>
 *
 * <p>The client knows <i>nothing</i> about a persona. {@code PersonaLink} carries a bare UUID and is
 * unsynced on both loaders; {@code PersonaService.personaOf} returns empty for anything that is not
 * a {@code ServerLevel}; and {@code NpcRegistry} is {@code SavedData} on the overworld, which has no
 * client instance. So a renderer on the client cannot reach a single field.
 *
 * <p>Two of the three things it needs are not derivable either. {@code appearanceSeed} is a pure
 * function of the persona id — which the client also does not have, and which is deliberately
 * <b>not</b> the entity's UUID, because that changes when a villager is zombified and cured.
 * {@code cultureId} is worse: it is sampled from the world seed at the settlement's bell when the
 * persona is placed, so it cannot be recomputed from anything a client holds.
 *
 * <h2>Why it is a push, and why it is this small</h2>
 *
 * <p>{@code DESIGN.md} §2 rules that <b>only the server ever opens an interaction and the client
 * cannot ask</b> — so a client-side "what does this villager look like" request is against a ruled
 * decision before it is against a budget. This is a push, sent when a player starts tracking a
 * villager and again if the persona is generated while they are watching.
 *
 * <p>The alternative was syncing the persona attachment, which is two loader APIs that behave
 * differently — Fabric's builder and NeoForge's are not the same shape and neither is the same
 * shape as the other loader's — in the last session of the slice. This reuses the transport seam
 * both loaders already have and sends <b>nine bytes</b> rather than the sixteen a UUID costs,
 * because the derivation runs on the server where the persona actually is.
 *
 * <h2>Hard rule 6 costs nothing here</h2>
 *
 * <p>Clientbound, like {@code NoticeBoardPayload}: there is no serverbound verb to gate because
 * there is nothing a viewer of a villager can ask the server to do. The handler goes through
 * {@code ClientScreenSink}'s sibling sink rather than naming a client class, because NeoForge
 * registers clientbound handlers on the dedicated server too.
 *
 * @param entityId      the villager's network id, which is what a client can look an entity up by
 * @param appearanceSeed {@code Persona.appearanceSeed}, derived server-side
 * @param cultureId     the persona's culture, or {@code Persona.UNASSIGNED_CULTURE} for one that has
 *                      not been generated yet — a real state a save can hold, so it is a real state
 *                      on the wire rather than an absence
 */
public record AppearancePayload(int entityId, int appearanceSeed, byte cultureId)
        implements ClientboundPayload {

    public static final CustomPacketPayload.Type<AppearancePayload> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(Namesake.MOD_ID, "appearance"));

    public static final StreamCodec<ByteBuf, AppearancePayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, AppearancePayload::entityId,
            ByteBufCodecs.INT, AppearancePayload::appearanceSeed,
            ByteBufCodecs.BYTE, AppearancePayload::cultureId,
            AppearancePayload::new);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
