package net.namesake.social;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.namesake.Namesake;
import net.namesake.npc.NpcRegistry;
import net.namesake.npc.Persona;
import net.namesake.npc.PersonaService;
import net.namesake.profile.Meter;
import net.namesake.profile.Meters;
import net.namesake.profile.Profiling;
import net.namesake.settlement.Settlement;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Steps 1 to 4 of {@code DESIGN.md} §4: emit, scan for witnesses, record, move the bonds.
 *
 * <p><b>Everything here happens on emit, and nothing here is ever polled.</b> That is the single
 * most important property of this class and the reason the whole social system costs zero per tick.
 * MCA's harvest chore scans roughly 72,000 blocks per villager per minute looking for something to
 * do; this scans one box, once, at the moment something actually happens, and then does not run
 * again until something else does. Session 04 measured our steady-state per-tick cost at
 * <i>zero calls</i>, and this session does not change that — the meters below record a burst, not a
 * budget line.
 *
 * <p>Steps 5 to 7 are deliberately absent. The settlement effect belongs to the era ladder, and the
 * gossip deque is session 08; building either early would mean building it against a bond system
 * nobody had watched work yet.
 */
public final class DeedBus {

    /** {@code DESIGN.md} §4: one {@code AABB.inflate(24)}, and it is the only spatial query here. */
    public static final int WITNESS_RADIUS = 24;

    /**
     * How many villagers may witness one deed.
     *
     * <p>A hard bound rather than a tuning knob. Without it the cost of a deed is the cost of
     * however many villagers a player has crowded into one place, which is a number a player
     * controls and can make very large on purpose.
     */
    public static final int MAX_WITNESSES = 12;

    private static final Meter SCAN = Profiling.ENABLED ? Meters.meter("DeedBus.witnessScan") : null;
    private static final Meter EMIT = Profiling.ENABLED ? Meters.meter("DeedBus.emit") : null;

    private DeedBus() {
    }

    /** What one emit did, so a caller — or a harness — can assert on it rather than infer it. */
    public record Result(Deed deed, int witnesses, int bondsMoved) {

        public static final Result NOTHING = new Result(null, 0, 0);

        public boolean happened() {
            return deed != null;
        }
    }

    /**
     * Emits a deed done by {@code actor} to {@code subject}, at the subject's feet.
     *
     * @param subject the villager it was done to. May be null for a deed with no single victim —
     *                defending a raid — in which case the deed happens where the actor is standing
     *                and everyone who saw it is a witness.
     */
    public static Result emit(ServerLevel level, DeedType type, LivingEntity actor, Villager subject) {
        Vec3 where = subject != null ? subject.position() : actor.position();
        UUID subjectId = subject != null
                ? PersonaService.personaOf(subject).map(Persona::id).orElse(actor.getUUID())
                : actor.getUUID();

        NpcRegistry registry = NpcRegistry.get(level);
        int settlementId = registry.settlements()
                .containing(level.dimension().location(), net.minecraft.core.BlockPos.containing(where))
                .map(Settlement::id)
                .orElse(Persona.UNASSIGNED);

        Deed deed = Deed.of(type, actor.getUUID(), subjectId, settlementId, Deed.dayOf(level));
        return emit(level, deed, actor, subject, where);
    }

