package com.ethermod.event;

import com.ethermod.item.EtherManipulatorItem;
import com.ethermod.manipulator.Manipulator;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.animal.allay.Allay;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.DecoratedPotBlock;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/**
 * While summoned, the Manipulator is the main-hand stack (PlayerMixin), so vanilla interactions
 * that take the held item would take the Manipulator itself: an empty item frame, an allay, an
 * armor stand, a decorated pot. Those are refused with the Manipulator in the main hand; the
 * off hand still works as usual.
 */
public final class VirtualHandGuard {

    private VirtualHandGuard() {}

    private static boolean holdsSummoned(Player player, InteractionHand hand, ItemStack stack) {
        return hand == InteractionHand.MAIN_HAND
                && stack.getItem() instanceof EtherManipulatorItem
                && Manipulator.isSummoned(player);
    }

    @SubscribeEvent
    public static void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        if (!holdsSummoned(event.getEntity(), event.getHand(), event.getItemStack())) return;
        var target = event.getTarget();
        if (target instanceof ItemFrame frame && frame.getItem().isEmpty()
                || target instanceof Allay
                || target instanceof ArmorStand) event.setCanceled(true);
    }

    /** Armor stands take the held item through interactAt. */
    @SubscribeEvent
    public static void onEntityInteractSpecific(PlayerInteractEvent.EntityInteractSpecific event) {
        if (holdsSummoned(event.getEntity(), event.getHand(), event.getItemStack())
                && event.getTarget() instanceof ArmorStand) event.setCanceled(true);
    }

    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (holdsSummoned(event.getEntity(), event.getHand(), event.getItemStack())
                && event.getLevel().getBlockState(event.getPos()).getBlock()
                        instanceof DecoratedPotBlock) event.setCanceled(true);
    }
}
