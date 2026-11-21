package com.ethermod.mixin;

import com.ethermod.client.PhotonicGlow;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;

import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * The Photonic glow on blocks: the light the terrain, block entities and particles are drawn with
 * gets its block part raised to the glow there (client.PhotonicGlow). Sodium 0.6 for 1.21.1
 * reads its terrain light through this method too (LightDataAccess#compute calls
 * LevelRenderer.getLightColor(level, state, pos) for every non-emissive block that is not a full
 * opaque cube), so it needs no Sodium-specific mixin. Cosmetic: skipped if it cannot apply.
 */
@Mixin(net.minecraft.util.LightCoordsUtil.class)
public abstract class PhotonicGlowMixin {
    @ModifyReturnValue(
            method =
                    "getLightCoords(Lnet/minecraft/util/LightCoordsUtil$BrightnessGetter;Lnet/minecraft/world/level/BlockAndLightGetter;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/BlockPos;)I",
            at = @At("RETURN"),
            require = 0)
    private static int mm$photonicGlow(
            int packed, net.minecraft.util.LightCoordsUtil.BrightnessGetter brightness, net.minecraft.world.level.BlockAndLightGetter level, BlockState state, BlockPos pos) {
        return PhotonicGlow.lightColor(level, pos, packed);
    }
}
