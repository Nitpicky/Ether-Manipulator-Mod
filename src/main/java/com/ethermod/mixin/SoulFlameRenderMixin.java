package com.ethermod.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.feature.FlameFeatureRenderer;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.resources.model.sprite.SpriteId;
import net.minecraft.resources.Identifier;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

/** A mob set alight by Soulwake soul fire burns with the soul fire flames instead of the orange ones. */
@Mixin(FlameFeatureRenderer.class)
public abstract class SoulFlameRenderMixin {
    @Unique
    private static final SpriteId MM_SOUL_0 =
            new SpriteId(TextureAtlas.LOCATION_BLOCKS, Identifier.withDefaultNamespace("block/soul_fire_0"));

    @Unique
    private static final SpriteId MM_SOUL_1 =
            new SpriteId(TextureAtlas.LOCATION_BLOCKS, Identifier.withDefaultNamespace("block/soul_fire_1"));

    @ModifyArgs(
            method = "buildGroup",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/client/renderer/feature/FlameFeatureRenderer;prepare(Lnet/minecraft/client/renderer/feature/FlameFeatureRenderer$Submit;Lcom/mojang/blaze3d/vertex/VertexConsumer;Lnet/minecraft/client/renderer/texture/TextureAtlasSprite;Lnet/minecraft/client/renderer/texture/TextureAtlasSprite;)V"))
    private void mm$soul(Args args) {
        FlameFeatureRenderer.Submit submit = args.get(0);
        var entity = com.ethermod.client.RenderStateEntities.of(submit.entityRenderState());
        if (entity == null || !entity.getPersistentData().getBooleanOr("mm_soul", false)) return;
        var atlas = Minecraft.getInstance().getAtlasManager();
        args.set(2, atlas.get(MM_SOUL_0));
        args.set(3, atlas.get(MM_SOUL_1));
    }
}
