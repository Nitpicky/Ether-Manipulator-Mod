package com.mattermod.mixin;

import net.minecraft.world.entity.Entity;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Entity.class)
public abstract class OwnedLavaMixin {
    @Inject(method = "lavaHurt", at = @At("HEAD"))
    private void mm$ownedLava(CallbackInfo ci) {
        var e = (Entity) (Object) this;
        if (e.level() instanceof net.minecraft.server.level.ServerLevel sl)
            com.mattermod.trait.WorldEffects.burnFromOwnedLava(sl, e);
    }
}
