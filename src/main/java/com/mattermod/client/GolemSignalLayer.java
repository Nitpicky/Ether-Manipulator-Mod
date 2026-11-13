package com.mattermod.client;

import com.mattermod.MatterMod;
import com.mattermod.entity.RedstoneGolem;
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
 * Lit redstone that shows the Redstone Furnace's heat as signal strength. At rest it is the texture's own four redstone
 * tones, glowing; as heat builds the same four tones step up in signal (0, 3, 6, 9, 12, 15) and hue-shift toward the
 * eyes' amber, keeping their contrast. When the furnace blows, the overheat animation flashes briefly to the eyes' white, then steps back through a halfway tone to signal 15.
 * Ministrosities show their Golem's furnace the same way. Cutout and full bright, so it stays solid under shader packs.
 */
public final class GolemSignalLayer<T extends net.minecraft.world.entity.Entity & software.bernie.geckolib.animatable.GeoAnimatable> extends GeoRenderLayer<T> {

    private final ResourceLocation[] SIGNAL = new ResourceLocation[6];
    private final ResourceLocation FLASH, AFTER;
    /** The Golem whose furnace this creature shows (itself, or a Ministrosity's Golem). */
    private final java.util.function.Function<T, RedstoneGolem> golemOf;

    private static ResourceLocation tex(String name) {
        return ResourceLocation.fromNamespaceAndPath(MatterMod.MODID, "textures/entity/" + name);
    }

    /** {@code prefix}: "monstrosity" or "ministrosity" (textures prefix_signal_0..5, prefix_flash, prefix_flash_after). */
    public GolemSignalLayer(GeoRenderer<T> renderer, String prefix, java.util.function.Function<T, RedstoneGolem> golemOf) {
        super(renderer);
        for (int i = 0; i < SIGNAL.length; i++) SIGNAL[i] = tex(prefix + "_signal_" + i + ".png");
        FLASH = tex(prefix + "_flash.png");
        AFTER = tex(prefix + "_flash_after.png");
        this.golemOf = golemOf;
    }

    @Override
    public void render(PoseStack poseStack, T entity, BakedGeoModel bakedModel, RenderType renderType,
            MultiBufferSource bufferSource, VertexConsumer buffer, float partialTick, int packedLight, int packedOverlay) {
        if (entity.isInvisible()) return;
        RedstoneGolem golem = golemOf.apply(entity);
        ResourceLocation tex = golem == null
                ? SIGNAL[0]
                : golem.flashing()
                ? FLASH
                : golem.afterglow()
                ? AFTER
                : SIGNAL[Math.min(SIGNAL.length - 1, Math.round(golem.signal(partialTick) * (SIGNAL.length - 1)))];
        RenderType rt = GlowMaskLayer.type(tex);
        getRenderer().reRender(bakedModel, poseStack, bufferSource, entity, rt, bufferSource.getBuffer(rt), partialTick,
                LightTexture.FULL_BRIGHT, packedOverlay, 0xFFFFFFFF);
    }
}
