package com.ethermod.entity;

import com.ethermod.trait.RedesignEffects;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.*;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.ai.attributes.*;
import net.minecraft.world.entity.ai.control.FlyingMoveControl;
import net.minecraft.world.entity.ai.navigation.*;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/** Temporary Soulwake companion; its summoner owns every burn it inflicts. */
public final class SoulBlazeling extends FlyingMob {
    private static final net.minecraft.network.syncher.EntityDataAccessor<Boolean> CHARGING =
            net.minecraft.network.syncher.SynchedEntityData.defineId(
                    SoulBlazeling.class,
                    net.minecraft.network.syncher.EntityDataSerializers.BOOLEAN);
    public static java.util.function.Consumer<SoulBlazeling> smokeEmitter = pet -> {};
    private int shotCooldown = 60;
    public static final double HOVER_HEIGHT = .8, CHARGE_RISE = .8;

    private double normalHoverY(ServerPlayer owner) {
        var from = position().add(0, .25, 0);
        var hit =
                level().clip(
                                new net.minecraft.world.level.ClipContext(
                                        from,
                                        from.add(0, -6, 0),
                                        net.minecraft.world.level.ClipContext.Block.COLLIDER,
                                        net.minecraft.world.level.ClipContext.Fluid.NONE,
                                        this));
        return hit.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK
                ? hit.getLocation().y + HOVER_HEIGHT
                : owner.getY() + HOVER_HEIGHT;
    }

    @Override
    protected void defineSynchedData(net.minecraft.network.syncher.SynchedEntityData.Builder b) {
        super.defineSynchedData(b);
        b.define(CHARGING, false);
    }

    @Override
    public boolean isOnFire() {
        return entityData.get(CHARGING);
    }

    @Override
    public boolean displayFireAnimation() {
        return isAlive() && isOnFire();
    }

    public SoulBlazeling(EntityType<? extends SoulBlazeling> type, Level level) {
        super(type, level);
        moveControl = new FlyingMoveControl(this, 20, true);
        setNoGravity(true);
        setPersistenceRequired();
        getPersistentData().putBoolean("mm_soul", true);
        xpReward = 0;
    }

