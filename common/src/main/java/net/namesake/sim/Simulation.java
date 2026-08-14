package net.namesake.sim;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.namesake.culture.Culture;
import net.namesake.npc.NpcRegistry;
import net.namesake.npc.Persona;
import net.namesake.npc.TraitRoll;
import net.namesake.settlement.Settlement;
import net.namesake.social.Bond;
import net.namesake.social.Deed;
import net.namesake.social.DeedBus;
import net.namesake.social.DeedType;
import net.namesake.social.Gossip;
import net.namesake.social.Personality;
import net.namesake.social.SocialEvents;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * <b>A settlement, run forward N in-game days, without a world.</b> Session 07's instrument.
 *
 * <h2>What this is simulating, which is the decision the session opened on</h2>
 *
 * <p>A hundred in-game days is 2.4 million ticks. Session 01 established that a long
 * {@code /tick sprint} outruns the chunk loader, so a real world cannot be sprinted through it, and
 * {@code runServer} needs an EULA that is not ours to accept. So the question is not "how fast can
 * we tick" — it is <b>what is actually being simulated, and what is the number therefore evidence
 * of?</b>
 *
 * <p>The honest answer turns on a property of the record layer rather than on a compromise:
 * <b>the record layer's only input from the passage of time is an integer.</b> {@code Deed.dayOf}
 * divides game time by 24,000; {@code Bond.decayedTo} takes a day; {@code Bond.apply} resets the
 * allowance when the day turns. Nothing downstream of a deed ever sees a tick. So running a hundred
 * days here is not an approximation of a hundred days — for everything {@code DESIGN.md} §8 rules
 * the authority, it <i>is</i> a hundred days, exactly, with no fidelity lost at all.
 *
 * <p>What is genuinely modelled is two things, and only two:
 *
 * <ol>
 *   <li><b>What the player does</b> — {@link PlayerModel}, five rows rather than one, because a
 *       single number under a single unstated model is precisely how LNK set skill gates at 35–205
 *       against an observed maximum of 32.</li>
 *   <li><b>Who was close enough to see it</b> — {@link Plan#witnessFraction}, which stands in for
 *       {@code DeedBus.witnesses}: one {@code AABB.inflate(24)} and a dozen ray casts. This is the
 *       least grounded input in the whole instrument, so the report sweeps it rather than asserting
 *       it.</li>
 * </ol>
 *
 * <p><b>Everything else is the shipped code, called through its shipped door.</b> The population is
 * built by the real {@link TraitRoll} against a real {@link Settlement}; every deed goes through
 * {@link DeedBus#record}, which is the same method {@code DeedBus.emit} calls the instant it has
 * finished asking the level who was watching. Nothing here re-implements {@code Deeds.deltaFor},
 * {@code Personality}, {@code Bond.apply} or {@code Memories} — a report built on a second copy of
 * that arithmetic would be a test of the copy.
 *
 * <p><b>So the earn rate is evidence of what the record layer does with a stated stream of deeds.
 * It is not evidence of how many deeds an hour of play produces</b> — that is the model's input, it
 * is an assumption, and session 15's playtest is what measures it. {@code /namesake debug earnrate}
 * computes the same numbers off a real save precisely so the two can be held against each other.
 *
 * <h2>What this deliberately does not touch</h2>
 *
 * <p>Its {@link NpcRegistry} is constructed here with {@code new} and never handed to a
 * {@code DimensionDataStorage}, so it has no path to disk at all — a stronger guarantee than session
 * 04's fixture-id guard, which had to exist because the profiler's records <i>were</i> offered to
 * the world's registry. Nothing in this package takes a {@code MinecraftServer} or a
 * {@code ServerLevel}.
 */
public final class Simulation {

    /** Session 04 measured seven real generated villages at a median of nine residents. */
    public static final int TYPICAL_RESIDENTS = 9;

    /**
     * What share of a settlement sees a deed, before {@code DeedBus.MAX_WITNESSES} caps it.
     *
     * <p><b>The least grounded number in this file, and it is labelled rather than buried.</b> The
     * real answer is one {@code AABB.inflate(24)} filtered by {@code canSee()} over however a
     * village is laid out, which is a property of terrain rather than of arithmetic. Session 05's
     * harness saw three of five candidates witness a deed on a flat fixture, which is not a village.
     * So this is a plausible middle, and {@link Reports#witnessSensitivity} sweeps it from nobody to
     * everybody so a threshold set from this report can be read against the range rather than
     * against the guess.
     */
    public static final float TYPICAL_WITNESS_FRACTION = 0.35F;

    /**
     * How many residents a player concentrates on.
     *
     * <p>Not invented: {@code DESIGN.md} §5 grants residency on a <i>known</i> band with <b>three</b>
     * residents, so three is the number of relationships the design itself says a player has to
     * build. Everyone else in the village rises by witnessing, which is what produces a distribution
     * rather than three numbers.
     */
    public static final int TYPICAL_FOCUS = 3;

    private static final ResourceLocation OVERWORLD = ResourceLocation.withDefaultNamespace("overworld");

    /** The actor. A UUID no persona can hold, so {@code DeedBus.record}'s NPC guard never fires. */
    private static final UUID PLAYER = new UUID(0x5EAF_0000_0000_0001L, 0x9E37_79B9_7F4A_7C15L);

    private Simulation() {
    }

    // --- inputs ------------------------------------------------------------------------------------

    /**
     * Every input, in one record, so a report can print the conditions next to the number.
     *
     * <p>Session 04's rule, applied to a different instrument: <i>a number without its conditions is
     * the same mistake in a different unit</i>.
     */
    public record Plan(
            long seed,
            int days,
            int residents,
            int households,
            byte cultureId,
            byte specialty,
            byte defensibility,
            PlayerModel model,
            int focus,
            float witnessFraction,
            boolean gossip) {

        public Plan {
            if (days < 1) {
                throw new IllegalArgumentException("A simulation of " + days + " days says nothing");
            }
            if (residents < 1) {
                throw new IllegalArgumentException("A settlement of " + residents + " people is not one");
            }
            if (witnessFraction < 0F || witnessFraction > 1F) {
                throw new IllegalArgumentException("A witness fraction of " + witnessFraction
                        + " is not a fraction");
            }
        }

        /** The measured defaults: a nine-resident farming village, the population session 04 found. */
        public static Plan standard(long seed, int days, PlayerModel model) {
            return new Plan(seed, days, TYPICAL_RESIDENTS, 3, Culture.VALE.id(),
                    net.namesake.settlement.Specialty.FARMING.id(), (byte) 70,
                    model, TYPICAL_FOCUS, TYPICAL_WITNESS_FRACTION, true);
        }

        public Plan with(PlayerModel other) {
            return new Plan(seed, days, residents, households, cultureId, specialty, defensibility,
                    other, focus, witnessFraction, gossip);
        }

        public Plan withWitnessFraction(float fraction) {
            return new Plan(seed, days, residents, households, cultureId, specialty, defensibility,
                    model, focus, fraction, gossip);
        }

        public Plan withCulture(byte culture) {
            return new Plan(seed, days, residents, households, culture, specialty, defensibility,
                    model, focus, witnessFraction, gossip);
        }

        public Plan withDays(int otherDays) {
            return new Plan(seed, otherDays, residents, households, cultureId, specialty,
                    defensibility, model, focus, witnessFraction, gossip);
        }

        /**
         * The same village with step 7 of the pipeline switched off.
         *
         * <p><b>A switch rather than a second instrument</b>, because the question it exists to
         * answer is a difference: session 07 measured that no three residents ever reach 20 warmth
         * in a hundred days, and gossip is plausibly the fix — more villagers holding a deed is more
         * villagers with contact days, and contact days are what stop the decay eating what was
         * earned. Running the same plan both ways and putting the residency tables side by side is
         * the whole experiment, and it costs one column.
         */
        public Plan withGossip(boolean spreading) {
            return new Plan(seed, days, residents, households, cultureId, specialty, defensibility,
                    model, focus, witnessFraction, spreading);
        }
    }

    // --- outputs -----------------------------------------------------------------------------------

    /**
     * One deed, as it landed. The chronicle is a list of these and nothing else.
     *
     * @param reached everyone it touched, subject first — kept rather than counted, because
     *                <b>"how many days did this villager have contact on" cannot be recovered from
     *                their ring</b> once the ring has started evicting, and the difference between
     *                the true answer and the ring's answer is one of the things this session is
     *                supposed to hand over.
     */
    public record Moment(int day, long deedId, long blurredId, DeedType type, UUID subject,
                         List<UUID> reached, int witnesses, int remembered, int bondsMoved) {

        public Moment {
            reached = List.copyOf(reached);
        }
    }

    /**
     * One resident's whole history, kept outside the ring.
     *
     * <p><b>The ring is thirty-two entries and a hundred days is more than thirty-two days, so a
     * villager's own memory cannot answer "how fast did this rise".</b> That is a real finding rather
     * than an inconvenience — it is exactly what {@code /namesake debug earnrate} runs into on a live
     * save — so the simulation keeps the truth here and the report prints both, with the difference
     * between them named.
     */
    public record History(UUID personaId, int[] warmthByDay, int[] trustByDay, int contactDays,
                          int firstContactDay, int peakWarmth, int peakDay) {
    }

    /**
     * How far one story got, day by day. <b>The exit criterion, as a data structure.</b>
     *
     * <p>Keyed on the <i>attributed</i> deed id, because a blurred copy is a different deed by
     * construction — the actor is part of the derivation — and the question being asked is about the
     * event rather than about the account of it. So {@link #unattributed} is a subset of
     * {@link #holders}: everybody who holds this story, and how many of them could not tell you who
     * did it.
     *
     * @param holders      how many residents held this story at the close of each day
     * @param unattributed how many of them held it with no name attached
     */
    public record Spread(long deedId, long blurredId, DeedType type, int emittedOnDay,
                         int[] holders, int[] unattributed) {

        /** What share of the settlement held it by the close of {@code day}. */
        public float coverage(int day, int residents) {
            return residents == 0 ? 0F : (float) holders[day] / residents;
        }
    }

    /** Everything one run produced. */
    public record Outcome(Plan plan, NpcRegistry registry, List<Persona> residents,
                          Settlement settlement, List<Moment> chronicle,
                          Map<UUID, History> histories, List<Spread> spread, long elapsedNanos) {

        /** The ledger's exit criterion is written in seconds; a run is measured in microseconds. */
        public long elapsedMillis() {
            return elapsedNanos / 1_000_000L;
        }

        public String elapsed() {
            return net.namesake.profile.Histogram.micros(elapsedNanos);
        }

        public Persona resident(UUID personaId) {
            return residents.stream().filter(p -> p.id().equals(personaId)).findFirst().orElseThrow();
        }

        /** The actor every deed in this run was done by. */
        public UUID player() {
            return PLAYER;
        }
    }

    // --- the run -----------------------------------------------------------------------------------

    /**
     * Builds the settlement and runs it forward.
     *
     * <p>Deterministic in the plan alone: same plan, same numbers, on any machine. A report that
     * cannot be reproduced is not evidence, and every threshold session 12 sets comes out of one.
     */
    public static Outcome run(Plan plan) {
        long begun = System.nanoTime();

        NpcRegistry registry = new NpcRegistry();
        Settlement settlement = settlementFor(plan);
        registry.settlements().put(settlement);

        List<Persona> residents = new ArrayList<>(plan.residents());
        for (int i = 0; i < plan.residents(); i++) {
            Persona placed = Persona.create(new UUID(plan.seed(), i), 0L)
                    .placed(settlement.id(), 4096 + (i % plan.households()), plan.cultureId());
            Persona rolled = placed.withTraits(TraitRoll.roll(placed, registry.settlements()));
            registry.put(rolled);
            residents.add(rolled);
        }

        List<Moment> chronicle = new ArrayList<>();
        Map<UUID, History> histories = new LinkedHashMap<>();
        Map<UUID, int[]> warmth = new LinkedHashMap<>();
        // Trust as well as warmth, because they behave completely differently over a hundred days —
        // warmth decays and trust does not — and DESIGN.md §5's residency threshold reads both.
        // Session 09 is what has to choose between them, and this is the column it chooses on.
        Map<UUID, int[]> trust = new LinkedHashMap<>();
        Map<UUID, Integer> contact = new LinkedHashMap<>();
        Map<UUID, Integer> first = new LinkedHashMap<>();
        Map<UUID, Integer> peak = new LinkedHashMap<>();
        Map<UUID, Integer> peakDay = new LinkedHashMap<>();
        for (Persona resident : residents) {
            warmth.put(resident.id(), new int[plan.days()]);
            trust.put(resident.id(), new int[plan.days()]);
            contact.put(resident.id(), 0);
            first.put(resident.id(), -1);
            peak.put(resident.id(), 0);
            peakDay.put(resident.id(), -1);
        }

        // One row per deed the run emits, filled in at the close of every day. Keyed on the
        // attributed id; the blurred twin is counted into the same row, because a story is one
        // story however badly it is remembered.
        Map<Long, Spread> spread = new LinkedHashMap<>();

        int visit = 0;
        for (int day = 0; day < plan.days(); day++) {
            if (day % plan.model().visitEveryDays() == 0) {
                boolean strike = plan.model().strikeEveryVisits() > 0
                        && visit % plan.model().strikeEveryVisits() == 0
                        && visit > 0;
                for (int i = 0; i < plan.model().deedsPerVisit(); i++) {
                    // The blow is the first deed of the visit rather than a random one, so a run is
                    // reproducible and the chronicle reads in the order things happened.
                    DeedType type = strike && i == 0
                            ? DeedType.STRUCK_RESIDENT
                            : kindnessFor(plan, day, i);
                    Moment moment = emit(registry, plan, settlement, residents, day, visit, i, type);
                    chronicle.add(moment);
                    spread.computeIfAbsent(moment.deedId(), id -> new Spread(id, moment.blurredId(),
                            moment.type(), moment.day(), new int[plan.days()], new int[plan.days()]));
                }
                visit++;
            }

            // Step 7, at the cadence DESIGN.md §8 rules it: four an in-game hour, ninety-six a day.
            // Run after the day's deeds rather than before, which is the conservative order — a
            // deed emitted at nine in the morning gets about sixty drains before midnight in a real
            // game, and a story is exhausted by two.
            if (plan.gossip()) {
                for (int drain = 0; drain < Gossip.DRAINS_PER_DAY; drain++) {
                    Gossip.drainEverySettlement(registry, day);
                }
            }

            countHolders(registry, residents, spread, day);

            // The day's close, read the way a mechanic would read it: through the decayed view,
            // because that is the number any consumer of a bond actually sees.
            for (Persona resident : residents) {
                Bond bond = registry.bonds().at(resident.id(), PLAYER, day);
                warmth.get(resident.id())[day] = bond.warmth();
                trust.get(resident.id())[day] = bond.trust();
                if (bond.warmth() > peak.get(resident.id())) {
                    peak.put(resident.id(), (int) bond.warmth());
                    peakDay.put(resident.id(), day);
                }
            }
        }

        // Contact days come out of the chronicle rather than out of the rings, because a ring holds
        // thirty-two entries and a hundred days does not fit in thirty-two. Reports.ringTruncation
        // is what measures the gap between the two.
        Map<UUID, Set<Integer>> daysSeen = new LinkedHashMap<>();
        for (Moment moment : chronicle) {
            for (UUID reached : moment.reached()) {
                daysSeen.computeIfAbsent(reached, key -> new LinkedHashSet<>()).add(moment.day());
            }
        }
        for (Persona resident : residents) {
            Set<Integer> seen = daysSeen.getOrDefault(resident.id(), Set.of());
            contact.put(resident.id(), seen.size());
            first.put(resident.id(), seen.stream().mapToInt(Integer::intValue).min().orElse(-1));
        }

        for (Persona resident : residents) {
            histories.put(resident.id(), new History(resident.id(), warmth.get(resident.id()),
                    trust.get(resident.id()),
                    contact.get(resident.id()), first.get(resident.id()),
                    peak.get(resident.id()), peakDay.get(resident.id())));
        }

        return new Outcome(plan, registry, List.copyOf(residents), settlement,
                Collections.unmodifiableList(chronicle), Collections.unmodifiableMap(histories),
                List.copyOf(spread.values()), System.nanoTime() - begun);
    }

    /**
     * One deed, through {@link DeedBus#record} — the same door the game uses.
     *
     * <p>The subject rotates through the player's focus set; the witnesses are the next residents in
     * a rotation seeded from the day, so a village is not always the same three people watching.
     */
    private static Moment emit(NpcRegistry registry, Plan plan, Settlement settlement,
                               List<Persona> residents, int day, int visit, int index, DeedType type) {
        int focus = Math.min(plan.focus(), residents.size());
        Persona subject = residents.get((visit * plan.model().deedsPerVisit() + index) % focus);

        int wanted = Math.min(DeedBus.MAX_WITNESSES,
                Math.round((residents.size() - 1) * plan.witnessFraction()));
        List<Persona> reached = new ArrayList<>(wanted + 1);
        Set<UUID> taken = new LinkedHashSet<>();
        reached.add(subject);
        taken.add(subject.id());
        for (int i = 0; i < wanted; i++) {
            // Rotating rather than fixed: standing in the square is not the same three people every
            // day, and a fixed set would produce a distribution with a hole in it that no real
            // village has. Walked forward from a per-day offset so a collision with the subject
            // costs a neighbour rather than a witness.
            for (int probe = 0; probe < residents.size(); probe++) {
                Persona candidate = residents.get(
                        Math.floorMod(day * 31 + i * 7 + probe, residents.size()));
                if (taken.add(candidate.id())) {
                    reached.add(candidate);
                    break;
                }
            }
        }

        Deed deed = type == DeedType.STRUCK_RESIDENT
                ? Deed.of(type, PLAYER, subject.id(), settlement.id(), day)
                        .withSeverity(SocialEvents.severityOf(4.0F))
                : Deed.of(type, PLAYER, subject.id(), settlement.id(), day);

        DeedBus.Result result = DeedBus.record(registry, deed, reached, reached.size() - 1);
        return new Moment(day, deed.id(), deed.blurredId(), type, subject.id(),
                reached.stream().map(Persona::id).toList(), result.witnesses(),
                result.remembered(), result.bondsMoved());
    }

    /**
     * How many residents held each story at the close of {@code day}.
     *
     * <p>Read off the rings rather than accumulated as the deeds land, for the reason session 07
     * gave for reading bonds through the decayed view: <b>the report has to say what a villager
     * actually holds</b>, and a ring evicts. A counter incremented on delivery would keep reporting a
     * villager as a holder long after the thirty-third distinct thing pushed the story out.
     *
     * <p>Both ids are counted into one row. A blurred copy is a different deed by construction —
     * the actor is part of the derivation — and the question is about the event rather than about
     * the account of it.
     */
    private static void countHolders(NpcRegistry registry, List<Persona> residents,
                                     Map<Long, Spread> spread, int day) {
        Map<Long, Integer> held = new LinkedHashMap<>();
        for (Persona resident : residents) {
            for (Deed deed : registry.memories().of(resident.id())) {
                held.merge(deed.id(), 1, Integer::sum);
            }
        }
        for (Spread story : spread.values()) {
            int named = held.getOrDefault(story.deedId(), 0);
            int unnamed = held.getOrDefault(story.blurredId(), 0);
            story.holders()[day] = named + unnamed;
            story.unattributed()[day] = unnamed;
        }
    }

    /**
     * Which kindness the player did.
     *
     * <p>Three distinct types rather than one, because {@code Deed.id()} is content-addressed: a
     * hundred days of the same gift to the same person on different days is a hundred distinct
     * deeds, but a dozen of them <i>on one day</i> is one. That is the ring's ungrindability doing
     * its job, and a model that only ever emitted {@code GIFT_WANTED} would measure it wrong in a way
     * that flatters the ring.
     */
    private static DeedType kindnessFor(Plan plan, int day, int index) {
        return switch (Math.floorMod(day + index, 3)) {
            case 0 -> DeedType.FED_HUNGRY;
            case 1 -> DeedType.GIFT_WANTED;
            default -> DeedType.GIFT_UNWANTED;
        };
    }

    /**
     * A settlement with the survey outputs a real one would carry.
     *
     * <p>The needs vector is derived from the seed so a run is deterministic and two runs of
     * different seeds are genuinely different places — {@code TraitRoll.settlementMean} reads all
     * four, so a village short of food really does raise warier, greedier people than one short of
     * tools.
     */
    private static Settlement settlementFor(Plan plan) {
        long seed = plan.seed();
        byte[] needs = {
                (byte) Math.floorMod(seed * 7, 61),
                (byte) Math.floorMod(seed * 13, 61),
                (byte) Math.floorMod(seed * 17, 61),
                (byte) Math.floorMod(seed * 23, 61)};
        return new Settlement(0, OVERWORLD,
                new BlockPos((int) Math.floorMod(seed, 4096), 64, (int) Math.floorMod(seed / 4096, 4096)),
                plan.specialty(), plan.defensibility(), needs);
    }

    /** What the day's allowance is worth to this person, for the report's own explanation of itself. */
    public static int allowanceOf(Persona persona) {
        return Personality.allowance(persona);
    }
}
