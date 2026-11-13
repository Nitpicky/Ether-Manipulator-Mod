package com.mattermod.client;

import com.mattermod.MatterMod;
import com.mattermod.entity.Ministrosity;

import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;

import software.bernie.geckolib.model.DefaultedEntityGeoModel;
import software.bernie.geckolib.renderer.GeoEntityRenderer;
import software.bernie.geckolib.renderer.layer.AutoGlowingGeoLayer;

/** Ministrosity: the Golem's little helper, eyes glowing through ministrosity_glowmask.png. */
public final class MinistrosityRenderer extends GeoEntityRenderer<Ministrosity> {

    public MinistrosityRenderer(EntityRendererProvider.Context ctx) {
        super(ctx, new DefaultedEntityGeoModel<>(ResourceLocation.fromNamespaceAndPath(MatterMod.MODID, "ministrosity")));
        addRenderLayer(new AutoGlowingGeoLayer<>(this));
        addRenderLayer(new EyePulseLayer<>(this, "ministrosity_eyes.png", 30));
        shadowRadius = .4f;
    }
}
