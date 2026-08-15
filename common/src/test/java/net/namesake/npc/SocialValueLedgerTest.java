package net.namesake.npc;

import com.mojang.serialization.Codec;
import net.namesake.settlement.Settlement;
import net.namesake.settlement.Settlements;
import net.namesake.social.Bond;
import net.namesake.social.Deed;
import net.namesake.social.DeedBus;
import net.namesake.social.Deeds;
import net.namesake.social.DialogueStats;
import net.namesake.social.GiftPolicy;
import net.namesake.social.Memories;
import net.namesake.social.Personality;
import net.namesake.social.Residency;
import net.namesake.testing.MethodBody;
import net.namesake.testing.ModClasses;
import net.namesake.testing.SessionLedger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>Rule 5, enforced.</b> {@code DESIGN.md} §1:
 *
 * <blockquote>Every social value must have at least one named consumer that is not a display. If
 * you cannot name the {@code if} statement it feeds, delete the field.</blockquote>
 *
 * <p>Both reference codebases died here, in opposite directions. MCA's 12 genes, 22 traits and 15
 * personalities all terminate in a renderer; its only mechanical gene effect is a ±10% walk speed,
 * and 4 of 22 traits are dead code. LNK computes an affinity score for 158 pairs with a second LLM
 * call per reply, and {@code grep affinity prompt.py} returns nothing. Neither team decided to do
 * that. It happened because nothing said no.
 *
 * <p>So this is the thing that says no, and it says it three ways:
 *
 * <ul>
 *   <li><b>Every persisted field must be classified.</b> A new field with no entry below turns the
 *       build red. There is no way to add one without making the decision.</li>
 *   <li><b>A named consumer must be real.</b> The class and method must exist, must not be a
 *       renderer or a debug command, and — read out of the compiled bytecode — must actually read
 *       the field. Naming a method that merely exists is how a ledger becomes fiction.</li>
 *   <li><b>An exemption expires.</b> Each one names the session that owes it a consumer, and is
 *       compared against {@code WORKPLAN.md}'s own status board. Nobody has to remember.</li>
 * </ul>
 *
 * <p><b>{@code Persona.traits} is the entry this file was built around, and session 05 paid it.</b>
 * The exemption was ruled in the session 01 log and expired at the close of 05; the consumer that
 * pays it is {@code Personality.scale}, the static {@code float[8][6]} weight table. Had it not
 * landed, this test would have turned the build red by itself at the moment the status board moved
 * to session 06 — which is the whole design: nobody has to remember.
 *
 * <p><b>And session 05 opened five more, which is worth stating rather than burying.</b> A bond's
 * four axes and its debt scalar are written here and read by sessions 09, 12 and 16. The temptation
 * was to name the arithmetic that writes them, which would have passed every check in this file and
 * been the same lie {@code cultureId} told in session 03. See the notes on {@code Bond} below.
 *
 * <p><b>Session 03 is the first time the mechanism fired at anyone.</b> Three exemptions —
 * {@code settlementId}, {@code householdId} and {@code cultureId} — expired at the close of that
 * session, and all three now name {@code TraitRoll}. One of them did not land where its own
 * exemption predicted: the note against {@code cultureId} said the syllable grammar would consume
 * it, and the grammar turns out to be a display. Moving the date would have been the one thing
 * this file exists to stop, so the honest answer was a different consumer.
 */
class SocialValueLedgerTest {

    // --- the ledger -------------------------------------------------------------------------------

    private enum Kind {
        /** Describes a person, and therefore owes an {@code if} statement. */
        SOCIAL,
        /** Identifies a record rather than describing a person. */
        IDENTITY,
        /** Exists to be drawn, and is ruled as such in DESIGN.md. */
        PRESENTATION
    }

    private record Entry(Class<?> owner, String field, Kind kind, List<String> accessors,
                         Class<?> consumerClass, String consumerMethod,
                         int exemptAfterSession, String note) {

        static Entry consumedBy(Class<?> owner, String field, List<String> accessors,
                                Class<?> consumerClass, String consumerMethod, String note) {
            return new Entry(owner, field, Kind.SOCIAL, accessors, consumerClass, consumerMethod, -1, note);
        }

        static Entry exemptUntilAfter(Class<?> owner, String field, List<String> accessors,
                                      int session, String note) {
            return new Entry(owner, field, Kind.SOCIAL, accessors, null, null, session, note);
        }

        static Entry identity(Class<?> owner, String field, String note) {
            return new Entry(owner, field, Kind.IDENTITY, List.of(), null, null, -1, note);
        }

        static Entry presentation(Class<?> owner, String field, String note) {
            return new Entry(owner, field, Kind.PRESENTATION, List.of(), null, null, -1, note);
        }

        boolean hasConsumer() {
            return consumerClass != null;
        }

        boolean isExempt() {
            return exemptAfterSession >= 0;
        }

        String describe() {
            return owner.getSimpleName() + "." + field;
        }
    }

