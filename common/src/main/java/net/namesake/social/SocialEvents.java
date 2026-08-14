package net.namesake.social;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.raid.Raider;
import net.minecraft.world.item.ItemStack;
import net.namesake.npc.NpcRegistry;
import net.namesake.npc.Persona;
import net.namesake.npc.PersonaService;
import net.namesake.settlement.Settlement;

/**
 * Where the six deed types actually come from in a running game.
 *
 * <p>Three doors, and every one of them is an event the engine already fires. Nothing here is on a
 * schedule and nothing here is a new packet: {@code DESIGN.md} §4 step 1 says a deed is emitted from
 * an interaction handler, a damage hook or a raid outcome, and these are those.
 *
 * <ul>
 *   <li>{@link #onGive} — the give gesture. <b>Sneak, something in the main hand, right-click a
 *       villager</b>, which is the empty-handed conversation gesture from session 02 with the hand
 *       full. Produces one of the three kindnesses.</li>
 *   <li>{@link #onHurt} — a player hurt a villager and it survived. {@code STRUCK_RESIDENT}.</li>
 *   <li>{@link #onDeath} — a player killed a villager ({@code KILLED_RESIDENT}), or killed a raider
 *       inside a settlement that is being raided ({@code DEFENDED_RAID}).</li>
 * </ul>
 *
 * <p><b>Which of the three kindnesses it is, is decided by vanilla and not by us.</b>
 * {@code Villager#wantsMoreFood} is the engine's own hunger threshold and
 * {@code Villager#wantsToPickUp} is the engine's own answer to "would this villager pick this up off
 * the ground", which already folds in the profession's requested items. Inventing a wanted-items
 * table here would be inventing a second source of truth for a question the game already answers,
 * and the addon API in {@code DESIGN.md} §2 is where a modpack gets to extend it.
 */
public final class SocialEvents {

    /** Below this much damage in one blow a strike still registers; above it, it registers fully. */
    private static final float SEVERITY_PER_DAMAGE = 12.5F;
    private static final int MIN_SEVERITY = 20;

    private SocialEvents() {
    }

    // --- giving --------------------------------------------------------------------------------

    /**
     * Sneaking, holding something, and looking at a villager.
     *
     * <p>Deliberately a {@code Villager} and not any persona carrier: a <i>zombie</i> villager plus
     * a held item plus a right-click is the vanilla cure, and intercepting that would break the one
     * interaction the attach bet is proven against.
     *
     * <p>It costs nothing that vanilla would otherwise have done. A sneaking right-click on a
     * villager with an item already goes to {@code Villager#mobInteract} and opens the trade screen;
     * the block in your hand was never going to be placed. So this changes which of our two gestures
     * the click means, and takes nothing away.
     */
    public static boolean isGiveGesture(Player player, InteractionHand hand, Entity target) {
        return hand == InteractionHand.MAIN_HAND
                && player.isShiftKeyDown()
                && !player.getMainHandItem().isEmpty()
                && target instanceof Villager;
    }

    /**
     * Hands one item over and emits the deed it turned out to be.
     *
     * <p><b>A deed is only emitted if the item actually changed hands.</b> Not politeness — if an
     * offer a villager cannot accept still emitted {@code GIFT_UNWANTED}, then a villager with a
     * full inventory is eight free points of trust a day, for ever, at no cost. Every deed here
     * costs a real item out of a real inventory.
     */
    public static void onGive(ServerPlayer player, InteractionHand hand, Villager villager) {
        if (!(player.level() instanceof ServerLevel level)) {
            return;
        }
        ItemStack held = player.getItemInHand(hand);
        if (held.isEmpty()) {
            return;
        }
        ItemStack one = held.copyWithCount(1);

        if (!villager.getInventory().canAddItem(one)) {
            player.displayClientMessage(
                    Component.literal("They have no room for that."), true);
            return;
        }

        DeedType type;
        if (Villager.FOOD_POINTS.containsKey(one.getItem()) && villager.wantsMoreFood()) {
            type = DeedType.FED_HUNGRY;
        } else if (villager.wantsToPickUp(one)) {
            type = DeedType.GIFT_WANTED;
        } else {
            type = DeedType.GIFT_UNWANTED;
        }

        villager.getInventory().addItem(one);
        if (!player.getAbilities().instabuild) {
            held.shrink(1);
        }

        DeedBus.Result result = DeedBus.emit(level, type, player, villager);
        player.displayClientMessage(Component.literal(switch (type) {
            case FED_HUNGRY -> "They were hungry.";
            case GIFT_WANTED -> "They wanted that.";
            default -> "They take it, politely.";
        } + (result.witnesses() > 0 ? "  (" + result.witnesses() + " watching)" : "")), true);
    }

    // --- violence ------------------------------------------------------------------------------

    /**
     * A blow that a villager survived.
     *
     * <p>The killing blow is deliberately not double-counted: at this point the engine has already
     * applied the damage, so a villager whose health has reached zero is the death hook's business
     * and gets {@code KILLED_RESIDENT} instead of a strike <i>and</i> a killing.
     */
    public static void onHurt(LivingEntity victim, DamageSource source, float amount) {
        if (!(victim instanceof Villager villager)
                || !(victim.level() instanceof ServerLevel level)
                || !(source.getEntity() instanceof ServerPlayer player)
                || victim.isDeadOrDying()) {
            return;
        }
        Deed deed = deedAt(level, DeedType.STRUCK_RESIDENT, player, villager)
                .withSeverity(severityOf(amount));
        DeedBus.emit(level, deed, player, villager, villager.position());
    }

    /**
     * A death a player caused: a resident killed, or a raider killed in a village being raided.
     *
     * <p>{@code ServerLevel#getRaidAt} is vanilla's own answer to "is this place under attack right
     * now", so {@code DEFENDED_RAID} needs no raid hook of its own and no polling. Killing twenty
     * raiders emits twenty deeds and moves the bond by eight, because that is what the daily cap is
     * for.
     */
    public static void onDeath(LivingEntity victim, DamageSource source) {
        if (!(victim.level() instanceof ServerLevel level)
                || !(source.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        if (victim instanceof Villager villager) {
            DeedBus.emit(level, DeedType.KILLED_RESIDENT, player, villager);
        } else if (victim instanceof Raider && level.getRaidAt(victim.blockPosition()) != null) {
            DeedBus.emit(level, DeedType.DEFENDED_RAID, player, null);
        }
    }

    /**
     * Damage in one blow, as a percentage of a deed's nominal weight. Two hearts is half of it.
     *
     * <p>Public rather than package-visible from session 07, so the headless simulation strikes with
     * the severity the game would actually produce rather than with a number chosen in the
     * simulation. It is the same reason the simulation calls {@code DeedBus.record}: a report built
     * on a second copy of the arithmetic is a report about the copy.
     */
    public static byte severityOf(float damage) {
        int scaled = Math.round(damage * SEVERITY_PER_DAMAGE);
        return (byte) Math.max(MIN_SEVERITY, Math.min(Deed.NOMINAL, scaled));
    }

    private static Deed deedAt(ServerLevel level, DeedType type, ServerPlayer actor, Villager subject) {
        int settlementId = NpcRegistry.get(level).settlements()
                .containing(level.dimension().location(), subject.blockPosition())
                .map(Settlement::id)
                .orElse(Persona.UNASSIGNED);
        return Deed.of(type, actor.getUUID(),
                PersonaService.personaOf(subject).map(Persona::id).orElse(actor.getUUID()),
                settlementId,
                Deed.dayOf(level));
    }
}
