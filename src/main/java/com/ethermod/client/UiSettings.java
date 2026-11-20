package com.ethermod.client;

import static com.ethermod.client.UpgradeVisuals.*;

import com.google.gson.JsonParser;

import net.minecraft.client.Minecraft;

import java.nio.file.Files;

/** The K-screen's two chosen theme colours, persisted in config/ethermod-ui.json. */
final class UiSettings {
    private UiSettings() {}

    private static String primary = "petrol", accent = "ember";
    private static boolean settingsLoaded;

    /** The frame / chrome palette. */
    static Palette p() {
        return themes.get(primary);
    }

    /** The accent palette (stage button, sets list, module picker frame). */
    static Palette a() {
        return themes.get(accent);
    }

    static void cycle(Minecraft minecraft, boolean first) {
        String current = first ? primary : accent,
                next = colours.get((colours.indexOf(current) + 1) % colours.size());
        if (first) primary = next;
        else accent = next;
        save(minecraft);
    }

    static void load(Minecraft minecraft) {
        if (settingsLoaded) return;
        settingsLoaded = true;
        try {
            var file = minecraft.gameDirectory.toPath().resolve("config/ethermod-ui.json");
            var settings = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            if (themes.containsKey(settings.get("primary").getAsString()))
                primary = settings.get("primary").getAsString();
            if (themes.containsKey(settings.get("accent").getAsString()))
                accent = settings.get("accent").getAsString();
        } catch (Exception ignored) {
        }
    }

    static void save(Minecraft minecraft) {
        try {
            var path = minecraft.gameDirectory.toPath().resolve("config/ethermod-ui.json");
            Files.createDirectories(path.getParent());
            Files.writeString(
                    path, "{\"primary\":\"" + primary + "\",\"accent\":\"" + accent + "\"}");
        } catch (Exception e) {
            com.ethermod.EtherMod.LOGGER.warn("Could not save UI theme", e);
        }
    }
}
