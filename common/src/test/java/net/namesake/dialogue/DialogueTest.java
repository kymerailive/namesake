package net.namesake.dialogue;

import net.namesake.culture.Culture;
import net.namesake.npc.NpcRegistry;
import net.namesake.npc.Persona;
import net.namesake.social.Bond;
import net.namesake.social.Deed;
import net.namesake.social.DeedType;
import net.namesake.social.Residency;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>What is machine-checkable about a hundred and sixty authored lines, and what is not.</b>
 *
 * <p>Whether they are any <i>good</i> is the owner's, and {@code WORKPLAN.md} says so. Four things
 * about them are not opinions, and this project has shipped the last one six times:
 *
 * <ol>
 *   <li>every pool prints its own absence;</li>
 *   <li>the stranger pool explicitly says it does not know you;</li>
 *   <li>the per-culture tics actually differ between cultures rather than differing in a comment;</li>
 *   <li>every line fits the chat width in every state it can be in — which lives in
 *       {@code CommandLayoutTest} with the other twelve enumerated command states, because a fifth
 *       guard that samples one state is how the first four got shipped.</li>
 * </ol>
 */
class DialogueTest {

    private static final UUID PLAYER = new UUID(0x5EAF_0000_0000_0001L, 9);
    private static final UUID ANNA = new UUID(77, 0);
    private static final int VILLAGE = 0;

    private static Persona resident(int index, Culture culture) {
        return Persona.create(new UUID(77, index), 0L).placed(VILLAGE, index / 3, culture.id());
    }

    private static NpcRegistry village(int count, Culture culture) {
        NpcRegistry registry = new NpcRegistry();
        for (int i = 0; i < count; i++) {
            registry.put(resident(i, culture));
        }
        return registry;
    }

    /**
     * A bond built directly rather than through {@code apply}.
     *
     * <p>{@code Bond.apply} clamps its allowance to what the four-bit {@code gainedToday} counters
     * can hold, so a fixture asking for twenty warmth in one call quietly gets fifteen — which is a
     * fixture that tests a threshold it never reaches, and is exactly the class of false green this
     * project keeps finding. The states below are ones a real bond reaches over several in-game days;
     * this constructs the end state rather than simulating the days.
     */
    private static Bond bond(int trust, int warmth) {
        return new Bond((byte) trust, (byte) warmth, (byte) 0, (byte) 0, (short) 0, 0, (short) 0,
                (byte) warmth);
    }

    // --- the shape of the pools --------------------------------------------------------------------

    @Test
    @DisplayName("the pools carry the hundred and sixty lines the ledger asks for")
    void theLinesAreAllThere() {
        assertEquals(Pool.values().length * Register.values().length * Lines.PER_REGISTER,
                Lines.count());
        assertEquals(160, Lines.count(), "WORKPLAN.md: four pools x five registers x ~8 lines");

        for (Pool pool : Pool.values()) {
            for (Register register : Register.values()) {
                assertEquals(Lines.PER_REGISTER, Lines.of(pool, register).size(),
                        () -> pool + " " + register + " is short of lines");
            }
        }
    }

    /**
     * <b>Every pool prints its own absence.</b> {@code DESIGN.md} §11's rule, applied to dialogue:
     * every one of the twenty (pool, register) cells has something to say, so no combination of bond
     * state and ring state can produce a villager who is silently mute. A mute villager reads to a
     * player as a broken mod, and a broken mod is what they will report instead of the thing that is
     * actually wrong.
     */
    @Test
    @DisplayName("no combination of pool and register can leave a villager with nothing to say")
    void everyPoolPrintsItsOwnAbsence() {
        for (Pool pool : Pool.values()) {
            for (Register register : Register.values()) {
                for (String line : Lines.of(pool, register)) {
                    assertFalse(line.isBlank(),
                            () -> pool + " " + register + " has a blank line in it");
                    assertTrue(line.length() > 2, () -> "'" + line + "' is not a sentence");
                }
            }
        }
    }