    /**
     * The ledger. One line per persisted field, and the build will not go green without one.
     *
     * <p>Only {@code traits} was ruled in the session 01 log; the other expiry sessions are read
     * off {@code WORKPLAN.md}'s own sequence and recorded in the session 02 log so they can be
     * ruled differently.
     */
    private static final List<Entry> LEDGER = List.of(

            Entry.identity(Persona.class, "id",
                    "The persona's identity. The registry is keyed by it and every binding points "
                            + "at it; it describes nobody."),

            // Paid off in session 03. The exemption said the three-layer roll would branch on it,
            // and it does: this id chooses which settlement's mean the roll starts from, and a
            // persona with no settlement starts from its culture alone.
            Entry.consumedBy(Persona.class, "settlementId", List.of("settlementId"),
                    TraitRoll.class, "roll",
                    "TraitRoll.roll looks the settlement up by this id to find the mean it rolls "
                            + "about. Change it and different numbers come out."),

            // Paid off in session 03, and this is the one that carries the exit criterion: every
            // member of a household draws the same middle layer, which is what makes a family
            // resemble each other rather than resembling the village average.
            Entry.consumedBy(Persona.class, "householdId", List.of("householdId"),
                    TraitRoll.class, "roll",
                    "TraitRoll.roll seeds the household layer (±20 about the settlement mean) "
                            + "from this and nothing else, so households cluster."),

            // Paid off in session 05, and the reason this test exists at all. The exemption was
            // ruled in the session 01 log, carried through 02, 03 and 04, and expired at the close
            // of this session. It landed exactly where it said it would: the static float[8][6]
            // weight table reads all eight axes and multiplies what a deed is worth by them.
            Entry.consumedBy(Persona.class, "traits", List.of("traits", "trait", "withTrait"),
                    Personality.class, "scale",
                    "Personality.scale reads all eight axes to decide what a deed is worth to this "
                            + "person. The same loaf is +2 warmth to a suspicious smith and +5 to a "
                            + "warm innkeeper. Standing risk 4, paid."),

            // Paid off in session 03 — but NOT by the thing the exemption predicted. The exemption
            // named the syllable grammar, and the grammar turns a culture id into a string that is
            // shown to a person, which is a display however non-display the package it lives in
            // happens to be. Naming it here would have been the technicality this whole file
            // exists to refuse. The real consumer is the roll: a culture supplies the baseline
            // eight axes start from, and its conformity decides how far individuals are allowed to
            // stray from their household. Session 09's pool and register selection is the second.
            Entry.consumedBy(Persona.class, "cultureId", List.of("cultureId"),
                    TraitRoll.class, "roll",
                    "TraitRoll.roll reads it twice: Culture.traitBase is where the settlement mean "
                            + "starts, and Culture.conformity narrows the ±25 individual layer. A "
                            + "Tal-Qir household produces people who resemble each other; a "
                            + "Meridian one does not."),

            Entry.exemptUntilAfter(Persona.class, "professionId", List.of("professionId"), 12,
                    "Session 12: whether one recipe is taught. The longest exemption here, and the "
                            + "weakest — this field duplicates what vanilla already stores on the "
                            + "villager. If session 12 does not read it, delete it rather than "
                            + "moving this number."),

            Entry.consumedBy(Persona.class, "birthTick", List.of("birthTick"),
                    PersonaService.class, "reapStrayMint",
                    "Reaping a stray mint after a zombie cure requires the persona to have been "
                            + "born this tick. Without that condition the reaper would delete an "
                            + "unrelated persona that happened to be bound to the same entity."),

            Entry.presentation(Persona.class, "appearanceSeed",
                    "Ruled presentation in DESIGN.md §9: appearance is a pure function of the "
                            + "persona id, and the seed is persisted only so existing villagers "
                            + "keep the faces players already know if the derivation changes."),

            Entry.exemptUntilAfter(Persona.class, "eraOfMajority", List.of("eraOfMajority"), 24,
                    "Sessions 24-27, the era ladder: offices and charters gate on the era an NPC "
                            + "came of age in. Outside the 16-session slice, so this exemption is "
                            + "long by design — but it still expires, and it moves with the era "
                            + "ladder rather than with anyone's memory."),

            // --- Settlement, new in session 03 -------------------------------------------------
            //
            // Six fields and six consumers, with no exemptions at all. That is not restraint, it
            // is what happens when the rule is applied while the record is being written rather
            // than afterwards: a seventh field held the settlement's culture, and deleting it was
            // easier than justifying it, because culture is a pure function of the world seed and
            // the biome at the bell. See the note on Settlement itself.

            Entry.identity(Settlement.class, "id",
                    "The settlement's identity. Personas reference it and the table is keyed by "
                            + "it; it describes nothing about the place."),

            Entry.consumedBy(Settlement.class, "dimension", List.of("dimension"),
                    Settlements.class, "containing",
                    "Settlements.containing refuses a settlement in another dimension before it "
                            + "measures anything. A bell in the Nether is not this village's bell."),

            Entry.consumedBy(Settlement.class, "centre", List.of("centre"),
                    Settlements.class, "containing",
                    "Settlements.containing decides residency by distance from the centre, which "
                            + "is what makes a villager a resident of one settlement rather than "
                            + "another. TraitRoll.settlementMean reads it again, to give each "
                            + "settlement a character that belongs to the place rather than to "
                            + "the order it was discovered in."),

            Entry.consumedBy(Settlement.class, "specialty", List.of("specialty", "specialtyValue"),
                    TraitRoll.class, "settlementMean",
                    "The trade a settlement lives by shifts eight axes before household and "
                            + "individual variation: a smithing town raises quicker tempers than a "
                            + "farming town of the same culture."),

            Entry.consumedBy(Settlement.class, "defensibility", List.of("defensibility"),
                    TraitRoll.class, "settlementMean",
                    "Exposure — a hundred minus this — lowers boldness and sociability and raises "
                            + "temper. A settlement nobody can defend raises warier people."),

            Entry.consumedBy(Settlement.class, "needs", List.of("needs", "need"),
                    TraitRoll.class, "settlementMean",
                    "Each of the four needs moves an axis: hunger raises acquisitiveness and "
                            + "lowers warmth, missing tools raise industry, crowding raises "
                            + "temper. This is the whole reason the survey is worth running."),

            // --- Deed, new in session 05, persisted from session 06 ----------------------------
            //
            // Seven fields, seven consumers, no exemptions — and no eighth field, which is the
            // session 06 decision worth recording here rather than only in the ledger.
            //
            // Session 06 dedupes a ring on (npcUuid, deedId), and the obvious way to get a deedId is
            // to add one: a counter or a random UUID, persisted, whose named consumer would be the
            // dedupe. That is a legitimate shape — Bond.gainedToday is exactly it, bookkeeping
            // consumed by the rule it implements. It was not taken. Deed.id() is *derived* from the
            // six fields above, so there is no new persisted value at all and nothing for this
            // ledger to classify; see Deed.id() for why content addressing rather than assignment,
            // and what it costs at session 08.
            //
            // The trap that shape would have walked into is worth naming even though it was avoided,
            // because the next session to write a record will meet it: this test proves a consumer
            // by reading its bytecode for a *call*, and a record touching its own field compiles to
            // a getfield no such check can see. Bond's three bookkeeping fields read themselves
            // through their accessors for precisely that reason. An invariant enforced by bytecode
            // has a shape, and code has to be written to fit it.
            //
            // Deed was ledgered by name from session 05, before it declared a codec, because a
            // record carrying social meaning through the pipeline is held to rule 5 whether or not
            // it has reached a save file — or the rule would be about serialisation rather than
            // about consequence. It now declares one, so everyPersistedRecordIsLedgered finds it on
            // its own and the entries below are load-bearing twice over.

            Entry.consumedBy(Deed.class, "typeId", List.of("typeId", "type"),
                    Deeds.class, "deltaFor",
                    "Chooses the four nominal deltas and the column of the personality weight "
                            + "table. Everything a deed is worth begins here."),

            Entry.consumedBy(Deed.class, "actor", List.of("actor"),
                    DeedBus.class, "applyTo",
                    "The subject of the bond that moves. A deed is a thing somebody did, and the "
                            + "bond is about whoever did it. Session 08 gave it a second value it "
                            + "can hold — Deed.UNKNOWN_ACTOR, for a story nobody can attribute — "
                            + "and that is why NpcRegistry.putBond refuses one."),

            Entry.consumedBy(Deed.class, "subject", List.of("subject"),
                    Deeds.class, "deltaFor",
                    "Decides whether this person takes the whole share or a witness's fraction of "
                            + "it, by comparing their persona id against it. Read off the deed "
                            + "rather than passed in, so a caller cannot get it wrong."),

            Entry.consumedBy(Deed.class, "settlementId", List.of("settlementId"),
                    Deeds.class, "deltaFor",
                    "A witness who is not a resident of the settlement the deed happened in "
                            + "records it at three quarters weight. It is not their village."),

            Entry.consumedBy(Deed.class, "gameDay", List.of("gameDay"),
                    DeedBus.class, "applyTo",
                    "The day the bond is brought up to before the delta is spent — which resets "
                            + "the daily allowance if the day has turned, and applies the decay if "
                            + "it has turned several times."),

            Entry.consumedBy(Deed.class, "severity", List.of("severity"),
                    Deeds.class, "deltaFor",
                    "Scales the whole delta. A blow for two hearts costs a quarter of what a blow "
                            + "for eight does; SocialEvents.severityOf is what writes it."),

            // Two consumers from session 08, and the second is the interesting one. deltaFor
            // scales the whole delta by it, so a story heard at second hand moves a bond less than
            // having watched it. And Deed.isAttributed reads it to decide whether the holder can
            // name who did it at all — which DeedBus.deliver branches on to move no bond whatsoever,
            // and Gossip.drain branches on to stop passing the story on. One field, three `if`
            // statements, and the blur is a mechanic rather than a caption because of it.
            Entry.consumedBy(Deed.class, "confidence", List.of("confidence", "isAttributed"),
                    Deeds.class, "deltaFor",
                    "Scales the whole delta, so a story heard at second hand moves a bond less "
                            + "than having watched it. Session 08 added a second reader: below "
                            + "Deed.ATTRIBUTED the holder cannot name the actor, which stops the "
                            + "bond moving at all and stops the story travelling."),

            // Session 09's "richer per memory", and the one field of the three this session added
            // that gets an exemption rather than a consumer. That is the honest answer and it is
            // written out here rather than resolved with a technicality, because the technicality
            // was available and would have passed every check in this file.
            //
            // What reads "which item" today is a line of dialogue — you gave me bread when I was
            // hungry — and net.namesake.dialogue is in DISPLAY_PACKAGES below precisely so that
            // cannot be named. Three non-display readers were built and rejected:
            //
            //   * Deed.id(). Adding the item to the derivation makes a loaf and an apple two
            //     memories, which sounds exactly like what "richer per memory" means and hands the
            //     ring back its grindability through a door the daily cap does not watch: an
            //     afternoon of eight different junk items would be eight entries where an afternoon
            //     of one gift is one. Session 06 built the whole ring around that not being true.
            //   * A gift-acceptance rule — a villager refusing a fourth of the same object. It is a
            //     real `if`, and it is bypassed by alternating two objects, because a slot that
            //     collects two objects honestly names neither (Memories.Slot.again). A guard whose
            //     bypass is that trivial is a consumer on paper.
            //   * Session 12's trade band, built early. That is this exemption, taken two sessions
            //     ahead of the session that owns it, and it would perturb every earn-rate number
            //     sessions 07 and 08 measured.
            //
            // So it is an exemption with a date, which is what exemptions are for. Session 12 either
            // reads it or the field is deleted; moving this number is the thing this file exists to
            // stop.
            Entry.exemptUntilAfter(Deed.class, "item", List.of("item"), 12,
                    "Session 12, the trade band: a villager given the thing they actually wanted is "
                            + "DESIGN.md §12's 'whether one recipe is taught'. Every non-display "
                            + "reader available at session 09 was either the deed id — which would "
                            + "make the ring grindable — or a gate rule with a one-item bypass, so "
                            + "this is an exemption rather than a technicality. If session 12 does "
                            + "not read it, delete it rather than moving this number."),

            // --- Bond, new in session 05 -------------------------------------------------------
            //
            // Five exemptions, and that is the honest count rather than a comfortable one. Session
            // 03 shipped Settlement with none, because every one of its fields was read by the trait
            // roll in the same session. A bond's four axes are not like that: they are written here
            // and read by sessions 09 and 12, and the only thing in this session that touches them
            // is the arithmetic that writes them.
            //
            // Naming that arithmetic would have passed this test and been a lie of exactly the shape
            // session 03 caught with cultureId — a consumer that exists, is not a display, reads the
            // field, and is still not a consumer, because it is the writer looking at its own work.
            // A number whose only reader is the thing that wrote it has no output.
            //
            // The three bookkeeping fields below are a different case and are consumed for real.
            // gainedToday, lastSeenDay and peakWarmth exist to be read by the cap and the decay, and
            // the cap and the decay change what the bond becomes. That is an `if` statement with a
            // consequence, which is all rule 5 asks for.

            // Paid off in session 09, exactly where the exemption said it would land. The band
            // reads trust and not warmth, and that was measured rather than chosen: session 07
            // found that no three residents ever reach 20 warmth in a hundred in-game days, and
            // session 08 found that gossip does not change it at any mark. Residency is a state
            // change with consequences a line of dialogue does not have — session 10's cross-
            // settlement gossip about you, session 12's price band — and the pool selection, which
            // is what this entry has warned against naming since session 05, is a display sitting
            // downstream of it rather than the consumer.
            Entry.consumedBy(Bond.class, "trust", List.of("trust", "axis"),
                    Residency.class, "verdict",
                    "Residency.verdict counts the residents holding TRUST_THRESHOLD or more and "
                            + "grants DESIGN.md §5's band at three of them. The third resident "
                            + "crosses 20 on day 28, measured. Trust does not decay, so residency "
                            + "is earned by consistency rather than by intensity."),

            // Paid off in session 09, and NOT by what its own exemption predicted — the second time
            // this mechanism has caught a consumer that was named in good faith and was not one.
            // This entry said "the same residency threshold reads both", and the owner ruled at the
            // close of session 08 that it reads trust alone, which left warmth written by every
            // kindness in the mod and read by nothing. That is the shape DESIGN.md §1 forbids,
            // arriving from a direction session 05 did not predict: not "nobody got round to it"
            // but "the consumer we were both relying on turned out to want the other axis".
            //
            // Both escapes were offered at the close of 08 and the owner declined both — moving the
            // expiry to 12, and deleting the field — so session 09 owed warmth a real mechanic.
            // GiftPolicy is it, and the reason it is warmth's rather than a consumer that happens
            // to read warmth is that trust could not do this job: trust never decays, so a gate on
            // it opens once and never closes. Warmth decays toward four tenths of its high-water
            // mark, so this gate closes again if you stop turning up.
            Entry.consumedBy(Bond.class, "warmth", List.of("warmth", "axis"),
                    GiftPolicy.class, "verdict",
                    "GiftPolicy.verdict refuses a gift the villager has no use for from somebody "
                            + "below WARMTH_TO_ACCEPT_UNWANTED. The item does not change hands, no "
                            + "deed is emitted, no bond moves and no memory is written. Feeding the "
                            + "hungry and giving what they want are never gated, so onboarding and "
                            + "the acceptance script's step 2 are untouched and there is no "
                            + "deadlock — the two ungated routes are the two that raise warmth."),

            Entry.exemptUntilAfter(Bond.class, "respect", List.of("respect", "axis"), 12,
                    "Session 12, the standing bands: the trade price multiplier and whether one "
                            + "recipe is taught. Both are mechanics rather than lines, and the "
                            + "ledger names session 12 as having exactly three consumers."),

            Entry.exemptUntilAfter(Bond.class, "fear", List.of("fear", "axis"), 16,
                    "Sessions 16-20, the grievance ladder: DESIGN.md §3 says fear earns its slot "
                            + "because it makes force-resolutions representable — intimidation "
                            + "works and the settlement remembers that it worked — and §6 is where "
                            + "that is read. This and debt are the two longest exemptions here. "
                            + "Fear is at least written already: a strike and a killing both raise "
                            + "it, so the field is live rather than dormant."),

            Entry.exemptUntilAfter(Bond.class, "debt", List.of("debt"), 16,
                    "Sessions 16-20: a favour or a gift is what resolves a stage-2 grievance, and "
                            + "inheritance at 21-23 rules that debts die with the NPC. The longest "
                            + "exemption here and the one with the least behind it — nothing in the "
                            + "sixteen-session slice writes it, let alone reads it. DELETION WAS "
                            + "OFFERED AT THE CLOSE OF SESSION 05 AND THE OWNER RULED THE FIELD "
                            + "STAYS, so it is carried deliberately rather than by inertia. The "
                            + "expiry is untouched by that ruling: session 16 either reads it or "
                            + "deletes it, and moving this number is still the one thing this file "
                            + "exists to stop."),

            Entry.consumedBy(Bond.class, "lastSeenDay", List.of("lastSeenDay"),
                    Bond.class, "decayedTo",
                    "How many days of warmth to give back, and whether the daily allowance has "
                            + "reset. A bond read on a later day is a different bond, which is the "
                            + "whole of what 'lazy decay' means."),

            Entry.consumedBy(Bond.class, "gainedToday", List.of("gainedToday"),
                    Bond.class, "apply",
                    "Four counters in four bits each. Bond.apply spends a positive delta against "
                            + "what is left of the axis's allowance and drops the rest, which is "
                            + "the difference between a relationship and a number you can grind."),

            Entry.consumedBy(Bond.class, "peakWarmth", List.of("peakWarmth"),
                    Bond.class, "decayedTo",
                    "The floor decay falls to, at four tenths of it. Somebody who mattered to you "
                            + "and then went away for a year is cooler when they come back, not a "
                            + "stranger — and that is a different number for every bond."),

            // --- Memories.Slot, new in session 09 ------------------------------------------------
            //
            // A ring slot is persisted state that DECLARES NO CODEC, because session 09 packs the
            // ring into a fixed-width byte array rather than writing a compound per deed. So
            // everyPersistedRecordIsLedgered — which discriminates "persisted" by the presence of a
            // Codec — cannot see it, and would have let a new persisted social value through
            // silently. That is a real gap in the mechanism and it is recorded rather than papered
            // over: theRingSlotIsLedgered below pins this record by name, so a future refactor that
            // drops these three lines turns the build red instead of quietly exempting a field.

            Entry.identity(Memories.Slot.class, "id",
                    "The key the ring is addressed by, and the only field here that is not on disk "
                            + "at all: it is Deed.id() recomputed on load, carried in memory only "
                            + "because recomputing a sixty-four bit mix of eight fields per slot per "
                            + "lookup was three quarters of a hundred-day simulation run."),

            Entry.consumedBy(Memories.Slot.class, "deed", List.of("deed"),
                    Memories.class, "evictionWeight",
                    "Which memory survives an overflow starts here: a harmful deed outranks every "
                            + "kindness absolutely, so no quantity of bread displaces a killing. "
                            + "Every field of the deed itself is ledgered above."),

            // Session 09's repeat count, and the second of the owner's three memory-depth routes.
            // Its consumer is deliberately NOT the line of dialogue that would say "nine times" —
            // that is a display, and the shape Bond.trust's entry has warned against since 05. It is
            // eviction, which session 07 flagged as the one question its numbers could not settle:
            // "nothing in this run gave a villager a killing to keep, so nothing has yet tested
            // whether thirty-two subsequent gifts push one out."
            Entry.consumedBy(Memories.Slot.class, "repeats", List.of("repeats", "happenedOnce"),
                    Memories.class, "evictionWeight",
                    "An afternoon of nine gifts is held harder than a single one, up to "
                            + "REPEATS_COUNTED — so overflow drops the single one and keeps the nine. "
                            + "The cap is what stops the count reopening the grind content addressing "
                            + "closed: five hundred repeats must not become the strongest memory a "
                            + "villager has.")
    );

