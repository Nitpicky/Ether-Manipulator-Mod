package com.ethermod.client;

import com.ethermod.beam.BeamRenderer;
import com.ethermod.beam.BlockGlow;
import com.ethermod.beam.Muzzle;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * Clears client-side render caches whenever the client's level changes (dimension change, joining
 * another server, returning to the title screen). Those caches are keyed by block position or
 * entity id, which mean nothing in the next world, and would otherwise draw ghost glows or beams
 * there.
 */
@OnlyIn(Dist.CLIENT)
public final class ClientCacheReset {

    private ClientCacheReset() {}

    private static ClientLevel lastLevel;

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == lastLevel) return;
        lastLevel = level;
        BlockGlow.clearCaches();
        BeamRenderer.clearCaches();
        Muzzle.clearCaches();
        com.ethermod.beam.RemoteBeams.clear(); // keyed by entity id, meaningless in the next level
    }
}
