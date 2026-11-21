package com.ethermod.beam;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.phys.Vec3;

/**
 * Gust mode: the travelling air burst, Deflector and the final burst. Split out of {@link
 * BeamBallEntity}; every method takes the orb as {@code b}.
 */
final class OrbGust {

    private OrbGust() {}

    /**
     * In flight: Deflector turns projectiles around; a Cyclone drifts and lifts. Returns true if it
     * handled the tick.
     */
    static boolean gustTick(BeamBallEntity b) {
        ServerLevel sl = (ServerLevel) b.level();
        Entity owner = b.getOwner();
        if (!b.has("pneumatic.cyclone") || b.getPhase() != 2) return false;
        // Cyclone: a slow whirlwind that lifts mobs and items, then bursts
        b.lifeTicks++;
        int life = 60;
        Vec3 v = b.getDeltaMovement();
        Vec3 next = b.position().add(v);
        if (!sl.noCollision(b, b.getBoundingBox().move(v))) v = Vec3.ZERO;
        else b.setPos(next.x, next.y, next.z);
        b.setDeltaMovement(v);
        Vec3 c = b.position();
        for (Entity e :
                sl.getEntities(
                        b,
                        b.getBoundingBox().inflate(2.5, 3, 2.5),
                        e ->
                                !com.ethermod.beam.Mounts.own(owner, e)
                                        && (e instanceof LivingEntity
                                                || e instanceof ItemEntity
                                                || e instanceof ExperienceOrb)
                                        && !(e
                                                instanceof
                                                net.minecraft.world.entity.player.Player))) {
            Vec3 off = e.position().subtract(c);
            Vec3 tangent = new Vec3(-off.z, 0, off.x).normalize().scale(0.25);
            Vec3 in = new Vec3(-off.x, 0, -off.z).scale(0.08);
            Vec3 ev = e.getDeltaMovement();
            e.setDeltaMovement(
                    ev.x * 0.5 + tangent.x + in.x,
                    Math.max(ev.y, 0.18),
                    ev.z * 0.5 + tangent.z + in.z);
            com.ethermod.util.MotionSync.sync(e);
            e.resetFallDistance();
        }
        if (b.lifeTicks % 2 == 0) {
            for (int i = 0; i < 4; i++) {
                double a = b.lifeTicks * 0.5 + i * Math.PI / 2, h = (i % 2) * 1.2;
                sl.sendParticles(
                        ParticleTypes.CLOUD,
                        c.x + Math.cos(a) * (1 + h * 0.5),
                        c.y + h,
                        c.z + Math.sin(a) * (1 + h * 0.5),
                        1,
                        0,
                        0,
                        0,
                        0);
            }
        }
        if (b.lifeTicks % 20 == 0)
            sl.playSound(
                    null,
                    c.x,
                    c.y,
                    c.z,
                    com.ethermod.sound.ModSounds.WORLD_BREEZE_IDLE_AIR,
                    SoundSource.PLAYERS,
                    0.8f,
                    0.8f);
        if (b.lifeTicks > life) {
            OrbGust.gustBurst(b, c);
            b.discard();
        }
        return true;
    }

    /**
     * A Gust bursts like a wind charge: knocks things back, flips levers, doors, buttons and
     * trapdoors. Updraft launches you higher from your feet; Deflector II knocks shields out;
     * Shockwave (phase 3) shoves every mob within 8 blocks.
     */
    static void gustBurst(BeamBallEntity b, Vec3 at) {
        if (b.getOwner() instanceof ServerPlayer sp)
            ProjectileEffects.gust((ServerLevel) b.level(), sp, b.traits, at, b.getPhase());
    }
}
