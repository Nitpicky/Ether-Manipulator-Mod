package com.ethermod.client;

import com.ethermod.entity.SoulBolt;
import com.mojang.blaze3d.vertex.*;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.*;
import net.minecraft.client.renderer.entity.*;
import net.minecraft.resources.Identifier;
import net.minecraft.world.inventory.InventoryMenu;

/** Vanilla animated soul-fire sprite gives the small fireball a soul-fire skin. */
public final class SoulBoltRenderer extends EntityRenderer<SoulBolt> {
    public SoulBoltRenderer(EntityRendererProvider.Context context) {
        super(context);
    }

    public Identifier getTextureLocation(SoulBolt entity) {
        return InventoryMenu.BLOCK_ATLAS;
    }

    @Override
    protected int getBlockLightLevel(SoulBolt e, net.minecraft.core.BlockPos pos) {
        return 15;
    }

    @Override
    public void render(
            SoulBolt e,
            float yaw,
            float partial,
            PoseStack pose,
            MultiBufferSource buffers,
            int light) {
        var sprite =
                Minecraft.getInstance()
                        .getTextureAtlas(InventoryMenu.BLOCK_ATLAS)
                        .apply(Identifier.withDefaultNamespace("block/soul_fire_0"));
        pose.pushPose();
        pose.mulPose(entityRenderDispatcher.cameraOrientation());
        pose.scale(.75f, .75f, .75f);
        var v = buffers.getBuffer(RenderType.entityCutoutNoCull(InventoryMenu.BLOCK_ATLAS));
        var matrix = pose.last();
        vertex(v, matrix, -.5f, -.25f, sprite.getU0(), sprite.getV1());
        vertex(v, matrix, .5f, -.25f, sprite.getU1(), sprite.getV1());
        vertex(v, matrix, .5f, .75f, sprite.getU1(), sprite.getV0());
        vertex(v, matrix, -.5f, .75f, sprite.getU0(), sprite.getV0());
        pose.popPose();
        super.render(e, yaw, partial, pose, buffers, light);
    }

    private static void vertex(
            VertexConsumer v, PoseStack.Pose p, float x, float y, float u, float w) {
        v.addVertex(p.pose(), x, y, 0)
                .setColor(-1)
                .setUv(u, w)
                .setOverlay(net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY)
                .setLight(15728880)
                .setNormal(p, 0, 1, 0);
    }
}
