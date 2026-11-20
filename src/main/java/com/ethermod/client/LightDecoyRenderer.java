package com.ethermod.client;

import com.ethermod.beam.LightDecoy;
import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.Minecraft;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.*;
import net.minecraft.resources.Identifier;

public final class LightDecoyRenderer extends MobRenderer<LightDecoy, PlayerModel<LightDecoy>> {
    private final PlayerModel<LightDecoy> normal, slim;

    public LightDecoyRenderer(EntityRendererProvider.Context c) {
        super(c, new PlayerModel<>(c.bakeLayer(ModelLayers.PLAYER), false), 0);
        normal = model;
        slim = new PlayerModel<>(c.bakeLayer(ModelLayers.PLAYER_SLIM), true);
    }

    @Override
    protected boolean isBodyVisible(LightDecoy entity) {
        return false;
    }

    @Override
    public Identifier getTextureLocation(LightDecoy e) {
        var connection = Minecraft.getInstance().getConnection();
        var info = connection == null ? null : connection.getPlayerInfo(e.owner());
        return info != null
                ? info.getSkin().texture()
                : net.minecraft.client.resources.DefaultPlayerSkin.get(e.owner()).texture();
    }

    @Override
    public void render(
            LightDecoy e,
            float yaw,
            float partial,
            PoseStack pose,
            MultiBufferSource buffer,
            int light) {
        var connection = Minecraft.getInstance().getConnection();
        var info = connection == null ? null : connection.getPlayerInfo(e.owner());
        var skin =
                info != null
                        ? info.getSkin()
                        : net.minecraft.client.resources.DefaultPlayerSkin.get(e.owner());
        model =
                skin.model() == net.minecraft.client.resources.PlayerSkin.Model.SLIM
                        ? slim
                        : normal;
        super.render(e, yaw, partial, pose, buffer, 15728880);
    }
}
