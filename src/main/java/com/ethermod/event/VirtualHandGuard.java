package com.ethermod.event;

import com.ethermod.item.EtherManipulatorItem;
import com.ethermod.manipulator.Manipulator;

import net.neoforged.bus.api.SubscribeEvent;

/**
 * While summoned, the Manipulator is the main-hand stack (PlayerMixin). Block use and
 * Player#interactOn see an empty hand (ManipulatorBlockUseMixin, ManipulatorVeil); the one entity path
 * left is Entity#interactAt (armor stands, and any modded entity that overrides it), which vanilla
 * calls directly rather than through Player#interactOn. It is refused for the Manipulator hand on
 * both sides, for every entity: the result is PASS, so vanilla goes on to the plain interaction and
 * then to the Manipulator's own right click. The off hand still works as usual.
 */
public final class VirtualHandGuard {

    private VirtualHandGuard() {}

    /**
     * A Manipulator item entity is never the player's Manipulator (that lives in its own slot): a
     * copy split off the virtual hand by some other mod, or an old drop. Like the inventory copies
     * EtherManipulatorItem#inventoryTick deletes, it never enters a server level.
     */
    @SubscribeEvent
    public static void onStrayManipulatorDrop(
            net.neoforged.neoforge.event.entity.EntityJoinLevelEvent event) {
        if (!event.getLevel().isClientSide()
                && event.getEntity() instanceof net.minecraft.world.entity.item.ItemEntity drop
                && drop.getItem().getItem() instanceof EtherManipulatorItem) event.setCanceled(true);
    }
}
