package com.ethermod.mixin;

import com.ethermod.manipulator.Manipulator;
import com.ethermod.trait.Traits;

import net.minecraft.client.player.LocalPlayer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;

@Mixin(LocalPlayer.class)
public abstract class ChargeMovementMixin {
    @Inject(method = "moveTowardsClosestSpace", at = @At("HEAD"), cancellable = true)
    private void mm$rotatedEscape(
            double x, double z, org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        var p = (LocalPlayer) (Object) this;
        if (com.ethermod.trait.MovementPhysics.down(p) == net.minecraft.core.Direction.DOWN)
            return;
        com.ethermod.trait.MovementPhysics.escapeEmbedded(p);
        ci.cancel();
    }

    @Redirect(
            method = "aiStep",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/world/phys/Vec3;add(DDD)Lnet/minecraft/world/phys/Vec3;"))
    private net.minecraft.world.phys.Vec3 mm$flyingKeys(
            net.minecraft.world.phys.Vec3 v, double x, double y, double z) {
        var p = (LocalPlayer) (Object) this;
        if (p.getAbilities().flying
                && com.ethermod.trait.MovementPhysics.down(p) != net.minecraft.core.Direction.DOWN)
            return v.add(
                    com.ethermod.trait.MovementPhysics.rotate(
                            new net.minecraft.world.phys.Vec3(x, y, z),
                            com.ethermod.trait.MovementPhysics.down(p)));
        return v.add(x, y, z);
    }

    @Redirect(
            method = "aiStep",
            at =
                    @At(
                            value = "INVOKE",
                            target = "Lnet/minecraft/client/player/LocalPlayer;isUsingItem()Z"))
    private boolean mm$chargeSlow(LocalPlayer p) {
        return p.isUsingItem()
                && !(p.getUseItem().getItem() instanceof com.ethermod.item.EtherManipulatorItem
                        && (Traits.has(Manipulator.stack(p), "kinetic.skirmisher")
                                || com.ethermod.item.OrbMode.get(Manipulator.stack(p)).isHold()));
    }
}
