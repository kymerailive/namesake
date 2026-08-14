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
 * <p><b>Step 3 arrived in session 06 and is the reason an emit now writes to the save file.</b> Each
 * witness appends the deed to their ring at {@link Deed#FIRST_HAND}, and so does the subject. What
 * the design calls <i>the subject records it weighted higher</i> needs no extra field and no second
 * copy: a deed already carries {@link Deed#subject()}, so anyone reading a ring can tell whether it
 * happened to them by comparing their own persona id against it — which is exactly what
 * {@link Deeds#deltaFor} does one step later to give the subject the whole share and a bystander a
 * fraction. Storing the weighting would be storing an answer the struct already contains, and
 * session 05 already found the version of that mistake worth avoiding: a deed that has to be
 * <i>told</i> who it happened to is a deed that can be told wrong.
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

    /**
     * What one emit did, so a caller — or a harness — can assert on it rather than infer it.
     *
     * <p>{@code remembered} and {@code bondsMoved} are separate counts because they are separate
     * questions, and after session 06 they genuinely diverge: the tenth loaf of the day moves no bond
     * (the allowance is spent) and adds no memory (it is the same deed as the first), while a deed
     * whose share rounds to nothing for a distant witness is still remembered by them.
     */
    public record Result(Deed deed, int witnesses, int remembered, int bondsMoved) {

        public static final Result NOTHING = new Result(null, 0, 0, 0);

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
        if (Profiling.MOD_INERT) {
            // Checked here as well as in the full form below: hard rule 4's baseline is "the same
            // world with none of our code in it", and reaching the registry to work out where the
            // deed happened is our code running.
            return Result.NOTHING;
        }
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

        List<Villager> witnesses = witnesses(level, where, actorEntity, subjectEntity);

        // Everyone this deed reached, subject first. One list rather than two loops, because steps 3
        // and 4 ask the same question of the same people and splitting them would look up every
        // persona twice.
        List<Persona> reached = new ArrayList<>(witnesses.size() + 1);
        if (subjectEntity != null) {
            PersonaService.personaOf(subjectEntity).ifPresent(reached::add);
        }
        for (Villager villager : witnesses) {
            PersonaService.personaOf(villager).ifPresent(reached::add);
        }

        Result result = record(registry, deed, reached, witnesses.size());

        if (Profiling.ENABLED) {
            EMIT.end(begun);
            Meters.count("DeedBus deeds emitted");
            Meters.count("DeedBus witnesses recorded", witnesses.size());
            Meters.count("DeedBus ring entries written", result.remembered());
        }
        Namesake.LOGGER.debug("{} witnessed by {}, remembered by {}, {} bond(s) moved",
                deed, witnesses.size(), result.remembered(), result.bondsMoved());
        return result;
    }

    /**
     * Steps 3 and 4 for a deed whose audience is already decided: remember it, then move the bonds.
     *
     * <p><b>This is the seam session 07's headless simulation runs through, and pulling it out is
     * the single decision that makes that simulation evidence rather than arithmetic.</b> Everything
     * above this line is the engine — an entity query over a 48-block cube and a dozen ray casts.
     * Everything below it is the record layer that {@code DESIGN.md} §8 rules the authority, and it
     * needs no level, no entities and no server. So the simulation replaces exactly two things — who
     * was standing close enough to see it, and what the player chose to do — and calls the shipped
     * pipeline for the rest, through the same door {@link #emit} uses rather than through a copy of
     * it. A number produced by re-implementing this loop in a test would be a test of the test.
     *
     * @param reached everyone the deed reached, <b>subject first</b>. May be empty.
     * @param witnesses how many of {@code reached} are bystanders rather than the subject. Reported
     *                  back rather than derived, because "the subject was not a persona" and "there
     *                  was no subject" are different and neither is a witness count.
     */
    public static Result record(NpcRegistry registry, Deed deed, List<Persona> reached, int witnesses) {
        // The population guard for session 05's decision 1, checked once here rather than thirteen
        // times inside putBond. An NPC-to-NPC bond has no consumer until session 16's grievance
        // engine, and a persisted social value with no consumer is what DESIGN.md §1 forbids. The
        // deed itself is perfectly general and is built anyway — it is the storing that is withheld,
        // the ring along with the bond, because a ring of deeds nothing reads is the same forbidden
        // shape as a bond nothing reads. Session 16 deletes this branch and changes no schema.
        if (registry.persona(deed.actor()).isPresent()) {
            Namesake.LOGGER.debug("Deed {} has an NPC actor; nothing was recorded and no bond was "
                    + "written. NPC-to-NPC deeds arrive with session 16's grievance engine.", deed);
            return new Result(deed, 0, 0, 0);
        }

        int remembered = 0;
        int moved = 0;
        for (Persona persona : reached) {
            // Step 3 before step 4, and unconditionally. The bond is the tally of how much and the
            // ring is the record of what, so a witness whose daily allowance is already spent — or
            // whose share rounds to nothing — has still *seen* it. Coupling the two would mean a
            // deed type that moves no axis leaves no memory either.
            if (registry.remember(persona.id(), deed)) {
                remembered++;
            }
            moved += applyTo(registry, deed, persona);
        }
        return new Result(deed, witnesses, remembered, moved);
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
     * Step 4 for one person: work out what the deed was worth to them, and move the bond.
     *
     * <p>A row is never created for nothing. A witness three blocks from a gift so small that their
     * share rounds to zero has genuinely had nothing happen to them, and writing a bond of all
     * zeroes for every villager who has ever been in the same square as a player is how a save file
     * fills up with rows that mean "no". <b>The ring is deliberately not held to that rule</b> — see
     * the loop above — because "nothing moved" and "nothing happened" are different claims, and only
     * one of them is what a memory records.
     */
    private static int applyTo(NpcRegistry registry, Deed deed, Persona persona) {
        int[] delta = Deeds.deltaFor(deed, persona);

        Optional<Bond> existing = registry.bonds().stored(persona.id(), deed.actor());
        Bond before = existing.orElseGet(() -> Bond.fresh(deed.gameDay()));
        // The ceiling, not the step: a receptive villager's day is worth more than a closed one's.
        // See Personality.allowance for why that is where personality had to move to.
        Bond after = before.apply(delta, deed.gameDay(), Personality.allowance(persona));

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
