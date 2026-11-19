package com.ethermod.event;

import com.ethermod.beam.BeamAudio;
import com.ethermod.beam.BeamRenderState;
import com.ethermod.item.ManipulatorStage;
import com.ethermod.item.EtherManipulatorItem;
import com.ethermod.network.ModNetwork;

import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;

/**
 * Client-only input handler.
 *
 * <p>Responsibilities: 1. LMB down/up → BeamActivePayload to the server, which mines in Beam mode
 * and runs the mode's own left click otherwise (LeftClick) 2. Suppresses the vanilla LMB arm-swing
 * 3. Tracks the charge phase, from vanilla use-item ticks (RMB) or the LMB hold (Hydraulic and
 * Pyrotechnic charge on left click too), for the charging orb and the arm pose.
 */
@OnlyIn(Dist.CLIENT)
public class ClientInputHandler {

    /** True while LMB beam fires or RMB charges — read by the arm pose. */
    public static boolean jiggleActive = false;

    private static boolean wasBeamActive = false;
    private static boolean wasSignal;
    private static boolean wasPick;

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.screen != null) return;

        ItemStack held = mc.player.getMainHandItem();
        if (!(held.getItem() instanceof EtherManipulatorItem manip)) {
            cleanup(mc);
            return;
        }

        // scroll-wheel click inspects, aimed at a block or not (vanilla only fires pick block
        // when the crosshair is on something; that one is cancelled below)
        boolean pick = mc.options.keyPickItem.isDown();
        if (pick && !wasPick) com.ethermod.client.ManipulatorAnimations.inspect(mc.player);
        wasPick = pick;

        boolean using = mc.player.isUsingItem();
        com.ethermod.item.OrbMode mode = com.ethermod.item.OrbMode.get(held);
        // Hold modes (Tether rope) have no charging orb or charge sound
        boolean charging = using && !mode.isHold();
        boolean lmbDown = mc.options.keyAttack.isDown();
        boolean signal = false;

        // ---- LMB: the beam in Beam mode, the mode's own left click otherwise ----------
        // Switching mode mid-press ends it; press again for the new mode's left click.
        if (wasBeamActive && mode != lmbMode) {
            endLeftClick(signal);
            lmbBlocked = true;
        }
        if (!lmbDown) lmbBlocked = false;
        if (lmbDown && !using && !lmbBlocked) {
            if (!wasBeamActive) {
                ModNetwork.sendBeamActive(true);
                wasBeamActive = true;
                lmbMode = mode;
                lmbTicks = 0;
                com.ethermod.client.ManipulatorAnimations.leftPress(mc.player, mode);
            }
            lmbTicks++;
            boolean mining = mode == com.ethermod.item.OrbMode.ENERGY;
            // Photonic's Beacon draws as a beam of light (BeamRenderer, in the Light colour)
            if (mining || mode == com.ethermod.item.OrbMode.LIGHT) {
                BeamRenderState.beamActive = true;
                BeamRenderState.stageColorIdx = ManipulatorStage.get(held).ordinal();
                BeamRenderState.beamTicks++;
                if (mining) trackCombatLevel(mc);
                jiggleActive = true;
            }

            // ---- Suppress vanilla LMB arm-swing animation -------------------
            // When holding LMB on a non-attacking item, Minecraft still runs the
            // "swing" animation on the main hand. We reset it every tick so the
            // arm stays still while the beam is firing.
            mc.player.swinging = false;
            mc.player.swingTime = 0;

        } else {
            if (wasBeamActive) endLeftClick(signal);
            if (!charging) jiggleActive = false;
        }

        // Hydraulic / Pyrotechnic charge their left click like an orb
        boolean leftCharge = wasBeamActive && mode.chargesOnLeft() && !using;
        BeamRenderState.leftChargeTicks = leftCharge ? lmbTicks : 0;
        BeamRenderState.leftMode = wasBeamActive && !using ? mode : null;
        BeamRenderState.chargeMode = leftCharge ? mode : charging ? mode.rmbShot() : null;

        // ---- Charge phase + isCharging (updated every tick, not every render frame) ----
        if (charging || leftCharge) {
            int ticksHeld =
                    leftCharge
                            ? lmbTicks
                            : manip.getUseDuration(held, mc.player)
                                    - mc.player.getUseItemRemainingTicks();
            int newPhase =
                    Math.min(2, ticksHeld * 3 / com.ethermod.trait.Traits.chargeTicks(held));
            if (newPhase > BeamRenderState.chargePhase) {
                BeamRenderState.phaseFlash = com.ethermod.config.ClientConfig.reduceFlashing() ? 0.25f : 1f;
                // each charge stage swells a beacon power note, louder and higher (FX bench)
                mc.getSoundManager()
                        .play(
                                net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(
                                        com.ethermod.sound.ModSounds.SHOT_BEACON_POWER_SELECT,
                                        1.1f + 0.25f * newPhase,
                                        0.2f + 0.05f * newPhase));
            }
            BeamRenderState.chargePhase = newPhase;
            BeamRenderState.chargeProgress =
                    Math.min(1f, ticksHeld / (float) com.ethermod.trait.Traits.chargeTicks(held));
            BeamRenderState.phaseFlash *= 0.7f;
            BeamRenderState.isCharging = true;
            jiggleActive = true;
        } else {
            BeamRenderState.chargePhase = 0;
            BeamRenderState.chargeProgress = 0f;
            BeamRenderState.phaseFlash = 0f;
            BeamRenderState.isCharging = false;
        }
    }

    /** The mode LMB went down in, its ticks held, and whether it waits for a fresh press. */
    private static com.ethermod.item.OrbMode lmbMode;

    private static int lmbTicks;
    private static boolean lmbBlocked;

    private static void endLeftClick(boolean signal) {
        ModNetwork.sendBeamActive(false);
        wasBeamActive = false;
        if (!signal) BeamRenderState.clearBeam();
        lmbTicks = 0; // (pressure stays and drains on its own, like the server's)
    }


    /** The beam's pressure, as the server reports it (BeamPulsePayload), for visuals and sound. */
    private static void trackCombatLevel(Minecraft mc) {
        int lvl = com.ethermod.beam.RemoteBeams.level(mc.player.getId());
        if (lvl > BeamRenderState.combatLevel && lmbTicks > 3) BeamAudio.levelUp(lvl);
        else if (lvl < BeamRenderState.combatLevel && lmbTicks > 3) BeamAudio.levelDown(lvl);
        BeamRenderState.combatLevel = lvl;
    }

    /**
     * LMB with the Manipulator is the beam only: no vanilla punch or block breaking alongside it,
     * and no arm swing.
     */
    @SubscribeEvent
    public static void onInteractionKey(InputEvent.InteractionKeyMappingTriggered event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        // Scroll-wheel click (pick block) while holding the Manipulator: inspect it instead
        if (event.isPickBlock()
                && mc.player.getMainHandItem().getItem() instanceof EtherManipulatorItem) {
            event.setSwingHand(false);
            event.setCanceled(true); // the client tick starts the inspect
            return;
        }
        if (!event.isAttack()) return;
        if (mc.player.getMainHandItem().getItem() instanceof EtherManipulatorItem) {
            event.setSwingHand(false);
            event.setCanceled(true);
        }
    }

    /**
     * Runs every tick while the Manipulator isn't held. Only undo our own state, once: clearing the
     * swing here every tick would freeze the swing animation of every other item.
     */
    private static void cleanup(Minecraft mc) {
        if (!wasBeamActive
                && !jiggleActive
                && !BeamRenderState.beamActive
                && !BeamRenderState.isCharging) return;
        if (wasBeamActive) {
            ModNetwork.sendBeamActive(false);
            wasBeamActive = false;
        }
        BeamRenderState.clearBeam();
        BeamRenderState.chargeMode = null;
        BeamRenderState.leftChargeTicks = 0;
        BeamRenderState.leftMode = null;
        jiggleActive = false;
    }
}
