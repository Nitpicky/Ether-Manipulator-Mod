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
import com.ethermod.trait.SetIcons;
import com.ethermod.trait.TraitLines;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.component.CustomData;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The saved sets on the right, as a grid of tiles in their main Module's Badge colours: click to
 * pick (load), double-click to rename, right-click to change the icon, hold and drag to reorder,
 * the heart to mark a favourite. Under the grid: new set, copy code, paste code, delete.
 */
final class KLoadoutList {
    // Layout (K-window units). Content is 70px wide inside the panel's 2px padding: three columns
    // of 20x22 tiles 2px apart, then 2px and a 4px scrollbar. Four rows show at once (33..127),
    // then the four 16x16 buttons with 2px gaps (131..146) and the panel's 2px bottom padding.
    static final int PANEL_X = 236, PANEL_W = 76, X = 239, BAR_X = 305, BAR_W = 4;
    static final int LIST_Y = 33, BUTTON_Y = 131, BUTTON_W = 16, BUTTON_H = 16, GAP = 2;
    static final int COLS = 3, ROWS = 4, TILE_W = 20, TILE_H = 22, STEP_X = TILE_W + GAP, STEP_Y = TILE_H + GAP;
    static final int GRID_H = ROWS * STEP_Y - GAP;
    /** Holding a tile this long (or moving the mouse a few pixels) picks it up. */
    private static final double HOLD = .28;
    /** How fast tiles glide to their place (per second, exponential). */
    private static final double GLIDE = 16;

    private final UpgradeAudio audio;

    // The list as drawn: the server's, or what it will be once a sent change arrives.
    private List<Loadouts.Entry> shown = List.of();
    /** One token per shown set: the set's identity on screen while sets move around. */
    private final List<Object> tokens = new ArrayList<>();
    private final Map<Object, double[]> pos = new HashMap<>();
    private final Deque<List<Loadouts.Entry>> expects = new ArrayDeque<>();
    private List<Loadouts.Entry> base = List.of();
    private double expectUntil, lastFrame = -1;

    private Object selected;
    private boolean selectionFound, selectNew;
    private int setScroll, renaming = -1;
    private EditBox rename;
    /** A save or paste waits for the server's copy of the list, but never longer than this. */
    private double pendingUntil;
    private boolean draggingBar;
    private int barGrab;

    // A held tile: picked up after HOLD, then it follows the mouse until released.
    private Object held;
    private double heldAt, heldX, heldY, grabX, grabY, edgeAt;
    private boolean dragging;
    private int dropAt = -1;

    KLoadoutList(UpgradeAudio audio) {
        this.audio = audio;
    }

    /** Builds the rename box (it opens over the panel title); the screen registers it. */
    EditBox createWidget(Font font) {
        rename = new EditBox(font, 0, 0, BAR_X + BAR_W - X, 12, Component.translatable("screen.ethermod.set_name"));
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
        selected = null;
    }

    private int selectedIndex() {
        return selected == null ? -1 : tokens.indexOf(selected);
    }