    /**
     * <b>{@code WORKPLAN.md}'s rule for this session, and it is a rule rather than a preference.</b>
     *
     * <p><i>The stranger pool must explicitly say it does not know you. Silence reads as permission;
     * a gap has to be authored as a negative.</i> Small talk is exempt and deliberately so — it is
     * about the weather rather than about you — but everything the stranger pool says <i>about the
     * player</i> has to carry a negative in so many words.
     */
    @Test
    @DisplayName("every stranger line about you says, in words, that they do not know you")
    void theStrangerPoolIsAuthoredAsANegative() {
        List<String> negatives = List.of("don't", "not ", "nothing", "no one", "nobody", "none",
                "hasn't", "isn't", "aren't", "couldn't", "new", "n't", "no ");

        for (Register register : List.of(Register.GREETING, Register.ABOUT_YOU,
                Register.ABOUT_OTHERS)) {
            for (String line : Lines.of(Pool.STRANGER, register)) {
                String lower = line.toLowerCase(Locale.ROOT);
                assertTrue(negatives.stream().anyMatch(lower::contains), () -> """
                        A stranger line has to say it does not know you, in words: "%s" (%s). \
                        Silence reads as permission, so a gap has to be authored as a negative. \
                        WORKPLAN.md, session 09.""".formatted(line, register));
            }
        }
    }

    // --- the cultures ------------------------------------------------------------------------------

    /**
     * <b>Standing risk 3, at the level this session can touch it.</b> <i>If settlement two sounds and
     * behaves like settlement one, the travel loop collapses around hour 45.</i> Session 03 made the
     * names differ and the owner's playtest ruled that it landed; a culture that differs only in what
     * its people are called is one a player stops noticing.
     */
    @Test
    @DisplayName("no two cultures share an opener, a tag question or a word for a stranger")
    void theCulturesDoNotShareTics() {
        Set<String> openers = new LinkedHashSet<>();
        Set<String> tags = new LinkedHashSet<>();
        Set<String> addresses = new LinkedHashSet<>();
        Set<Float> formalities = new LinkedHashSet<>();

        for (Culture culture : Culture.values()) {
            Voice voice = Voice.of(culture);
            assertTrue(openers.add(voice.opener()),
                    () -> culture + " shares an opener with another culture: " + voice.opener());
            assertTrue(tags.add(voice.tag()),
                    () -> culture + " shares a tag question: " + voice.tag());
            assertTrue(addresses.add(voice.strangerAddress()),
                    () -> culture + " calls a stranger the same thing another culture does");
            formalities.add(voice.formality());
        }
        assertTrue(formalities.size() >= 5,
                "formality is a per-culture number, not a per-culture label — if five of six share "
                        + "one, it is not doing anything");
    }

    /**
     * <b>The tics differ in the rendered sentence, not only in the table.</b>
     *
     * <p>Session 03's lesson, and the reason it is worth restating: a name space that <i>counted</i>
     * twelve million and <i>behaved</i> like a hundred and thirty thousand passed every check that
     * read the table. So this measures what the six cultures actually say.
     */
    @Test
    @DisplayName("the same line comes out audibly different in six cultures")
    void theSameLineSoundsDifferentInEachCulture() {
        String template = "I've not seen you before, {you}.";
        Set<String> rendered = new LinkedHashSet<>();
        for (Culture culture : Culture.values()) {
            Voice voice = Voice.of(culture);
            String line = template.replace(Lines.ADDRESS, voice.strangerAddress());
            // Both coins forced, so this measures the tics rather than the hash.
            rendered.add(voice.opener() + " "
                    + line.substring(0, line.length() - 1) + ", " + voice.tag());
        }
        assertEquals(Culture.values().length, rendered.size(),
                "six cultures must produce six sentences, or the tics are decoration");
    }

