package com.ethermod.client.beam;

import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import org.joml.Matrix4f;

import java.util.List;

/** The beam's path (see {@link BeamRenderer}): the bent, rippling curve from the nozzle, and the Arc jumps. */
@OnlyIn(Dist.CLIENT)
final class BeamCurve {

    private BeamCurve() {}

    private static final float PX = BeamFx.PX;

    /**
     * Combat: Arc. From ramp level 2 the beam jumps off the target to the nearest creatures (one
     * per rank), mirroring the server's pick.
     */
    static void drawArcs(
            Matrix4f m,
            Vec3 camPos,
            Player player,
            ItemStack held,
            HitResult hit,
            int level,
            float time,
            float[] color) {
        if (!(hit instanceof EntityHitResult eh) || level < 2) return;
        int arc = com.ethermod.trait.Traits.rank(held, "plasmic.arc");
        if (arc <= 0 || !(eh.getEntity() instanceof net.minecraft.world.entity.LivingEntity first))
            return;
        Vec3 from = first.getBoundingBox().getCenter();
        List<net.minecraft.world.entity.LivingEntity> near =
                player.level()
                        .getEntitiesOfClass(
                                net.minecraft.world.entity.LivingEntity.class,
                                first.getBoundingBox().inflate(4 + arc),
                                e ->
                                        e != first
                                                && !com.ethermod.beam.Mounts.own(player, e)
                                                && e.isAlive()
                                                && e.isPickable()
                                                && !(e instanceof Player));
        near.sort(java.util.Comparator.comparingDouble(e -> e.distanceToSqr(first)));
        for (int i = 0; i < Math.min(arc, near.size()); i++) {
            Vec3 to = near.get(i).getBoundingBox().getCenter();
            Vec3 dir = to.subtract(from).normalize();
            drawBeam(
                    -(player.getId() * 32 + i + 1), // own roll/arcs, no swell (not the striking beam)
                    m,
                    camPos,
                    from,
                    dir,
                    to,
                    1f,
                    Math.max(1, level - 1),
                    time + i * 7,
                    color,
                    true);
        }
    }

    static Vec3 approximateMuzzle(Player p, float pt) {
        Vec3 eye = p.getEyePosition(pt);
        Vec3 look = p.getViewVector(pt);
        Vec3 right =
                Math.abs(look.y) < 0.999
                        ? look.cross(new Vec3(0, 1, 0)).normalize()
                        : new Vec3(1, 0, 0);
        float hs = p.getMainArm() == HumanoidArm.RIGHT ? 1f : -1f;
        return eye.add(right.scale(0.35f * hs)).add(look.scale(0.55f)).add(0, -0.3, 0);
    }

    /**
     * The beam: one continuous line from the nozzle that bends towards the (lagging) tip. It runs
     * through a row of points that each drift on their own slow, smooth wobble, so the line ripples
     * without jumping. It is a single flat ribbon facing the camera with a bright centre that fades
     * to nothing at its edges, so the glow never overlaps itself. The colour shifts hue along its
     * length, and the gradient flows towards the target.
     */
    static void drawBeam(
            int owner,
            Matrix4f m,
            Vec3 camPos,
            Vec3 fromW,
            Vec3 aim,
            Vec3 toW,
            float extend,
            int level,
            float time,
            float[] color,
            boolean hitSomething) {
        double full = fromW.distanceTo(toW);
        if (full < 0.05) return;
        Vec3 a = fromW.subtract(camPos);
        Vec3 tip = toW.subtract(camPos);
        Vec3 c =
                a.add(
                        aim.scale(
                                full
                                        * 0.5)); // control point on the aim line: a lagging tip
                                                 // bends the beam
        int lv = Math.max(0, Math.min(2, level - 1)); // 0, 1, 2

        // A few control points along the curve, each drifting slowly on its own
        int nc = Math.max(3, Math.min(24, (int) Math.round(full / 0.9)));
        Vec3[] ctl = new Vec3[nc + 1];
        for (int i = 0; i <= nc; i++) {
            double t = (double) i / nc * extend;
            double u = 1 - t;
            ctl[i] = a.scale(u * u).add(c.scale(2 * u * t)).add(tip.scale(t * t));
        }
        Vec3 axis = ctl[nc].subtract(ctl[0]).normalize();
        Vec3 su = axis.cross(new Vec3(0, 1, 0));
        if (su.lengthSqr() < 1e-6) su = axis.cross(new Vec3(1, 0, 0));
        su = su.normalize();
        Vec3 sv = su.cross(axis).normalize();
        float amp = (1.6f + 0.6f * lv) * PX; // a clear sway, the same size all along the beam
        float speed = 1f + 0.4f * lv;
        for (int i = 1; i < nc; i++) {
            long h = i * 0x9E3779B97F4A7C15L;
            double f1 = 0.08 + ((h >>> 8) & 0xff) / 255.0 * 0.08,
                    f2 = 0.06 + ((h >>> 16) & 0xff) / 255.0 * 0.07;
            double p1 = ((h >>> 24) & 0xff) / 255.0 * 6.283,
                    p2 = ((h >>> 32) & 0xff) / 255.0 * 6.283;
            double ox = Math.sin(time * f1 * speed + p1), oy = Math.sin(time * f2 * speed + p2);
            float ks = BeamRenderer.nearScale(ctl[i]);
            ctl[i] = ctl[i].add(su.scale(ox * amp * ks)).add(sv.scale(oy * amp * ks));
        }
        // Smooth curve through them (Catmull-Rom), so there are no corners to fold over
        int sub = 6;
        int n = nc * sub;
        Vec3[] pts = new Vec3[n + 1];
        for (int i = 0; i < nc; i++) {
            Vec3 p0 = ctl[Math.max(0, i - 1)],
                    p1 = ctl[i],
                    p2 = ctl[i + 1],
                    p3 = ctl[Math.min(nc, i + 2)];
            for (int j = 0; j < sub; j++) {
                double t = (double) j / sub, t2 = t * t, t3 = t2 * t;
                pts[i * sub + j] =
                        p1.scale(2)
                                .add(p2.subtract(p0).scale(t))
                                .add(
                                        p0.scale(2)
                                                .subtract(p1.scale(5))
                                                .add(p2.scale(4))
                                                .subtract(p3)
                                                .scale(t2))
                                .add(
                                        p1.scale(3)
                                                .subtract(p0)
                                                .subtract(p2.scale(3))
                                                .add(p3)
                                                .scale(t3))
                                .scale(0.5);
            }
        }
        pts[n] = ctl[nc];

        // the look chosen on the FX bench: the Prism rope, with surface arcs and the damage swell
        PrismRope.draw(owner, pts, sub, su, time, level, color, hitSomething, full);
    }
}
