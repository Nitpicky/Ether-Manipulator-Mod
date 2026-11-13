package com.mattermod.client;

import com.mattermod.MatterMod;
import com.mattermod.entity.RedstoneGolem;

import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;

import software.bernie.geckolib.animation.AnimationState;
import software.bernie.geckolib.model.GeoModel;
import software.bernie.geckolib.renderer.GeoEntityRenderer;

/** The Redstone Golem (the user's x0.318 model, 1 texel per unit), eyes glowing through monstrosity_glowmask.png. */
public final class RedstoneGolemRenderer extends GeoEntityRenderer<RedstoneGolem> {

    public RedstoneGolemRenderer(EntityRendererProvider.Context ctx) {
        super(ctx, new Model());
        addRenderLayer(new software.bernie.geckolib.renderer.layer.AutoGlowingGeoLayer<>(this));
        addRenderLayer(new GolemHeatLayer(this));
        shadowRadius = 1.0f;
    }

    /** The break-down animation tips it over; vanilla's death roll would do it twice. */
    @Override
    protected float getDeathMaxRotation(RedstoneGolem golem) {
        return 0;
    }

    static final class Model extends GeoModel<RedstoneGolem> {
        private static final ResourceLocation GEO = id("geo/entity/monstrosity.geo.json"),
                TEX = id("textures/entity/monstrosity.png"),
                ANIM = id("animations/entity/monstrosity.animation.json");

        private static ResourceLocation id(String path) {
            return ResourceLocation.fromNamespaceAndPath(MatterMod.MODID, path);
        }

        @Override
        public ResourceLocation getModelResource(RedstoneGolem golem) {
            return GEO;
        }

        @Override
        public ResourceLocation getTextureResource(RedstoneGolem golem) {
            return TEX;
        }

        @Override
        public ResourceLocation getAnimationResource(RedstoneGolem golem) {
            return ANIM;
        }

        /** The lamp bone on its back belonged to the retired Redstone Lamp trait: kept hidden. */
        @Override
        public void setCustomAnimations(RedstoneGolem golem, long instanceId, AnimationState<RedstoneGolem> state) {
            super.setCustomAnimations(golem, instanceId, state);
            var lamp = getAnimationProcessor().getBone("lamp");
            if (lamp != null) lamp.setHidden(true);
        }
    }
}
