package com.mattermod.config;

import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.List;

/**
 * Common config (config/mattermod-common.toml).
 *
 * <p>Gems set the beam colour. Vanilla gems are built in; other mods' gems can be added by hand
 * here, or picked up automatically from the {@code c:gems} item tag (see {@link
 * com.mattermod.item.GemColors}).
 */
public final class MatterConfig {

    private MatterConfig() {}

    public static final ModConfigSpec SPEC;
    public static final ModConfigSpec.ConfigValue<List<? extends String>> GEM_COLORS;
    public static final ModConfigSpec.BooleanValue AUTO_GEMS;

    public static final ModConfigSpec.IntValue MATTER_REFILL,
            STASIS_TICKS,
            SOUL_LIFETIME,
            PHOTONIC_BEACON_TICKS;
    public static final ModConfigSpec.DoubleValue RADIANCE_HEAL;

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();
        b.comment("Beam colours from gems, set on the K screen's Colours tab.").push("beam_gems");
        GEM_COLORS =
                b.comment(
                                "Extra gems and their beam colour, as \"namespace:item=#RRGGBB\".",
                                "Overrides the built-in and automatic colours. Example:"
                                    + " \"othermod:ruby=#E0115F\"")
                        .defineListAllowEmpty(
                                "gem_colors",
                                List.of(),
                                () -> "modid:item=#FFFFFF",
                                o -> o instanceof String s && s.contains("=#"));
        AUTO_GEMS =
                b.comment(
                                "Also accept any item in the c:gems tag (other mods' rubies,"
                                    + " sapphires...).",
                                "Its colour is guessed from its name (ruby = red, sapphire = blue,"
                                    + " ...).")
                        .define("auto_detect_gems", true);
        b.pop();
        b.push("redesign_tuning");
        MATTER_REFILL =
                b.comment("Ticks per unit the shared projectile tank refills (40 = one every 2 seconds).")
                        .defineInRange("matter_refill_ticks", 40, 1, 12000);
        STASIS_TICKS = b.defineInRange("stasis_ticks", 40, 1, 200);
        // Keep the existing config key so upgraded worlds retain their summon duration.
        SOUL_LIFETIME = b.defineInRange("soul_skeleton_lifetime_ticks", 600, 20, 12000);
        PHOTONIC_BEACON_TICKS = b.defineInRange("photonic_beacon_ticks", 600, 20, 24000);
        RADIANCE_HEAL = b.defineInRange("radiance_health_per_second", 1.0, 0.1, 20.0);
        b.pop();
        SPEC = b.build();
    }
}
