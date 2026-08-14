package net.namesake.social;

import net.namesake.culture.Names;
import net.namesake.npc.NpcRegistry;
import net.namesake.npc.Persona;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * <b>What a world's social state actually looks like, measured rather than assumed.</b> Session 07's
 * instrument, and the answer to the failure it exists to prevent.
 *
 * <p>LNK set skill gates at 35–205 against an observed maximum affinity of <b>32</b>. Zero players
 * ever reached the lowest gate and nobody noticed for months, because nothing in that codebase could
 * answer "what do players actually earn". {@link #observedMaximum} is that question, and every
 * threshold session 12 sets is expressed in a unit this class reports.
 *
 * <h2>Not persisted, and that is a ruling rather than an omission</h2>
 *
 * <p>{@code WORKPLAN.md} session 07 specifies "{@code DialogueStats} SavedData". It is not one, for
 * three reasons and one trap, and each is worth stating because the instruction was explicit.
 *
 * <ol>
 *   <li><b>Every number here is derivable from state that is already persisted and already
 *       ledgered.</b> A bond carries what it is worth and when it was last touched; a ring carries
 *       thirty-two deeds each stamped with the day it happened on. Contact days, spans, rates,
 *       occupancy and the deed mix all fall out of those. Storing them as well would be a cache —
 *       and session 03 deleted {@code Settlement.culture} for being exactly that, on the grounds
 *       that a cache with no behaviour of its own is a persisted value with no consumer.</li>
 *   <li><b>A stored tally survives a change to the thing it counts.</b> Retune a weight, add a deed
 *       type, move the daily cap, and a persisted histogram still holds last month's numbers —
 *       describing a mod that no longer exists, with nothing available to notice. Derived, it cannot
 *       disagree with the registry, because it <i>is</i> the registry read a second way.</li>
 *   <li><b>Hard rule 1 would fire for nothing.</b> A persisted schema change owes a schema bump, a
 *       datafixer and a load test against a pre-change save. That is a real price, and here it would
 *       buy the ability to regenerate on demand something that is already free to regenerate on
 *       demand.</li>
 * </ol>
 *
 * <p><b>The trap, named because it is the one the session brief warned about.</b>
 * {@code SocialValueLedgerTest} refuses a persisted record whose only named consumer is a display,
 * and {@code DISPLAY_PACKAGES} already contains {@code net.namesake.command}. A persisted
 * {@code DialogueStats} whose consumer is {@code /namesake debug stats} would be refused, correctly,
 * and the available ways round it are both dishonest: an exemption is a promise that a mechanic will
 * read the field, and no mechanic reads a histogram; and a {@code PRESENTATION} classification means
 * "ruled as existing to be drawn in {@code DESIGN.md}", which is true of
 * {@code Persona.appearanceSeed} and not of this. So the build would have been right and the honest
 * answer was to have nothing to classify.
 *
 * <p><b>What that costs, plainly, because it does cost something.</b> A deed evicted from a ring is
 * gone, so nothing here can say "you have forgotten forty things about this player". What it can say
 * is which rings are <i>full</i> and how far back the fullest one still reaches, which is the same
 * question asked in a form the data can answer — see {@link Ring}. A live counter would answer it
 * exactly and would reset on every restart, which makes it a fact about this session rather than
 * about the playthrough; two answers to one question is the problem {@code CLAUDE.md} names, in code.
 *
 * <h2>The unit, ruled here so session 12 has one</h2>
 *
 * <p><b>An earn rate is warmth points per in-game day of contact.</b> Not per player-hour and not
 * per deed, and both alternatives are worse for the same reason: the mechanism's own clock is the
 * in-game day. {@code Bond.DAILY_CAP} is per axis per day, the decay is a point per day,
 * {@code lastSeenDay} is a day, and {@code Deed.dayOf} is what turns game time into one. A
 * player-hour would need a conversion nobody will remember and would differ between two players with
 * identical play time. A per-deed rate is dominated by how hard somebody is grinding, which is
 * precisely the number the daily cap exists to make meaningless.
 *
 * <p>{@link Standing#perElapsedDay} is reported beside it and is the second half of the truth: a
 * rate per day <i>of contact</i> ignores the decay, and a player who visits once a fortnight is
 * losing warmth between visits. Session 12 wants both — the first to know how fast a band can be
 * crossed, the second to know whether it can be held.
 */
public final class DialogueStats {

    /**
     * The ladder the report measures days-to-reach against.
     *
     * <p><b>Deliberately not called bands.</b> Session 12 owns the bands and their thresholds;
     * naming them here would be this session deciding session 12's work under a different heading.
     * These are evenly spaced marks on a 0–100 axis, so a threshold set anywhere can be read off the
     * table by interpolation.
     */
    public static final int[] LADDER = {20, 40, 60, 80, 100};

    private final List<Standing> standings;
    private final List<Ring> rings;
    private final int today;
    private final int personas;

    /**
     * One resident's relationship with one viewer, and how fast it got there.
     *
     * @param bond        the decayed view, because that is what any consumer of a bond actually sees
     * @param contactDays distinct in-game days on which a deed by this viewer reached them,
     *                    <b>as far back as their ring still remembers</b>
     * @param spanDays    from the oldest such day their ring holds to today, inclusive
     */
    public record Standing(UUID holder, String name, Bond bond, int contactDays, int spanDays,
                           int ringSlots, float giftScale, int allowance) {

        /** Warmth per in-game day of contact — the unit ruled above. */
        public float perContactDay() {
            return contactDays == 0 ? 0F : (float) bond.warmth() / contactDays;
        }

        /** Warmth per in-game day elapsed since the oldest contact the ring still holds. */
        public float perElapsedDay() {
            return spanDays == 0 ? 0F : (float) bond.warmth() / spanDays;
        }

        public boolean hasMetTheViewer() {
            return !bond.isNothing() || contactDays > 0;
        }
    }

    /**
     * One villager's ring, as a shape rather than as a list of rows.
     *
     * <p>{@code full} is the number the owner's two parked rulings are actually about: a ring that
     * has never filled has never evicted anything, so thirty-two has never been tested. The oldest
     * day a full ring still holds is how far back that villager's memory reaches, which is the
     * closest a derived instrument can get to "what have they forgotten".
     */
    public record Ring(UUID holder, String name, int slots, int oldestDay, int newestDay,
                       Map<DeedType, Integer> mix) {

        public Ring {
            mix = Collections.unmodifiableMap(new EnumMap<>(mix));
        }

        public boolean isFull() {
            return slots >= Memories.RING_CAPACITY;
        }

        /** How many in-game days this villager's memory currently reaches back. */
        public int reachDays() {
            return slots == 0 ? 0 : newestDay - oldestDay + 1;
        }

        /** Which deed types they hold, commonest first. */
        public List<Map.Entry<DeedType, Integer>> byFrequency() {
            List<Map.Entry<DeedType, Integer>> entries = new ArrayList<>(mix.entrySet());
            entries.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
            return entries;
        }
    }

    private DialogueStats(List<Standing> standings, List<Ring> rings, int today, int personas) {
        this.standings = List.copyOf(standings);
        this.rings = List.copyOf(rings);
        this.today = today;
        this.personas = personas;
    }

    /**
     * Reads a registry.
     *
     * <p><b>Works identically over a live world's registry and over {@code Simulation}'s.</b> That is
     * the whole point of it taking an {@code NpcRegistry} rather than a server: the numbers session
     * 12's thresholds are set from and the numbers a real save produces come out of one piece of
     * code, so they can be held against each other rather than compared by eye.
     *
     * @param viewer whose relationships to measure. Null asks only about the rings, which is the
     *               right answer from a console with no player attached to it.
     */
    public static DialogueStats of(NpcRegistry registry, UUID viewer, int today) {
        List<Standing> standings = new ArrayList<>();
        List<Ring> rings = new ArrayList<>();

        for (Persona persona : registry.all()) {
            List<Deed> ring = registry.memories().of(persona.id());
            if (!ring.isEmpty()) {
                rings.add(ringOf(persona, ring));
            }
            if (viewer == null) {
                continue;
            }
            Set<Integer> contactDays = new LinkedHashSet<>();
            int oldest = Integer.MAX_VALUE;
            for (Deed deed : ring) {
                if (deed.actor().equals(viewer)) {
                    contactDays.add(deed.gameDay());
                    oldest = Math.min(oldest, deed.gameDay());
                }
            }
            Bond bond = registry.bonds().at(persona.id(), viewer, today);
            int span = oldest == Integer.MAX_VALUE ? 0 : today - oldest + 1;
            standings.add(new Standing(persona.id(), nameOf(persona), bond,
                    contactDays.size(), span, ring.size(),
                    Personality.scale(persona, DeedType.GIFT_WANTED),
                    Personality.allowance(persona)));
        }

        standings.sort((a, b) -> Integer.compare(b.bond().warmth(), a.bond().warmth()));
        rings.sort((a, b) -> Integer.compare(b.slots(), a.slots()));
        return new DialogueStats(standings, rings, today, registry.size());
    }

    private static Ring ringOf(Persona persona, List<Deed> ring) {
        Map<DeedType, Integer> mix = new EnumMap<>(DeedType.class);
        int oldest = Integer.MAX_VALUE;
        int newest = Integer.MIN_VALUE;
        for (Deed deed : ring) {
            mix.merge(deed.type(), 1, Integer::sum);
            oldest = Math.min(oldest, deed.gameDay());
            newest = Math.max(newest, deed.gameDay());
        }
        return new Ring(persona.id(), nameOf(persona), ring.size(), oldest, newest, mix);
    }

    private static String nameOf(Persona persona) {
        return persona.isGenerated()
                ? Names.of(persona).full()
                : "(ungenerated) " + persona.id().toString().substring(0, 8);
    }

    // --- reads ---------------------------------------------------------------------------------

    /** Every resident's standing with the viewer, warmest first. Empty when there is no viewer. */
    public List<Standing> standings() {
        return standings;
    }

    /** Everyone who remembers anything, deepest ring first. */
    public List<Ring> rings() {
        return rings;
    }

    public int today() {
        return today;
    }

    public int personas() {
        return personas;
    }

    /** How many residents have any relationship with the viewer at all. */
    public int metTheViewer() {
        return (int) standings.stream().filter(Standing::hasMetTheViewer).count();
    }

    /**
     * <b>The number LNK never had.</b> The highest any resident has actually reached on this axis.
     *
     * <p>A band threshold above this is a band nobody is in, and a band threshold above this is
     * exactly what LNK shipped: gates from 35 to 205 against an observed 32. Read it before setting
     * one.
     */
    public int observedMaximum(int axis) {
        return standings.stream().mapToInt(standing -> standing.bond().axis(axis)).max().orElse(0);
    }

    public int observedMinimum(int axis) {
        return standings.stream().mapToInt(standing -> standing.bond().axis(axis)).min().orElse(0);
    }

    /**
     * The {@code percent}th percentile of one bond axis across everyone who has met the viewer.
     *
     * <p>Nearest-rank, and over <i>those who have met the viewer</i> rather than over everybody: a
     * village of four hundred people the player has never been near would otherwise drag every
     * percentile to zero and report that nobody earns anything.
     */
    public int percentile(int axis, int percent) {
        int[] values = standings.stream()
                .filter(Standing::hasMetTheViewer)
                .mapToInt(standing -> standing.bond().axis(axis))
                .sorted()
                .toArray();
        if (values.length == 0) {
            return 0;
        }
        int index = Math.min(values.length - 1, (int) ((percent / 100.0) * values.length));
        return values[index];
    }

    /** The deepest ring in the world, or empty if nobody remembers anything. */
    public java.util.Optional<Ring> deepestRing() {
        return rings.isEmpty() ? java.util.Optional.empty() : java.util.Optional.of(rings.get(0));
    }

    /** The median ring by occupancy — the typical villager's memory rather than the busiest one's. */
    public java.util.Optional<Ring> medianRing() {
        return rings.isEmpty()
                ? java.util.Optional.empty()
                : java.util.Optional.of(rings.get(rings.size() / 2));
    }

    public int fullRings() {
        return (int) rings.stream().filter(Ring::isFull).count();
    }

    public int deedsHeld() {
        return rings.stream().mapToInt(Ring::slots).sum();
    }

    /** How many slots of the world's total ring capacity are in use, as a fraction. */
    public float ringOccupancy() {
        if (personas == 0) {
            return 0F;
        }
        return (float) deedsHeld() / (personas * Memories.RING_CAPACITY);
    }

    /** Every deed type held anywhere, commonest first. */
    public List<Map.Entry<DeedType, Integer>> deedMix() {
        Map<DeedType, Integer> total = new EnumMap<>(DeedType.class);
        for (Ring ring : rings) {
            ring.mix().forEach((type, count) -> total.merge(type, count, Integer::sum));
        }
        List<Map.Entry<DeedType, Integer>> entries = new ArrayList<>(total.entrySet());
        entries.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
        return entries;
    }

    /**
     * How many in-game days of contact it would take to reach {@code target} warmth, at the median
     * observed rate.
     *
     * <p>{@code -1} when nobody is earning anything, which is a real answer rather than an infinity:
     * a threshold nobody is moving toward is the LNK failure with the arithmetic done.
     */
    public int contactDaysTo(int target) {
        float[] rates = ratesPerContactDay();
        if (rates.length == 0) {
            return -1;
        }
        float median = rates[rates.length / 2];
        return median <= 0F ? -1 : (int) Math.ceil(target / median);
    }

    /** Sorted warmth-per-contact-day for everyone who has met the viewer. */
    public float[] ratesPerContactDay() {
        List<Float> values = new ArrayList<>();
        for (Standing standing : standings) {
            if (standing.contactDays() > 0) {
                values.add(standing.perContactDay());
            }
        }
        float[] rates = new float[values.size()];
        for (int i = 0; i < rates.length; i++) {
            rates[i] = values.get(i);
        }
        java.util.Arrays.sort(rates);
        return rates;
    }

    /** Ring occupancy as a distribution: how many villagers hold each count of slots. */
    public Map<Integer, Integer> occupancyHistogram() {
        Map<Integer, Integer> histogram = new LinkedHashMap<>();
        for (Ring ring : rings) {
            histogram.merge(ring.slots(), 1, Integer::sum);
        }
        return histogram;
    }
}
