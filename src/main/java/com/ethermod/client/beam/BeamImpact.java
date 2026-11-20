package com.ethermod.client.beam;

import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;

/** Where the beam hits (see {@link BeamRenderer}): the impact crown, popping sparks, and the pixels it breaks into when released. */
@OnlyIn(Dist.CLIENT)
final class BeamImpact {

    private BeamImpact() {}

    private static final float PX = BeamFx.PX;

    private static final List<Spark> sparks = new ArrayList<>();
    private static final Random RNG = new Random();
    private static float spawnDebt = 0f;

    static void clear() {
        sparks.clear();
        spikes.clear();
    }

    private static final List<Spike> spikes = new ArrayList<>();
    private static float crownDebt = 0f;

    static void spawnImpactFx(
            ClientLevel level,
            HitResult hit,
            Vec3 toW,
            Vec3 fromW,
            float[] color,
            int combatLevel,
            float dt,
            boolean hitSomething,
            int owner) {
        Vec3 back = fromW.subtract(toW).normalize();

        // Crown: thin spikes flaring back from the impact in a ring, a few at a time.
        // Nothing hit: no crown, the beam just fades out (see drawBeam)
        crownDebt += dt * (hitSomething ? 20f + 5f * combatLevel : 0f);
        while (crownDebt >= 1f) {
            crownDebt -= 1f;
            spikes.add(Spike.crown(owner, toW, back, color, hitSomething, combatLevel));
        }

        if (!hitSomething) return;
        // Sparks pop off in small bursts rather than a constant trickle
        spawnDebt += dt * (6f + 2f * combatLevel);
        while (spawnDebt >= 1f) {
            spawnDebt -= 1f;
            int burst = 3 + RNG.nextInt(3);
            for (int i = 0; i < burst; i++) {
                Vec3 dir = back.add(rand(1.6), rand(1.6) + 0.35, rand(1.6)).normalize();
                sparks.add(
                        new Spark(
                                toW.add(back.scale(0.03)),
                                dir.scale(4.0 + RNG.nextDouble() * 4.0),
                                0.18f + RNG.nextFloat() * 0.22f,
                                color));
            }
        }

        if (RNG.nextFloat() < dt * 12f) {
            if (hit instanceof BlockHitResult bh) {
                BlockPos pos = bh.getBlockPos();
                BlockState state = level.getBlockState(pos);
                if (!state.isAir()) {
                    level.addParticle(
                            new BlockParticleOption(ParticleTypes.BLOCK, state),
                            toW.x,
                            toW.y,
                            toW.z,
                            back.x * 0.6 + rand(0.3),
                            back.y * 0.6 + 0.2,
                            back.z * 0.6 + rand(0.3));
                }
            } else {
                level.addParticle(
                        ParticleTypes.ELECTRIC_SPARK,
                        toW.x,
                        toW.y,
                        toW.z,
                        rand(0.4),
                        rand(0.4) + 0.1,
                        rand(0.4));
            }
        }
    }

    /** The beam breaks into drifting pixels when released. */
    static void spawnShutdown(Vec3 fromW, Vec3 toW, float[] color) {
        Vec3 diff = toW.subtract(fromW);
        int count = 16 + RNG.nextInt(10);
        for (int i = 0; i < count; i++) {
            Vec3 pos = fromW.add(diff.scale(RNG.nextDouble()));
            Vec3 vel =
                    new Vec3(rand(1), rand(1), rand(1))
                            .normalize()
                            .scale(0.6 + RNG.nextDouble() * 0.8);
            sparks.add(new Spark(pos, vel, 0.3f + RNG.nextFloat() * 0.3f, color).floaty());
        }
    }

