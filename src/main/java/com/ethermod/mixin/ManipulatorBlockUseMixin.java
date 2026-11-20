package com.ethermod.mixin;

import com.ethermod.item.EtherManipulatorItem;
import com.ethermod.manipulator.ManipulatorVeil;
import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalRef;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.phys.BlockHitResult;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * No block ever receives the Manipulator: displays, pots, pedestals and cutting boards (vanilla or
 * modded) would store or consume the held stack. Right click with it behaves as with an empty
 * hand: useItemOn is skipped straight to the default (useWithoutItem: chests open, buttons press,
 * levers flip) and that default runs under a veil, so the hand reads as empty there too. When the
 * block does nothing either, vanilla goes on to the item's own useOn (PASS) and then to Item#use,
 * which charges the Manipulator's right click as before.
 *
 * <p>Both MultiPlayerGameMode (client) and ServerPlayerGameMode (server) call these two methods,
 * so the client predicts exactly what the server does.
 */
@Mixin(BlockBehaviour.BlockStateBase.class)
public abstract class ManipulatorBlockUseMixin {

    @Inject(method = "useItemOn", at = @At("HEAD"), cancellable = true)
    private void ethermod$manipulatorSkipsBlock(
            ItemStack stack,
            Level level,
            Player player,
            InteractionHand hand,
            BlockHitResult hit,
            CallbackInfoReturnable<ItemInteractionResult> cir) {
        if (stack.getItem() instanceof EtherManipulatorItem)
            cir.setReturnValue(ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION);
    }

    @Inject(method = "useWithoutItem", at = @At("HEAD"))
    private void ethermod$veilOpen(
            Level level,
            Player player,
            BlockHitResult hit,
            CallbackInfoReturnable<InteractionResult> cir,
            @Share("veil") LocalRef<Player> previous) {
        previous.set(ManipulatorVeil.open(player));
    }

    @Inject(method = "useWithoutItem", at = @At("RETURN"))
    private void ethermod$veilClose(
            Level level,
            Player player,
            BlockHitResult hit,
            CallbackInfoReturnable<InteractionResult> cir,
            @Share("veil") LocalRef<Player> previous) {
        ManipulatorVeil.close(previous.get());
    }
}
