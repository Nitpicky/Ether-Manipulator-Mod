package com.ethermod.beam;

import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * The beam's look, chosen on the FX bench ("Prism rope"): the beam built like the Tether rope, one
 * continuous square tube along the swaying curve (a soft prism in the beam's colour around a
 * whitened core prism), slowly rolling. Pressure: thicker, whiter, faster roll, more arcs
 * crackling over its surface. Each damage strike is announced by a swell of energy pushed down the
 * beam that lands on the creature exactly when the strike happens.
 *
 * <p>Points are camera-relative (the camera is at the origin).
 */
@OnlyIn(Dist.CLIENT)
final class PrismRope {

    private PrismRope() {}

    private static final float PX = BeamFx.PX;

    /** Integrated roll per beam owner, so a pressure change never makes the roll jump. */
    private static final Map<Integer, float[]> ROLL = new HashMap<>();

    static void draw(
            int owner,
            Vec3[] pts,
            int sub,
            Vec3 su,
            float time,
            int level,
            float[] color,
            boolean hitSomething,
            double full) {
        int n = pts.length - 1;
        int L = Math.max(1, Math.min(4, level));
        float[] rs = ROLL.computeIfAbsent(owner, k -> new float[] {0f, time});
        if (time < rs[1]) rs[1] = time;
        rs[0] += (time - rs[1]) * (0.05f + 0.03f * L);
        rs[1] = time;
        float roll = rs[0];
        float gl = (1.4f + 0.3f * (L - 1)) * PX, co = (0.5f + 0.1f * (L - 1)) * PX;
        float[] wc = mix3(color, 0.3f + 0.08f * (L - 1));
        float[] fade = new float[n + 1];
        for (int i = 0; i <= n; i++) fade[i] = fadeAt((double) i / n, n, sub, hitSomething, full, time);
        float[] h1 = new float[n + 1], h2 = new float[n + 1], r = new float[n + 1], a1 = new float[n + 1], a2 = new float[n + 1];
        for (int i = 0; i <= n; i++) {
            double t = (double) i / n;
            h1[i] = gl;
            h2[i] = co;
            r[i] = roll + (float) t * 3f;
            a1[i] = 0.45f * fade[i];
            a2[i] = 0.95f * fade[i];
        }
        tube(pts, su, h1, r, color, a1);
        tube(pts, su, h2, r, wc, a2);
        arcs(owner, pts, su, time, L, gl);
        swells(owner, pts, su, L, color, time);
    }

    static void forget(int owner) {
        ROLL.remove(owner);
        ARCS.remove(owner);
    }

    // ---------------------------------------------------------------- tube

    /**
     * One continuous square tube along the points: shared corners between segments (no joints, no
     * caps), only the faces turned toward the camera, face-shaded and additive like the Tether
     * rope and the orbs' cubes.
     */
    private static void tube(Vec3[] pts, Vec3 su, float[] h, float[] roll, float[] c, float[] a) {
        int n = pts.length - 1;
        VertexConsumer vc = com.ethermod.client.Fx.glow();
        Vec3[][] cs = new Vec3[n + 1][];
        for (int j = 0; j <= n; j++) {
            Vec3[] fr = frame(pts, j, su);
            Vec3 p = pts[j];
            float hh = h[j] * BeamRenderer.nearScale(p);
            Vec3[] ring = new Vec3[4];
            for (int k = 0; k < 4; k++) {
                double an = roll[j] + k * Math.PI / 2 + Math.PI / 4;
                ring[k] = p.add(fr[1].scale(Math.cos(an) * hh)).add(fr[2].scale(Math.sin(an) * hh));
            }
            cs[j] = ring;
        }
        for (int j = 0; j < n; j++) {
            if (a[j] <= 0.003f && a[j + 1] <= 0.003f) continue;
            for (int k = 0; k < 4; k++) {
                int k2 = (k + 1) % 4;
                Vec3 mid = cs[j][k].add(cs[j][k2]).scale(0.5);
                Vec3 nrm = mid.subtract(pts[j]);
                if (nrm.dot(mid.scale(-1)) <= 0) continue; // faces away from the camera
                double ny = nrm.normalize().y;
                float sh = (float) (ny >= 0 ? 0.8 + 0.2 * ny : 0.8 + 0.3 * ny);
                int ca = BeamFx.ch(a[j]), cb = BeamFx.ch(a[j + 1]);
                int r = BeamFx.ch(c[0] * sh), g = BeamFx.ch(c[1] * sh), b = BeamFx.ch(c[2] * sh);
                v(vc, cs[j][k], r, g, b, ca);
                v(vc, cs[j][k2], r, g, b, ca);
                v(vc, cs[j + 1][k2], r, g, b, cb);
                v(vc, cs[j + 1][k], r, g, b, cb);
                v(vc, cs[j + 1][k], r, g, b, cb); // both windings, whatever the layer culls
                v(vc, cs[j + 1][k2], r, g, b, cb);
                v(vc, cs[j][k2], r, g, b, ca);
                v(vc, cs[j][k], r, g, b, ca);
            }
        }
    }

