package com.ethermod.client;

import com.ethermod.trait.Trait;
import com.ethermod.trait.TraitLine;

import net.minecraft.client.resources.language.I18n;
import net.minecraft.locale.Language;
import net.minecraft.resources.ResourceLocation;

/**
 * Translated UI text. Trait and Module texts come from the lang files (trait.ethermod.&lt;id&gt;,
 * trait.ethermod.&lt;id&gt;.desc, module.ethermod.&lt;line&gt;); the datapack's own text is only
 * the fallback for lines added by other datapacks.
 */
public final class Lang {
    private Lang() {}

    /** A formatted UI string (gui.ethermod.*). */
    public static String tr(String key, Object... args) {
        return I18n.get(key, args);
    }

    /** A raw lang entry, unformatted (trait texts contain plain % signs). */
    private static String raw(String key, String fallback) {
        return Language.getInstance().has(key) ? Language.getInstance().getOrDefault(key) : fallback;
    }

    public static String traitName(Trait t) {
        return raw("trait.ethermod." + t.id(), t.name());
    }

    public static String traitDesc(Trait t) {
        return raw("trait.ethermod." + t.id() + ".desc", t.description());
    }

    public static String moduleName(ResourceLocation id, TraitLine line) {
        return raw("module.ethermod." + id.getPath(), line.name());
    }

    /** The English case clean-up in trait texts only applies to English. */
    public static boolean english() {
        var mc = net.minecraft.client.Minecraft.getInstance();
        return mc.getLanguageManager().getSelected().startsWith("en");
    }
}
