package com.ethermod.trait;

import com.ethermod.beam.BeamBallEntity;
import com.ethermod.manipulator.Manipulator;

import net.minecraft.ChatFormatting;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Gravitic: Zipline. Hook a block and let go while sneaking: the hook stays as the rope's first
 * end. Hook a second block and the rope strings between the two hooks. Anyone in Tether mode rides
 * it by aiming at it and holding RMB: they slide the way they look. Ropes last 2 minutes (10 at II,
 * up to 3).
 */
public final class Ziplines {

    private Ziplines() {}

    private static final Map<UUID, Integer> PENDING = new HashMap<>();
    private static final Map<UUID, Deque<int[]>> ROPES = new HashMap<>();

    /** speed[0]: the rider's speed along the rope (blocks per tick, signed toward b). */
    private record Ride(int a, int b, double[] speed) {}

    /** How fast a rider slides, and how quickly they reach that speed or turn around. */
    private static final double RIDE_SPEED = 0.6, RIDE_ACCEL = 0.08;
    /**
     * How much of the sideways and vertical gap to the rope is closed each tick. Kept low on
     * purpose: the server only sees the rider's position a few ticks late, and a stronger pull
     * overshoots and swings the rider from side to side.
     */
    private static final double RIDE_PULL = 0.15;

    private static final Map<UUID, Ride> RIDES = new HashMap<>();

    /** Let go while sneaking: the hook becomes the first end. */
    public static void pin(ServerPlayer sp, BeamBallEntity hook) {
        ServerLevel sl = sp.serverLevel();
        Integer old = PENDING.remove(sp.getUUID());
        if (old != null
                && sl.getEntity(old) instanceof BeamBallEntity o
                && o.isAnchor()
                && o.getPartnerId() < 0) o.discard();
        hook.makeAnchor(600, -1);
        PENDING.put(sp.getUUID(), hook.getId());
        sl.playSound(
                null,
                hook.getX(),
                hook.getY(),
                hook.getZ(),
                com.ethermod.sound.ModSounds.WORLD_CHAIN_PLACE,
                SoundSource.PLAYERS,
                1f,
                0.8f);
        sp.displayClientMessage(
                Component.translatable("message.ethermod.zipline_first")
                        .withStyle(ChatFormatting.LIGHT_PURPLE),
                true);
    }

    /** A hook just stuck: if a first end is waiting, string the rope. */
    public static void onHookStuck(ServerPlayer sp, BeamBallEntity hook) {
        Integer pid = PENDING.get(sp.getUUID());
        if (pid == null) return;
        ServerLevel sl = sp.serverLevel();
        if (!(sl.getEntity(pid) instanceof BeamBallEntity a)
                || !a.isAnchor()
                || a.getPartnerId() >= 0
                || a == hook) {
            PENDING.remove(sp.getUUID());
            return;
        }
        double d = a.position().distanceTo(hook.position());
        if (d < 3 || d > 64) return;
        PENDING.remove(sp.getUUID());
        int life = 2400;
        hook.makeAnchor(life, a.getId());
        a.linkAnchor(hook.getId(), life);
        Deque<int[]> list = ROPES.computeIfAbsent(sp.getUUID(), k -> new ArrayDeque<>());
        list.removeIf(pair -> !(sl.getEntity(pair[0]) instanceof BeamBallEntity e) || !e.isAlive());
        list.addLast(new int[] {a.getId(), hook.getId()});
        while (list.size() > 1) {
            int[] o = list.removeFirst();
            for (int id : o) if (sl.getEntity(id) instanceof BeamBallEntity e) e.discard();
        }
        sl.playSound(
                null,
                hook.getX(),
                hook.getY(),
                hook.getZ(),
                com.ethermod.sound.ModSounds.WORLD_CHAIN_PLACE,
                SoundSource.PLAYERS,
                1f,
                1.2f);
        sp.displayClientMessage(
                Component.translatable("message.ethermod.zipline_done")
                        .withStyle(ChatFormatting.LIGHT_PURPLE),
                true);
    }

    /** Tether pressed: ride a rope you aim at, if there is one in reach. */
    public static boolean tryRide(ServerPlayer sp) {
        ServerLevel sl = sp.serverLevel();
        Vec3 eye = sp.getEyePosition(), far = eye.add(sp.getLookAngle().scale(10));
        BeamBallEntity bestA = null, bestB = null;
        double best = 1.6;
        for (BeamBallEntity a :
                sl.getEntitiesOfClass(
                        BeamBallEntity.class,
                        sp.getBoundingBox().inflate(64),
                        b -> b.isAnchor() && b.getPartnerId() >= 0)) {
            if (!(sl.getEntity(a.getPartnerId()) instanceof BeamBallEntity b) || !b.isAlive())
                continue;
            double d = segmentDistance(eye, far, a.position(), b.position());
            if (d < best) {
                best = d;
                bestA = a;
                bestB = b;
            }
        }
        if (bestA == null) {
            RIDES.remove(sp.getUUID());
            return false;
        }
        // the rider keeps the momentum they jumped on with, along the rope only
        Vec3 ab = bestB.position().subtract(bestA.position());
        double along = ab.lengthSqr() > 1e-6 ? sp.getDeltaMovement().dot(ab.normalize()) : 0;
        along = Math.max(-1.2, Math.min(1.2, along));
        RIDES.put(sp.getUUID(), new Ride(bestA.getId(), bestB.getId(), new double[] {along}));
        sl.playSound(
                null,
                sp.getX(),
                sp.getEyeY(),
                sp.getZ(),
                com.ethermod.sound.ModSounds.WORLD_CHAIN_HIT,
                SoundSource.PLAYERS,
                0.8f,
                1.4f);
        return true;
    }

