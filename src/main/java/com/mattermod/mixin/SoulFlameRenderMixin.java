package com.mattermod.mixin;

import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.Material;
import net.minecraft.world.entity.Entity;

import org.joml.Quaternionf;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;

@Mixin(EntityRenderDispatcher.class)
public abstract class SoulFlameRenderMixin {
    @Redirect(
            method = "renderFlame",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/client/resources/model/Material;sprite()Lnet/minecraft/client/renderer/texture/TextureAtlasSprite;"))
    private TextureAtlasSprite mm$soul(
            Material material,
            PoseStack pose,
            MultiBufferSource buffer,
            Entity entity,
            Quaternionf rotation) {
        if (entity.getPersistentData().getBoolean("mm_soul"))
            return net.minecraft.client.Minecraft.getInstance()
                    .getTextureAtlas(net.minecraft.world.inventory.InventoryMenu.BLOCK_ATLAS)
                    .apply(
                            net.minecraft.resources.ResourceLocation.withDefaultNamespace(
                                    material
                                                    == net.minecraft.client.resources.model
                                                            .ModelBakery.FIRE_0
                                            ? "block/soul_fire_0"
                                            : "block/soul_fire_1"));
        return material.sprite();
    }
}
