package com.mattermod.mixin;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;

import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerGamePacketListenerImpl.class)
public abstract class MovementConnectionMixin {
    @Shadow public ServerPlayer player;
    @Shadow private boolean clientIsFloating;

    @Redirect(
            method = "handleMovePlayer",
            at =
                    @At(
                            value = "INVOKE",
                            target = "Lnet/minecraft/server/level/ServerPlayer;jumpFromGround()V"))
    private void mm$localJump(ServerPlayer p) {
        if (com.mattermod.trait.MovementPhysics.down(p) == net.minecraft.core.Direction.DOWN
                || com.mattermod.trait.MovementPhysics.jump(p)) p.jumpFromGround();
    }

    @Inject(method = "tick", at = @At("HEAD"))
    private void mm$legitimateHover(CallbackInfo ci) {
        var st = com.mattermod.manipulator.Manipulator.stack(player);
        if (com.mattermod.trait.MovementPhysics.down(player) != net.minecraft.core.Direction.DOWN
                || com.mattermod.trait.Traits.has(st, "mobility.airwalk")) clientIsFloating = false;
    }
}
