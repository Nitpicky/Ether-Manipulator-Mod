package com.mattermod.beam;

import com.mattermod.entity.RedstoneBolt;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;

import org.joml.Matrix4f;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

/**
 * The Golem's Dispenser bolt, drawn like the Manipulator's orbs (pixel cubes, a spinning shell around a hot core,
 * orbiting pixels, a trail of shrinking cubes) but in redstone: the trail runs down vanilla's powered-wire colours,
 * from a full signal at the bolt to a dying one at its tail.
 */
public final class RedstoneBoltRenderer extends EntityRenderer<RedstoneBolt> {

    private static final ResourceLocation NO_TEXTURE =
            ResourceLocation.fromNamespaceAndPath("mattermod", "textures/entity/beam_ball.png");
    private static final int TRAIL_POINTS = 12;
    /** Vanilla redstone wire at power 15. */
    private static final float[] SIGNAL = {1f, .2f, 0f, .95f};
    private final Map<Integer, Deque<Vec3>> trails = new HashMap<>();

    public RedstoneBoltRenderer(EntityRendererProvider.Context ctx) {
        super(ctx);
        shadowRadius = 0;
    }

    @Override
    public boolean shouldRender(RedstoneBolt bolt, Frustum frustum, double x, double y, double z) {
        return true;
    }

    /** Vanilla's wire colour at a signal strength 0..15 (RedStoneWireBlock). */
    static float[] wire(float power) {
        float f = power / 15f;
        float r = f * .6f + (f > 0 ? .4f : .3f);
        float g = Math.max(0, Math.min(1, f * f * .7f - .5f));
        float b = Math.max(0, Math.min(1, f * f * .6f - .7f));
        return new float[] {r, g, b};
    }

    @Override
    public void render(RedstoneBolt bolt, float yaw, float partialTick, PoseStack ps, MultiBufferSource buf, int light) {
        if (com.mattermod.client.ShaderCompat.inShadowPass()) return;
        Minecraft mc = Minecraft.getInstance();
        Vec3 camPos = mc.gameRenderer.getMainCamera().getPosition();
        Vec3 worldPos = bolt.getPosition(partialTick);
        float time = bolt.tickCount + partialTick;
        ps.pushPose();
        Vec3 entRel = worldPos.subtract(camPos);
        ps.translate(-entRel.x, -entRel.y, -entRel.z);
        Matrix4f m = ps.last().pose();
        VertexConsumer vc = buf.getBuffer(RenderType.lightning());

        Deque<Vec3> trail = trails.computeIfAbsent(bolt.getId(), k -> new ArrayDeque<>());
        if (trail.isEmpty() || trail.peekFirst().distanceToSqr(worldPos) > .04) {
            trail.addFirst(worldPos);
            while (trail.size() > TRAIL_POINTS) trail.removeLast();
        }
        int i = 0;
        for (Vec3 p : trail) {
            if (i++ == 0) continue;
            float t = (float) i / (TRAIL_POINTS + 1);
            float[] c = wire(15 * (1 - t));
            BeamFx.spinCube(vc, m, p.subtract(camPos), BeamFx.px(.15f * (1 - t)), time * .2 + i, .5, c[0], c[1], c[2], .7f * (1 - t));
        }
        if (trails.size() > 64) trails.keySet().removeIf(id -> mc.level == null || mc.level.getEntity(id) == null);
        BeamBallRenderer.drawOrb(vc, m, entRel, .2f, SIGNAL, time, 1f);
        ps.popPose();
    }

    @Override
    public ResourceLocation getTextureLocation(RedstoneBolt bolt) {
        return NO_TEXTURE;
    }
}
