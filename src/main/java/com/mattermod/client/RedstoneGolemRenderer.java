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

    /** Seats a rider on the body's top as it is animated: the seat point is carried through the body bone and its
     *  parents (GeckoLib order: position, pivot, rotate Z-Y-X, back from the pivot), then turned with the body yaw. */
    @Override
    public void render(RedstoneGolem golem, float yaw, float partialTick, com.mojang.blaze3d.vertex.PoseStack pose,
            net.minecraft.client.renderer.MultiBufferSource buffers, int light) {
        super.render(golem, yaw, partialTick, pose, buffers, light);
        var body = getGeoModel().getBone("body").orElse(null);
        if (body == null || !golem.isVehicle()) {
            golem.seatOffset = null;
            return;
        }
        org.joml.Vector3f p = new org.joml.Vector3f(0, (float) (RedstoneGolem.SEAT_Y * 16), 0);   // model units
        for (var b = body; b != null; b = b.getParent()) {
            org.joml.Vector3f piv = new org.joml.Vector3f(b.getPivotX(), b.getPivotY(), b.getPivotZ());
            p.sub(piv);
            new org.joml.Quaternionf().rotationZYX(b.getRotZ(), b.getRotY(), b.getRotX()).transform(p);
            p.add(piv).add(-b.getPosX(), b.getPosY(), b.getPosZ());
        }
        p.div(16);
        float bodyYaw = net.minecraft.util.Mth.rotLerp(partialTick, golem.yBodyRotO, golem.yBodyRot);
        new org.joml.Quaternionf().rotationY((float) Math.toRadians(180 - bodyYaw)).transform(p);
        golem.seatOffset = new net.minecraft.world.phys.Vec3(p.x(), p.y(), p.z());
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
