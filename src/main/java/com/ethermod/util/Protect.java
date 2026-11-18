package com.ethermod.util;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.BlockEvent;

/**
 * May the Manipulator change this block for this player? Spawn protection and world borders
 * (vanilla's mayInteract), then, when the server keeps respect_protection on, the same test
 * claim mods answer for any block-changing tool: a break event that they may cancel. Our own
 * break listeners skip these probes ({@link #probing}).
 */
public final class Protect {

    private Protect() {}

    public static boolean probing;

    public static boolean may(LevelAccessor level, Player p, BlockPos pos) {
        if (!level.mayInteract(p, pos)) return false;
        if (p == null || !(level instanceof Level l) || l.isClientSide()
                || !com.ethermod.config.ServerConfig.respectProtection()) return true;
        if (probing) return true;
        probing = true;
        try {
            var ev = new BlockEvent.BreakEvent(l, pos, l.getBlockState(pos), p);
            return !NeoForge.EVENT_BUS.post(ev).isCanceled();
        } finally {
            probing = false;
        }
    }
}
