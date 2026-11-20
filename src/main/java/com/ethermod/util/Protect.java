package com.ethermod.util;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.block.BreakBlockEvent;

/**
 * May the Manipulator change this block for this player? Spawn protection and world borders
 * (vanilla's mayInteract), then, when the server keeps respect_protection on, the same test
 * claim mods answer for any block-changing tool: a break event that they may cancel. Our own
 * break listeners skip these probes ({@link #probing}).
 *
 * <p>NeoForge 21.1 has no dedicated "may this player edit here" event, so the probe is a
 * synthetic {@link BreakBlockEvent}. Grief loggers, quest and statistics mods can't tell
 * it from a real break, so callers probe only right before they actually change the block, and
 * a repeat probe of the same block by the same player in the same tick is answered from a
 * cache instead of being posted again. Where a real break follows anyway (the player's own
 * destroyBlock posts the event itself), use {@link #mayInteract} and let that event decide.
 */
public final class Protect {

    private Protect() {}

    public static boolean probing;

    private record Key(UUID player, long pos, BlockState state) {}

    /** This tick's probe answers, for the level they were asked in. */
    private static final Map<Key, Boolean> CACHE = new HashMap<>();
    private static Level cacheLevel;
    private static long cacheTick = Long.MIN_VALUE;

    /** Vanilla's own checks only (spawn protection, world border): never posts an event. */
    public static boolean mayInteract(LevelAccessor level, Player p, BlockPos pos) {
        return !(level instanceof Level l) || p == null || l.mayInteract(p, pos);
    }

    public static boolean may(LevelAccessor level, Player p, BlockPos pos) {
        if (!(level instanceof Level l)) return true;
        if (p != null && !l.mayInteract(p, pos)) return false;
        if (p == null || l.isClientSide()
                || !com.ethermod.config.ServerConfig.respectProtection()) return true;
        if (probing) return true;
        BlockState state = l.getBlockState(pos);
        long now = l.getGameTime();
        if (cacheLevel != l || cacheTick != now) {
            CACHE.clear();
            cacheLevel = l;
            cacheTick = now;
        }
        var key = new Key(p.getUUID(), pos.asLong(), state);
        Boolean known = CACHE.get(key);
        if (known != null) return known;
        boolean allowed;
        probing = true;
        try {
            allowed = !NeoForge.EVENT_BUS.post(new BreakBlockEvent(l, pos, state, p)).isCanceled();
        } finally {
            probing = false;
        }
        CACHE.put(key, allowed);
        return allowed;
    }

    /** Server stop: drop the cache (and the level it points at). */
    public static void clear() {
        CACHE.clear();
        cacheLevel = null;
        cacheTick = Long.MIN_VALUE;
    }
}