    /** Opens the rename box for set [index] (the screen then focuses it). */
    void beginRename(int index, KUi ui) {
        if (index < 0 || index >= shown.size()) return;
        cancelHold();
        renaming = index;
        rename.setValue(shown.get(index).name());
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

    // ---- The shown list ----------------------------------------------------------------

    /** Follows the server's list, keeping each set's token (and so its place on screen). */
    private void sync(KUi ui) {
        var list = Loadouts.list(ui.stack);
        if (!expects.isEmpty()) {
            int k = 0;
            for (var e : expects) {
                if (e.equals(list)) break;
                k++;
            }
            if (k < expects.size()) {
                for (int i = 0; i <= k; i++) expects.removeFirst();
                base = list;
                if (expects.isEmpty()) shown = list;
                return;
            }
            if (list.equals(base) && ui.now < expectUntil) return;
            expects.clear();
        }
        reconcile(list, ui);
    }

    private void reconcile(List<Loadouts.Entry> list, KUi ui) {
        if (!list.equals(shown) || tokens.size() != list.size()) {
            int n = shown.size(), m = list.size();
            if (tokens.size() != n) fresh(m);
            else if (m == n - 1) {
                int k = n - 1;
                for (int i = 0; i < m; i++)
                    if (!shown.get(i).equals(list.get(i))) {
                        k = i;
                        break;
                    }
                tokens.remove(k);
            } else if (m == n + 1 && list.subList(0, n).equals(shown)) {
                Object t = new Object();
                tokens.add(t);
                if (selectNew) selected = t;
                selectNew = false;
            } else if (m != n) fresh(m);
            shown = list;
        }
        if (!selectionFound && !tokens.isEmpty()) {
            // On opening, the set that matches the current build is the picked one.
            selectionFound = true;
            ListTag current = currentBuild(ui);
            for (int i = 0; i < shown.size(); i++)
                if (shown.get(i).build().equals(current)) {
                    selected = tokens.get(i);
                    showSet(i);
                    break;
                }
        }
    }

    private void fresh(int size) {
        tokens.clear();
        pos.clear();
        for (int i = 0; i < size; i++) tokens.add(new Object());
    }

    /** Shows [next] straight away while the server catches up. */
    private void predict(List<Loadouts.Entry> next, KUi ui) {
        if (expects.isEmpty()) base = Loadouts.list(ui.stack);
        expects.addLast(next);
        expectUntil = ui.now + 2;
        shown = next;
    }

    private void replace(int index, Loadouts.Entry e, KUi ui) {
        var next = new ArrayList<>(shown);
        next.set(index, e);
        predict(next, ui);
    }

    private void move(int from, int to, KUi ui) {
        var next = new ArrayList<>(shown);
        next.add(to, next.remove(from));
        tokens.add(to, tokens.remove(from));
        predict(next, ui);
        send(new TraitPayloads.Loadout(Loadouts.MOVE, from, Integer.toString(to)));
    }

    /** Right click: the next icon in the cycle. */
    void cycleIcon(int index, KUi ui) {
        if (index < 0 || index >= shown.size() || dragging) return;
        var e = shown.get(index);
        String icon = SetIcons.next(e.shownIcon());
        replace(index, new Loadouts.Entry(e.name(), e.build(), icon, e.favorite()), ui);
        send(new TraitPayloads.Loadout(Loadouts.ICON, index, icon));
        audio.iconCycled();
    }

    private void toggleFavorite(int index, KUi ui) {
        if (index < 0 || index >= shown.size()) return;
        var e = shown.get(index);
        replace(index, new Loadouts.Entry(e.name(), e.build(), e.icon(), !e.favorite()), ui);
        send(new TraitPayloads.Loadout(Loadouts.FAVORITE, index, ""));
    }

    private void delete(int index, KUi ui) {
        var next = new ArrayList<>(shown);
        next.remove(index);
        tokens.remove(index);
        predict(next, ui);
        send(new TraitPayloads.Loadout(Loadouts.DELETE, index, ""));
    }

    private static ListTag currentBuild(KUi ui) {
        return ui.stack
                .getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY)
                .getUnsafe()
                .getList("mm_traits", Tag.TAG_COMPOUND);
    }

    // ---- Scrolling ----------------------------------------------------------------------

    private static int rowsOf(int size) {
        return (size + COLS - 1) / COLS;
    }

    private static int maxScroll(int size) {
        return Math.max(0, rowsOf(size) - ROWS);
    }

    /** Wheel over the list: one row per notch. */
    void scroll(double vertical, KUi ui) {
        setScroll = Math.clamp(setScroll - (int) Math.signum(vertical), 0, maxScroll(shown.size()));
        if (renaming >= 0) cancelRename();
    }

    /** Scrolls just enough to show set [index]. */
    private void showSet(int index) {
        int row = index / COLS;
        if (row >= setScroll + ROWS) setScroll = row - ROWS + 1;
        if (row < setScroll) setScroll = row;
    }

