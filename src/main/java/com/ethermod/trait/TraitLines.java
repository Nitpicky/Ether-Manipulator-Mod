package com.ethermod.trait;

import com.ethermod.EtherMod;

import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.Identifier;
import net.neoforged.neoforge.registries.DataPackRegistryEvent;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** The datapack registry of trait lines. Synced to clients by NeoForge. */
public final class TraitLines {

    private TraitLines() {}

    public static final ResourceKey<Registry<TraitLine>> KEY =
            ResourceKey.createRegistryKey(
                    Identifier.fromNamespaceAndPath(EtherMod.MODID, "trait_line"));

    public static void onNewRegistry(DataPackRegistryEvent.NewRegistry event) {
        event.dataPackRegistry(KEY, TraitLine.CODEC, TraitLine.CODEC);
    }

    public static Optional<TraitLine> get(RegistryAccess access, Identifier id) {
        return access.registry(KEY).flatMap(r -> r.getOptional(id));
    }

    /** All lines, in display order. */
    public static List<Map.Entry<Identifier, TraitLine>> all(RegistryAccess access) {
        return access.registry(KEY)
                .map(
                        r ->
                                r.entrySet().stream()
                                        .map(e -> Map.entry(e.getKey().location(), e.getValue()))
                                        .sorted(
                                                Comparator.comparingInt(
                                                                (Map.Entry<
                                                                                        Identifier,
                                                                                        TraitLine>
                                                                                e) ->
                                                                        displayOrder(
                                                                                e.getKey(),
                                                                                e.getValue()
                                                                                        .order()))
                                                        .thenComparing(e -> e.getKey().toString()))
                                        .toList())
                .orElse(List.of());
    }

    private static int displayOrder(Identifier id, int fallback) {
        int index =
                List.of(
                                "plasmic",
                                "kinetic",
                                "hydraulic",
                                "pyrotechnic",
                                "tectonic",
                                "pneumatic",
                                "botanic",
                                "photonic",
                                "gravitic")
                        .indexOf(id.getPath());
        return index < 0 ? 100 + fallback : index;
    }
}
