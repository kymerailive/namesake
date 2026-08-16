package net.namesake.config;

import net.namesake.day.DayPlan;
import net.namesake.day.Errand;
import net.namesake.day.Steering;
import net.namesake.social.Bond;
import net.namesake.social.Deed;
import net.namesake.testing.ConstantFields;
import net.namesake.testing.MethodBody;
import net.namesake.testing.ModClasses;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>{@code WORKPLAN.md}'s never-cut list, held against the session that built a config.</b>
 *
 * <p>The list is seven walls long and <i>every one of them looks exactly like a tuning knob</i>. Six
 * of them are a small integer with a comment; the seventh is a nine-line helper. Session 15 is the
 * first session that could turn any of them into a setting by accident, and the guard that existed
 * could not have seen it: {@code DayPlanTest.theSpreadFloorIsNotConfigurable} reads one class's
 * {@code <clinit>} for four JDK property doors, and a config file is none of them.
 *
 * <p>So this asks the question one level up, at the level the claim holds — see
 * {@link ConstantFields}. A folded constant has no runtime read anywhere in the program, so it
 * cannot be redirected to a config, a property, an environment variable, a file, a packet or a door
 * nobody has thought of. That is the difference between <i>"nothing currently reads a property for
 * this"</i> and <i>"there is nothing to read"</i>.
 */
class NeverCutTest {

    /**
     * One wall: where it lives, what it is called, and what the ledger calls it.
     *
     * @param owner the class holding it
     * @param field the field name
     * @param wall  the words {@code WORKPLAN.md} uses, so a failure names the wall rather than the
     *              field
     */
    private record Wall(Class<?> owner, String field, String wall) {
    }

    /**
     * The walls that are numbers. {@code WORKPLAN.md}'s list, in its own order, minus the three that
     * are not constants — see {@link #theListIsAccountedForInFull}.
     */
    private static final List<Wall> WALLS = List.of(
            new Wall(DayPlan.class, "SPREAD_FLOOR", "the spread >= 64 boundary-jitter floor"),
            new Wall(DayPlan.class, "SPREAD", "the spread actually used, held to its own floor"),
            new Wall(Steering.class, "TRANSITIONS_PER_TICK", "the 8/tick transition governor"),
            new Wall(DayPlan.class, "PATH_GATE_PERIOD", "the id % 7 path gate"),
            new Wall(Bond.class, "MAX_DAY_DELTA", "the dayDelta <= 64 clamp"),
            new Wall(Deed.class, "ATTRIBUTED", "Deed.ATTRIBUTED, the blur threshold and the "
                    + "retelling floor (session 08)"),
            new Wall(Deed.class, "RETOLD", "Deed.RETOLD, which decides how far every story in the "
                    + "world travels (session 08)"));

    /**
     * <b>Every numbered wall is folded at compile time, so nothing can read it at runtime.</b>
     *
     * <p>This is the test session 15 owed itself. The session's whole deliverable is a file a server
     * operator edits, and the never-cut list is a list of numbers that would each make a plausible
     * line in it. A reviewer cannot hold that line and a code search cannot either — a config read
     * can be written in any class, and the four-door check only ever looked in one.
     */
    @Test
    @DisplayName("every never-cut number is a compile-time constant, so no config can reach it")
    void everyWallIsACompileTimeConstant() {
        for (Wall wall : WALLS) {
            ConstantFields folded = ConstantFields.of(wall.owner());
            assertTrue(folded.isFolded(wall.field()), () ->
                    wall.owner().getSimpleName() + "." + wall.field() + " — " + wall.wall() + " — is "
                            + "no longer a compile-time constant, so it is now READ at runtime and "
                            + "can be pointed at a config file, a system property or anything else. "
                            + "WORKPLAN.md's never-cut list calls it a load-bearing wall rather than "
                            + "a tuning knob. Either restore the constant initialiser and the final, "
                            + "or take it off that list on purpose and say why in the ledger. The "
                            + "folded fields on that class are: " + folded.all().keySet());
        }
    }

    /**
     * <b>The one wall that is not a number is still a method, and it is still called.</b>
     *
     * <p>{@code addActivitySafely} is on the list because {@code Villager.refreshBrain} destroys a
     * custom activity on every load from disk — see {@code Errand}'s javadoc. A config could not
     * make it configurable, but a config session could easily make it <i>unreachable</i> by gating
     * the errand behind a setting at the wrong level, so what this holds is that the day plan still
     * goes through it.
     */
    @Test
    @DisplayName("the errand still re-establishes its activity through addActivitySafely")
    void theHelperIsStillTheOnlyDoor() {
        MethodBody enterSlot = MethodBody.of(Steering.class, "beginErrand");
        assertTrue(enterSlot.invokes(Errand.class, "addActivitySafely"),
                "Steering.beginErrand no longer calls Errand.addActivitySafely. That helper is on "
                        + "WORKPLAN.md's never-cut list because Villager.refreshBrain replaces the "
                        + "whole Brain on EVERY load from disk, so a one-shot registration is wrong "
                        + "the first time a player walks away from a village and comes back.");
    }

