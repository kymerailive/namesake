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
 * <p>A persona is minted the moment a villager loads and <i>generated</i> a little later, once its
 * settlement is known — see {@link #isGenerated()}. Until then it has no culture, no household and
 * no traits, which is a real state a save file can hold rather than a transient one.
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

    /**
     * Sentinel for "no settlement / no household yet".
     *
     * <p>Not {@code 0}: settlement detection in session 03 hands out ids from a counter that starts
     * at zero, so zero is a legal id and cannot double as "unset". Schema 1 used zero and schema 2
     * migrates it — see {@code NpcSchema}.
     */
    public static final int UNASSIGNED = -1;

    /**
     * Sentinel for "this persona has not been generated yet".
     *
     * <p>Not {@code 0}, for exactly the reason {@link #UNASSIGNED} is not: culture ids start at
     * zero, so zero is a real culture. Schema 2 and earlier wrote {@code 0} into this field meaning
     * "none", which from session 03 on would read as <i>every villager in an existing world belongs
     * to the first culture</i> — a save that loads perfectly and is silently wrong. The schema
     * 2 → 3 fix rewrites it, and this is why that fix exists.
     *
     * <p>It also doubles as the generation marker. A generated persona always holds a real culture
     * id, so no separate "generated" flag has to be persisted and kept in step with the fields it
     * describes.
     */
    public static final byte UNASSIGNED_CULTURE = -1;

    /**
     * The high half of every persona id that exists only to be measured.
     *
     * <p><b>Why this is in {@code Persona} and not in the profiler that uses it.</b> Session 04
     * creates hundreds of fake persona records to time a sweep over them, and
     * {@link NpcRegistry} writes to disk. Four hundred fixtures left in a save are not a schema
     * break — they are worse, because they load without complaint and look exactly like people.
     * The rule "an id in this range must never be persisted" therefore belongs to the identity
     * itself, and {@code NpcRegistry} enforces it at both doors rather than trusting a harness to
     * tidy up after a run that may have crashed.
     *
     * <p>Disjoint from a real id by construction, not by luck: {@code UUID.randomUUID} sets the
     * version nibble to 4 and this constant leaves it 0, so no minted persona can ever collide
     * with the range however many are minted.
     */
    public static final long PROFILING_NAMESPACE = 0x7E57_0000_5EED_0000L;

    /** An id in the reserved range. {@code index} distinguishes one fixture from another. */
    public static UUID profilingId(long index) {
        return new UUID(PROFILING_NAMESPACE, index);
    }

    /** True for a fixture id, which must never reach a save file. */
    public static boolean isReservedForProfiling(UUID id) {
        return id.getMostSignificantBits() == PROFILING_NAMESPACE;
    }

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

    /**
     * A minted, ungenerated persona: an identity with nobody in it yet.
     *
     * <p>Generation needs the villager's settlement, and a settlement needs a survey that does not
     * finish in the tick the villager loads. So minting and generating are two steps, and this is
     * the first.
     */
    public static Persona create(UUID id, long birthTick) {
        return new Persona(
                id,
                UNASSIGNED,
                UNASSIGNED,
                new byte[TRAIT_COUNT],
                UNASSIGNED_CULTURE,
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

    /** True once this persona has a culture, a household and rolled traits. */
    public boolean isGenerated() {
        return cultureId >= 0;
    }

    /**
     * Where this persona belongs, written in one step.
     *
     * <p>Deliberately one method rather than three withers. Settlement, household and culture are
     * decided together and are only meaningful together — a persona holding a household but no
     * culture is a state nothing should be able to produce by forgetting a line. Traits are rolled
     * <i>from</i> this placement, by {@link TraitRoll#roll}, and applied with {@link #withTraits}.
     */
    public Persona placed(int newSettlementId, int newHouseholdId, byte newCultureId) {
        return new Persona(id, newSettlementId, newHouseholdId, traits,
                newCultureId, professionId, birthTick, appearanceSeed, eraOfMajority);
    }

    public Persona withTraits(byte[] rolled) {
        return new Persona(id, settlementId, householdId, rolled,
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
