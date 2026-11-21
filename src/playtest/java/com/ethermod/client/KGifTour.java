package com.ethermod.client;

import com.ethermod.item.ManipulatorColors;
import com.ethermod.item.ManipulatorStage;
import com.ethermod.manipulator.Manipulator;
import com.ethermod.trait.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.*;
import net.minecraft.client.*;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * -PkGifs: records the K screen for the mod page. Each scene opens the screen, moves a virtual
 * mouse (render coordinates only, no desktop pointer) over named controls, clicks them, and grabs
 * a frame every other tick as kgif_&lt;scene&gt;_NNNN.png. kgif-frames.txt lists each frame with its
 * time, kgif-crop.txt the window rectangle in framebuffer pixels; .github/scripts/kgifs.py turns
 * them into GIFs.
 */
public final class KGifTour {
    private KGifTour() {}

    private record Step(int ticks, Runnable start, IntConsumer each) {}

    private static final List<Step> plan = new ArrayList<>();
    private static final List<String> frames = new ArrayList<>();
    private static final List<String> report = new ArrayList<>();
    private static int tick, stepIndex = -1, stepTick, frame;
    private static String scene;
    private static double vx = -50, vy = -50, fromX, fromY;
    private static boolean built, cropWritten;

    /** The K screen with a virtual mouse. */
    static final class Screen extends TraitScreen {
        @Override
        public void render(GuiGraphics g, int x, int y, float pt) {
            super.render(g, (int) Math.round(vx), (int) Math.round(vy), pt);
        }
    }

