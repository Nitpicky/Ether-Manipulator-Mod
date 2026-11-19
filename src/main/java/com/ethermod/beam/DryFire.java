package com.ethermod.beam;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * A shot the tank cannot pay for: no message, the shooter's client beeps, flashes the Ether
 * counter a brighter red and puffs a little smoke from the emitter. Spaced so a held click
 * does not stutter.
 */
public final class DryFire {

    private DryFire() {}

    private static final Map<UUID, Long> LAST = new HashMap<>();

    public static void signal(ServerPlayer p) {
        long now = p.level().getGameTime();
        Long last = LAST.get(p.getUUID());
        if (last != null && now - last < 5 && now >= last) return;
        LAST.put(p.getUUID(), now);
        PacketDistributor.sendToPlayer(p, com.ethermod.network.payload.DryFirePayload.INSTANCE);
    }
}