    /**
     * A consumer in one of these is not a consumer. Rule 5 is precisely about values that terminate
     * in something that shows them to a person.
     */
    private static final Set<String> DISPLAY_PACKAGES =
            Set.of("net.namesake.command", "net.namesake.harness", "net.namesake.client",
                    // Added at session 09, before a line of dialogue was written, and it is the
                    // single most load-bearing entry in this set. Session 09 is the first session
                    // whose deliverable is words, and it arrived carrying three fields that needed a
                    // reader with no obvious one that was not a line of dialogue. Bond.trust's own
                    // entry has ruled since session 05 that naming the pool selection would be
                    // naming a display; session 03 caught cultureId telling exactly that lie about
                    // the syllable grammar. Listing the package makes it a build failure rather than
                    // a thing somebody has to remember while under pressure to ship 160 lines.
                    "net.namesake.dialogue",
                    // Added at session 11, before a line of the Notice Board was written, for the
                    // same reason and after the same reasoning. The board is the first surface in
                    // this mod a player can read, so it is the most tempting reader in the codebase
                    // for a field that has none — and Deed.item's exemption falls due at session 12
                    // with the board already printing it. Listing the package means the board can
                    // never be mistaken for the payment: it shows the object, and session 12 still
                    // owes it a mechanic or a deletion.
                    "net.namesake.board");

