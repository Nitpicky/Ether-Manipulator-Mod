package com.ethermod.mixin;

import com.ethermod.manipulator.Manipulator;

import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The player's main hand, part of the virtual hand (see PlayerMixin). Since 26.3 equipment reads
 * and writes live on LivingEntity, so they are caught here, for players only.
 */
@Mixin(LivingEntity.class)
public abstract class LivingHandMixin {

    @Inject(method = "getItemBySlot", at = @At("HEAD"), cancellable = true)
    private void ethermod$virtualHand(EquipmentSlot slot, CallbackInfoReturnable<ItemStack> cir) {
        if (slot != EquipmentSlot.MAINHAND || !((Object) this instanceof Player self)) return;
        if (Manipulator.isSummoned(self)) {
            // inside a block/entity interaction the Manipulator is invisible: an empty hand
            cir.setReturnValue(
                    com.ethermod.manipulator.ManipulatorVeil.veiled(self)
                            ? ItemStack.EMPTY
                            : Manipulator.stack(self));
        }
    }

    /**
     * Under a veil the "empty" main hand is really the hidden hotbar slot, and a block or entity that
     * gives its item to an empty hand (armor stand, allay, a pedestal) would overwrite the item in it.
     * What it gives goes into the inventory instead (dropped when full), and clearing the hand
     * clears nothing.
     */
    @Inject(
            method = "setItemSlot(Lnet/minecraft/world/entity/EquipmentSlot;Lnet/minecraft/world/item/ItemStack;)V",
            at = @At("HEAD"),
            cancellable = true)
    private void ethermod$veiledHandWrite(
            EquipmentSlot slot,
            ItemStack stack,
            org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        if (slot != EquipmentSlot.MAINHAND || !((Object) this instanceof Player self)) return;
        if (!com.ethermod.manipulator.ManipulatorVeil.veiled(self)) return;
        ci.cancel();
        // the server is authoritative over the inventory and syncs it; the client just skips
        if (!stack.isEmpty() && !self.level().isClientSide())
            self.getInventory().placeItemBackInInventory(stack, net.minecraft.util.Prediction.SERVER_ONLY);
    }

    @Inject(
            method = "setItemSlot(Lnet/minecraft/world/entity/EquipmentSlot;Lnet/minecraft/world/item/ItemStack;Z)V",
            at = @At("HEAD"),
            cancellable = true)
    private void ethermod$veiledHandWriteSync(
            EquipmentSlot slot,
            ItemStack stack,
            boolean silent,
            org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        if (slot != EquipmentSlot.MAINHAND || !((Object) this instanceof Player self)) return;
        if (!com.ethermod.manipulator.ManipulatorVeil.veiled(self)) return;
        ci.cancel();
        // the server is authoritative over the inventory and syncs it; the client just skips
        if (!stack.isEmpty() && !self.level().isClientSide())
            self.getInventory().placeItemBackInInventory(stack, net.minecraft.util.Prediction.SERVER_ONLY);
    }
}
