package com.mattermod.mixin;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Mob.class)
public abstract class DazzleMixin {
    @Inject(method = "setTarget", at = @At("HEAD"), cancellable = true)
    private void mm$dazzle(LivingEntity target, CallbackInfo ci) {
        Mob self = (Mob) (Object) this;
        if (target != null
                && self.getPersistentData().getLong("mm_dazzled_until")
                        > self.level().getGameTime()) ci.cancel();
    }
}
