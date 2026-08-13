package net.namesake.settlement;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Objects;

/**
 * One detected settlement: a bell, and everything the one-shot survey concluded about the place
 * around it.
 *
 * <p>Detected, never founded — {@code DESIGN.md} rules settlements out of vanilla POI clusters, and
 * a player's own base registers on exactly the same terms as anywhere else if villagers actually
 * live there.
 *
 * <p><b>Six fields, and every one of them is read by an {@code if}.</b> {@code dimension} and
 * {@code centre} decide whether a villager belongs to this settlement or a different one;
 * {@code specialty}, {@code defensibility} and {@code needs} decide what the settlement's people
 * are like, in {@code TraitRoll.settlementMean}. {@code id} identifies the record.
 *
 * <p><b>Culture is deliberately absent.</b> It was a field here until rule 5 was applied honestly:
 * a settlement's culture is a pure function of the world seed and the biome at its centre, both of
 * which are stable for the life of a world, so storing it is caching — and a cache with no
 * behaviour of its own is a persisted value with no consumer. {@link net.namesake.culture.Cultures}
 * derives it on demand instead. A persona still stores its own culture id, because a person's
 * culture is where they were born and not where they currently live.
 */
public record Settlement(
        int id,
        ResourceLocation dimension,
        BlockPos centre,
        byte specialty,
        byte defensibility,
        byte[] needs) {

    /** Same trick as {@code Persona.traits} — a fixed-width byte vector in a compound tag. */
    private static final Codec<byte[]> NEEDS_CODEC = Codec.BYTE_BUFFER.xmap(
            buffer -> {
                byte[] copy = new byte[buffer.remaining()];
                buffer.duplicate().get(copy);
                return copy;
            },
            ByteBuffer::wrap);

    public static final Codec<Settlement> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.INT.fieldOf("id").forGetter(Settlement::id),
            ResourceLocation.CODEC.fieldOf("dimension").forGetter(Settlement::dimension),
            BlockPos.CODEC.fieldOf("centre").forGetter(Settlement::centre),
            Codec.BYTE.fieldOf("specialty").forGetter(Settlement::specialty),
            Codec.BYTE.fieldOf("defensibility").forGetter(Settlement::defensibility),
            // Reads the field directly rather than through the accessor, which copies.
            NEEDS_CODEC.fieldOf("needs").forGetter(settlement -> settlement.needs)
    ).apply(instance, Settlement::new));

    public Settlement {
        Objects.requireNonNull(dimension, "settlement dimension");
        Objects.requireNonNull(centre, "settlement centre");
        Objects.requireNonNull(needs, "settlement needs");
        if (needs.length != Need.COUNT) {
            throw new IllegalArgumentException(
                    "A settlement needs exactly " + Need.COUNT + " need axes, got " + needs.length);
        }
        needs = needs.clone();
    }

    /** Defensive copy — a caller must not be able to rewrite a settlement's needs in place. */
    @Override
    public byte[] needs() {
        return needs.clone();
    }

    public byte need(Need need) {
        return needs[need.index()];
    }

    public Specialty specialtyValue() {
        return Specialty.byId(specialty);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Settlement settlement
                && id == settlement.id
                && dimension.equals(settlement.dimension)
                && centre.equals(settlement.centre)
                && specialty == settlement.specialty
                && defensibility == settlement.defensibility
                && Arrays.equals(needs, settlement.needs);
    }

    @Override
    public int hashCode() {
        return 31 * Objects.hash(id, dimension, centre, specialty, defensibility)
                + Arrays.hashCode(needs);
    }

    @Override
    public String toString() {
        return "Settlement[" + id
                + " " + dimension
                + " centre=" + centre.toShortString()
                + " specialty=" + specialtyValue()
                + " defensibility=" + defensibility
                + " needs=" + Arrays.toString(needs) + ']';
    }
}
