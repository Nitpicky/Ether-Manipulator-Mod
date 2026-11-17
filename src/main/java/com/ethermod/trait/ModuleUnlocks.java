package com.ethermod.trait;

import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

import java.util.*;

/** Permanent knowledge belongs to the player's own Manipulator, not a saved Set. */
public final class ModuleUnlocks {
    private static final String KEY = "mm_known_modules";

    private ModuleUnlocks() {}

    public static boolean known(ItemStack stack, ResourceLocation id) {
        if (id == null) return false;
        if (id.equals(Traits.PLASMIC) || id.equals(Traits.KINETIC)) return true;
        var tag = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).getUnsafe();
        var list = tag.getList(KEY, Tag.TAG_STRING);
        for (int i = 0; i < list.size(); i++)
            if (list.getString(i).equals(id.toString())) return true;
        return false;
    }

    public static boolean unlock(ItemStack stack, ResourceLocation id) {
        if (known(stack, id)) return false;
        var tag = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        var list = tag.getList(KEY, Tag.TAG_STRING);
        list.add(StringTag.valueOf(id.toString()));
        tag.put(KEY, list);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        return true;
    }
}