    private static void v(VertexConsumer vc, Vec3 p, int r, int g, int b, int a) {
        vc.addVertex((float) p.x, (float) p.y, (float) p.z).setColor(r, g, b, a);
    }

    /** {tangent, u, v}: u follows the beam's fixed side vector so the tube doesn't twist. */
    private static Vec3[] frame(Vec3[] pts, int i, Vec3 su) {
        int n = pts.length - 1;
        Vec3 tan = pts[Math.min(n, i + 1)].subtract(pts[Math.max(0, i - 1)]);
        tan = tan.lengthSqr() < 1e-12 ? su : tan.normalize();
        Vec3 u = su.subtract(tan.scale(su.dot(tan)));
        if (u.lengthSqr() < 1e-9) u = tan.cross(new Vec3(0, 1, 0));
        if (u.lengthSqr() < 1e-9) u = tan.cross(new Vec3(1, 0, 0));
        u = u.normalize();
        return new Vec3[] {tan, u, tan.cross(u).normalize()};
    }

    private static Vec3 pointAt(Vec3[] pts, double f) {
        int n = pts.length - 1;
        double x = Math.max(0, Math.min(1, f)) * n;
        int i = Math.min(n - 1, (int) Math.floor(x));
        return pts[i].lerp(pts[i + 1], x - i);
    }

    /** BeamRenderer's fade: in over the first segment, out at the far end when hitting nothing. */
    private static float fadeAt(double t, int n, int sub, boolean hit, double full, float time) {
        float f = (float) Math.min(1, t * n / (1.5 * sub));
        if (!hit) {
            double fl = Math.min(0.45, 2.5 / Math.max(full, 0.1)) * (0.85 + 0.15 * Math.sin(time * 0.35));
            double into = (t - (1 - fl)) / fl;
            if (into > 0) f *= (float) Math.pow(1 - Math.min(1, into), 1.6);
        }
        return f;
    }

    private static float[] mix3(float[] c, float w) {
        return new float[] {c[0] + (1 - c[0]) * w, c[1] + (1 - c[1]) * w, c[2] + (1 - c[2]) * w};
    }

    // ---------------------------------------------------------------- damage swell

    /** Ticks a swell takes to run the whole beam (0.4s: a fixed, readable speed). */
    private static final float SURGE_TICKS = 8f;

    /**
     * Energy pushed down the beam: a bead that bulges the beam (glow shell + whitened core) and
     * lands on the creature exactly when the next damage strike happens. Strikes come every
     * {@link BeamState#hitInterval} ticks, so the client launches each swell SURGE_TICKS before
     * the predicted strike; at high pressure two swells share the beam.
     */
    private static void swells(int owner, Vec3[] pts, Vec3 su, int L, float[] color, float time) {
        RemoteBeams.Pulse pu = RemoteBeams.pulseOf(owner);
        if (pu == null || pu.strikeAt == Long.MIN_VALUE) return;
        int gap = BeamState.hitInterval(L);
        float since = time - pu.strikeAt;
        if (since < 0 || since > gap * 2.5f) return; // not striking anything now
        float next = pu.strikeAt + gap;
        while (next < time) next += gap;
        for (float ta = next; ta - time <= SURGE_TICKS; ta += gap) {
            float f = 1 - (ta - time) / SURGE_TICKS;
            if (f < 0 || f > 1) continue;
            swell(pts, su, f, color, L, Math.min(1f, f / 0.08f), time);
        }
    }

