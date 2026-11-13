package com.mattermod.client;

import com.mattermod.entity.RedstoneGolem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.ParticleStatus;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;

import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import software.bernie.geckolib.cache.object.BakedGeoModel;
import software.bernie.geckolib.cache.object.GeoBone;
import software.bernie.geckolib.cache.object.GeoCube;
import software.bernie.geckolib.cache.object.GeoQuad;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Supercharged Golem: electric arcs crackling over its body. Each arc is a flat, camera-facing bolt drawn pixel by pixel
 * at true pixel size (1/16 block): it jumps 5-12 pixels between two points on one part of the body, bowing out, with
 * 3-4 straight legs and sharp kinks; it takes a new shape every tick, forks on its first, and lives 2-4 ticks, cooling
 * from white-hot through amber to orange and breaking up as it dies. About 0.86 new arcs a tick (fewer with Decreased
 * particles, none with Minimal). Client only: the arcs are drawn, never synced.
 */
final class GolemArcs {
    private GolemArcs() {}

    private static final float PX = 1 / 16f, RATE = .86f;
    private static final int[][] CORE = {{255, 250, 235}, {255, 210, 130}, {255, 166, 26}, {236, 106, 6}};

    private record Arc(GeoBone bone, Vector3f a, Vector3f b, long born, int life, long seed) {}

    private static final class State {
        final List<Arc> arcs = new ArrayList<>();
        long lastTick;
    }

    private static final Map<Integer, State> STATES = new HashMap<>();
    private static final RandomSource RNG = RandomSource.create();
    /** Model scale of the baked cube vertices: model units (1) or blocks (16), found once. */
    private static float vertexScale = -1;

    static void render(RedstoneGolem golem, BakedGeoModel model, float partialTick, PoseStack pose, MultiBufferSource buffers) {
        Minecraft mc = Minecraft.getInstance();
        State st = STATES.computeIfAbsent(golem.getId(), k -> new State());
        if (STATES.size() > 32) STATES.keySet().removeIf(id -> mc.level == null || mc.level.getEntity(id) == null);
        long now = golem.level().getGameTime();
        st.arcs.removeIf(a -> now - a.born >= a.life);
        ParticleStatus ps = mc.options.particles().get();
        float rate = !golem.charged() || ps == ParticleStatus.MINIMAL ? 0 : ps == ParticleStatus.DECREASED ? RATE / 2 : RATE;
        if (now != st.lastTick) {
            for (long t = Math.max(st.lastTick + 1, now - 3); t <= now; t++) if (RNG.nextFloat() < rate) spawn(st, model, t);
            st.lastTick = now;
        }
        if (st.arcs.isEmpty()) return;
        var cam = mc.gameRenderer.getMainCamera();
        Vector3f right = new Vector3f(cam.getLeftVector()).negate(), up = new Vector3f(cam.getUpVector());
        float bodyYaw = Mth.rotLerp(partialTick, golem.yBodyRotO, golem.yBodyRot);
        Quaternionf turn = new Quaternionf().rotationY((float) Math.toRadians(180 - bodyYaw));
        VertexConsumer vc = buffers.getBuffer(RenderType.lightning());
        Matrix4f m = pose.last().pose();
        for (Arc arc : st.arcs) {
            int age = (int) (now - arc.born);
            Vector3f A = world(arc.bone, arc.a, turn), B = world(arc.bone, arc.b, turn);
            Vector3f M = new Vector3f(A).add(B).mul(.5f);
            int ax = Math.round(new Vector3f(A).sub(M).dot(right) / PX), ay = Math.round(new Vector3f(A).sub(M).dot(up) / PX);
            int bx = Math.round(new Vector3f(B).sub(M).dot(right) / PX), by = Math.round(new Vector3f(B).sub(M).dot(up) / PX);
            if (ax == bx && ay == by) continue;
            RandomSource r = RandomSource.create(arc.seed + now * 7919);
            List<int[]> cells = bolt(r, ax, ay, bx, by, arc.seed % 2 == 0 ? 1 : -1);
            int[] col = CORE[Math.min(CORE.length - 1, age)];
            for (int i = 0; i < cells.size(); i++) {
                if (age >= 2 && (i + now) % 3 == 0) continue;      // breaking up as it dies
                quad(vc, m, M, right, up, cells.get(i), col);
            }
            if (age == 0 && cells.size() > 5 && r.nextBoolean()) {   // a fork on its first tick
                int[] c = cells.get(cells.size() / 2);
                List<int[]> fork = line(c[0], c[1], c[0] + r.nextInt(7) - 3, c[1] + r.nextInt(7) - 3);
                for (int k = 1; k < fork.size(); k++) quad(vc, m, M, right, up, fork.get(k), CORE[2]);
            }
        }
    }

    /** A point given in a bone's own model space (units), carried through the animated bone chain, in blocks from the
     *  Golem's feet, turned with its body (as the seat is computed). */
    private static Vector3f world(GeoBone bone, Vector3f local, Quaternionf turn) {
        Vector3f p = new Vector3f(local);
        for (GeoBone b = bone; b != null; b = b.getParent()) {
            Vector3f piv = new Vector3f(b.getPivotX(), b.getPivotY(), b.getPivotZ());
            p.sub(piv);
            new Quaternionf().rotationZYX(b.getRotZ(), b.getRotY(), b.getRotX()).transform(p);
            p.add(piv).add(-b.getPosX(), b.getPosY(), b.getPosZ());
        }
        return turn.transform(p.div(16));
    }

