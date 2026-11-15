package com.mattermod.beam;

import com.mattermod.client.ClientMobility;
import com.mattermod.network.payload.ModulePayloads;
import com.mattermod.trait.Lodestar;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import org.joml.Matrix4f;

import java.util.Iterator;

/**
 * Photonic markers drawn in the world: Lumen Trail's arrows along the path you walked (pointing
 * back the way you came), and the Lodestar, a star in the sky you can see from anywhere in the
 * dimension.
 */
@OnlyIn(Dist.CLIENT)
final class ModuleMarkers {

    private ModuleMarkers() {}

    static void render(
            Minecraft mc,
            Vec3 camPos,
            Vec3 camLeft,
            float time,
            MultiBufferSource.BufferSource buf,
            Matrix4f m) {
        renderTrail(camPos, time, buf, m);
        renderLodestar(mc, camPos, camLeft, time, buf, m);
    }

    private static void renderTrail(
            Vec3 camPos, float time, MultiBufferSource.BufferSource buf, Matrix4f m) {
        float alpha = ClientMobility.trailAlpha;
        if (alpha <= 0 || ClientMobility.TRAIL.size() < 2) return;
        VertexConsumer vc = com.mattermod.client.Fx.glow();
        Iterator<Vec3> it = ClientMobility.TRAIL.iterator();
        Vec3 newer = it.next();
        int i = 0;
        while (it.hasNext()) {
            Vec3 older = it.next();
            i++;
            // arrows point the way you walked: from the older point toward the newer one
            Vec3 dir = newer.subtract(older).multiply(1, 0, 1);
            Vec3 at = newer.add(older).scale(0.5);
            newer = older;
            if (dir.lengthSqr() < 1e-4 || at.distanceTo(camPos) > 64) continue;
            dir = dir.normalize();
            Vec3 side = new Vec3(-dir.z, 0, dir.x);
            // a soft wave of brightness runs along the trail, away from you
            float wave = (float) (0.55 + 0.45 * Math.sin(i * 0.6 - time * 0.25));
            int a = (int) (200 * alpha * wave * Math.max(0.3, 1 - i / 128.0));
            Vec3 c = at.subtract(camPos).add(0, 0.06, 0);
            chevron(vc, m, c, dir, side, a);
        }
    }

    /**
     * A one-pixel chevron: two strokes 1/16 block thick that share their tip edge, so the corner
     * comes to a clean point and nothing is drawn twice.
     */
    private static void chevron(
            VertexConsumer vc, Matrix4f m, Vec3 c, Vec3 dir, Vec3 side, int alpha) {
        double t = 1 / 16.0 * 1.41; // measured along dir, so the stroke itself is ~1 px
        Vec3 tip = c.add(dir.scale(0.2));
        Vec3 back = dir.scale(-t);
        for (int s = -1; s <= 1; s += 2) {
            Vec3 end = c.add(dir.scale(-0.15)).add(side.scale(0.35 * s));
            Vec3 a0 = tip, a1 = end, a2 = end.add(back), a3 = tip.add(back);
            quad(vc, m, a0, a1, a2, a3, 255, 240, 160, alpha);
            quad(vc, m, a3, a2, a1, a0, 255, 240, 160, alpha);
        }
    }

    private static void renderLodestar(
            Minecraft mc,
            Vec3 camPos,
            Vec3 camLeft,
            float time,
            MultiBufferSource.BufferSource buf,
            Matrix4f m) {
        ModulePayloads.LodestarMark mark = Lodestar.clientMark;
        if (mark == null || mc.level == null) return;
        if (mc.level.getGameTime() > mark.expires()) {
            Lodestar.clientMark = null;
            return;
        }
        if (!mark.dim().equals(mc.level.dimension().location())) return;
        Vec3 world = Vec3.atCenterOf(mark.pos());
        Vec3 rel = world.subtract(camPos);
        double dist = rel.length();
        // far away it is drawn closer but bigger, so it shows from anywhere
        double show = Math.min(dist, 96);
        Vec3 c = rel.scale(show / Math.max(0.01, dist));
        float s = (float) (1.2 * show / 24) * (float) (1 + 0.1 * Math.sin(time * 0.2));
        Vec3 up = new Vec3(0, 1, 0);
        Vec3 left = camLeft.normalize();
        Vec3 toCam = c.scale(-1).normalize();
        Vec3 vUp = toCam.cross(left).normalize();
        VertexConsumer vc = buf.getBuffer(OreSense.XRAY);
        // four-point star facing the camera
        for (int k = 0; k < 4; k++) {
            double ang = k * Math.PI / 2 + time * 0.01;
            Vec3 arm = left.scale(Math.cos(ang)).add(vUp.scale(Math.sin(ang)));
            Vec3 perp = left.scale(-Math.sin(ang)).add(vUp.scale(Math.cos(ang)));
            Vec3 tip = c.add(arm.scale(s));
            Vec3 b1 = c.add(perp.scale(s * 0.18)), b2 = c.subtract(perp.scale(s * 0.18));
            quad(vc, m, b1, tip, b2, c, 255, 245, 190, 230);
        }
        // the glow around it
        Vec3 l = left.scale(s * 0.45), u = vUp.scale(s * 0.45);
        quad(
                vc,
                m,
                c.subtract(l).subtract(u),
                c.add(l).subtract(u),
                c.add(l).add(u),
                c.subtract(l).add(u),
                255,
                230,
                140,
                90);
        // A world-vertical pillar rises from the actual marked block.
        if (dist < 128) {
            Vec3 base = world.subtract(camPos),
                    top = base.add(0, Math.min(96, mc.level.getMaxBuildHeight() - world.y), 0);
            for (int axis = 0; axis < 2; axis++) {
                Vec3 width = axis == 0 ? new Vec3(.12, 0, 0) : new Vec3(0, 0, .12);
                quad(
                        vc,
                        m,
                        base.subtract(width),
                        base.add(width),
                        top.add(width),
                        top.subtract(width),
                        255,
                        240,
                        170,
                        160);
                quad(
                        vc,
                        m,
                        top.subtract(width),
                        top.add(width),
                        base.add(width),
                        base.subtract(width),
                        255,
                        240,
                        170,
                        160);
            }
        }
        buf.endBatch(OreSense.XRAY);
    }

    private static void quad(
            VertexConsumer vc,
            Matrix4f m,
            Vec3 a,
            Vec3 b,
            Vec3 c,
            Vec3 d,
            int r,
            int g,
            int bl,
            int al) {
        vc.addVertex(m, (float) a.x, (float) a.y, (float) a.z).setColor(r, g, bl, al);
        vc.addVertex(m, (float) b.x, (float) b.y, (float) b.z).setColor(r, g, bl, al);
        vc.addVertex(m, (float) c.x, (float) c.y, (float) c.z).setColor(r, g, bl, al);
        vc.addVertex(m, (float) d.x, (float) d.y, (float) d.z).setColor(r, g, bl, al);
    }
}
