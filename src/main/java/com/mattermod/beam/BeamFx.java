package com.mattermod.beam;

import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import org.joml.Matrix4f;

/**
 * Low-level drawing for beam effects, on the vanilla lightning render type (position + colour,
 * additive blending). Using only a vanilla render type keeps the effects working under shader packs
 * (Iris / Oculus).
 *
 * <p>The look is blocky to match Minecraft: effects are built from boxes sized in whole pixels
 * (1/16 block) with flat per-face shading, like beacon beams and vanilla particles, rather than
 * smooth glows.
 *
 * <p>All positions are camera-relative.
 */
@OnlyIn(Dist.CLIENT)
public final class BeamFx {

    private BeamFx() {}

    /** One Minecraft pixel, in blocks. */
    public static final float PX = 1f / 16f;

    /**
     * A solid box with flat Minecraft-style face shading (top brightest, bottom darkest), centred
     * at {@code c} with half-extent vectors {@code hx, hy, hz} (any orientation). Camera-relative.
     */
    public static void box(
            VertexConsumer vc,
            Matrix4f m,
            Vec3 c,
            Vec3 hx,
            Vec3 hy,
            Vec3 hz,
            float r,
            float g,
            float b,
            float alpha) {
        Vec3[] k = new Vec3[8];
        for (int i = 0; i < 8; i++) {
            k[i] =
                    c.add(hx.scale((i & 1) == 0 ? -1 : 1))
                            .add(hy.scale((i & 2) == 0 ? -1 : 1))
                            .add(hz.scale((i & 4) == 0 ? -1 : 1));
        }
        int a = ch(alpha);
        // shade per face by how much its normal points up (vanilla: top 1.0, sides 0.8/0.6, bottom
        // 0.5)
        face(vc, m, k[0], k[1], k[3], k[2], hz.scale(-1), r, g, b, a); // -z
        face(vc, m, k[4], k[6], k[7], k[5], hz, r, g, b, a); // +z
        face(vc, m, k[0], k[2], k[6], k[4], hx.scale(-1), r, g, b, a); // -x
        face(vc, m, k[1], k[5], k[7], k[3], hx, r, g, b, a); // +x
        face(vc, m, k[0], k[4], k[5], k[1], hy.scale(-1), r, g, b, a); // -y
        face(vc, m, k[2], k[3], k[7], k[6], hy, r, g, b, a); // +y
    }

    private static void face(
            VertexConsumer vc,
            Matrix4f m,
            Vec3 p0,
            Vec3 p1,
            Vec3 p2,
            Vec3 p3,
            Vec3 normal,
            float r,
            float g,
            float b,
            int a) {
        double ny = normal.lengthSqr() < 1e-12 ? 0 : normal.normalize().y;
        float shade = (float) (ny >= 0 ? 0.8 + 0.2 * ny : 0.8 + 0.3 * ny);
        int ri = ch(r * shade), gi = ch(g * shade), bi = ch(b * shade);
        quad(vc, m, p0, p1, p2, p3, ri, gi, bi, a, a, a, a);
    }

    /** An axis-aligned cube of half-size {@code h}. */
    public static void cube(
            VertexConsumer vc,
            Matrix4f m,
            Vec3 c,
            float h,
            float r,
            float g,
            float b,
            float alpha) {
        box(vc, m, c, new Vec3(h, 0, 0), new Vec3(0, h, 0), new Vec3(0, 0, h), r, g, b, alpha);
    }

    /**
     * A cube of half-size {@code h} turned {@code yaw} radians around the vertical axis and tipped
     * by {@code tilt}.
     */
    public static void spinCube(
            VertexConsumer vc,
            Matrix4f m,
            Vec3 c,
            float h,
            double yaw,
            double tilt,
            float r,
            float g,
            float b,
            float alpha) {
        Vec3 x = new Vec3(Math.cos(yaw), 0, Math.sin(yaw));
        Vec3 z = new Vec3(-Math.sin(yaw), 0, Math.cos(yaw));
        Vec3 y = new Vec3(0, 1, 0);
        // tip around x
        Vec3 y2 = y.scale(Math.cos(tilt)).add(z.scale(Math.sin(tilt)));
        Vec3 z2 = z.scale(Math.cos(tilt)).subtract(y.scale(Math.sin(tilt)));
        box(vc, m, c, x.scale(h), y2.scale(h), z2.scale(h), r, g, b, alpha);
    }

    /**
     * A square prism from {@code a} to {@code b} (like a beacon beam), half-width {@code h}, rolled
     * {@code roll} radians around its axis.
     */
    public static void prism(
            VertexConsumer vc,
            Matrix4f m,
            Vec3 a,
            Vec3 b,
            float h,
            double roll,
            float r,
            float g,
            float bl,
            float alpha) {
        Vec3 d = b.subtract(a);
        double len = d.length();
        if (len < 1e-4) return;
        Vec3 axis = d.scale(1 / len);
        Vec3 u = axis.cross(new Vec3(0, 1, 0));
        if (u.lengthSqr() < 1e-6) u = axis.cross(new Vec3(1, 0, 0));
        u = u.normalize();
        Vec3 v = u.cross(axis).normalize();
        Vec3 u2 = u.scale(Math.cos(roll)).add(v.scale(Math.sin(roll)));
        Vec3 v2 = v.scale(Math.cos(roll)).subtract(u.scale(Math.sin(roll)));
        box(vc, m, a.add(d.scale(0.5)), d.scale(0.5), u2.scale(h), v2.scale(h), r, g, bl, alpha);
    }

    /** Rounds to whole Minecraft pixels (at least one). */
    public static float px(float blocks) {
        return Math.max(1, Math.round(blocks * 16f)) * PX;
    }

    /** Emits a quad with both windings so it shows from either side regardless of culling. */
    private static void quad(
            VertexConsumer vc,
            Matrix4f m,
            Vec3 p0,
            Vec3 p1,
            Vec3 p2,
            Vec3 p3,
            int r,
            int g,
            int b,
            int a0,
            int a1,
            int a2,
            int a3) {
        v(vc, m, p0, r, g, b, a0);
        v(vc, m, p1, r, g, b, a1);
        v(vc, m, p2, r, g, b, a2);
        v(vc, m, p3, r, g, b, a3);
        v(vc, m, p3, r, g, b, a3);
        v(vc, m, p2, r, g, b, a2);
        v(vc, m, p1, r, g, b, a1);
        v(vc, m, p0, r, g, b, a0);
    }

    private static void v(VertexConsumer vc, Matrix4f m, Vec3 p, int r, int g, int b, int a) {
        vc.addVertex(m, (float) p.x, (float) p.y, (float) p.z).setColor(r, g, b, a);
    }

    static int ch(float f) {
        return (int) (Math.max(0, Math.min(1, f)) * 255);
    }
}
