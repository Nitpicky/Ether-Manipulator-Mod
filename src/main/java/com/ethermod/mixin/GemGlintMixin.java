package com.ethermod.mixin;

import com.ethermod.client.GemGlint;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexMultiConsumer;

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Enchanted Gemstones glint in their Module's colour instead of vanilla purple. */
@Mixin(ItemRenderer.class)
public abstract class GemGlintMixin {
    @Inject(method = "render", at = @At("HEAD"))
    private void mm$gemStart(ItemStack stack, ItemDisplayContext ctx, boolean left, PoseStack pose,
            MultiBufferSource buffers, int light, int overlay, BakedModel model, CallbackInfo ci) {
        GemGlint.current = stack;
    }

    @Inject(method = "render", at = @At("RETURN"))
    private void mm$gemEnd(ItemStack stack, ItemDisplayContext ctx, boolean left, PoseStack pose,
            MultiBufferSource buffers, int light, int overlay, BakedModel model, CallbackInfo ci) {
        GemGlint.current = ItemStack.EMPTY;
    }

    @Inject(method = "getFoilBuffer", at = @At("HEAD"), cancellable = true)
    private static void mm$gemFoil(MultiBufferSource buffers, RenderType type, boolean isItem, boolean glint,
            CallbackInfoReturnable<VertexConsumer> cir) {
        if (!glint || !isItem) return;
        var g = GemGlint.forCurrent();
        if (g != null) cir.setReturnValue(VertexMultiConsumer.create(buffers.getBuffer(g), buffers.getBuffer(type)));
    }

    @Inject(method = "getFoilBufferDirect", at = @At("HEAD"), cancellable = true)
    private static void mm$gemFoilDirect(MultiBufferSource buffers, RenderType type, boolean noEntity, boolean glint,
            CallbackInfoReturnable<VertexConsumer> cir) {
        if (!glint || !noEntity) return;
        var g = GemGlint.forCurrent();
        if (g != null) cir.setReturnValue(VertexMultiConsumer.create(buffers.getBuffer(g), buffers.getBuffer(type)));
    }
}
