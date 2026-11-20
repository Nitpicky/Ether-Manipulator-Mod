package com.ethermod.beam;

import com.ethermod.util.FoliageHelper;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Pyrotechnic and Gust shots tear through foliage (leaves, grass, flowers...) on their way: fire
 * burns it away, wind cuts it loose with its drops. Every other shot and the beam pass through it
 * and leave it standing.
 */
final class FoliageCut {

    private FoliageCut() {}

    /** Clears the foliage around a shot's move from {@code from} to {@code to}. */
    static void sweep(ServerLevel sl, ServerPlayer owner, Vec3 from, Vec3 to, boolean burn) {
        AABB swept = new AABB(from, to).inflate(0.45);
        for (BlockPos bp :
                BlockPos.betweenClosed(
                        BlockPos.containing(swept.minX, swept.minY, swept.minZ),
                        BlockPos.containing(swept.maxX, swept.maxY, swept.maxZ))) {
            var bs = sl.getBlockState(bp);
            if (!FoliageHelper.isPlant(bs)) continue;
            BlockPos at = bp.immutable();
            // breakAsPlayer posts the real break event claims answer; only the burn, which
            // removes the block itself, needs the probe first
            if (owner != null
                    && !(burn
                            ? com.ethermod.util.Protect.may(sl, owner, at)
                            : com.ethermod.util.Protect.mayInteract(sl, owner, at)))
                continue;
            if (burn) {
                sl.destroyBlock(at, false, owner);
                sl.sendParticles(ParticleTypes.FLAME, at.getX() + .5, at.getY() + .5, at.getZ() + .5, 4, .25, .25, .25, .01);
                sl.sendParticles(ParticleTypes.SMOKE, at.getX() + .5, at.getY() + .6, at.getZ() + .5, 2, .2, .2, .2, .01);
                sl.playSound(null, at, com.ethermod.sound.ModSounds.WORLD_FIRE_EXTINGUISH, SoundSource.BLOCKS, .25f, 1.6f);
            } else if (owner != null) MiningDamageTracker.breakAsPlayer(owner, at);
            else sl.destroyBlock(at, true);
        }
    }
}
