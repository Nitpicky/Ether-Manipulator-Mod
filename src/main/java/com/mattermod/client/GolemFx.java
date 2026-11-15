package com.mattermod.client;

import com.mattermod.entity.RedstoneGolem;
import com.mattermod.network.payload.GolemFxPayload;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * The Redstone Golem's Winston-style effects, client side.
 *
 * <p>Cube Field (Primal Rage): a 7 x 5 x 7 box around the Golem drawn at 16 pixels a block, redrawn every tick into one
 * texture (four walls stacked, then the roof) and drawn additive and full bright: a faint offset-brick grid (cells 6 x 4
 * px), a bright rim, a shimmer band climbing the walls, cells flickering on; it rises from the ground and the roof closes
 * from the rim as it goes up, a stopped shot sends a square ripple out, and it breaks up pixel by pixel as it goes down.
 *
 * <p>Supercharged chains: each jump of an arc chain is a pixel bolt (1/16-block squares, a white-hot core with an orange
 * edge) between the two bodies, with sharp kinks that take a new shape every tick; it forks on its first tick and cools
 * through amber to orange, breaking up, over 6 ticks.
 */
public final class GolemFx {
    private GolemFx() {}

    // ---- payloads ----------------------------------------------------------------------------

    public static void accept(GolemFxPayload p) {
        var mc = Minecraft.getInstance();
        if (mc.level == null) return;
        long now = mc.level.getGameTime();
        List<Float> f = p.points();
        if (p.kind() == GolemFxPayload.CHAIN) {
            List<Vec3> pts = new ArrayList<>();
            for (int i = 0; i + 2 < f.size(); i += 3) pts.add(new Vec3(f.get(i), f.get(i + 1), f.get(i + 2)));
            if (pts.size() >= 2) CHAINS.add(new Chain(pts, now, RNG.nextLong()));
            while (CHAINS.size() > 32) CHAINS.remove(0);
        } else if (p.kind() == GolemFxPayload.FIELD_HIT && f.size() >= 3
                && mc.level.getEntity(p.golemId()) instanceof RedstoneGolem g) {
            field(g).hit(g, new Vec3(f.get(0), f.get(1), f.get(2)));
        }
    }

    private static final RandomSource RNG = RandomSource.create();

    // ---- Supercharged arc chains -------------------------------------------------------------

    private record Chain(List<Vec3> pts, long born, long seed) {}

    private static final List<Chain> CHAINS = new ArrayList<>();
    static final int CHAIN_LIFE = 6;
    private static final float PX = 1 / 16f;
    private static final int[][] CORE = {{255, 250, 235}, {255, 250, 235}, {255, 214, 140}, {255, 180, 60}, {255, 150, 30}, {236, 106, 6}};
    private static final int[] EDGE = {214, 70, 8};

    /** Playtest: chains on screen right now. */
    public static int liveChains() {
        return CHAINS.size();
    }

    @SubscribeEvent
    public static void renderChains(RenderLevelStageEvent ev) {
        if (ev.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) return;
        if (ShaderCompat.inShadowPass()) return;
        var mc = Minecraft.getInstance();
        if (mc.level == null) {
            CHAINS.clear();
            return;
        }
        long now = mc.level.getGameTime();
        CHAINS.removeIf(c -> now - c.born >= CHAIN_LIFE || now < c.born);
        if (CHAINS.isEmpty()) return;
        var cam = ev.getCamera();
        Vec3 cp = cam.getPosition();
        Vector3f right = new Vector3f(cam.getLeftVector()).negate(), up = new Vector3f(cam.getUpVector());
        PoseStack ps = ev.getPoseStack();
        ps.pushPose();
        Matrix4f m = ps.last().pose();
        var buffers = mc.renderBuffers().bufferSource();
        RenderType type = RenderType.debugQuads();
        VertexConsumer vc = buffers.getBuffer(type);
        for (Chain c : CHAINS) {
            int age = (int) (now - c.born);
            int[] col = CORE[Math.min(CORE.length - 1, age)];
            RandomSource r = RandomSource.create(c.seed + now * 7919);
            for (int i = 0; i + 1 < c.pts.size(); i++) {
                List<Vector3f> cells = bolt(r, c.pts.get(i).subtract(cp), c.pts.get(i + 1).subtract(cp));
                for (int k = 0; k < cells.size(); k++) {
                    if (age >= 4 && (k + now) % 3 == 0) continue;       // breaking up as it dies
                    Vector3f at = cells.get(k);
                    // the edge sits a hair behind the core, toward the far side from the camera
                    if (age <= 3) square(vc, m, new Vector3f(at).mul(1.002f), right, up, 1.5f, EDGE);
                    square(vc, m, at, right, up, .5f, col);
                }
                if (age == 0 && cells.size() > 8) {   // a fork on its first tick
                    Vector3f s = cells.get(cells.size() / 3 + r.nextInt(cells.size() / 3));
                    Vector3f e = new Vector3f(s).add(r.nextFloat() - .5f, r.nextFloat() * .6f - .2f, r.nextFloat() - .5f);
                    for (Vector3f q : leg(s, e)) square(vc, m, q, right, up, .5f, CORE[2]);
                }
            }
        }
        buffers.endBatch(type);
        ps.popPose();
    }

