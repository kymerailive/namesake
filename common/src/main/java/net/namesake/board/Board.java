package net.namesake.board;

import net.minecraft.core.BlockPos;
import net.namesake.culture.Culture;
import net.namesake.culture.Names;
import net.namesake.dialogue.Dialogue;
import net.namesake.dialogue.Pool;
import net.namesake.npc.NpcRegistry;
import net.namesake.npc.Persona;
import net.namesake.settlement.Settlement;
import net.namesake.social.Bond;
import net.namesake.social.Deed;
import net.namesake.social.DeedType;
import net.namesake.social.Memories;
import net.namesake.social.Residency;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * <b>What one village knows about one player, as a structure.</b> Session 11's Notice Board, before
 * anything has been laid out or drawn.
 *
 * <h2>This is a rendering, not a computation</h2>
 *
 * <p>Every number here comes out of an instrument that already exists and is already covered.
 * {@link Dialogue#poolFor} decides the standing, {@link Residency#verdict} decides whether the
 * village has taken you in, {@link Memories#slotsOf} is the ring, and {@link Names} is the naming
 * authority. Nothing in this class decides anything about the world — if a second implementation of
 * any of those appears here, that is the defect rather than the feature.
 *
 * <h2>Nothing is persisted, and there is nothing to persist</h2>
 *
 * <p>{@code DESIGN.md} §9 says the Notice Board is <i>a lectern with a block entity</i>. It has no
 * block entity, and the reason is the one this project has ruled four times — settlements at 03,
 * bonds at 05, rings at 06, gossip at 08: <b>one file, one version number that cannot disagree with
 * itself.</b> A block entity is saved by Minecraft into chunk data, which is a second persisted store
 * with a schema of its own, outside {@code NpcSchema}, that hard rule 1's fixer ladder cannot see and
 * a load test cannot cover. What it would hold is the single fact that this lectern is a board — and
 * that is answerable without holding it, because a lectern standing in a registered settlement
 * <i>is</i> the board. See {@link NoticeBoard} for the rest of that argument.
 *
 * <p>This object is built on GUI open, sent once, and dropped. It is never ticked, never cached and
 * never shared between two players, which is also the whole of how {@code DESIGN.md} §2's 80/20
 * personal split is honoured: <b>there is nowhere for one player's board to be stored, so there is
 * nowhere for another player to read it from.</b>
 *
 * <h2>What a village remembers, rather than what nine villagers each remember</h2>
 *
 * <p>Deeds are deduped across the settlement on {@link Deed#id()} and counted, which is only possible
 * because session 06 derived that id from the deed rather than assigning one — the same property
 * {@link Residency} leans on for its feeding count. So one feeding four people watched is one row
 * saying four people remember it, rather than four rows saying the same thing. The confidence kept is
 * the highest any holder has, which is {@code DESIGN.md} §2's ring-collision rule — <i>the better
 * attested copy of an event wins</i> — read at the scale of a village instead of a slot.
 */
public record Board(
        String place,
        String culture,
        int day,
        int residents,
        boolean named,
        Residency.Verdict residency,
        List<Neighbour> opinions,
        int strangers,
        List<Memory> witnessed,
        List<Memory> hearsay) {

    public Board {
        opinions = List.copyOf(opinions);
        witnessed = List.copyOf(witnessed);
        hearsay = List.copyOf(hearsay);
    }

    /**
     * One resident who has an opinion of the viewer, and which one.
     *
     * <p><b>{@link Pool} rather than a naming scheme of this session's own, and that is the
     * composition decision session 11 opens on.</b> {@code DESIGN.md} §2 rules <i>bands, never raw
     * integers</i>, and the five bands are session 12's. Four ways out were available; three of them
     * cost something this session cannot pay. Inventing a fifth scheme puts two answers to "what is
     * my standing" in the game at once — the board's and the villager's — which is the failure
     * {@code CLAUDE.md} names about documents, in code. Printing no standing at all fails the brief
     * and makes the board's first section its own absence for ever. Printing the integers breaks a
     * ruled decision.
     *
     * <p>So the board asks {@link Dialogue#poolFor} — <b>the same call {@code Dialogue.speak} makes,
     * not a second copy of its arithmetic</b> — and renders the answer as a phrase. The board and the
     * villager therefore agree by construction rather than by two implementations that happen to
     * match, which is the strongest available answer to the objection.
     *
     * <p><b>What session 12 has to replace is one method.</b> Its third ruled consumer is <i>which
     * dialogue pool is selected</i>, which is {@code Dialogue.poolFor} itself; when that becomes a
     * band lookup the board moves with it and needs no edit, because nothing here names a threshold
     * or a number. What session 12 may <i>additionally</i> want is to print five band names where
     * this prints four pool phrases — and that is a second change, to one table, rather than a
     * mechanism change. The cost of not making it is stated rather than buried: <b>two bands that
     * share a pool look identical on this board</b>, so a player whose price moved may see the board
     * stand still. That is strictly better than the board and the villager disagreeing.
     */
    public record Neighbour(String name, Pool pool) {
    }

    /**
     * One thing the viewer did, as this settlement holds it.
     *
     * @param holders  how many residents here remember it at all
     * @param repeats  the most any one of them remembers it happening — a per-slot number, because
     *                 two villagers who watched the same afternoon can have seen different amounts
     *                 of it
     * @param item     what it was about, or {@link Deed#NO_ITEM}. Emptied when two holders disagree:
     *                 a village that cannot say which object it was does not pick one.
     */
    public record Memory(int day, DeedType type, String item, int repeats, int holders,
                         byte confidence, Origin origin) {

        public boolean firstHand() {
            return confidence >= Deed.FIRST_HAND;
        }
    }

    /**
     * <b>Where a story came from — session 10's loose end, and it needed no field.</b>
     *
     * <p>{@code /namesake debug deeds} prints {@code @s0}, which is not a sentence.
     * {@code Deed.UNKNOWN_ACTOR}'s own note has said since session 08 that <i>"from the north" needs
     * no field: the direction is a function of where the deed happened, which the deed carries, and
     * where the holder lives, which their persona carries</i> — and nothing had ever built it.
     *
     * <p>Both halves are here, and the name is the better of the two rather than a replacement for
     * it. A settlement's name is derived from its bell and its culture ({@link Names#ofSettlement}),
     * so it costs no bytes and no schema; the bearing is arithmetic over two bells. A story from a
     * place that is no longer in the settlement table — a ruin, at sessions 21–23 — keeps the bearing
     * and loses the name, which is this mod's one rule about detail: <b>it degrades, it is never
     * invented.</b>
     */
    public record Origin(boolean here, String place, String bearing) {

        /** It happened in this village. Nobody carried it anywhere. */
        public static final Origin HERE = new Origin(true, "", "");

        public boolean hasPlace() {
            return !place.isEmpty();
        }
    }

    /**
     * The eight points of the compass, from the holder's village to the place a story came from.
     *
     * <p>Minecraft's axes: {@code +X} is east and {@code +Z} is south, so north is {@code -Z} and the
     * bearing wanted is {@code atan2(dx, -dz)} rather than the usual {@code atan2(dy, dx)}. The
     * half-sector offset is what makes each name cover the forty-five degrees <i>centred</i> on it
     * instead of the forty-five degrees after it — without it a village due east reads as
     * "south-east".
     */
    static final String[] COMPASS = {
            "north", "north-east", "east", "south-east",
            "south", "south-west", "west", "north-west"};

    static String bearing(BlockPos from, BlockPos to) {
        int dx = to.getX() - from.getX();
        int dz = to.getZ() - from.getZ();
        if (dx == 0 && dz == 0) {
            // Two bells on one column is not a direction. Every caller has already established that
            // these are different settlements, so this is a guard rather than a case.
            return "";
        }
        double degrees = Math.toDegrees(Math.atan2(dx, -dz));
        int sector = (int) Math.floor((degrees + 360.0 + 22.5) / 45.0) % 8;
        return COMPASS[sector];
    }

    // --- building it ---------------------------------------------------------------------------

    /**
     * Reads a registry, on the server, at the moment somebody opens a lectern.
     *
     * <p>Pure over the registry — no level, no entities, no server — for the reason
     * {@link Residency#verdict} and {@link net.namesake.social.DialogueStats} are: it is what lets
     * {@code BoardTest} enumerate every state this board can be in rather than sampling whichever one
     * a fixture happens to produce. This project has shipped a string nobody measured against the
     * space it has to sit in six times, and every one of the last three was found by a person looking
     * at a screen.
     *
     * @param viewer whose board this is. Never null — a board with no viewer is a board nobody
     *               opened.
     */
    public static Board of(NpcRegistry registry, Settlement here, UUID viewer, int day) {
        List<Persona> residents = new ArrayList<>();
        for (Persona persona : registry.all()) {
            if (persona.settlementId() == here.id()) {
                residents.add(persona);
            }
        }

        Optional<Culture> culture = cultureOf(residents);
        String place = culture.map(c -> Names.ofSettlement(c, here.centre().getX(), here.centre().getZ()))
                .orElse("");

        List<Neighbour> opinions = new ArrayList<>();
        int strangers = 0;
        Map<Long, Tally> tallies = new LinkedHashMap<>();
        for (Persona resident : residents) {
            List<Memories.Slot> ring = registry.memories().slotsOf(resident.id());
            List<Deed> deeds = new ArrayList<>(ring.size());
            for (Memories.Slot slot : ring) {
                deeds.add(slot.deed());
            }

            Bond bond = registry.bonds().at(resident.id(), viewer, day);
            // The same call Dialogue.speak makes, so the board cannot disagree with the villager.
            Pool pool = Dialogue.poolFor(bond, Dialogue.remembersThem(deeds, viewer));
            if (pool == Pool.STRANGER) {
                strangers++;
            } else {
                opinions.add(new Neighbour(nameOf(resident), pool));
            }

            for (Memories.Slot slot : ring) {
                // A blurred rumour carries Deed.UNKNOWN_ACTOR, so it never matches the viewer and
                // never reaches this board. That is not an omission: a villager who cannot say who
                // killed the smith has nothing to post about *you*, which is the same `if` session
                // 08 gave the blur instead of a caption.
                if (viewer.equals(slot.deed().actor())) {
                    tallies.computeIfAbsent(slot.deed().id(), id -> new Tally(slot.deed())).add(slot);
                }
            }
        }

        opinions.sort(Comparator.comparingInt((Neighbour n) -> order(n.pool()))
                .thenComparing(Neighbour::name));

        List<Memory> witnessed = new ArrayList<>();
        List<Memory> hearsay = new ArrayList<>();
        for (Tally tally : tallies.values()) {
            Memory memory = tally.toMemory(registry, here);
            (memory.firstHand() ? witnessed : hearsay).add(memory);
        }
        Comparator<Memory> newestFirst = Comparator.comparingInt(Memory::day).reversed();
        witnessed.sort(newestFirst);
        hearsay.sort(newestFirst);

        return new Board(place, culture.map(Culture::displayName).orElse(""), day, residents.size(),
                !place.isEmpty(), Residency.verdict(registry, here.id(), viewer, day),
                opinions, strangers, witnessed, hearsay);
    }

    /**
     * Which order the opinions read in: the ones with something to say first.
     *
     * <p>Not the pool's own ordinal. {@code Pool} is declared in the order a relationship develops,
     * and a board sorted that way buries the one villager who is hostile under everybody who merely
     * knows your face.
     */
    private static int order(Pool pool) {
        return switch (pool) {
            case HOSTILE -> 0;
            case WARM -> 1;
            case KNOWN -> 2;
            case STRANGER -> 3;
        };
    }

    /**
     * The culture the village speaks, taken from anybody who lives in it.
     *
     * <p>Unanimous by construction rather than by luck: {@code Personas.generate} reads the culture
     * at the <i>settlement centre</i> rather than at the villager's feet, precisely so that two
     * residents on opposite sides of a culture border do not grow up in different cultures in one
     * village. So the first resident answers for all of them, and a settlement with nobody left in
     * the registry has no name — which the board says out loud rather than inventing one.
     */
    private static Optional<Culture> cultureOf(List<Persona> residents) {
        for (Persona resident : residents) {
            if (resident.isGenerated()) {
                return Optional.of(Culture.byId(resident.cultureId()));
            }
        }
        return Optional.empty();
    }

    private static String nameOf(Persona persona) {
        return persona.isGenerated()
                ? Names.of(persona).full()
                : "(ungenerated) " + persona.id().toString().substring(0, 8);
    }

    /** One event, gathered across everybody in the village who holds a copy of it. */
    private static final class Tally {

        private final Deed deed;
        private int holders;
        private int repeats;
        private byte confidence;
        private String item;
        private boolean itemDisputed;

        private Tally(Deed deed) {
            this.deed = deed;
            this.item = deed.item();
        }

        private void add(Memories.Slot slot) {
            holders++;
            repeats = Math.max(repeats, slot.repeats());
            confidence = (byte) Math.max(confidence, slot.deed().confidence());
            // Every field of a deed but confidence and the object is inside Deed.id(), so two copies
            // of one event can only ever disagree about those two. Confidence takes the best account
            // anybody has; the object takes DESIGN.md §2's other rule and names neither when two
            // holders remember different things, because a village that cannot say which object it
            // was does not get to pick one.
            if (!itemDisputed && !item.equals(slot.deed().item())) {
                itemDisputed = true;
                item = Deed.NO_ITEM;
            }
        }

        private Memory toMemory(NpcRegistry registry, Settlement here) {
            return new Memory(deed.gameDay(), deed.type(), item, repeats, holders, confidence,
                    originOf(registry, here, deed.settlementId()));
        }
    }

    /**
     * Where a deed happened, said the way a person standing in {@code here} would say it.
     *
     * <p>Three answers and they are a ladder of what can honestly be known: it happened here; it
     * happened in a place with a name and a direction; or it happened somewhere that is no longer in
     * the settlement table, which still has a direction.
     */
    static Origin originOf(NpcRegistry registry, Settlement here, int settlementId) {
        if (settlementId == here.id()) {
            return Origin.HERE;
        }
        Optional<Settlement> from = registry.settlements().byId(settlementId);
        if (from.isEmpty()) {
            return new Origin(false, "", "");
        }
        Settlement there = from.get();
        String bearing = bearing(here.centre(), there.centre());
        List<Persona> residents = new ArrayList<>();
        for (Persona persona : registry.all()) {
            if (persona.settlementId() == there.id()) {
                residents.add(persona);
            }
        }
        String name = cultureOf(residents)
                .map(c -> Names.ofSettlement(c, there.centre().getX(), there.centre().getZ()))
                .orElse("");
        return new Origin(false, name, bearing);
    }

    /** Whether anything at all has happened between this village and this player. */
    public boolean hasHistory() {
        return !witnessed.isEmpty() || !hearsay.isEmpty();
    }
}
