package com.ethermod.client.beam;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.joml.Matrix4f;

/**
 * Photonic's light beacon (FX bench pick): built like vanilla's BeaconRenderer.renderBeaconBeam,
 * scaled to the tool (x0.3). An inner square column turning 2.25 degrees per tick and a still outer
 * column, both wrapped in the beacon_beam texture scrolling along the beam, drawn as added light so
 * nothing muddies the colour. On top, in the mod's style: a thin white core prism, a wide soft glow,
 * pixel motes of light climbing the shaft and a small four-point flare where it lands.
 *
 * <p>Points are camera-relative.
 */
@OnlyIn(Dist.CLIENT)
final class LightBeacon {

    private LightBeacon() {}

    private static final float PX = BeamFx.PX;
    private static final float SCALE = 0.3f, R1 = 0.2f * SCALE, R2 = 0.25f * SCALE;

    /** Vanilla's beacon beam texture, added as light, no depth write (ModPipelines.WORLD_BEACON). */
    private static final net.minecraft.resources.Identifier BEAM_TEXTURE =
            net.minecraft.resources.Identifier.withDefaultNamespace("textures/entity/beacon_beam.png");

    static void draw(Matrix4f m, Vec3 from, Vec3 to, float time, float[] color) {
        Vec3 d = to.subtract(from);
        double len = d.length();
        if (len < 0.02) return;
        Vec3 dir = d.scale(1 / len);
        Vec3 u = dir.cross(new Vec3(0, 1, 0));
        if (u.lengthSqr() < 1e-6) u = new Vec3(1, 0, 0);
        u = u.normalize();
        Vec3 v = u.cross(dir).normalize();
        float wr = color[0] + (1 - color[0]) * 0.15f, wg = color[1] + (1 - color[1]) * 0.15f, wb = color[2] + (1 - color[2]) * 0.15f;

        // vanilla's two columns
        float f2 = (time % 40f);
        float frac = f2 * 0.2f - (float) Math.floor(f2 * 0.1f);
        frac -= (float) Math.floor(frac);
        VertexConsumer vc = com.ethermod.client.WorldDraw.buffer(com.ethermod.client.ModPipelines.WORLD_BEACON, BEAM_TEXTURE, true);
        column(vc, m, from, dir, u, v, len, R1, Math.toRadians(f2 * 2.25 - 45), frac, 0.2f, wr, wg, wb, 0.32f);
        column(vc, m, from, dir, u, v, len, R2, Math.toRadians(-45), frac, 0.25f, wr, wg, wb, 0.1f);

        // the mod's touch
        VertexConsumer g = com.ethermod.client.Fx.glow();
        boolean calm = com.ethermod.config.ClientConfig.reduceFlashing();
        float br = calm ? 1f : 1 + 0.08f * (float) Math.sin(time * 0.15);
        BeamFx.prism(g, m, from, to, PX * 0.25f, 0.785, 1f, 1f, 1f, 0.7f);
        BeamFx.prism(g, m, from, to, PX * 2.2f * br, 0.785, wr, wg, wb, 0.035f);
        for (int k = 0; k < 4; k++) {
            double s = (time * 0.008 + k / 4.0) % 1, a = k * 2.4 + time * 0.05, off = R2 * 1.6 * Math.sin(s * Math.PI);
            Vec3 p = from.add(dir.scale(len * s)).add(u.scale(Math.cos(a) * off)).add(v.scale(Math.sin(a) * off));
            BeamFx.cube(g, m, p, PX * 0.25f * BeamRenderer.nearScale(p), wr, wg, wb, 0.5f * (float) Math.sin(s * Math.PI));
        }
        float r = 0.16f * br * BeamRenderer.nearScale(to);
        Vec3 b = to.add(dir.scale(-0.04));
        for (int k = 0; k < 4; k++) {
            double an = k * Math.PI / 2 + Math.PI / 4 + time * 0.01;
            Vec3 dd = u.scale(Math.cos(an)).add(v.scale(Math.sin(an)));
            BeamFx.prism(g, m, b, b.add(dd.scale(r)), PX * 0.4f, 0.785, 1f, 1f, 1f, calm ? 0.25f : 0.6f);
        }
        BeamFx.cube(g, m, b, PX * 1.2f * BeamRenderer.nearScale(to), wr, wg, wb, 0.5f);
    }

    /** One square column of half-width {@code r} from {@code from} along {@code dir}, rolled {@code roll}. */
    private static void column(
            VertexConsumer vc, Matrix4f m, Vec3 from, Vec3 dir, Vec3 u, Vec3 v, double len, float r, double roll,
            float frac, float vanillaR, float cr, float cg, float cb, float alpha) {
        Vec3 uu = u.scale(Math.cos(roll)).add(v.scale(Math.sin(roll)));
        Vec3 vv = v.scale(Math.cos(roll)).subtract(u.scale(Math.sin(roll)));
        double[][] c = {{-1, -1}, {1, -1}, {1, 1}, {-1, 1}};
        float v0 = -1 + frac, v1 = (float) (len * (0.5 / vanillaR)) + v0; // vanilla's texture density
        Vec3 top = from.add(dir.scale(len));
        int ri = BeamFx.ch(cr), gi = BeamFx.ch(cg), bi = BeamFx.ch(cb), ai = BeamFx.ch(alpha);
        for (int f = 0; f < 4; f++) {
            double[] a = c[f], b = c[(f + 1) % 4];
            Vec3 oa = uu.scale(a[0] * r).add(vv.scale(a[1] * r)), ob = uu.scale(b[0] * r).add(vv.scale(b[1] * r));
            vert(vc, m, from.add(oa), 0, v1, ri, gi, bi, ai);
            vert(vc, m, from.add(ob), 1, v1, ri, gi, bi, ai);
            vert(vc, m, top.add(ob), 1, v0, ri, gi, bi, ai);
            vert(vc, m, top.add(oa), 0, v0, ri, gi, bi, ai);
        }
    }

    private static void vert(VertexConsumer vc, Matrix4f m, Vec3 p, float uu, float vv, int r, int g, int b, int a) {
        vc.addVertex(m, (float) p.x, (float) p.y, (float) p.z)
                .setColor(r, g, b, a)
                .setUv(uu, vv)
                .setOverlay(net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY)
                .setLight(0xF000F0)
                .setNormal(0f, 1f, 0f);
    }
}
