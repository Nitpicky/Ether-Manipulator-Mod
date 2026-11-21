package com.ethermod.beam;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Mass Driver mode: steering, impact damage, placing and dropping the carried block group. Split
 * out of {@link BeamBallEntity}; every method takes the orb as {@code b}.
 */
final class OrbMass {

    private OrbMass() {}

    /** Hardness makes it hurt: dirt barely, obsidian a lot. */
    static float massDamage(BeamBallEntity b) {
        BlockState c = b.getCarried();
        float hard = c.getDestroySpeed(b.level(), b.blockPosition());
        float base = Math.max(2f, Math.min(20f, 2f + hard * 1.6f));
        return base * (0.6f + 0.2f * b.getPhase());
    }

    static void massHitEntity(BeamBallEntity b, Entity target, Vec3 at) {
        // one hit per creature per throw
        long now = b.level().getGameTime();
        if (com.ethermod.util.MobMemory.peek(target).getLongOr("mm_mass_hit", 0L) > now) return;
        com.ethermod.util.MobMemory.of(target).putLong("mm_mass_hit", now + 10);
        Vec3 v = b.getDeltaMovement();
        if (b.massGentle) {
            // Steady Hand doesn't fight: the block just drops off the creature
            b.setDeltaMovement(v.scale(-.1));
            return;
        }
        Entity owner = b.getOwner();
        boolean self = target == owner;
        // your own block coming back at you hurts, but only half as much
        target.hurt(
                b.damageSources().thrown(b, owner instanceof LivingEntity le ? le : b),
                OrbMass.massDamage(b) * (self ? .5f : 1f));
        if (self && b.massBounces == 0 && owner instanceof net.minecraft.server.level.ServerPlayer sp)
            com.ethermod.advancement.ManipulatorTrigger.fire(sp, "mass_self");
        Vec3 push = v.normalize().scale(0.4 + 0.2 * b.getPhase());
        target.setDeltaMovement(target.getDeltaMovement().add(push.x, 0.2, push.z));
        com.ethermod.util.MotionSync.sync(target);
        // recoil: the block bounces back off the creature (and up), then falls and lands
        b.setDeltaMovement(v.x * -.35, Math.max(.25, -v.y * .3), v.z * -.35);
        com.ethermod.util.MotionSync.sync(b);
        if (b.massBounces > 0) b.massBounces--;
        hitSound(b, at);
    }

    /** Off a block face: the push into the face turns around, the slide along it slows. */
    static void bounce(BeamBallEntity b, net.minecraft.core.Direction face, Vec3 at) {
        Vec3 v = b.getDeltaMovement();
        Vec3 n = new Vec3(face.getStepX(), face.getStepY(), face.getStepZ());
        Vec3 into = n.scale(v.dot(n));
        Vec3 along = v.subtract(into);
        b.setDeltaMovement(along.scale(.6).subtract(into.scale(.4)));
        b.setPos(at.add(n.scale(.5)));
        com.ethermod.util.MotionSync.sync(b);
        hitSound(b, at);
    }

    private static void hitSound(BeamBallEntity b, Vec3 at) {
        var sound = b.getCarried().getSoundType();
        b.level()
                .playSound(
                        null,
                        at.x,
                        at.y,
                        at.z,
                        sound.getHitSound(),
                        net.minecraft.sounds.SoundSource.PLAYERS,
                        1f,
                        sound.getPitch() * .7f);
    }

    /** On a block: it places itself on the face it hit, if it can; otherwise it drops. */
    static void massPlace(BeamBallEntity b, BlockPos hitPos, Direction face, Vec3 at) {
        ServerLevel sl = (ServerLevel) b.level();
        BlockState c = b.getCarried();
        BlockPos front = hitPos.relative(face);
        if (!c.isAir()
                && sl.getBlockState(front).canBeReplaced()
                && c.canSurvive(sl, front)
                && sl.isUnobstructed(
                        c, front, net.minecraft.world.phys.shapes.CollisionContext.empty())
                && b.mayBuild(front)) { // the claim probe last, once the block would really go here
            sl.setBlockAndUpdate(front, c);
            restoreBe(sl, front, b);
            var sound = c.getSoundType();
            sl.playSound(
                    null,
                    front,
                    sound.getPlaceSound(),
                    SoundSource.BLOCKS,
                    1f,
                    sound.getPitch() * 0.8f);
            b.setCarried(Blocks.AIR.defaultBlockState());
            return;
        }
        OrbMass.dropCarried(b, at);
    }

    /** Puts a lifted container's contents back into the block just placed. */
    static void restoreBe(ServerLevel sl, BlockPos at, BeamBallEntity b) {
        if (b.carriedBe == null) return;
        var be = sl.getBlockEntity(at);
        if (be != null) {
            be.loadWithComponents(net.minecraft.world.level.storage.TagValueInput.create(net.minecraft.util.ProblemReporter.DISCARDING, sl.registryAccess(), b.carriedBe));
            be.setChanged();
        }
        b.carriedBe = null;
    }

    static void dropCarried(BeamBallEntity b, Vec3 at) {
        BlockState c = b.getCarried();
        if (c.isAir()) return;
        if (b.carriedBe != null && b.level() instanceof ServerLevel sl) {
            BlockPos pos = BlockPos.containing(at);
            var be = net.minecraft.world.level.block.entity.BlockEntity.loadStatic(pos, c, b.carriedBe, sl.registryAccess());
            if (be instanceof net.minecraft.world.Container box)
                net.minecraft.world.Containers.dropContents(sl, pos, box);
            b.carriedBe = null;
        }
        ItemStack drop = new ItemStack(c.getBlock().asItem());
        if (!drop.isEmpty()) {
            ItemEntity ie = new ItemEntity(b.level(), at.x, at.y, at.z, drop);
            ie.setDefaultPickUpDelay();
            b.level().addFreshEntity(ie);
        }
        b.setCarried(Blocks.AIR.defaultBlockState());
    }
}
