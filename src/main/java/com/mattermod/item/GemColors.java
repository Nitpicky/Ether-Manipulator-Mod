package com.mattermod.item;

import com.mattermod.config.MatterConfig;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Which items are gems that colour the beam, and the colour each gives.
 *
 * <p>Order of lookup: the config's {@code gem_colors} list, then the built-in vanilla gems, then
 * (if {@code auto_detect_gems} is on) any item in the {@code c:gems} tag, coloured by a keyword in
 * its name, or by a stable bright colour from its id when no keyword matches.
 */
public final class GemColors {

    private GemColors() {}

    public static final TagKey<Item> C_GEMS =
            ItemTags.create(ResourceLocation.fromNamespaceAndPath("c", "gems"));

    private static final Map<Item, Integer> VANILLA =
            Map.of(
                    Items.AMETHYST_SHARD, ManipulatorColors.DEFAULT_BEAM,
                    Items.EMERALD, 0x2FE07A,
                    Items.DIAMOND, 0x4FE8E0,
                    Items.QUARTZ, 0xF2EDE4,
                    Items.LAPIS_LAZULI, 0x3C6CF0,
                    Items.REDSTONE, 0xFF2A1E,
                    Items.GLOWSTONE_DUST, 0xFFD24A);

    /** Name keywords for other mods' gems, checked in order. */
    private static final Map<String, Integer> KEYWORDS = new LinkedHashMap<>();

    static {
        KEYWORDS.put("ruby", 0xE8163C);
        KEYWORDS.put("garnet", 0xA3172E);
        KEYWORDS.put("sapphire", 0x2F5BFF);
        KEYWORDS.put("tanzanite", 0x6A4CFF);
        KEYWORDS.put("aquamarine", 0x5FE3E8);
        KEYWORDS.put("topaz", 0xFFB02E);
        KEYWORDS.put("citrine", 0xFFD93B);
        KEYWORDS.put("amber", 0xFF9A1F);
        KEYWORDS.put("peridot", 0x9BE02F);
        KEYWORDS.put("jade", 0x3FBF7F);
        KEYWORDS.put("malachite", 0x1FA05A);
        KEYWORDS.put("onyx", 0x5A5A6E);
        KEYWORDS.put("opal", 0xF4D8F0);
        KEYWORDS.put("moonstone", 0xD8E6FF);
        KEYWORDS.put("pearl", 0xF0EEE6);
        KEYWORDS.put("zircon", 0x7FD0FF);
        KEYWORDS.put("spinel", 0xFF4F8A);
        KEYWORDS.put("tourmaline", 0xFF6FB0);
        KEYWORDS.put("prismarine", 0x7FD6C2);
        KEYWORDS.put("fluorite", 0x9F7FFF);
        KEYWORDS.put("certus", 0xBFE6FF);
    }

    private static String cachedConfig;
    private static Map<Item, Integer> configured = Map.of();

    /** The beam colour this item gives, or -1 if it isn't a gem. */
    public static int colorOf(ItemStack stack) {
        if (stack.isEmpty()) return -1;
        Item item = stack.getItem();
        Integer c = config().get(item);
        if (c != null) return c;
        c = VANILLA.get(item);
        if (c != null) return c;
        if (autoGems() && stack.is(C_GEMS)) {
            String path = BuiltInRegistries.ITEM.getKey(item).getPath();
            for (var e : KEYWORDS.entrySet()) if (path.contains(e.getKey())) return e.getValue();
            return fromId(BuiltInRegistries.ITEM.getKey(item).toString());
        }
        return -1;
    }

    private static boolean autoGems() {
        try {
            return MatterConfig.AUTO_GEMS.get();
        } catch (IllegalStateException e) {
            return true;
        }
    }

    private static Map<Item, Integer> config() {
        java.util.List<? extends String> list;
        try {
            list = MatterConfig.GEM_COLORS.get();
        } catch (IllegalStateException e) {
            return Map.of();
        }
        String key = String.valueOf(list);
        if (key.equals(cachedConfig)) return configured;
        Map<Item, Integer> m = new java.util.HashMap<>();
        for (String entry : list) {
            int eq = entry.indexOf("=#");
            if (eq < 0) continue;
            ResourceLocation id = ResourceLocation.tryParse(entry.substring(0, eq).trim());
            if (id == null || !BuiltInRegistries.ITEM.containsKey(id)) continue;
            try {
                m.put(
                        BuiltInRegistries.ITEM.get(id),
                        Integer.parseInt(entry.substring(eq + 2).trim(), 16) & 0xFFFFFF);
            } catch (NumberFormatException ignored) {
            }
        }
        cachedConfig = key;
        configured = m;
        return m;
    }

    /** A stable, bright colour from an id, for gems no keyword covers. */
    private static int fromId(String id) {
        float hue = (id.hashCode() & 0xFFFF) / 65536f;
        return net.minecraft.util.Mth.hsvToRgb(hue, 0.7f, 1f) & 0xFFFFFF;
    }
}
