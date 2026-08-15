package net.namesake.board;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.namesake.culture.Culture;
import net.namesake.culture.Names;
import net.namesake.dialogue.Dialogue;
import net.namesake.dialogue.Pool;
import net.namesake.npc.NpcRegistry;
import net.namesake.npc.Persona;
import net.namesake.settlement.Need;
import net.namesake.settlement.Settlement;
import net.namesake.settlement.Specialty;
import net.namesake.social.Standing;
import net.namesake.social.Bond;
import net.namesake.social.Deed;
import net.namesake.social.DeedType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>What one village knows about one player.</b> Session 11's board, as arithmetic.
 *
 * <p>Everything here is pure over a registry, which is the line {@code WORKPLAN.md} draws between its
 * two instruments: the layout, the absence branches, the standing naming and the direction arithmetic
 * are ten-millisecond unit tests, and what only a running game can show — a lectern placed in the
 * world, opened by a real player, drawn through a real screen with a real font — is the harness leg.
 */
class BoardTest {

    private static final UUID PLAYER = new UUID(0x5EAF_0000_0000_0001L, 11);
    private static final UUID SECOND_PLAYER = new UUID(0x5EAF_0000_0000_0002L, 11);
    private static final int HERE = 0;
    private static final int AWAY = 1;
    private static final int VANISHED = 77;

    /** The day every board in this file is read on. */
    private static final int TODAY = 20;

    private static final BlockPos HERE_BELL = new BlockPos(0, 64, 0);
    private static final BlockPos AWAY_BELL = new BlockPos(600, 64, 0);

    private static Settlement settlement(int id, BlockPos bell) {
        return new Settlement(id, ResourceLocation.withDefaultNamespace("overworld"), bell,
                Specialty.FARMING.id(), (byte) 50, new byte[Need.COUNT]);
    }

    private static UUID residentId(int settlement, int index) {
        return new UUID(0x11_0000L + settlement, index);
    }

    /** A village of {@code count} people who have met nobody, plus its settlement record. */
    private static NpcRegistry village(int count) {
        NpcRegistry registry = new NpcRegistry();
        registry.putSettlement(settlement(HERE, HERE_BELL));
        for (int i = 0; i < count; i++) {
            registry.put(Persona.create(residentId(HERE, i), 0L)
                    .placed(HERE, i / 3, Culture.VALE.id()));
        }
        return registry;
    }

    private static Board boardOf(NpcRegistry registry, UUID viewer) {
        return Board.of(registry, registry.settlements().byId(HERE).orElseThrow(), viewer, TODAY);
    }

    private static Deed fed(int day) {
        return Deed.of(DeedType.FED_HUNGRY, PLAYER, residentId(HERE, 0), HERE, day);
    }

    // --- what a village remembers, rather than what nine villagers each remember -------------------

    /**
     * <b>The dedupe, and it is only possible because session 06 derived a deed's id.</b>
     *
     * <p>Four people watching one feeding is one thing that happened, and a board that printed it
     * four times would be reporting the size of the crowd as the length of your history. Content
     * addressing pays off here for the third time — {@link net.namesake.social.Residency} counts its
     * feedings the same way, and neither needed a field for it.
     */
    @Test
    @DisplayName("one feeding four people watched is one row saying four remember it")
    void aVillageRemembersAnEventOnce() {
        NpcRegistry registry = village(9);
        Deed deed = fed(12);
        for (int i = 0; i < 4; i++) {
            registry.remember(residentId(HERE, i), deed);
        }

        Board board = boardOf(registry, PLAYER);
        assertEquals(1, board.witnessed().size(), () -> board.witnessed().toString());
        assertEquals(4, board.witnessed().get(0).holders());
        assertEquals(9, board.residents());
        assertTrue(board.hearsay().isEmpty());
    }

    /**
     * {@code DESIGN.md} §2's ring-collision rule — <i>the better attested copy of an event wins</i> —
     * read at the scale of a village instead of a slot.
     */
    @Test
    @DisplayName("if anybody here watched it, the village watched it, however many were only told")
    void theBestAccountAnybodyHasIsTheVillages() {
        NpcRegistry registry = village(9);
        Deed deed = fed(12);
        registry.remember(residentId(HERE, 0), deed);
        for (int i = 1; i < 5; i++) {
            registry.remember(residentId(HERE, i), deed.retold());
        }

        Board board = boardOf(registry, PLAYER);
        assertEquals(1, board.witnessed().size());
        assertEquals(5, board.witnessed().get(0).holders(), "everybody who holds a copy counts");
        assertEquals(Deed.FIRST_HAND, board.witnessed().get(0).confidence());
        assertTrue(board.hearsay().isEmpty(),
                "one event is on one list, and the list is decided by the best account in the village");
    }

