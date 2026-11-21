package com.ethermod.client;

import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.world.entity.Entity;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * 26.3 draws entities from render states taken earlier in the frame, without the entity. The mod's
 * layers still need it (Stasis's hold, a player's gravity), so each state remembers the entity it
 * was last filled from (RenderStateEntityMixin).
 */
public final class RenderStateEntities {
    private RenderStateEntities() {}

    private static final Map<EntityRenderState, Entity> FROM = new WeakHashMap<>();

    public static void filled(EntityRenderState state, Entity entity) {
        FROM.put(state, entity);
    }

    /** The entity a state was last filled from, or null. */
    public static Entity of(EntityRenderState state) {
        return FROM.get(state);
    }
}
