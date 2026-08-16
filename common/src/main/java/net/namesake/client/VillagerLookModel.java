package net.namesake.client;

import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.npc.Villager;
import net.namesake.Namesake;

import java.util.function.Function;

/**
 * <b>The vanilla humanoid model, drawing a villager.</b> {@code DESIGN.md} §9, ruling 1: <i>use the
 * vanilla humanoid model verbatim — swap the renderer, not the entity.</i>
 *
 * <h2>Two layer definitions, not one, and it is not a registration line</h2>
 *
 * <p>{@code VillagerModel} is a {@code HierarchicalModel} and {@code HumanoidModel} is an
 * {@code AgeableListModel}. <b>They meet at {@code EntityModel} and share nothing below it</b>, so
 * there is no part of vanilla's villager rendering that can be reused — not the mesh, not the layer
 * location, not the animation. What §9 calls "swap the renderer" is a new renderer, a new model, two
 * {@link ModelLayerLocation}s and two {@link LayerDefinition}s.
 *
 * <p>Two, because <b>only {@link PlayerModel#createMesh} takes a slim flag.</b>
 * {@code HumanoidModel.createMesh} has no slim variant at all, so a wide-only villager population
 * would be a decision made by an API rather than by this document.
 *
 * <p>{@code PlayerModel} rather than {@code HumanoidModel} for a second reason that pays for itself:
 * it produces the 64×64 player-skin layout, hat and jacket layers included — which is exactly what
 * §9 means by <i>author in Blockbench skin-edit mode</i>. Every texture in this mod is a thing a
 * person can open in a skin editor, and that is the difference between twenty-five textures being
 * authorable by a contributor and being authorable by whoever wrote the model.
 */
public final class VillagerLookModel extends PlayerModel<Villager> {

    /** The wide build. {@code namesake:villager_wide}. */
    public static final ModelLayerLocation WIDE = new ModelLayerLocation(
            ResourceLocation.fromNamespaceAndPath(Namesake.MOD_ID, "villager"), "wide");

    /** The slim build — three-pixel arms. {@code namesake:villager_slim}. */
    public static final ModelLayerLocation SLIM = new ModelLayerLocation(
            ResourceLocation.fromNamespaceAndPath(Namesake.MOD_ID, "villager"), "slim");

    /**
     * Vanilla's own villager scale, and the one number a naive swap gets wrong.
     *
     * <p>{@code VillagerRenderer} applies {@code 0.9375F} — fifteen sixteenths — before it draws
     * anything. A humanoid model at 1.0 renders <b>every villager in the world visibly larger than
     * vanilla</b>, in every existing save, which is the kind of change nobody reports as a bug
     * because it looks deliberate.
     */
    public static final float VANILLA_SCALE = 0.9375F;

    public VillagerLookModel(net.minecraft.client.model.geom.ModelPart root, boolean slim) {
        super(root, slim);
    }

    /**
     * @param slim three-pixel arms
     */
    public static LayerDefinition mesh(boolean slim) {
        return LayerDefinition.create(PlayerModel.createMesh(CubeDeformation.NONE, slim), 64, 64);
    }

    /**
     * The render type every one of this model's passes uses.
     *
     * <p>{@code entityTranslucent} rather than {@code entityCutoutNoCull}, because the hair, the
     * clothing and the face are drawn <i>over</i> the body and each is mostly transparent. A cutout
     * type would draw their empty pixels as black.
     */
    public static Function<ResourceLocation, RenderType> passType() {
        return RenderType::entityTranslucent;
    }

    /**
     * Marks this model as a baby, which is where vanilla's villager renderer does not do it.
     *
     * <p>{@code VillagerRenderer} folds the baby into its {@code scale} hook. {@link HumanoidModel}
     * has {@code young} instead, and it does more than halve the size — it moves the head, which is
     * what makes a baby read as a baby rather than as a small adult. §9's ruling 5: <b>three hooks,
     * not one.</b>
     */
    public void setBaby(boolean baby) {
        this.young = baby;
    }
}
