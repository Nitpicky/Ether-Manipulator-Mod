package com.ethermod.event;

import com.ethermod.client.TraitScreen;
import com.ethermod.manipulator.Manipulator;
import com.ethermod.network.ModNetwork;
import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.settings.KeyConflictContext;

import org.lwjgl.glfw.GLFW;

/**
 * Manipulator keys. R: summon / put away (never blocked) K: open the upgrade screen (works whether
 * or not it's summoned) Left Alt: hold for the orb mode wheel, tap to cycle Sneak + scroll while
 * summoned: cycle orb modes
 *
 * <p>While summoned, the drop and swap-hand keys are swallowed (they'd act on the hidden hotbar
 * item), and changing hotbar slot puts the Manipulator away.
 */
@OnlyIn(Dist.CLIENT)
public class ManipulatorKeys {

    private static final String CATEGORY = "key.categories.ethermod";

    public static KeyMapping SKILL_TREE_KEY;
    public static KeyMapping SUMMON_KEY;
    public static KeyMapping ORB_MODE_KEY;

    private static int lastSelectedSlot = -1;

    /** Ticks left of the put-away animation before the hand swaps back (0: not putting away). */

    public static void register(RegisterKeyMappingsEvent event) {
        SUMMON_KEY =
                new KeyMapping(
                        "key.ethermod.summon",
                        KeyConflictContext.IN_GAME,
                        InputConstants.Type.KEYSYM,
                        GLFW.GLFW_KEY_R,
                        CATEGORY);
        SKILL_TREE_KEY =
                new KeyMapping(
                        "key.ethermod.skilltree",
                        KeyConflictContext.IN_GAME,
                        InputConstants.Type.KEYSYM,
                        GLFW.GLFW_KEY_K,
                        CATEGORY);
        ORB_MODE_KEY =
                new KeyMapping(
                        "key.ethermod.orb_mode",
                        KeyConflictContext.IN_GAME,
                        InputConstants.Type.KEYSYM,
                        GLFW.GLFW_KEY_LEFT_ALT,
                        CATEGORY);
        event.register(SUMMON_KEY);
        event.register(ORB_MODE_KEY);
        event.register(SKILL_TREE_KEY);
    }

    /** Runs before vanilla handles keybinds this tick. */
    @SubscribeEvent
    public static void onClientTickPre(ClientTickEvent.Pre event) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null) {
            lastSelectedSlot = -1;
            return;
        }

        if (Manipulator.isSummoned(player)) {
            while (mc.options.keyDrop.consumeClick()) {
                /* swallow */
            }
            while (mc.options.keySwapOffhand.consumeClick()) {
                /* swallow */
            }
        }
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null) return;

        // Scrolling or pressing a number key brings the real hotbar item back.
        int selected = player.getInventory().selected;
        if (lastSelectedSlot != -1
                && selected != lastSelectedSlot
                && Manipulator.isSummoned(player)) {
            com.ethermod.client.ManipulatorAnimations.putAway(player);
            setSummoned(player, false);
        }
        lastSelectedSlot = selected;

        // Alt: the mode wheel, with no screen so movement and sprinting carry on
        com.ethermod.client.ModeWheel.tick(mc, ORB_MODE_KEY);
        if (mc.screen != null) return;

        while (SUMMON_KEY.consumeClick()) {
            if (!Manipulator.isSummoned(player)) {
                setSummoned(player, true);
            } else {
                // swap straight away, like vanilla; the tool folds while vanilla lowers it
                if (player.isUsingItem()) player.stopUsingItem();
                com.ethermod.client.ManipulatorAnimations.putAway(player);
                setSummoned(player, false);
            }
        }
        if (SKILL_TREE_KEY.consumeClick()) {
            mc.setScreen(new TraitScreen());
        }
    }

    /** Sneak + scroll cycles orb modes instead of changing hotbar slot. */
    @SubscribeEvent
    public static void onScroll(
            net.neoforged.neoforge.client.event.InputEvent.MouseScrollingEvent event) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null
                || mc.screen != null
                || !player.isShiftKeyDown()
                || !Manipulator.isSummoned(player)) return;
        double d = event.getScrollDeltaY();
        if (d == 0) return;
        event.setCanceled(true);
        var stack = Manipulator.stack(player);
        var modes =
                java.util.Arrays.stream(com.ethermod.item.OrbMode.values())
                        .filter(m -> m.isUnlocked(stack))
                        .toList();
        if (modes.isEmpty()) return;
        int i = Math.max(0, modes.indexOf(com.ethermod.item.OrbMode.get(stack)));
        var next = modes.get(Math.floorMod(i + (d < 0 ? 1 : -1), modes.size()));
        com.ethermod.item.OrbMode.set(stack, next);
        ModNetwork.sendOrbMode(next);
        player.displayClientMessage(
                net.minecraft.network.chat.Component.translatable(
                                "message.ethermod.orb_selected", next.title())
                        .withStyle(s -> s.withColor(next.color)),
                true);
    }

    /** Predict locally so the hand swaps instantly; the server confirms with a sync. */
    private static void setSummoned(LocalPlayer player, boolean summoned) {
        Manipulator.data(player).setSummoned(summoned);
        ModNetwork.sendSummonToggle(summoned);
        if (player.isUsingItem()) player.stopUsingItem();
    }
}
