package net.namesake.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.MobRenderer;
import net.minecraft.client.renderer.entity.layers.CustomHeadLayer;
import net.minecraft.client.renderer.entity.layers.ItemInHandLayer;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.npc.Villager;
import net.namesake.Namesake;

/**
 * <b>Draws a villager as a person.</b> {@code DESIGN.md} §9, and the whole of session 15's art.
 *
 * <h2>It extends {@code MobRenderer}, and not {@code HumanoidMobRenderer}</h2>
 *
 * <p>{@code HumanoidMobRenderer}'s constructor adds three layers on its own: {@code CustomHeadLayer},
 * {@code ItemInHandLayer} and <b>{@code ElytraLayer}</b>. So the obvious swap — extend the humanoid
 * renderer, get the humanoid model — <b>newly renders elytra on villagers</b>, which is a thing this
 * mod would have shipped and nobody would have gone looking for. §9's ruling 4.
 *
 * <p>What we add instead is two of those three. {@code CustomHeadLayer} because vanilla's villager
 * renderer has it and a villager wearing a carved pumpkin should still be wearing one.
 * {@code ItemInHandLayer} because it replaces vanilla's {@code CrossedArmsItemLayer} — a villager
 * model has crossed arms and ours does not, so the errand sack goes <b>in the hand</b>. That is a
 * dividend rather than a cost: {@code WORKPLAN.md} recorded the sack as <i>"invisible until session
 * 15 swaps the renderer"</i> and that was wrong, because {@code CrossedArmsItemLayer} was already
 * drawing it; the real gate is {@code ShowTradesToPlayer}'s own preconditions, and it is unchanged.
 *
 * <h2>What the swap deletes, and what pays for it</h2>
 *
 * <p>{@code VillagerRenderer}'s third layer is {@code VillagerProfessionLayer}, and it is generically
 * bounded on {@code VillagerHeadModel}, which {@code HumanoidModel} does not implement. So the swap
 * silently deletes vanilla's <b>biome type, profession and trade-level overlays</b>, and session 13's
 * headline was <i>you can tell who works by looking</i>.
 *
 * <p>Implementing the interface to keep the layer is <b>refused on correctness</b>: those overlay
 * textures are authored against the villager mesh's UV map and a humanoid mesh samples different
 * ones, so it would compile, run, and draw nonsense. Profession legibility is re-earned in
 * {@link Appearance.Clothing}'s eight shapes; the biome type is replaced in kind by the culture
 * palette, which is the axis this mod has. <b>The trade-level badge is lost at a glance and has no
 * scheduled home</b> — it is still drawn in the trade window.
 *
 * <h2>Four passes over one model</h2>
 *
 * <p>Body, clothing, hair, face — each the whole model with a different texture and a different
 * tint, which is what {@code VillagerProfessionLayer} itself does. Rejected: compositing a
 * {@code DynamicTexture} per persona, which is MCA's answer and costs 6.5 MB of heap at
 * {@code DESIGN.md} §8's four hundred plus a cache that has to be invalidated. §9's ruling 6.
 *
 * <p><b>This is client render cost and not server tick cost</b>, which is worth stating because §8's
 * ~5.95 µs budget is a server number and nothing in this file runs on the server thread.
 */
public final class VillagerLookRenderer extends MobRenderer<Villager, VillagerLookModel> {

    /**
     * Drawn under everything else when a villager has no persona yet, and on a server without this
     * mod's server half. A visible neutral rather than a missing texture.
     */
    private static final ResourceLocation FALLBACK = Appearances.texture("body/wide");

    private final VillagerLookModel wide;
    private final VillagerLookModel slim;

    public VillagerLookRenderer(EntityRendererProvider.Context context) {
        super(context, new VillagerLookModel(context.bakeLayer(VillagerLookModel.WIDE), false), 0.5F);
        this.wide = this.model;
        this.slim = new VillagerLookModel(context.bakeLayer(VillagerLookModel.SLIM), true);

        addLayer(new CustomHeadLayer<>(this, context.getModelSet(), context.getItemInHandRenderer()));
        addLayer(new ItemInHandLayer<>(this, context.getItemInHandRenderer()));
        addLayer(new Pass(this, Slot.CLOTHING));
        addLayer(new Pass(this, Slot.HAIR));
        addLayer(new Pass(this, Slot.FACE));
    }

