package com.ethermod.client;

import com.ethermod.beam.LightDecoy;
import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.Minecraft;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.MobRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.player.PlayerModelType;
import net.minecraft.world.entity.player.PlayerSkin;

/** Photonic's decoy: the owner's own skin, see-through and full bright. */
public final class LightDecoyRenderer extends MobRenderer<LightDecoy, AvatarRenderState, PlayerModel> {
    private final PlayerModel normal, slim;

    public LightDecoyRenderer(EntityRendererProvider.Context c) {
        super(c, new PlayerModel(c.bakeLayer(ModelLayers.PLAYER), false), 0);
        normal = model;
        slim = new PlayerModel(c.bakeLayer(ModelLayers.PLAYER_SLIM), true);
    }

    private static PlayerSkin skin(LightDecoy e) {
        var connection = Minecraft.getInstance().getConnection();
        var info = connection == null ? null : connection.getPlayerInfo(e.owner());
        return info != null ? info.getSkin() : DefaultPlayerSkin.get(e.owner());
    }

    @Override
    public AvatarRenderState createRenderState() {
        return new AvatarRenderState();
    }

    @Override
    public void extractRenderState(LightDecoy e, AvatarRenderState s, float partialTick) {
        super.extractRenderState(e, s, partialTick);
        s.skin = skin(e);
        s.lightCoords = 15728880;
        s.mainArm = HumanoidArm.RIGHT;
        s.rightArmPose = HumanoidModel.ArmPose.EMPTY;
        s.leftArmPose = HumanoidModel.ArmPose.EMPTY;
        s.showHat = s.showJacket = s.showLeftPants = s.showRightPants = true;
        s.showLeftSleeve = s.showRightSleeve = true;
    }

    @Override
    protected boolean isBodyVisible(AvatarRenderState s) {
        return false;
    }

    @Override
    public Identifier getTextureLocation(AvatarRenderState s) {
        return (s.skin != null ? s.skin : DefaultPlayerSkin.getDefaultSkin()).body().texturePath();
    }

    @Override
    public void submit(AvatarRenderState s, PoseStack pose, SubmitNodeCollector collector, CameraRenderState camera) {
        model = s.skin != null && s.skin.model() == PlayerModelType.SLIM ? slim : normal;
        super.submit(s, pose, collector, camera);
    }
}
