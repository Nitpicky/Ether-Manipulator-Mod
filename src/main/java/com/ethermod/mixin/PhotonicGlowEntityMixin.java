package com.ethermod.mixin;

import com.ethermod.client.PhotonicGlow;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;

import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * The Photonic glow on entities (and the items they hold): their block light is raised to the glow
 * where they stand (client.PhotonicGlow). Renderers that override the light themselves keep theirs.
 * Cosmetic: skipped if it cannot apply.
 */
@Mixin(EntityRenderer.class)
public abstract class PhotonicGlowEntityMixin {
    @ModifyReturnValue(method = "getBlockLightLevel", at = @At("RETURN"), require = 0)
    private int mm$photonicGlow(int light, Entity entity, BlockPos pos) {
        return PhotonicGlow.entityLight(entity, pos, light);
    }
}