    private static void swell(Vec3[] pts, Vec3 su, float f, float[] color, int L, float fi, float time) {
        float roll = time * 0.2f;
        float half = 0.07f;
        double f0 = Math.max(0, f - half), f1 = Math.min(1, f + half);
        if (f1 - f0 < 0.01) return;
        int k = 12;
        Vec3[] sp = new Vec3[k + 1];
        float[] hg = new float[k + 1], hc = new float[k + 1], rr = new float[k + 1], ag = new float[k + 1], ac = new float[k + 1];
        for (int j = 0; j <= k; j++) {
            double ff = f0 + (f1 - f0) * j / k;
            sp[j] = pointAt(pts, ff);
            double x = (ff - f) / half;
            float bump = (float) Math.pow(Math.max(0, Math.cos(Math.min(1, Math.abs(x)) * Math.PI / 2)), 1.5);
            hg[j] = (1.3f + 0.5f * bump * (1.6f + 0.3f * (L - 1))) * PX;
            hc[j] = (0.45f + 1.1f * bump) * PX;
            rr[j] = roll;
            ag[j] = 0.5f * bump * fi;
            ac[j] = 0.95f * bump * fi;
        }
        tube(sp, su, hg, rr, color, ag);
        tube(sp, su, hc, rr, mix3(color, 0.55f), ac);
    }

    // ---------------------------------------------------------------- surface arcs

    /**
     * Arcs crackling over the beam, in the style of the Overcharge arcs: flat camera-facing bolts
     * drawn pixel by pixel, jumping 5-12 pixels between two points on the beam's surface, 3-4
     * straight legs with sharp kinks, a new shape every tick, living 2-4 ticks and cooling from
     * white-hot through amber to orange. More of them with pressure; a burst at the target on each
     * strike.
     */
    private static final float[][] ARC_CORE = {
        {1f, 250 / 255f, 235 / 255f}, {1f, 210 / 255f, 130 / 255f}, {1f, 166 / 255f, 26 / 255f}, {236 / 255f, 106 / 255f, 6 / 255f}
    };

    private record Arc(double f, long born, int life, long seed) {}

    private static final class ArcState {
        final List<Arc> arcs = new ArrayList<>();
        long tick = Long.MIN_VALUE;
        long burstFor = Long.MIN_VALUE;
    }

    private static final Map<Integer, ArcState> ARCS = new HashMap<>();

    private static final class Rng {
        long s;

        Rng(long seed) {
            s = Math.floorMod(seed, 2147483646L) + 1;
        }

        double next() {
            s = (s * 16807) % 2147483647L;
            return s / 2147483647.0;
        }
    }

    private static void arcs(int owner, Vec3[] pts, Vec3 su, float time, int L, float radius) {
        ArcState st = ARCS.computeIfAbsent(owner, k -> new ArcState());
        long now = (long) Math.floor(time);
        double rate = new double[] {0, 0.25, 0.45, 0.7, 1}[L];
        if (st.tick == Long.MIN_VALUE || now < st.tick) st.tick = now - 1;
        RemoteBeams.Pulse pu = RemoteBeams.pulseOf(owner);
        for (long t = Math.max(st.tick + 1, now - 3); t <= now; t++) {
            Rng r = new Rng(t * 7919 + 13);
            if (r.next() < rate) st.arcs.add(new Arc(0.35 + r.next() * 0.6, t, 2 + (int) (r.next() * 3), (long) (r.next() * 1e9)));
            if (pu != null && pu.strikeAt != Long.MIN_VALUE && t - pu.strikeAt <= 1 && st.burstFor != pu.strikeAt) {
                st.burstFor = pu.strikeAt;
                for (int k = 0; k < 2 + L; k++)
                    st.arcs.add(new Arc(0.9 + r.next() * 0.1, t, 2 + (int) (r.next() * 3), (long) (r.next() * 1e9)));
            }
        }
        st.tick = now;
        st.arcs.removeIf(a -> now - a.born >= a.life);
        Vec3 look = pointAt(pts, 0.5).normalize();
        Vec3 right = look.cross(new Vec3(0, 1, 0));
        if (right.lengthSqr() < 1e-9) right = new Vec3(1, 0, 0);
        right = right.normalize();
        Vec3 up = right.cross(look).normalize();
        int n = pts.length - 1;
        VertexConsumer vc = com.ethermod.client.Fx.beam();
        for (Arc a : st.arcs) {
            int age = (int) (now - a.born);
            Rng r = new Rng(a.seed + now * 7919);
            Vec3 p0 = pointAt(pts, a.f);
            Vec3[] fr = frame(pts, (int) Math.round(a.f * n), su);
            double len = (5 + r.next() * 7) * PX;
            Vec3 dir = fr[0].scale(r.next() < 0.5 ? 1 : -1);
            double ang1 = r.next() * 6.283, ang2 = ang1 + (r.next() - 0.5) * 2;
            double rad = radius * BeamRenderer.nearScale(p0) + 0.6 * PX;
            Vec3 A = p0.add(fr[1].scale(Math.cos(ang1) * rad)).add(fr[2].scale(Math.sin(ang1) * rad));
            Vec3 B = p0.add(dir.scale(len)).add(fr[1].scale(Math.cos(ang2) * rad)).add(fr[2].scale(Math.sin(ang2) * rad));
            Vec3 M = A.add(B).scale(0.5).add(look.scale(-0.04));
            double px = PX * BeamRenderer.nearScale(M);
            int ax = (int) Math.round(A.subtract(M).dot(right) / px), ay = (int) Math.round(A.subtract(M).dot(up) / px);
            int bx = (int) Math.round(B.subtract(M).dot(right) / px), by = (int) Math.round(B.subtract(M).dot(up) / px);
            if (ax == bx && ay == by) continue;
            List<int[]> cells = bolt(r, ax, ay, bx, by, a.seed % 2 == 1 ? 1 : -1);
            float[] col = ARC_CORE[Math.min(3, age)];
            for (int i = 0; i < cells.size(); i++) {
                if (age >= 2 && (i + now) % 3 == 0) continue;
                cell(vc, M, right, up, px, cells.get(i), col);
            }
            if (age == 0 && cells.size() > 5 && r.next() < 0.5) {
                int[] c = cells.get(cells.size() / 2);
                List<int[]> fork = line(c[0], c[1], c[0] + (int) Math.floor(r.next() * 7) - 3, c[1] + (int) Math.floor(r.next() * 7) - 3);
                for (int i = 1; i < fork.size(); i++) cell(vc, M, right, up, px, fork.get(i), ARC_CORE[2]);
            }
        }
    }