    /** A 3-6 leg bolt from a to b (camera-relative), kinked off the straight line by up to a fifth of its length, as
     *  pixel cells 1/16 block apart. */
    private static List<Vector3f> bolt(RandomSource r, Vec3 a, Vec3 b) {
        Vector3f A = new Vector3f((float) a.x, (float) a.y, (float) a.z), B = new Vector3f((float) b.x, (float) b.y, (float) b.z);
        Vector3f d = new Vector3f(B).sub(A);
        float len = d.length();
        int n = Math.max(3, Math.min(6, Math.round(len * 1.2f)));
        Vector3f s1 = new Vector3f(d).cross(0, 1, 0);
        if (s1.lengthSquared() < 1e-4) s1.set(1, 0, 0);
        s1.normalize();
        Vector3f s2 = new Vector3f(d).cross(s1).normalize();
        float amp = Math.min(.6f, len * .18f);
        List<Vector3f> kinks = new ArrayList<>();
        kinks.add(A);
        for (int i = 1; i < n; i++) {
            float k = i / (float) n;
            Vector3f p = new Vector3f(A).lerp(B, k);
            p.add(new Vector3f(s1).mul((r.nextFloat() * 2 - 1) * amp)).add(new Vector3f(s2).mul((r.nextFloat() * 2 - 1) * amp));
            kinks.add(p);
        }
        kinks.add(B);
        List<Vector3f> cells = new ArrayList<>();
        for (int i = 0; i + 1 < kinks.size(); i++) cells.addAll(leg(kinks.get(i), kinks.get(i + 1)));
        return cells;
    }

    private static List<Vector3f> leg(Vector3f a, Vector3f b) {
        List<Vector3f> out = new ArrayList<>();
        int steps = Math.max(1, Math.min(200, (int) Math.ceil(a.distance(b) / PX)));
        for (int i = 0; i < steps; i++) out.add(new Vector3f(a).lerp(b, i / (float) steps));
        return out;
    }

    /** A camera-facing square, {@code half} pixels from its centre to each side. */
    private static void square(VertexConsumer vc, Matrix4f m, Vector3f c, Vector3f right, Vector3f up, float half, int[] col) {
        Vector3f h = new Vector3f(right).mul(PX * half), v = new Vector3f(up).mul(PX * half);
        vc.addVertex(m, c.x - h.x - v.x, c.y - h.y - v.y, c.z - h.z - v.z).setColor(col[0], col[1], col[2], 255);
        vc.addVertex(m, c.x + h.x - v.x, c.y + h.y - v.y, c.z + h.z - v.z).setColor(col[0], col[1], col[2], 255);
        vc.addVertex(m, c.x + h.x + v.x, c.y + h.y + v.y, c.z + h.z + v.z).setColor(col[0], col[1], col[2], 255);
        vc.addVertex(m, c.x - h.x + v.x, c.y - h.y + v.y, c.z - h.z + v.z).setColor(col[0], col[1], col[2], 255);
    }

    // ---- Cube Field ----------------------------------------------------------------------------

    static final int W = (int) (RedstoneGolem.FIELD_HALF * 32), H = (int) (RedstoneGolem.FIELD_HEIGHT * 16);
    /** The texture: walls north, south, west, east (W x H each, top row = the top of the wall), then the roof (W x W). */
    static final int TEX_H = 4 * H + W;
    static final int DEPLOY = 16, ROOF_FROM = 14, ROOF_LEN = 8, COLLAPSE = 10;
    /** Ember orange: the Golem's overheat colour; bright pixels run to white-hot. */
    static final int[] TINT = {255, 84, 24};

    private static final Map<Integer, Field> FIELDS = new HashMap<>();

    private static Field field(RedstoneGolem g) {
        var mc = Minecraft.getInstance();
        if (FIELDS.size() > 8)
            for (Iterator<Map.Entry<Integer, Field>> it = FIELDS.entrySet().iterator(); it.hasNext(); ) {
                var e = it.next();
                if (mc.level == null || mc.level.getEntity(e.getKey()) == null) {
                    e.getValue().close();
                    it.remove();
                }
            }
        return FIELDS.computeIfAbsent(g.getId(), Field::new);
    }

