package com.ethermod.manipulator;

import net.minecraft.world.entity.player.Player;

/**
 * Hides the summoned Manipulator from block and entity interactions: inside a veil the player's
 * main hand reads as empty (PlayerMixin), so a block's or entity's interaction code runs exactly as
 * for an empty hand and never sees, takes or stores the Manipulator. Item frames, allays, armor
 * stands, pots and modded pedestals or display cases all fall under it without a list.
 *
 * <p>Veils are opened around BlockState#useWithoutItem (ManipulatorBlockUseMixin) and
 * Player#interactOn (PlayerMixin); BlockState#useItemOn never receives the Manipulator at all.
 * Per thread, so the client and the integrated server don't see each other's veils, and keyed to
 * the player object, so no other entity is affected.
 */
public final class ManipulatorVeil {

    private ManipulatorVeil() {}

    private static final ThreadLocal<Player> VEILED = new ThreadLocal<>();

    /** Opens a veil for {@code player}; returns what to hand back to {@link #close}. */
    public static Player open(Player player) {
        Player previous = VEILED.get();
        if (Manipulator.isSummoned(player)) VEILED.set(player);
        return previous;
    }

    public static void close(Player previous) {
        if (previous == null) VEILED.remove();
        else VEILED.set(previous);
    }

    /** Whether {@code player}'s main hand currently reads as empty. */
    public static boolean veiled(Player player) {
        return VEILED.get() == player;
    }
}
