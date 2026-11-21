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

    /**
     * Clicks through the game's real mouse path (MouseHandler.onButton, as a physical click would),
     * so the screen gets exactly what a player's click gives it. Falls back to calling the screen.
     */
    static boolean click(net.minecraft.client.gui.screens.Screen s, double x, double y, int button) {
        if (realButton(x, y, button, 1)) return true;
        return s.mouseClicked(mouse(x, y, button), false);
    }

    static boolean release(net.minecraft.client.gui.screens.Screen s, double x, double y, int button) {
        if (realButton(x, y, button, 0)) return true;
        return s.mouseReleased(mouse(x, y, button));
    }

    private static boolean realButton(double guiX, double guiY, int button, int action) {
        try {
            var mc = net.minecraft.client.Minecraft.getInstance();
            var w = mc.getWindow();
            var mh = mc.mouseHandler;
            var fx = net.minecraft.client.MouseHandler.class.getDeclaredField("xpos");
            var fy = net.minecraft.client.MouseHandler.class.getDeclaredField("ypos");
            fx.setAccessible(true);
            fy.setAccessible(true);
            fx.setDouble(mh, guiX * w.getScreenWidth() / w.getGuiScaledWidth());
            fy.setDouble(mh, guiY * w.getScreenHeight() / w.getGuiScaledHeight());
            mh.onButton(w.handle(), new net.minecraft.client.input.MouseButtonInfo(button, 0), action);
            return true;
        } catch (Throwable e) {
            System.out.println("[playtest] real mouse path unavailable: " + e);
            return false;
        }
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
