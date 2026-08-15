package net.namesake.social;

import net.namesake.npc.NpcRegistry;
import net.namesake.npc.Persona;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>{@code Bond.warmth}'s mechanic.</b> Whether a villager takes what you are holding out.
 *
 * <p>The four things this had to not break are the four things worth testing, and each one is a
 * failure this design could plausibly have shipped: onboarding, the deadlock, a threshold above what
 * anybody reaches, and being a mechanic rather than a line.
 */
class GiftPolicyTest {

    private static final UUID PLAYER = new UUID(0x5EAF_0000_0000_0001L, 9);
    private static final UUID ANNA = new UUID(77, 0);

    private static NpcRegistry withWarmth(int warmth) {
        NpcRegistry registry = new NpcRegistry();
        registry.put(Persona.create(ANNA, 0L).placed(0, 0, (byte) 0));
        if (warmth > 0) {
            // Built directly rather than through Bond.apply, which clamps its allowance to what the
            // four-bit gainedToday counters can hold — so a fixture asking for twenty warmth in one
            // call quietly gets fifteen, and would be testing a threshold it never reaches. The peak
            // is set to the same value, which is what a bond that actually earned it would hold and
            // is what the decay in theGateReopens measures against.
            registry.putBond(ANNA, PLAYER, new Bond((byte) 0, (byte) warmth, (byte) 0,
                    (short) 0, 0, (short) 0, (byte) warmth));
        }
        return registry;
    }

    private static GiftPolicy.Verdict offer(NpcRegistry registry, DeedType kind, int day) {
        return GiftPolicy.verdict(registry, ANNA, PLAYER, kind, true, day);
    }

    /**
     * <b>{@code DESIGN.md} §10's acceptance script, step 2.</b> A player who arrived ten minutes ago
     * feeds a hungry villager in the square. If warmth gated that, the first hour of the mod would be
     * a closed door and the script would be unpassable.
     */
    @Test
    @DisplayName("a total stranger can always feed the hungry and give what is wanted")
    void onboardingIsNeverGated() {
        NpcRegistry cold = withWarmth(0);
        assertEquals(GiftPolicy.Verdict.ACCEPTED, offer(cold, DeedType.FED_HUNGRY, 0));
        assertEquals(GiftPolicy.Verdict.ACCEPTED, offer(cold, DeedType.GIFT_WANTED, 0));
    }

    /**
     * The deadlock this design had to avoid: a gate on all giving is a gate on the only thing that
     * raises the axis it reads. The two ungated routes are exactly the two that raise warmth, so the
     * door out is always open and {@code DESIGN.md}'s "always recoverable, slowly" stays true.
     */
    @Test
    @DisplayName("the way past the gate is the thing the gate does not block")
    void thereIsNoDeadlock() {
        NpcRegistry registry = withWarmth(0);
        assertEquals(GiftPolicy.Verdict.REFUSED_STRANGER, offer(registry, DeedType.GIFT_UNWANTED, 0));

        // Which vanilla says they want, or that they are hungry — either raises warmth.
        assertEquals(GiftPolicy.Verdict.ACCEPTED, offer(registry, DeedType.FED_HUNGRY, 0));
        assertTrue(DeedType.FED_HUNGRY.delta(Bond.WARMTH) > 0
                        && DeedType.GIFT_WANTED.delta(Bond.WARMTH) > 0,
                "both ungated kindnesses have to actually raise the axis the gate reads, or this is "
                        + "a door with no handle on the inside");
    }

    @Test
    @DisplayName("a villager takes rubbish from somebody they like and not from a stranger")
    void warmthDecidesTheUnwantedGift() {
        assertEquals(GiftPolicy.Verdict.REFUSED_STRANGER,
                offer(withWarmth(GiftPolicy.WARMTH_TO_ACCEPT_UNWANTED - 1),
                        DeedType.GIFT_UNWANTED, 0));
        assertEquals(GiftPolicy.Verdict.ACCEPTED,
                offer(withWarmth(GiftPolicy.WARMTH_TO_ACCEPT_UNWANTED),
                        DeedType.GIFT_UNWANTED, 0));
    }

    /**
     * <b>The gate closes again, and that is the whole reason this is warmth's mechanic rather than
     * trust's.</b>
     *
     * <p>Trust does not decay, so a gate on it opens once and never shuts — <i>he was kind to me a
     * season ago, so I will take his rubbish for ever</i>. Warmth falls a point an in-game day toward
     * four tenths of its high-water mark. Nothing else in the mod can express "lately".
     */
    @Test
    @DisplayName("stop turning up and the gate closes again")
    void theGateReopensBecauseWarmthDecays() {
        NpcRegistry registry = withWarmth(GiftPolicy.WARMTH_TO_ACCEPT_UNWANTED + 2);
        assertEquals(GiftPolicy.Verdict.ACCEPTED, offer(registry, DeedType.GIFT_UNWANTED, 0));
        assertEquals(GiftPolicy.Verdict.REFUSED_STRANGER, offer(registry, DeedType.GIFT_UNWANTED, 60),
                "two months later they are not taking your rubbish again");
    }

    /**
     * <b>The LNK check.</b> Gates from 35 to 205 against an observed maximum affinity of 32: zero
     * players ever reached the lowest one and nobody noticed for months. Session 07 measured warmth's
     * median across a village at 0–1 and its maximum at 56, and session 08 confirmed both with
     * propagation on. A threshold has to be read against those numbers before it is written down.
     */
    @Test
    @DisplayName("the threshold sits inside what a real villager actually reaches")
    void theThresholdIsBelowTheObservedMaximum() {
        assertTrue(GiftPolicy.WARMTH_TO_ACCEPT_UNWANTED <= 56 / 4,
                "session 07 and 08 measured warmth's observed maximum at 56-60 across a hundred "
                        + "in-game days. A gate near that is a gate nobody crosses, which is the "
                        + "failure LNK shipped five of.");
        assertTrue(GiftPolicy.WARMTH_TO_ACCEPT_UNWANTED > 1,
                "one point of warmth is a bystander's share of a single gift, which the decay takes "
                        + "back the next day. A gate at one is a gate on having stood nearby once.");
        assertTrue(GiftPolicy.WARMTH_TO_ACCEPT_UNWANTED <= DeedType.FED_HUNGRY.delta(Bond.WARMTH) * 2,
                "and it has to be reachable by being kind twice, which is what makes it low");
    }

    @Test
    @DisplayName("a full inventory is a different refusal from a cold one, and says so")
    void noRoomIsNotAJudgement() {
        NpcRegistry warm = withWarmth(100);
        assertEquals(GiftPolicy.Verdict.NO_ROOM,
                GiftPolicy.verdict(warm, ANNA, PLAYER, DeedType.GIFT_WANTED, false, 0),
                "somebody who likes you can still have nowhere to put it, and a player has to be "
                        + "able to tell the two apart");
    }
}
