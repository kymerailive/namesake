package net.namesake.social;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.item.crafting.RecipeHolder;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * <b>A villager who trusts you shows you how they make things.</b> Session 12's second consumer,
 * and {@code WORKPLAN.md}'s <i>whether one recipe is taught</i>.
 *
 * <h2>What is taught: their own workstation, and nothing else</h2>
 *
 * <p>One recipe per profession, and it is the block they stand at all day. A librarian teaches you
 * to build a lectern, a shepherd a loom, a mason a stonecutter. That is legible without a word of
 * explanation — you watched them use it — and it costs no art, no data pack and no new registry
 * entry, because every one of these is a vanilla crafting recipe whose id is its own item's id.
 *
 * <p><b>And the librarian's is a lectern, which is session 11's Notice Board.</b> Nothing was
 * arranged to make that true; it falls out of the profession table. It is recorded rather than
 * built on: whether a village comes with a board already standing is session 15's question and this
 * does not answer it, but a trusted librarian teaching you to make one is a third answer that was
 * already there.
 *
 * <h2>Nothing is persisted, for the third session running</h2>
 *
 * <p>{@code ServerPlayer#awardRecipes} writes to the player's own recipe book, which vanilla has
 * saved since 1.12 and which is per-player by construction. So there is no flag of ours recording
 * that a recipe was taught, nothing to migrate, and — the part that matters for
 * {@code DESIGN.md} §10 step 7 — <b>no way for one player's unlock to be visible to another</b>.
 * It is also idempotent: {@code awardRecipes} filters what the player already knows and returns how
 * many were genuinely new, which is what lets this be called on every interaction rather than
 * guarded by state somebody has to keep.
 *
 * <h2>What this does <i>not</i> read, and the field it buries</h2>
 *
 * <p>It reads {@code villager.getVillagerData().getProfession()} — vanilla's own answer, on the
 * entity that is standing in front of the player at the only moment a recipe can be taught.
 * <b>It does not read {@code Persona.professionId}</b>, and that is this session's ruling on the
 * weakest exemption in the rule 5 ledger rather than an oversight. That field has been in the record
 * since session 01 and <b>nothing has ever written it</b>: {@code Persona.create} sets it to zero
 * and no wither changes it, so every persona in every save on disk holds the same constant. Its own
 * ledger entry has said since session 02 that it duplicates what vanilla stores on the villager and
 * that <i>if session 12 does not read it, delete it rather than moving this number</i>. Building a
 * write path for it now would be building a cache of vanilla state that can drift, which is what
 * session 03 deleted {@code Settlement.culture} for. So the field is gone at schema 8.
 */
public final class Teaching {

    /**
     * Each profession's own workstation, by recipe id.
     *
     * <p>Keyed on the profession's registry name rather than on the {@link VillagerProfession}
     * instance, for {@code Deed.item}'s reason from session 09 one layer up: a table keyed on an
     * object cannot be read without the registry, and a modded profession that is not in this map
     * has to be a miss rather than a crash. It is also what lets a unit test walk the whole table
     * without a server.
     *
     * <p><b>{@code NITWIT} and {@code NONE} are deliberately absent.</b> A villager with no trade
     * has nothing to show you, and that is an absence branch with a sentence of its own rather than
     * a silent nothing — see {@link #teach}.
     */
    private static final Map<String, String> WORKSTATION_RECIPES = Map.ofEntries(
            Map.entry("armorer", "blast_furnace"),
            Map.entry("butcher", "smoker"),
            Map.entry("cartographer", "cartography_table"),
            Map.entry("cleric", "brewing_stand"),
            Map.entry("farmer", "composter"),
            Map.entry("fisherman", "barrel"),
            Map.entry("fletcher", "fletching_table"),
            Map.entry("leatherworker", "cauldron"),
            Map.entry("librarian", "lectern"),
            Map.entry("mason", "stonecutter"),
            Map.entry("shepherd", "loom"),
            Map.entry("toolsmith", "smithing_table"),
            Map.entry("weaponsmith", "grindstone"));

    private Teaching() {
    }

    /** What one attempt did. Every branch is named, because most of them are absences. */
    public enum Outcome {
        /** The band is below {@link Standing#TRUSTED}. Nothing was offered. */
        NOT_TRUSTED_ENOUGH,
        /** A nitwit, or a villager who has not taken a job. There is nothing to teach. */
        NO_TRADE_TO_TEACH,
        /** A modded profession this table has never heard of, or a recipe a data pack removed. */
        NO_RECIPE_FOR_TRADE,
        /** They have shown you this before. */
        ALREADY_KNOWN,
        /** Newly unlocked in the player's own recipe book. */
        TAUGHT
    }

    /**
     * Teaches this player one recipe, if this villager stands high enough with them to bother.
     *
     * @param standing the band, from {@link Standing#of} — passed in rather than recomputed so that
     *                 the price and the recipe cannot disagree about the same bond read twice
     */
    public static Outcome teach(ServerPlayer player, Villager villager, Standing standing) {
        if (!standing.teachesARecipe()) {
            return Outcome.NOT_TRUSTED_ENOUGH;
        }
        Optional<ResourceLocation> recipeId = recipeFor(villager.getVillagerData().getProfession());
        if (recipeId.isEmpty()) {
            return Outcome.NO_TRADE_TO_TEACH;
        }
        Optional<RecipeHolder<?>> recipe = player.server.getRecipeManager().byKey(recipeId.get());
        if (recipe.isEmpty()) {
            return Outcome.NO_RECIPE_FOR_TRADE;
        }
        if (player.awardRecipes(List.of(recipe.get())) == 0) {
            return Outcome.ALREADY_KNOWN;
        }
        // Above the hotbar rather than in chat: this is a thing that just happened in front of you,
        // and vanilla's own recipe toast is already saying what was unlocked.
        player.displayClientMessage(
                Component.literal("They show you how it is done."), true);
        return Outcome.TAUGHT;
    }

    /**
     * Which recipe a profession knows, or empty for one with no trade and one this table has never
     * heard of.
     *
     * <p>Both misses are the same answer on purpose. A modded profession is not an error — the addon
     * API in {@code DESIGN.md} §2 hands professions to third parties — and a villager who has not
     * taken a job has nothing to show you, which is the same nothing.
     */
    public static Optional<ResourceLocation> recipeFor(VillagerProfession profession) {
        ResourceLocation key = BuiltInRegistries.VILLAGER_PROFESSION.getKey(profession);
        if (key == null || !"minecraft".equals(key.getNamespace())) {
            return Optional.empty();
        }
        String recipe = WORKSTATION_RECIPES.get(key.getPath());
        return recipe == null
                ? Optional.empty()
                : Optional.of(ResourceLocation.withDefaultNamespace(recipe));
    }

    /** The professions this table covers, for the test that walks the vanilla registry. */
    public static java.util.Set<String> professionsWithARecipe() {
        return WORKSTATION_RECIPES.keySet();
    }
}
