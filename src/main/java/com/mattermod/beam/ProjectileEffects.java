package com.mattermod.beam;

import com.mattermod.trait.*;

import net.minecraft.core.*;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.*;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.effect.*;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.phys.*;

public final class ProjectileEffects {
    private ProjectileEffects() {}

    public static void gust(ServerLevel sl, ServerPlayer owner, ItemStack st, Vec3 at, int phase) {
        gust(sl, owner, st, at, phase, true);
    }

    /** Ground Slam shares Gust's target effects, but never recoils its owner. */
    public static void gust(ServerLevel sl, ServerPlayer owner, ItemStack st, Vec3 at, int phase, boolean recoilOwner) {
        double radius = Traits.has(st, "pneumatic.convergence") ? 3 + phase * 2 : 2 + phase;
        double force = Traits.has(st, "pneumatic.gale_force") ? 1.4 + phase * .7 : .6 + phase * .35;
        for (var e :
                sl.getEntitiesOfClass(
                        LivingEntity.class,
                        new AABB(at, at).inflate(radius),
                        e -> e.isAlive() && !RedesignEffects.ally(owner, e))) {
            Vec3 away = e.position().add(0, e.getBbHeight() * .4, 0).subtract(at);
            if (away.lengthSqr() < .1) away = owner.getLookAngle();
            Vec3 push = away.normalize().scale(force).add(0, .2, 0);
            if (Traits.has(st, "pneumatic.convergence"))
                push = at.subtract(e.position()).normalize().scale(force);
            // Gale Force: harder knockback by charge (above), and the fall after it hurts more
            if (Traits.has(st, "pneumatic.gale_force") && !(e instanceof Player))
                e.getPersistentData().putLong("mm_updraft_until", sl.getGameTime() + 200);
            if (Traits.has(st, "pneumatic.stasis"))
                RedesignEffects.stasis(e, com.mattermod.config.MatterConfig.STASIS_TICKS.get());
            RedesignEffects.impulse(e, push);
            GustMotion.track(e, owner, Traits.has(st, "pneumatic.rebound"));
            if (e instanceof Mob mob && !mob.getMainHandItem().isEmpty()) {
                var item = mob.getMainHandItem().copy();
                mob.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
                mob.spawnAtLocation(item);
            }
        }
        sl.sendParticles(ParticleTypes.GUST_EMITTER_LARGE, at.x, at.y, at.z, 1, 0, 0, 0, 0);
    }

