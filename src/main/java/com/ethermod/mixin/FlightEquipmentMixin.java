package com.ethermod.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;

import net.minecraft.client.player.LocalPlayer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Gravitic Flight: the local player can start gliding without an elytra. */
@Mixin(LocalPlayer.class)
public abstract class FlightEquipmentMixin {
    @ModifyExpressionValue(
            method = "aiStep",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/world/item/ItemStack;canElytraFly(Lnet/minecraft/world/entity/LivingEntity;)Z"))
    private boolean mm$wings(boolean canFly) {
        return canFly
                || com.ethermod.trait.Traits.has(
                        com.ethermod.manipulator.Manipulator.stack((LocalPlayer) (Object) this),
                        "gravitic.flight");
    }
}