    private static int thumbH(int size) {
        int rows = rowsOf(size);
        return rows <= ROWS ? GRID_H : Math.max(8, GRID_H * ROWS / rows);
    }

    private static int thumbY(int size, int scroll) {
        int max = maxScroll(size);
        return LIST_Y + (max == 0 ? 0 : (GRID_H - thumbH(size)) * scroll / max);
    }

    boolean draggingBar() {
        return draggingBar;
    }

    /** Mouse held on the scrollbar: the thumb follows it (grabbed where it was pressed). */
    void dragBar(double ly, KUi ui) {
        int size = shown.size(), max = maxScroll(size);
        if (max == 0) return;
        int room = GRID_H - thumbH(size);
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
        g.fill(BAR_X, LIST_Y, BAR_X + BAR_W, LIST_Y + GRID_H, a().insetShadow());
        int y = thumbY(size, setScroll), h = thumbH(size);
        boolean live = maxScroll(size) > 0;
        Palette pal = live ? p() : a();
        ui.rampControl(g, "scrollbar", BAR_X, y, BAR_W, h, pal, null, List.of());
        if (live) {
            ui.hit("scrollbar", BAR_X, LIST_Y, BAR_W, GRID_H, () -> pressBar(ui, size), List.of(), pal.accent(), false);
            ui.hover(g, "scrollbar", BAR_X, y, BAR_W, h, pal.accent(), false);
        }
    }

    // ---- Holding and dragging a tile ------------------------------------------------------

    /** Left button down on set [index]: it is picked up if held, or clicked when let go. */
    void press(int index, KUi ui) {
        if (index < 0 || index >= tokens.size()) return;
        held = tokens.get(index);
        heldAt = ui.now;
        heldX = ui.mx;
        heldY = ui.my;
        grabX = ui.mx - slotX(index);
        grabY = ui.my - slotY(index);
        dragging = false;
    }

    boolean holding() {
        return held != null;
    }

    boolean draggingSet() {
        return dragging;
    }

    private void cancelHold() {
        held = null;
        dragging = false;
        dropAt = -1;
    }

    /** Left button up: drops a dragged set in its new place, or picks (loads) a clicked one. */
    void release(KUi ui) {
        if (held == null) return;
        int index = tokens.indexOf(held);
        if (dragging) {
            if (index >= 0 && dropAt >= 0 && dropAt != index) {
                move(index, dropAt, ui);
                audio.setDropped();
            }
        } else if (index >= 0) {
            audio.press("set" + index);
            if (selected == held) selected = null;
            else {
                selected = held;
                send(new TraitPayloads.Loadout(Loadouts.LOAD, index, ""));
            }
        }
        cancelHold();
    }

    /** The place in the list the mouse points at, for the held set. */
    private int pointedSlot(KUi ui) {
        int col = Math.clamp((int) Math.floor((ui.mx - X + GAP / 2.0) / STEP_X), 0, COLS - 1);
        int row = (int) Math.floor((ui.my - LIST_Y + GAP / 2.0) / STEP_Y);
        row = Math.clamp(row, 0, ROWS - 1) + setScroll;
        return Math.clamp(row * COLS + col, 0, Math.max(0, shown.size() - 1));
    }

    private void followHold(KUi ui) {
        if (held == null) return;
        if (!tokens.contains(held)) {
            cancelHold();
            return;
        }
        if (!dragging && (ui.now - heldAt >= HOLD || Math.hypot(ui.mx - heldX, ui.my - heldY) > 3)) {
            dragging = true;
            audio.setLifted();
        }
        if (!dragging) return;
        // Near the top or bottom edge the grid scrolls, one row at a time.
        if (ui.now >= edgeAt) {
            if (ui.my < LIST_Y + 3 && setScroll > 0) {
                setScroll--;
                edgeAt = ui.now + .3;
            } else if (ui.my > LIST_Y + GRID_H - 3 && setScroll < maxScroll(shown.size())) {
                setScroll++;
                edgeAt = ui.now + .3;
            }
        }
        dropAt = pointedSlot(ui);
    }

