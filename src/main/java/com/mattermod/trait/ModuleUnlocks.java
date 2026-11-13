package com.mattermod.trait;

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
        if (id.equals(Traits.COMBAT) || id.equals(Traits.ORB)) return true;
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

    public static void migrate(ItemStack stack, net.minecraft.core.RegistryAccess registry) {
        var tag = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        if (tag.getInt("mm_redesign_version") >= 1) return;
        var backup = new CompoundTag();
        backup.put("build", tag.getList("mm_traits", Tag.TAG_COMPOUND).copy());
        backup.put("sets", tag.getList("mm_loadouts", Tag.TAG_COMPOUND).copy());
        tag.put("mm_pre_redesign", backup);
        var known = tag.getList(KEY, Tag.TAG_STRING);
        // Preserve existing equipped/Set knowledge; never turn an old build into a locked module.
        var builds = new ArrayList<ListTag>();
        builds.add(tag.getList("mm_traits", Tag.TAG_COMPOUND));
        var sets = tag.getList("mm_loadouts", Tag.TAG_COMPOUND);
        for (int i = 0; i < sets.size(); i++)
            builds.add(sets.getCompound(i).getList("build", Tag.TAG_COMPOUND));
        for (var build : builds)
            for (int i = 0; i < build.size(); i++) {
                var slot = build.getCompound(i);
                var id = ResourceLocation.tryParse(slot.getString("line"));
                if (id == null) continue;
                var value = StringTag.valueOf(id.toString());
                if (!known.contains(value)) known.add(value);
                var line = TraitLines.get(registry, id).orElse(null);
                if (line == null) continue;
                var old = slot.getList("picks", Tag.TAG_STRING);
                var updated = new ListTag();
                for (int tier = 0; tier < 3; tier++) {
                    String keep = "";
                    for (var trait : line.tier(tier))
                        for (int x = 0; x < old.size(); x++)
                            if (trait.id().equals(old.getString(x))) keep = trait.id();
                    updated.add(StringTag.valueOf(keep));
                }
                // Keep valid relocated picks, but obey sequential choice prerequisites.
                for (int tier = 1; tier < 3; tier++)
                    if (updated.getString(tier - 1).isEmpty())
                        updated.set(tier, StringTag.valueOf(""));
                slot.put("picks", updated);
            }
        tag.put(KEY, known);
        tag.putInt("mm_redesign_version", 1);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
    }
}
