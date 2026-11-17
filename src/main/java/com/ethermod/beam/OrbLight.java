package com.ethermod.beam;

import com.ethermod.trait.WorldEffects;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Light mode: bounce lights and their extras. Split out of {@link BeamBallEntity}; every method
 * takes the orb as {@code b}.
 */
final class OrbLight {

    private OrbLight() {}

    /**
     * Light orb: sticks as an invisible light that fades after 3 minutes (6 with Prism II). A tap
     * on an existing Manipulator light removes it instead. Flare: nearby mobs glow.
     */
    static void lightImpact(BeamBallEntity b, BlockPos at, BlockPos hitBlock) {
        ServerLevel sl = (ServerLevel) b.level();
        if (b.has("photonic.flare")) {
            for (LivingEntity e :
                    sl.getEntitiesOfClass(
                            LivingEntity.class,
                            new AABB(at).inflate(12 + 8 * (b.rank("photonic.flare") - 1)),
                            e ->
                                    e != b.getOwner()
                                            && !(e
                                                    instanceof
                                                    net.minecraft.world.entity.player.Player))) {
                e.addEffect(
                        new net.minecraft.world.effect.MobEffectInstance(
                                net.minecraft.world.effect.MobEffects.GLOWING, 200));
            }
        }
        // a plain(tap) Light orb on an existing light takes it away; bounced orbs never do
        if (b.getPhase() == 0)
            for (BlockPos p : BlockPos.betweenClosed(at.offset(-1, -1, -1), at.offset(1, 1, 1))) {
                if (sl.getBlockState(p).is(Blocks.LIGHT) && b.mayBuild(p)) {
                    sl.removeBlock(p, false);
                    WorldEffects.forgetLight(sl, p);
                    if (b.getOwner() instanceof ServerPlayer sp)
                        com.ethermod.trait.BeaconNet.remove(sp, sl, p.immutable());
                    return;
                }
            }
        if (!sl.getBlockState(at).isAir() || !b.mayBuild(at)) return;
        WorldEffects.tempLight(sl, at, 12 + b.getPhase(), b.lightLife);
        if (b.getOwner() instanceof ServerPlayer sp)
            com.ethermod.trait.BeaconNet.add(sp, sl, at, b.lightLife);
        sl.sendParticles(
                ParticleTypes.END_ROD,
                at.getX() + 0.5,
                at.getY() + 0.5,
                at.getZ() + 0.5,
                12,
                0.2,
                0.2,
                0.2,
                0.05);
    }

    /** Dazzle and Sunburst, wherever a Light orb lands. */
    static void lightExtras(BeamBallEntity b, Vec3 at) {
        ProjectileEffects.lightEnd(b, b.traits, at);
    }
}
