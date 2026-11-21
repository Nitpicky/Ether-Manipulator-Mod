package com.ethermod.mixin;

import com.ethermod.trait.MovementPhysics;

import net.minecraft.client.player.AbstractClientPlayer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Under sideways gravity, view bobbing follows walking in your own floor plane (vanilla only counts
 * world-horizontal movement, so walking "up" a wall was still).
 */
@Mixin(AbstractClientPlayer.class)
public abstract class GravityBobMixin {
    @Inject(method = "updateBob", at = @At("HEAD"), cancellable = true)
    private void mm$localBob(CallbackInfo ci) {
        var p = (AbstractClientPlayer) (Object) this;
        if (MovementPhysics.down(p) == net.minecraft.core.Direction.DOWN) return;
        double h = MovementPhysics.local(p, p.position().subtract(p.xo, p.yo, p.zo)).horizontalDistance();
        float f = p.onGround() && !p.isDeadOrDying() && !p.isSwimming() ? (float) Math.min(.1, h) : 0;
        p.avatarState().updateBob(f);
        ci.cancel();
    }
}
