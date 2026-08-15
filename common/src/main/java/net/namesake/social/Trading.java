package net.namesake.social;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.trading.MerchantOffer;
import net.namesake.npc.NpcRegistry;
import net.namesake.npc.Persona;
import net.namesake.npc.PersonaService;

import java.util.Optional;

/**
 * <b>What your standing costs you at the counter.</b> Session 12's first consumer, and the first
 * time this mod changes a number a player was already looking at.
 *
 * <h2>Vanilla is already doing this, and we add to it rather than replacing it</h2>
 *
 * <p>Nothing in this repository had ever touched {@code Villager#updateSpecialPrices},
 * {@code getPlayerReputation} or {@code specialPriceDiff}, and vanilla runs a complete reputation
 * system through them: its own gossip container, its own decay, its own numbers. So there were about
 * to be two systems adjusting one number, and the decision had to be made out loud.
 *
 * <p><b>Read first, from the patched sources on both loaders.</b> Vanilla's mechanism is:
 *
 * <ul>
 *   <li>{@code Villager#startTrading} calls {@code updateSpecialPrices(player)} <i>every time</i> a
 *       trade window opens, and {@code stopTrading} calls {@code resetSpecialPrices()} when it
 *       closes. The adjustment is per-open and per-player by construction;</li>
 *   <li>{@code updateSpecialPrices} <b>adds</b> two contributions into one accumulator — the gossip
 *       reputation, scaled by each offer's own {@code priceMultiplier}, and Hero of the Village.
 *       {@code MerchantOffer#addToSpecialPriceDiff} is the method's name, and it means what it
 *       says;</li>
 *   <li>{@code MerchantOffer#getModifiedCostCount} is
 *       {@code clamp(base + demand + specialPriceDiff, 1, maxStackSize)}, so the whole thing is an
 *       integer added to an item count.</li>
 * </ul>
 *
 * <p><b>So the number is already an accumulator with two contributors in it, and we are the
 * third.</b> Replacing vanilla's would mean suppressing Hero of the Village and the discount for
 * curing a zombie villager — vanilla behaviour a player already knows — which is replacing the
 * entity's behaviour by the back door, and {@code DESIGN.md} §2's first architectural row is
 * <i>attach a Persona to the vanilla Villager, never replace the entity</i>. Leaving it alone would
 * mean not shipping the consumer. So: <b>we add.</b>
 *
 * <p><b>What a player sees when both fire.</b> Exactly what vanilla already draws. The trade screen
 * renders a struck-through original cost beside the adjusted one whenever
 * {@code specialPriceDiff != 0}, in both directions — vanilla's own negative reputation produces a
 * markup through the same field. So the ruled 0.75 renders itself and this session ships no art, no
 * screen and no packet for its most visible consumer. A player who has cured a zombie villager
 * <i>and</i> stands {@link Standing#WARM} with them gets both discounts, and one who murdered
 * somebody in the square gets vanilla's gossip markup and ours together. Both systems are describing
 * the same relationship from two angles; ours is the one that travels down a road.
 *
 * <h2>The two things that would leak between players, and the guards for them</h2>
 *
 * <p>{@code DESIGN.md} §10 step 7 is this session's exit criterion — <i>a second player who has done
 * nothing gets stranger lines and 1.00 prices everywhere</i> — and a price lives on the offer rather
 * than on the viewer, so it is the one consumer of the three that could get it wrong.
 *
 * <ol>
 *   <li><b>We reset before we add.</b> Our hook runs on every interaction with a villager, including
 *       ones that open no window at all, so without a reset a diff set for one player would still be
 *       sitting there when the next one opened the screen. Resetting first also means our own
 *       contribution can never accumulate across two clicks.</li>
 *   <li><b>We decline while somebody else has the window open.</b> Vanilla's {@code mobInteract}
 *       returns without trading when the villager is already trading, but our hook runs
 *       <i>before</i> that check — so rewriting the offers underneath an open screen would change
 *       what the other player pays. {@link #shouldPrice} refuses.</li>
 * </ol>
 */
public final class Trading {

    private Trading() {
    }

    /**
     * What one call did, so a harness leg and a test can assert on it rather than on a screen.
     *
     * <p>{@code priced} is not the same as {@code offersMoved > 0} and the difference is the point:
     * a villager standing {@link Standing#NEUTRAL} is priced and moves nothing, and a villager whose
     * counter we declined to touch is not priced at all. Reporting the first as the second is how a
     * guard against the second goes green for the wrong reason.
     */
    public record Applied(Standing standing, float multiplier, int offersMoved, int totalDiff,
                          boolean priced) {

        /** Nothing was priced: no persona, a baby, or somebody else is already at the counter. */
        public static final Applied NONE = new Applied(Standing.NEUTRAL, 1.00F, 0, 0, false);
    }

