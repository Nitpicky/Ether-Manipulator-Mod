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

    /**
     * Body facing must use the same local displacement as limb animation. LivingEntity.tick derives
     * the body yaw it passes to tickHeadTurn from the world-space step (getX() - xo, getZ() - zo).
     * Under shifted gravity it is recomputed here from the gravity-local step, with vanilla's own
     * arithmetic, so the result is what vanilla gives for that step. Cosmetic: if the call moves
     * in an update, vanilla body turning remains.
     */
    @WrapOperation(
            method = "tick",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/world/entity/LivingEntity;tickHeadTurn(F)V"),
            require = 0)
    private void mm$bodyTurn(LivingEntity self, float yRot, Operation<Void> original) {
        if (!(self instanceof Player p)
                || MovementPhysics.down(p) == net.minecraft.core.Direction.DOWN) {
            original.call(self, yRot);
            return;
        }
        Vec3 local = MovementPhysics.local(p, p.position().subtract(p.xo, p.yo, p.zo));
        double d0 = local.x, d1 = local.z;
        float f = (float) (d0 * d0 + d1 * d1);
        float f1 = p.yBodyRot;
        if (f > 0.0025000002F) {
            float f4 = (float) Mth.atan2(d1, d0) * (180.0F / (float) Math.PI) - 90.0F;
            float f5 = Mth.abs(Mth.wrapDegrees(p.getYRot()) - f4);
            if (95.0F < f5 && f5 < 265.0F) f1 = f4 - 180.0F;
            else f1 = f4;
        }
        if (p.getSwingAnimation(1.0F) > 0.0F) f1 = p.getYRot();
        original.call(self, f1);
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
            p.walkAnimation.update(Math.min((float) distance * 4, 1), .4f, 1.0f);
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