    static void updateAndRenderSparks(
            float dt, Vec3 camPos, VertexConsumer vc, Matrix4f m) {
        if (sparks.size() > 400) sparks.subList(0, sparks.size() - 400).clear();
        Iterator<Spark> it = sparks.iterator();
        while (it.hasNext()) {
            Spark s = it.next();
            if (!s.update(dt)) {
                it.remove();
                continue;
            }
            float life = 1f - s.age / s.lifetime;
            // pop: a white flash at birth, then shrink to nothing
            float flash = Math.max(0f, 1f - s.age / 0.05f);
            float h = PX * 0.5f * (0.35f + 0.65f * life) * BeamRenderer.nearScale(s.pos.subtract(camPos));
            BeamFx.cube(
                    vc,
                    m,
                    s.pos.subtract(camPos),
                    h,
                    mix(s.r, 1f, 0.4f + 0.6f * flash),
                    mix(s.g, 1f, 0.4f + 0.6f * flash),
                    mix(s.b, 1f, 0.4f + 0.6f * flash),
                    0.95f);
        }

        if (spikes.size() > 200) spikes.subList(0, spikes.size() - 200).clear();
        Iterator<Spike> sp = spikes.iterator();
        while (sp.hasNext()) {
            Spike k = sp.next();
            k.age += dt;
            if (k.age >= k.lifetime) {
                sp.remove();
                continue;
            }
            float t = k.age / k.lifetime;
            float grow = 1f - (1f - t) * (1f - t); // ease out
            float alpha = 1f - t * t;
            // stays on the beam's tip as it moves, instead of where it was spawned
            Vec3 anchor = BeamRenderer.tipOf(k.owner);
            Vec3 o = (anchor != null ? anchor : k.origin).subtract(camPos);
            float ks = BeamRenderer.nearScale(o);
            Vec3 end = o.add(k.dir.scale(k.length * grow * ks));
            // tapered: a thick base pixel and a thinner tip
            Vec3 mid = o.add(end.subtract(o).scale(0.4));
            BeamFx.prism(vc, m, o, mid, PX * 0.6f * ks, 0.785, k.r, k.g, k.b, alpha);
            BeamFx.prism(
                    vc,
                    m,
                    mid,
                    end,
                    PX * 0.3f * ks,
                    0.785,
                    mix(k.r, 1f, 0.5f),
                    mix(k.g, 1f, 0.5f),
                    mix(k.b, 1f, 0.5f),
                    alpha);
        }
    }

    private static final class Spark {
        Vec3 pos, vel;
        final float lifetime, r, g, b;
        float age;
        boolean floaty;

        Spark(Vec3 pos, Vec3 vel, float lifetime, float[] c) {
            this.pos = pos;
            this.vel = vel;
            this.lifetime = lifetime;
            this.r = c[0];
            this.g = c[1];
            this.b = c[2];
        }

        Spark floaty() {
            floaty = true;
            return this;
        }

        boolean update(float dt) {
            age += dt;
            if (age >= lifetime) return false;
            if (floaty) vel = vel.scale(Math.pow(0.05, dt));
            // fast burst, heavy drag, then gravity takes over
            else vel = vel.add(0, -14.0 * dt, 0).scale(Math.pow(0.02, dt));
            pos = pos.add(vel.scale(dt));
            return true;
        }
    }

    /** One spike of the impact crown. */
    private static final class Spike {
        final Vec3 origin, dir;
        final float length, lifetime, r, g, b;
        final int owner;
        float age;
        private static double crownSpin;

        private Spike(int owner, Vec3 origin, Vec3 dir, float length, float lifetime, float[] c) {
            this.owner = owner;
            this.origin = origin;
            this.dir = dir;
            this.length = length;
            this.lifetime = lifetime;
            this.r = mix(c[0], 1f, 0.3f);
            this.g = mix(c[1], 1f, 0.3f);
            this.b = mix(c[2], 1f, 0.3f);
        }

        /**
         * Spikes sit on a cone around the way back to the nozzle, at slowly turning ring positions.
         */
        static Spike crown(int owner, Vec3 at, Vec3 back, float[] c, boolean hit, int level) {
            Vec3 u = back.cross(new Vec3(0, 1, 0));
            if (u.lengthSqr() < 1e-6) u = back.cross(new Vec3(1, 0, 0));
            u = u.normalize();
            Vec3 v = u.cross(back).normalize();
            int points = 6;
            crownSpin += 0.37;
            double ang =
                    Math.round(crownSpin * points / (Math.PI * 2)) * (Math.PI * 2 / points)
                            + (RNG.nextDouble() - 0.5) * 0.35;
            double spread = Math.toRadians(16 + RNG.nextDouble() * 10);
            Vec3 ring = u.scale(Math.cos(ang)).add(v.scale(Math.sin(ang)));
            Vec3 dir = back.scale(Math.cos(spread)).add(ring.scale(Math.sin(spread))).normalize();
            float len = 0.22f + RNG.nextFloat() * 0.16f + 0.05f * level;
            return new Spike(owner, at, dir, len, 0.14f + RNG.nextFloat() * 0.1f, c);
        }
    }

    private static double rand(double s) {
        return (RNG.nextDouble() - 0.5) * s;
    }

    private static float mix(float a, float b, float t) {
        return a + (b - a) * t;
    }
}
