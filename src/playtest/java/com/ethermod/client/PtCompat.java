package com.ethermod.client;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;

/** 26.3 helpers for the playtest: vanilla entity types and items by id (their fields moved). */
final class PtCompat {
    private PtCompat() {}

    @SuppressWarnings("unchecked")
    static <T extends Entity> EntityType<T> type(String id) {
        return (EntityType<T>) BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.withDefaultNamespace(id));
    }

    static Item item(String id) {
        return BuiltInRegistries.ITEM.getValue(Identifier.parse(id));
    }

    static net.minecraft.client.input.MouseButtonEvent mouse(double x, double y, int button) {
        return new net.minecraft.client.input.MouseButtonEvent(x, y, new net.minecraft.client.input.MouseButtonInfo(button, 0));
    }

    static boolean click(net.minecraft.client.gui.screens.Screen s, double x, double y, int button) {
        return s.mouseClicked(mouse(x, y, button), false);
    }

    static boolean release(net.minecraft.client.gui.screens.Screen s, double x, double y, int button) {
        return s.mouseReleased(mouse(x, y, button));
    }

    static boolean drag(net.minecraft.client.gui.screens.Screen s, double x, double y, int button, double dx, double dy) {
        return s.mouseDragged(mouse(x, y, button), dx, dy);
    }

    /** The old named screenshot: screenshots/name in the game directory. */
    static void grab(
            java.io.File gameDir,
            String name,
            com.mojang.blaze3d.pipeline.RenderTarget target,
            java.util.function.Consumer<net.minecraft.network.chat.Component> done) {
        net.minecraft.client.Screenshot.takeScreenshot(
                target,
                image -> {
                    try (image) {
                        var dir = new java.io.File(gameDir, "screenshots");
                        dir.mkdirs();
                        image.writeToFile(new java.io.File(dir, name));
                        done.accept(net.minecraft.network.chat.Component.literal("Saved " + name));
                    } catch (Exception e) {
                        done.accept(net.minecraft.network.chat.Component.literal("Screenshot failed: " + e));
                    }
                });
    }
}
