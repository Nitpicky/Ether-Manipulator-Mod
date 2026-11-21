package com.ethermod.util;

import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

/**
 * 26.3: marking an entity's motion dirty no longer reaches a player's own client, which moves
 * itself; a player pushed by the server gets the new velocity sent directly.
 */
public final class MotionSync {
    private MotionSync() {}

    public static void sync(Entity e) {
        e.needsSync = true;
        if (e instanceof ServerPlayer sp && sp.connection != null)
            sp.connection.send(new ClientboundSetEntityMotionPacket(sp));
    }
}
