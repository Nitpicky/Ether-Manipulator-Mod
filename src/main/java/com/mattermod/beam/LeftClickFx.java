package com.mattermod.beam;

import com.mattermod.item.OrbMode;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import org.joml.Matrix4f;

/**
 * Draws the left-click effects in the same pixel-cube style and mode colours as the right-click
 * shots ({@link BeamBallRenderer}): the gust grenade is a Gust swirl that ticks faster before it
 * bursts, the Gravitic pull is the Tether rope, the poison puff is a Growth pollen cloud, and the
 * Botanic harvest leaves a Growth slash through each crop, chaining into one sweep.
 */
@OnlyIn(Dist.CLIENT)
final class LeftClickFx {

    private LeftClickFx() {}

    private static final float[] GUST = rgb(OrbMode.GUST.color);
    private static final float[] TETHER = rgb(OrbMode.TETHER.color);
    private static final float[] GROWTH = rgb(OrbMode.GROWTH.color);

    private static float[] rgb(int c) {
        return new float[] {(c >> 16 & 255) / 255f, (c >> 8 & 255) / 255f, (c & 255) / 255f, 1f};
    }

    static void render(Minecraft mc, Vec3 cam, float pt, float time, VertexConsumer vc, Matrix4f m) {
        long now = LeftClickFxData.now();
        float age0 = now + pt;

        // ---- Gust grenade: a swirl in flight; latched, its core pulses faster and faster ----
        for (var g : LeftClickFxData.GRENADES.values()) {
            Vec3 at = g.prev.add(g.pos.subtract(g.prev).scale(pt)).subtract(cam);
            if (g.fuse < 0) {
                BeamBallRenderer.drawGust(vc, m, at, time, 1, 0.9f);
                // the same shrinking pixel trail as a thrown orb
                Vec3 back = g.prev.subtract(g.pos);
                for (int i = 1; i <= 4; i++) {
                    float t = i / 5f;
                    BeamFx.spinCube(
                            vc,
                            m,
                            at.add(back.scale(i * 0.5)),
                            BeamFx.px(0.12f * (1 - t)),
                            time * 0.2 + i,
                            0.5,
                            GUST[0],
                            GUST[1],
                            GUST[2],
                            0.6f * (1 - t));
                }
            } else {
                float urgency = 1 - g.fuse / (float) GustGrenade.FUSE; // 0 → 1 as it nears the burst
                float rate = 0.35f + urgency * 1.4f;
                float pulse = 0.5f + 0.5f * (float) Math.sin(time * rate * 2);
                BeamBallRenderer.drawGust(vc, m, at, time * (1 + urgency), 0, 0.7f + 0.3f * urgency);
                BeamFx.spinCube(
                        vc,
                        m,
                        at,
                        BeamFx.PX * (1.5f + pulse * (1 + urgency)),
                        time * 0.4,
                        0.6,
                        mix(GUST[0], 1, pulse),
                        mix(GUST[1], 1, pulse),
                        mix(GUST[2], 1, pulse),
                        0.55f + 0.4f * pulse);
            }
        }

        // ---- Burst: a ring of wind pixels thrown out to the blast radius ----
        for (var b : LeftClickFxData.BURSTS) {
            float t = Math.min(1f, (age0 - b.start()) / LeftClickFxData.BURST_TICKS);
            float ease = 1 - (1 - t) * (1 - t);
            float fade = 1 - t;
            Vec3 c = b.pos().subtract(cam);
            int n = 28;
            for (int i = 0; i < n; i++) {
                double a1 = i * 2.399963;
                double a2 = Math.acos(1 - 2 * ((i + 0.5) / n));
                double r = GustGrenade.RADIUS * ease * (0.75 + 0.25 * ((i * 0.61) % 1.0));
                Vec3 o =
                        new Vec3(
                                Math.cos(a1) * Math.sin(a2) * r,
                                Math.cos(a2) * r * 0.6,
                                Math.sin(a1) * Math.sin(a2) * r);
                BeamFx.spinCube(
                        vc,
                        m,
                        c.add(o),
                        BeamFx.PX * (i % 3 == 0 ? 1.4f : 0.9f) * (0.4f + 0.6f * fade),
                        time * 0.5 + i,
                        0.6,
                        mix(GUST[0], 1, fade * 0.5f),
                        mix(GUST[1], 1, fade * 0.5f),
                        mix(GUST[2], 1, fade * 0.5f),
                        0.75f * fade);
            }
            BeamFx.spinCube(
                    vc, m, c, BeamFx.PX * (3 + 5 * ease), time * 0.5, 0.6, 1, 1, 1, 0.35f * fade);
        }

        // ---- Gravitic pull: the Tether rope shoots out, hooks, and reels back in ----
        for (var p : LeftClickFxData.PULLS) {
            float age = age0 - p.start();
            Entity owner = mc.level.getEntity(p.owner());
            if (!(owner instanceof Player op)) continue;
            Vec3 from =
                    op == mc.player && BeamRenderer.nozzle != null
                            ? BeamRenderer.nozzle
                            : BeamRenderer.approximateMuzzle(op, pt);
            Entity target = p.target() >= 0 ? mc.level.getEntity(p.target()) : null;
            Vec3 to =
                    target != null
                            ? target.getPosition(pt).add(0, target.getBbHeight() * 0.5, 0)
                            : p.end();
            // out in 2 ticks, then a missed pull reels back in; a caught one follows its target
            float out = Math.min(1f, age / 2f);
            float back = target == null ? Math.max(0f, (age - 3) / 4f) : 0f;
            float reach = Math.max(0f, out - back);
            float fade = Math.min(1f, (LeftClickFxData.PULL_TICKS - age) / 3f);
            if (reach <= 0 || fade <= 0) continue;
            Vec3 a = from.subtract(cam);
            Vec3 tip = a.add(to.subtract(cam).subtract(a).scale(reach));
            BeamFx.prism(vc, m, a, tip, BeamFx.PX * 1.4f, time * 0.1, TETHER[0], TETHER[1], TETHER[2], 0.35f * fade);
            BeamFx.prism(
                    vc,
                    m,
                    a,
                    tip,
                    BeamFx.PX * 0.5f,
                    time * 0.1,
                    mix(TETHER[0], 1, 0.6f),
                    mix(TETHER[1], 1, 0.6f),
                    mix(TETHER[2], 1, 0.6f),
                    0.95f * fade);
            // the hook, as on the right-click Tether
            BeamFx.spinCube(vc, m, tip, BeamFx.PX * 2, time * 0.3, 0.7, 0.9f, 0.9f, 1f, 0.95f * fade);
        }

        // ---- Poison puff: a Growth pollen cloud that blooms, lingers and thins out ----
        for (var p : LeftClickFxData.PUFFS) {
            float age = age0 - p.start();
            float grow = Math.min(1f, age / 6f);
            grow = 1 - (1 - grow) * (1 - grow);
            float fade = Math.min(1f, (LeftClickFxData.PUFF_TICKS - age) / 10f);
            if (fade <= 0) continue;
            BeamBallRenderer.drawCloud(
                    vc, m, p.pos().subtract(cam), GROWTH, time, 2, 0.5f + 0.7f * grow, 0.75f * fade);
        }

        // ---- Botanic harvest: a slash streak through each crop in the sweep's direction, with chaff
        // pixels thrown on the way; crops are cut one column a tick, so the streaks chain into one
        // sickle pass ----
        for (var c : LeftClickFxData.CUTS) {
            float age = age0 - c.start();
            if (age < 0) continue;
            float t = Math.min(1f, age / LeftClickFxData.CUT_TICKS);
            float fade = 1 - t;
            Vec3 at = c.pos().subtract(cam);
            // the streak shoots across in 2 ticks, its tail catching up behind it
            float head = Math.min(1f, age / 2f), tail = Math.min(1f, Math.max(0f, (age - 1) / 4f));
            Vec3 from = at.add(c.dir().scale(-0.6 + 1.2 * tail)).add(0, 0.12 - 0.1 * tail, 0);
            Vec3 to = at.add(c.dir().scale(-0.6 + 1.2 * head)).add(0, 0.12 - 0.1 * head, 0);
            if (head > tail) {
                BeamFx.prism(vc, m, from, to, BeamFx.PX * 1.6f, time * 0.1, GROWTH[0], GROWTH[1], GROWTH[2], 0.35f * fade);
                BeamFx.prism(
                        vc,
                        m,
                        from,
                        to,
                        BeamFx.PX * 0.5f,
                        time * 0.1,
                        mix(GROWTH[0], 1, 0.6f),
                        mix(GROWTH[1], 1, 0.6f),
                        mix(GROWTH[2], 1, 0.6f),
                        0.95f * fade);
            }
            // chaff: a few pixels thrown along the sweep and up, falling back as they fade
            int n = 6;
            for (int i = 0; i < n; i++) {
                double spread = (i - (n - 1) / 2.0) * 0.18;
                Vec3 side = new Vec3(-c.dir().z, 0, c.dir().x);
                double fly = 0.25 + 0.9 * t * (0.6 + 0.4 * ((i * 0.37) % 1.0));
                double up = 0.5 * t * (1.4 - t) * (0.7 + 0.3 * ((i * 0.53) % 1.0));
                Vec3 o = c.dir().scale(fly).add(side.scale(spread)).add(0, 0.1 + up, 0);
                boolean leaf = i % 2 == 0;
                BeamFx.spinCube(
                        vc,
                        m,
                        at.add(o),
                        BeamFx.PX * (leaf ? 1.2f : 0.8f) * (0.4f + 0.6f * fade),
                        time * 0.5 + i,
                        0.6,
                        leaf ? GROWTH[0] : mix(GROWTH[0], 1, 0.5f),
                        leaf ? GROWTH[1] : mix(GROWTH[1], 1, 0.5f),
                        leaf ? GROWTH[2] : mix(GROWTH[2], 1, 0.5f),
                        0.8f * fade);
            }
        }
    }

    private static float mix(float a, float b, float t) {
        return a + (b - a) * t;
    }
}
