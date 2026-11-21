package com.ethermod.client;

import static com.ethermod.client.KDraw.*;
import static com.ethermod.client.KTooltips.label;
import static com.ethermod.client.KUi.send;
import static com.ethermod.client.UiSettings.a;
import static com.ethermod.client.UiSettings.p;
import static com.ethermod.client.UpgradeVisuals.*;

import com.ethermod.network.payload.TraitPayloads;
import com.ethermod.trait.BuildCode;
import com.ethermod.trait.Loadouts;
import com.ethermod.trait.ModuleUnlocks;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.component.CustomData;

import java.util.List;
import java.util.Optional;

/**
 * The saved-sets list on the right: select / load, save, copy and paste set codes, delete, scroll
 * and inline rename.
 */
final class KLoadoutList {
    // Layout (K-window units). Content is 70px wide inside the panel's 2px padding: the rows and
    // a 4px scrollbar 2px apart, then four 16x14 buttons with 2px gaps under them. Seven 12px rows
    // with 2px gaps fill 35..130, leaving 2px above the title band's end and above the buttons.
    static final int PANEL_X = 236, PANEL_W = 76, X = 239, ROW_W = 64, BAR_X = 305, BAR_W = 4;
    static final int LIST_Y = 35, BUTTON_Y = 133, BUTTON_W = 16, BUTTON_H = 14, GAP = 2;
    /** Rows shown at once; the rest scroll. */
    static final int VISIBLE = 7;
    private static final int CHIP = 5, CHIP_GAP = 2, CHIP_STEP = CHIP + CHIP_GAP;
    /** Chips sit CHIP_GAP inside the row's 1px border on the right, top and bottom. */
    private static final int CHIPS_X = X + ROW_W - 1 - CHIP_GAP - (CHIP_STEP * 2 + CHIP);
    private static final int NAME_X = X + 3, NAME_W = CHIPS_X - 2 - NAME_X;

    private final UpgradeAudio audio;
    private int selectedSet = -1, pendingSave = -1, setScroll, renaming = -1;
    private EditBox rename;
    /** A save or paste waits for the server's copy of the list, but never longer than this. */
    private double pendingUntil;
    private boolean draggingBar;
    private int barGrab;

    KLoadoutList(UpgradeAudio audio) {
        this.audio = audio;
    }

