package com.ethermod.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.SoulFireBlock;
import net.minecraft.world.level.block.state.BlockState;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The mod's own soul fire (Soulwake terrain fire, Blazeling, placed through TempBlocks.tempBlock)
 * burns on any ground until it expires. Other soul fire keeps the vanilla rule (soul sand / soul
 * soil only).
 *
 * <p>The server decides: it keeps a temp soul fire however its neighbours change, and removes any
 * other soul fire that can't survive (and tells the client). The client can't see which fires are
 * temporary, so it only stays lenient (survives wherever normal fire could) so that a predicted
 * neighbour change never hides a fire the server keeps; the server's update corrects the rest.
 */
@Mixin(SoulFireBlock.class)
public abstract class SoulFireMixin {
    @Inject(method = "canSurvive", at = @At("HEAD"), cancellable = true)
    private void mm$temporarySoul(
            BlockState state,
            LevelReader level,
            BlockPos pos,
            CallbackInfoReturnable<Boolean> cir) {
        if (level instanceof ServerLevel sl) {
            // checked during placement too: tempBlock marks the position before its setBlock
            if (com.ethermod.trait.TempBlocks.isTempBlock(sl, pos)) cir.setReturnValue(true);
        } else if (level instanceof Level l
                && l.isClientSide()
                && net.minecraft.world.level.block.Blocks.FIRE
                        .defaultBlockState()
                        .canSurvive(level, pos)) cir.setReturnValue(true);
    }
}
