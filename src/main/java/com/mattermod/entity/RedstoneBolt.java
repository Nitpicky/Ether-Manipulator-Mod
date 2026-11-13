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

import org.joml.Vector3f;

/** Dispenser Arm shot: a straight redstone bolt, 6 damage, gone after about 24 blocks. */
public final class RedstoneBolt extends ThrowableItemProjectile {
    private static final DustParticleOptions DUST = new DustParticleOptions(new Vector3f(1, .08f, .03f), 1.2f);
    private ServerPlayer player;

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
        if (tickCount > 16) {
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
        if (hit.getEntity().hurt(damageSources().mobProjectile(this, shooter), RedstoneGolem.BOLT_DAMAGE)
                && getOwner() instanceof RedstoneGolem g
                && hit.getEntity() instanceof LivingEntity l) g.boltHit(player, l);
    }

    @Override
    protected void onHit(HitResult result) {
        super.onHit(result);
        if (level() instanceof ServerLevel sl) {
            com.mattermod.MatterMod.LOGGER.debug("redstone bolt hit {} at {} after {} ticks", result instanceof EntityHitResult eh ? eh.getEntity().getType().toShortString() : result.getType(), result.getLocation(), tickCount);
            sl.sendParticles(DUST, getX(), getY(), getZ(), 10, .2, .2, .2, 0);
            discard();
        }
    }
}
