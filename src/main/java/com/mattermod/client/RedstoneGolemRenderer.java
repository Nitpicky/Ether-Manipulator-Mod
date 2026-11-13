package com.mattermod.client;

import com.mattermod.MatterMod;
import com.mattermod.entity.RedstoneGolem;

import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;

import software.bernie.geckolib.animation.AnimationState;
import software.bernie.geckolib.model.GeoModel;
import software.bernie.geckolib.renderer.GeoEntityRenderer;

/** The Redstone Golem (the user's x0.318 model, 1 texel per unit), its redstone lit by GolemSignalLayer. */
public final class RedstoneGolemRenderer extends GeoEntityRenderer<RedstoneGolem> {

    public RedstoneGolemRenderer(EntityRendererProvider.Context ctx) {
        super(ctx, new Model());
        addRenderLayer(new GolemSignalLayer<>(this, "monstrosity", g -> g));
        addRenderLayer(new EyePulseLayer<>(this, "monstrosity_eyes.png", 60));
        shadowRadius = 1.0f;
    }

    /** Tracks the body bone so a rider can sit on its seat as it is animated. */
    @Override
    public void render(RedstoneGolem golem, float yaw, float partialTick, com.mojang.blaze3d.vertex.PoseStack pose,
            net.minecraft.client.renderer.MultiBufferSource buffers, int light) {
        var body = getGeoModel().getBone("body").orElse(null);
        if (body != null) body.setTrackingMatrices(true);
        super.render(golem, yaw, partialTick, pose, buffers, light);
        if (body != null && golem.isVehicle()) {
            // the tracked matrix sits at the bone's pivot (10 units up), not at the model origin
            org.joml.Vector4f p = body.getLocalSpaceMatrix()
                    .transform(new org.joml.Vector4f(0, (float) (RedstoneGolem.SEAT_Y - body.getPivotY() / 16.0), 0, 1));
            golem.seatOffset = new net.minecraft.world.phys.Vec3(p.x(), p.y(), p.z());
        } else golem.seatOffset = null;
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
