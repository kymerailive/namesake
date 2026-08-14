package net.namesake.dialogue;

import net.namesake.culture.Culture;

import java.util.EnumMap;
import java.util.Map;

/**
 * <b>How a culture sounds when it says the same thing.</b> {@code DESIGN.md} §2:
 * <i>shared ~400 templates + per-culture tics and register tuning.</i>
 *
 * <p>Session 03 gave six cultures their own phonotactics, baseline disposition and conformity, and
 * the owner's playtest ruled that a second village 3,000 blocks out read as foreign without being
 * pointed at. That was <b>names</b>. This is the second half of the same claim and it is the one
 * standing risk 3 is actually about: <i>if settlement two sounds and behaves like settlement one, the
 * travel loop collapses around hour 45</i>. A culture that differs only in what its people are called
 * is a culture a player stops noticing.
 *
 * <h2>Four tics, and each one does something a test can see</h2>
 *
 * <ul>
 *   <li>{@link #opener} — what goes in front. Vale leans on <i>Aye,</i>; Tal-Qir on <i>So.</i></li>
 *   <li>{@link #tag} — the question hung on the end. Meridian asks <i>no?</i> constantly; Tal-Qir
 *       almost never does.</li>
 *   <li>{@link #strangerAddress} — <b>what they call you before residency</b>, which is the half of
 *       {@code DESIGN.md} §5's name swap that is not the name. A Meridian trader calls a stranger
 *       <i>friend</i>; a Karsk hillman calls them <i>outlander</i>. Both then use your name, and the
 *       distance travelled is different in each.</li>
 *   <li>{@link #formality} — the bias between the two, in {@code [0, 1]}. It is not decoration: a
 *       formal culture opens and rarely tags, an informal one tags and rarely opens, so the <i>rate</i>
 *       at which each tic appears is a per-culture number rather than a per-culture string.</li>
 * </ul>
 *
 * <p><b>Machine-checked rather than asserted in a comment</b>, because "the cultures differ" is
 * exactly the claim a comment is worst at keeping true: {@code DialogueTest} fails if any two
 * cultures share a tic, if two share a word for a stranger, or if the measured opener rate does not
 * follow the formality ordering. Session 03 shipped a name space that counted twelve million and
 * behaved like a hundred and thirty thousand; the lesson taken from that was to measure the realised
 * behaviour rather than the table.
 *
 * <p><b>Not persisted and not a record</b>, exactly as {@link Culture} is not: a persona stores a
 * culture id and this table is code, so a tic can never reach a save file and the table can never
 * drift from one.
 */
public record Voice(String opener, String tag, String strangerAddress, float formality) {

    private static final Map<Culture, Voice> BY_CULTURE = new EnumMap<>(Culture.class);

    static {
        // Temperate farmland: plain, settled, a little blunt. Says aye and means it.
        BY_CULTURE.put(Culture.VALE, new Voice("Aye,", "aye?", "stranger", 0.30F));
        // Cold high country: clipped, proud, slow to open. The opener is barely a word.
        BY_CULTURE.put(Culture.KARSK, new Voice("Hm.", "yes?", "outlander", 0.65F));
        // Warm coast, mercantile: everyone is a friend until they are not, and everything is a
        // question. The lowest formality of the six, and it shows in how often it tags.
        BY_CULTURE.put(Culture.MERIDIAN, new Voice("Ah!", "no?", "friend", 0.15F));
        // Hot lowland: communal, unhurried, hard to offend.
        BY_CULTURE.put(Culture.ASHANI, new Voice("Eh,", "eh?", "traveller", 0.25F));
        // Dry uplands: the most formal of the six, and the most conformist — session 03's own note.
        BY_CULTURE.put(Culture.TALQIR, new Voice("So.", "indeed?", "guest", 0.85F));
        // Cool wet valleys: reserved, orderly, private. Opens rather than tags.
        BY_CULTURE.put(Culture.YUN, new Voice("Mm.", "hm?", "visitor", 0.70F));
    }

    public static Voice of(Culture culture) {
        Voice voice = BY_CULTURE.get(culture);
        if (voice == null) {
            // Every culture is in the table above and the test says so. Failing loudly beats
            // substituting somebody else's accent, which is the kind of quiet wrong this project
            // keeps finding: a save that loads and a village that sounds like the wrong place.
            throw new IllegalStateException("No voice for culture " + culture);
        }
        return voice;
    }

    /**
     * The line as this culture actually says it.
     *
     * <p>Two independent coins off one seed, so a villager does not open and tag every sentence:
     * the opener attaches with probability {@link #formality} and the tag with probability
     * {@code 1 - formality}. That is what makes formality a mechanism rather than a label — Tal-Qir
     * opens five sentences in six and tags one; Meridian does the reverse.
     *
     * <p><b>A tag question turns a statement into a question, so it only belongs on a statement.</b>
     * Three things stop it landing where it would produce a sentence nobody would say, and all three
     * were found by reading the rendered output rather than by reasoning about the mechanism:
     *
     * <ul>
     *   <li><b>Not on a question or an exclamation.</b> <i>"What do you want, no?"</i> The pools
     *       contain plenty of both, so this is checked on the punctuation.</li>
     *   <li><b>Not on a parting.</b> A goodbye is an instruction rather than a claim, and
     *       <i>"Go on, then, aye?"</i> and <i>"On your way, aye?"</i> both read as somebody who has
     *       lost their thread. This is why the method takes a {@link Register} rather than only a
     *       string.</li>
     *   <li><b>Not on something too short to hang one off.</b> The hostile pool opens with
     *       <i>"You."</i> and closes with <i>"Go."</i>, and <i>"You, yes?"</i> is not a sentence in
     *       any register. {@link #TAGGABLE_LENGTH} is where that stops.</li>
     * </ul>
     */
    public String inflect(String line, Register register, long seed) {
        String spoken = line;
        if (coin(seed) < formality) {
            spoken = opener + " " + spoken;
        }
        if (canTag(spoken, register) && coin(mix(seed)) < 1.0F - formality) {
            spoken = spoken.substring(0, spoken.length() - 1) + ", " + tag;
        }
        return spoken;
    }

    /**
     * Shorter than this and there is nothing to hang a question on.
     *
     * <p>Sixteen, which is where the hostile pool's one-word lines sit and where a real clause
     * starts. Measured on the <i>inflected</i> string, so a culture that has already put its opener
     * in front has that much less to prove.
     */
    static final int TAGGABLE_LENGTH = 16;

    /** Whether a tag question can honestly go on the end of this. */
    static boolean canTag(String spoken, Register register) {
        return spoken.endsWith(".")
                && register != Register.PARTING
                && spoken.length() >= TAGGABLE_LENGTH;
    }

    /** A number in {@code [0, 1)} from a seed. Deterministic: a report and a game must agree. */
    private static float coin(long seed) {
        return (mix(seed) >>> 40) / (float) (1L << 24);
    }

    /** SplitMix64, the same finalizer {@code Names} uses. Allocation-free and well distributed. */
    static long mix(long value) {
        long z = value + 0x9E3779B97F4A7C15L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }
}