    private static final List<String> DISPLAY_SUFFIXES =
            List.of("Renderer", "Screen", "Hud", "Widget", "Commands", "Harness");

    // --- the tests ---------------------------------------------------------------------------------

    /**
     * The check that makes the rest self-maintaining: a persisted record nobody added to the ledger
     * fails here rather than being silently exempt.
     *
     * <p>"Persisted" is discriminated by declaring a {@link Codec}, which is what makes a record
     * something that reaches a save file. {@code Bond}, {@code Deed} and {@code Grievance} join
     * this set at sessions 05, 06 and 16 by doing nothing but existing.
     */
    @Test
    @DisplayName("every persisted record in the mod appears in the ledger")
    void everyPersistedRecordIsLedgered() {
        Set<Class<?>> ledgered = LEDGER.stream().map(Entry::owner)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        List<String> missing = new ArrayList<>();
        int persisted = 0;
        for (Class<?> candidate : ModClasses.all()) {
            if (!candidate.isRecord() || !declaresACodec(candidate)) {
                continue;
            }
            persisted++;
            if (!ledgered.contains(candidate)) {
                missing.add(candidate.getName());
            }
        }

        assertTrue(persisted > 0, "the scan found no persisted records, so it proves nothing");
        assertTrue(missing.isEmpty(), () -> """
                These records are persisted and have no entry in the social value ledger:
                  %s
                Add one line per field to LEDGER in this file, naming the non-display consumer it \
                feeds — or an exemption and the session that owes it one. DESIGN.md §1.""".formatted(
                String.join("\n  ", missing)));
    }

