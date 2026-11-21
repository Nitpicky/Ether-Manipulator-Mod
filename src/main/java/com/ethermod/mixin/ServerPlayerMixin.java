package com.ethermod.mixin;

import com.ethermod.manipulator.Manipulator;

import net.minecraft.server.level.ServerPlayer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The drop key (Q) acts on the real selected hotbar item. While the Manipulator is summoned the
 * player can't see that item, so dropping is refused instead of silently throwing away something
 * hidden.
 */
@Mixin(ServerPlayer.class)
public abstract class ServerPlayerMixin {

    @Inject(method = "drop(Z)V", at = @At("HEAD"), cancellable = true)
    private void ethermod$noDropWhileSummoned(
            boolean fullStack, org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        if (Manipulator.isSummoned((ServerPlayer) (Object) this)) {
            ci.cancel();
        }
    }
}
