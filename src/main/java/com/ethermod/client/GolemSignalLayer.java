package com.ethermod.client;

import com.ethermod.EtherMod;
import com.ethermod.entity.RedstoneGolem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;

import software.bernie.geckolib.cache.object.BakedGeoModel;
import software.bernie.geckolib.renderer.GeoRenderer;
import software.bernie.geckolib.renderer.layer.GeoRenderLayer;

/**
 * Lit redstone that shows the Redstone Generator's heat. Each of the texture's four redstone tones has its own white mask,
 * drawn full bright and tinted with the tone the Golem shows right now (RedstoneGolem.tones): at rest the texture's own
 * reds, warming toward orange as heat builds and throbbing toward the next hotter tone, faster as it heats; Overcharged it
 * throbs up to amber, the dark tones moving less. The eyes are drawn untinted. Ministrosities show their Golem's generator.
 */
public final class GolemSignalLayer<T extends net.minecraft.world.entity.Entity & software.bernie.geckolib.animatable.GeoAnimatable> extends GeoRenderLayer<T> {

    private final ResourceLocation[] TONE = new ResourceLocation[4];
    private final ResourceLocation EYES;
    /** The Golem whose generator this creature shows (itself, or a Ministrosity's Golem). */
    private final java.util.function.Function<T, RedstoneGolem> golemOf;

    private static ResourceLocation tex(String name) {
        return ResourceLocation.fromNamespaceAndPath(EtherMod.MODID, "textures/entity/" + name);
    }

    /** {@code prefix}: "monstrosity" or "ministrosity" (textures prefix_tone_0..3, prefix_lit_eyes). */
    public GolemSignalLayer(GeoRenderer<T> renderer, String prefix, java.util.function.Function<T, RedstoneGolem> golemOf) {
        super(renderer);
        for (int i = 0; i < TONE.length; i++) TONE[i] = tex(prefix + "_tone_" + i + ".png");
        EYES = tex(prefix + "_lit_eyes.png");
        this.golemOf = golemOf;
    }

    @Override
    public void render(PoseStack poseStack, T entity, BakedGeoModel bakedModel, RenderType renderType,
            MultiBufferSource bufferSource, VertexConsumer buffer, float partialTick, int packedLight, int packedOverlay) {
        if (entity.isInvisible()) return;
        RedstoneGolem golem = golemOf.apply(entity);
        int[] tones = golem == null ? RedstoneGolem.REST_TONES : golem.tones(partialTick);
        draw(EYES, 0xFFFFFF, poseStack, entity, bakedModel, bufferSource, partialTick, packedOverlay);
        for (int i = 0; i < TONE.length; i++) draw(TONE[i], tones[i], poseStack, entity, bakedModel, bufferSource, partialTick, packedOverlay);
    }

    private void draw(ResourceLocation tex, int rgb, PoseStack poseStack, T entity, BakedGeoModel bakedModel,
            MultiBufferSource bufferSource, float partialTick, int packedOverlay) {
        RenderType rt = GlowMaskLayer.type(tex);
        getRenderer().reRender(bakedModel, poseStack, bufferSource, entity, rt, bufferSource.getBuffer(rt), partialTick,
                LightTexture.FULL_BRIGHT, packedOverlay, 0xFF000000 | rgb);
    }
}
