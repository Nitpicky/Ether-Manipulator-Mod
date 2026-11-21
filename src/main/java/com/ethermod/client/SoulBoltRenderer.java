package com.ethermod.client;

import com.ethermod.entity.SoulBolt;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;

/** Vanilla's animated soul-fire texture gives the small fireball a soul-fire skin. */
public final class SoulBoltRenderer extends EntityRenderer<SoulBolt, EntityRenderState> {
    /** The soul fire's frames, stacked top to bottom (16 x 16 each). */
    private static final Identifier TEXTURE =
            Identifier.withDefaultNamespace("textures/block/soul_fire_0.png");

    private static final int FRAMES = 32;

    public SoulBoltRenderer(EntityRendererProvider.Context context) {
        super(context);
    }

    @Override
    public EntityRenderState createRenderState() {
        return new EntityRenderState();
    }

    @Override
    protected int getBlockLightLevel(SoulBolt e, net.minecraft.core.BlockPos pos) {
        return 15;
    }

    @Override
    public void submit(EntityRenderState state, PoseStack pose, SubmitNodeCollector collector, CameraRenderState camera) {
        int frame = Math.floorMod((int) state.ageInTicks, FRAMES);
        float v0 = frame / (float) FRAMES, v1 = (frame + 1) / (float) FRAMES;
        pose.pushPose();
        pose.rotate(camera.orientation);
        pose.scale(.75f, .75f, .75f);
        collector.submitCustomGeometry(pose, RenderTypes.entityCutout(TEXTURE), (p, v) -> {
            vertex(v, p, -.5f, -.25f, 0, v1);
            vertex(v, p, .5f, -.25f, 1, v1);
            vertex(v, p, .5f, .75f, 1, v0);
            vertex(v, p, -.5f, .75f, 0, v0);
        });
        pose.popPose();
        super.submit(state, pose, collector, camera);
    }

    private static void vertex(VertexConsumer v, PoseStack.Pose p, float x, float y, float u, float w) {
        v.addVertex(p, x, y, 0)
                .setColor(-1)
                .setUv(u, w)
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(15728880)
                .setNormal(p, 0, 1, 0);
    }
}
