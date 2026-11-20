package com.ethermod.client;

import com.ethermod.network.payload.ModulePayloads;

import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;

/**
 * Client-side bodies of play-to-client payload handlers that touch client-only classes (Minecraft,
 * ClientLevel). ModNetwork, which a dedicated server also loads, reaches them through
 * {@link com.ethermod.SideHooks}, so it never names a client class itself.
 */
public final class ClientPayloadHandlers {

    private ClientPayloadHandlers() {}

    public static void waterCurrent(ModulePayloads.WaterCurrent p) {
        var mc = Minecraft.getInstance();
        if (mc.level != null)
            com.ethermod.trait.RapidsCurrents.receive(
                    mc.level, p.positions(), new Vec3(p.x(), 0, p.z()), p.expires());
    }

    public static void soulFlame(ModulePayloads.SoulFlame p) {
        var mc = Minecraft.getInstance();
        if (mc.level == null) return;
        var e = mc.level.getEntity(p.entity());
        if (e != null) e.getPersistentData().putBoolean("mm_soul", p.soul());
    }
}
