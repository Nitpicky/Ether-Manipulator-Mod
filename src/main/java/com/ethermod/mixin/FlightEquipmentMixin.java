package com.ethermod.mixin;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;

@Mixin(LocalPlayer.class)
public abstract class FlightEquipmentMixin {
    @Redirect(
            method = "aiStep",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/world/item/ItemStack;canElytraFly(Lnet/minecraft/world/entity/LivingEntity;)Z"))
    private boolean mm$wings(ItemStack stack, LivingEntity entity) {
        return stack.canElytraFly(entity)
                || (entity instanceof Player p
                        && com.ethermod.trait.Traits.has(
                                com.ethermod.manipulator.Manipulator.stack(p), "mobility.flight"));
    }
}
