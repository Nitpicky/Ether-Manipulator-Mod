package com.ethermod.loot;

import com.mojang.serialization.Codec;

import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Structures (per dimension) that already gave their guaranteed gem. */
public final class GemGuarantees extends SavedData {
    private final Set<String> claimed = new HashSet<>();

    private static final Codec<GemGuarantees> CODEC =
            Codec.STRING
                    .listOf()
                    .fieldOf("claimed")
                    .codec()
                    .xmap(
                            list -> {
                                var g = new GemGuarantees();
                                g.claimed.addAll(list);
                                return g;
                            },
                            g -> List.copyOf(g.claimed));

    private static final SavedDataType<GemGuarantees> TYPE =
            new SavedDataType<>(
                    Identifier.fromNamespaceAndPath(com.ethermod.EtherMod.MODID, "gem_guarantees"),
                    GemGuarantees::new,
                    CODEC);

    public static GemGuarantees get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(TYPE);
    }

    /** True the first time a key is claimed. */
    public boolean claim(String key) {
        if (!claimed.add(key)) return false;
        setDirty();
        return true;
    }
}