    /**
     * The full form: a deed that has already been built, so a caller can set its severity.
     *
     * @param actorEntity what witnesses must be able to see. Not the position of the deed but the
     *                    <i>person who did it</i>: a villager who watched a blow land from behind a
     *                    wall did not see who threw it, and the whole point of the scan is who
     *                    knows what about whom.
     */
    public static Result emit(ServerLevel level, Deed deed, LivingEntity actorEntity,
                              Villager subjectEntity, Vec3 where) {
        if (Profiling.MOD_INERT) {
            // Hard rule 4's baseline: the same world with none of our code in it. See Profiling.
            return Result.NOTHING;
        }
        long begun = Meters.now();
        NpcRegistry registry = NpcRegistry.get(level);

        // The population guard for decision 1, checked once here rather than thirteen times inside
        // putBond. An NPC-to-NPC bond has no consumer until session 16's grievance engine, and a
        // persisted social value with no consumer is what DESIGN.md §1 forbids. The deed itself is
        // perfectly general and is emitted anyway — it is only the bond that is withheld.
        if (registry.persona(deed.actor()).isPresent()) {
            Namesake.LOGGER.debug("Deed {} has an NPC actor; no bond was written. NPC-to-NPC bonds "
                    + "arrive with session 16's grievance engine.", deed);
            return new Result(deed, 0, 0);
        }

        List<Villager> witnesses = witnesses(level, where, actorEntity, subjectEntity);

        int moved = 0;
        if (subjectEntity != null) {
            moved += applyTo(registry, deed, subjectEntity);
        }
        for (Villager witness : witnesses) {
            moved += applyTo(registry, deed, witness);
        }

        if (Profiling.ENABLED) {
            EMIT.end(begun);
            Meters.count("DeedBus deeds emitted");
            Meters.count("DeedBus witnesses recorded", witnesses.size());
        }
        Namesake.LOGGER.debug("{} witnessed by {}, {} bond(s) moved", deed, witnesses.size(), moved);
        return new Result(deed, witnesses.size(), moved);
    }

    /**
     * Step 2: the witness scan. One box, filtered by line of sight, capped at the twelve nearest.
     *
     * <p><b>Sorted before the rays are cast, which is the same answer for less work.</b>
     * {@code DESIGN.md} words it as "filtered by {@code canSee()}, capped at 12 nearest" — filter,
     * then take twelve. Walking the candidates nearest-first and stopping at twelve produces exactly
     * that set, and in a crowd it casts twelve rays instead of one per villager in a 48-block cube.
     *
     * <p><b>Deliberately {@code LivingEntity#hasLineOfSight} rather than {@code Sensing}.</b>
     * {@code Sensing} caches per entity id and is only cleared from the mob's own AI step, so any
     * villager that is loaded but not running its brain — every fixture in the harness, which sets
     * {@code setNoAi} — would answer from a cache populated before there was a wall in the way.
     * A cached instrument that reports yesterday's answer is exactly the class of false green this
     * project keeps finding.
     */
    static List<Villager> witnesses(ServerLevel level, Vec3 where, LivingEntity actor, Villager subject) {
        long begun = Meters.now();

        AABB box = new AABB(where, where).inflate(WITNESS_RADIUS);
        List<Villager> candidates = new ArrayList<>(level.getEntitiesOfClass(Villager.class, box,
                villager -> villager != subject && villager.isAlive()));
        candidates.sort(Comparator.comparingDouble(villager -> villager.distanceToSqr(where)));

        List<Villager> seen = new ArrayList<>(MAX_WITNESSES);
        for (Villager candidate : candidates) {
            if (seen.size() >= MAX_WITNESSES) {
                break;
            }
            if (candidate.hasLineOfSight(actor)) {
                seen.add(candidate);
            }
        }

        if (Profiling.ENABLED) {
            SCAN.end(begun);
            Meters.count("DeedBus witness candidates", candidates.size());
        }
        return seen;
    }

    /**
     * Steps 3 and 4 for one person: work out what the deed was worth to them, and move the bond.
     *
     * <p>A row is never created for nothing. A witness three blocks from a gift so small that their
     * share rounds to zero has genuinely had nothing happen to them, and writing a bond of all
     * zeroes for every villager who has ever been in the same square as a player is how a save file
     * fills up with rows that mean "no".
     */
    private static int applyTo(NpcRegistry registry, Deed deed, Villager villager) {
        Optional<Persona> holder = PersonaService.personaOf(villager);
        if (holder.isEmpty()) {
            return 0;
        }
        Persona persona = holder.get();
        int[] delta = Deeds.deltaFor(deed, persona);

        Optional<Bond> existing = registry.bonds().stored(persona.id(), deed.actor());
        Bond before = existing.orElseGet(() -> Bond.fresh(deed.gameDay()));
        Bond after = before.apply(delta, deed.gameDay());

        if (after.isNothing() && existing.isEmpty()) {
            return 0;
        }
        if (after.equals(before)) {
            return 0;
        }
        registry.putBond(persona.id(), deed.actor(), after);
        return 1;
    }

    /** Entity → villager, for the loader hooks, which see {@code Entity} and nothing narrower. */
    public static Villager asVillager(Entity entity) {
        return entity instanceof Villager villager ? villager : null;
    }
}
