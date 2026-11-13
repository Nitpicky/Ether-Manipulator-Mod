package com.mattermod.client;

import com.mattermod.MatterMod;
import com.mattermod.item.MatterManipulatorItem;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemDisplayContext;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import software.bernie.geckolib.animation.AnimationState;
import software.bernie.geckolib.constant.DataTickets;
import software.bernie.geckolib.model.GeoModel;

/**
 * GeckoLib model wiring for the Matter Manipulator.
 *
 * <p>Asset paths: geo model → assets/mattermod/geo/matter_manipulator.geo.json texture →
 * assets/mattermod/textures/item/matter_manipulator.png animations →
 * assets/mattermod/animations/matter_manipulator.animation.json (empty file — no keyframed
 * animations needed)
 *
 * <p>The model must be exported from Blockbench as a "GeckoLib Animated Model" (.geo.json format).
 * See GECKOLIB_SETUP.md for Blockbench instructions.
 */
@OnlyIn(Dist.CLIENT)
public class ManipulatorItemModel extends GeoModel<MatterManipulatorItem> {

    @Override
    public ResourceLocation getModelResource(MatterManipulatorItem animatable) {
        return ResourceLocation.fromNamespaceAndPath(
                MatterMod.MODID, "geo/matter_manipulator.geo.json");
    }

    @Override
    public ResourceLocation getTextureResource(MatterManipulatorItem animatable) {
        return ResourceLocation.fromNamespaceAndPath(
                MatterMod.MODID, "textures/item/matter_manipulator.png");
    }

    @Override
    public ResourceLocation getAnimationResource(MatterManipulatorItem animatable) {
        return ResourceLocation.fromNamespaceAndPath(
                MatterMod.MODID, "animations/matter_manipulator.animation.json");
    }

    /**
     * After the keyframes: the ring's inertia, including the Energy charge's spin (see
     * ManipulatorAnimations.ring).
     */
    @Override
    public void setCustomAnimations(
            MatterManipulatorItem animatable,
            long instanceId,
            AnimationState<MatterManipulatorItem> animationState) {
        super.setCustomAnimations(animatable, instanceId, animationState);
        var holder =
                ManipulatorAnimations.holder(animationState.getData(DataTickets.ITEMSTACK));
        // Whole-tool motion (kicks, recoil, inspect turn) is first person only: in
        // third person and on screens the tool stays put in the hand and only its prongs and ring
        // move.
        var ctx = animationState.getData(DataTickets.ITEM_RENDER_PERSPECTIVE);
        boolean firstPerson =
                ctx == ItemDisplayContext.FIRST_PERSON_RIGHT_HAND
                        || ctx == ItemDisplayContext.FIRST_PERSON_LEFT_HAND;
        var body = getAnimationProcessor().getBone("manipulator");
        if (body != null && !firstPerson) {
            body.setPosX(0);
            body.setPosY(0);
            body.setPosZ(0);
            body.setRotX(0);
            body.setRotY(0);
            body.setRotZ(0);
        }
        var ring = getAnimationProcessor().getBone("prongs");
        if (ring == null) return;
        boolean blending = false;
        var manager = animatable.getAnimatableInstanceCache().getManagerForId(instanceId);
        if (manager != null) {
            var main = manager.getAnimationControllers().get("main");
            blending =
                    main != null
                            && main.getAnimationState()
                                    == software.bernie.geckolib.animation.AnimationController
                                            .State.TRANSITIONING;
        }
        float raw = (float) Math.toDegrees(ring.getRotZ());
        float extra = ManipulatorAnimations.ring(holder, raw, blending);
        if (extra != 0) ring.setRotZ(ring.getRotZ() + (float) Math.toRadians(extra));
        // (the ring keeps its own angle: see ManipulatorAnimations.ring)
    }
}