    /**
     * <b>Formality is a mechanism rather than a label, and this is the measurement that says so.</b>
     *
     * <p>A formal culture opens and rarely tags; an informal one tags and rarely opens. Measured over
     * a thousand seeds so it is the realised rate rather than the constant that is under test.
     */
    @Test
    @DisplayName("a formal culture opens more often than an informal one, measurably")
    void formalityChangesHowOftenATicAppears() {
        int formal = 0;
        int informal = 0;
        Voice talqir = Voice.of(Culture.TALQIR);
        Voice meridian = Voice.of(Culture.MERIDIAN);
        assertTrue(talqir.formality() > meridian.formality(), "the fixture has the right pair");

        for (long seed = 0; seed < 1000; seed++) {
            if (talqir.inflect("A quiet place, mostly.", seed).startsWith(talqir.opener())) {
                formal++;
            }
            if (meridian.inflect("A quiet place, mostly.", seed).startsWith(meridian.opener())) {
                informal++;
            }
        }
        int formalOpens = formal;
        int informalOpens = informal;
        assertTrue(formalOpens > informalOpens * 2,
                () -> "Tal-Qir opened " + formalOpens + " of a thousand and Meridian "
                        + informalOpens + ", which is not a difference a player would hear");

        // And the other way round for the tag, which is what makes it a bias rather than a volume.
        int talqirTags = 0;
        int meridianTags = 0;
        for (long seed = 0; seed < 1000; seed++) {
            if (talqir.inflect("A quiet place, mostly.", seed).endsWith(talqir.tag())) {
                talqirTags++;
            }
            if (meridian.inflect("A quiet place, mostly.", seed).endsWith(meridian.tag())) {
                meridianTags++;
            }
        }
        int formalTags = talqirTags;
        int informalTags = meridianTags;
        assertTrue(informalTags > formalTags * 2, () -> "Meridian tagged " + informalTags
                + " and Tal-Qir " + formalTags);
    }

    @Test
    @DisplayName("a tag question is never hung off a question or an exclamation")
    void aTagOnlyAttachesToAStatement() {
        for (Culture culture : Culture.values()) {
            Voice voice = Voice.of(culture);
            for (long seed = 0; seed < 200; seed++) {
                assertFalse(voice.inflect("What do you want?", seed).endsWith(voice.tag()),
                        () -> culture + " hung " + voice.tag() + " off a question");
                assertFalse(voice.inflect("You'll be missed!", seed).endsWith(voice.tag()),
                        () -> culture + " hung " + voice.tag() + " off an exclamation");
            }
        }
    }

    // --- selection ---------------------------------------------------------------------------------

    @Test
    @DisplayName("a bond nobody has moved puts you in the stranger pool")
    void poolSelectionStartsAtStranger() {
        assertEquals(Pool.STRANGER, Dialogue.poolFor(Bond.fresh(0), false));
        assertEquals(Pool.KNOWN, Dialogue.poolFor(bond(2, 0), false));
        assertEquals(Pool.WARM, Dialogue.poolFor(bond(0, Dialogue.WARM_WARMTH), false));
        assertEquals(Pool.KNOWN, Dialogue.poolFor(bond(0, Dialogue.WARM_WARMTH - 1), false));
        assertEquals(Pool.HOSTILE, Dialogue.poolFor(bond(-1, 0), false));
    }

    /**
     * <b>Session 06's asymmetry, arriving in the words.</b> <i>The gift moved 0 bonds — every
     * allowance was spent — and was remembered by 4 people regardless.</i> A villager whose allowance
     * was full when you fed them would otherwise still be greeting you as a stranger, which is the
     * ring and the bond disagreeing in front of the player.
     */
    @Test
    @DisplayName("a villager who remembers you is not a stranger, even if no axis moved")
    void rememberingYouIsEnoughToStopBeingAStranger() {
        assertEquals(Pool.KNOWN, Dialogue.poolFor(Bond.fresh(0), true));
    }

    @Test
    @DisplayName("the first thing said is a greeting and the last is a parting")
    void theConversationHasAShape() {
        assertEquals(Register.GREETING, Dialogue.registerFor(List.of(), PLAYER, 0));
        assertEquals(Register.SMALL_TALK, Dialogue.registerFor(List.of(), PLAYER, 1));
        assertEquals(Register.PARTING,
                Dialogue.registerFor(List.of(), PLAYER, Conversations.TURNS_BEFORE_PARTING));
    }

    /**
     * <b>The thesis, as a branch.</b> A villager who was <i>told</i> about you says so before a
     * villager who watched you does — because the told one is the only sentence in this mod that
     * could not have been said before session 08's drain existed, and {@code DESIGN.md} §10 step 5 is
     * that sentence one settlement further along.
     */
    @Test
    @DisplayName("what they were told comes before what they saw")
    void hearsayIsTheMostInterestingThingTheyHave() {
        Deed watched = Deed.of(DeedType.FED_HUNGRY, PLAYER, ANNA, VILLAGE, 3);
        Deed heard = watched.retold();
        assertTrue(heard.isAttributed(), "the fixture has to still name the actor");

        assertEquals(Register.ABOUT_YOU, Dialogue.registerFor(List.of(watched), PLAYER, 1));
        assertEquals(Register.ABOUT_OTHERS, Dialogue.registerFor(List.of(heard), PLAYER, 1));
        assertEquals(Register.ABOUT_OTHERS,
                Dialogue.registerFor(List.of(watched, heard), PLAYER, 1));
        assertEquals(Register.ABOUT_YOU, Dialogue.registerFor(List.of(watched, heard), PLAYER, 2),
                "and the second thing they say is the other one, so both registers are reachable");
    }

