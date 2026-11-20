package com.ethermod.trait;

import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.*;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

import java.util.*;

/** Permanent knowledge belongs to the player's own Manipulator, not a saved Set. */
public final class ModuleUnlocks {
    private static final String KEY = "mm_known_modules";

    private ModuleUnlocks() {}

    public static boolean known(ItemStack stack, Identifier id) {
        if (id == null) return false;
        if (id.equals(Traits.PLASMIC) || id.equals(Traits.KINETIC)) return true;
        if (com.ethermod.config.ServerConfig.moduleDisabled(id.getPath())) return false;
        if (com.ethermod.config.ServerConfig.allModules()) return true;
        var tag = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        var list = tag.getListOrEmpty(KEY);
        for (int i = 0; i < list.size(); i++)
            if (list.getStringOr(i, "").equals(id.toString())) return true;
        return false;
    }

    public static boolean unlock(ItemStack stack, Identifier id) {
        if (known(stack, id)) return false;
        var tag = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        var list = tag.getListOrEmpty(KEY);
        list.add(StringTag.valueOf(id.toString()));
        tag.put(KEY, list);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        return true;
    }
}
