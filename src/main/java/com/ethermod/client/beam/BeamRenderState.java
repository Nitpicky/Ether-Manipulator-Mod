package com.ethermod.client.beam;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/** Shared client render state for the beam and the charging orb. */
@OnlyIn(Dist.CLIENT)
public final class BeamRenderState {

    private BeamRenderState() {}

    // ---- LMB beam -----------------------------------------------------------
    public static boolean beamActive = false;
    public static int stageColorIdx = 0;

    /** Client ticks since the beam started (mining ramp + start-up visuals). */
    public static int beamTicks = 0;

    /** Client-side estimate of the Symmetra combat level (1-3), for visuals and sound. */
    public static int combatLevel = 1;

    // ---- RMB charge ---------------------------------------------------------
    public static boolean isCharging = false;
    public static int chargePhase = 0;

    /** 0..1 charge towards full (phase 2). */
    public static float chargeProgress = 0f;

    /** 1 right after a new charge phase is reached, decaying to 0 (swell effect). */
    public static float phaseFlash = 0f;

    /** The projectile charging now (the ice shard on Hydraulic RMB, the blob on its LMB). */
    public static com.ethermod.item.OrbMode chargeMode = null;

    /** Left-click charge in progress (Hydraulic / Pyrotechnic), for the model's animation. */
    public static int leftChargeTicks = 0;

    /** The mode whose left click is held right now (null when LMB is up), for the animations. */
    public static com.ethermod.item.OrbMode leftMode = null;

    public static void clearBeam() {
        beamActive = false;
        beamTicks = 0;
        combatLevel = 1;
        isCharging = false;
    }
}
