package com.mattermod.mixin;

import com.mattermod.manipulator.Manipulator;
import com.mattermod.trait.*;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LivingEntity.class)
public abstract class GravityLivingMixin {
    // Body facing must use the same local displacement as limb animation.
    @ModifyVariable(method = "tick", at = @At("STORE"), ordinal = 0)
    private double mm$bodyX(double value) {
        if ((Object) this instanceof Player p
                && MovementPhysics.down(p) != net.minecraft.core.Direction.DOWN)
            return MovementPhysics.local(p, p.position().subtract(p.xo, p.yo, p.zo)).x;
        return value;
    }

    @ModifyVariable(method = "tick", at = @At("STORE"), ordinal = 1)
    private double mm$bodyZ(double value) {
        if ((Object) this instanceof Player p
                && MovementPhysics.down(p) != net.minecraft.core.Direction.DOWN)
            return MovementPhysics.local(p, p.position().subtract(p.xo, p.yo, p.zo)).z;
        return value;
    }

    @Inject(method = "calculateEntityAnimation", at = @At("HEAD"), cancellable = true)
    private void mm$walkPlane(boolean includeVertical, CallbackInfo ci) {
        if ((Object) this instanceof Player p
                && MovementPhysics.down(p) != net.minecraft.core.Direction.DOWN) {
            Vec3 delta = p.position().subtract(p.xo, p.yo, p.zo),
                    up = MovementPhysics.vector(MovementPhysics.down(p).getOpposite());
            double distance =
                    includeVertical
                            ? delta.length()
                            : delta.subtract(up.scale(delta.dot(up))).length();
            p.walkAnimation.update(Math.min((float) distance * 4, 1), .4f);
            ci.cancel();
        }
    }

    @Inject(method = "jumpFromGround", at = @At("HEAD"), cancellable = true)
    private void mm$jump(CallbackInfo ci) {
        if ((Object) this instanceof Player p
                && MovementPhysics.down(p) != net.minecraft.core.Direction.DOWN) {
            MovementPhysics.jumpFromGround(p);
            ci.cancel();
        }
    }

    @Inject(method = "updateFallFlying", at = @At("HEAD"), cancellable = true)
    private void mm$elytra(CallbackInfo ci) {
        if ((Object) this instanceof Player p
                && Traits.has(Manipulator.stack(p), "mobility.flight")
                && p.isFallFlying()
                && !p.onGround()
                && !p.isInWater()
                && !p.isPassenger()) ci.cancel();
    }
}
