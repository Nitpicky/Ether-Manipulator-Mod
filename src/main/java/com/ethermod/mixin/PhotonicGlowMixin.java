package com.ethermod.mixin;

import com.ethermod.client.PhotonicGlow;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;

import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * The Photonic glow on blocks: the light the terrain, block entities and particles are drawn with
 * gets its block part raised to the glow there (client.PhotonicGlow). Sodium and Embeddium read
 * their terrain light through this method too. Cosmetic: skipped if it cannot apply.
 */
@Mixin(LevelRenderer.class)
public abstract class PhotonicGlowMixin {
    @ModifyReturnValue(
            method =
                    "getLightColor(Lnet/minecraft/world/level/BlockAndTintGetter;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/BlockPos;)I",
            at = @At("RETURN"),
            require = 0)
    private static int mm$photonicGlow(
            int packed, BlockAndTintGetter level, BlockState state, BlockPos pos) {
        return PhotonicGlow.lightColor(level, pos, packed);
    }
}
