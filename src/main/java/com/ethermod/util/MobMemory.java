package com.ethermod.util;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Entity;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Server-side, in-memory scratch data for entities: short-lived combat state (beam pressure,
 * Mass Driver hit cooldown, burn owner, Radiance, Stasis force) that used to be written to the
 * entity's persistent data and so was saved with every mob ever hit. Entries go when the entity
 * is garbage collected and on server stop. Anything that must survive a restart (summon owner and
 * expiry, Stasis restore flags, pulled items) stays in persistent data.
 */
public final class MobMemory {

    private MobMemory() {}

    private static final Map<Entity, CompoundTag> DATA =
            Collections.synchronizedMap(new WeakHashMap<>());

    /** Old versions saved these on entities; they are dropped when an entity joins a level. */
    private static final String[] OLD_KEYS = {
        "mm_combat_pressure",
        "mm_mass_hit",
        "mm_burn_owner",
        "mm_flashover",
        "mm_radiance_power",
        "mm_radiance_next",
        "mm_stasis_force",
        "mm_stasis_cooldown",
        "mm_stasis_x",
        "mm_stasis_y",
        "mm_stasis_z",
        "mm_stasis_ex",
        "mm_stasis_ey",
        "mm_stasis_ez"
    };

    /** The entity's scratch data, created on first use. Server side only. */
    public static CompoundTag of(Entity e) {
        return DATA.computeIfAbsent(e, k -> new CompoundTag());
    }

    /** The entity's scratch data for reading only: an empty tag when it has none. */
    public static CompoundTag peek(Entity e) {
        CompoundTag t = DATA.get(e);
        return t != null ? t : new CompoundTag();
    }

    public static void forget(Entity e) {
        DATA.remove(e);
    }

    /** Removes the transient keys older versions saved on the entity. */
    public static void tidy(Entity e) {
        CompoundTag d = e.getPersistentData();
        if (d.isEmpty()) return;
        for (String k : OLD_KEYS) d.remove(k);
    }

    public static void clear() {
        DATA.clear();
    }
}
