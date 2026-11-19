package com.ethermod.client;

import static com.ethermod.client.KDraw.*;
import static com.ethermod.client.KUi.send;
import static com.ethermod.client.UiSettings.a;
import static com.ethermod.client.UiSettings.p;
import static com.ethermod.client.UpgradeVisuals.*;

import com.ethermod.item.*;
import com.ethermod.network.payload.TraitPayloads;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/** The custom-trim colour dialog: saturation/value square, hue strip, hex box and apply/cancel. */
final class KColourPicker {
    private boolean open;
    private float hue = .45f, saturation = .5f, value = .8f;
    private int oldColour;
    private int dragPicker;
    private EditBox hexBox;

    /** Builds the hex box; the screen registers it as a widget. */
    EditBox createWidget(Font font) {
        hexBox = new EditBox(font, 0, 0, 108, 12, Component.translatable("screen.ethermod.hex_code"));
        hexBox.setMaxLength(7);
        hexBox.setVisible(false);
        hexBox.setResponder(
                s -> {
                    try {
                        if (s.matches("#?[0-9a-fA-F]{6}")) {
                            float[] hsv =
                                    java.awt.Color.RGBtoHSB(
                                            (hex(s) >> 16) & 255,
                                            (hex(s) >> 8) & 255,
                                            hex(s) & 255,
                                            null);
                            hue = hsv[0];
                            saturation = hsv[1];
                            value = hsv[2];
                        }
                    } catch (Exception ignored) {
                    }
                });
        return hexBox;
    }

    boolean isOpen() {
        return open;
    }

    EditBox hexBox() {
        return hexBox;
    }

    boolean dragging() {
        return dragPicker != 0;
    }

    void endDrag() {
        dragPicker = 0;
    }

    /** Continues a drag started on the square or the strip. */
    void drag(double lx, double ly) {
        pickColourPoint(lx, ly, dragPicker);
    }

    /** Draws and positions the hex box above the dialog. */
    void renderHexBox(GuiGraphics g, KUi ui, float pt) {
        hexBox.setX(151);
        hexBox.setY(134);
        g.pose().pushPose();
        g.pose().translate(0, 0, 450);
        hexBox.render(g, ui.mx, ui.my, pt);
        g.pose().popPose();
    }

    void open(ItemStack stack) {
        open = true;
        oldColour = ManipulatorColors.body(stack);
        int c = oldColour < 0 ? 0x75d5b9 : oldColour;
        float[] v = java.awt.Color.RGBtoHSB(c >> 16 & 255, c >> 8 & 255, c & 255, null);
        hue = v[0];
        saturation = v[1];
        value = v[2];
        hexBox.setValue(String.format("#%06X", c));
        hexBox.setVisible(true);
        hexBox.setFocused(false);
    }

    void draw(GuiGraphics g, KUi ui) {
        frame(g, 119, 39, 152, 130, a());
        ui.text(g, Lang.tr("gui.ethermod.custom_trim"), 126, 46, a().bright());
        for (int x = 0; x < 88; x++)
            for (int y = 0; y < 64; y++)
                g.fill(127 + x, 60 + y, 128 + x, 61 + y, hsv(hue, x / 87f, 1 - y / 63f));
        for (int y = 0; y < 64; y++) g.fill(221, 60 + y, 229, 61 + y, hsv(y / 63f, 1, 1));
        int cx = 127 + Math.round(saturation * 87), cy = 60 + Math.round((1 - value) * 63);
        border(g, cx - 2, cy - 2, 5, 5, 0xff000000);
        border(g, cx - 1, cy - 1, 3, 3, 0xffffffff);
        border(g, 219, 58 + Math.round(hue * 63), 12, 4, 0xffffffff);
        ui.text(g, Lang.tr("gui.ethermod.colour_new"), 236, 60, a().text());
        g.fill(236, 71, 263, 87, hsv(hue, saturation, value));
        ui.text(g, Lang.tr("gui.ethermod.colour_old"), 236, 96, a().text());
        g.fill(236, 107, 263, 123, 0xff000000 | oldColour);
        ui.text(g, Lang.tr("gui.ethermod.hex"), 127, 136, a().text());
        ui.hit("sv", 127, 60, 88, 64, () -> pickColourPoint(ui.mx, ui.my, 1), List.of(), 0, false);
        ui.hit("hue", 221, 60, 8, 64, () -> pickColourPoint(ui.mx, ui.my, 2), List.of(), 0, false);
        ui.control(
                g,
                "cancel-colour",
                127,
                152,
                64,
                12,
                p(),
                p().accent(),
                () -> close(),
                List.of(),
                false);
        ui.center(g, Lang.tr("gui.cancel"), 127, 152, 64, 12, p().text());
        boolean valid = hexBox.getValue().matches("#?[0-9a-fA-F]{6}");
        ui.control(
                g,
                "apply-colour",
                197,
                152,
                66,
                12,
                p(),
                valid ? p().accent() : p().border(),
                valid
                        ? () -> {
                            send(
                                    new TraitPayloads.PickColour(
                                            true, hex(hexBox.getValue()) & 0xffffff));
                            close();
                        }
                        : null,
                List.of(),
                false);
        ui.center(g, Lang.tr("gui.ethermod.apply"), 197, 152, 66, 12, p().bright());
    }

    void close() {
        open = false;
        hexBox.setVisible(false);
        dragPicker = 0;
    }

    private void pickColourPoint(double x, double y, int kind) {
        dragPicker = kind;
        if (kind == 1) {
            saturation = (float) Math.clamp((x - 127) / 87, 0, 1);
            value = 1 - (float) Math.clamp((y - 60) / 63, 0, 1);
        } else hue = (float) Math.clamp((y - 60) / 63, 0, 1);
        hexBox.setValue(String.format("#%06X", hsv(hue, saturation, value) & 0xffffff));
    }
}
