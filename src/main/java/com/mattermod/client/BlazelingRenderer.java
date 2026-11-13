package com.mattermod.client;

import com.mattermod.entity.SoulBlazeling;

import net.minecraft.client.renderer.entity.*;
import net.minecraft.resources.ResourceLocation;

public final class BlazelingRenderer extends MobRenderer<SoulBlazeling, BlazelingModel> {
    private static final ResourceLocation TEXTURE =
            ResourceLocation.fromNamespaceAndPath(
                    "mattermod", "textures/entity/soul_blazeling.png");

    public BlazelingRenderer(EntityRendererProvider.Context context) {
        super(context, new BlazelingModel(context.bakeLayer(BlazelingModel.LAYER)), .25f);
    }

    public ResourceLocation getTextureLocation(SoulBlazeling entity) {
        return TEXTURE;
    }

    @Override
    protected int getBlockLightLevel(SoulBlazeling entity, net.minecraft.core.BlockPos pos) {
        return 15;
    }
}