    /**
     * <b>Nothing in the config package reads a persisted social value.</b>
     *
     * <p>Rule 5's ledger asks what {@code if} statement a field feeds, and {@code Config}'s own
     * javadoc rules that a config value can never be the answer because it is the <i>operand</i> of
     * the comparison rather than its subject. This is that ruling as a build failure: config flows
     * one way, into mechanics, and never back out of a record.
     *
     * <p>It also closes the hole {@code SocialValueLedgerTest} would otherwise have, which is that
     * {@code net.namesake.config} is in neither its display set nor its suffix list — so without
     * this, a persisted field could name a config method as its non-display consumer and pass every
     * gate in that file. That is exactly the lie {@code cultureId} told the syllable grammar at
     * session 03.
     */
    @Test
    @DisplayName("the config package reads no persisted social value")
    void theConfigReadsNoRecord() {
        List<Class<?>> forbidden = List.of(
                net.namesake.social.Bond.class,
                net.namesake.social.Deed.class,
                net.namesake.npc.Persona.class,
                net.namesake.social.Memories.class);

        int checked = 0;
        for (Class<?> candidate : ModClasses.all()) {
            if (!candidate.getPackageName().equals("net.namesake.config")) {
                continue;
            }
            checked++;
            for (java.lang.reflect.Method method : candidate.getDeclaredMethods()) {
                if (method.isSynthetic() || method.isBridge()) {
                    continue;
                }
                MethodBody body = MethodBody.of(candidate, method.getName());
                for (Class<?> owner : forbidden) {
                    assertFalse(body.mentions(owner), () ->
                            candidate.getName() + "#" + method.getName() + " reaches "
                                    + owner.getSimpleName() + ". The config supplies the operand of "
                                    + "a comparison; it must never be the thing doing the comparing, "
                                    + "and it must never be able to be named as a persisted field's "
                                    + "rule 5 consumer. See Config's own javadoc for why that is a "
                                    + "different objection from the one DISPLAY_PACKAGES makes.");
                }
            }
        }
        assertTrue(checked > 0, "no config class was scanned, so this test proves nothing");
    }

    /**
     * <b>The schema layer cannot see the config, which is what keeps a config file from being a
     * save.</b>
     *
     * <p>Hard rule 1 applies to state on disk that a later build reads. A config qualifies on the
     * words and not on the substance, because a missing key defaults and an unknown key is ignored —
     * so there is no reading of an old one that produces a wrong value. <b>That stops being true the
     * moment a config value decides how a persisted byte is interpreted</b>: a save written under
     * one setting would then be misread under another, with no version number anywhere to catch it.
     *
     * <p>So the one clause the argument rests on is a build failure rather than a promise.
     */
    @Test
    @DisplayName("no class in the schema layer mentions the config")
    void theSchemaLayerCannotSeeTheConfig() {
        int checked = 0;
        for (Class<?> candidate : ModClasses.all()) {
            if (!candidate.getPackageName().equals("net.namesake.npc")) {
                continue;
            }
            checked++;
            for (java.lang.reflect.Method method : candidate.getDeclaredMethods()) {
                if (method.isSynthetic() || method.isBridge()) {
                    continue;
                }
                MethodBody body = MethodBody.of(candidate, method.getName());
                assertFalse(body.mentions(Config.class), () ->
                        candidate.getName() + "#" + method.getName() + " reads the config. A config "
                                + "value that decides how a persisted byte is interpreted turns "
                                + "namesake_npcs.dat into a file whose meaning depends on a text "
                                + "file beside it, with no version number in either. That is hard "
                                + "rule 1 with the datafixer removed.");
            }
        }
        assertTrue(checked > 0, "no schema class was scanned, so this test proves nothing");
    }

    /**
     * <b>The whole never-cut list is accounted for, including the two entries that are not code.</b>
     *
     * <p>Kept as a written record rather than an assertion because two of the seven walls guard
     * nothing: <b>the player-relative particle emission gate and the sleep-skip cold-start mode have
     * no implementation anywhere in this repository.</b> Session 15 looked for them —
     * {@code ParticleTypes}, {@code sendParticles} and {@code addParticle} return no hits in main
     * source, and "sleep skip" appears only in one comment in {@code Deed}.
     *
     * <p>They are notes to a future session rather than walls, and saying so is the point: a
     * never-cut entry that guards code that does not exist reads, to anybody scanning the list, as
     * an existing guard. See the session 15 log.
     */
    @Test
    @DisplayName("the never-cut list is seven walls, five of them held here and two not yet built")
    void theListIsAccountedForInFull() {
        // Five distinct constants across four classes, plus addActivitySafely and the two that do
        // not exist. WALLS has seven rows because SPREAD and SPREAD_FLOOR are one wall in two fields
        // and ATTRIBUTED and RETOLD are one wall in two fields.
        Set<Class<?>> owners = new LinkedHashSet<>();
        WALLS.forEach(wall -> owners.add(wall.owner()));
        assertTrue(owners.size() == 4, () -> "expected four wall-holding classes, found " + owners);

        assertTrue(WALLS.stream().anyMatch(wall -> wall.owner() == Deed.class),
                "session 08's addition to the list is Deed.ATTRIBUTED and Deed.RETOLD, and it is "
                        + "the one entry the ledger explicitly says looks like a tuning knob and is "
                        + "a wall.");
    }
}