    private static void quad(VertexConsumer vc, Matrix4f m, Vector3f M, Vector3f right, Vector3f up, int[] cell, int[] col) {
        Vector3f c = new Vector3f(M).add(new Vector3f(right).mul(cell[0] * PX)).add(new Vector3f(up).mul(cell[1] * PX));
        Vector3f h = new Vector3f(right).mul(PX / 2), v = new Vector3f(up).mul(PX / 2);
        float[][] corners = {{-1, -1}, {1, -1}, {1, 1}, {-1, 1}};
        for (float[] k : corners) {
            Vector3f p = new Vector3f(c).add(new Vector3f(h).mul(k[0])).add(new Vector3f(v).mul(k[1]));
            vc.addVertex(m, p.x, p.y, p.z).setColor(col[0], col[1], col[2], 255);
        }
    }

    /** 3-4 straight legs from a to b with sharp kinks, bowing out to one side. */
    private static List<int[]> bolt(RandomSource r, int ax, int ay, int bx, int by, int bowSide) {
        int n = 3 + r.nextInt(2);
        float px = -(by - ay), py = bx - ax, len = (float) Math.sqrt(px * px + py * py);
        px /= len;
        py /= len;
        float bulge = (1.5f + r.nextFloat() * 1.5f) * bowSide;
        List<int[]> kinks = new ArrayList<>();
        kinks.add(new int[] {ax, ay});
        for (int i = 1; i < n; i++) {
            float k = i / (float) n, off = Mth.sin((float) Math.PI * k) * bulge + (r.nextFloat() * 3 - 1.5f);
            kinks.add(new int[] {Math.round(ax + (bx - ax) * k + px * off), Math.round(ay + (by - ay) * k + py * off)});
        }
        kinks.add(new int[] {bx, by});
        List<int[]> cells = new ArrayList<>();
        for (int i = 0; i + 1 < kinks.size(); i++) {
            List<int[]> seg = line(kinks.get(i)[0], kinks.get(i)[1], kinks.get(i + 1)[0], kinks.get(i + 1)[1]);
            cells.addAll(seg.subList(0, seg.size() - 1));
        }
        cells.add(new int[] {bx, by});
        return cells;
    }

    private static List<int[]> line(int x0, int y0, int x1, int y1) {
        List<int[]> out = new ArrayList<>();
        int dx = Math.abs(x1 - x0), dy = -Math.abs(y1 - y0), sx = x0 < x1 ? 1 : -1, sy = y0 < y1 ? 1 : -1, e = dx + dy;
        while (true) {
            out.add(new int[] {x0, y0});
            if (x0 == x1 && y0 == y1 || out.size() > 64) return out;
            int e2 = 2 * e;
            if (e2 >= dy) { e += dy; x0 += sx; }
            if (e2 <= dx) { e += dx; y0 += sy; }
        }
    }

    /** A new arc between two points 5-12 pixels apart on one part of the body, a little off its surface. */
    private static void spawn(State st, BakedGeoModel model, long tick) {
        List<GeoBone> bones = new ArrayList<>();
        collect(model.topLevelBones(), bones);
        if (bones.isEmpty()) return;
        if (vertexScale < 0) {
            float max = 0;
            for (GeoBone b : bones) for (GeoCube c : b.getCubes()) for (GeoQuad q : c.quads()) if (q != null)
                for (var v : q.vertices()) max = Math.max(max, Math.abs(v.position().y()));
            vertexScale = max < 6 ? 16 : 1;
        }
        for (int attempt = 0; attempt < 8; attempt++) {
            GeoBone bone = bones.get(RNG.nextInt(bones.size()));
            Vector3f a = surfacePoint(bone), b = surfacePoint(bone);
            if (a == null || b == null) continue;
            float d = a.distance(b);
            if (d < 5 || d > 12) continue;
            st.arcs.add(new Arc(bone, a, b, tick, 2 + RNG.nextInt(3), RNG.nextLong()));
            return;
        }
    }

    private static void collect(List<GeoBone> in, List<GeoBone> out) {
        for (GeoBone b : in) {
            if (!b.getCubes().isEmpty() && !b.isHidden()) out.add(b);
            collect(b.getChildBones(), out);
        }
    }

    /** A random point on one of the bone's cube faces, pushed 0.6 units out along the face. */
    private static Vector3f surfacePoint(GeoBone bone) {
        List<GeoCube> cubes = bone.getCubes();
        GeoCube c = cubes.get(RNG.nextInt(cubes.size()));
        GeoQuad[] quads = c.quads();
        if (quads.length == 0) return null;
        GeoQuad q = quads[RNG.nextInt(quads.length)];
        if (q == null || q.vertices().length < 4) return null;
        float s = RNG.nextFloat(), t = RNG.nextFloat();
        Vector3f p0 = q.vertices()[0].position(), p1 = q.vertices()[1].position(), p2 = q.vertices()[2].position(), p3 = q.vertices()[3].position();
        Vector3f top = new Vector3f(p0).lerp(p1, s), bottom = new Vector3f(p3).lerp(p2, s);
        Vector3f p = top.lerp(bottom, t).mul(vertexScale);
        return p.add(new Vector3f(q.normal()).mul(.6f));
    }
}
