package com.ethermod.mixin;

import com.ethermod.trait.MovementPhysics;

import net.minecraft.client.Camera;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.phys.Vec3;

import org.joml.Quaternionf;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Camera.class)
public abstract class GravityCameraMixin {
    @Shadow @Final private Quaternionf rotation;
    @Shadow @Final private org.joml.Vector3f forwards;
    @Shadow @Final private org.joml.Vector3f up;
    @Shadow @Final private org.joml.Vector3f left;

    @Shadow
    protected abstract void setPosition(Vec3 position);

    @Shadow private Vec3 position;

    @Unique
    private void mm$align(Player p, float partial) {
        var align = MovementPhysics.viewRotation(p, partial);
        setPosition(MovementPhysics.eye(p, partial));
        rotation.premul(align);
        align.transform(forwards);
        align.transform(up);
        align.transform(left);
    }

    /** Whether the zoom hook below already turned the camera during this setup. */
    @Unique private boolean mm$aligned;

    @Inject(method = "setup", at = @At("HEAD"), require = 0)
    private void mm$begin(
            BlockGetter level,
            Entity entity,
            boolean detached,
            boolean reversed,
            float partial,
            CallbackInfo ci) {
        mm$aligned = false;
    }

    // Vanilla zoom must test terrain from the rotated eye and along the rotated view.
    @Inject(
            method = "setup",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Camera;getMaxZoom(F)F"),
            require = 0)
    private void mm$thirdPerson(
            BlockGetter level,
            Entity entity,
            boolean detached,
            boolean reversed,
            float partial,
            CallbackInfo ci) {
        if (entity instanceof Player p && MovementPhysics.isTurning(p)) {
            mm$align(p, partial);
            mm$aligned = true;
        }
    }

    /**
     * First person: turn the view at the end. Third person normally turned at the zoom above; when
     * a camera mod replaced that call (a redirect, or its own zoom), it did not run, so the camera
     * is turned here instead: its offset from the eye (the zoom and any shoulder offset the mod
     * chose) is kept in the camera's own frame and carried over to the turned eye. Terrain is not
     * re-tested along the turned view in that case.
     */
    @Inject(method = "setup", at = @At("TAIL"), require = 0)
    private void mm$finish(
            BlockGetter level,
            Entity entity,
            boolean detached,
            boolean reversed,
            float partial,
            CallbackInfo ci) {
        boolean aligned = mm$aligned;
        mm$aligned = false;
        if (aligned || !(entity instanceof Player p) || !MovementPhysics.isTurning(p)) return;
        if (!detached) {
            mm$align(p, partial);
            return;
        }
        Vec3 offset = position.subtract(p.getEyePosition(partial));
        org.joml.Vector3f local =
                rotation.conjugate(new Quaternionf())
                        .transform(new org.joml.Vector3f((float) offset.x, (float) offset.y, (float) offset.z));
        mm$align(p, partial);
        rotation.transform(local);
        setPosition(position.add(local.x, local.y, local.z));
    }
}
