package com.ethermod.client;

import com.ethermod.EtherMod;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;

import com.geckolib.animatable.GeoAnimatable;
import com.geckolib.cache.model.BakedGeoModel;
import com.geckolib.renderer.GeoRenderer;
import com.geckolib.renderer.layer.GeoRenderLayer;

/**
 * Lit redstone: the glow mask (the texture's own redstone tones, plus the eyes) drawn over the model at full
 * brightness with a cutout render type. Emissive (translucent) types turn see-through under shader packs; a cutout one
 * stays solid there and still ignores the darkness.
 */
public final class GlowMaskLayer<T extends Entity & GeoAnimatable> extends GeoRenderLayer<T> {

    private final Identifier mask;

    public GlowMaskLayer(GeoRenderer<T> renderer, String texture) {
        super(renderer);
        this.mask = Identifier.fromNamespaceAndPath(EtherMod.MODID, "textures/entity/" + texture);
    }

    /** The cutout, full-bright type every glow layer of these creatures uses. */
    static RenderType type(Identifier texture) {
        return RenderType.entityCutoutNoCull(texture);
    }

    @Override
    public void render(PoseStack poseStack, T entity, BakedGeoModel bakedModel, RenderType renderType,
            MultiBufferSource bufferSource, VertexConsumer buffer, float partialTick, int packedLight, int packedOverlay) {
        if (entity.isInvisible()) return;
        RenderType rt = type(mask);
        getRenderer().reRender(bakedModel, poseStack, bufferSource, entity, rt, bufferSource.getBuffer(rt), partialTick,
                LightTexture.FULL_BRIGHT, packedOverlay, 0xFFFFFFFF);
    }
}