    // ---- Drawing ----------------------------------------------------------------------

    private int slotX(int index) {
        return X + (index % COLS) * STEP_X;
    }

    private int slotY(int index) {
        return LIST_Y + (index / COLS - setScroll) * STEP_Y;
    }

    /** Positions and draws the rename box over the panel title. */
    void renderRenameBox(GuiGraphics g, KUi ui, float pt) {
        if (renaming < 0) return;
        rename.setX(X);
        rename.setY(21);
        g.pose().pushPose();
        g.pose().translate(0, 0, 450);
        rename.render(g, ui.mx, ui.my, pt);
        g.pose().popPose();
    }

    void draw(GuiGraphics g, KUi ui) {
        inset(g, PANEL_X, 20, PANEL_W, 130, a(), a().ramp()[1]);
        sync(ui);
        if (renaming < 0) title(g, ui);
        var list = shown;
        if (ui.now > pendingUntil) selectNew = false;
        if (renaming >= list.size()) cancelRename();
        setScroll = Math.clamp(setScroll, 0, maxScroll(list.size()));
        followHold(ui);
        double dt = lastFrame < 0 ? 1 : Math.min(.1, ui.now - lastFrame);
        lastFrame = ui.now;
        double glide = 1 - Math.exp(-GLIDE * dt);

        // Where each set goes: in list order, with a held set's place moved to where it would drop.
        List<Object> order = new ArrayList<>(tokens);
        if (dragging && dropAt >= 0) {
            order.remove(held);
            order.add(Math.min(dropAt, order.size()), held);
        }
        if (dragging && dropAt >= 0) cutFrame(g, slotX(dropAt), slotY(dropAt), a().border());
        for (int k = 0; k < order.size(); k++) {
            Object t = order.get(k);
            int i = tokens.indexOf(t);
            double tx = slotX(k), ty = slotY(k);
            double[] at = pos.computeIfAbsent(t, o -> new double[] {tx, ty});
            if (t == held && dragging) continue;
            at[0] += (tx - at[0]) * glide;
            at[1] += (ty - at[1]) * glide;
            if (Math.abs(tx - at[0]) < .3) at[0] = tx;
            if (Math.abs(ty - at[1]) < .3) at[1] = ty;
            int x = (int) Math.round(at[0]), y = (int) Math.round(at[1]);
            boolean inSlot = ty >= LIST_Y && ty < LIST_Y + GRID_H;
            boolean over = !dragging && held == null && inSlot && ui.mx >= tx && ui.mx < tx + TILE_W && ui.my >= ty && ui.my < ty + TILE_H;
            boolean pressed = t == held;
            drawTile(g, list.get(i), x, y, t == selected, over || pressed, over, LIST_Y, LIST_Y + GRID_H);
            if (inSlot && !dragging && renaming < 0) {
                final int index = i;
                var e = list.get(i);
                ui.hit("set" + i, (int) tx, (int) ty, TILE_W, TILE_H, () -> {}, tip(e, ui), accent(e), false);
                ui.hit(
                        "fav" + i,
                        (int) tx + 13,
                        (int) ty + 1,
                        6,
                        5,
                        () -> toggleFavorite(index, ui),
                        List.of(label(Lang.tr(e.favorite() ? "gui.ethermod.set_unfavorite" : "gui.ethermod.set_favorite"), accent(e))),
                        accent(e),
                        false);
            }
        }
        drawBar(g, ui, list.size());
        if (dragging) {
            // The held set rides above everything, kept inside the panel, with a 1px shadow.
            int i = tokens.indexOf(held);
            double[] at = pos.get(held);
            int x = (int) Math.clamp(ui.mx - grabX, PANEL_X + 1, PANEL_X + PANEL_W - 1 - TILE_W);
            int y = (int) Math.clamp(ui.my - grabY, 21, 149 - TILE_H);
            if (at != null) {
                at[0] = x;
                at[1] = y;
            }
            g.pose().pushPose();
            g.pose().translate(0, 0, 300);
            g.fill(x + 2, y + TILE_H, x + TILE_W, y + TILE_H + 1, 0x90000000);
            g.fill(x + TILE_W, y + 2, x + TILE_W + 1, y + TILE_H, 0x90000000);
            drawTile(g, list.get(i), x, y, held == selected, true, false, 0, 1000);
            g.pose().popPose();
        }

        drawButtons(g, ui, list);
    }