    /** Builds the rename box; the screen registers it as a widget. */
    EditBox createWidget(Font font) {
        rename = new EditBox(font, 0, 0, NAME_W + 2, 12, Component.translatable("screen.ethermod.set_name"));
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

    private static int maxScroll(int size) {
        return Math.max(0, size - VISIBLE);
    }

    /** Wheel over the list: one row per notch. */
    void scroll(double vertical, KUi ui) {
        setScroll = Math.clamp(setScroll - (int) Math.signum(vertical), 0, maxScroll(Loadouts.list(ui.stack).size()));
        if (renaming >= 0) cancelRename();
    }

    // ---- Scrollbar ---------------------------------------------------------------

    private static int barTrackH() {
        return setRowY(VISIBLE - 1) + setRowHeight(VISIBLE - 1) - LIST_Y;
    }

    private static int thumbH(int size) {
        int track = barTrackH();
        return size <= VISIBLE ? track : Math.max(8, track * VISIBLE / size);
    }

    private static int thumbY(int size, int scroll) {
        int max = maxScroll(size);
        return LIST_Y + (max == 0 ? 0 : (barTrackH() - thumbH(size)) * scroll / max);
    }

    boolean draggingBar() {
        return draggingBar;
    }

    /** Mouse held on the scrollbar: the thumb follows it (grabbed where it was pressed). */
    void dragBar(double ly, KUi ui) {
        int size = Loadouts.list(ui.stack).size(), max = maxScroll(size);
        if (max == 0) return;
        int room = barTrackH() - thumbH(size);
        double t = (ly - barGrab - LIST_Y) / Math.max(1, room);
        int to = Math.clamp(Math.round(t * max), 0, max);
        if (to != setScroll && renaming >= 0) cancelRename();
        setScroll = to;
    }

    void releaseBar() {
        draggingBar = false;
    }

    private void pressBar(KUi ui, int size) {
        int top = thumbY(size, setScroll), h = thumbH(size);
        // On the thumb: keep the grab point. On the track: centre the thumb on the mouse.
        barGrab = ui.my >= top && ui.my < top + h ? ui.my - top : h / 2;
        draggingBar = true;
        dragBar(ui.my, ui);
    }

    private void drawBar(GuiGraphics g, KUi ui, int size) {
        int track = barTrackH();
        g.fill(BAR_X, LIST_Y, BAR_X + BAR_W, LIST_Y + track, a().insetShadow());
        int y = thumbY(size, setScroll), h = thumbH(size);
        boolean live = maxScroll(size) > 0;
        Palette pal = live ? p() : a();
        ui.rampControl(g, "scrollbar", BAR_X, y, BAR_W, h, pal, null, List.of());
        if (live) {
            ui.hit("scrollbar", BAR_X, LIST_Y, BAR_W, track, () -> pressBar(ui, size), List.of(), pal.accent(), false);
            ui.hover(g, "scrollbar", BAR_X, y, BAR_W, h, pal.accent(), false);
        }
    }

    // ---- Rows ---------------------------------------------------------------------

    /** Positions and draws the rename box over its row. */
    void renderRenameBox(GuiGraphics g, KUi ui, float pt) {
        if (renaming < 0) return;
        int row = renaming - setScroll;
        if (row < 0 || row >= VISIBLE) return;
        rename.setX(NAME_X);
        rename.setY(setRowY(row) + (setRowHeight(row) - 9) / 2);
        g.pose().pushPose();
        g.pose().translate(0, 0, 450);
        rename.render(g, ui.mx, ui.my, pt);
        g.pose().popPose();
    }

    /** Every row is the same height with the panel's usual 2px gap between rows. */
    static final int ROW_H = 12, ROW_GAP = 2;

    static int setRowHeight(int row) { return ROW_H; }
    static int setRowY(int row) { return LIST_Y + row * (ROW_H + ROW_GAP); }

    /**
     * A Module chip drawn like the Module's Badge: its bright outline (step 4) with cut corners on
     * the Badge's dark fill (step 1). As tall as the row allows with the same 2px gap around it as
     * between chips. An empty slot is a hollow outline in the panel's border colour.
     */
    private static void chip(GuiGraphics g, int x, int y, int h, ResourceLocation id) {
        int line = a().border(), fill = 0;
        if (id != null) {
            Palette m = module(id, p());
            line = m.accent();
            fill = m.ramp()[1];
        }
        if (fill != 0) g.fill(x + 1, y + 1, x + CHIP - 1, y + h - 1, fill);
        g.fill(x + 1, y, x + CHIP - 1, y + 1, line);
        g.fill(x + 1, y + h - 1, x + CHIP - 1, y + h, line);
        g.fill(x, y + 1, x + 1, y + h - 1, line);
        g.fill(x + CHIP - 1, y + 1, x + CHIP, y + h - 1, line);
    }

    void draw(GuiGraphics g, KUi ui) {
        inset(g, PANEL_X, 20, PANEL_W, 130, a(), a().ramp()[1]);
        title(g, ui);
        var list = Loadouts.list(ui.stack);
        if (pendingSave >= 0 && (list.size() > pendingSave || ui.now > pendingUntil)) pendingSave = -1;
        if (selectedSet >= list.size() && pendingSave < 0) selectedSet = -1;
        setScroll = Math.clamp(setScroll, 0, maxScroll(list.size()));
        for (int i = setScroll; i < Math.min(list.size(), setScroll + VISIBLE); i++) {
            int row = i - setScroll;
            int y = setRowY(row), h = setRowHeight(row);
            boolean on = selectedSet == i;
            int c = on ? p().accent() : a().border();
            g.fill(X, y, X + ROW_W, y + h, on ? mix(p().ramp()[1], p().ramp()[3], .35) : a().bg());
            border(g, X, y, ROW_W, h, c);
            String name = list.get(i).name();
            while (ui.font.width(name) > NAME_W && !name.isEmpty())
                name = name.substring(0, name.length() - 1);
            // Glyphs are 7px tall plus a 1px shadow: centred inside the 1px border.
            int textY = y + 1 + (h - 2 - 8 + 1) / 2;
            if (renaming != i) ui.text(g, name, NAME_X, textY, on ? p().bright() : a().text());
            var build = list.get(i).build();
            for (int j = 0; j < 3; j++) {
                String line = j < build.size() ? build.getCompound(j).getString("line") : "";
                chip(g, CHIPS_X + j * CHIP_STEP, y + 1 + CHIP_GAP, h - 2 - 2 * CHIP_GAP, line.isEmpty() ? null : ResourceLocation.tryParse(line));
            }
            final int index = i;
            ui.hit(
                    "set" + i,
                    X,
                    y,
                    ROW_W,
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
            ui.hover(g, "set" + i, X, y, ROW_W, h, p().accent(), false);
        }
        drawBar(g, ui, list.size());

        // Bottom row: new set, copy code, paste code, delete. Each has its own fixed hue ramp,
        // whatever the theme; a button that can't be used now turns grey.
        boolean room = list.size() < Loadouts.LIMIT;
        Palette off = themes.getOrDefault("graphite", a());
        int x0 = X, x1 = x0 + BUTTON_W + GAP, x2 = x1 + BUTTON_W + GAP, x3 = x2 + BUTTON_W + GAP;

        Palette add = room ? button("add") : off;
        var addLook = ui.rampControl(
                g,
                "save",
                x0,
                BUTTON_Y,
                BUTTON_W,
                BUTTON_H,
                add,
                room
                        ? () -> {
                            int n = list.size();
                            if (n < Loadouts.LIMIT && pendingSave < 0) {
                                pendingSave = n;
                                pendingUntil = ui.now + FLASH_SECONDS;
                                selectedSet = n;
                                showRow(n);
                                send(new TraitPayloads.Loadout(Loadouts.SAVE, 0, Lang.tr("gui.ethermod.set_default", n + 1)));
                            }
                        }
                        : null,
                List.of(label(Lang.tr("gui.ethermod.save_set"), add.text())));
        hollowPlus(g, x0 + (BUTTON_W - 10) / 2, BUTTON_Y + 2 + addLook.dy(), addLook.icon());

        Palette copy = flashing("copy-code", ui) ? flashPalette : button("copy");
        var copyLook = ui.rampControl(
                g,
                "copy-code",
                x1,
                BUTTON_Y,
                BUTTON_W,
                BUTTON_H,
                copy,
                () -> copy(ui, list),
                List.of(
                        label(
                                selectedSet >= 0 && selectedSet < list.size()
                                        ? Lang.tr("gui.ethermod.copy_set")
                                        : Lang.tr("gui.ethermod.copy_build"),
                                copy.text())));
        icon(g, "copy", x1, copyLook);

        Palette paste = flashing("paste-code", ui) ? flashPalette : room ? button("paste") : off;
        var pasteLook = ui.rampControl(
                g,
                "paste-code",
                x2,
                BUTTON_Y,
                BUTTON_W,
                BUTTON_H,
                paste,
                room ? () -> paste(ui, list) : null,
                List.of(label(Lang.tr("gui.ethermod.paste_set"), paste.text())));
        icon(g, "paste", x2, pasteLook);

        Palette del = button("delete");
        var delLook = ui.rampControl(
                g,
                "delete",
                x3,
                BUTTON_Y,
                BUTTON_W,
                BUTTON_H,
                del,
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
                                del.text())));
        icon(g, "trash", x3, delLook);
    }

