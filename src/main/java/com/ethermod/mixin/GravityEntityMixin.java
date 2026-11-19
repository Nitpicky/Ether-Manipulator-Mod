package com.ethermod.mixin;

import com.ethermod.trait.MovementPhysics;

import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.*;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Entity.class)
public abstract class GravityEntityMixin {
    @Inject(method = "makeBoundingBox", at = @At("HEAD"), cancellable = true)
    private void mm$box(CallbackInfoReturnable<AABB> cir) {
        if ((Object) this instanceof Player p && MovementPhysics.down(p) != Direction.DOWN)
            cir.setReturnValue(MovementPhysics.bounds(p));
    }

    @Inject(
            method = "getEyePosition(F)Lnet/minecraft/world/phys/Vec3;",
            at = @At("HEAD"),
            cancellable = true)
    private void mm$eye(float partial, CallbackInfoReturnable<Vec3> cir) {
        if ((Object) this instanceof Player p && MovementPhysics.isTurning(p))
            cir.setReturnValue(MovementPhysics.eye(p, partial));
    }

    @Inject(method = "getViewVector", at = @At("RETURN"), cancellable = true)
    private void mm$look(float partial, CallbackInfoReturnable<Vec3> cir) {
        if ((Object) this instanceof Player p && MovementPhysics.isTurning(p))
            cir.setReturnValue(MovementPhysics.orientView(p, cir.getReturnValue(), partial));
    }

    @Inject(method = "getLookAngle", at = @At("RETURN"), cancellable = true)
    private void mm$lookNow(CallbackInfoReturnable<Vec3> cir) {
        if ((Object) this instanceof Player p && MovementPhysics.isTurning(p))
            cir.setReturnValue(MovementPhysics.orientView(p, cir.getReturnValue(), 0));
    }

    @Inject(
            method = "getEyePosition()Lnet/minecraft/world/phys/Vec3;",
            at = @At("HEAD"),
            cancellable = true)
    private void mm$eyeNow(CallbackInfoReturnable<Vec3> cir) {
        if ((Object) this instanceof Player p && MovementPhysics.isTurning(p))
            cir.setReturnValue(MovementPhysics.eye(p, 0));
    }

    @Inject(method = "collide", at = @At("HEAD"), cancellable = true)
    private void mm$collision(Vec3 wanted, CallbackInfoReturnable<Vec3> cir) {
        if ((Object) this instanceof Player p
                && MovementPhysics.down(p) != Direction.DOWN
                && !p.noPhysics)
            cir.setReturnValue(
                    com.ethermod.trait.AirwalkFloor.clamp(
                            p, com.ethermod.trait.GravityCollision.resolve(p, wanted)));
    }

    /** Airwalk: the floor stops the fall like a block would (upright gravity). */
    @Inject(method = "collide", at = @At("RETURN"), cancellable = true)
    private void mm$airwalk(Vec3 wanted, CallbackInfoReturnable<Vec3> cir) {
        if ((Object) this instanceof Player p && MovementPhysics.down(p) == Direction.DOWN)
            cir.setReturnValue(com.ethermod.trait.AirwalkFloor.clamp(p, cir.getReturnValue()));
    }

    @org.spongepowered.asm.mixin.injection.Redirect(
            method = "move",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/world/entity/Entity;checkFallDamage(DZLnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/BlockPos;)V"))
    private void mm$fall(
            Entity entity,
            double distance,
            boolean onGround,
            net.minecraft.world.level.block.state.BlockState block,
            net.minecraft.core.BlockPos pos) {
        if (!(entity instanceof Player p) || MovementPhysics.down(p) == Direction.DOWN)
            ((GravityFallAccessor) entity).mm$checkFall(distance, onGround, block, pos);
    }

}
