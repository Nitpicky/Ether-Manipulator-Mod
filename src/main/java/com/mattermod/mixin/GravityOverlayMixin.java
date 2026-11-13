package com.mattermod.mixin;

import net.minecraft.client.renderer.ScreenEffectRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import org.apache.commons.lang3.tuple.Pair;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ScreenEffectRenderer.class)
public abstract class GravityOverlayMixin {
    @Inject(method = "getOverlayBlock", at = @At("HEAD"), cancellable = true)
    private static void mm$eye(
            Player player, CallbackInfoReturnable<Pair<BlockState, BlockPos>> cir) {
        if (!com.mattermod.trait.MovementPhysics.isTurning(player)) return;
        Vec3 eye = player.getEyePosition();
        for (int i = 0; i < 8; i++) {
            Vec3 offset =
                    new Vec3(
                            ((i & 1) == 0 ? -.4 : .4) * player.getBbWidth(),
                            ((i & 2) == 0 ? -.05 : .05) * player.getScale(),
                            ((i & 4) == 0 ? -.4 : .4) * player.getBbWidth());
            BlockPos pos =
                    BlockPos.containing(
                            eye.add(
                                    com.mattermod.trait.MovementPhysics.orientView(
                                            player, offset, 0)));
            var state = player.level().getBlockState(pos);
            if (state.getRenderShape() != net.minecraft.world.level.block.RenderShape.INVISIBLE
                    && state.isViewBlocking(player.level(), pos)) {
                cir.setReturnValue(Pair.of(state, pos));
                return;
            }
        }
        cir.setReturnValue(null);
    }
}
