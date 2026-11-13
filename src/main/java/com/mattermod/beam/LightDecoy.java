package com.mattermod.beam;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.*;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.ai.attributes.*;
import net.minecraft.world.level.Level;

import java.util.*;

public final class LightDecoy extends PathfinderMob {
    private static final EntityDataAccessor<Optional<UUID>> OWNER =
            SynchedEntityData.defineId(LightDecoy.class, EntityDataSerializers.OPTIONAL_UUID);
    private int lifetime = 120;

    public LightDecoy(EntityType<? extends LightDecoy> type, Level level) {
        super(type, level);
        setNoAi(true);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder b) {
        super.defineSynchedData(b);
        b.define(OWNER, Optional.empty());
    }

    public UUID owner() {
        return entityData.get(OWNER).orElse(new UUID(0, 0));
    }

    public void configure(net.minecraft.server.level.ServerPlayer player, int ticks) {
        entityData.set(OWNER, Optional.of(player.getUUID()));
        lifetime = ticks;
        setYRot(player.getYRot());
        setYHeadRot(player.getYRot());
    }

    @Override
    public void tick() {
        super.tick();
        if (!level().isClientSide) {
            if (tickCount % 5 == 0)
                ((net.minecraft.server.level.ServerLevel) level())
                        .sendParticles(
                                net.minecraft.core.particles.ParticleTypes.END_ROD,
                                getX(),
                                getY() + 1,
                                getZ(),
                                2,
                                0.25,
                                0.5,
                                0.25,
                                0.005);
            if (--lifetime <= 0) discard();
        }
    }

    @Override
    public void addAdditionalSaveData(CompoundTag t) {
        super.addAdditionalSaveData(t);
        t.putUUID("owner", owner());
        t.putInt("life", lifetime);
    }

    @Override
    public void readAdditionalSaveData(CompoundTag t) {
        super.readAdditionalSaveData(t);
        if (t.hasUUID("owner")) entityData.set(OWNER, Optional.of(t.getUUID("owner")));
        lifetime = t.getInt("life");
    }

    public static AttributeSupplier.Builder attributes() {
        return Mob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 6)
                .add(Attributes.MOVEMENT_SPEED, 0);
    }

    @Override
    public boolean hurt(net.minecraft.world.damagesource.DamageSource source, float amount) {
        return false;
    }

    @Override
    public boolean isPushable() {
        return false;
    }
}
