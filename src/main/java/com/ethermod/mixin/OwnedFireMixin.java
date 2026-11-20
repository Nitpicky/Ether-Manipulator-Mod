package com.ethermod.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.state.BlockState;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(BaseFireBlock.class)
public abstract class OwnedFireMixin {
    @Inject(method = "entityInside", at = @At("HEAD"))
    private void mm$owned(BlockState state, Level level, BlockPos pos, Entity e, CallbackInfo ci) {
        if (level instanceof net.minecraft.server.level.ServerLevel sl)
            com.ethermod.trait.OwnedFire.burnFromOwnedFire(sl, pos, e);
    }
}
