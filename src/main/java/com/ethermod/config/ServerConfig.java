package com.ethermod.config;

import java.util.List;
import java.util.function.Supplier;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Gameplay settings, stored per world (serverconfig/ethermod-server.toml) and sent to every
 * player who joins, so the client shows the same tank, reload and range as the server uses.
 * Every getter falls back to the default while no world is loaded (main menu, early init).
 */
public final class ServerConfig {

    private ServerConfig() {}

    public static final ModConfigSpec SPEC;

    // balance
    private static final ModConfigSpec.IntValue TANK_BASE, TANK_PER_TRAIT, VEIN_LIMIT;
    private static final ModConfigSpec.DoubleValue RELOAD_FIRST, RELOAD_LAST, BEAM_DMG, ORB_DMG, MINING, CHARGE;
    private static final ModConfigSpec.BooleanValue PVP;
    // world
    private static final ModConfigSpec.BooleanValue PROTECTION, MASS_BE, FIRE, WATER, GUST_FOLIAGE, BEAM_LEAVES, BEAM_PLANTS;
    private static final ModConfigSpec.ConfigValue<List<? extends String>> BLACKLIST;
    private static final ModConfigSpec.IntValue MASS_MAX;
    private static final ModConfigSpec.DoubleValue RANGE;
    // progression
    private static final ModConfigSpec.BooleanValue FIRST_JOIN, GEM_GUARANTEE, ALL_MODULES, ALL_TIERS;
    private static final ModConfigSpec.DoubleValue GEM_CHANCE;
    // modules
    private static final ModConfigSpec.ConfigValue<List<? extends String>> DISABLED;
    private static final ModConfigSpec.ConfigValue<List<? extends String>> GEM_ITEMS;
    private static final ModConfigSpec.BooleanValue BLAZELINGS, TETHER_PLAYERS, STASIS_PLAYERS;
    private static final ModConfigSpec.IntValue BLAZE_LIFE, MAX_SUMMONS, PHOTONIC_LIFE, FROST_MELT;
    // compatibility
    private static final ModConfigSpec.ConfigValue<List<? extends String>> HARVEST_TAGS, FOLIAGE, CROPS;

    private static boolean idOrTag(Object o) {
        return o instanceof String s && ResourceLocation.tryParse(s.startsWith("#") ? s.substring(1) : s) != null;
    }

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();

        b.comment("Ether, damage and mining.").push("balance");
        TANK_BASE = b.comment("Ether tank size before tank traits.").defineInRange("ether_tank_size", 6, 1, 100);
        TANK_PER_TRAIT = b.comment("Extra Ether per tank trait (Reservoir, Crucible, Seedbank, Bellows).")
                .defineInRange("ether_per_tank_trait", 2, 0, 50);
        RELOAD_FIRST = b.comment("Seconds without spending Ether before the tank reloads, at Prototype.")
                .defineInRange("reload_seconds_prototype", 15.0, 0.5, 600.0);
        RELOAD_LAST = b.comment("The same at Overclocked. The tiers in between fall evenly between the two.")
                .defineInRange("reload_seconds_overclocked", 5.0, 0.5, 600.0);
        BEAM_DMG = b.comment("Multiplies the beam's damage.").defineInRange("beam_damage_multiplier", 1.0, 0.0, 10.0);
        ORB_DMG = b.comment("Multiplies the damage of charged shots and their splashes.")
                .defineInRange("shot_damage_multiplier", 1.0, 0.0, 10.0);
        PVP = b.comment("Whether the beam, shots and tether can hurt or grab other players.")
                .define("hurt_players", false);
        MINING = b.comment("Multiplies mining speed.").defineInRange("mining_speed_multiplier", 1.0, 0.1, 10.0);
        VEIN_LIMIT = b.comment("Most blocks Vein Miner breaks at once.").defineInRange("vein_miner_limit", 64, 1, 1024);
        CHARGE = b.comment("Multiplies the time a shot takes to charge.")
                .defineInRange("charge_time_multiplier", 1.0, 0.1, 10.0);
        b.pop();

        b.comment("What the Manipulator may do to the world.").push("world");
        PROTECTION = b.comment(
                        "Check claims and spawn protection before every block the Manipulator changes.",
                        "Claims are asked the way claim mods expect: with a block break event posted just before the change.",
                        "Grief loggers, quest and statistics mods may record these checks as broken blocks; turn this off",
                        "if that matters more than claims (spawn protection and the world border are still respected).")
                .define("respect_protection", true);
        BLACKLIST = b.comment("Blocks the beam can't break and the Mass Driver can't lift. Block ids or #tags.")
                .defineListAllowEmpty("block_blacklist", List.of("minecraft:spawner", "minecraft:trial_spawner", "minecraft:vault", "minecraft:budding_amethyst"), () -> "modid:block", ServerConfig::idOrTag);
        MASS_BE = b.comment("Whether the Mass Driver can lift blocks with contents (chests, furnaces, machines).")
                .define("mass_driver_lifts_containers", false);
        MASS_MAX = b.comment("Most blocks the Mass Driver holds at once.").defineInRange("mass_driver_max_blocks", 32, 1, 256);
        FIRE = b.comment("Whether Pyrotechnic can set fire and place lava.").define("fire_and_lava", true);
        WATER = b.comment("Whether Hydraulic can place water and freeze bridges.").define("water_and_frost", true);
        BEAM_LEAVES = b.comment("Whether leaves are solid to the Manipulator: the beam mines them and every shot hits them. Off: the beam and shots pass through them (the sneak beam still shears them).")
                .define("solid_leaves", true);
        BEAM_PLANTS = b.comment("Whether the beam breaks grass, flowers, vines and other small plants. Off: it passes through them.")
                .define("beam_breaks_small_plants", false);
        GUST_FOLIAGE = b.comment("Whether Gust shots and the gust grenade cut leaves and plants.")
                .define("gust_cuts_foliage", true);
        RANGE = b.comment("Multiplies the beam's reach.").defineInRange("beam_range_multiplier", 1.0, 0.1, 4.0);
        b.pop();

