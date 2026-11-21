package com.ethermod.client;

import com.ethermod.EtherMod;
import com.ethermod.item.ManipulatorColors;
import com.ethermod.item.EtherManipulatorItem;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Util;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import com.geckolib.constant.dataticket.DataTicket;
import com.geckolib.renderer.GeoItemRenderer;
import com.geckolib.renderer.base.GeoRenderState;
import com.geckolib.renderer.base.RenderPassInfo;
import com.geckolib.renderer.layer.GeoRenderLayer;

/**
 * The star on the emitter shows the mode colour (see {@link ManipulatorColors#crystal}), glows,
 * and slowly pulses brighter and back. It is two white masks drawn over the model: the four-pixel
 * star and its lighter centre. (The body colour is in the texture itself: {@link BodyTextures}.)
 */
@OnlyIn(Dist.CLIENT)
public class ManipulatorTintLayer
        extends GeoRenderLayer<EtherManipulatorItem, GeoItemRenderer.RenderData, GeoRenderState> {

    private static final Identifier STAR =
            Identifier.fromNamespaceAndPath(
                    EtherMod.MODID, "textures/item/ether_manipulator_star.png");
    private static final Identifier CORE =
            Identifier.fromNamespaceAndPath(
                    EtherMod.MODID, "textures/item/ether_manipulator_star_core.png");

    /** One pulse, in milliseconds. */
    private static final double PERIOD = 1200;

    /** True while the player holding this stack charges an orb with it. */
    private static boolean charging(ItemStack stack) {
        net.minecraft.world.entity.player.Player p = ManipulatorAnimations.holder(stack);
        return p != null
                && p.isUsingItem()
                && p.getUseItem().getItem() instanceof EtherManipulatorItem;
    }

    public ManipulatorTintLayer(GeoItemRenderer<EtherManipulatorItem> renderer) {
        super(renderer);
    }

    private static int mix(int a, int b, double t) {
        int r = (int) Math.round((a >> 16 & 255) + ((b >> 16 & 255) - (a >> 16 & 255)) * t);
        int g = (int) Math.round((a >> 8 & 255) + ((b >> 8 & 255) - (a >> 8 & 255)) * t);
        int bl = (int) Math.round((a & 255) + ((b & 255) - (a & 255)) * t);
        return r << 16 | g << 8 | bl;
    }

    /** {star, centre} colours at a pulse value 0..1. Same rule as model-v3/preview.py. */
    static int[] starColours(int mode, double pulse) {
        int bright = mix(mode, 0xFFFFFF, 0.55);
        int star = mix(mode, bright, pulse);
        int core = mix(mix(mode, 0xFFFFFF, 0.6), 0xFFFFFF, 0.4 + 0.6 * pulse);
        return new int[] {star, core};
    }

    /** The stack's star colours this frame, kept in the render state. */
    private static final DataTicket<int[]> COLOURS = DataTicket.create("ethermod_star_colours", int[].class);

    @Override
    public void addRenderData(
            EtherManipulatorItem item, GeoItemRenderer.RenderData data, GeoRenderState state, float partialTick) {
        ItemStack stack = data == null ? ItemStack.EMPTY : data.itemStack();
        if (stack.isEmpty()) return;
        double pulse = 0.5 - 0.5 * Math.cos(2 * Math.PI * (Util.getMillis() % (long) PERIOD) / PERIOD);
        int[] c = starColours(ManipulatorColors.crystal(stack, charging(stack)), pulse);
        // reload (FX bench): the star goes dark while the Ether is drawn in, flashes white on the clamp, then
        // relights in the mode colour
        var mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.player != null && stack == mc.player.getMainHandItem()) {
            float rt = ManipulatorAnimations.reloadTime();
            if (rt >= 0.05f && rt < 1.16f) c = new int[] {mix(c[0], 0x101010, 0.85), mix(c[1], 0x101010, 0.85)};
            else if (rt >= 1.16f && rt < 1.22f && !com.ethermod.config.ClientConfig.reduceFlashing())
                c = new int[] {0xFFFFFF, 0xFFFFFF};
        }
        state.addGeckolibData(COLOURS, c);
    }

    @Override
    public void submitRenderTask(RenderPassInfo<GeoRenderState> info, SubmitNodeCollector tasks) {
        int[] c = info.getGeckolibData(COLOURS);
        if (c == null || !info.willRender() || info.model().isMissingno()) return;
        draw(info, tasks, STAR, c[0]);
        draw(info, tasks, CORE, c[1]);
    }

    /** Redraws the posed model with a white mask, tinted and full bright. */
    private static void draw(RenderPassInfo<GeoRenderState> info, SubmitNodeCollector tasks, Identifier mask, int rgb) {
        int overlay = info.packedOverlay();
        tasks.order(1)
                .submitCustomGeometry(
                        info.poseStack(),
                        RenderTypes.entityCutout(mask),
                        (pose, vertices) -> {
                            var poseStack = info.poseStack();
                            poseStack.pushPose();
                            poseStack.last().set(pose);
                            info.renderPosed(
                                    () -> info.model().render(info, vertices, 0xF000F0, overlay, 0xFF000000 | rgb));
                            poseStack.popPose();
                        });
    }
}
