package com.ethermod.client;

import com.ethermod.EtherMod;
import com.ethermod.item.EtherManipulatorItem;

import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemDisplayContext;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import com.geckolib.animation.state.AnimationTest;
import com.geckolib.constant.DataTickets;
import com.geckolib.model.GeoModel;

/**
 * GeckoLib model wiring for the Ether Manipulator.
 *
 * <p>Asset paths: geo model → assets/ethermod/geo/ether_manipulator.geo.json texture →
 * assets/ethermod/textures/item/ether_manipulator.png animations →
 * assets/ethermod/animations/ether_manipulator.animation.json (empty file — no keyframed
 * animations needed)
 *
 * <p>The model must be exported from Blockbench as a "GeckoLib Animated Model" (.geo.json format).
 * See GECKOLIB_SETUP.md for Blockbench instructions.
 */
@OnlyIn(Dist.CLIENT)
public class ManipulatorItemModel extends GeoModel<EtherManipulatorItem> {

    @Override
    public Identifier getModelResource(EtherManipulatorItem animatable) {
        return Identifier.fromNamespaceAndPath(
                EtherMod.MODID, "geo/ether_manipulator.geo.json");
    }

    @Override
    public Identifier getTextureResource(EtherManipulatorItem animatable) {
        return Identifier.fromNamespaceAndPath(
                EtherMod.MODID, "textures/item/ether_manipulator.png");
    }

    @Override
    public Identifier getAnimationResource(EtherManipulatorItem animatable) {
        return Identifier.fromNamespaceAndPath(
                EtherMod.MODID, "animations/ether_manipulator.animation.json");
    }

    /**
     * After the keyframes: the ring's inertia, including the Energy charge's spin (see
     * ManipulatorAnimations.ring).
     */
    @Override
    public void setCustomAnimations(
            EtherManipulatorItem animatable,
            long instanceId,
            AnimationTest<EtherManipulatorItem> animationState) {
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
        // the ring's inertia belongs to a tool in a hand: an icon (a creative tab, a slot, an item frame) rendering in the
        // same frame would feed the holder's ring a second motor angle and set both spinning erratically
        boolean inHand = firstPerson
                || ctx == ItemDisplayContext.THIRD_PERSON_RIGHT_HAND
                || ctx == ItemDisplayContext.THIRD_PERSON_LEFT_HAND;
        if (!inHand) return;
        boolean blending = false;
        var manager = animatable.getAnimatableInstanceCache().getManagerForId(instanceId);
        if (manager != null) {
            var main = manager.getAnimationControllers().get("main");
            blending =
                    main != null
                            && main.getAnimationState()
                                    == com.geckolib.animation.AnimationController
                                            .State.TRANSITIONING;
        }
        float raw = (float) Math.toDegrees(ring.getRotZ());
        float extra = ManipulatorAnimations.ring(holder, raw, blending);
        if (extra != 0) ring.setRotZ(ring.getRotZ() + (float) Math.toRadians(extra));
        // (the ring keeps its own angle: see ManipulatorAnimations.ring)
    }
}
