package com.ethermod.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.SoulFireBlock;
import net.minecraft.world.level.block.state.BlockState;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(SoulFireBlock.class)
public abstract class SoulFireMixin {
    @Inject(method = "canSurvive", at = @At("HEAD"), cancellable = true)
    private void mm$temporarySoul(
            BlockState state,
            LevelReader level,
            BlockPos pos,
            CallbackInfoReturnable<Boolean> cir) {
        if (level instanceof ServerLevel sl
                && com.ethermod.trait.TempBlocks.isTempBlock(sl, pos)) cir.setReturnValue(true);
        else if (net.minecraft.world.level.block.Blocks.FIRE
                .defaultBlockState()
                .canSurvive(level, pos)) cir.setReturnValue(true);
    }
}