        b.comment("Starting out and finding gems.").push("progression");
        FIRST_JOIN = b.comment("Give each player the Manipulator the first time they join.")
                .define("give_on_first_join", true);
        GEM_CHANCE = b.comment("Multiplies the chance of a gem in each structure chest. 0 = no random gems.")
                .defineInRange("gem_chance_multiplier", 1.0, 0.0, 100.0);
        GEM_GUARANTEE = b.comment("The first chest opened in each gem structure always holds its gem.")
                .define("gem_in_first_chest", true);
        ALL_MODULES = b.comment("Every Module is unlocked from the start, no gems needed.")
                .define("all_modules_unlocked", false);
        ALL_TIERS = b.comment("Every Manipulator starts at Overclocked, with every trait tier open.")
                .define("all_tiers_unlocked", false);
        GEM_ITEMS = b.comment(
                        "Other items that work like a gem: right click to unlock that Module and use the item up.",
                        "Each entry is item=module or #tag=module, e.g. \"minecraft:heart_of_the_sea=hydraulic\".",
                        "Modules: tectonic, hydraulic, pyrotechnic, pneumatic, photonic, gravitic, botanic.")
                .defineListAllowEmpty("gem_items", List.of(), () -> "modid:item=hydraulic", ServerConfig::gemEntry);
        b.pop();

        b.comment("Modules and what they leave in the world.").push("modules");
        DISABLED = b.comment("Modules turned off: no gems, can't be equipped. Ids: tectonic, hydraulic, pyrotechnic,"
                        + " pneumatic, photonic, gravitic, botanic.")
                .defineListAllowEmpty("disabled_modules", List.of(), () -> "tectonic", o -> o instanceof String);
        BLAZELINGS = b.comment("Whether Soulwake summons Soulfire Blazelings. Off for packs without vanilla-style creatures.")
                .define("soulwake_blazelings", true);
        BLAZE_LIFE = b.comment("Seconds a Soulfire Blazeling lives.").defineInRange("blazeling_lifetime_seconds", 30, 1, 600);
        MAX_SUMMONS = b.comment("Most summons each player can have at once.").defineInRange("max_summons_per_player", 8, 0, 64);
        PHOTONIC_LIFE = b.comment("Seconds Photonic's lasting lights (Mirage decoys and lights) stay.")
                .defineInRange("photonic_light_seconds", 30, 1, 1200);
        FROST_MELT = b.comment("Seconds a frost bridge stays before it melts (Glacier makes it three times longer).").defineInRange("frost_bridge_seconds", 10, 1, 1200);
        TETHER_PLAYERS = b.comment("Whether Gravitic's tether can grab other players.").define("tether_grabs_players", false);
        STASIS_PLAYERS = b.comment("Whether Pneumatic's Stasis Grenade can hold and launch other players (needs PvP on).")
                .define("stasis_grenade_hits_players", true);
        b.pop();

        b.comment("Other mods.").push("compatibility");
        HARVEST_TAGS = b.comment("Block tags the Manipulator always harvests with drops, for modded blocks that need a modded tool.")
                .defineListAllowEmpty("always_harvest", List.of(), () -> "#modid:mineable/tool", ServerConfig::idOrTag);
        FOLIAGE = b.comment("Extra blocks the beam passes through like leaves. Block ids or #tags.")
                .defineListAllowEmpty("extra_foliage", List.of(), () -> "modid:block", ServerConfig::idOrTag);
        CROPS = b.comment("Extra crop blocks Botanic harvests and replants. Block ids or #tags.")
                .defineListAllowEmpty("extra_crops", List.of(), () -> "modid:crop", ServerConfig::idOrTag);
        b.pop();