    @Test
    @DisplayName("every field of every ledgered record is classified, and nothing is classified twice")
    void everyFieldIsClassified() {
        for (Class<?> owner : LEDGER.stream().map(Entry::owner).distinct().toList()) {
            Set<String> fields = new LinkedHashSet<>();
            for (RecordComponent component : owner.getRecordComponents()) {
                fields.add(component.getName());
            }
            List<String> entries = LEDGER.stream()
                    .filter(entry -> entry.owner().equals(owner))
                    .map(Entry::field)
                    .toList();

            List<String> unclassified = fields.stream().filter(f -> !entries.contains(f)).toList();
            assertTrue(unclassified.isEmpty(), () -> """
                    %s has fields with no entry in the social value ledger: %s
                    Name the non-display consumer each one feeds, or an exemption and the session \
                    that owes it one. If you cannot name the `if` statement it feeds, delete the \
                    field. DESIGN.md §1.""".formatted(owner.getSimpleName(), unclassified));

            List<String> phantom = entries.stream().filter(f -> !fields.contains(f)).toList();
            assertTrue(phantom.isEmpty(),
                    () -> owner.getSimpleName() + " has ledger entries for fields that no longer "
                            + "exist: " + phantom);
            assertTrue(entries.size() == new LinkedHashSet<>(entries).size(),
                    () -> owner.getSimpleName() + " has duplicate ledger entries: " + entries);
        }
    }