    /** The main Module (first used slot), or null. */
    private static ResourceLocation mainModule(Loadouts.Entry e) {
        var build = e.build();
        for (int s = 0; s < build.size(); s++) {
            String line = build.getCompound(s).getString("line");
            if (!line.isEmpty()) return ResourceLocation.tryParse(line);
        }
        return null;
    }

    private static int accent(Loadouts.Entry e) {
        return module(mainModule(e), a()).accent();
    }

    /**
     * One set tile, in its main Module's ramp. Normal: dark fill (0), border 3, icon 5. Hovered:
     * fill 1, border 4, icon 6. Picked: a negative tile, fill 4 (5 hovered), border 6, dark icon,
     * the Module pips in a dark well. Rows outside [clipTop, clipBottom) are not drawn.
     */
    private static void drawTile(
            GuiGraphics g, Loadouts.Entry e, int x, int y, boolean picked, boolean lit, boolean ghostHeart, int clipTop, int clipBottom) {
        int[] c = module(mainModule(e), a()).ramp();
        int fill = picked ? c[lit ? 5 : 4] : c[lit ? 1 : 0];
        int line = picked ? c[6] : c[lit ? 4 : 3];
        int icon = picked ? c[0] : c[lit ? 6 : 5];
        var r = new Raster(TILE_W, TILE_H);
        r.outline(0, 0, TILE_W, TILE_H, line, fill, 1);
        r.sprite("set_" + e.shownIcon(), 6, 5, icon);
        if (picked) r.box(2, 15, 16, 4, c[0]);
        var build = e.build();
        for (int j = 0; j < 3; j++) {
            String id = j < build.size() ? build.getCompound(j).getString("line") : "";
            int pip = !id.isEmpty() ? module(ResourceLocation.tryParse(id), a()).accent() : picked ? c[2] : lit ? c[0] : c[2];
            r.box(3 + j * 5, 16, 4, 2, pip);
        }
        if (e.favorite()) r.sprite("heart", 14, 2, picked ? c[0] : c[6]);
        else if (ghostHeart) r.sprite("heart", 14, 2, c[3]);
        blit(g, r, x, y, clipTop, clipBottom);
    }

    /** Like KDraw.raster, but only the rows that land inside [top, bottom). */
    private static void blit(GuiGraphics g, Raster r, int x, int y, int top, int bottom) {
        for (int yy = Math.max(0, top - y); yy < Math.min(r.h, bottom - y); yy++) {
            int start = 0;
            while (start < r.w) {
                int c = r.pixels[yy * r.w + start], end = start + 1;
                while (end < r.w && r.pixels[yy * r.w + end] == c) end++;
                if ((c >>> 24) > 0) g.fill(x + start, y + yy, x + end, y + yy + 1, c);
                start = end;
            }
        }
    }

    /** The empty place a held set will drop into: a faint frame with cut corners. */
    private static void cutFrame(GuiGraphics g, int x, int y, int c) {
        if (y < LIST_Y || y >= LIST_Y + GRID_H) return;
        var r = new Raster(TILE_W, TILE_H);
        r.outline(0, 0, TILE_W, TILE_H, c, 0, 1);
        raster(g, r, x, y);
    }