        SPEC = b.build();
    }

    private static <T> T or(Supplier<T> v, T fallback) {
        try {
            return SPEC.isLoaded() ? v.get() : fallback;
        } catch (IllegalStateException e) {
            return fallback;
        }
    }

    public static int tankBase() { return or(TANK_BASE::get, 6); }
    public static int tankPerTrait() { return or(TANK_PER_TRAIT::get, 2); }

    /** Reload delay in ticks at a tier (0 = Prototype .. 3 = Overclocked). */
    public static int reloadTicks(int tier) {
        double a = or(RELOAD_FIRST::get, 15.0), z = or(RELOAD_LAST::get, 5.0);
        return Math.max(10, (int) Math.round((a + (z - a) * Math.max(0, Math.min(3, tier)) / 3.0) * 20));
    }

    public static float beamDamage() { return or(() -> BEAM_DMG.get().floatValue(), 1f); }
    public static float shotDamage() { return or(() -> ORB_DMG.get().floatValue(), 1f); }
    public static boolean hurtPlayers() { return or(PVP::get, false); }
    public static float miningSpeed() { return or(() -> MINING.get().floatValue(), 1f); }
    public static int veinLimit() { return or(VEIN_LIMIT::get, 64); }
    public static float chargeTime() { return or(() -> CHARGE.get().floatValue(), 1f); }

    public static boolean respectProtection() { return or(PROTECTION::get, true); }
    public static boolean massLiftsContainers() { return or(MASS_BE::get, false); }
    public static int massMax() { return or(MASS_MAX::get, 32); }
    public static boolean fireAndLava() { return or(FIRE::get, true); }
    public static boolean waterAndFrost() { return or(WATER::get, true); }
    public static boolean gustCutsFoliage() { return or(GUST_FOLIAGE::get, true); }
    public static boolean beamMinesLeaves() { return or(BEAM_LEAVES::get, true); }
    public static boolean beamMinesPlants() { return or(BEAM_PLANTS::get, false); }
    public static float rangeMultiplier() { return or(() -> RANGE.get().floatValue(), 1f); }

    public static boolean giveOnFirstJoin() { return or(FIRST_JOIN::get, true); }
    public static float gemChance() { return or(() -> GEM_CHANCE.get().floatValue(), 1f); }
    public static boolean gemInFirstChest() { return or(GEM_GUARANTEE::get, true); }
    public static boolean allModules() { return or(ALL_MODULES::get, false); }
    public static boolean allTiers() { return or(ALL_TIERS::get, false); }

    private static boolean gemEntry(Object o) {
        if (!(o instanceof String str)) return false;
        int eq = str.indexOf('=');
        if (eq <= 0 || eq == str.length() - 1) return false;
        String item = str.substring(0, eq).trim();
        return ResourceLocation.tryParse(item.startsWith("#") ? item.substring(1) : item) != null;
    }

    /** The Module an item stands in for as a gem (config gem_items), or null. */
    public static String gemModule(net.minecraft.world.item.ItemStack stack) {
        if (stack.isEmpty()) return null;
        List<? extends String> gems = or(GEM_ITEMS::get, List.of());
        for (String e : gems) {
            int eq = e.indexOf('=');
            if (eq <= 0) continue;
            String item = e.substring(0, eq).trim(), module = e.substring(eq + 1).trim().toLowerCase(java.util.Locale.ROOT);
            if (item.startsWith("#")) {
                ResourceLocation id = ResourceLocation.tryParse(item.substring(1));
                if (id != null && stack.is(TagKey.create(Registries.ITEM, id))) return module;
            } else {
                ResourceLocation id = ResourceLocation.tryParse(item);
                if (id != null && BuiltInRegistries.ITEM.getKey(stack.getItem()).equals(id)) return module;
            }
        }
        return null;
    }

    public static boolean moduleDisabled(String line) {
        List<? extends String> l = or(DISABLED::get, List.of());
        for (String s : l) if (s.trim().equalsIgnoreCase(line)) return true;
        return false;
    }

    public static boolean blazelings() { return or(BLAZELINGS::get, true); }
    public static int blazelingTicks() { return or(BLAZE_LIFE::get, 30) * 20; }
    public static int maxSummons() { return or(MAX_SUMMONS::get, 8); }
    public static int photonicTicks() { return or(PHOTONIC_LIFE::get, 30) * 20; }
    public static int frostTicks() { return or(FROST_MELT::get, 10) * 20; }
    public static boolean tetherPlayers() { return or(TETHER_PLAYERS::get, false); }
    public static boolean stasisGrenadePlayers() { return or(STASIS_PLAYERS::get, true); }

    public static boolean blacklisted(BlockState s) { return matches(s, or(BLACKLIST::get, List.of())); }
    public static boolean alwaysHarvest(BlockState s) { return matches(s, or(HARVEST_TAGS::get, List.of())); }
    public static boolean extraFoliage(BlockState s) { return matches(s, or(FOLIAGE::get, List.of())); }
    public static boolean extraCrop(BlockState s) { return matches(s, or(CROPS::get, List.of())); }

    private static boolean matches(BlockState s, List<? extends String> list) {
        if (list.isEmpty()) return false;
        for (String e : list) {
            String t = e.trim();
            if (t.startsWith("#")) {
                ResourceLocation id = ResourceLocation.tryParse(t.substring(1));
                if (id != null && s.is(TagKey.create(Registries.BLOCK, id))) return true;
            } else {
                ResourceLocation id = ResourceLocation.tryParse(t);
                if (id != null && BuiltInRegistries.BLOCK.getKey(s.getBlock()).equals(id)) return true;
            }
        }
        return false;
    }

}
