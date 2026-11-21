package com.ethermod.trait;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.List;

/**
 * The playstyle icons a Set can show. A Set without a chosen icon shows the one its first Module
 * suggests (the picks break the Tectonic tie between mining and building); right-clicking a Set in
 * the K-screen steps through them in this order.
 */
public final class SetIcons {
    private SetIcons() {}

    public static final List<String> ALL =
            List.of("mining", "building", "melee", "ranged", "farming", "traversal", "caving", "nether", "ocean");

    public static boolean valid(String icon) {
        return ALL.contains(icon);
    }

    /** The chosen icon, or the suggested one when none (or an unknown one) is stored. */
    public static String of(String chosen, ListTag build) {
        return valid(chosen) ? chosen : auto(build);
    }

    /** The icon after [icon] in the cycle. */
    public static String next(String icon) {
        return ALL.get((Math.max(0, ALL.indexOf(icon)) + 1) % ALL.size());
    }

    /** The icon a build suggests: from the Module in its first used slot. */
    public static String auto(ListTag build) {
        for (int s = 0; s < build.size(); s++) {
            CompoundTag slot = build.getCompound(s);
            String line = slot.getString("line");
            if (line.isEmpty()) continue;
            String module = line.substring(line.indexOf(':') + 1);
            ListTag picks = slot.getList("picks", Tag.TAG_STRING);
            return switch (module) {
                case "tectonic" ->
                        picked(picks, "tectonic.steady_hand") || picked(picks, "tectonic.mass_pickup")
                                ? "building"
                                : "mining";
                case "plasmic" -> "melee";
                case "kinetic" -> "ranged";
                case "botanic" -> "farming";
                case "gravitic", "pneumatic" -> "traversal";
                case "photonic" -> "caving";
                case "pyrotechnic" -> "nether";
                case "hydraulic" -> "ocean";
                default -> "mining";
            };
        }
        return "mining";
    }

    private static boolean picked(ListTag picks, String id) {
        for (int i = 0; i < picks.size(); i++) if (picks.getString(i).equals(id)) return true;
        return false;
    }
}
