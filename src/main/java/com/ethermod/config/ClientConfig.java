package com.ethermod.config;

import java.util.function.Supplier;
import net.neoforged.neoforge.common.ModConfigSpec;

/** What each player sees and hears (config/ethermod-client.toml). Nothing here changes gameplay. */
public final class ClientConfig {

    private ClientConfig() {}

    public enum Detail { FULL, SIMPLE }

    public enum Counter { ALWAYS, FADE_WHEN_FULL, HIDDEN }

    public enum ShaderMode { AUTO, SIMPLE }

    public static final ModConfigSpec SPEC;

    private static final ModConfigSpec.DoubleValue MASTER, BEAM, SHOT, UI, OTHER;
    private static final ModConfigSpec.BooleanValue FLASHING, ARCS, GLOW, PHOTONIC_GLOW, SCROLL, SPIN, CHAT;
    private static final ModConfigSpec.EnumValue<Detail> OTHER_DETAIL;
    private static final ModConfigSpec.EnumValue<ShaderMode> SHADER;
    private static final ModConfigSpec.EnumValue<Counter> COUNTER;

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();

        b.comment("Volumes, from 0 (silent) to 1 (as designed).").push("audio");
        MASTER = b.comment("Every sound the mod plays.").defineInRange("master_volume", 1.0, 0.0, 1.0);
        BEAM = b.comment("Your beam: hum, crackle, sub and snaps.").defineInRange("beam_volume", 1.0, 0.0, 1.0);
        SHOT = b.comment("Charging and firing shots.").defineInRange("shot_and_charge_volume", 1.0, 0.0, 1.0);
        UI = b.comment("Mode-switch cues, reload and screen sounds.").defineInRange("ui_and_cue_volume", 1.0, 0.0, 1.0);
        OTHER = b.comment("Other players' beams.").defineInRange("other_players_beams_volume", 1.0, 0.0, 1.0);
        b.pop();

        b.comment("Visual effects.").push("visuals");
        FLASHING = b.comment("Softens flashes: the damage swell, charge stages, the reload star and the light beacon.")
                .define("reduce_flashing", false);
        ARCS = b.comment("Crackling arcs on the beam.").define("beam_arcs", true);
        GLOW = b.comment("The glow on blocks you mine.").define("mined_block_glow", true);
        PHOTONIC_GLOW = b.comment("The Photonic glow: a light that follows Photonic players smoothly (you and others). Off: no glow.")
                .define("photonic_dynamic_light", true);
        OTHER_DETAIL = b.comment("Other players' beams: FULL, or SIMPLE (no arcs or swell) for busy servers.")
                .defineEnum("other_players_beams", Detail.FULL);
        SHADER = b.comment("AUTO detects shader packs. SIMPLE always draws the plain effects, if a pack breaks the glow.")
                .defineEnum("shader_mode", ShaderMode.AUTO);
        b.pop();

        b.comment("HUD and controls.").push("hud");
        COUNTER = b.comment("The Ether counter under the crosshair: ALWAYS, FADE_WHEN_FULL or HIDDEN.")
                .defineEnum("ether_counter", Counter.FADE_WHEN_FULL);
        SCROLL = b.comment("Sneak + scroll cycles modes.").define("sneak_scroll_cycles_modes", false);
        SPIN = b.comment("The Manipulator turns slowly on the upgrade screen.").define("upgrade_screen_spin", true);
        CHAT = b.comment("Set codes in chat become click-to-copy links.").define("chat_set_code_links", true);
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

    private static float f(ModConfigSpec.DoubleValue v) { return or(() -> v.get().floatValue(), 1f); }

    public static float master() { return f(MASTER); }
    public static float beam() { return f(BEAM) * master(); }
    public static float shot() { return f(SHOT) * master(); }
    public static float ui() { return f(UI) * master(); }
    public static float otherBeams() { return f(OTHER) * master(); }

    public static boolean reduceFlashing() { return or(FLASHING::get, false); }
    public static boolean arcs() { return or(ARCS::get, true); }
    public static boolean minedGlow() { return or(GLOW::get, true); }
    public static boolean photonicDynamicLight() { return or(PHOTONIC_GLOW::get, true); }
    /** Playtest: switches the Photonic glow (in memory, as the config screen would). */
    public static void setPhotonicDynamicLight(boolean on) { if (SPEC.isLoaded()) PHOTONIC_GLOW.set(on); }
    public static boolean simpleOtherBeams() { return or(OTHER_DETAIL::get, Detail.FULL) == Detail.SIMPLE; }
    public static boolean forceSimpleShaders() { return or(SHADER::get, ShaderMode.AUTO) == ShaderMode.SIMPLE; }
    public static Counter counter() { return or(COUNTER::get, Counter.FADE_WHEN_FULL); }
    public static boolean sneakScroll() { return or(SCROLL::get, false); }
    public static boolean spin() { return or(SPIN::get, true); }
    public static boolean chatSetCodes() { return or(CHAT::get, true); }
}