    @Test
    @DisplayName("a village that was only ever told about it says so")
    void aVillageThatOnlyHeardAboutItSaysSo() {
        NpcRegistry registry = village(9);
        Deed heard = fed(12).retold();
        registry.remember(residentId(HERE, 0), heard);
        registry.remember(residentId(HERE, 1), heard);

        Board board = boardOf(registry, PLAYER);
        assertTrue(board.witnessed().isEmpty());
        assertEquals(1, board.hearsay().size());
        assertEquals(2, board.hearsay().get(0).holders());
        assertFalse(board.hearsay().get(0).firstHand());
    }

    /**
     * <b>The blur, arriving at a third surface, and it needed no code here at all.</b>
     *
     * <p>Session 08 made the blur replace the actor rather than hide them, so a story nobody can
     * attribute does not name the player — and a board that only ever asks "what did <i>you</i> do"
     * therefore cannot show one. A villager who cannot say who killed the smith has nothing to post
     * about you, which is the same {@code if} statement in a third costume.
     */
    @Test
    @DisplayName("a rumour nobody can attribute is not on your board, because it is not about you")
    void aBlurredRumourIsNotAboutAnybody() {
        NpcRegistry registry = village(9);
        Deed rumour = Deed.of(DeedType.KILLED_RESIDENT, PLAYER, residentId(HERE, 8), HERE, 12)
                .retold().retold();
        assertFalse(rumour.isAttributed(), "the fixture has to actually be blurred or this proves nothing");
        registry.remember(residentId(HERE, 0), rumour);

        Board board = boardOf(registry, PLAYER);
        assertFalse(board.hasHistory(), () -> "a blurred rumour reached the board: " + board.hearsay());
    }

    /**
     * <b>{@code DESIGN.md} §10 step 7, at the level the board can prove it.</b>
     *
     * <p>Two viewers, one village, one call. There is no cache and no shared state anywhere in
     * {@link Board}, so the only way one player could see another's history is if this method read
     * something other than the UUID it was handed.
     */
    @Test
    @DisplayName("a second player who has done nothing sees a board with no history on it")
    void everyBoardBelongsToOneViewer() {
        NpcRegistry registry = village(9);
        registry.remember(residentId(HERE, 0), fed(12));
        registry.putBond(residentId(HERE, 0), PLAYER, warmth(30));

        Board mine = boardOf(registry, PLAYER);
        Board theirs = boardOf(registry, SECOND_PLAYER);

        assertTrue(mine.hasHistory());
        assertFalse(theirs.hasHistory(), () -> theirs.witnessed() + " / " + theirs.hearsay());
        assertFalse(mine.opinions().isEmpty());
        assertTrue(theirs.opinions().isEmpty(), "nobody has an opinion of somebody they never met");
        assertEquals(9, theirs.strangers());
    }

    /**
     * The rule {@code Memories.remember} already carries, applied one level up: a village that cannot
     * say which object it was does not get to pick one.
     */
    /**
     * <b>Two holders of one event still collapse to one row, and that is what survived session 12's
     * deletion of the object.</b>
     *
     * <p>Until session 12 this test read <i>two people remembering different objects for one event
     * names neither</i> — the rule {@code DESIGN.md} §2 states for gossip, applied to a ring slot.
     * {@code Deed.item} lost its rule 5 exemption with no consumer to name, so there is no longer an
     * object for two villagers to disagree about. What the test is <i>for</i> is unchanged and is the
     * half that was always load-bearing: <b>the crowd is not the history.</b> One gift that four
     * people watched is one row saying four remember it, not four rows.
     */
    @Test
    @DisplayName("one event held by two people is one row, whoever is holding it")
    void aVillageDoesNotPostOneEventTwice() {
        NpcRegistry registry = village(9);
        Deed given = Deed.of(DeedType.GIFT_WANTED, PLAYER, residentId(HERE, 0), HERE, 12);
        Deed sameDay = Deed.of(DeedType.GIFT_WANTED, PLAYER, residentId(HERE, 0), HERE, 12);
        assertEquals(given.id(), sameDay.id(), "one event is one id, whoever is holding it");
        registry.remember(residentId(HERE, 0), given);
        registry.remember(residentId(HERE, 1), sameDay);

        Board board = boardOf(registry, PLAYER);
        assertEquals(1, board.witnessed().size());
        assertEquals(2, board.witnessed().get(0).holders());
    }

