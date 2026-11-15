package com.mattermod.client;

import com.mattermod.trait.Trait;
import com.mattermod.trait.TraitLine;

import net.minecraft.client.resources.language.I18n;
import net.minecraft.locale.Language;
import net.minecraft.resources.ResourceLocation;

/**
 * Translated UI text. Trait and Module texts come from the lang files (trait.mattermod.&lt;id&gt;,
 * trait.mattermod.&lt;id&gt;.desc, module.mattermod.&lt;line&gt;); the datapack's own text is only
 * the fallback for lines added by other datapacks.
 */
public final class Lang {
    private Lang() {}

    /** A formatted UI string (gui.mattermod.*). */
    public static String tr(String key, Object... args) {
        return I18n.get(key, args);
    }

    /** A raw lang entry, unformatted (trait texts contain plain % signs). */
    private static String raw(String key, String fallback) {
        return Language.getInstance().has(key) ? Language.getInstance().getOrDefault(key) : fallback;
    }

    public static String traitName(Trait t) {
        return raw("trait.mattermod." + t.id(), t.name());
    }

    public static String traitDesc(Trait t) {
        return raw("trait.mattermod." + t.id() + ".desc", t.description());
    }

    public static String moduleName(ResourceLocation id, TraitLine line) {
        return raw("module.mattermod." + id.getPath(), line.name());
    }

    /** The English case clean-up in trait texts only applies to English. */
    public static boolean english() {
        var mc = net.minecraft.client.Minecraft.getInstance();
        return mc.getLanguageManager().getSelected().startsWith("en");
    }
}
