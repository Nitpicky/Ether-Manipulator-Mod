package com.mattermod.client;

import com.mattermod.MatterMod;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;

import software.bernie.geckolib.animatable.GeoAnimatable;
import software.bernie.geckolib.cache.object.BakedGeoModel;
import software.bernie.geckolib.renderer.GeoRenderer;
import software.bernie.geckolib.renderer.layer.GeoRenderLayer;

/**
 * Living eyes: the eye texels (a mask in their own colours) drawn glowing over the model, tinted along the eyes' own tones.
 * Multiplying shifts every texel down the ladder white, amber, deep orange, so the eyes slowly pulse from hot white to
 * ember and back while keeping their pattern. Each creature runs its own phase.
 */
public final class EyePulseLayer<T extends Entity & GeoAnimatable> extends GeoRenderLayer<T> {

    /** white, pale amber, deep amber: the tint cycles through these and back */
    private static final int[] TINT = {0xFFFFFF, 0xFFD088, 0xFF9A4C};

    private final ResourceLocation mask;
    private final float period;

    public EyePulseLayer(GeoRenderer<T> renderer, String texture, float periodTicks) {
        super(renderer);
        this.mask = ResourceLocation.fromNamespaceAndPath(MatterMod.MODID, "textures/entity/" + texture);
        this.period = periodTicks;
    }

    static int mix(int a, int b, double t) {
        int r = (int) Math.round((a >> 16 & 255) + ((b >> 16 & 255) - (a >> 16 & 255)) * t);
        int g = (int) Math.round((a >> 8 & 255) + ((b >> 8 & 255) - (a >> 8 & 255)) * t);
        int bl = (int) Math.round((a & 255) + ((b & 255) - (a & 255)) * t);
        return r << 16 | g << 8 | bl;
    }

    @Override
    public void render(PoseStack poseStack, T entity, BakedGeoModel bakedModel, RenderType renderType,
            MultiBufferSource bufferSource, VertexConsumer buffer, float partialTick, int packedLight, int packedOverlay) {
        if (entity.isInvisible()) return;
        double t = ((entity.tickCount + partialTick) / period + entity.getId() * .37) % 1.0;
        double k = .5 - .5 * Math.cos(t * Math.PI * 2);             // 0 -> 1 -> 0, eased
        double x = k * (TINT.length - 1);
        int i = Math.min(TINT.length - 2, (int) x);
        int rgb = mix(TINT[i], TINT[i + 1], x - i);
        RenderType rt = GlowMaskLayer.type(mask);
        getRenderer().reRender(bakedModel, poseStack, bufferSource, entity, rt, bufferSource.getBuffer(rt), partialTick,
                LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY, 0xFF << 24 | rgb);
    }
}
