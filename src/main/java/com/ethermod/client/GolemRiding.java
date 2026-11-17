package com.ethermod.client;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Player;

/** Client side of riding the Redstone Golem: the rider's Jump key, read where the ride is simulated. */
public final class GolemRiding {
    private GolemRiding() {}

    public static boolean jumpHeld(Player rider) {
        return rider instanceof LocalPlayer lp && lp.input.jumping;
    }
}
