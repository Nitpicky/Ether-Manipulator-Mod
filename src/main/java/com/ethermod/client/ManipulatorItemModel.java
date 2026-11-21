package com.ethermod.client;

import com.ethermod.EtherMod;
import com.ethermod.item.EtherManipulatorItem;

import net.minecraft.resources.Identifier;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import com.geckolib.model.GeoModel;
import com.geckolib.renderer.base.GeoRenderState;

/**
 * GeckoLib model wiring for the Ether Manipulator: geckolib/models/ether_manipulator.geo.json,
 * geckolib/animations/ether_manipulator.animation.json. The texture is chosen per stack by
 * {@link ManipulatorItemRenderer} (the body colour, see {@link BodyTextures}).
 */
@OnlyIn(Dist.CLIENT)
public class ManipulatorItemModel extends GeoModel<EtherManipulatorItem> {

    private static final Identifier MODEL =
            Identifier.fromNamespaceAndPath(EtherMod.MODID, "ether_manipulator");
    private static final Identifier ANIMATIONS =
            Identifier.fromNamespaceAndPath(EtherMod.MODID, "ether_manipulator");

    @Override
    public Identifier getModelResource(GeoRenderState state) {
        return MODEL;
    }

    @Override
    public Identifier getTextureResource(GeoRenderState state) {
        return BodyTextures.BASE;
    }

    @Override
    public Identifier getAnimationResource(EtherManipulatorItem animatable) {
        return ANIMATIONS;
    }
}
