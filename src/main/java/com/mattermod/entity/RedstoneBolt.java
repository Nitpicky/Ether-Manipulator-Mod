package com.mattermod.entity;

import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.ThrowableItemProjectile;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import org.joml.Vector3f;

/** Dispenser Arm shot: a straight redstone bolt, 6 damage, gone after about 24 blocks. */
public final class RedstoneBolt extends ThrowableItemProjectile {
    private static final DustParticleOptions DUST = new DustParticleOptions(new Vector3f(1, .08f, .03f), 1.2f);
    private ServerPlayer player;
    /** Steers toward this one while it lives (server side). */
    private LivingEntity homing;

    /** Share of the bolt damage (Split fans one shot into three). */
    private float share = 1;
    /** Bank Shot: block bounces left. */
    private int bouncesLeft;

    void home(LivingEntity target) {
        homing = target;
    }

    void share(float s) {
        share = s;
    }

    void bounces(int n) {
        bouncesLeft = n;
    }

    public RedstoneBolt(EntityType<? extends RedstoneBolt> type, Level level) {
        super(type, level);
    }

    public RedstoneBolt(Level level, RedstoneGolem golem, ServerPlayer player) {
        super(com.mattermod.registry.ModEntities.REDSTONE_BOLT.get(), golem, level);
        this.player = player;
    }

    @Override
    protected Item getDefaultItem() {
        return Items.REDSTONE;
    }

    @Override
    protected double getDefaultGravity() {
        return 0;
    }

    @Override
    public void tick() {
        super.tick();
        if (level().isClientSide) {
            level().addParticle(DUST, getX(), getY(), getZ(), 0, 0, 0);
            return;
        }
        if (homing != null && homing.isAlive() && !homing.isRemoved()) {
            // homing: turns most of the way toward the target every tick, at the same speed
            Vec3 v = getDeltaMovement();
            double speed = Math.max(1.2, v.length());
            Vec3 want = homing.getBoundingBox().getCenter().subtract(position()).normalize();
            setDeltaMovement(v.normalize().scale(.45).add(want.scale(.55)).normalize().scale(speed));
            hasImpulse = true;
        }
        if (tickCount > (homing != null ? 30 : 16)) {
            com.mattermod.MatterMod.LOGGER.debug("redstone bolt expired at {}", position());
            discard();
        }
    }

    @Override
    protected boolean canHitEntity(Entity e) {
        if (!super.canHitEntity(e)) return false;
        if (getOwner() instanceof RedstoneGolem g)
            return e instanceof LivingEntity l && g.canHit(player, l);
        return true;
    }

    @Override
    protected void onHitEntity(EntityHitResult hit) {
        super.onHitEntity(hit);
        LivingEntity shooter = getOwner() instanceof LivingEntity l ? l : null;
        if (hit.getEntity().hurt(damageSources().mobProjectile(this, shooter), (getOwner() instanceof RedstoneGolem gg ? gg.boltDamage(player) : RedstoneGolem.BOLT_DAMAGE) * share)
                && getOwner() instanceof RedstoneGolem g
                && hit.getEntity() instanceof LivingEntity l) g.boltHit(player, l);
    }

    @Override
    protected void onHitBlock(net.minecraft.world.phys.BlockHitResult hit) {
        if (bouncesLeft > 0 && !level().isClientSide) {
            // Bank Shot: off the face like a mirror, with its range renewed
            bouncesLeft--;
            Vec3 n = Vec3.atLowerCornerOf(hit.getDirection().getNormal());
            Vec3 v = getDeltaMovement();
            setDeltaMovement(v.subtract(n.scale(2 * v.dot(n))));
            Vec3 at = hit.getLocation().add(n.scale(.05));
            setPos(at.x, at.y, at.z);
            tickCount = 0;
            hasImpulse = true;
            ((ServerLevel) level()).sendParticles(DUST, getX(), getY(), getZ(), 4, .1, .1, .1, 0);
            return;
        }
        super.onHitBlock(hit);
    }

    @Override
    protected void onHit(HitResult result) {
        if (result.getType() == HitResult.Type.BLOCK && bouncesLeft > 0 && !level().isClientSide) {
            onHitBlock((net.minecraft.world.phys.BlockHitResult) result);
            return;
        }
        super.onHit(result);
        if (level() instanceof ServerLevel sl) {
            com.mattermod.MatterMod.LOGGER.debug("redstone bolt hit {} at {} after {} ticks", result instanceof EntityHitResult eh ? eh.getEntity().getType().toShortString() : result.getType(), result.getLocation(), tickCount);
            sl.sendParticles(DUST, getX(), getY(), getZ(), 10, .2, .2, .2, 0);
            sl.playSound(null, getX(), getY(), getZ(), com.mattermod.registry.ModSounds.GOLEM_SPARK_HIT.get(),
                    net.minecraft.sounds.SoundSource.NEUTRAL, 1, 1);
            discard();
        }
    }
}