    /** A 10x10 sprite centred in a bottom-row button. */
    private static void icon(GuiGraphics g, String name, int x, KUi.Look look) {
        var r = new Raster(BUTTON_W, BUTTON_H);
        r.sprite(name, (BUTTON_W - 10) / 2, (BUTTON_H - 10) / 2 + look.dy(), look.icon());
        raster(g, r, x, BUTTON_Y);
    }

    /** Scrolls just enough to show row [index] (a new or pasted set lands at the end). */
    private void showRow(int index) {
        if (index >= setScroll + VISIBLE) setScroll = index - VISIBLE + 1;
        if (index < setScroll) setScroll = index;
    }

    // ---- Set codes -------------------------------------------------------------

    private static final double FLASH_SECONDS = 2;
    private String flashKey = "", flashText = "";
    private Palette flashPalette;
    private double flashUntil;

    private void flash(KUi ui, String key, String text, boolean ok) {
        flashKey = key;
        flashText = text;
        flashPalette = button(ok ? "ok" : "bad");
        flashUntil = ui.now + FLASH_SECONDS;
    }

    private boolean flashing(String key, KUi ui) {
        return key.equals(flashKey) && ui.now < flashUntil;
    }

    /** The panel title, or the last copy/paste result for a moment, shrunk to fit the panel. */
    private void title(GuiGraphics g, KUi ui) {
        boolean on = ui.now < flashUntil;
        String s = on ? flashText : Lang.tr("gui.ethermod.sets");
        int c = on ? flashPalette.text() : a().text();
        int room = BAR_X + BAR_W - X, w = ui.font.width(s);
        if (w <= room) {
            ui.text(g, s, X, 23, c);
            return;
        }
        float k = (float) room / w;
        g.pose().pushPose();
        g.pose().translate(X, 23 + (1 - k) * 4, 0);
        g.pose().scale(k, k, 1);
        ui.text(g, s, 0, 0, c);
        g.pose().popPose();
    }

