package com.ethermod.trait;

import com.ethermod.manipulator.Manipulator;

import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

/**
 * The virtual hand: vanilla's harvest check and break speed read the selected hotbar item, not
 * the main hand, so while the Manipulator is out they would judge whatever sits in that slot
 * (often nothing, so no drops). Judge the Manipulator instead.
 */
public final class VirtualHand {
    private VirtualHand() {}

    @SubscribeEvent
    public static void harvest(PlayerEvent.HarvestCheck ev) {
        var p = ev.getEntity();
        if (!Manipulator.isSummoned(p)) return;
        ItemStack mm = p.getMainHandItem();
        if (!(mm.getItem() instanceof com.ethermod.item.EtherManipulatorItem)) return;
        var state = ev.getTargetBlock();
        ev.setCanHarvest(!state.requiresCorrectToolForDrops() || mm.isCorrectToolForDrops(state));
    }

    @SubscribeEvent(priority = net.neoforged.bus.api.EventPriority.HIGHEST)
    public static void virtualHandSpeed(PlayerEvent.BreakSpeed ev) {
        var p = ev.getEntity();
        if (!Manipulator.isSummoned(p)) return;
        ItemStack mm = p.getMainHandItem();
        if (!(mm.getItem() instanceof com.ethermod.item.EtherManipulatorItem)) return;
        float selected = Math.max(1f, p.getInventory().getSelectedItem().getDestroySpeed(ev.getState()));
        ev.setNewSpeed(ev.getNewSpeed() / selected * mm.getDestroySpeed(ev.getState()));
    }
}