    @Test
    @DisplayName("a social field carries exactly one of a consumer or an exemption")
    void socialFieldsMakeExactlyOneClaim() {
        for (Entry entry : LEDGER) {
            if (entry.kind() != Kind.SOCIAL) {
                continue;
            }
            assertTrue(entry.hasConsumer() ^ entry.isExempt(),
                    () -> entry.describe() + " must either name a consumer or claim an exemption, "
                            + "not both and not neither.");
            assertFalse(entry.accessors().isEmpty(),
                    () -> entry.describe() + " must list the accessors a consumer would read it "
                            + "through, or the bytecode check has nothing to look for.");
        }
    }

    @Test
    @DisplayName("every named consumer exists, is not a display, and actually reads the field")
    void namedConsumersAreReal() {
        int checked = 0;
        for (Entry entry : LEDGER) {
            if (!entry.hasConsumer()) {
                continue;
            }
            checked++;

            String consumerName = entry.consumerClass().getName();
            assertFalse(DISPLAY_PACKAGES.contains(entry.consumerClass().getPackageName()),
                    () -> entry.describe() + " names " + consumerName + " as its consumer, but "
                            + "that package exists to show things to a person. A display is not a "
                            + "consumer. DESIGN.md §1.");
            assertFalse(DISPLAY_SUFFIXES.stream().anyMatch(consumerName::endsWith),
                    () -> entry.describe() + " names " + consumerName + ", which is a display "
                            + "class by its own name. DESIGN.md §1.");

            // MethodBody throws if the method has been renamed away, which is the point: a ledger
            // that drifts from the code is worse than no ledger.
            MethodBody body = MethodBody.of(entry.consumerClass(), entry.consumerMethod());
            assertTrue(body.invokesAny(entry.owner(), entry.accessors()),
                    () -> entry.describe() + ": " + consumerName + "." + entry.consumerMethod()
                            + " never reads it. It calls none of " + entry.accessors() + ". A "
                            + "consumer that does not read the field is not a consumer.");
        }
        assertTrue(checked > 0, "no consumer was checked, so this test proves nothing");
    }

