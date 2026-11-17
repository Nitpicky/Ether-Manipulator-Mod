package com.ethermod.mixin;

import com.ethermod.item.EtherManipulatorItem;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.world.InteractionHand;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Vanilla drops the held item out of view and raises it again whenever a right click is used (the
 * same motion as equipping it). The Manipulator has its own charge and release animations, so it
 * stays in hand.
 */
@Mixin(ItemInHandRenderer.class)
public abstract class NoUseDipMixin {

    @Inject(method = "itemUsed", at = @At("HEAD"), cancellable = true)
    private void mm$keepManipulatorUp(InteractionHand hand, CallbackInfo ci) {
        var p = Minecraft.getInstance().player;
        if (p != null && p.getItemInHand(hand).getItem() instanceof EtherManipulatorItem)
            ci.cancel();
    }
}
