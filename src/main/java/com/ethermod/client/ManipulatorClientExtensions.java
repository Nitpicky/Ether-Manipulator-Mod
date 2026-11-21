package com.ethermod.client;

import com.ethermod.client.event.ClientInputHandler;

import net.minecraft.client.model.HumanoidModel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.client.extensions.common.IClientItemExtensions;

/**
 * Client item extensions for Ether Manipulator. Provides the bow-draw arm pose (the GeckoLib renderer comes from EtherManipulatorItem.createGeoRenderer)
 * (no applyForgeHandTransform).
 */
@OnlyIn(Dist.CLIENT)
public class ManipulatorClientExtensions implements IClientItemExtensions {

    public static final ManipulatorClientExtensions INSTANCE = new ManipulatorClientExtensions();
    @Override
    public HumanoidModel.ArmPose getArmPose(
            LivingEntity entity, InteractionHand hand, ItemStack stack) {
        if (entity.isUsingItem() && entity.getUsedItemHand() == hand) {
            return HumanoidModel.ArmPose.BOW_AND_ARROW;
        }
        var mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.player != null && mc.player == entity && ClientInputHandler.jiggleActive) {
            return HumanoidModel.ArmPose.BOW_AND_ARROW;
        }
        // every other use (left clicks of each mode, a hook out, a carried block, a grenade...)
        if (mc.player == entity && com.ethermod.client.beam.BeamRenderState.leftMode != null)
            return HumanoidModel.ArmPose.BOW_AND_ARROW;
        if (entity instanceof net.minecraft.world.entity.player.Player p
                && ManipulatorAnimations.aiming(p)) return HumanoidModel.ArmPose.BOW_AND_ARROW;
        return HumanoidModel.ArmPose.ITEM;
    }
}
