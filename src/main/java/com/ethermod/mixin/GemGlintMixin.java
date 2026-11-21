package com.ethermod.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import net.minecraft.client.renderer.feature.ItemFeatureRenderer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.resources.model.geometry.BakedQuad;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Enchanted Gemstones glint in their Module's colour instead of vanilla purple (GemGlint). */
@Mixin(ItemFeatureRenderer.class)
public abstract class GemGlintMixin {
    @WrapOperation(
            method = "prepareMainSubmit",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/client/resources/model/geometry/BakedQuad$MaterialInfo;itemGlintRenderType()Lnet/minecraft/client/renderer/rendertype/RenderType;"))
    private RenderType mm$gemGlint(BakedQuad.MaterialInfo info, Operation<RenderType> original) {
        return com.ethermod.client.GemGlint.glintFor(info.sprite(), original.call(info));
    }
}