    @Test
    @DisplayName("an afternoon that happened nine times says nine, from whoever saw the most of it")
    void theCountIsTheMostAnybodySaw() {
        NpcRegistry registry = village(9);
        Deed deed = fed(12);
        registry.remember(residentId(HERE, 0), deed);
        for (int i = 0; i < 9; i++) {
            registry.remember(residentId(HERE, 1), deed);
        }

        Board board = boardOf(registry, PLAYER);
        assertEquals(9, board.witnessed().get(0).repeats());
    }

    @Test
    @DisplayName("the newest thing you did is at the top of the board")
    void historyIsNewestFirst() {
        NpcRegistry registry = village(9);
        for (int day : new int[]{3, 17, 9}) {
            registry.remember(residentId(HERE, 0), fed(day));
        }

        List<Integer> days = new ArrayList<>();
        boardOf(registry, PLAYER).witnessed().forEach(memory -> days.add(memory.day()));
        assertEquals(List.of(17, 9, 3), days);
    }

    // --- where a story came from -------------------------------------------------------------------

    /**
     * <b>Session 10's loose end, closed with no new field.</b>
     *
     * <p>{@code /namesake debug deeds} prints {@code @s0}. A story that crossed a road came from a
     * place, and both halves of saying so were already on disk: the deed carries which settlement it
     * happened in, and the settlement table carries where that is.
     */
    @Test
    @DisplayName("a story that came down the road names the village it came from, and which way")
    void hearsayNamesWhereItCameFrom() {
        NpcRegistry registry = village(9);
        registry.putSettlement(settlement(AWAY, AWAY_BELL));
        registry.put(Persona.create(residentId(AWAY, 0), 0L).placed(AWAY, 0, Culture.KARSK.id()));

        Deed elsewhere = Deed.of(DeedType.FED_HUNGRY, PLAYER, residentId(AWAY, 0), AWAY, 11).retold();
        registry.remember(residentId(HERE, 0), elsewhere);

        Board.Origin origin = boardOf(registry, PLAYER).hearsay().get(0).origin();
        assertFalse(origin.here());
        assertEquals("east", origin.bearing(), "the far village's bell is 600 blocks along +X");
        assertEquals(Names.ofSettlement(Culture.KARSK, AWAY_BELL.getX(), AWAY_BELL.getZ()),
                origin.place());
        assertTrue(origin.hasPlace());
    }

    /**
     * A place that is no longer in the settlement table keeps what can still be known about it and
     * loses what cannot. Nothing is invented — this mod's one rule about detail, at a third site.
     */
    @Test
    @DisplayName("a story from a place that is gone still says it came from somewhere else")
    void aVanishedPlaceLosesItsNameAndNotItsExistence() {
        NpcRegistry registry = village(9);
        Deed elsewhere =
                Deed.of(DeedType.FED_HUNGRY, PLAYER, residentId(HERE, 0), VANISHED, 11).retold();
        registry.remember(residentId(HERE, 0), elsewhere);

        Board.Origin origin = boardOf(registry, PLAYER).hearsay().get(0).origin();
        assertFalse(origin.here());
        assertFalse(origin.hasPlace());
        assertEquals("from somewhere else", BoardText.source(origin));
    }

    @Test
    @DisplayName("something that happened here says here, rather than naming the village you are in")
    void whatHappenedHereSaysHere() {
        NpcRegistry registry = village(9);
        registry.remember(residentId(HERE, 0), fed(12));
        assertEquals(Board.Origin.HERE, boardOf(registry, PLAYER).witnessed().get(0).origin());
    }

    // --- the direction arithmetic ------------------------------------------------------------------

    /**
     * <b>Enumerated rather than sampled.</b> Eight bearings, eight answers, each checked against the
     * axis convention rather than against whatever the first one produced.
     */
    @Test
    @DisplayName("all eight points of the compass are reachable and none of them is the wrong one")
    void everyBearingIsItself() {
        BlockPos here = new BlockPos(0, 64, 0);
        // Minecraft's axes: +X is east, +Z is south.
        assertEquals("north", Board.bearing(here, new BlockPos(0, 64, -500)));
        assertEquals("north-east", Board.bearing(here, new BlockPos(500, 64, -500)));
        assertEquals("east", Board.bearing(here, new BlockPos(500, 64, 0)));
        assertEquals("south-east", Board.bearing(here, new BlockPos(500, 64, 500)));
        assertEquals("south", Board.bearing(here, new BlockPos(0, 64, 500)));
        assertEquals("south-west", Board.bearing(here, new BlockPos(-500, 64, 500)));
        assertEquals("west", Board.bearing(here, new BlockPos(-500, 64, 0)));
        assertEquals("north-west", Board.bearing(here, new BlockPos(-500, 64, -500)));

        Set<String> distinct = new LinkedHashSet<>(List.of(Board.COMPASS));
        assertEquals(8, distinct.size(), "two points of the compass share a word");
    }