    public static AttributeSupplier.Builder attributes() {
        return Mob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 8)
                .add(Attributes.FLYING_SPEED, .35)
                .add(Attributes.MOVEMENT_SPEED, .25)
                .add(Attributes.FOLLOW_RANGE, 24);
    }

    @Override
    protected PathNavigation createNavigation(Level level) {
        var nav = new FlyingPathNavigation(this, level);
        nav.setCanFloat(true);
        nav.setCanOpenDoors(false);
        nav.setCanPassDoors(true);
        return nav;
    }

    public ServerPlayer summoner() {
        return level().getServer() != null && getPersistentData().hasUUID("mm_summoner")
                ? level().getServer()
                        .getPlayerList()
                        .getPlayer(getPersistentData().getUUID("mm_summoner"))
                : null;
    }

    public void configure(ServerPlayer owner) {
        getPersistentData().putUUID("mm_summoner", owner.getUUID());
        getPersistentData()
                .putLong(
                        "mm_summon_expires",
                        level().getGameTime()
                                + 600);
    }

    public boolean validTarget(ServerPlayer owner, LivingEntity e) {
        return e != null
                && !e.getPersistentData().hasUUID("mm_summoner")
                && RedesignEffects.enemy(owner, e);
    }

    @Override
    public void tick() {
        super.tick();
        if (level().isClientSide) {
            if (isAlive()) smokeEmitter.accept(this);
            return;
        }
        if (!isAlive()) return;
        if (getPersistentData().hasUUID("mm_summoner")
                && level().getGameTime() >= getPersistentData().getLong("mm_summon_expires")) {
            hurt(damageSources().genericKill(), Float.MAX_VALUE);
            return;
        }
        var owner = summoner();
        if (owner == null || !owner.isAlive() || owner.level() != level()) {
            entityData.set(CHARGING, false);
            setTarget(null);
            getNavigation().stop();
            return;
        }
        if (tickCount % 5 == 0) {
            LivingEntity chosen = owner.getLastHurtMob();
            if (!validTarget(owner, chosen) || distanceToSqr(chosen) > 24 * 24)
                chosen = owner.getLastHurtByMob();
            if (!validTarget(owner, chosen) || distanceToSqr(chosen) > 24 * 24)
                chosen = getTarget();
            if (!validTarget(owner, chosen) || distanceToSqr(chosen) > 24 * 24) {
                chosen = null;
                double best = 24 * 24;
                for (var e :
                        level().getEntitiesOfClass(
                                        LivingEntity.class,
                                        getBoundingBox().inflate(20),
                                        e -> e instanceof Enemy && validTarget(owner, e))) {
                    double d = distanceToSqr(e);
                    if (d < best) {
                        best = d;
                        chosen = e;
                    }
                }
            }
            setTarget(chosen);
        }
        var target = getTarget();
        boolean firingPosition =
                validTarget(owner, target)
                        && distanceToSqr(target) <= 18 * 18
                        && getSensing().hasLineOfSight(target);
        if (firingPosition) {
            if (shotCooldown > 0) shotCooldown--;
        } else shotCooldown = Math.max(60, shotCooldown);
        boolean charging = firingPosition && shotCooldown <= 60;
        entityData.set(CHARGING, charging);
        if (validTarget(owner, target)) {
            getLookControl().setLookAt(target, 30, 30);
            if (!charging && (distanceToSqr(target) > 64 || !getSensing().hasLineOfSight(target))) {
                if (tickCount % 10 == 0)
                    getNavigation()
                            .moveTo(
                                    target.getX(),
                                    target.getY() + target.getBbHeight() * .5,
                                    target.getZ(),
                                    1);
            } else {
                getNavigation().stop();
                setDeltaMovement(getDeltaMovement().scale(.8));
            }
            if (shotCooldown <= 0 && firingPosition) {
                fireAt(target);
                shotCooldown = 160;
            }
        } else if (distanceToSqr(owner) > 9) {
            if (tickCount % 10 == 0)
                getNavigation().moveTo(owner.getX(), owner.getY() + 1, owner.getZ(), 1);
        } else {
            getNavigation().stop();
            setDeltaMovement(getDeltaMovement().scale(.8));
        }
        // Hover at the former firing height even while idle; charging adds a separate rise.
        double wanted = normalHoverY(owner) + (charging ? CHARGE_RISE : 0);
        var path = getNavigation().getPath();
        if (!charging && path != null && !path.isDone())
            wanted = Math.max(wanted, path.getNextEntityPos(this).y);
        var v = getDeltaMovement();
        double speed = Math.clamp((wanted - getY()) * .3, -.18, .3);
        setDeltaMovement(v.x, v.y + (speed - v.y) * .3, v.z);
    }

    public void fireAt(LivingEntity target) {
        if (level().isClientSide) return;
        var bolt = com.ethermod.registry.ModEntities.SOUL_BOLT.get().create(level());
        if (bolt == null) return;
        bolt.setOwner(this);
        bolt.setPos(getX(), getY() + getBbHeight() * .6, getZ());
        Vec3 aim = target.getBoundingBox().getCenter().subtract(bolt.position());
        bolt.setDeltaMovement(aim.normalize().scale(.1));
        level().addFreshEntity(bolt);
        entityData.set(CHARGING, false);
        if (!isSilent()) level().levelEvent(null, 1018, blockPosition(), 0);
    }

    @Override
    public boolean canAttack(LivingEntity e) {
        var owner = summoner();
        return owner != null && validTarget(owner, e) && super.canAttack(e);
    }

    @Override
    public boolean removeWhenFarAway(double distance) {
        return false;
    }

    @Override
    protected SoundEvent getAmbientSound() {
        return SoundEvents.BLAZE_AMBIENT;
    }

    @Override
    protected SoundEvent getHurtSound(net.minecraft.world.damagesource.DamageSource source) {
        return SoundEvents.BLAZE_HURT;
    }

    @Override
    protected SoundEvent getDeathSound() {
        return SoundEvents.BLAZE_DEATH;
    }

    @Override
    protected float getSoundVolume() {
        return .25f;
    }

    @Override
    public float getVoicePitch() {
        return 1.6f;
    }

    @Override
    public void addAdditionalSaveData(CompoundTag t) {
        super.addAdditionalSaveData(t);
        t.putInt("shot_cooldown", shotCooldown);
    }

    @Override
    public void readAdditionalSaveData(CompoundTag t) {
        super.readAdditionalSaveData(t);
        shotCooldown = t.contains("shot_cooldown") ? t.getInt("shot_cooldown") : 60;
        setNoGravity(true);
    }
}
