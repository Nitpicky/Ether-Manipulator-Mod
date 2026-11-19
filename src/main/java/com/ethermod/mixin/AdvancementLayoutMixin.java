package com.ethermod.mixin;

import net.minecraft.advancements.AdvancementNode;
import net.minecraft.advancements.TreeNodePosition;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * Lays out the Ether Manipulator advancement tab by hand (vanilla's automatic layout shuffles
 * it and centres the root between its first and last branch): the gems and the default Modules'
 * challenges on top, each gem with its Module's challenge beside it; the upgrade path as the
 * straight line from the root; dyes at the bottom.
 */
@Mixin(TreeNodePosition.class)
public abstract class AdvancementLayoutMixin {
    /** Top rows: {advancement, its child on the same row (or null)}. */
    @Unique private static final String[][] TOP = {
        // the default Modules first, as in the Module order
        {"let_it_go", null},
        {"ping_pong", null},
        {"sirenite", "rain_check"},
        {"gehennite", "this_is_fine"},
        {"stratite", "return_to_sender"},
        {"zephyrite", "gone_with_the_wind"},
        {"quetzalite", "green_thumb"},
        {"atenite", "night_light"},
        {"oculite", "fish_out_of_water"}
    };

    @Unique private static final List<String> LINE = List.of("calibrated", "augmented", "overclocked");

    @Inject(method = "run", at = @At("TAIL"))
    private static void mm$layout(AdvancementNode root, CallbackInfo ci) {
        var id = root.holder().id();
        if (!"ethermod".equals(id.getNamespace()) || !"manipulator/root".equals(id.getPath())) return;
        int line = TOP.length;
        place(root, "root", 0, line);
        for (int i = 0; i < LINE.size(); i++) place(root, LINE.get(i), i + 1, line);
        for (int row = 0; row < TOP.length; row++) {
            place(root, TOP[row][0], 1, row);
            if (TOP[row][1] != null) place(root, TOP[row][1], 2, row);
        }
        place(root, "dye", 1, line + 1);
        place(root, "spectrum", 2, line + 1);
    }

    @Unique
    private static void place(AdvancementNode root, String name, int x, int y) {
        var node = find(root, "manipulator/" + name);
        if (node != null) node.holder().value().display().ifPresent(d -> d.setLocation(x, y));
    }

    @Unique
    private static AdvancementNode find(AdvancementNode n, String path) {
        if (n.holder().id().getPath().equals(path) && "ethermod".equals(n.holder().id().getNamespace())) return n;
        for (var c : n.children()) {
            var f = find(c, path);
            if (f != null) return f;
        }
        return null;
    }
}
