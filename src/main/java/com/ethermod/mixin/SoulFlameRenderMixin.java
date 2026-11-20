package com.ethermod.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;

import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.Material;
import net.minecraft.world.entity.Entity;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** A creature set alight by soul fire burns with blue flames. Cosmetic only. */
@Mixin(EntityRenderDispatcher.class)
public abstract class SoulFlameRenderMixin {
    @WrapOperation(
            method = "renderFlame",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/client/resources/model/Material;sprite()Lnet/minecraft/client/renderer/texture/TextureAtlasSprite;"),
            require = 0)
    private TextureAtlasSprite mm$soul(
            Material material,
            Operation<TextureAtlasSprite> original,
            @Local(argsOnly = true) Entity entity) {
        if (entity != null && entity.getPersistentData().getBoolean("mm_soul"))
            return net.minecraft.client.Minecraft.getInstance()
                    .getTextureAtlas(net.minecraft.world.inventory.InventoryMenu.BLOCK_ATLAS)
                    .apply(
                            net.minecraft.resources.ResourceLocation.withDefaultNamespace(
                                    material
                                                    == net.minecraft.client.resources.model
                                                            .ModelBakery.FIRE_0
                                            ? "block/soul_fire_0"
                                            : "block/soul_fire_1"));
        return original.call(material);
    }
}