    /**
     * <b>The blur, arriving in the words.</b> A story nobody can attribute carries
     * {@link Deed#UNKNOWN_ACTOR}, so the villager holding it has genuinely nothing to say about
     * <i>you</i>. Session 08 made that an {@code if} in the bond arithmetic; this is the same rule
     * one layer up.
     */
    @Test
    @DisplayName("a rumour nobody can attribute gives a villager nothing to say about you")
    void anUnattributedRumourSelectsNothing() {
        Deed rumour = Deed.of(DeedType.KILLED_RESIDENT, PLAYER, ANNA, VILLAGE, 3).retold().retold();
        assertFalse(rumour.isAttributed(), "the fixture has to actually be blurred");

        assertEquals(Register.SMALL_TALK, Dialogue.registerFor(List.of(rumour), PLAYER, 1));
        assertEquals(Pool.STRANGER, Dialogue.poolFor(Bond.fresh(0),
                Dialogue.remembersThem(List.of(rumour), PLAYER)));
    }

    // --- the name swap -----------------------------------------------------------------------------

    /**
     * <b>{@code DESIGN.md} §5, the whole pitch in one line.</b> <i>Before residency they call you
     * stranger. After, they use your name.</i>
     */
    @Test
    @DisplayName("crossing residency swaps the word they call you for your name")
    void theNameSwap() {
        NpcRegistry registry = village(9, Culture.VALE);
        Persona speaker = registry.persona(ANNA).orElseThrow();
        Voice vale = Voice.of(Culture.VALE);

        assertEquals(vale.strangerAddress(),
                Dialogue.addressFor(registry, speaker, PLAYER, "Kymerailive", 0, vale));

        for (int i = 0; i < Residency.RESIDENTS_REQUIRED; i++) {
            registry.putBond(new UUID(77, i), PLAYER, bond(Residency.TRUST_THRESHOLD, 0));
        }

        assertEquals("Kymerailive",
                Dialogue.addressFor(registry, speaker, PLAYER, "Kymerailive", 0, vale));
    }

    /**
     * <b>And the villager who has never met you still uses it, which is correct rather than a bug.</b>
     *
     * <p>Residency belongs to the settlement, not to one relationship. So a resident of a village
     * that has taken you in uses your name in the very line where they say they do not know you —
     * which is the thesis in miniature: what one villager did changed what a different one calls you.
     */
    @Test
    @DisplayName("a villager who has never met you uses your name once the village has taken you in")
    void theVillageDecidesTheName() {
        NpcRegistry registry = village(9, Culture.VALE);
        for (int i = 0; i < Residency.RESIDENTS_REQUIRED; i++) {
            registry.putBond(new UUID(77, i), PLAYER, bond(Residency.TRUST_THRESHOLD, 0));
        }

        // Resident number eight has no bond and no memory: a total stranger inside a village that
        // knows the player's name.
        Persona neverMet = registry.persona(new UUID(77, 8)).orElseThrow();
        Dialogue.Spoken spoken = Dialogue.speak(registry, neverMet, PLAYER, "Kymerailive",
                0, 0, 20260815L);

        assertEquals(Pool.STRANGER, spoken.pool(), "they personally have not met you");
        assertEquals("Kymerailive", spoken.address(), "and the village has still taken you in");
        assertTrue(spoken.line().contains("Kymerailive")
                        || !Lines.at(Pool.STRANGER, Register.GREETING, 0).contains(Lines.ADDRESS),
                () -> "the address has to reach the sentence: " + spoken.line());
    }

