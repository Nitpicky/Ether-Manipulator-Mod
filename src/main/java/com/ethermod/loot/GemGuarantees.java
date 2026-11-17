package com.ethermod.loot;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.HashSet;
import java.util.Set;

/** Structures (per dimension) that already gave their guaranteed gem. */
public final class GemGuarantees extends SavedData {
    private static final String NAME = "ethermod_gem_guarantees";
    private final Set<String> claimed = new HashSet<>();

    public static GemGuarantees get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(new SavedData.Factory<>(GemGuarantees::new, GemGuarantees::load, null), NAME);
    }

    private static GemGuarantees load(CompoundTag tag, HolderLookup.Provider registries) {
        var g = new GemGuarantees();
        var list = tag.getList("claimed", Tag.TAG_STRING);
        for (int i = 0; i < list.size(); i++) g.claimed.add(list.getString(i));
        return g;
    }

    /** True the first time a key is claimed. */
    public boolean claim(String key) {
        if (!claimed.add(key)) return false;
        setDirty();
        return true;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        var list = new ListTag();
        for (String s : claimed) list.add(StringTag.valueOf(s));
        tag.put("claimed", list);
        return tag;
    }
}
