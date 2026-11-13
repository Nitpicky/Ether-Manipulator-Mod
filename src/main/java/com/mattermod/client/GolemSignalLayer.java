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
 * Cutout and full bright, so it stays solid under shader packs.
 */
public final class GolemSignalLayer extends GeoRenderLayer<RedstoneGolem> {

    private static final ResourceLocation[] SIGNAL = new ResourceLocation[6];
    private static final ResourceLocation FLASH = tex("monstrosity_flash.png"), AFTER = tex("monstrosity_flash_after.png");

    static {
        for (int i = 0; i < SIGNAL.length; i++) SIGNAL[i] = tex("monstrosity_signal_" + i + ".png");
    }

    private static ResourceLocation tex(String name) {
        return ResourceLocation.fromNamespaceAndPath(MatterMod.MODID, "textures/entity/" + name);
    }

    public GolemSignalLayer(GeoRenderer<RedstoneGolem> renderer) {
        super(renderer);
    }

    @Override
    public void render(PoseStack poseStack, RedstoneGolem golem, BakedGeoModel bakedModel, RenderType renderType,
            MultiBufferSource bufferSource, VertexConsumer buffer, float partialTick, int packedLight, int packedOverlay) {
        if (golem.isInvisible()) return;
        ResourceLocation tex = golem.flashing()
                ? FLASH
                : golem.afterglow()
                ? AFTER
                : SIGNAL[Math.min(SIGNAL.length - 1, Math.round(golem.signal(partialTick) * (SIGNAL.length - 1)))];
        RenderType rt = GlowMaskLayer.type(tex);
        getRenderer().reRender(bakedModel, poseStack, bufferSource, golem, rt, bufferSource.getBuffer(rt), partialTick,
                LightTexture.FULL_BRIGHT, packedOverlay, 0xFFFFFFFF);
    }
}