    /**
     * The half-sector offset, which is the one line of this arithmetic that can be wrong while every
     * cardinal direction still looks right.
     *
     * <p>Without it each name covers the forty-five degrees <i>after</i> it rather than the
     * forty-five centred on it, so a village a few blocks south of due east reads as "south-east" —
     * and the four diagonals would still pass the test above.
     */
    @Test
    @DisplayName("a village barely south of due east is still east")
    void aBearingCoversTheSectorCentredOnIt() {
        BlockPos here = new BlockPos(0, 64, 0);
        assertEquals("east", Board.bearing(here, new BlockPos(500, 64, 40)));
        assertEquals("east", Board.bearing(here, new BlockPos(500, 64, -40)));
        assertEquals("north", Board.bearing(here, new BlockPos(40, 64, -500)));
        assertEquals("north", Board.bearing(here, new BlockPos(-40, 64, -500)));
    }

    @Test
    @DisplayName("two bells on one column are not a direction")
    void noDistanceIsNoBearing() {
        assertEquals("", Board.bearing(new BlockPos(4, 64, 4), new BlockPos(4, 90, 4)));
    }

    // --- the name of a place ----------------------------------------------------------------------

    /**
     * <b>Derived, and that is what keeps {@code DESIGN.md} §1 out of it entirely.</b>
     *
     * <p>A settlement name whose only reader is a display would be the shape §1 forbids if it were
     * persisted. It is not persisted, so there is nothing to classify — the standing {@link Names}
     * has held since session 03, and this test is the guard: add a name field to {@code Settlement}
     * and it goes red, saying why.
     */
    @Test
    @DisplayName("a settlement stores no name, because a name is derived from its bell and its people")
    void aPlaceNameIsNeverPersisted() {
        List<String> fields = new ArrayList<>();
        for (RecordComponent component : Settlement.class.getRecordComponents()) {
            fields.add(component.getName());
        }
        assertEquals(List.of("id", "dimension", "centre", "specialty", "defensibility", "needs"),
                fields, "Settlement grew a field. If it is a name, derive it instead: Names is a "
                        + "total function of the bell and the culture, costs zero bytes and zero "
                        + "schema, and cannot drift from the settlement it describes. DESIGN.md §1.");
    }

    @Test
    @DisplayName("the same bell always names the same place, and a different bell names a different one")
    void aPlaceNameIsStable() {
        String once = Names.ofSettlement(Culture.VALE, 128, -64);
        assertEquals(once, Names.ofSettlement(Culture.VALE, 128, -64));
        assertNotEquals(once, Names.ofSettlement(Culture.VALE, -64, 128),
                "two coordinates swapped are two different places and must not share a name");

        int distinct = new LinkedHashSet<>(
                java.util.stream.IntStream.range(0, 200)
                        .mapToObj(i -> Names.ofSettlement(Culture.VALE, i * 512, 0))
                        .toList()).size();
        assertTrue(distinct > 150, () -> "200 villages produced only " + distinct + " names");
    }

    @Test
    @DisplayName("a village is named in the tongue of the people who live in it")
    void aPlaceIsNamedInItsOwnCulture() {
        for (Culture culture : Culture.values()) {
            String name = Names.ofSettlement(culture, 4096, 4096);
            assertFalse(name.isEmpty());
            assertTrue(Character.isUpperCase(name.charAt(0)), () -> culture + " gave " + name);
            assertTrue(List.of(culture.grammar().familySuffixes()).stream()
                            .anyMatch(suffix -> name.toLowerCase(java.util.Locale.ROOT)
                                    .endsWith(suffix.toLowerCase(java.util.Locale.ROOT))),
                    () -> culture + " named a place " + name + ", which is not built from its own "
                            + "place words - a family suffix is a place word in every one of these "
                            + "grammars, which is why ofSettlement reuses them");
        }
    }