    public static void growthCloud(BeamBallEntity b, ItemStack st) {
        if (b.tickCount % 10 != 0 || !(b.getOwner() instanceof ServerPlayer p)) return;
        int a = 0;
        for (var e :
                p.serverLevel()
                        .getEntitiesOfClass(
                                LivingEntity.class,
                                b.getBoundingBox().inflate(2),
                                e ->
                                        RedesignEffects.enemy(p, e)
                                                && (e
                                                                instanceof
                                                                net.minecraft.world.entity.monster
                                                                        .Enemy
                                                        || e instanceof Mob m
                                                                && m.getTarget() == p))) {
            e.addEffect(new MobEffectInstance(MobEffects.POISON, 60, a));
            e.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 60, a));
        }
    }

    private static BlockPos waterNear(ServerLevel sl, Vec3 at) {
        BlockPos best = null;
        double distance = Double.MAX_VALUE;
        for (var p :
                BlockPos.betweenClosed(
                        BlockPos.containing(at).offset(-2, -2, -2),
                        BlockPos.containing(at).offset(2, 2, 2))) {
            if (sl.hasChunkAt(p)
                    && sl.getFluidState(p).is(FluidTags.WATER)
                    && sl.getFluidState(p.above()).isEmpty()) {
                double d = Vec3.atCenterOf(p).distanceToSqr(at);
                if (d < distance) {
                    best = p.immutable();
                    distance = d;
                }
            }
        }
        return best;
    }

    public static void waterPath(BeamBallEntity b, ItemStack st) {
        if (!(b.getOwner() instanceof ServerPlayer p)) return;
        var sl = p.serverLevel();
        if (b.getMode() == com.mattermod.item.OrbMode.WATER
                && Traits.has(st, "hydraulics.rapids")) {
            var water = waterNear(sl, b.position());
            if (water != null) {
                // over water the shot rides its own surge, at the speed it carries a boat, so
                // the current stays with you instead of racing off ahead
                Vec3 v = b.getDeltaMovement();
                double h = v.horizontalDistance();
                double want = com.mattermod.trait.RapidsCurrents.SURGE_SPEED;
                if (h > want) b.setDeltaMovement(v.multiply(want / h, 1, want / h));
                surge(b, water);
            }
        }
        if (!Traits.has(st, "hydraulics.diving_bell")) return;
        // Diving Bell: one continuous 3x3 corridor along the whole path flown this tick (the
        // shot covers more than a block per tick, so clearing only around it left gaps)
        Vec3 from = new Vec3(b.xo, b.yo, b.zo), to = b.position();
        int steps = Math.max(1, (int) Math.ceil(from.distanceTo(to) / .5));
        var done = new java.util.HashSet<BlockPos>();
        for (int i = 0; i <= steps; i++) {
            var c = BlockPos.containing(from.lerp(to, i / (double) steps));
            for (var pos : BlockPos.betweenClosed(c.offset(-1, -1, -1), c.offset(1, 1, 1))) {
                var q = pos.immutable();
                if (done.add(q)
                        && sl.hasChunkAt(q)
                        && sl.mayInteract(p, q)
                        && sl.getBlockState(q).is(Blocks.WATER))
                    WorldEffects.tempBlock(sl, q, Blocks.AIR.defaultBlockState(), 400);
            }
        }
    }

    public static void waterImpact(BeamBallEntity b, ItemStack st, Vec3 at) {
        if (!(b.getOwner() instanceof ServerPlayer p)) return;
        var sl = p.serverLevel();
        int phase = b.getPhase();
        if (Traits.has(st, "hydraulics.geyser"))
            WorldEffects.schedule(
                    sl,
                    2,
                    () -> {
                        var tile = waterNear(sl, at);
                        if (tile == null) return;
                        var center = Vec3.atCenterOf(tile).add(0, .5, 0);
                        for (int tick = 0; tick < 80; tick++) {
                            final int step = tick;
                            WorldEffects.schedule(
                                    sl,
                                    tick,
                                    () -> {
                                        if (!sl.hasChunkAt(tile)
                                                || !sl.getFluidState(tile).is(FluidTags.WATER))
                                            return;
                                        double height = 3 + phase * 2;
                                        var box =
                                                new AABB(
                                                        center.x - .65,
                                                        center.y - .8,
                                                        center.z - .65,
                                                        center.x + .65,
                                                        center.y + height,
                                                        center.z + .65);
                                        for (var e :
                                                sl.getEntities(
                                                        (Entity) null,
                                                        box,
                                                        e ->
                                                                e instanceof LivingEntity
                                                                        || e
                                                                                instanceof
                                                                                net.minecraft.world
                                                                                        .entity
                                                                                        .vehicle
                                                                                        .Boat)) {
                                            var v = e.getDeltaMovement();
                                            e.setDeltaMovement(
                                                    v.x, Math.max(v.y, .65 + phase * .3), v.z);
                                            e.hurtMarked = true;
                                            e.resetFallDistance();
                                        }
                                        if (step % 3 == 0) {
                                            sl.sendParticles(
                                                    com.mattermod.registry.ModParticles.BASE.get(),
                                                    center.x,
                                                    center.y,
                                                    center.z,
                                                    1,
                                                    0,
                                                    0,
                                                    0,
                                                    0);
                                            sl.sendParticles(
                                                    com.mattermod.registry.ModParticles.PLUME.get(),
                                                    center.x,
                                                    center.y,
                                                    center.z,
                                                    0,
                                                    0,
                                                    .25 + phase * .12,
                                                    0,
                                                    1);
                                            sl.sendParticles(
                                                    com.mattermod.registry.ModParticles.POOF.get(),
                                                    center.x,
                                                    center.y + height * .8,
                                                    center.z,
                                                    2,
                                                    .3,
                                                    .2,
                                                    .3,
                                                    .03);
                                        }
                                    });
                        }
                    });
        if (Traits.has(st, "hydraulics.rapids")) {
            var tile = waterNear(sl, at);
            if (tile != null) surge(b, tile);
        }
    }

    private static void surge(BeamBallEntity b, BlockPos tile) {
        var sl = (ServerLevel) b.level();
        Vec3 surface = Vec3.atCenterOf(tile);
        RapidsCurrents.redirect(sl, surface, b.getDeltaMovement(), 2 * (b.getPhase() + 1));
        if (b.tickCount % 4 == 0) {
            var dir = b.getDeltaMovement().multiply(1, 0, 1).normalize();
            sl.sendParticles(
                    ParticleTypes.SPLASH,
                    surface.x,
                    surface.y + .5,
                    surface.z,
                    0,
                    dir.x * .15,
                    .02,
                    dir.z * .15,
                    1);
        }
    }

    public static void lightFlight(BeamBallEntity b, ItemStack st) {
        PhotonicEffects.flight(b, st);
    }

    public static void lightBounce(BeamBallEntity b, ItemStack st, Vec3 at) {
        PhotonicEffects.bounce(b, st, at);
    }

    public static void lightEnd(BeamBallEntity b, ItemStack st, Vec3 at) {
        PhotonicEffects.end(b, st, at);
    }
}
