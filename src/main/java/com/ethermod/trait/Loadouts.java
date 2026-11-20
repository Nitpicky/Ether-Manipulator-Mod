package com.ethermod.trait;

import com.ethermod.manipulator.Manipulator;

import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

import java.util.ArrayList;
import java.util.List;

/**
 * Saved trait builds ("loadouts") on the Manipulator: a named copy of the build, in a list the
 * player orders. Loading one replaces the whole build and follows the same rule as changing traits
 * by hand: not in combat.
 */
public final class Loadouts {

    private Loadouts() {}

    public static final int MAX = 7;
    public static final int MAX_NAME = 10;
    private static final String TAG = "mm_loadouts";

    public static final int SAVE = 0,
            LOAD = 1,
            RENAME = 2,
            UP = 3,
            DOWN = 4,
            DELETE = 5,
            OVERWRITE = 6;

    public record Entry(String name, ListTag build) {}

    public static List<Entry> list(ItemStack stack) {
        CompoundTag root =
                stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).getUnsafe();
        ListTag l = root.getListOrEmpty(TAG);
        List<Entry> out = new ArrayList<>();
        for (int i = 0; i < l.size(); i++) {
            CompoundTag e = l.getCompoundOrEmpty(i);
            out.add(new Entry(e.getStringOr("name", ""), e.getListOrEmpty("build")));
        }
        return out;
    }

    /** The line ids of a saved build, for display ("tectonic", ...). */
    public static List<String> lineIds(Entry e) {
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < e.build().size(); i++) {
            String s = e.build().getCompoundOrEmpty(i).getStringOr("line", "");
            if (!s.isEmpty()) ids.add(s);
        }
        return ids;
    }

    /** Server: one loadout operation from the K screen. */
    public static void handle(ServerPlayer player, int op, int index, String name) {
        ItemStack stack = Manipulator.stack(player);
        CompoundTag root =
                stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        ListTag l = root.getListOrEmpty(TAG);
        ListTag current = root.getListOrEmpty("mm_traits");
        name = clean(name);
        boolean valid = index >= 0 && index < l.size();
        switch (op) {
            case SAVE -> {
                if (l.size() >= MAX) {
                    deny(player, "sets_full", MAX);
                    return;
                }
                CompoundTag e = new CompoundTag();
                e.putString("name", name.isEmpty() ? "#" + (l.size() + 1) : name);
                e.put("build", current.copy());
                l.add(e);
            }
            case OVERWRITE -> {
                if (!valid) return;
                l.getCompoundOrEmpty(index).put("build", current.copy());
            }
            case RENAME -> {
                if (!valid || name.isEmpty()) return;
                l.getCompoundOrEmpty(index).putString("name", name);
            }
            case UP, DOWN -> {
                int to = op == UP ? index - 1 : index + 1;
                if (!valid || to < 0 || to >= l.size()) return;
                Tag a = l.get(index);
                l.set(index, l.get(to));
                l.set(to, a);
            }
            case DELETE -> {
                if (!valid) return;
                l.remove(index);
            }
            case LOAD -> {
                if (!valid) return;
                ListTag build = l.getCompoundOrEmpty(index).getListOrEmpty("build");
                if (build.equals(current)) return;
                if (!TraitBuild.isEmpty(stack) && TraitRules.inCombat(player)) {
                    deny(player, "combat_load_set");
                    return;
                }
                var safe = build.copy();
                for (int slot = 0; slot < safe.size(); slot++) {
                    var entry = safe.getCompoundOrEmpty(slot);
                    var id =
                            net.minecraft.resources.Identifier.tryParse(
                                    entry.getStringOr("line", ""));
                    if (id != null && !ModuleUnlocks.known(stack, id)) {
                        deny(player, "set_module_unknown");
                        return;
                    }
                }
                root.put("mm_traits", safe);
                player.sendOverlayMessage(
                        Component.translatable(
                                        "message.ethermod.set_loaded",
                                        l.getCompoundOrEmpty(index).getStringOr("name", ""))
                                .withStyle(ChatFormatting.AQUA));
            }
            default -> {
                return;
            }
        }
        root.put(TAG, l);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(root));
        if (op == LOAD) TraitEnchants.refresh(stack, player.registryAccess());
        Manipulator.sync(player);
    }

    private static String clean(String s) {
        if (s == null) return "";
        s = s.replaceAll("\\p{Cntrl}", "").strip();
        return s.length() > MAX_NAME ? s.substring(0, MAX_NAME) : s;
    }

    private static void deny(ServerPlayer player, String msg, Object... args) {
        player.sendOverlayMessage(
                Component.translatable("message.ethermod." + msg, args)
                        .withStyle(ChatFormatting.RED));
    }
}