    /**
     * The one door both loaders call: price the counter, then teach if they will.
     *
     * <p>Called from the entity-interact hook on the path that <b>lets vanilla proceed</b> — the
     * plain right-click, after session 02's conversation gesture and session 05's give gesture have
     * each had their turn and declined it. So this runs immediately before
     * {@code Villager#mobInteract} opens the trade screen, and vanilla's own
     * {@code updateSpecialPrices} adds the gossip reputation on top of what we just set.
     *
     * <p><b>One bond read, two consumers.</b> The band is computed once and handed to
     * {@link Teaching}, rather than each mechanic asking the registry for itself: two reads of one
     * bond on either side of a day boundary would be two different bands, and a player being charged
     * one standing's price while taught another standing's recipe is the shape session 11 refused
     * when it made the board ask {@code Dialogue.poolFor} instead of implementing it again.
     */
    public static Applied onVillagerInteract(ServerPlayer player, Villager villager) {
        Applied applied = onTradeOpening(player, villager);
        if (applied.priced()) {
            Teaching.teach(player, villager, applied.standing());
        }
        return applied;
    }

    /**
     * Prices this villager's offers for this player, now.
     *
     * <p>Called from both loaders' entity-interact hook on the path that lets vanilla proceed, so it
     * runs immediately before {@code Villager#startTrading} calls {@code updateSpecialPrices} and
     * adds vanilla's own contribution on top of ours.
     *
     * <p><b>Recomputed on every open and stored nowhere</b>, which is session 11's argument arriving
     * at a second surface: a price is a pure function of a bond that is already on disk, so there is
     * nothing to persist, nothing to migrate and nothing that can drift from the bond it describes.
     * It is also what makes step 7 structural rather than lucky — there is nowhere for one player's
     * price to be kept, so there is nowhere for another to read it from.
     */
    public static Applied onTradeOpening(ServerPlayer player, Villager villager) {
        if (!(player.level() instanceof ServerLevel level) || !shouldPrice(player, villager)) {
            return Applied.NONE;
        }
        Optional<Persona> persona = PersonaService.personaOf(villager);
        if (persona.isEmpty()) {
            return Applied.NONE;
        }

        NpcRegistry registry = NpcRegistry.get(level);
        Bond bond = registry.bonds().at(persona.get().id(), player.getUUID(), Deed.dayOf(level));
        Standing standing = Standing.of(bond);
        return applyTo(villager, standing);
    }

    /**
     * The half with no level in it, so a test can hand it a villager and a band.
     *
     * <p>Split out for the reason session 07 split {@code DeedBus.record} out of
     * {@code DeedBus.emit}: everything above this line is asking the world a question, and
     * everything below it is arithmetic that should not need a server to check.
     */
    public static Applied applyTo(Villager villager, Standing standing) {
        float multiplier = standing.priceMultiplier();
        int moved = 0;
        int total = 0;
        for (MerchantOffer offer : villager.getOffers()) {
            // Reset first — see the class note. Our contribution is recomputed from scratch on every
            // open; vanilla's is added after us and cleared by its own stopTrading.
            offer.resetSpecialPriceDiff();
            int diff = adjustmentFor(offer.getBaseCostA().getCount(), multiplier);
            if (diff != 0) {
                offer.addToSpecialPriceDiff(diff);
                moved++;
                total += diff;
            }
        }
        return new Applied(standing, multiplier, moved, total, true);
    }

    /**
     * How many items to add to a cost of {@code baseCount} to charge {@code multiplier} of it.
     *
     * <p><b>Off the base cost rather than off the demand-adjusted one.</b> Vanilla's demand term is
     * a separate accumulator computed inside {@code getModifiedCostCount}, and multiplying a
     * multiplier by a multiplier is how a 0.75 becomes a number nobody can predict.
     *
     * <p>Rounding is {@link Deeds#round}, away from zero, for the reason it exists: a discount that
     * rounds to nothing on a cheap trade is a discount the player was told they had. It still
     * happens — a cost of one item cannot move by any multiplier here, and vanilla's own
     * {@code clamp(…, 1, …)} would refuse it anyway — which is a real property of a band expressed
     * as a fraction of a price, and it is stated rather than hidden.
     */
    public static int adjustmentFor(int baseCount, float multiplier) {
        return Deeds.round(baseCount * (multiplier - 1F));
    }

    /**
     * Whether this interaction is one we may price at all.
     *
     * <p>The second guard in the class note. A villager already trading with somebody else has that
     * player's screen open against these very offer objects, so rewriting them would change what a
     * different person pays — and it would do it invisibly, because their client already has the
     * offers it drew.
     */
    public static boolean shouldPrice(ServerPlayer player, Villager villager) {
        return !villager.isBaby()
                && (villager.getTradingPlayer() == null || villager.getTradingPlayer() == player);
    }
}
