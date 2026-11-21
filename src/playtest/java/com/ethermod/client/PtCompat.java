package com.ethermod.client;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;

/** 26.3 helpers for the playtest: vanilla entity types and items by id (their fields moved). */
final class PtCompat {
    private PtCompat() {}

    @SuppressWarnings("unchecked")
    static <T extends Entity> EntityType<T> type(String id) {
        return (EntityType<T>) BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.withDefaultNamespace(id));
    }

    static Item item(String id) {
        return BuiltInRegistries.ITEM.getValue(Identifier.parse(id));
    }
}