    public static boolean isRiding(ServerPlayer sp) {
        return RIDES.containsKey(sp.getUUID());
    }

    public static void stopRide(ServerPlayer sp) {
        RIDES.remove(sp.getUUID());
    }

    /** While RMB is held: slide along the rope the way you look, hanging under it. */
    public static void rideTick(ServerPlayer sp) {
        Ride r = RIDES.get(sp.getUUID());
        if (r == null) return;
        ServerLevel sl = sp.serverLevel();
        if (!(sl.getEntity(r.a()) instanceof BeamBallEntity a)
                || !(sl.getEntity(r.b()) instanceof BeamBallEntity b)
                || !a.isAlive()
                || !b.isAlive()) {
            RIDES.remove(sp.getUUID());
            return;
        }
        Vec3 A = a.position(), ab = b.position().subtract(A);
        double len = ab.length();
        if (len < 0.5) return;
        Vec3 dir = ab.scale(1 / len);
        Vec3 hands = sp.position().add(0, 2.1, 0);
        double t = Math.max(0, Math.min(len, hands.subtract(A).dot(dir)));
        double way = Math.signum(sp.getLookAngle().dot(dir));
        if (way == 0) way = dir.y < 0 ? 1 : -1;
        // ease toward full speed the way you look, so a turn-around is a smooth stop and go
        double[] speed = r.speed();
        speed[0] += Math.max(-RIDE_ACCEL, Math.min(RIDE_ACCEL, RIDE_SPEED * way - speed[0]));
        // stop at the ends instead of pushing into the hook
        if ((t <= 0 && speed[0] < 0) || (t >= len && speed[0] > 0)) speed[0] = 0;
        // along the rope at that speed; sideways and up/down, a soft pull back under the rope.
        // Only the gap across the rope counts, so momentum never turns into a zig-zag, and the
        // pull lifts by the gravity the client adds each tick so the rider doesn't sag.
        Vec3 gap = A.add(dir.scale(t)).subtract(hands);
        gap = gap.subtract(dir.scale(gap.dot(dir)));
        Vec3 v = dir.scale(speed[0]).add(gap.scale(RIDE_PULL)).add(0, 0.08, 0);
        if (v.length() > 1.2) v = v.normalize().scale(1.2);
        sp.setDeltaMovement(v);
        sp.hurtMarked = true;
        sp.resetFallDistance();
        MobilityEffects.markSoftLanding(sp);
        if (sp.tickCount % 3 == 0) {
            Vec3 h = sp.position().add(0, 2.1, 0);
            sl.sendParticles(ParticleTypes.ELECTRIC_SPARK, h.x, h.y, h.z, 2, 0.1, 0.05, 0.1, 0.05);
        }
    }

    /** Shortest distance between segments p1-q1 and p2-q2. */
    static double segmentDistance(Vec3 p1, Vec3 q1, Vec3 p2, Vec3 q2) {
        Vec3 d1 = q1.subtract(p1), d2 = q2.subtract(p2), r = p1.subtract(p2);
        double a = d1.dot(d1), e = d2.dot(d2), f = d2.dot(r);
        double s, t;
        if (a <= 1e-9 && e <= 1e-9) return p1.distanceTo(p2);
        if (a <= 1e-9) {
            s = 0;
            t = clamp(f / e);
        } else {
            double c = d1.dot(r);
            if (e <= 1e-9) {
                t = 0;
                s = clamp(-c / a);
            } else {
                double b = d1.dot(d2), denom = a * e - b * b;
                s = denom != 0 ? clamp((b * f - c * e) / denom) : 0;
                t = (b * s + f) / e;
                if (t < 0) {
                    t = 0;
                    s = clamp(-c / a);
                } else if (t > 1) {
                    t = 1;
                    s = clamp((b - c) / a);
                }
            }
        }
        return p1.add(d1.scale(s)).distanceTo(p2.add(d2.scale(t)));
    }

    private static double clamp(double v) {
        return Math.max(0, Math.min(1, v));
    }

    public static void clear() {
        PENDING.clear();
        ROPES.clear();
        RIDES.clear();
    }
}