    private static Object field(Object owner, Class<?> type, String name) {
        try {
            var f = type.getDeclaredField(name);
            f.setAccessible(true);
            return f.get(owner);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static int call(Object rec, String name) {
        try {
            var m = rec.getClass().getDeclaredMethod(name);
            m.setAccessible(true);
            return (int) m.invoke(rec);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static String key(Object rec) {
        try {
            var m = rec.getClass().getDeclaredMethod("key");
            m.setAccessible(true);
            return (String) m.invoke(rec);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** Centre of the control with this key, in GUI coordinates, or null. */
    private static double[] centre(Minecraft mc, String k) {
        if (!(mc.screen instanceof UpgradeScreen s)) return null;
        int left = (int) field(s, UpgradeScreen.class, "left"), top = (int) field(s, UpgradeScreen.class, "top");
        float scale = (float) field(s, UpgradeScreen.class, "uiScale");
        @SuppressWarnings("unchecked")
        var hits = new ArrayList<>((List<Object>) field(s, UpgradeScreen.class, "hits"));
        for (Object h : hits)
            if (key(h).equals(k))
                return new double[] {
                    left + (call(h, "x") + call(h, "w") / 2.0) * scale,
                    top + (call(h, "y") + call(h, "h") / 2.0) * scale
                };
        return null;
    }

    /** A point given in K-window units (0..320, 0..158), in GUI coordinates. */
    private static double[] local(Minecraft mc, double lx, double ly) {
        var s = mc.screen;
        int left = (int) field(s, UpgradeScreen.class, "left"), top = (int) field(s, UpgradeScreen.class, "top");
        float scale = (float) field(s, UpgradeScreen.class, "uiScale");
        return new double[] {left + lx * scale, top + ly * scale};
    }

    private static void check(String label, boolean pass) {
        String line = (pass ? "PASS " : "FAIL ") + label;
        report.add(line);
        System.out.println("[kgifs] " + line);
    }

    // ---- plan builders -------------------------------------------------------------------
    private static void add(int ticks, Runnable start, IntConsumer each) {
        plan.add(new Step(ticks, start == null ? () -> {} : start, each == null ? i -> {} : each));
    }

    private static void waitT(int n) {
        add(n, null, null);
    }

    private static void server(Consumer<ServerPlayer> f) {
        Minecraft mc = Minecraft.getInstance();
        add(
                6,
                () ->
                        mc.getSingleplayerServer()
                                .execute(
                                        () -> {
                                            var p =
                                                    mc.getSingleplayerServer()
                                                            .getPlayerList()
                                                            .getPlayer(mc.player.getUUID());
                                            f.accept(p);
                                            Manipulator.sync(p);
                                        }),
                null);
    }

    private static void open(String name) {
        Minecraft mc = Minecraft.getInstance();
        add(
                12,
                () -> {
                    vx = -50;
                    vy = -50;
                    mc.setScreen(new Screen());
                },
                i -> {
                    if (i == 11) scene = name; // start recording once the screen has settled
                });
    }

    private static void close() {
        Minecraft mc = Minecraft.getInstance();
        add(
                4,
                () -> {
                    scene = null;
                    mc.setScreen(null);
                },
                null);
    }

    private static void glideTo(Supplier<double[]> target, String label, int n) {
        Minecraft mc = Minecraft.getInstance();
        final double[][] to = new double[1][];
        add(
                n,
                () -> {
                    fromX = vx;
                    fromY = vy;
                    to[0] = target.get();
                    check("control " + label + " found", to[0] != null);
                    if (fromX < 0) { // enter from the window's right edge
                        var e = local(mc, 330, 120);
                        fromX = e[0];
                        fromY = e[1];
                    }
                },
                i -> {
                    if (to[0] == null) return;
                    double f = (i + 1) / (double) n;
                    f = f * f * (3 - 2 * f); // ease in and out
                    vx = fromX + (to[0][0] - fromX) * f;
                    vy = fromY + (to[0][1] - fromY) * f;
                });
    }

    private static void glide(String k, int n) {
        glideTo(() -> centre(Minecraft.getInstance(), k), k, n);
    }

    private static void glideLocal(double lx, double ly, int n) {
        glideTo(() -> local(Minecraft.getInstance(), lx, ly), lx + "," + ly, n);
    }

    private static void click(int button) {
        Minecraft mc = Minecraft.getInstance();
        add(
                3,
                () -> mc.screen.mouseClicked(vx, vy, button),
                i -> {
                    if (i == 1) mc.screen.mouseReleased(vx, vy, button);
                });
    }

    private static void click() {
        click(0);
    }

    /** Press, drag the virtual mouse by (dx, dy) GUI pixels over n ticks, release. */
    private static void drag(double dx, double dy, int n) {
        Minecraft mc = Minecraft.getInstance();
        add(
                n + 2,
                () -> mc.screen.mouseClicked(vx, vy, 0),
                i -> {
                    if (i < n) {
                        double sx = dx / n, sy = dy / n;
                        vx += sx;
                        vy += sy;
                        mc.screen.mouseDragged(vx, vy, 0, sx, sy);
                    } else if (i == n) mc.screen.mouseReleased(vx, vy, 0);
                });
    }

    private static boolean tooltips;

    private static void tips(boolean on) {
        add(1, () -> tooltips = on, null);
    }

    /** Press, drag the virtual mouse to a point in K-window pixels over n ticks, release. */
    private static void dragTo(double lx, double ly, int n) {
        Minecraft mc = Minecraft.getInstance();
        final double[] from = new double[2], to = new double[2];
        add(
                n + 2,
                () -> {
                    from[0] = vx;
                    from[1] = vy;
                    var t = local(mc, lx, ly);
                    to[0] = t[0];
                    to[1] = t[1];
                    mc.screen.mouseClicked(vx, vy, 0);
                },
                i -> {
                    if (i < n) {
                        double f = (i + 1) / (double) n, px = vx, py = vy;
                        f = f * f * (3 - 2 * f);
                        vx = from[0] + (to[0] - from[0]) * f;
                        vy = from[1] + (to[1] - from[1]) * f;
                        mc.screen.mouseDragged(vx, vy, 0, vx - px, vy - py);
                    } else if (i == n) mc.screen.mouseReleased(vx, vy, 0);
                });
    }

    /** K-window centre of set tile [index] when the grid is scrolled to the top. */
    private static double[] set(int index) {
        return new double[] {239 + (index % 3) * 22 + 10, 33 + (index / 3) * 24 + 11};
    }

    /** Press, keep still for [hold] ticks (the set lifts), drag to a point over n ticks, release. */
    private static void holdDragTo(double[] to, int hold, int n) {
        Minecraft mc = Minecraft.getInstance();
        final double[] from = new double[2], end = new double[2];
        add(
                hold + n + 2,
                () -> {
                    from[0] = vx;
                    from[1] = vy;
                    var t = local(mc, to[0], to[1]);
                    end[0] = t[0];
                    end[1] = t[1];
                    mc.screen.mouseClicked(vx, vy, 0);
                },
                i -> {
                    if (i < hold) return;
                    int j = i - hold;
                    if (j < n) {
                        double f = (j + 1) / (double) n, px = vx, py = vy;
                        f = f * f * (3 - 2 * f);
                        vx = from[0] + (end[0] - from[0]) * f;
                        vy = from[1] + (end[1] - from[1]) * f;
                        mc.screen.mouseDragged(vx, vy, 0, vx - px, vy - py);
                    } else if (j == n) mc.screen.mouseReleased(vx, vy, 0);
                });
    }

    private static void away(int n) {
        glideLocal(330, 120, n);
        add(1, () -> vx = vy = -50, null);
    }

    private static int moduleIndex(net.minecraft.resources.ResourceLocation id) {
        var lines = TraitLines.all(Minecraft.getInstance().level.registryAccess());
        for (int i = 0; i < lines.size(); i++) if (lines.get(i).getKey().equals(id)) return i;
        return 0;
    }

    private static void build(ServerPlayer p, ItemStack st, boolean picks) {
        var ids =
                new net.minecraft.resources.ResourceLocation[] {
                    Traits.TECTONIC, Traits.HYDRAULIC, Traits.GRAVITIC
                };
        for (int slot = 0; slot < 3; slot++) {
            TraitBuild.setLine(st, slot, ids[slot]);
            var line = TraitLines.get(p.registryAccess(), ids[slot]).orElseThrow();
            for (int tier = 0; tier < 3; tier++)
                TraitBuild.setPick(st, slot, tier, picks ? line.tier(tier).get((slot + tier) % 2).id() : "");
        }
    }

    private static void makePlan() {
        Minecraft mc = Minecraft.getInstance();
        // ---- 1. Upgrade: Prototype to Calibrated with an Iron Tier Chip (new chip sprites, Rust accent) ----
        server(
                p -> {
                    var st = Manipulator.stack(p);
                    ManipulatorStage.set(st, ManipulatorStage.STONE);
                    for (var line : TraitLines.all(p.registryAccess())) ModuleUnlocks.unlock(st, line.getKey());
                    for (int i = 0; i < 3; i++) TraitBuild.setLine(st, i, null);
                    p.getInventory().clearContent();
                    p.getInventory().add(new ItemStack(com.ethermod.registry.ModItems.UPGRADE_IRON.get()));
                });
        open("1_upgrade");
        waitT(20);
        glide("upgrade", 14);
        waitT(30);
        click();
        waitT(30);
        away(10);
        waitT(10);
        close();

        // ---- 2. Choosing a Module for the Calibrated slot (nine Modules: Mechatronic is shelved) 
        open("2_module");
        waitT(14);
        glide("badge0", 14);
        waitT(10);
        click();
        waitT(16);
        add(1, null, null);
        glideTo(() -> centre(mc, "module-pick" + moduleIndex(Traits.HYDRAULIC)), "hydraulic", 12);
        waitT(16);
        glideTo(() -> centre(mc, "module-pick" + moduleIndex(Traits.GRAVITIC)), "gravitic", 12);
        waitT(16);
        glideTo(() -> centre(mc, "module-pick" + moduleIndex(Traits.PYROTECHNIC)), "pyrotechnic", 12);
        waitT(18);
        click();
        waitT(30);
        away(10);
        waitT(10);
        close();

        // ---- 3. Trait choices: A or B per tier, then the Commons ----------------------------
        server(
                p -> {
                    var st = Manipulator.stack(p);
                    ManipulatorStage.set(st, ManipulatorStage.NETHERITE);
                    build(p, st, false);
                });
        open("3_traits");
        tips(true); // this scene reads the trait tooltips
        waitT(14);
        glide("node000", 14);
        waitT(40);
        glide("node001", 10);
        waitT(40);
        click();
        waitT(14);
        glide("node010", 12);
        waitT(36);
        click();
        waitT(14);
        glide("node021", 12);
        waitT(36);
        click();
        waitT(14);
        glide("common00", 12);
        waitT(36);
        glide("common01", 10);
        waitT(36);
        away(10);
        waitT(10);
        close();
        tips(false);

        // ---- 4. Colours: unlock a dye from the inventory, pick dyes, then Custom ------------
        server(
                p -> {
                    var st = Manipulator.stack(p);
                    build(p, st, true);
                    for (DyeColor d : DyeColor.values())
                        if (d != DyeColor.LIME)
                            ManipulatorColors.unlock(st, true, d.getTextureDiffuseColor() & 0xffffff);
                    p.getInventory().add(new ItemStack(Items.LIME_DYE));
                });
        open("4_colours");
        waitT(14);
        glide("dye9", 14); // lime: locked, a dye in the inventory makes it pulse with a plus
        waitT(22);
        click();
        waitT(22);
        glide("dye11", 10);
        waitT(4);
        click();
        waitT(20);
        glide("dye14", 10);
        waitT(4);
        click();
        waitT(20);
        glide("dye7", 10);
        waitT(4);
        click();
        waitT(20);
        glide("dye17", 12);
        waitT(16);
        click();
        waitT(14);
        // hue first (a violet), then saturation/brightness inside the square: a bright, saturated pick
        glideLocal(225, 64, 12);
        dragTo(225, 112, 24);
        waitT(8);
        glideLocal(140, 100, 10);
        dragTo(200, 66, 24);
        waitT(10);
        glide("apply-colour", 12);
        waitT(6);
        click();
        waitT(30);
        glide("dye0", 12);
        waitT(4);
        click();
        waitT(14);
        away(10);
        waitT(10);
        close();

        // ---- 5. Sets: save the build, clear it, load it back --------------------------------
        open("5_sets");
        waitT(12);
        glide("save", 14);
        waitT(10);
        click();
        waitT(20);
        // the new set is picked (linked: changes save into it); unpick it before clearing the build
        glide("set0", 12);
        waitT(4);
        click();
        waitT(12);
        glide("badge0", 12);
        waitT(4);
        click(1);
        waitT(8);
        glide("badge1", 10);
        waitT(4);
        click(1);
        waitT(8);
        glide("badge2", 10);
        waitT(4);
        click(1);
        waitT(18);
        glide("set0", 14);
        waitT(8);
        click();
        waitT(20);
        glide("fav0", 10); // 1.0.2: favourite the set with its heart
        waitT(6);
        click();
        waitT(24);
        away(10);
        waitT(10);
        close();

        // ---- 6. Overview: full build, turntable, then a drag spin ---------------------------
        open("6_overview");
        waitT(70);
        glideLocal(40, 43, 14);
        drag(-60 * 3, 6, 10);
        away(12);
        waitT(50);
        close();

        // ---- 7. Set panel: tiles, copy a code, change an icon, favourites, drag to reorder, scroll --
        server(
                p -> {
                    var st = Manipulator.stack(p);
                    String[] codes = {
                        "1TAB2HBA3GAA", "1TB2WB", "1PAB2KBA3FAB", "1BA", "1LA2GB3HA", "1KB", "1FA2PB", "1GAB",
                        "1HA2WA", "1WB", "1TA2LA", "1PA2FA", "1KA2HA", "1GA2WB"
                    };
                    String[] names = {
                        "Mining", "Builder", "Fight", "Farm", "Explore", "Snipe", "Blaze", "Travel",
                        "Ocean", "Sky", "Caves", "Duel", "Hunt", "Glide"
                    };
                    for (int i = 0; i < codes.length; i++)
                        com.ethermod.trait.Loadouts.add(st, names[i], com.ethermod.trait.BuildCode.decode(codes[i], p.registryAccess()));
                });
        open("7_set_panel");
        waitT(24);
        glide("copy-code", 12);
        waitT(6);
        click();
        waitT(16);
        glide("set4", 12);
        waitT(6);
        click(1);
        waitT(10);
        click(1);
        waitT(12);
        glide("fav2", 10);
        waitT(6);
        click();
        waitT(10);
        glide("fav5", 10);
        waitT(6);
        click();
        waitT(12);
        glide("set0", 12);
        waitT(4);
        holdDragTo(set(5), 10, 22);
        waitT(24);
        glideLocal(307, 40, 10);
        dragTo(307, 125, 24);
        waitT(20);
        away(10);
        waitT(10);
        close();

        // ---- 8. Set tooltip: the name, code and picked traits of a set ------------------------
        open("8_set_tooltip");
        tips(true);
        waitT(16);
        glide("set2", 12);
        waitT(40);
        tips(false);
        close();
        add(
                20,
                () -> {
                    try {
                        Path dir = mc.gameDirectory.toPath();
                        Files.write(dir.resolve("kgif-frames.txt"), frames);
                        Files.write(dir.resolve("kgif-report.txt"), report);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                },
                i -> {
                    if (i == 19) mc.stop();
                });
    }

    public static void step(Minecraft mc) {
        tick++;
        UpgradeScreen.showTooltips = tooltips;
        mc.getToasts().clear();
        if (tick < 20) return;
        if (!built) {
            built = true;
            makePlan();
        }
        if (stepIndex < 0 || stepTick >= plan.get(stepIndex).ticks()) {
            stepIndex++;
            stepTick = 0;
            if (stepIndex >= plan.size()) return;
            plan.get(stepIndex).start().run();
        }
        plan.get(stepIndex).each().accept(stepTick);
        stepTick++;
        if (scene != null && mc.screen instanceof UpgradeScreen s && tick % 2 == 0) {
            if (!cropWritten) {
                cropWritten = true;
                int gs = (int) mc.getWindow().getGuiScale();
                int left = (int) field(s, UpgradeScreen.class, "left"), top = (int) field(s, UpgradeScreen.class, "top");
                float scale = (float) field(s, UpgradeScreen.class, "uiScale");
                try {
                    Files.writeString(
                            mc.gameDirectory.toPath().resolve("kgif-crop.txt"),
                            (left * gs) + " " + (top * gs) + " " + Math.round(320 * scale * gs) + " "
                                    + Math.round(158 * scale * gs));
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
            String name = String.format("kgif_%s_%04d.png", scene, frame++);
            net.minecraft.client.Screenshot.grab(mc.gameDirectory, name, mc.getMainRenderTarget(), c -> {});
            frames.add(name + " " + System.nanoTime());
        }
    }
}
