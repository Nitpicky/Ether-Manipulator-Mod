package com.mattermod.client;

import com.mattermod.MatterMod;
import com.mattermod.entity.RedstoneGolem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;

import software.bernie.geckolib.cache.object.BakedGeoModel;
import software.bernie.geckolib.renderer.GeoRenderer;
import software.bernie.geckolib.renderer.layer.GeoRenderLayer;

/**
 * Overheat (Redstone Furnace): only the redstone heats up, red, hue-shifting through the eyes' deep orange up to their amber, and cools
 * back down when the overheat ends; the rock stays grey. monstrosity_heat.png holds the redstone
 * texels only (grey, keeping their light/dark steps), drawn glowing over the model in the heat colour.
 */
public final class GolemHeatLayer extends GeoRenderLayer<RedstoneGolem> {

    private static final ResourceLocation HEAT =
            ResourceLocation.fromNamespaceAndPath(MatterMod.MODID, "textures/entity/monstrosity_heat.png");
    /** Full-signal redstone, hue-shifted step by step up to the eyes' colours: deep orange, then amber at full heat. */
    private static final int[] RAMP = {0xFF3300, 0xFB4200, 0xF75200, 0xFC7A0E, 0xFFA61A};

    public GolemHeatLayer(GeoRenderer<RedstoneGolem> renderer) {
        super(renderer);
    }

    private static int mix(int a, int b, double t) {
        int r = (int) Math.round((a >> 16 & 255) + ((b >> 16 & 255) - (a >> 16 & 255)) * t);
        int g = (int) Math.round((a >> 8 & 255) + ((b >> 8 & 255) - (a >> 8 & 255)) * t);
        int bl = (int) Math.round((a & 255) + ((b & 255) - (a & 255)) * t);
        return r << 16 | g << 8 | bl;
    }

    @Override
    public void render(PoseStack poseStack, RedstoneGolem golem, BakedGeoModel bakedModel, RenderType renderType,
            MultiBufferSource bufferSource, VertexConsumer buffer, float partialTick, int packedLight, int packedOverlay) {
        float h = golem.heatGlow(partialTick);
        if (h <= 0.05f) return;
        double x = Math.min(1, h) * (RAMP.length - 1);
        int i = Math.min(RAMP.length - 2, (int) x);
        int rgb = mix(RAMP[i], RAMP[i + 1], x - i);
        int alpha = 255;
        RenderType rt = GlowMaskLayer.type(HEAT);
        getRenderer().reRender(bakedModel, poseStack, bufferSource, golem, rt, bufferSource.getBuffer(rt), partialTick,
                LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY, alpha << 24 | rgb);
    }
}
