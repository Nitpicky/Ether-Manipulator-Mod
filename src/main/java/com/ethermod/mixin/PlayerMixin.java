package com.ethermod.mixin;

import com.ethermod.manipulator.Manipulator;

import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The virtual hand: while the Manipulator is summoned, the main hand reports the Manipulator
 * instead of the selected hotbar item. Mining speed, harvest checks, attacks, item use, hand
 * rendering and the equipment sync to other players all read through here, so they work without
 * moving any real item.
 */
@Mixin(Player.class)
public abstract class PlayerMixin {
    @Inject(method = "maybeBackOffFromEdge", at = @At("HEAD"), cancellable = true)
    private void mm$sneakEdge(
            net.minecraft.world.phys.Vec3 movement,
            net.minecraft.world.entity.MoverType type,
            CallbackInfoReturnable<net.minecraft.world.phys.Vec3> cir) {
        Player p = (Player) (Object) this;
        if (com.ethermod.trait.MovementPhysics.down(p) != net.minecraft.core.Direction.DOWN)
            cir.setReturnValue(com.ethermod.trait.MovementPhysics.sneakEdge(p, movement, type));
    }

    /** Vanilla still chooses the pose; clearance is tested in the player's actual gravity frame. */
    @Inject(
            method = "canPlayerFitWithinBlocksAndEntitiesWhen",
            at = @At("HEAD"),
            cancellable = true)
    private void mm$poseClearance(
            net.minecraft.world.entity.Pose pose, CallbackInfoReturnable<Boolean> cir) {
        Player p = (Player) (Object) this;
        if (com.ethermod.trait.MovementPhysics.down(p) != net.minecraft.core.Direction.DOWN)
            cir.setReturnValue(
                    p.level()
                            .noCollision(
                                    p,
                                    com.ethermod.trait.MovementPhysics.poseBounds(p, pose)
                                            .deflate(.0000001)));
    }

    @Inject(method = "travel", at = @At("HEAD"), cancellable = true)
    private void mm$relativeTravel(
            net.minecraft.world.phys.Vec3 input,
            org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        if (com.ethermod.trait.MovementPhysics.travel((Player) (Object) this, input)) ci.cancel();
    }

    @org.spongepowered.asm.mixin.injection.ModifyVariable(
            method = "causeFoodExhaustion",
            at = @At("HEAD"),
            argsOnly = true)
    private float ethermod$nourishment(float amount) {
        return com.ethermod.trait.Traits.hasCommon(
                        Manipulator.stack((Player) (Object) this),
                        com.ethermod.trait.Traits.BOTANIC,
                        1)
                ? amount * 0.5f
                : amount;
    }

    @Inject(method = "tryToStartFallFlying", at = @At("HEAD"), cancellable = true)
    private void mm$flight(CallbackInfoReturnable<Boolean> cir) {
        Player p = (Player) (Object) this;
        if (com.ethermod.trait.Traits.has(Manipulator.stack(p), "gravitic.flight")
                && !p.isFallFlying()
                && !p.onGround()
                && !p.onClimbable()
                && !p.hasEffect(net.minecraft.world.effect.MobEffects.LEVITATION)
                && !p.isInWater()
                && !p.isPassenger()) {
            p.startFallFlying();
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "getItemBySlot", at = @At("HEAD"), cancellable = true)
    private void ethermod$virtualHand(EquipmentSlot slot, CallbackInfoReturnable<ItemStack> cir) {
        if (slot != EquipmentSlot.MAINHAND) return;
        Player self = (Player) (Object) this;
        if (Manipulator.isSummoned(self)) {
            cir.setReturnValue(Manipulator.stack(self));
        }
    }
}