    /**
     * The forcing function. Each exemption names the session that owes it a consumer; the current
     * session is read from {@code WORKPLAN.md}'s status board, which is updated at the close of
     * every session by {@code CLAUDE.md}'s own rule.
     *
     * <p>So the build goes red the moment the ledger records that the owing session is finished —
     * not at its start, which would leave {@code main} red for the whole session.
     */
    @Test
    @DisplayName("no exemption has outlived the session that owed it a consumer")
    void noExemptionHasExpired() {
        int session = SessionLedger.currentSession();
        List<String> expired = LEDGER.stream()
                .filter(Entry::isExempt)
                .filter(entry -> session > entry.exemptAfterSession())
                .map(entry -> entry.describe() + " (exempt only through session "
                        + entry.exemptAfterSession() + "): " + entry.note())
                .toList();

        assertTrue(expired.isEmpty(), () -> """
                WORKPLAN.md says session %02d is next, so these exemptions have expired:
                  %s
                Either the consumer landed and this entry should now name it, or it did not and the \
                field should be deleted. Moving the expiry is the one thing this test exists to \
                stop. DESIGN.md §1.""".formatted(session, String.join("\n  ", expired)));
    }

    @Test
    @DisplayName("an exemption that is one session stale is reported expired")
    void theExpiryComparisonWorks() {
        // Guards the guard. If this comparison were inverted or off by one, noExemptionHasExpired
        // would be green forever and nobody would know until session 05 shipped without the weight
        // table — which is exactly the failure it was written to prevent.
        int session = SessionLedger.currentSession();
        Entry stale = Entry.exemptUntilAfter(Persona.class, "traits", List.of("trait"),
                session - 1, "fixture");
        Entry current = Entry.exemptUntilAfter(Persona.class, "traits", List.of("trait"),
                session, "fixture");

        assertTrue(session > stale.exemptAfterSession(), "a stale exemption must read as expired");
        assertFalse(session > current.exemptAfterSession(),
                "an exemption for the session in progress must not read as expired");
    }

