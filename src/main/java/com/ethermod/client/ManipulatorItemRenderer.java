package com.ethermod.client;

import com.ethermod.client.beam.Muzzle;
import com.ethermod.item.EtherManipulatorItem;
import com.ethermod.item.ManipulatorColors;
import com.ethermod.item.ManipulatorStage;

import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import com.geckolib.constant.DataTickets;
import com.geckolib.constant.dataticket.DataTicket;
import com.geckolib.renderer.GeoItemRenderer;
import com.geckolib.renderer.base.BoneSnapshots;
import com.geckolib.renderer.base.GeoRenderState;
import com.geckolib.renderer.base.RenderPassInfo;

/**
 * The Manipulator's GeckoLib renderer (26.3: render states). Captures the stack and its body
 * texture into the render state, keeps the whole-tool motion first person only, adds the ring's
 * inertia, and samples the nozzle for {@link Muzzle}.
 */
@OnlyIn(Dist.CLIENT)
public class ManipulatorItemRenderer extends GeoItemRenderer<EtherManipulatorItem> {

    private static final String MUZZLE_BONE = "muzzle_anchor";

    /** The stack being drawn (the animation controller reads it too). */
    public static final DataTicket<ItemStack> STACK = DataTicket.create("ethermod_stack", ItemStack.class);

    private static final DataTicket<Identifier> TEXTURE =
            DataTicket.create("ethermod_body_texture", Identifier.class);

    public ManipulatorItemRenderer() {
        super(new ManipulatorItemModel());
        withRenderLayer(new ManipulatorTintLayer(this));
    }

    @Override
    public void addRenderData(
            EtherManipulatorItem item, RenderData data, GeoRenderState state, float partialTick) {
        ItemStack stack = data == null ? ItemStack.EMPTY : data.itemStack();
        state.addGeckolibData(STACK, stack);
        // the texture recoloured for the stack's body colour (the painted one by default)
        state.addGeckolibData(
                TEXTURE,
                stack.isEmpty()
                        ? BodyTextures.BASE
                        : BodyTextures.get(ManipulatorColors.body(stack), ManipulatorStage.get(stack)));
    }

    @Override
    public Identifier getTextureLocation(GeoRenderState state) {
        Identifier t = state.getGeckolibData(TEXTURE);
        return t == null ? BodyTextures.BASE : t;
    }

    private static boolean firstPerson(ItemDisplayContext ctx) {
        return ctx == ItemDisplayContext.FIRST_PERSON_RIGHT_HAND
                || ctx == ItemDisplayContext.FIRST_PERSON_LEFT_HAND;
    }

    private static boolean thirdPerson(ItemDisplayContext ctx) {
        return ctx == ItemDisplayContext.THIRD_PERSON_RIGHT_HAND
                || ctx == ItemDisplayContext.THIRD_PERSON_LEFT_HAND;
    }

    @Override
    public void preRenderPass(RenderPassInfo<GeoRenderState> info, SubmitNodeCollector tasks) {
        super.preRenderPass(info, tasks);
        ItemDisplayContext ctx = info.getGeckolibData(DataTickets.ITEM_RENDER_PERSPECTIVE);
        boolean fp = firstPerson(ctx);
        if ((fp || thirdPerson(ctx)) && !ShaderCompat.inShadowPass())
            // the pose is at the bone's pivot: the centre of the nozzle's front face
            info.model()
                    .getBone(MUZZLE_BONE)
                    .ifPresent(
                            bone ->
                                    info.addPerBoneRender(
                                            bone,
                                            (pass, b, collector) ->
                                                    Muzzle.capture(pass.poseStack().last().pose(), 0, 0, 0, fp)));
    }

    /**
     * After the keyframes: whole-tool motion (kicks, recoil, inspect turn) is first person only,
     * and the ring gets its inertia, including the Energy charge's spin (see
     * ManipulatorAnimations.ring).
     */
    @Override
    public void adjustModelBonesForRender(RenderPassInfo<GeoRenderState> info, BoneSnapshots snapshots) {
        ItemDisplayContext ctx = info.getGeckolibData(DataTickets.ITEM_RENDER_PERSPECTIVE);
        boolean fp = firstPerson(ctx);
        // in third person and on screens the tool stays put in the hand: only its prongs and ring move
        if (!fp) snapshots.ifPresent("manipulator", b -> b.setTranslation(0, 0, 0).setRotation(0, 0, 0));
        // the ring's inertia belongs to a tool in a hand: an icon (a creative tab, a slot, an item frame) drawn in
        // the same frame would feed the holder's ring a second motor angle and set both spinning erratically
        if (!fp && !thirdPerson(ctx)) return;
        ItemStack stack = info.getGeckolibData(STACK);
        var manager = info.getGeckolibData(DataTickets.ANIMATABLE_MANAGER);
        var main = manager == null ? null : manager.getAnimationControllers().get("main");
        boolean blending = main != null && main.isTransitioning();
        var holder = ManipulatorAnimations.holder(stack);
        snapshots.ifPresent(
                "prongs",
                ring -> {
                    float raw = (float) Math.toDegrees(ring.getRotZ());
                    float extra = ManipulatorAnimations.ring(holder, raw, blending);
                    if (extra != 0) ring.setRotZ(ring.getRotZ() + (float) Math.toRadians(extra));
                });
    }
}
