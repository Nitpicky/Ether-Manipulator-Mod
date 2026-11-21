package com.ethermod.mixin;

import com.ethermod.trait.TempBlocks;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.FrostedIceBlock;
import net.minecraft.world.level.block.state.BlockState;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Frost bridges are Frost Walker's ice, but they keep to their own timer: vanilla's light and
 * neighbour melting leaves them alone ({@link TempBlocks} ages and removes them).
 */
@Mixin(FrostedIceBlock.class)
public abstract class FrostedIceMixin {

    @Inject(method = "slightlyMelt", at = @At("HEAD"), cancellable = true)
    private void ethermod$keepBridge(
            BlockState state, Level level, BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        if (level instanceof ServerLevel sl && TempBlocks.isTempBlock(sl, pos))
            cir.setReturnValue(false);
    }
}
