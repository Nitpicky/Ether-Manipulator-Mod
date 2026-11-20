package com.ethermod.entity;

import net.minecraft.core.particles.*;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.projectile.SmallFireball;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.*;

/** Vanilla small-fireball flight/impact; owned soulfire and friendly/terrain safety. */
public final class SoulBolt extends SmallFireball {
    public SoulBolt(EntityType<? extends SoulBolt> type, Level level) {
        super(type, level);
        getPersistentData().putBoolean("mm_soul", true);
    }

    @Override
    protected ParticleOptions getTrailParticle() {
        return ParticleTypes.SOUL_FIRE_FLAME;
    }

    @Override
    public void tick() {
        super.tick();
        if (!level().isClientSide() && tickCount > 100) discard();
    }

    @Override
    protected boolean canHitEntity(Entity e) {
        if (!super.canHitEntity(e)
                || !(e instanceof LivingEntity living)
                || !(getOwner() instanceof SoulBlazeling pet)) return false;
        var owner = pet.summoner();
        return owner != null && pet.validTarget(owner, living);
    }

    @Override
    protected void onHitEntity(EntityHitResult hit) {
        if (level().isClientSide()
                || !(hit.getEntity() instanceof LivingEntity target)
                || !(getOwner() instanceof SoulBlazeling pet)) return;
        var owner = pet.summoner();
        if (owner == null || !pet.validTarget(owner, target)) return;
        com.ethermod.trait.Burning.soulBurn(target, owner, 5);
        // Vanilla five-damage hit and enchantment hooks, attributed to the summoning player.
        setOwner(owner);
        try {
            super.onHitEntity(hit);
        } finally {
            setOwner(pet);
        }
        discard();
    }

    @Override
    protected void onHitBlock(BlockHitResult hit) {
        if (!level().isClientSide()) discard();
    }
}
