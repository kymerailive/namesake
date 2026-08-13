package net.namesake.npc;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.UUIDUtil;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

/**
 * The identity of one NPC. {@code DESIGN.md} §3.
 *
 * <p><b>The record is the authority, not the entity.</b> A {@code Villager} entity is a temporary,
 * expensive rendering of a persona; the persona outlives it. That is why {@link #id} is generated
 * here and never derived from {@code Entity#getUUID()} — the entity UUID changes when a villager is
 * zombified and cured, and a persona must survive that.
 *
 * <p>Generation logic (traits, culture, household clustering) is session 03. Everything created
 * today comes out of {@link #create(UUID, long)} with zeroed traits.
 *
 * <p><b>On {@code byte[] traits} inside a record:</b> arrays have identity equality, so the
 * generated {@code equals}/{@code hashCode} would compare two personas with identical traits as
 * different. Since "same field values after a reload" is an exit criterion this session, both are
 * overridden below and the array is defensively copied on the way in and out.
 */
public record Persona(
        UUID id,
        int settlementId,
        int householdId,
        byte[] traits,
        byte cultureId,
        int professionId,
        long birthTick,
        int appearanceSeed,
        byte eraOfMajority) {

    /** Eight signed axes, each -100..100. */
    public static final int TRAIT_COUNT = 8;

    public static final int WARMTH = 0;
    public static final int INDUSTRY = 1;
    public static final int BOLDNESS = 2;
    public static final int CURIOSITY = 3;
    public static final int TRADITION = 4;
    public static final int ACQUISITIVENESS = 5;
    public static final int TEMPER = 6;
    public static final int SOCIABILITY = 7;

    /** Index-aligned with the axis constants above. Used by {@code /namesake debug}. */
    public static final String[] TRAIT_NAMES = {
            "warmth", "industry", "boldness", "curiosity",
            "tradition", "acquisitiveness", "temper", "sociability"
    };

    /** Sentinel for "no settlement / no household yet". Settlement detection is session 03. */
    public static final int UNASSIGNED = 0;

    private static final Codec<byte[]> TRAITS_CODEC = Codec.BYTE_BUFFER.xmap(
            buffer -> {
                byte[] copy = new byte[buffer.remaining()];
                buffer.duplicate().get(copy);
                return copy;
            },
            ByteBuffer::wrap);

    public static final Codec<Persona> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            UUIDUtil.CODEC.fieldOf("id").forGetter(Persona::id),
            Codec.INT.fieldOf("settlement").forGetter(Persona::settlementId),
            Codec.INT.fieldOf("household").forGetter(Persona::householdId),
            // Reads the field directly rather than through the accessor, which copies.
            TRAITS_CODEC.fieldOf("traits").forGetter(persona -> persona.traits),
            Codec.BYTE.fieldOf("culture").forGetter(Persona::cultureId),
            Codec.INT.fieldOf("profession").forGetter(Persona::professionId),
            Codec.LONG.fieldOf("birthTick").forGetter(Persona::birthTick),
            Codec.INT.fieldOf("appearanceSeed").forGetter(Persona::appearanceSeed),
            Codec.BYTE.fieldOf("era").forGetter(Persona::eraOfMajority)
    ).apply(instance, Persona::new));

    public Persona {
        Objects.requireNonNull(id, "persona id");
        Objects.requireNonNull(traits, "persona traits");
        if (traits.length != TRAIT_COUNT) {
            throw new IllegalArgumentException(
                    "Persona needs exactly " + TRAIT_COUNT + " trait axes, got " + traits.length);
        }
        traits = traits.clone();
    }

    /** A blank persona. Traits are zeroed; session 03 rolls them for real. */
    public static Persona create(UUID id, long birthTick) {
        return new Persona(
                id,
                UNASSIGNED,
                UNASSIGNED,
                new byte[TRAIT_COUNT],
                (byte) 0,
                0,
                birthTick,
                deriveAppearanceSeed(id),
                (byte) 0);
    }

    /**
     * Appearance is a pure function of the persona id, so nothing about a face has to be stored
     * (`DESIGN.md` §9). The seed is still persisted: if this derivation is ever changed, existing
     * villagers must keep the faces players already know.
     */
    public static int deriveAppearanceSeed(UUID id) {
        long mixed = id.getMostSignificantBits() * 0x9E3779B97F4A7C15L ^ id.getLeastSignificantBits();
        return (int) (mixed ^ (mixed >>> 32));
    }

    /** Defensive copy — callers must not be able to mutate a persona's traits in place. */
    @Override
    public byte[] traits() {
        return traits.clone();
    }

    public byte trait(int axis) {
        return traits[axis];
    }

    public Persona withTrait(int axis, byte value) {
        byte[] updated = traits.clone();
        updated[axis] = value;
        return new Persona(id, settlementId, householdId, updated,
                cultureId, professionId, birthTick, appearanceSeed, eraOfMajority);
    }

    public Persona withSettlement(int newSettlementId) {
        return new Persona(id, newSettlementId, householdId, traits,
                cultureId, professionId, birthTick, appearanceSeed, eraOfMajority);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Persona persona
                && id.equals(persona.id)
                && settlementId == persona.settlementId
                && householdId == persona.householdId
                && Arrays.equals(traits, persona.traits)
                && cultureId == persona.cultureId
                && professionId == persona.professionId
                && birthTick == persona.birthTick
                && appearanceSeed == persona.appearanceSeed
                && eraOfMajority == persona.eraOfMajority;
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(id, settlementId, householdId,
                cultureId, professionId, birthTick, appearanceSeed, eraOfMajority);
        return 31 * result + Arrays.hashCode(traits);
    }

    @Override
    public String toString() {
        return "Persona[" + id
                + " settlement=" + settlementId
                + " household=" + householdId
                + " traits=" + Arrays.toString(traits)
                + " culture=" + cultureId
                + " profession=" + professionId
                + " birthTick=" + birthTick
                + " appearanceSeed=" + appearanceSeed
                + " era=" + eraOfMajority + ']';
    }
}