    @Test
    @DisplayName("a village nobody lives in has no name, and the board says so rather than inventing one")
    void anEmptyVillageIsUnnamed() {
        NpcRegistry registry = new NpcRegistry();
        registry.putSettlement(settlement(HERE, HERE_BELL));

        Board board = boardOf(registry, PLAYER);
        assertFalse(board.named());
        assertEquals("", board.place());
        assertEquals(0, board.residents());
    }

    // --- the standing, and whose answer it is ------------------------------------------------------

    /**
     * <b>The composition this session opens on, held as a test rather than as an intention.</b>
     *
     * <p>{@code DESIGN.md} §2 rules <i>bands, never raw integers</i>, and the five bands are session
     * 12's. The board therefore names a standing it does not own: it asks {@code Dialogue.poolFor} —
     * the same call {@code Dialogue.speak} makes — so the board and the villager cannot disagree.
     *
     * <p>This is the assertion that makes that real rather than incidental. The boundary is expressed
     * as {@code Standing.WARM_WARMTH} rather than as the number twenty, so <b>session 12 moving the
     * threshold moves this test with it</b>; and a board that grew a threshold of its own would go red
     * here the first time the two differed.
     */
    @Test
    @DisplayName("the board's boundary between warm and known is the villager's boundary")
    void theBoardAndTheVillagerShareOneAnswer() {
        NpcRegistry justUnder = village(9);
        justUnder.putBond(residentId(HERE, 0), PLAYER, warmth(Standing.WARM_WARMTH - 1));
        NpcRegistry exactly = village(9);
        exactly.putBond(residentId(HERE, 0), PLAYER, warmth(Standing.WARM_WARMTH));

        assertEquals(Pool.KNOWN, boardOf(justUnder, PLAYER).opinions().get(0).pool());
        assertEquals(Pool.WARM, boardOf(exactly, PLAYER).opinions().get(0).pool());
    }

    @Test
    @DisplayName("somebody you have never met is not on the board, and is counted instead")
    void strangersAreCountedRatherThanListed() {
        NpcRegistry registry = village(9);
        registry.putBond(residentId(HERE, 0), PLAYER, warmth(5));

        Board board = boardOf(registry, PLAYER);
        assertEquals(1, board.opinions().size());
        assertEquals(8, board.strangers());
    }

    /**
     * A villager whose allowance was spent when you fed them holds a bond of nothing and a ring that
     * remembers you. Session 06 proved those diverge — <i>the gift moved 0 bonds and was remembered
     * by 4 people regardless</i> — and {@code Dialogue.poolFor} has read both ever since.
     */
    @Test
    @DisplayName("somebody who remembers you has an opinion even when every axis rounded to zero")
    void rememberingYouIsHavingAnOpinion() {
        NpcRegistry registry = village(9);
        registry.remember(residentId(HERE, 0), fed(12));

        Board board = boardOf(registry, PLAYER);
        assertEquals(1, board.opinions().size());
        assertEquals(Pool.KNOWN, board.opinions().get(0).pool());
    }

    /** Somebody wary of you is the one row worth reading, so it is not buried under eight others. */
    @Test
    @DisplayName("the people with something to say are at the top")
    void opinionsAreSortedByHowMuchTheyMatter() {
        NpcRegistry registry = village(9);
        registry.putBond(residentId(HERE, 0), PLAYER, warmth(5));
        registry.putBond(residentId(HERE, 1), PLAYER, warmth(Standing.WARM_WARMTH));
        registry.putBond(residentId(HERE, 2), PLAYER,
                new Bond((byte) -20, (byte) 0, (byte) 0, (short) 0, TODAY, (short) 0,
                        (byte) 0));

        List<Pool> pools = new ArrayList<>();
        boardOf(registry, PLAYER).opinions().forEach(n -> pools.add(n.pool()));
        assertEquals(List.of(Pool.HOSTILE, Pool.WARM, Pool.KNOWN), pools);
    }

    /**
     * A bond holding this much warmth, <b>last touched on the day the board is read</b>.
     *
     * <p>The day matters and the first version of this fixture left it at zero, which put twenty days
     * of decay between the bond being written and the board asking about it — so a fixture asking for
     * exactly {@code WARM_WARMTH} arrived at the board holding eight. Both tests using it went red on
     * the real code, which is the right way round: session 09 found the same class of defect in three
     * bond fixtures capped at fifteen, and its note is the one that applies here — <b>had the
     * threshold been lower they would have been green and hollow.</b>
     */
    private static Bond warmth(int warmth) {
        return new Bond((byte) 0, (byte) warmth, (byte) 0, (short) 0, TODAY, (short) 0,
                (byte) warmth);
    }
}