    @Test
    @DisplayName("nothing a villager says still has a template slot in it")
    void everySlotIsFilled() {
        NpcRegistry registry = village(9, Culture.KARSK);
        Persona speaker = registry.persona(ANNA).orElseThrow();
        List<String> said = new ArrayList<>();
        for (int turn = 0; turn <= Conversations.TURNS_BEFORE_PARTING; turn++) {
            for (long seed = 0; seed < 64; seed++) {
                said.add(Dialogue.speak(registry, speaker, PLAYER, "Kymerailive", turn, 0, seed)
                        .line());
            }
        }
        for (String line : said) {
            assertFalse(line.contains(Lines.ADDRESS),
                    () -> "an unfilled template slot reached a player: " + line);
            assertFalse(line.contains("{"), () -> "an unfilled slot of some kind: " + line);
        }
    }

    @Test
    @DisplayName("two villagers do not say the same thing at the same moment")
    void thelinesVaryBetweenSpeakers() {
        NpcRegistry registry = village(9, Culture.MERIDIAN);
        Set<String> said = new LinkedHashSet<>();
        for (int i = 0; i < 9; i++) {
            Persona speaker = registry.persona(new UUID(77, i)).orElseThrow();
            said.add(Dialogue.speak(registry, speaker, PLAYER, "Kymerailive", 0, 0, 5L).line());
        }
        assertTrue(said.size() > 1,
                "nine villagers greeting you with one sentence between them is a village of one");
    }

    @Test
    @DisplayName("the same villager at the same moment says the same thing, so a report reproduces")
    void selectionIsDeterministic() {
        NpcRegistry registry = village(9, Culture.YUN);
        Persona speaker = registry.persona(ANNA).orElseThrow();
        Dialogue.Spoken first = Dialogue.speak(registry, speaker, PLAYER, "Kymerailive", 0, 0, 42L);
        Dialogue.Spoken again = Dialogue.speak(registry, speaker, PLAYER, "Kymerailive", 0, 0, 42L);
        assertEquals(first, again);
    }

    // --- the conversation counter --------------------------------------------------------------------

    @Test
    @DisplayName("turning to a different villager starts a new conversation")
    void theTurnCountResetsPerTarget() {
        Conversations.clear();
        assertEquals(0, Conversations.turn(PLAYER, ANNA));
        assertEquals(1, Conversations.turn(PLAYER, ANNA));
        assertEquals(0, Conversations.turn(PLAYER, new UUID(77, 1)),
                "or the second villager you meet opens with a goodbye");
        assertEquals(1, Conversations.turn(PLAYER, new UUID(77, 1)));

        Conversations.forget(PLAYER);
        assertEquals(0, Conversations.turn(PLAYER, new UUID(77, 1)));
    }

    @Test
    @DisplayName("every register is reachable, so no authored line is dead content")
    void everyRegisterIsSelectable() {
        Deed watched = Deed.of(DeedType.FED_HUNGRY, PLAYER, ANNA, VILLAGE, 3);
        Deed heard = watched.retold();
        Set<Register> reached = new LinkedHashSet<>();
        for (int turn = 0; turn <= Conversations.TURNS_BEFORE_PARTING; turn++) {
            reached.add(Dialogue.registerFor(List.of(), PLAYER, turn));
            reached.add(Dialogue.registerFor(List.of(watched), PLAYER, turn));
            reached.add(Dialogue.registerFor(List.of(watched, heard), PLAYER, turn));
        }
        assertEquals(Register.values().length, reached.size(),
                () -> "an authored register nothing can select is dead content, which is the same "
                        + "failure as a persisted field nothing reads. Unreached: "
                        + java.util.Arrays.stream(Register.values())
                        .filter(register -> !reached.contains(register)).toList());
    }

    @Test
    @DisplayName("the four pools are all reachable from a bond a player can actually produce")
    void everyPoolIsSelectable() {
        Set<Pool> reached = new LinkedHashSet<>();
        reached.add(Dialogue.poolFor(Bond.fresh(0), false));
        reached.add(Dialogue.poolFor(bond(4, 2), false));
        reached.add(Dialogue.poolFor(bond(4, Dialogue.WARM_WARMTH), false));
        reached.add(Dialogue.poolFor(bond(-8, 0), false));
        assertEquals(Pool.values().length, reached.size());
        assertNotEquals(Pool.STRANGER, Dialogue.poolFor(bond(4, 2), false));
    }
}
