package com.ethermod.beam;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
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
 * Beam sounds: the mod's own layered beam (hum per pressure level, crackle, sub body) that follows each
 * beaming player, plus start, stop, strike and level-up cues; and the orb charge.
 */
@OnlyIn(Dist.CLIENT)
public final class BeamAudio {

    private BeamAudio() {}


    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            VOICES.clear();
            RemoteBeams.clear();
            LeftClickFxData.clear();
            return;
        }
        RemoteBeams.tick(mc.level.getGameTime());
        LeftClickFxData.tick(mc.level.getGameTime());
        com.ethermod.client.ManipulatorAnimations.tick();

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
                        () -> RemoteBeams.level(p.getId()));
            }
        }
        strikes(mc);

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

    // =========================================================================
    // Beam: the mod's own layered sound (designed on the FX bench, mod-sounds/ there):
    //  hum   : a soft lightsaber-like buzz, one loop per pressure level (hum1-4). From level 2 a brighter layer
    //          pulses on top, faster each level, and a steady fifth adds presence; levels 2-4 get louder.
    //  crackle: sparse arc discharges under the hum, a touch louder and higher per level.
    //  sub   : a warm low body under the whole beam, stepping up with pressure.
    // All three share one baked reverb, so they read as one sound. No amethyst on the beam.
    // Strikes tick a faint snap; each level-up is marked by a double snap; on/off are the buzz fading in/out.
    // =========================================================================

    private static final Map<Integer, BeamVoice> VOICES = new HashMap<>();
    private static final float[] HUM_V = {0.6f, 0.65f, 0.7f, 0.75f};
    private static final float[] CRACKLE_V = {0.1f, 0.15f, 0.21f, 0.27f};
    private static final float[] SUB_V = {0.35f, 0.48f, 0.63f, 0.8f};
    /** Second snap of a level-up, 0.07s (about 1 tick) after the first. */
    private static final java.util.List<Object[]> LATER = new java.util.ArrayList<>();

    static SoundEvent ev(String name) {
        return SoundEvent.createVariableRangeEvent(
                ResourceLocation.fromNamespaceAndPath("ethermod", name));
    }

    private static void ensureLoop(Minecraft mc, Player p, IntSupplier level) {
        BeamVoice v = VOICES.get(p.getId());
        if (v != null && !v.done) return;
        VOICES.put(p.getId(), new BeamVoice(mc, p, level));
        mc.level.playLocalSound(
                p.getX(), p.getEyeY(), p.getZ(), ev("beam.on"), SoundSource.PLAYERS, 0.6f, 1f, false);
    }

    private static final Map<Integer, Integer> HEARD = new HashMap<>();

    /** Each damage strike: a faint high snap, so the cadence you hear is the damage you deal. */
    private static void strikes(Minecraft mc) {
        for (Map.Entry<Integer, BeamVoice> e : VOICES.entrySet()) {
            e.getValue().tick();
            RemoteBeams.Pulse pu = RemoteBeams.pulseOf(e.getKey());
            if (pu == null) continue;
            Integer last = HEARD.put(e.getKey(), pu.seed);
            if (last == null || last == pu.seed) continue;
            Entity ent = mc.level.getEntity(e.getKey());
            if (ent == null) continue;
            mc.level.playLocalSound(
                    ent.getX(), ent.getEyeY(), ent.getZ(), ev("beam.snap"), SoundSource.PLAYERS, 0.07f, 1.3f, false);
        }
        VOICES.values().removeIf(v -> v.done);
        HEARD.keySet().retainAll(VOICES.keySet());
        for (var it = LATER.iterator(); it.hasNext(); ) {
            Object[] o = it.next();
            if ((int) o[0] > 0) {
                o[0] = (int) o[0] - 1;
                continue;
            }
            Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(ev("beam.snap"), (float) o[2], (float) o[1]));
            it.remove();
        }
    }

    /** Local player's pressure dropped a level: one soft, low snap. */
    public static void levelDown(int level) {
        Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(ev("beam.snap"), 0.85f, 0.06f));
    }

    /** Local player's pressure went up: a double snap, louder and higher per level. */
    public static void levelUp(int level) {
        int l = Math.max(1, Math.min(4, level));
        Minecraft.getInstance()
                .getSoundManager()
                .play(SimpleSoundInstance.forUI(ev("beam.snap"), 0.95f + 0.1f * l, 0.08f + 0.02f * l));
        LATER.add(new Object[] {1, 0.06f + 0.02f * l, 1f + 0.1f * l});
    }

    private static boolean isBeaming(Player p) {
        Minecraft mc = Minecraft.getInstance();
        if (p == mc.player) return BeamRenderState.beamActive;
        return RemoteBeams.active().containsKey(p.getId());
    }

    /** One beaming player's layers: the hum loop of the current level, the crackle and the sub. */
    private static final class BeamVoice {
        final Player player;
        final IntSupplier level;
        final Minecraft mc;
        Layer hum, crackle, sub;
        int lv;
        boolean done;

        BeamVoice(Minecraft mc, Player p, IntSupplier level) {
            this.mc = mc;
            this.player = p;
            this.level = level;
            lv = lvl();
            hum = start("beam.hum" + lv, HUM_V[lv - 1], 1f);
            crackle = start("beam.crackle", CRACKLE_V[lv - 1], 0.94f + 0.06f * lv);
            sub = start("beam.sub", SUB_V[lv - 1], 1f);
        }

        int lvl() {
            return Math.max(1, Math.min(4, level.getAsInt()));
        }

        Layer start(String name, float vol, float pitch) {
            Layer l = new Layer(player, ev(name), vol, pitch);
            mc.getSoundManager().play(l);
            return l;
        }

        void tick() {
            if (done) return;
            if (player.isRemoved() || !isBeaming(player)) {
                hum.fade();
                crackle.fade();
                sub.fade();
                done = true;
                mc.level.playLocalSound(
                        player.getX(), player.getEyeY(), player.getZ(), ev("beam.off"), SoundSource.PLAYERS, 0.6f, 1f, false);
                return;
            }
            int now = lvl();
            if (now != lv) {
                lv = now;
                hum.fade();
                hum = start("beam.hum" + lv, HUM_V[lv - 1], 1f); // each level is its own loop
                crackle.target(CRACKLE_V[lv - 1], 0.94f + 0.06f * lv);
                sub.target(SUB_V[lv - 1], 1f);
            }
        }
    }

    /** A looping layer that follows its player, eases to a target volume/pitch and fades out on demand. */
    private static final class Layer extends AbstractTickableSoundInstance {
        private final Player player;
        private float tv, tp;
        private boolean fading;

        Layer(Player player, SoundEvent event, float vol, float pitch) {
            super(event, SoundSource.PLAYERS, RandomSource.create());
            this.player = player;
            this.looping = true;
            this.delay = 0;
            this.volume = vol * 0.5f;
            this.tv = vol;
            this.pitch = this.tp = pitch;
            follow();
        }

        void target(float vol, float pitch) {
            tv = vol;
            tp = pitch;
        }

        void fade() {
            fading = true;
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
            if (fading) {
                volume *= 0.55f;
                if (volume < 0.01f) stop();
                return;
            }
            volume += (tv - volume) * 0.35f;
            pitch += (tp - pitch) * 0.35f;
        }

        @Override
        public boolean canStartSilent() {
            return true;
        }
    }
}
