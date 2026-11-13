package com.mattermod.beam;

import java.util.HashMap;
import java.util.Map;

/**
 * Client-side set of other players whose beam is on, with the client game time it started (used for
 * the ramp visuals). Filled from BeamStatePayload. Holds no client classes so the payload handler
 * can reference it on both sides.
 */
public final class RemoteBeams {

    private RemoteBeams() {}

    private static final Map<Integer, Long> ACTIVE = new HashMap<>();
    private static long clientTime;

    public static void set(int entityId, boolean active) {
        if (active) ACTIVE.putIfAbsent(entityId, clientTime);
        else ACTIVE.remove(entityId);
    }

    public static Map<Integer, Long> active() {
        return ACTIVE;
    }

    /** Called from the client tick. */
    public static void tick(long gameTime) {
        clientTime = gameTime;
    }

    public static void clear() {
        ACTIVE.clear();
    }
}
