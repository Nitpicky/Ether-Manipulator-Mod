package com.ethermod.client;

import com.ethermod.entity.SoulBlazeling;

import net.minecraft.client.renderer.entity.*;
import net.minecraft.resources.Identifier;

public final class BlazelingRenderer extends MobRenderer<SoulBlazeling, BlazelingModel> {
    private static final Identifier TEXTURE =
            Identifier.fromNamespaceAndPath(
                    "ethermod", "textures/entity/soul_blazeling.png");

    public BlazelingRenderer(EntityRendererProvider.Context context) {
        super(context, new BlazelingModel(context.bakeLayer(BlazelingModel.LAYER)), .25f);
    }

    public Identifier getTextureLocation(SoulBlazeling entity) {
        return TEXTURE;
    }

    @Override
    protected int getBlockLightLevel(SoulBlazeling entity, net.minecraft.core.BlockPos pos) {
        return 15;
    }
}
