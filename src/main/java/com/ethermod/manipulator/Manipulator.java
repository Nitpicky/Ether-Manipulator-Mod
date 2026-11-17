package com.ethermod.manipulator;

import com.ethermod.network.payload.ManipulatorSyncPayload;
import com.ethermod.registry.ModAttachments;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

import software.bernie.geckolib.animatable.GeoItem;

/**
 * Entry point for the player's Manipulator: it lives in its own slot (a data attachment), is given
 * at start, and can never be lost.
 *
 * <p>"Summoned" means the virtual hand is active: PlayerMixin makes getItemBySlot(MAINHAND) return
 * the Manipulator, so vanilla mining, attacking, item use and hand rendering all see it, while the
 * real hotbar item stays untouched in the inventory.
 */
public final class Manipulator {

    private Manipulator() {}

    public static ManipulatorData data(Player player) {
        return player.getData(ModAttachments.MANIPULATOR);
    }

    /** The player's Manipulator stack (never empty). */
    public static ItemStack stack(Player player) {
        return data(player).stack();
    }

    /** True while the Manipulator is in the player's hand. Safe during construction. */
    public static boolean isSummoned(Player player) {
        return player.hasData(ModAttachments.MANIPULATOR) && data(player).summoned();
    }

    /** Server-side: summon or put away, then sync to the owner. */
    public static void setSummoned(ServerPlayer player, boolean summoned) {
        data(player).setSummoned(summoned);
        // Give the stack its own GeckoLib animation id, so each player's prongs animate on their own
        if (summoned) GeoItem.getOrAssignId(data(player).stack(), player.serverLevel());
        if (summoned) com.ethermod.advancement.ManipulatorTrigger.fire(player, "summon");
        sync(player);
    }

    /** Server-side: send the owner their Manipulator state. Call after any change to the stack. */
    public static void sync(ServerPlayer player) {
        ManipulatorData d = data(player);
        PacketDistributor.sendToPlayer(
                player, new ManipulatorSyncPayload(d.summoned(), d.stack().copy()));
    }
}