    /**
     * The base body, tinted by the skin colormap.
     *
     * <p>{@code MobRenderer} draws the parent model with this texture before any layer runs, so this
     * is pass one of four and the other three are {@link Pass}es.
     */
    @Override
    public ResourceLocation getTextureLocation(Villager villager) {
        return Appearances.texture("body/" + lookOf(villager).body());
    }

    /**
     * <b>Vanilla's own scale, and the reason it is here rather than assumed.</b>
     *
     * <p>{@code VillagerRenderer} applies {@code 0.9375 × getAgeScale()}. Without this line every
     * villager in every existing save is visibly larger than they were, which reads as intentional
     * and therefore never gets reported. §9's ruling 5, hook one of three.
     */
    @Override
    protected void scale(Villager villager, PoseStack pose, float partialTick) {
        float scale = VillagerLookModel.VANILLA_SCALE;
        if (villager.isBaby()) {
            scale *= 0.5F;
        }
        pose.scale(scale, scale, scale);
    }

    /**
     * Hook two of three: the model's own baby proportions, which live on
     * {@code AgeableListModel.young} rather than in the scale above.
     *
     * <p>And hook three: the shadow. {@code VillagerRenderer} halves it for a baby, and a
     * full-size shadow under a half-size villager is the giveaway that the swap was done by
     * somebody counting hooks wrong.
     */
    @Override
    public void render(Villager villager, float yaw, float partialTick, PoseStack pose,
                       MultiBufferSource buffers, int light) {
        Appearance.Look look = lookOf(villager);
        this.model = look.slim() ? slim : wide;
        this.model.setBaby(villager.isBaby());
        this.shadowRadius = villager.isBaby() ? 0.25F : 0.5F;
        super.render(villager, yaw, partialTick, pose, buffers, light);
    }

    /**
     * This villager's appearance, never null.
     *
     * <p>The profession key is vanilla's own registry path, read off the entity standing there
     * rather than off a persona — the same source {@code Teaching} reads, and the reason
     * {@code Persona.professionId} was deleted at schema 8 for never having been written.
     */
    static Appearance.Look lookOf(Villager villager) {
        String profession = net.minecraft.core.registries.BuiltInRegistries.VILLAGER_PROFESSION
                .getKey(villager.getVillagerData().getProfession())
                .getPath();
        return Appearances.lookOf(villager.getId(), profession);
    }

    /** Which channel a {@link Pass} draws. */
    private enum Slot {
        CLOTHING, HAIR, FACE
    }

    /**
     * One extra pass over the same model, with its own texture and its own tint.
     *
     * <p>Exactly the shape of {@code VillagerProfessionLayer}, which is the point: this is vanilla's
     * own way of putting a second image on a mob and it needs no shader, no composite and no cache.
     */
    private static final class Pass extends RenderLayer<Villager, VillagerLookModel> {

        private final Slot slot;

        Pass(VillagerLookRenderer parent, Slot slot) {
            super(parent);
            this.slot = slot;
        }

        @Override
        public void render(PoseStack pose, MultiBufferSource buffers, int light, Villager villager,
                           float limbSwing, float limbSwingAmount, float partialTick,
                           float ageInTicks, float yaw, float pitch) {
            if (villager.isInvisible()) {
                return;
            }
            Appearance.Look look = lookOf(villager);
            ResourceLocation where = switch (slot) {
                case CLOTHING -> Appearances.texture("clothing/" + look.clothing().id());
                case HAIR -> Appearances.texture("hair/" + look.hair());
                case FACE -> Appearances.texture("face/" + look.face());
            };
            int tint = switch (slot) {
                case CLOTHING -> look.clothTint();
                case HAIR -> look.hairTint();
                case FACE -> 0xFFFFFFFF;
            };
            VertexConsumer buffer = buffers.getBuffer(
                    VillagerLookModel.passType().apply(where));
            getParentModel().renderToBuffer(pose, buffer, light,
                    net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY, tint);
        }
    }

    static {
        // A single INFO line the first time this class is touched, so a log from somebody's crash
        // report says whether the swap was even installed. It is one line for the life of the
        // process, which is the whole of what this mod logs on the client.
        Namesake.LOGGER.info("Villager renderer swapped onto the vanilla humanoid model");
    }
}
