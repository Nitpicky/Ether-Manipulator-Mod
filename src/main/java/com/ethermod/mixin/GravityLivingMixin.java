package com.ethermod.mixin;

import com.ethermod.manipulator.Manipulator;
import com.ethermod.trait.*;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LivingEntity.class)
public abstract class GravityLivingMixin {
    @Shadow protected float run;
    @Shadow protected float oRun;

    /**
     * Body facing must use the same local displacement as limb animation. LivingEntity.tick
     * derives, from the world-space step (getX() - xo, getZ() - zo): the body yaw and the
     * animation step it passes to tickHeadTurn, and the run blend it stores just before. Under
     * shifted gravity all three are recomputed here from the gravity-local step, with vanilla's
     * own arithmetic, so the result is what vanilla gives for that step. Cosmetic: if the call
     * moves in an update, vanilla body turning remains.
     */
    @WrapOperation(
            method = "tick",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/world/entity/LivingEntity;tickHeadTurn(FF)F"),
            require = 0)
    private float mm$bodyTurn(
            LivingEntity self, float yRot, float animStep, Operation<Float> original) {
        if (!(self instanceof Player p)
                || MovementPhysics.down(p) == net.minecraft.core.Direction.DOWN)
            return original.call(self, yRot, animStep);
        Vec3 local = MovementPhysics.local(p, p.position().subtract(p.xo, p.yo, p.zo));
        double d0 = local.x, d1 = local.z;
        float f = (float) (d0 * d0 + d1 * d1);
        float f1 = p.yBodyRot;
        float f2 = 0.0F;
        float f3 = 0.0F;
        if (f > 0.0025000002F) {
            f3 = 1.0F;
            f2 = (float) Math.sqrt((double) f) * 3.0F;
            float f4 = (float) Mth.atan2(d1, d0) * (180.0F / (float) Math.PI) - 90.0F;
            float f5 = Mth.abs(Mth.wrapDegrees(p.getYRot()) - f4);
            if (95.0F < f5 && f5 < 265.0F) f1 = f4 - 180.0F;
            else f1 = f4;
        }
        if (p.attackAnim > 0.0F) f1 = p.getYRot();
        if (!p.onGround()) f3 = 0.0F;
        // vanilla already blended run toward its world-space f3; redo it from the value it started at
        this.run = this.oRun + (f3 - this.oRun) * 0.3F;
        return original.call(self, f1, f2);
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
                && Traits.has(Manipulator.stack(p), "gravitic.flight")
                && p.isFallFlying()
                && !p.onGround()
                && !p.isInWater()
                && !p.isPassenger()) ci.cancel();
    }
}
