package com.ethermod.client;

import static com.ethermod.client.KDraw.*;
import static com.ethermod.client.KTooltips.label;
import static com.ethermod.client.KUi.send;
import static com.ethermod.client.UiSettings.a;
import static com.ethermod.client.UiSettings.p;
import static com.ethermod.client.UpgradeVisuals.*;

import com.ethermod.network.payload.TraitPayloads;
import com.ethermod.trait.Loadouts;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.util.List;
import java.util.Optional;

/** The saved-sets list on the right: select / load, save, delete, scroll and inline rename. */
final class KLoadoutList {
    private int selectedSet = -1, pendingSave = -1, setScroll, renaming = -1;
    private EditBox rename;

    /** Builds the rename box; the screen registers it as a widget. */
    EditBox createWidget(Font font) {
        rename = new EditBox(font, 0, 0, 43, 12, Component.translatable("screen.ethermod.set_name"));
        rename.setMaxLength(Loadouts.MAX_NAME);
        rename.setVisible(false);
        return rename;
    }

    EditBox renameBox() {
        return rename;
    }

    boolean isRenaming() {
        return renaming >= 0;
    }

    void clearSelection() {
        selectedSet = -1;
    }

    /** Opens the rename box on row [index] of the list (the screen then focuses it). */
    void beginRename(int index, KUi ui) {
        renaming = index;
        rename.setValue(Loadouts.list(ui.stack).get(renaming).name());
        rename.setVisible(true);
        rename.setFocused(true);
    }

    /** Sends the new name unless blank, and closes the box. */
    void finishRename() {
        if (renaming >= 0 && !rename.getValue().isBlank())
            send(new TraitPayloads.Loadout(Loadouts.RENAME, renaming, rename.getValue()));
        renaming = -1;
        rename.setVisible(false);
    }

    void cancelRename() {
        renaming = -1;
        rename.setVisible(false);
    }

    /** Wheel over the list: one row per notch. */
    void scroll(double vertical, KUi ui) {
        setScroll =
                Math.clamp(
                        setScroll - (int) Math.signum(vertical),
                        0,
                        Math.max(0, Loadouts.list(ui.stack).size() - Loadouts.MAX));
    }

    /** Positions and draws the rename box over its row. */
    void renderRenameBox(GuiGraphicsExtractor g, KUi ui, float pt) {
        if (renaming < 0) return;
        rename.setX(241);
        rename.setY(setRowY(renaming - setScroll) + (setRowHeight(renaming - setScroll) - 9) / 2);
        g.pose().pushMatrix();
        g.nextStratum();
        rename.extractRenderState(g, ui.mx, ui.my, pt);
        g.pose().popMatrix();
    }

    static int setRowHeight(int row) { return row < 2 ? 14 : 13; }
    static int setRowY(int row) { return 33 + row * 14 + Math.min(row, 2); }

    void draw(GuiGraphicsExtractor g, KUi ui) {
        inset(g, 235, 20, 77, 130, a(), a().ramp()[1]);
        ui.text(g, Lang.tr("gui.ethermod.sets"), 238, 23, a().text());
        var list = Loadouts.list(ui.stack);
        if (pendingSave >= 0 && list.size() > pendingSave) pendingSave = -1;
        if (selectedSet >= list.size() && pendingSave < 0) selectedSet = -1;
        setScroll = Math.clamp(setScroll, 0, Math.max(0, list.size() - Loadouts.MAX));
        for (int i = setScroll; i < Math.min(list.size(), setScroll + Loadouts.MAX); i++) {
            int row = i - setScroll;
            int y = setRowY(row), h = setRowHeight(row);
            boolean on = selectedSet == i;
            int c = on ? p().accent() : a().border();
            g.fill(238, y, 309, y + h, on ? mix(p().ramp()[1], p().ramp()[3], .35) : a().bg());
            border(g, 238, y, 71, h, c);
            String name = list.get(i).name();
            while (ui.font.width(name) > 41 && !name.isEmpty())
                name = name.substring(0, name.length() - 1);
            if (renaming != i) ui.text(g, name, 241, y + (h - 9) / 2, on ? p().bright() : a().text());
            var ids = Loadouts.lineIds(list.get(i));
            for (int j = 0; j < Math.min(3, ids.size()); j++) {
                var id = Identifier.tryParse(ids.get(j));
                g.fill(287 + j * 7, y + (h - 5) / 2, 292 + j * 7, y + (h - 5) / 2 + 5, module(id, p()).accent());
            }
            final int index = i;
            ui.hit(
                    "set" + i,
                    238,
                    y,
                    71,
                    h,
                    () -> {
                        if (selectedSet == index) selectedSet = -1;
                        else {
                            selectedSet = index;
                            send(new TraitPayloads.Loadout(Loadouts.LOAD, index, ""));
                        }
                    },
                    List.of(),
                    p().accent(),
                    false);
            ui.hover(g, "set" + i, 238, y, 71, h, p().accent(), false);
        }
        ui.control(
                g,
                "save",
                238,
                133,
                55,
                14,
                p(),
                list.size() < Loadouts.MAX ? p().accent() : p().border(),
                list.size() >= Loadouts.MAX
                        ? null
                        : () -> {
                            int n = list.size();
                            if (n < Loadouts.MAX && pendingSave < 0) {
                                pendingSave = n;
                                selectedSet = n;
                                send(new TraitPayloads.Loadout(Loadouts.SAVE, 0, Lang.tr("gui.ethermod.set_default", n + 1)));
                            }
                        },
                List.of(label(Lang.tr("gui.ethermod.save_set"), p().accent())),
                false);
        hollowPlus(g, 261, 135, p().bright());
        ui.control(
                g,
                "delete",
                295,
                133,
                14,
                14,
                p(),
                0xffc8463c,
                () -> {
                    if (selectedSet >= 0) {
                        send(new TraitPayloads.Loadout(Loadouts.DELETE, selectedSet, ""));
                        selectedSet = -1;
                    } else
                        for (int i = 0; i < ui.stage(); i++)
                            send(new TraitPayloads.Equip(i, Optional.empty()));
                },
                List.of(
                        label(
                                selectedSet < 0 ? Lang.tr("gui.ethermod.clear_build") : Lang.tr("gui.ethermod.delete_set"),
                                0xfff2928a)),
                false);
        var r = new Raster(14, 14);
        r.sprite("trash", 2, 2, 0xfff2928a);
        raster(g, r, 295, 133);
    }
}