    /** Copies the selected set's code, or the current build's when no set is selected. */
    private void copy(KUi ui, List<Loadouts.Entry> list) {
        ListTag build =
                selectedSet >= 0 && selectedSet < list.size()
                        ? list.get(selectedSet).build()
                        : ui.stack
                                .getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY)
                                .getUnsafe()
                                .getList("mm_traits", Tag.TAG_COMPOUND);
        String code = BuildCode.encode(build, ui.mc.level.registryAccess());
        if (code.isEmpty()) {
            flash(ui, "copy-code", Lang.tr("gui.ethermod.code_empty"), false);
            audio.refused();
            return;
        }
        ui.mc.keyboardHandler.setClipboard(code);
        flash(ui, "copy-code", Lang.tr("gui.ethermod.code_copied"), true);
        audio.copied();
    }

    /** Checks the clipboard's code and, only when it is valid, adds it as a new set and loads it. */
    private void paste(KUi ui, List<Loadouts.Entry> list) {
        int n = list.size();
        if (n >= Loadouts.LIMIT || pendingSave >= 0) return;
        String code = ui.mc.keyboardHandler.getClipboard().strip();
        ListTag build;
        try {
            build = BuildCode.decode(code, ui.mc.level.registryAccess());
        } catch (IllegalArgumentException e) {
            flash(ui, "paste-code", Lang.tr("gui.ethermod.code_invalid"), false);
            audio.refused();
            return;
        }
        for (var id : BuildCode.modules(build))
            if (!ModuleUnlocks.known(ui.stack, id)) {
                flash(ui, "paste-code", Lang.tr("gui.ethermod.code_locked"), false);
                audio.refused();
                return;
            }
        pendingSave = n;
        pendingUntil = ui.now + FLASH_SECONDS;
        selectedSet = n;
        showRow(n);
        send(new TraitPayloads.ImportLoadout(code, Lang.tr("gui.ethermod.set_default", n + 1)));
        flash(ui, "paste-code", Lang.tr("gui.ethermod.code_pasted"), true);
        audio.pasted();
    }
}
