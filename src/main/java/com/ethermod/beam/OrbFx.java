package com.ethermod.beam;

import com.ethermod.item.ManipulatorStage;
import com.ethermod.item.OrbMode;
import com.ethermod.manipulator.Manipulator;
import com.ethermod.registry.ModEntities;
import com.ethermod.trait.Traits;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SimpleWaterloggedBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * The burst every orb makes where it lands: particles and a sound per mode, seen by everyone
 * nearby. Split out of {@link BeamBallEntity}; every method takes the orb as {@code b}.
 */
final class OrbFx {

    private OrbFx() {}

    /** Impact burst, visible to everyone nearby. */
    static void impact(BeamBallEntity b, Vec3 pos) {
        if (!(b.level() instanceof ServerLevel sl)) return;
        int ph = b.getPhase();

        switch (b.getMode()) {
            case ENERGY -> {
                sl.sendParticles(ParticleTypes.FLASH, pos.x, pos.y, pos.z, 1, 0, 0, 0, 0);
                sl.sendParticles(
                        ParticleTypes.ELECTRIC_SPARK,
                        pos.x,
                        pos.y,
                        pos.z,
                        12 + 10 * ph,
                        0.2,
                        0.2,
                        0.2,
                        0.5 + 0.3 * ph);
                sl.sendParticles(
                        ParticleTypes.END_ROD,
                        pos.x,
                        pos.y,
                        pos.z,
                        4 + 4 * ph,
                        0.1,
                        0.1,
                        0.1,
                        0.12);
                sl.playSound(
                        null,
                        pos.x,
                        pos.y,
                        pos.z,
                        com.ethermod.sound.ModSounds.WORLD_AMETHYST_CLUSTER_BREAK,
                        SoundSource.PLAYERS,
                        0.7f + 0.2f * ph,
                        1.6f - 0.25f * ph);
            }
            case WATER -> {
                sl.sendParticles(
                        ParticleTypes.SPLASH,
                        pos.x,
                        pos.y,
                        pos.z,
                        30 + 15 * ph,
                        0.4,
                        0.3,
                        0.4,
                        0.2);
                sl.sendParticles(
                        ParticleTypes.BUBBLE_POP, pos.x, pos.y, pos.z, 10, 0.3, 0.3, 0.3, 0.05);
                sl.playSound(
                        null,
                        pos.x,
                        pos.y,
                        pos.z,
                        com.ethermod.sound.ModSounds.WORLD_PLAYER_SPLASH,
                        SoundSource.PLAYERS,
                        0.22f,
                        1.8f);
                // a cup-sized splash (FX bench)
                sl.playSound(null, pos.x, pos.y, pos.z, com.ethermod.sound.ModSounds.WORLD_BUCKET_EMPTY, SoundSource.PLAYERS, .25f, 1.7f);
            }
            case LAVA -> {
                sl.sendParticles(
                        ParticleTypes.LAVA, pos.x, pos.y, pos.z, 10 + 6 * ph, 0.3, 0.2, 0.3, 0.1);
                sl.sendParticles(
                        ParticleTypes.LARGE_SMOKE, pos.x, pos.y, pos.z, 6, 0.3, 0.3, 0.3, 0.02);
                sl.playSound(
                        null,
                        pos.x,
                        pos.y,
                        pos.z,
                        com.ethermod.sound.ModSounds.WORLD_LAVA_EXTINGUISH,
                        SoundSource.PLAYERS,
                        0.5f,
                        1.2f);
            }
            case FROST -> {
                sl.sendParticles(
                        ParticleTypes.SNOWFLAKE,
                        pos.x,
                        pos.y,
                        pos.z,
                        25 + 15 * ph,
                        0.5,
                        0.4,
                        0.5,
                        0.05);
                sl.sendParticles(
                        ParticleTypes.ITEM_SNOWBALL, pos.x, pos.y, pos.z, 10, 0.3, 0.3, 0.3, 0.1);
                sl.playSound(
                        null,
                        pos.x,
                        pos.y,
                        pos.z,
                        com.ethermod.sound.ModSounds.WORLD_GLASS_BREAK,
                        SoundSource.PLAYERS,
                        0.7f,
                        1.5f);
            }
            case IGNITE -> {
                sl.sendParticles(
                        b.isSoulFire() ? ParticleTypes.SOUL_FIRE_FLAME : ParticleTypes.FLAME,
                        pos.x,
                        pos.y,
                        pos.z,
                        16 + 8 * ph,
                        0.3,
                        0.3,
                        0.3,
                        0.04);
                sl.sendParticles(ParticleTypes.SMOKE, pos.x, pos.y, pos.z, 8, 0.2, 0.2, 0.2, 0.02);
                sl.playSound(
                        null,
                        pos.x,
                        pos.y,
                        pos.z,
                        com.ethermod.sound.ModSounds.WORLD_FIRECHARGE_USE,
                        SoundSource.PLAYERS,
                        0.6f,
                        1.3f);
            }
            case GROWTH -> {
                sl.sendParticles(
                        ParticleTypes.HAPPY_VILLAGER,
                        pos.x,
                        pos.y,
                        pos.z,
                        15 + 10 * ph,
                        0.6,
                        0.4,
                        0.6,
                        0.05);
                sl.sendParticles(
                        ParticleTypes.CHERRY_LEAVES,
                        pos.x,
                        pos.y + 0.5,
                        pos.z,
                        8,
                        0.5,
                        0.3,
                        0.5,
                        0.02);
                sl.playSound(
                        null,
                        pos.x,
                        pos.y,
                        pos.z,
                        com.ethermod.sound.ModSounds.WORLD_BONE_MEAL_USE,
                        SoundSource.PLAYERS,
                        0.8f,
                        1.2f);
            }
            case LIGHT -> {
                sl.sendParticles(ParticleTypes.FLASH, pos.x, pos.y, pos.z, 1, 0, 0, 0, 0);
                sl.sendParticles(
                        ParticleTypes.END_ROD,
                        pos.x,
                        pos.y,
                        pos.z,
                        10 + 6 * ph,
                        0.3,
                        0.3,
                        0.3,
                        0.08);
                sl.playSound(
                        null,
                        pos.x,
                        pos.y,
                        pos.z,
                        com.ethermod.sound.ModSounds.WORLD_AMETHYST_BLOCK_CHIME,
                        SoundSource.PLAYERS,
                        1f,
                        1.6f);
            }
            case TETHER -> {
                sl.sendParticles(ParticleTypes.WITCH, pos.x, pos.y, pos.z, 12, 0.3, 0.3, 0.3, 0.05);
            }
            case MASS -> {
                BlockState c = b.getCarried();
                if (!c.isAir())
                    sl.sendParticles(
                            new net.minecraft.core.particles.BlockParticleOption(
                                    ParticleTypes.BLOCK, c),
                            pos.x,
                            pos.y,
                            pos.z,
                            20,
                            0.3,
                            0.3,
                            0.3,
                            0.1);
            }
            default -> {}
        }
    }
}
