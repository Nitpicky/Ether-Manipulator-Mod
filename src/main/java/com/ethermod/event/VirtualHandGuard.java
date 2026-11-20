package com.ethermod.event;

import com.ethermod.item.EtherManipulatorItem;
import com.ethermod.manipulator.Manipulator;

import net.minecraft.world.InteractionHand;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/**
 * While summoned, the Manipulator is the main-hand stack (PlayerMixin). Block use and
 * Player#interactOn never see it (ManipulatorBlockUseMixin, ManipulatorVeil); the one entity path
 * left is Entity#interactAt (armor stands, and any modded entity that overrides it), which vanilla
 * calls directly rather than through Player#interactOn. It is refused for the Manipulator hand on
 * both sides, for every entity: the result is PASS, so vanilla goes on to the plain interaction and
 * then to the Manipulator's own right click. The off hand still works as usual.
 */
public final class VirtualHandGuard {

    private VirtualHandGuard() {}

    @SubscribeEvent
    public static void onEntityInteractSpecific(PlayerInteractEvent.EntityInteractSpecific event) {
        if (event.getHand() == InteractionHand.MAIN_HAND
                && event.getItemStack().getItem() instanceof EtherManipulatorItem
                && Manipulator.isSummoned(event.getEntity())) {
            event.setCanceled(true);
        }
    }
}
