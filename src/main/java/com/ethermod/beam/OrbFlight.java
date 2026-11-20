package com.ethermod.beam;

import com.ethermod.item.ManipulatorStage;
import com.ethermod.item.OrbMode;
import com.ethermod.manipulator.Manipulator;
import com.ethermod.registry.ModEntities;
import com.ethermod.trait.Traits;
import com.ethermod.trait.WorldEffects;

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
 * Launch-time traits and in-flight steering of an orb: what the Orb line sets when it is fired
 * (Gust, Marksman, Seeker, Bank Shot, Prism), its gravity, Seeker's homing and Tidecaller's ride.
 * Split out of {@link BeamBallEntity}; every method takes the orb as {@code b}.
 */
final class OrbFlight {

    private OrbFlight() {}

    /** Applies the Orb line's launch-time traits. Call before spawning. */
    static void configure(BeamBallEntity b, ItemStack manipulator) {
        b.getEntityData().set(BeamBallEntity.COLOR, b.getMode().color);
        b.traits = manipulator.copy();
        b.getEntityData().set(BeamBallEntity.SOULFIRE, Traits.has(manipulator, "pyrotechnic.soulwake"));
        b.zipPlacing =
                b.getMode() == OrbMode.TETHER
                        && b.has("gravitic.zipline")
                        && b.getOwner() != null
                        && b.getOwner().isShiftKeyDown();
        if (Traits.hasCommon(b.traits, Traits.KINETIC, 1)) b.maxRange += 16;
        if (b.getMode() == OrbMode.GUST) {
            b.maxRange = 32;
            // Cyclone: a charged Gust becomes a slow whirlwind
            if (b.getPhase() == 2 && Traits.has(b.traits, "pneumatic.cyclone"))
                b.setDeltaMovement(b.getDeltaMovement().normalize().scale(0.22));
        }
        if (b.getMode() == OrbMode.MASS) b.maxRange = 64;
        // Bank Shot: every mode bounces (not the hook, nor a drifting pollen cloud)
        int bank = Traits.rank(b.traits, "kinetic.bank_shot");
        if (b.getMode() != OrbMode.TETHER && !b.isDrifting())
            b.bouncesLeft = bank > 0 ? b.getPhase() + 1 : 0;
        if (b.has("kinetic.marksman")
                && b.getOwner() != null
                && b.getOwner().getPersistentData().getInt("mm_charge_still") >= 10)
            b.setDeltaMovement(b.getDeltaMovement().scale(1.6));
        if (b.has("kinetic.seeker") && b.getOwner() instanceof ServerPlayer p) {
            var look = p.getLookAngle();
            var eye = p.getEyePosition();
            ((ServerLevel) b.level())
                            .getEntitiesOfClass(
                                    LivingEntity.class,
                                    p.getBoundingBox()
                                            .expandTowards(look.scale(b.maxRange))
                                            .inflate(4),
                                    e ->
                                            !com.ethermod.beam.Mounts.own(p, e)
                                                    && e.isAlive()
                                                    && !com.ethermod.trait.Targeting.ally(
                                                            p, e)
                                                    && p.hasLineOfSight(e)
                                                    && e.getBoundingBox()
                                                                    .getCenter()
                                                                    .subtract(eye)
                                                                    .normalize()
                                                                    .dot(look)
                                                            > .978)
                            .stream()
                            .min(
                                    java.util.Comparator.comparingDouble(
                                            e ->
                                                    1
                                                            - e.getBoundingBox()
                                                                    .getCenter()
                                                                    .subtract(eye)
                                                                    .normalize()
                                                                    .dot(look)))
                            .ifPresent(e -> b.seekerTarget = e.getUUID());
        }
        // Light: a Moira-style ball that bounces 2, then 4 times when charged; Prism adds 2;
        // Bank Shot one more than other modes
        if (b.getMode() == OrbMode.LIGHT) {
            int prism = Traits.rank(b.traits, "photonic.prism");
            b.bouncesLeft =
                    (b.getPhase() == 0 ? 0 : b.getPhase() == 1 ? 2 : 4)
                            + 2 * prism
                            + (bank > 0 ? bank + 1 : 0);
        }
    }

    /** Gravity this tick: Slipstream floats liquid orbs in water, Tidecaller rides flatter. */
    static float gravity(BeamBallEntity b) {
        OrbMode mode = b.getMode();
        if (mode == OrbMode.WATER
                && b.isInWater()
                && !b.traits.isEmpty()
                && Traits.hasCommon(b.traits, Traits.HYDRAULIC, 1)) return 0f;
        if (b.carrying) return mode.gravity() * 0.35f;
        return mode.gravity();
    }

    /** Tidecaller: pulls the owner along just behind the orb. */
    static void carry(BeamBallEntity b) {
        if (!(b.getOwner() instanceof ServerPlayer sp) || !sp.isAlive() || sp.level() != b.level()) {
            b.carrying = false;
            return;
        }
        Vec3 v = b.getDeltaMovement();
        Vec3 spot =
                b.position()
                        .subtract(v.lengthSqr() > 1e-4 ? v.normalize().scale(2.2) : Vec3.ZERO)
                        .subtract(0, sp.getBbHeight() * 0.5, 0);
        Vec3 want = spot.subtract(sp.position());
        Vec3 nv = want.scale(0.5);
        if (nv.length() > 2.2) nv = nv.normalize().scale(2.2);
        sp.setDeltaMovement(nv);
        sp.hurtMarked = true;
        // the trident's riptide spin, kept going for as long as the shot carries you
        if (!sp.isAutoSpinAttack() || b.tickCount % 4 == 0) sp.startAutoSpinAttack(6, 6f, b.traits);
        sp.resetFallDistance();
        com.ethermod.trait.MobilityEffects.markSoftLanding(sp);
    }

    /** Seeker: bends the flight toward the creature picked at launch. */
    static void seek(BeamBallEntity b) {
        ServerLevel sl = (ServerLevel) b.level();
        BlockPos here = b.blockPosition();

        if (b.seekerTarget != null
                && b.getMode() != OrbMode.TETHER
                && b.getMode() != OrbMode.MASS
                && sl.getEntity(b.seekerTarget) instanceof LivingEntity target
                && target.isAlive()) {
            Vec3 v = b.getDeltaMovement();
            Vec3 wish = target.getBoundingBox().getCenter().subtract(b.position()).normalize();
            b.setDeltaMovement(
                    v.normalize().scale(.85).add(wish.scale(.15)).normalize().scale(v.length()));
        }
    }
}
