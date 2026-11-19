package com.ethermod.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.SoulFireBlock;
import net.minecraft.world.level.block.state.BlockState;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Soul fire set by the mod's Soulwake burns on any block, like ordinary fire, while it lasts. */
@Mixin(SoulFireBlock.class)
public abstract class SoulFireMixin {
    @Inject(method = "canSurvive", at = @At("HEAD"), cancellable = true)
    private void mm$temporarySoul(
            BlockState state,
            LevelReader level,
            BlockPos pos,
            CallbackInfoReturnable<Boolean> cir) {
        // The server only keeps the mod's own soul fire (temporary blocks) alive off soul soil;
        // every other soul fire, and world generation, follow vanilla. The client cannot see which
        // blocks are temporary, so it keeps any soul fire that ordinary fire could stand on, rather
        // than dropping the mod's fire locally until the server corrects it.
        if (level instanceof ServerLevel sl) {
            if (com.ethermod.trait.WorldEffects.isTempBlock(sl, pos)) cir.setReturnValue(true);
        } else if (level instanceof net.minecraft.world.level.Level l
                && l.isClientSide()
                && net.minecraft.world.level.block.Blocks.FIRE
                        .defaultBlockState()
                        .canSurvive(level, pos)) cir.setReturnValue(true);
    }
}