    /** Name, code, then each Module with the traits picked in it. */
    private static List<Component> tip(Loadouts.Entry e, KUi ui) {
        var access = ui.mc.level.registryAccess();
        var out = new ArrayList<Component>();
        out.add(label(e.name(), accent(e)));
        String code = BuildCode.encode(e.build(), access);
        if (!code.isEmpty()) out.add(label(code, a().text()));
        var build = e.build();
        for (int s = 0; s < build.size(); s++) {
            String lineId = build.getCompound(s).getString("line");
            ResourceLocation id = lineId.isEmpty() ? null : ResourceLocation.tryParse(lineId);
            if (id == null) continue;
            var line = TraitLines.get(access, id).orElse(null);
            if (line == null) continue;
            int c = module(id, a()).accent();
            MutableComponent row = Component.literal(Lang.moduleName(id, line)).withStyle(st -> st.withColor(c & 0xffffff));
            var picks = build.getCompound(s).getList("picks", Tag.TAG_STRING);
            List<String> names = new ArrayList<>();
            for (int t = 0; t < picks.size(); t++) {
                String pick = picks.getString(t);
                if (pick.isEmpty() || t >= com.ethermod.trait.TraitLine.TIERS) continue;
                for (var trait : line.tier(t)) if (trait.id().equals(pick)) names.add(Lang.traitName(trait));
            }
            if (!names.isEmpty())
                row.append(Component.literal(": " + String.join(", ", names)).withStyle(st -> st.withColor(0xDDDDDD)));
            out.add(row);
        }
        return out;
    }

    // ---- Buttons -----------------------------------------------------------------------

    private void drawButtons(GuiGraphics g, KUi ui, List<Loadouts.Entry> list) {
        // Each has its own fixed hue ramp, whatever the theme; a button that can't be used now
        // turns grey.
        boolean room = list.size() < Loadouts.LIMIT;
        int sel = selectedIndex();
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
                            if (n < Loadouts.LIMIT && !selectNew) {
                                selectNew = true;
                                pendingUntil = ui.now + FLASH_SECONDS;
                                showSet(n);
                                send(new TraitPayloads.Loadout(Loadouts.SAVE, 0, Lang.tr("gui.ethermod.set_default", n + 1)));
                            }
                        }
                        : null,
                List.of(label(Lang.tr("gui.ethermod.save_set"), add.text())));
        int px = x0 + (BUTTON_W - 10) / 2, py = BUTTON_Y + (BUTTON_H - 10) / 2 + addLook.dy();
        hollowPlus(g, px, py, addLook.icon());

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
                List.of(label(sel >= 0 ? Lang.tr("gui.ethermod.copy_set") : Lang.tr("gui.ethermod.copy_build"), copy.text())));
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
                    int s = selectedIndex();
                    if (s >= 0) {
                        delete(s, ui);
                        selected = null;
                    } else
                        for (int i = 0; i < ui.stage(); i++)
                            send(new TraitPayloads.Equip(i, Optional.empty()));
                },
                List.of(label(sel < 0 ? Lang.tr("gui.ethermod.clear_build") : Lang.tr("gui.ethermod.delete_set"), del.text())));
        icon(g, "trash", x3, delLook);
    }

    /** A 10x10 sprite centred in a bottom-row button. */
    private static void icon(GuiGraphics g, String name, int x, KUi.Look look) {
        var r = new Raster(BUTTON_W, BUTTON_H);
        int ix = (BUTTON_W - 10) / 2, iy = (BUTTON_H - 10) / 2 + look.dy();
        r.sprite(name, ix, iy, look.icon());
        raster(g, r, x, BUTTON_Y);
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

    /** Copies the picked set's code, or the current build's when no set is picked. */
    private void copy(KUi ui, List<Loadouts.Entry> list) {
        int sel = selectedIndex();
        ListTag build = sel >= 0 && sel < list.size() ? list.get(sel).build() : currentBuild(ui);
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
        if (n >= Loadouts.LIMIT || selectNew) return;
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
        selectNew = true;
        pendingUntil = ui.now + FLASH_SECONDS;
        showSet(n);
        send(new TraitPayloads.ImportLoadout(code, Lang.tr("gui.ethermod.set_default", n + 1)));
        flash(ui, "paste-code", Lang.tr("gui.ethermod.code_pasted"), true);
        audio.pasted();
    }
}