    private static void cell(VertexConsumer vc, Vec3 M, Vec3 right, Vec3 up, double px, int[] c, float[] col) {
        Vec3 ctr = M.add(right.scale(c[0] * px)).add(up.scale(c[1] * px));
        Vec3 h = right.scale(px / 2), w = up.scale(px / 2);
        int r = BeamFx.ch(col[0]), g = BeamFx.ch(col[1]), b = BeamFx.ch(col[2]);
        v(vc, ctr.subtract(h).subtract(w), r, g, b, 255);
        v(vc, ctr.add(h).subtract(w), r, g, b, 255);
        v(vc, ctr.add(h).add(w), r, g, b, 255);
        v(vc, ctr.subtract(h).add(w), r, g, b, 255);
        v(vc, ctr.subtract(h).add(w), r, g, b, 255);
        v(vc, ctr.add(h).add(w), r, g, b, 255);
        v(vc, ctr.add(h).subtract(w), r, g, b, 255);
        v(vc, ctr.subtract(h).subtract(w), r, g, b, 255);
    }

    private static List<int[]> line(int x0, int y0, int x1, int y1) {
        List<int[]> out = new ArrayList<>();
        int dx = Math.abs(x1 - x0), dy = -Math.abs(y1 - y0), sx = x0 < x1 ? 1 : -1, sy = y0 < y1 ? 1 : -1, e = dx + dy;
        for (; ; ) {
            out.add(new int[] {x0, y0});
            if ((x0 == x1 && y0 == y1) || out.size() > 64) return out;
            int e2 = 2 * e;
            if (e2 >= dy) {
                e += dy;
                x0 += sx;
            }
            if (e2 <= dx) {
                e += dx;
                y0 += sy;
            }
        }
    }

    private static List<int[]> bolt(Rng r, int ax, int ay, int bx, int by, int side) {
        int n = 3 + (r.next() < 0.5 ? 0 : 1);
        double px = -(by - ay), py = bx - ax, l = Math.hypot(px, py);
        if (l == 0) l = 1;
        px /= l;
        py /= l;
        double bulge = (1.5 + r.next() * 1.5) * side;
        List<int[]> ks = new ArrayList<>();
        ks.add(new int[] {ax, ay});
        for (int i = 1; i < n; i++) {
            double k = (double) i / n, off = Math.sin(Math.PI * k) * bulge + (r.next() * 3 - 1.5);
            ks.add(new int[] {(int) Math.round(ax + (bx - ax) * k + px * off), (int) Math.round(ay + (by - ay) * k + py * off)});
        }
        ks.add(new int[] {bx, by});
        List<int[]> cells = new ArrayList<>();
        for (int i = 0; i + 1 < ks.size(); i++) {
            List<int[]> sg = line(ks.get(i)[0], ks.get(i)[1], ks.get(i + 1)[0], ks.get(i + 1)[1]);
            cells.addAll(sg.subList(0, sg.size() - 1));
        }
        cells.add(new int[] {bx, by});
        return cells;
    }
}