    /**
     * <b>Measurement data must not become persisted state without this conversation happening.</b>
     *
     * <p>{@code WORKPLAN.md} session 07 specifies "{@code DialogueStats} SavedData", and it is not
     * one. The brief's own warning is why: a {@code DialogueStats} whose named consumer is
     * {@code /namesake debug stats} is a display, {@code DISPLAY_PACKAGES} already contains
     * {@code net.namesake.command}, and this file would refuse it — correctly. The two ways round
     * that are both dishonest. An exemption is a promise that a mechanic will read the field, and no
     * mechanic reads a histogram. A {@code PRESENTATION} classification means "ruled in
     * {@code DESIGN.md} as existing to be drawn", which is true of {@code Persona.appearanceSeed} and
     * would be a lie here.
     *
     * <p>So the honest answer was to have nothing to classify: every number
     * {@code DialogueStats} reports is derived from bonds and rings that are already persisted and
     * already ledgered above. This is what stops that quietly reverting. It fails if the class ever
     * declares a codec, and it fails if {@code NpcRegistry}'s save or load path ever mentions it —
     * because a tally reachable from the save path is a tally on its way into a file, whatever it
     * happens to be called that week.
     */
    @Test
    @DisplayName("the measurement instrument is not on the save path, and declares no codec")
    void measurementDataIsNotPersisted() {
        assertFalse(declaresACodec(DialogueStats.class),
                "DialogueStats declares a Codec, so it is on its way into a save file. Every number "
                        + "in it is derivable from bonds and rings that are already persisted and "
                        + "already ledgered here; storing it as well is a cache, and session 03 "
                        + "deleted Settlement.culture for being exactly that. If it genuinely has to "
                        + "be persisted, add it to LEDGER above and name the mechanic that reads it "
                        + "— and hard rule 1 then owes a schema bump, a datafixer and a load test.");

        for (String method : List.of("save", "load")) {
            assertFalse(MethodBody.of(NpcRegistry.class, method).mentions(DialogueStats.class),
                    () -> "NpcRegistry." + method + " reaches DialogueStats. Measurement data on the "
                            + "save path is measurement data in a save file, which survives a change "
                            + "to the thing it counts and then describes a mod that no longer exists.");
        }
    }

    /**
     * <b>The gap session 09 opened in {@link #everyPersistedRecordIsLedgered}, pinned rather than
     * papered over.</b>
     *
     * <p>That check discriminates "persisted" by whether a record declares a {@link Codec}, which
     * has been a good proxy for five sessions: {@code Bond}, {@code Deed} and {@code Settlement} all
     * joined the ledger by doing nothing but existing. Session 09 packs the deed ring into a
     * fixed-width byte array to make a hundred and twenty-eight slots fit, so {@code Memories.Slot}
     * is <b>persisted state with no codec</b> — invisible to the scan, and carrying a new social
     * value in {@code repeats}.
     *
     * <p>Fixing the scan properly would mean deciding what "persisted" means for a record somebody
     * writes by hand, which is a bigger claim than this session can honestly make. Pinning the one
     * record that has the shape is the small honest thing: delete its three ledger entries and this
     * turns red naming it.
     */
    @Test
    @DisplayName("a persisted record with no codec is still ledgered, by name")
    void theRingSlotIsLedgered() {
        Set<Class<?>> ledgered = LEDGER.stream().map(Entry::owner)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        assertTrue(ledgered.contains(Memories.Slot.class), """
                Memories.Slot is written to namesake_npcs.dat and declares no Codec, so \
                everyPersistedRecordIsLedgered cannot see it. It has to be listed in LEDGER by \
                hand, and this test is what says so. DESIGN.md §1.""");
        assertFalse(declaresACodec(Memories.Slot.class),
                "Memories.Slot has gained a Codec, so everyPersistedRecordIsLedgered can find it on "
                        + "its own now and this hand-written pin is no longer the only thing "
                        + "holding it. Fold it back into the scan.");
    }

    @Test
    @DisplayName("a field that is not a social value says why in writing")
    void nonSocialFieldsAreJustified() {
        for (Entry entry : LEDGER) {
            if (entry.kind() == Kind.SOCIAL) {
                continue;
            }
            assertTrue(entry.note() != null && entry.note().length() > 40,
                    () -> entry.describe() + " is classified " + entry.kind() + " with no real "
                            + "justification. Declaring a field 'not social' is how rule 5 gets "
                            + "evaded; it costs a sentence to say why.");
        }
    }

    private static boolean declaresACodec(Class<?> candidate) {
        for (Field field : candidate.getDeclaredFields()) {
            if (Codec.class.isAssignableFrom(field.getType())) {
                return true;
            }
        }
        return false;
    }
}
