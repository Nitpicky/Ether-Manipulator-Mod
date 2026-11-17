package com.ethermod.client;

import com.ethermod.EtherMod;
import com.ethermod.item.ManipulatorColors;
import com.ethermod.item.EtherManipulatorItem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.Util;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import software.bernie.geckolib.cache.object.BakedGeoModel;
import software.bernie.geckolib.renderer.GeoItemRenderer;
import software.bernie.geckolib.renderer.layer.GeoRenderLayer;

/**
 * The star on the emitter shows the mode colour (see {@link ManipulatorColors#crystal}), glows,
 * and slowly pulses brighter and back. It is two white masks drawn over the model: the four-pixel
 * star and its lighter centre. (The body colour is in the texture itself: {@link BodyTextures}.)
 */
@OnlyIn(Dist.CLIENT)
public class ManipulatorTintLayer extends GeoRenderLayer<EtherManipulatorItem> {

    private static final ResourceLocation STAR =
            ResourceLocation.fromNamespaceAndPath(
                    EtherMod.MODID, "textures/item/ether_manipulator_star.png");
    private static final ResourceLocation CORE =
            ResourceLocation.fromNamespaceAndPath(
                    EtherMod.MODID, "textures/item/ether_manipulator_star_core.png");

    /** One pulse, in milliseconds. */
    private static final double PERIOD = 1200;

    private final GeoItemRenderer<EtherManipulatorItem> itemRenderer;

    /** True while the player holding this stack charges an orb with it. */
    private static boolean charging(ItemStack stack) {
        net.minecraft.world.entity.player.Player p = ManipulatorAnimations.holder(stack);
        return p != null
                && p.isUsingItem()
                && p.getUseItem().getItem() instanceof EtherManipulatorItem;
    }

    public ManipulatorTintLayer(GeoItemRenderer<EtherManipulatorItem> renderer) {
        super(renderer);
        this.itemRenderer = renderer;
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

    @Override
    public void render(
            PoseStack poseStack,
            EtherManipulatorItem animatable,
            BakedGeoModel bakedModel,
            RenderType renderType,
            MultiBufferSource bufferSource,
            VertexConsumer buffer,
            float partialTick,
            int packedLight,
            int packedOverlay) {
        ItemStack stack = itemRenderer.getCurrentItemStack();
        if (stack == null || stack.isEmpty()) return;

        double pulse = 0.5 - 0.5 * Math.cos(2 * Math.PI * (Util.getMillis() % (long) PERIOD) / PERIOD);
        int[] c = starColours(ManipulatorColors.crystal(stack, charging(stack)), pulse);
        draw(STAR, c[0], poseStack, animatable, bakedModel, bufferSource, partialTick);
        draw(CORE, c[1], poseStack, animatable, bakedModel, bufferSource, partialTick);
    }

    private void draw(
            ResourceLocation mask,
            int rgb,
            PoseStack poseStack,
            EtherManipulatorItem animatable,
            BakedGeoModel bakedModel,
            MultiBufferSource bufferSource,
            float partialTick) {
        RenderType rt = RenderType.entityCutoutNoCull(mask);
        getRenderer()
                .reRender(
                        bakedModel,
                        poseStack,
                        bufferSource,
                        animatable,
                        rt,
                        bufferSource.getBuffer(rt),
                        partialTick,
                        LightTexture.FULL_BRIGHT,
                        OverlayTexture.NO_OVERLAY,
                        0xFF000000 | rgb);
    }
}
