package com.mattermod.mixin;

import com.mattermod.trait.MovementPhysics;

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

    @Unique
    private void mm$align(Player p, float partial) {
        var align = MovementPhysics.viewRotation(p, partial);
        setPosition(MovementPhysics.eye(p, partial));
        rotation.premul(align);
        align.transform(forwards);
        align.transform(up);
        align.transform(left);
    }

    // Vanilla zoom must test terrain from the rotated eye and along the rotated view.
    @Inject(
            method = "setup",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Camera;getMaxZoom(F)F"))
    private void mm$thirdPerson(
            BlockGetter level,
            Entity entity,
            boolean detached,
            boolean reversed,
            float partial,
            CallbackInfo ci) {
        if (entity instanceof Player p && MovementPhysics.isTurning(p)) mm$align(p, partial);
    }

    @Inject(method = "setup", at = @At("TAIL"))
    private void mm$firstPerson(
            BlockGetter level,
            Entity entity,
            boolean detached,
            boolean reversed,
            float partial,
            CallbackInfo ci) {
        if (!detached && entity instanceof Player p && MovementPhysics.isTurning(p))
            mm$align(p, partial);
    }
}
