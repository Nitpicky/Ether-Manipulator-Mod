package com.ethermod.trait;

import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

/**
 * Event driver for the short-lived world effects ({@link TempLights}, {@link Scheduler}, {@link
 * OwnedFire}, {@link TempBlocks}). One subscriber keeps the per-tick and shutdown order fixed.
 */
public final class WorldEffects {

    private WorldEffects() {}

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        long now = level.getGameTime();
        TempLights.tick(level, now);
        Scheduler.tick(level, now);
        OwnedFire.tick(level, now);
        TempBlocks.tick(level, now);
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        TempLights.clearOnStop();
        Scheduler.runOnStopAndClear();
        OwnedFire.clear();
        Ziplines.clear();
        TempBlocks.restoreOnStop();
        // everything above was put back, so nothing is pending for the next start
        for (ServerLevel level : event.getServer().getAllLevels()) PendingReverts.clear(level);
    }
}
