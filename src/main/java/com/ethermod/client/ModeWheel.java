package com.ethermod.client;

import com.ethermod.item.OrbMode;
import com.ethermod.manipulator.Manipulator;
import com.ethermod.network.ModNetwork;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvents;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.CalculatePlayerTurnEvent;
import net.neoforged.neoforge.client.event.InputEvent;

import java.util.List;

/**
 * The mode wheel key (Alt). It opens no screen, so nothing you are doing stops: walking, sprinting,
 * jumping and sneaking carry on. A quick tap steps to the next mode. Held, the ring opens (drawn by
 * {@link ManipulatorHud}); while it is open the mouse moves a pointer instead of the camera, the
 * side it is on picks the mode, and letting go (or a click) takes it.
 */
@OnlyIn(Dist.CLIENT)
public final class ModeWheel {

    private ModeWheel() {}

    private static final int TAP_TICKS = 5;

    /** Ticks the key has been held, or -1 when it is up. */
    private static int held = -1;

    private static double startX, startY;
    private static List<OrbMode> modes = List.of();
    private static OrbMode current;

    /** Client tick, before anything reads the key: follows the wheel key. */
    public static void tick(Minecraft mc, KeyMapping key) {
        while (key.consumeClick()) {} // the key is read as held, never as a click
        var p = mc.player;
        boolean down = key.isDown() && mc.screen == null && p != null && Manipulator.isSummoned(p);
        if (down) {
            if (held < 0) {
                held = 0;
                var stack = Manipulator.stack(p);
                modes = ManipulatorHud.modes(stack);
                current = OrbMode.get(stack);
                startX = mc.mouseHandler.xpos();
                startY = mc.mouseHandler.ypos();
            }
            held++;
            ManipulatorHud.wheelOpen = held > TAP_TICKS;
            if (!ManipulatorHud.wheelOpen) {
                // the pointer starts where the mouse is when the ring opens
                startX = mc.mouseHandler.xpos();
                startY = mc.mouseHandler.ypos();
            }
            ManipulatorHud.hovered = ManipulatorHud.wheelOpen ? pointed(mc) : null;
        } else if (held >= 0) finish(mc);
    }

    private static OrbMode pointed(Minecraft mc) {
        var w = mc.getWindow();
        double scale = w.getGuiScale();
        // the pointer is the mouse's travel since the ring opened, kept on the screen
        double dx = Math.max(-w.getGuiScaledWidth() / 2.0, Math.min(w.getGuiScaledWidth() / 2.0, (mc.mouseHandler.xpos() - startX) / scale));
        double dy = Math.max(-w.getGuiScaledHeight() / 2.0, Math.min(w.getGuiScaledHeight() / 2.0, (mc.mouseHandler.ypos() - startY) / scale));
        return ManipulatorHud.pointed(modes, dx, dy, w.getGuiScaledWidth(), w.getGuiScaledHeight());
    }

    private static void finish(Minecraft mc) {
        OrbMode pick = null;
        if (held <= TAP_TICKS && !modes.isEmpty()) {
            int i = modes.indexOf(current);
            pick = modes.get((i + 1) % modes.size());
        } else if (ManipulatorHud.hovered != null) pick = ManipulatorHud.hovered;
        held = -1;
        ManipulatorHud.wheelOpen = false;
        ManipulatorHud.hovered = null;
        if (pick != null && pick != current && mc.player != null) {
            ModNetwork.sendOrbMode(pick);
            OrbMode.set(Manipulator.stack(mc.player), pick); // predict; the server confirms
            mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.4f));
        }
    }

    /** While the ring is open the mouse drives the pointer, not the camera. */
    @SubscribeEvent
    public static void turn(CalculatePlayerTurnEvent ev) {
        if (!ManipulatorHud.wheelOpen) return;
        ev.setMouseSensitivity(-1 / 3.0); // vanilla's turn factor is (s * .6 + .2)^3: zero
        ev.setCinematicCameraEnabled(false);
    }

    /** A click while the ring is open takes the pointed mode instead of attacking or using. */
    @SubscribeEvent
    public static void click(InputEvent.InteractionKeyMappingTriggered ev) {
        if (!ManipulatorHud.wheelOpen) return;
        ev.setCanceled(true);
        ev.setSwingHand(false);
        finish(Minecraft.getInstance());
    }
}
