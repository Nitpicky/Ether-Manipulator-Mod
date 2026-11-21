package com.ethermod.mixin;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerGamePacketListenerImpl.class)
public abstract class MovementConnectionMixin {
    @Shadow public ServerPlayer player;
    @Shadow private boolean clientIsFloating;

    /** Under shifted gravity a jump is only the vanilla one when the player really jumped. */
    @WrapOperation(
            method = {"handleMovePlayer", "handlePlayerPositionChange"},
            at =
                    @At(
                            value = "INVOKE",
                            target = "Lnet/minecraft/server/level/ServerPlayer;jumpFromGround()V"),
            require = 0)
    private void mm$localJump(ServerPlayer p, Operation<Void> original) {
        if (com.ethermod.trait.MovementPhysics.down(p) == net.minecraft.core.Direction.DOWN
                || com.ethermod.trait.MovementPhysics.jump(p)) original.call(p);
    }

    @Inject(method = "tick", at = @At("HEAD"))
    private void mm$legitimateHover(CallbackInfo ci) {
        // tether hangs, reels and zipline rides hold a player up longer than vanilla's 80 ticks
        if (com.ethermod.trait.MobilityEffects.legitimatelyAirborne(player)) clientIsFloating = false;
    }
}
