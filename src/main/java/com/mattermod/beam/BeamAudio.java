package com.mattermod.beam;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.function.IntSupplier;

/**
 * Beam sounds: a looping hum that follows each beaming player and rises in pitch with the combat
 * level, plus start, stop and level-up cues. Built from vanilla sounds, so no resource pack is
 * needed.
 */
@OnlyIn(Dist.CLIENT)
public final class BeamAudio {

    private BeamAudio() {}

    private static final Map<Integer, BeamLoop> LOOPS = new HashMap<>();

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            LOOPS.clear();
            RemoteBeams.clear();
            LeftClickFxData.clear();
            return;
        }
        RemoteBeams.tick(mc.level.getGameTime());
        LeftClickFxData.tick(mc.level.getGameTime());
        com.mattermod.client.ManipulatorAnimations.tick();

        // Local player
        int self = mc.player.getId();
        if (BeamRenderState.beamActive) {
            ensureLoop(mc, mc.player, () -> BeamRenderState.combatLevel);
        }

        // Others
        for (Map.Entry<Integer, Long> e : RemoteBeams.active().entrySet()) {
            if (e.getKey() == self) continue;
            Entity ent = mc.level.getEntity(e.getKey());
            if (ent instanceof Player p) {
                long start = e.getValue();
                ensureLoop(
                        mc,
                        p,
                        () -> {
                            long held = mc.level.getGameTime() - start;
                            return held >= 40 ? 3 : held >= 20 ? 2 : 1;
                        });
            }
        }
        LOOPS.values().removeIf(BeamLoop::isStopped);

        tickCharge(mc);
    }

    // =========================================================================
    // Orb charge: a rising hum plus pulses that get louder and faster
    // =========================================================================

    private static ChargeLoop chargeLoop;
    private static float pulseDebt;

    private static void tickCharge(Minecraft mc) {
        if (!BeamRenderState.isCharging) {
            pulseDebt = 0;
            return;
        }
        float c = Math.max(0f, Math.min(1f, BeamRenderState.chargeProgress));
        if (chargeLoop == null || chargeLoop.isStopped()) {
            chargeLoop = new ChargeLoop(mc.player);
            mc.getSoundManager().play(chargeLoop);
        }
        // Pulses: about 2 per second when you start, 10 per second at full charge
        pulseDebt += 0.1f + 0.4f * c * c;
        if (pulseDebt >= 1f) {
            pulseDebt -= 1f;
            mc.level.playLocalSound(
                    mc.player.getX(),
                    mc.player.getEyeY(),
                    mc.player.getZ(),
                    SoundEvents.AMETHYST_BLOCK_CHIME,
                    SoundSource.PLAYERS,
                    0.25f + 0.55f * c,
                    0.7f + 1.1f * c,
                    false);
        }
    }

    private static final class ChargeLoop extends AbstractTickableSoundInstance {
        private final Player player;

        ChargeLoop(Player player) {
            super(SoundEvents.BEACON_AMBIENT, SoundSource.PLAYERS, RandomSource.create());
            this.player = player;
            this.looping = true;
            this.delay = 0;
            this.volume = 0.15f;
            this.pitch = 0.6f;
            x = player.getX();
            y = player.getEyeY();
            z = player.getZ();
        }

        @Override
        public void tick() {
            if (player.isRemoved() || !BeamRenderState.isCharging) {
                volume *= 0.4f;
                if (volume < 0.02f) stop();
                return;
            }
            x = player.getX();
            y = player.getEyeY();
            z = player.getZ();
            float c = Math.max(0f, Math.min(1f, BeamRenderState.chargeProgress));
            volume = 0.15f + 0.6f * c;
            pitch = 0.6f + 1.2f * c;
        }

        @Override
        public boolean canStartSilent() {
            return true;
        }
    }

    private static void ensureLoop(Minecraft mc, Player p, IntSupplier level) {
        BeamLoop loop = LOOPS.get(p.getId());
        if (loop != null && !loop.isStopped()) return;
        loop = new BeamLoop(p, level);
        LOOPS.put(p.getId(), loop);
        mc.getSoundManager().play(loop);
        mc.level.playLocalSound(
                p.getX(),
                p.getEyeY(),
                p.getZ(),
                SoundEvents.BEACON_ACTIVATE,
                SoundSource.PLAYERS,
                0.35f,
                1.8f,
                false); // power up (was the summon sound)
    }

    /** Local player's combat level went up. */
    public static void levelUp(int level) {
        Minecraft.getInstance()
                .getSoundManager()
                .play(
                        SimpleSoundInstance.forUI(
                                SoundEvents.AMETHYST_BLOCK_CHIME, 0.9f + 0.35f * level, 0.8f));
    }

    private static boolean isBeaming(Player p) {
        Minecraft mc = Minecraft.getInstance();
        if (p == mc.player) return BeamRenderState.beamActive;
        return RemoteBeams.active().containsKey(p.getId());
    }

    private static final class BeamLoop extends AbstractTickableSoundInstance {
        private final Player player;
        private final IntSupplier level;
        private int fadeOut = -1;

        BeamLoop(Player player, IntSupplier level) {
            super(SoundEvents.BEACON_AMBIENT, SoundSource.PLAYERS, RandomSource.create());
            this.player = player;
            this.level = level;
            this.looping = true;
            this.delay = 0;
            this.volume = 0.7f;
            this.pitch = 1.6f;
            follow();
        }

        private void follow() {
            x = player.getX();
            y = player.getEyeY();
            z = player.getZ();
        }

        @Override
        public void tick() {
            if (player.isRemoved()) {
                stop();
                return;
            }
            follow();
            if (fadeOut < 0 && !isBeaming(player)) {
                fadeOut = 3;
                Minecraft.getInstance()
                        .level
                        .playLocalSound(
                                x,
                                y,
                                z,
                                SoundEvents.BEACON_DEACTIVATE,
                                SoundSource.PLAYERS,
                                0.35f,
                                1.8f,
                                false); // power down
            }
            if (fadeOut >= 0) {
                volume *= 0.5f;
                if (fadeOut-- == 0) stop();
                return;
            }
            float target = 1.5f + 0.2f * level.getAsInt();
            pitch += (target - pitch) * 0.2f;
        }

        @Override
        public boolean canStartSilent() {
            return true;
        }
    }
}