    /** Playtest: whether a field is being drawn for this Golem, and its hits. */
    public static String debugField(RedstoneGolem g) {
        Field f = FIELDS.get(g.getId());
        return f == null ? "none" : "drawn=" + f.drawnTick + " hits=" + f.hits.size() + " totalHits=" + f.totalHits;
    }

    private record Hit(int face, int u, int v, int tick) {}

    private static final class Field {
        final ResourceLocation id;
        final DynamicTexture tex;
        final List<Hit> hits = new ArrayList<>();
        int lastTick = Integer.MIN_VALUE, drawnTick = -1, totalHits;

        Field(int golemId) {
            id = ResourceLocation.fromNamespaceAndPath("mattermod", "dynamic/golem_field_" + golemId);
            tex = new DynamicTexture(W, TEX_H, true);
            Minecraft.getInstance().getTextureManager().register(id, tex);
        }

        void close() {
            Minecraft.getInstance().getTextureManager().release(id);
        }

        void hit(RedstoneGolem g, Vec3 p) {
            double x = p.x - g.getX(), y = p.y - g.getY(), z = p.z - g.getZ();
            double hw = RedstoneGolem.FIELD_HALF, top = RedstoneGolem.FIELD_HEIGHT;
            int face, u, v;
            double dRoof = Math.abs(y - top), dx = hw - Math.abs(x), dz = hw - Math.abs(z);
            if (dRoof < Math.min(dx, dz)) {
                face = 4;
                u = (int) ((x + hw) * 16);
                v = (int) ((z + hw) * 16);
            } else if (dz <= dx) {
                face = z < 0 ? 0 : 1;
                u = (int) ((x + hw) * 16);
                v = (int) ((top - y) * 16);
            } else {
                face = x < 0 ? 2 : 3;
                u = (int) ((z + hw) * 16);
                v = (int) ((top - y) * 16);
            }
            hits.add(new Hit(face, u, v, g.tickCount));
            totalHits++;
            while (hits.size() > 24) hits.remove(0);
        }

        /** Redraws the texture for the Golem's current tick. */
        void redraw(RedstoneGolem g) {
            int t = g.tickCount;
            if (t == lastTick) return;
            lastTick = t;
            drawnTick = t;
            hits.removeIf(h -> t - h.tick > 20);
            boolean live = g.overheated();
            int since = t - g.fieldOnTick, gone = t - g.fieldOffTick;
            float rise = live ? Math.min(1, since / (float) DEPLOY) : 1;
            float roof = live ? Math.max(0, Math.min(1, (since - ROOF_FROM) / (float) ROOF_LEN)) : 1;
            float fade = live ? 1 : Math.max(0, 1 - gone / (float) COLLAPSE);
            NativeImage img = tex.getPixels();
            if (img == null) return;
            for (int face = 0; face < 5; face++) {
                boolean isTop = face == 4;
                int fh = isTop ? W : H, y0 = face * H;
                for (int y = 0; y < fh; y++)
                    for (int x = 0; x < W; x++) {
                        float a = pixel(face, isTop, x, y, fh, t, rise, roof, fade, live);
                        float w = Math.max(0, a - .6f) / .4f;
                        int r = (int) (TINT[0] * a + (255 - TINT[0]) * w * a), gg = (int) (TINT[1] * a + (255 - TINT[1]) * w * a),
                                b = (int) (TINT[2] * a + (255 - TINT[2]) * w * a);
                        img.setPixelRGBA(x, y0 + y, 0xFF000000 | Math.min(255, b) << 16 | Math.min(255, gg) << 8 | Math.min(255, r));
                    }
            }
            tex.upload();
        }

