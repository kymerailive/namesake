package net.namesake.npc;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.phys.AABB;
import net.namesake.Namesake;
import net.namesake.culture.Cultures;
import net.namesake.culture.Names;
import net.namesake.platform.PersonaLink;
import net.namesake.settlement.Settlement;
import net.namesake.settlement.SettlementRegistrar;
import net.namesake.settlement.SettlementSurvey;

import java.util.Optional;

/**
 * Turning a minted persona into a person: where they are from, whose house they are in, and who
 * they take after.
 *
 * <p><b>Minting and generating are two steps, and a save file can hold the gap between them.</b> A
 * persona is minted the instant a villager loads, because session 05's witnesses need identity to
 * already exist. But generation needs to know the villager's settlement, and finding one means a
 * POI survey that does not finish inside a tick. So a persona spends a little while — occasionally
 * across a save — as an identity with nobody in it, and {@link Persona#isGenerated()} says so
 * honestly rather than a half-filled record pretending otherwise.
 *
 * <p><b>A generated persona is never regenerated.</b> Culture and traits are who someone is; a
 * villager who later walks into a different village is an immigrant, not a different person. Only
 * {@code settlementId} is allowed to change afterwards, and only from "nowhere" to "here".
 */
public final class Personas {

    /**
     * How wide a household is, in blocks.
     *
     * <p>Households are spatial rather than stored: a villager belongs to the cell of the world its
     * persona was generated in, measured from the settlement's bell so the grid is aligned to the
     * village rather than to the world origin. Sixteen blocks covers a vanilla village house and
     * usually its neighbour, which is what puts two or three people in a family.
     *
     * <p>The alternative — clustering beds into households at registration — costs a pass over
     * every bed and a persisted household table, and buys a tidier answer to a question no
     * mechanic asks yet. This is the cheap version, and it is entirely derived, so nothing can
     * drift out of step with it.
     */
    public static final int HOUSEHOLD_CELL = 16;

    private Personas() {
    }

    /**
     * Called for every persona whose villager has just entered the world.
     *
     * <p>Three cases: already a person and settled — nothing to do; already a person but
     * unsettled — record it if they are standing in a settlement now; not yet a person — generate
     * them, which may have to wait for a survey.
     */
    public static void onPersonaLoaded(ServerLevel level, NpcRegistry registry, Persona persona,
                                       BlockPos pos) {
        if (persona.isGenerated()) {
            if (persona.settlementId() == Persona.UNASSIGNED) {
                settlementAt(registry, level, pos).ifPresent(settlement -> {
                    registry.put(persona.withSettlement(settlement.id()));
                    Namesake.LOGGER.debug("Persona {} moved into settlement {}",
                            persona.id(), settlement.id());
                });
            }
            return;
        }

        Optional<Settlement> home = settlementAt(registry, level, pos);
        if (home.isPresent()) {
            generate(level, registry, persona, pos);
            return;
        }
        if (!SettlementRegistrar.request(level, pos)) {
            // This area has already been surveyed and has no bell in it. They are nobody's
            // resident, which is a real answer — not a reason to leave them blank.
            generate(level, registry, persona, pos);
        }
    }

    /**
     * Generates one persona in place: culture from where they are, household from the cell they
     * stand in, traits from the three-layer roll.
     *
     * @return the generated persona, already written back to the registry
     */
    public static Persona generate(ServerLevel level, NpcRegistry registry, Persona persona,
                                   BlockPos pos) {
        Optional<Settlement> home = settlementAt(registry, level, pos);
        // Culture is a property of the place, so it is sampled at the settlement's bell rather
        // than at the villager's feet — otherwise two residents on opposite sides of a culture
        // border would grow up in different cultures in the same village.
        byte cultureId = Cultures.at(level, home.map(Settlement::centre).orElse(pos)).id();
        int settlementId = home.map(Settlement::id).orElse(Persona.UNASSIGNED);

        // The household grid is anchored at the bell so it is aligned to the village. With no
        // village there is no bell to anchor to, and anchoring it at the villager's own feet would
        // put every unsettled villager in the world in cell (0,0) — one household, one surname,
        // shared by strangers a thousand blocks apart. The world origin is the only stable anchor
        // left, and it gives the wilderness real spatial households too.
        BlockPos anchor = home.map(Settlement::centre).orElse(BlockPos.ZERO);
        int householdId = householdAt(settlementId, anchor, pos);

        Persona placed = persona.placed(settlementId, householdId, cultureId);
        Persona generated = placed.withTraits(TraitRoll.roll(placed, registry.settlements()));
        registry.put(generated);

        Namesake.LOGGER.debug("Generated {} ({}) in settlement {} household {}",
                Names.of(generated).full(), generated.cultureId(), settlementId, householdId);
        return generated;
    }

    /**
     * Generates every loaded villager near {@code origin} that is still waiting on one.
     *
     * <p>Called when a survey finishes, either because a settlement registered or because the area
     * turned out to have no bell. A villager beyond this sweep is not lost — it generates the next
     * time it loads, by which point the answer is already known and costs nothing.
     */
    public static int generateLoadedNear(ServerLevel level, BlockPos origin) {
        NpcRegistry registry = NpcRegistry.get(level);
        AABB box = new AABB(origin).inflate(SettlementSurvey.SURVEY_RADIUS);
        int generated = 0;

        for (Villager villager : level.getEntitiesOfClass(Villager.class, box)) {
            Optional<Persona> persona = PersonaLink.get().personaId(villager)
                    .flatMap(registry::persona)
                    .filter(candidate -> !candidate.isGenerated());
            if (persona.isPresent()) {
                generate(level, registry, persona.get(), villager.blockPosition());
                generated++;
            }
        }
        return generated;
    }

    /**
     * Which household a position falls in.
     *
     * <p>Folds the settlement id in, so cell {@code (0,0)} of two different villages is two
     * different families. Shifted right by 33 rather than masked, which keeps the result
     * non-negative and therefore never collides with {@link Persona#UNASSIGNED}.
     */
    public static int householdAt(int settlementId, BlockPos origin, BlockPos pos) {
        int cellX = Math.floorDiv(pos.getX() - origin.getX(), HOUSEHOLD_CELL);
        int cellZ = Math.floorDiv(pos.getZ() - origin.getZ(), HOUSEHOLD_CELL);

        long z = (long) settlementId * 0x9E3779B97F4A7C15L
                ^ (long) cellX * 0xC2B2AE3D27D4EB4FL
                ^ (long) cellZ * 0x165667B19E3779F9L;
        z += 0x9E3779B97F4A7C15L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        z ^= z >>> 31;
        return (int) (z >>> 33);
    }

    private static Optional<Settlement> settlementAt(NpcRegistry registry, ServerLevel level,
                                                     BlockPos pos) {
        return registry.settlements().containing(level.dimension().location(), pos);
    }
}
