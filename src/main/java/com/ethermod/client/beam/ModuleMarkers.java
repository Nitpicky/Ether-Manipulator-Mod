package com.ethermod.client.beam;

import com.ethermod.client.ClientMobility;
import com.ethermod.network.payload.ModulePayloads;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import org.joml.Matrix4f;

import java.util.Iterator;

/**
 * Photonic markers drawn in the world: Lumen Trail's arrows along the path you walked (pointing
 * back the way you came).
 */
@OnlyIn(Dist.CLIENT)
final class ModuleMarkers {

    private ModuleMarkers() {}

    static void render(
            Minecraft mc,
            Vec3 camPos,
            Vec3 camLeft,
            float time,
            Matrix4f m) {
        renderTrail(camPos, time, m);
    }

    private static void renderTrail(
            Vec3 camPos, float time, Matrix4f m) {
        float alpha = ClientMobility.trailAlpha;
        if (alpha <= 0 || ClientMobility.TRAIL.size() < 2) return;
        VertexConsumer vc = com.ethermod.client.Fx.glow();
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
