package com.ethermod.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(net.minecraft.world.entity.Entity.class)
public interface GravityFallAccessor {
    @Invoker("checkFallDamage")
    void mm$checkFall(
            double distance,
            boolean ground,
            net.minecraft.world.level.block.state.BlockState state,
            net.minecraft.core.BlockPos pos);
}
