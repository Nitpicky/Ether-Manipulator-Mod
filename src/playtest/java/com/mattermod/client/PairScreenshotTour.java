package com.mattermod.client;

import com.mattermod.item.ManipulatorStage;
import com.mattermod.manipulator.Manipulator;
import com.mattermod.trait.*;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import java.nio.file.*;
import java.util.*;

/** Opt-in captures from the game framebuffer; never captures the desktop. */
public final class PairScreenshotTour {
    private static int tick;
    private static final List<String> report = new ArrayList<>();
    private static void check(String name, boolean ok) {
        report.add((ok ? "PASS " : "FAIL ") + name);
        System.out.println(report.getLast());
    }
    private static void configure(Minecraft mc, ManipulatorStage stage, String... modules) {
        mc.getSingleplayerServer().execute(() -> {
            var p = mc.getSingleplayerServer().getPlayerList().getPlayer(mc.player.getUUID());
            var st = Manipulator.stack(p);
            ManipulatorStage.set(st, stage);
            for (var entry : TraitLines.all(p.registryAccess())) ModuleUnlocks.unlock(st, entry.getKey());
            for (int slot = 0; slot < 3; slot++) TraitBuild.setLine(st, slot, null);
            for (int slot = 0; slot < modules.length && slot < TraitBuild.openSlots(st); slot++) {
                var id = ResourceLocation.fromNamespaceAndPath("mattermod", modules[slot]);
                TraitBuild.setLine(st, slot, id);
                var line = TraitLines.get(p.registryAccess(), id).orElseThrow();
                for (int tier = 0; tier < TraitBuild.openTiers(st); tier++)
                    TraitBuild.setPick(st, slot, tier, line.tier(tier).get((slot + tier) % 2).id());
            }
            Manipulator.setSummoned(p, true);
            if (tick < 30) {
                check("Ten Modules", TraitLines.all(p.registryAccess()).size() == 10);
                for (var entry : TraitLines.all(p.registryAccess())) {
                    var line = entry.getValue();
                    check(line.name() + " has six choices and two Commons", line.adept().size() == 2 && line.master().size() == 2 && line.grandmaster().size() == 2 && line.commons().size() == 2);
                }
                for (int n = 0; n < 7; n++) Loadouts.handle(p, Loadouts.SAVE, 0, "Set " + (n + 1));
                check("Seven Set limit", Loadouts.list(st).size() == 7);
                for (int slot = 0; slot < modules.length; slot++)
                    check("Selected build " + modules[slot], !TraitBuild.pick(st, slot, 2).isEmpty());
            }
            TraitEnchants.refresh(st, p.registryAccess());
            Manipulator.sync(p);
        });
    }
    private static void capture(Minecraft mc, String name) {
        net.minecraft.client.Screenshot.grab(mc.gameDirectory, name + ".png", mc.getMainRenderTarget(), c -> {});
        System.out.println("PAIR SCREENSHOT " + name);
    }
    public static void step(Minecraft mc) {
        tick++;
        if (tick == 10) configure(mc, ManipulatorStage.NETHERITE, "combat", "orb", "hydraulics");
        if (tick == 25 || tick == 155 || tick == 285 || tick == 415 || tick == 545) mc.setScreen(new TraitScreen());
        if (tick == 120) capture(mc, "pairs-01-plasmic-kinetic-hydraulic");
        if (tick == 140) configure(mc, ManipulatorStage.NETHERITE, "pyrotechnics", "excavation", "pneumatic");
        if (tick == 250) capture(mc, "pairs-02-pyrotechnic-tectonic-pneumatic");
        if (tick == 270) configure(mc, ManipulatorStage.NETHERITE, "cultivation", "mechatronic", "photonic");
        if (tick == 380) capture(mc, "pairs-03-botanic-mechatronic-photonic");
        if (tick == 400) configure(mc, ManipulatorStage.NETHERITE, "mobility", "mechatronic", "combat");
        if (tick == 510) capture(mc, "pairs-04-gravitic");
        if (tick == 530) configure(mc, ManipulatorStage.IRON, "combat");
        if (tick == 640) capture(mc, "pairs-05-calibrated-locked-panels");
        if (tick == 660 && mc.screen instanceof UpgradeScreen screen) {
            try { var f = UpgradeScreen.class.getDeclaredField("picker"); f.setAccessible(true); f.setInt(screen, 0); }
            catch (Exception e) { throw new RuntimeException(e); }
        }
        if (tick == 690) capture(mc, "pairs-06-module-picker");
        if (tick == 720) {
            try { Files.write(Path.of("pair-tour-report.txt"), report); }
            catch (Exception e) { throw new RuntimeException(e); }
            mc.stop();
        }
    }
}
