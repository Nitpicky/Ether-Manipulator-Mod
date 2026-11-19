package com.ethermod.mixin;

import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.AdvancementTree;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;

/**
 * Lays out the Ether Manipulator advancement tab in a fixed order. Vanilla adds advancements in
 * hash-map order, and the tree layout puts a parent's first child on its own row (the straight
 * line from the root) and the rest below, so the tab came out shuffled. Our entries are sorted
 * by {@link #ORDER}: the upgrade path first (the straight line), then the gems, each with its
 * Module's challenge, then the default Modules' challenges, then dyes at the bottom.
 */
@Mixin(AdvancementTree.class)
public abstract class AdvancementOrderMixin {
    private static final List<String> ORDER =
            List.of(
                    "calibrated", "augmented", "overclocked",
                    "sirenite", "gehennite", "stratite", "zephyrite", "quetzalite", "atenite", "oculite",
                    "let_it_go", "ping_pong",
                    "dye", "spectrum");

    @ModifyVariable(method = "addAll", at = @At("HEAD"), argsOnly = true)
    private Collection<AdvancementHolder> mm$order(Collection<AdvancementHolder> all) {
        List<AdvancementHolder> sorted = new ArrayList<>(all);
        sorted.sort(Comparator.comparingInt(AdvancementOrderMixin::mm$rank)); // stable: others keep their order
        return sorted;
    }

    private static int mm$rank(AdvancementHolder h) {
        var id = h.id();
        if (!"ethermod".equals(id.getNamespace()) || !id.getPath().startsWith("manipulator/")) return -1;
        int i = ORDER.indexOf(id.getPath().substring("manipulator/".length()));
        return i < 0 ? ORDER.size() : i;
    }
}