        private float pixel(int face, boolean isTop, int x, int y, int fh, int t, float rise, float roof, float fade, boolean live) {
            int hy = isTop ? H : H - 1 - y;
            int rim = isTop ? Math.min(Math.min(x, y), Math.min(W - 1 - x, fh - 1 - y)) : 99;
            float maxRim = isTop ? W / 2f : 1;
            boolean visible = isTop ? rim / maxRim <= roof * 1.02f && roof > 0 : hy <= rise * H;
            if (!visible) return 0;
            boolean edge = x == 0 || x == W - 1 || y == fh - 1 || y == 0;
            int row = y / 4, off = (row % 2) * 3;
            boolean line = y % 4 == 0 || (x + off) % 6 == 0;
            float a = line ? .17f : .025f;
            if (!isTop) a += (1 - hy / (float) H) * (line ? .12f : .03f);
            float db = Math.abs(hy - band(t));
            if (db < 6 && !isTop) a += (line ? .35f : .08f) * (1 - db / 6);
            int id = row * 997 + (x + off) / 6 + face * 50021;
            float ph = hash(id + (int) Math.floor((t + hash(id) * 40) / 40) * 7919);
            if (ph < .02f) a += line ? .25f : .3f;
            if (live && rise < 1 && !isTop && Math.abs(hy - rise * H) < 1.5f) a = 1;
            if (live && roof > 0 && roof < 1 && isTop && Math.abs(rim / maxRim - roof) < .06f) a = 1;
            for (Hit h : hits) {
                if (h.face != face) continue;
                int age = t - h.tick;
                float r = age * 1.6f;
                int dist = Math.max(Math.abs(x - h.u), Math.abs(y - h.v));
                if (age < 2 && dist < 3) a = 1;
                if (Math.abs(dist - r) < 1 && age < 18) a += (line ? .9f : .45f) * (1 - age / 18f);
                else if (dist < r && age < 10) a += .12f * (1 - age / 10f);
            }
            if (edge) a = Math.max(a, .7f + .2f * (float) Math.sin(t * .3));
            if (fade < 1 && hash(x * 131 + y * 7 + face * 99991) > fade) return 0;
            return Math.min(1, a) * fade;
        }

        private float band(int t) {
            return (t * 1.5f) % (H + 40) - 20;
        }
    }

    private static float hash(int n) {
        n = (n ^ 61) ^ (n >>> 16);
        n = n + (n << 3);
        n = n ^ (n >>> 4);
        n = n * 0x27d4eb2d;
        return ((n ^ (n >>> 15)) & 0xFFFFFFFFL) / 4294967296f;
    }

    /** Draws the Golem's field, if it is up or still breaking up; the pose is at the Golem's (interpolated) feet. */
    static void renderField(RedstoneGolem g, PoseStack pose, MultiBufferSource buffers) {
        boolean live = g.overheated();
        if (!live && g.tickCount - g.fieldOffTick > COLLAPSE) {
            Field f = FIELDS.remove(g.getId());
            if (f != null) f.close();
            return;
        }
        Field f = field(g);
        f.redraw(g);
        VertexConsumer vc = buffers.getBuffer(RenderType.eyes(f.id));
        PoseStack.Pose p = pose.last();
        float hw = (float) RedstoneGolem.FIELD_HALF, top = (float) RedstoneGolem.FIELD_HEIGHT;
        float band = H / (float) TEX_H, roofV0 = 4 * band;
        // walls: corners from the bottom-left as seen from outside, u along the wall, v from the top of the band
        wall(vc, p, -hw, -hw, hw, -hw, 0, top, 0 * band, band);   // north: x -> +x at z = -hw
        wall(vc, p, -hw, hw, hw, hw, 0, top, 1 * band, band);     // south
        wall(vc, p, -hw, -hw, -hw, hw, 0, top, 2 * band, band);   // west: z -> +z at x = -hw
        wall(vc, p, hw, -hw, hw, hw, 0, top, 3 * band, band);     // east
        // roof: u along x, v along z
        float[][] roof = {{-hw, -hw, 0, roofV0}, {hw, -hw, 1, roofV0}, {hw, hw, 1, 1}, {-hw, hw, 0, 1}};
        for (int n = 0; n < 8; n++) {
            float[] c = roof[n < 4 ? n : 7 - n];
            vertex(vc, p, c[0], top, c[1], c[2], c[3]);
        }
    }

    private static void wall(VertexConsumer vc, PoseStack.Pose p, float x0, float z0, float x1, float z1, float yb, float yt, float v0, float vh) {
        float[][] q = {{x0, yb, z0, 0, v0 + vh}, {x1, yb, z1, 1, v0 + vh}, {x1, yt, z1, 1, v0}, {x0, yt, z0, 0, v0}};
        for (int n = 0; n < 8; n++) {   // both windings: seen from inside and out
            float[] c = q[n < 4 ? n : 7 - n];
            vertex(vc, p, c[0], c[1], c[2], c[3], c[4]);
        }
    }

    private static void vertex(VertexConsumer vc, PoseStack.Pose p, float x, float y, float z, float u, float v) {
        vc.addVertex(p, x, y, z).setColor(255, 255, 255, 255).setUv(u, v).setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(LightTexture.FULL_BRIGHT).setNormal(p, 0, 1, 0);
    }
}
