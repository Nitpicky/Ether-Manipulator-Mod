package com.ethermod.client;

import static com.ethermod.client.KDraw.frame;
import static com.ethermod.client.KUi.send;
import static com.ethermod.client.UiSettings.a;
import static com.ethermod.client.UiSettings.p;

import com.ethermod.manipulator.Manipulator;
import com.ethermod.network.payload.TraitPayloads;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Native, server-authoritative implementation of the approved K-screen prototype. This class is the
 * {@link Screen} and orchestrates the parts (all in this package, prefixed K): {@link KUi} frame
 * context, {@link KGrid} backdrop, {@link KModelView} turntable, {@link KStageButton},
 * {@link KDyePalette} with {@link KColourPicker}, {@link KTierPanel}s, {@link KLoadoutList} and
 * {@link KModulePicker}, with {@link KTooltips} and {@link UiSettings} behind them.
 *
 * <p>The fields {@code left}, {@code top}, {@code uiScale}, {@code hits}, {@code picker} and
 * {@code panels} (and {@link #showTooltips}) are read by name from the playtest tours: keep them.
 */
public class UpgradeScreen extends Screen {
    // Exact prototype coordinates, translated from its reference frame (125,80).
    static final int W = 320, H = 158;
    private int left, top;
    private float uiScale = 1;
    private final List<KHit> hits = new ArrayList<>();
    private final KUi ui = new KUi(hits);
    private KHit hovered;
    private String hoverKey = "", lastSetKey = "";
    private double hoverAt;
    private double lastTime, lastClick;
    private int picker = -1;
    private final UpgradeAudio audio = new UpgradeAudio();
    private final KGrid grid = new KGrid();
    private final KModelView model = new KModelView();
    private final KStageButton stageButton = new KStageButton();
    private final KColourPicker colour = new KColourPicker();
    private final KDyePalette dyes = new KDyePalette(() -> colour.open(ui.stack));
    private final KLoadoutList loadouts = new KLoadoutList(audio);
    private final KModulePicker modulePicker = new KModulePicker();
    private final KTierPanel[] panels = {
        new KTierPanel(0, ui, audio, this::openPicker, () -> {}),
        new KTierPanel(1, ui, audio, this::openPicker, () -> {}),
        new KTierPanel(2, ui, audio, this::openPicker, () -> {})
    };

    /** Off only for the recorded mod-page GIFs, where a tooltip would be cut by the crop. */
    static boolean showTooltips = true;

    public UpgradeScreen() {
        super(Component.translatable("screen.ethermod.upgrade"));
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private double clock() {
        return System.nanoTime() / 1e9;
    }

    private void openPicker(int slot) {
        picker = slot;
    }

    @Override
    protected void init() {
        UpgradeVisuals.load();
        UiSettings.load(minecraft);
        uiScale = Math.min(1f, Math.min((width - 8f) / W, (height - 8f) / H));
        left = Math.round((width - W * uiScale) / 2);
        top = Math.round((height - H * uiScale) / 2);
        ui.bind(minecraft, font);
        ui.layout(left, top, uiScale);
        addRenderableWidget(loadouts.createWidget(font));
        addRenderableWidget(colour.createWidget(font));
        ui.stack = Manipulator.stack(minecraft.player);
        ui.now = clock();
        lastTime = ui.now;
        for (KTierPanel panel : panels) panel.reset();
        audio.open();
    }

    @Override
    public void tick() {
        if (minecraft.player == null) {
            onClose();
            return;
        }
        ui.stack = Manipulator.stack(minecraft.player);
    }

    @Override
    public void renderBackground(GuiGraphics g, int x, int y, float pt) {}

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float pt) {
        // GuiGraphics otherwise flushes each tiny fill separately: batch the pixel-art geometry.
        g.drawManaged(() -> renderNative(g, mouseX, mouseY, pt));
    }

    private void renderNative(GuiGraphics g, int mouseX, int mouseY, float pt) {
        ui.now = clock();
        double dt = Math.min(.05, ui.now - lastTime);
        lastTime = ui.now;
        ui.mx = (int) ((mouseX - left) / uiScale);
        ui.my = (int) ((mouseY - top) / uiScale);
        g.fill(0, 0, width, height, 0x98000000);
        g.pose().pushPose();
        g.pose().translate(left, top, 0);
        g.pose().scale(uiScale, uiScale, 1);
        hits.clear();
        frame(g, 0, 0, W, H, p());
        grid.draw(g, ui);
        frame(g, 0, 0, W, H, p(), false);
        ui.text(g, Lang.tr("screen.ethermod.upgrade"), 8, 8, p().bright());
        ui.control(
                g,
                "theme-primary",
                286,
                5,
                12,
                12,
                p(),
                p().accent(),
                () -> UiSettings.cycle(minecraft, true),
                List.of(),
                false);
        ui.control(
                g,
                "theme-accent",
                300,
                5,
                12,
                12,
                a(),
                a().accent(),
                () -> UiSettings.cycle(minecraft, false),
                List.of(),
                false);
        if (Boolean.getBoolean("ethermod.uiDebug")) {
            ui.control(
                    g,
                    "sound-profile",
                    272,
                    5,
                    12,
                    12,
                    p(),
                    p().accent(),
                    () -> audio.auditionNext(),
                    List.of(
                            KTooltips.label(Lang.tr("gui.ethermod.sound", audio.profileName()), p().bright()),
                            KTooltips.label(Lang.tr("gui.ethermod.sound_hint"), p().text())),
                    false);
            ui.center(g, "S", 272, 5, 12, 12, p().text());
        }
        model.render(g, ui, dt);
        stageButton.draw(g, ui);
        dyes.draw(g, ui);
        for (KTierPanel panel : panels) panel.draw(g);
        loadouts.draw(g, ui);
        audio.observe(ui.stack);
        audio.tick();
        // Modal content owns input; the underlying panels continue their animation.
        if (picker >= 0) {
            hits.clear();
            hits.add(new KHit("dismiss", 0, 0, W, H, () -> picker = -1, List.of(), 0, false));
            g.pose().pushPose();
            g.pose().translate(0, 0, 400);
            modulePicker.draw(
                    g,
                    ui,
                    picker,
                    () -> {
                        picker = -1;
                    });
            g.pose().popPose();
        }
        if (colour.isOpen()) {
            hits.clear();
            g.pose().pushPose();
            g.pose().translate(0, 0, 400);
            colour.draw(g, ui);
            g.pose().popPose();
        }
        hovered = null;
        for (int i = hits.size() - 1; i >= 0; i--)
            if (hits.get(i).contains(ui.mx, ui.my)) {
                hovered = hits.get(i);
                break;
            }
        String key = hovered == null ? "" : hovered.key();
        if (!key.equals(hoverKey)) {
            hoverKey = key;
            hoverAt = ui.now;
            if (hovered != null && hovered.action() != null && !key.equals("dismiss"))
                audio.hover();
        }
        for (KHit h : hits) {
            double target = h == hovered ? 1 : 0, level = ui.hoverLevels.getOrDefault(h.key(), 0d);
            ui.hoverLevels.put(h.key(), level + (target - level) * Math.min(1, dt * 18));
        }
        loadouts.renderRenameBox(g, ui, pt);
        if (colour.isOpen()) colour.renderHexBox(g, ui, pt);
        g.pose().popPose();
        if (showTooltips && hovered != null && !hovered.tip().isEmpty() && ui.now - hoverAt >= .180)
            KTooltips.render(g, font, width, hovered, mouseX, mouseY);
    }

    @Override
    public boolean mouseClicked(double x, double y, int button) {
        double lx = (x - left) / uiScale, ly = (y - top) / uiScale;
        ui.now = clock();
        if (colour.isOpen() && colour.hexBox().mouseClicked(lx, ly, button)) {
            setFocused(colour.hexBox());
            return true;
        }
        if (loadouts.isRenaming()) {
            if (loadouts.renameBox().mouseClicked(lx, ly, button)) {
                setFocused(loadouts.renameBox());
                return true;
            }
            finishRename();
        }
        if (button == 0 && picker < 0 && !colour.isOpen() && KModelView.hits(lx, ly)) {
            model.press(ui.now - lastClick < .25, ui.now);
            lastClick = ui.now;
            return true;
        }
        for (int i = hits.size() - 1; i >= 0; i--) {
            var h = hits.get(i);
            if (h.contains(lx, ly)) {
                if (button == 1 && h.key().startsWith("badge") && picker < 0 && !colour.isOpen()) {
                    int slot = Integer.parseInt(h.key().substring(5));
                    send(new TraitPayloads.Equip(slot, Optional.empty()));
                    audio.press("delete");
                    return true;
                }
                if (h.key().startsWith("set")) {
                    // A set tile: right click changes its icon, a double click renames it, and
                    // the left button is held (to drag it) or let go (to pick it).
                    int index = Integer.parseInt(h.key().substring(3));
                    if (button == 1) {
                        loadouts.cycleIcon(index, ui);
                        return true;
                    }
                    if (button != 0) return true;
                    if (ui.now - lastClick < .25 && h.key().equals(lastSetKey)) {
                        loadouts.beginRename(index, ui);
                        var rename = loadouts.renameBox();
                        setFocused(rename);
                        rename.moveCursorToEnd(false);
                        rename.setHighlightPos(0);
                        lastClick = 0;
                        return true;
                    }
                    lastClick = ui.now;
                    lastSetKey = h.key();
                    ui.pressedKey = h.key();
                    loadouts.press(index, ui);
                    return true;
                }
                if (button != 0) return true;
                lastClick = ui.now;
                if (h.action() != null) {
                    ui.pressedKey = h.key();
                    audio.press(h.key());
                    h.action().run();
                } else if (h.key().startsWith("node")) audio.refused();
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean mouseDragged(double x, double y, int button, double dx, double dy) {
        double lx = (x - left) / uiScale, ly = (y - top) / uiScale;
        if (colour.isOpen() && colour.dragging()) {
            colour.drag(lx, ly);
            return true;
        }
        if (model.dragging()) {
            model.drag(dx, dy, uiScale, clock());
            return true;
        }
        if (loadouts.draggingBar()) {
            loadouts.dragBar(ly, ui);
            return true;
        }
        return loadouts.holding();
    }

    @Override
    public boolean mouseReleased(double x, double y, int b) {
        colour.endDrag();
        loadouts.releaseBar();
        if (b == 0) {
            ui.mx = (int) ((x - left) / uiScale);
            ui.my = (int) ((y - top) / uiScale);
            loadouts.release(ui);
        }
        ui.pressedKey = null;
        return model.release(clock());
    }

    @Override
    public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
        if ((x - left) / uiScale >= KLoadoutList.PANEL_X && picker < 0 && !colour.isOpen()) {
            loadouts.scroll(vertical, ui);
            return true;
        }
        return false;
    }

    private void finishRename() {
        loadouts.finishRename();
        setFocused(null);
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (!loadouts.isRenaming()
                && !colour.hexBox().isFocused()
                && com.ethermod.client.event.ManipulatorKeys.SKILL_TREE_KEY.matches(key, scan)) {
            onClose();
            return true;
        }
        if (key == 256) {
            if (colour.isOpen()) {
                colour.close();
                return true;
            }
            if (picker >= 0) {
                picker = -1;
                return true;
            }
            if (loadouts.isRenaming()) {
                loadouts.cancelRename();
                return true;
            }
        }
        if (key == 257 && loadouts.isRenaming()) {
            finishRename();
            return true;
        }
        return super.keyPressed(key, scan, mods);
    }

    @Override
    public void removed() {
        audio.stop();
        for (KTierPanel panel : panels) panel.release();
        UiSettings.save(minecraft);
        grid.release(ui);
        super.removed();
    }
}
