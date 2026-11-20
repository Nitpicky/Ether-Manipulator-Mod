package com.ethermod.trait;

import com.ethermod.manipulator.Manipulator;
import com.ethermod.network.payload.PhotonicGlowPayload;

import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Tells clients which players give off the Photonic glow: everyone with the Photonic line equipped,
 * summoned or not (a passive, like the other line bonuses). The glow itself is drawn on each
 * client (client.PhotonicGlow); this only keeps the on/off flag in sync.
 */
public final class PhotonicGlowSync {

    private PhotonicGlowSync() {}

    private static final Map<UUID, Boolean> STATE = new HashMap<>();

    public static boolean glows(ServerPlayer p) {
        return !p.isSpectator() && Traits.hasLine(Manipulator.stack(p), Traits.PHOTONIC);
    }

    /** Every 10 ticks: send the flag to the player and their watchers when it changes. */
    public static void tick(ServerPlayer p) {
        boolean on = glows(p);
        Boolean was = STATE.put(p.getUUID(), on);
        if (was == null ? on : was != on)
            PacketDistributor.sendToPlayersTrackingEntityAndSelf(p, new PhotonicGlowPayload(p.getId(), on));
    }

    /** A watcher starts seeing this player. */
    public static void startTracking(ServerPlayer target, ServerPlayer watcher) {
        if (glows(target)) PacketDistributor.sendToPlayer(watcher, new PhotonicGlowPayload(target.getId(), true));
    }

    public static void forget(UUID player) {
        STATE.remove(player);
    }

    public static void clear() {
        STATE.clear();
    }
}
