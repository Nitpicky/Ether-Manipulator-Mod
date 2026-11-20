package com.ethermod.client.beam;

import com.ethermod.beam.LeftClickFxData;
import com.ethermod.beam.RemoteBeams;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.resources.Identifier;
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

    /** The volume setting for this player's beam: yours, or other players'. */
    private static float own(Entity p) {
        return p == Minecraft.getInstance().player
                ? com.ethermod.config.ClientConfig.beam()
                : com.ethermod.config.ClientConfig.otherBeams();
    }

    /** A one-shot that carries its own volume setting (the sound hook leaves com.ethermod sounds alone). */
    private static final class Once extends SimpleSoundInstance {
        Once(SoundEvent e, SoundSource src, float vol, float pitch, double x, double y, double z) {
            super(e, src, vol, pitch, RandomSource.create(), x, y, z);
        }

        Once(SoundEvent e, float vol, float pitch) { // like forUI: not positioned
            super(e.location(), SoundSource.MASTER, vol, pitch, RandomSource.create(), false, 0, Attenuation.NONE, 0, 0, 0, true);
        }
    }

    private static void at(Entity p, SoundEvent e, float vol, float pitch, float setting) {
        if (setting <= 0) return;
        Minecraft.getInstance().getSoundManager().play(new Once(e, SoundSource.PLAYERS, vol * setting, pitch, p.getX(), p.getEyeY(), p.getZ()));
    }


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
        ElementFx.tick();
        com.ethermod.client.StasisFx.tick();

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
            at(mc.player, SoundEvents.AMETHYST_BLOCK_CHIME, 0.25f + 0.55f * c, 0.7f + 1.1f * c, com.ethermod.config.ClientConfig.shot());
        }
    }

    private static final class ChargeLoop extends AbstractTickableSoundInstance {
        private final Player player;

        ChargeLoop(Player player) {
            super(SoundEvents.BEACON_AMBIENT, SoundSource.PLAYERS, RandomSource.create());
            this.player = player;
            this.looping = true;
            this.delay = 0;
            this.volume = 0.15f * com.ethermod.config.ClientConfig.shot();
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
            volume = (0.15f + 0.6f * c) * com.ethermod.config.ClientConfig.shot();
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
    private static final float[] HUM_V = {0.75f, 0.81f, 0.88f, 0.94f};
    private static final float[] CRACKLE_V = {0.12f, 0.19f, 0.26f, 0.34f};
    private static final float[] SUB_V = {0.44f, 0.6f, 0.79f, 1f};
    /** UI sounds played a few ticks from now (the level-up's second snap, mode-switch cues). */
    private static final java.util.List<Later> LATER = new java.util.ArrayList<>();

    private static final class Later {
        int ticks;
        final SoundEvent event;
        final float vol, pitch;
        final int cut;

        Later(int ticks, SoundEvent event, float vol, float pitch, int cut) {
            this(ticks, event, vol, pitch, cut, com.ethermod.config.ClientConfig.ui());
        }

        Later(int ticks, SoundEvent event, float vol, float pitch, int cut, float setting) {
            this.ticks = ticks;
            this.event = event;
            this.vol = vol * setting;
            this.pitch = pitch;
            this.cut = cut;
        }
    }

    private static void later(int ticks, SoundEvent e, float vol, float pitch) {
        LATER.add(new Later(ticks, e, vol, pitch, 0));
    }

    /**
     * Mode switch: a small cue of the Module it switches to, at the moment the star changes colour
     * (4 ticks into the animation). All levelled to about the same loudness, played whole.
     */
    public static void modeCue(com.ethermod.item.OrbMode m) {
        int t = 4;
        switch (m) {
            case WATER -> later(t, SoundEvents.BUCKET_FILL, 0.3f, 1.2f);
            case LAVA -> later(t, SoundEvents.BUCKET_FILL_LAVA, 0.15f, 1.15f);
            case GUST -> later(t, SoundEvents.BREEZE_IDLE_AIR, 0.2f, 1.25f);
            case LIGHT -> later(t, ev("mode.photonic"), 1f, 1f);
            case TETHER -> later(t, SoundEvents.CHORUS_FRUIT_TELEPORT, 0.07f, 1.4f);
            case GROWTH -> later(t, SoundEvents.GRASS_HIT, 0.9f, 1.1f);
            case MASS -> later(t, SoundEvents.DEEPSLATE_HIT, 0.22f, 1f);
            default -> { // the Plasmic beam: a snap and a short crackle
                later(t, ev("beam.snap"), 0.45f, 1f);
                LATER.add(new Later(t, ev("beam.crackle"), 0.45f, 1.1f, 7));
                later(t + 2, ev("beam.snap"), 0.35f, 1.15f);
            }
        }
    }

    /** A looping sound cut after a few ticks with a short fade (for a burst of crackle). */
    private static final class CutSound extends AbstractTickableSoundInstance {
        private int left;
        private final float v0;

        CutSound(SoundEvent e, float vol, float pitch, int ticks) {
            super(e, SoundSource.MASTER, RandomSource.create());
            looping = true;
            relative = true;
            attenuation = Attenuation.NONE;
            volume = v0 = vol;
            this.pitch = pitch;
            left = ticks;
        }

        @Override
        public void tick() {
            if (--left <= 2) volume = v0 * Math.max(0, left) / 3f;
            if (left <= 0) stop();
        }
    }

    static SoundEvent ev(String name) {
        return SoundEvent.createVariableRangeEvent(
                Identifier.fromNamespaceAndPath("ethermod", name));
    }

    private static void ensureLoop(Minecraft mc, Player p, IntSupplier level) {
        BeamVoice v = VOICES.get(p.getId());
        if (v != null && !v.done) return;
        BeamVoice nv = new BeamVoice(mc, p, level);
        VOICES.put(p.getId(), nv);
        if (nv.light) // Photonic's beacon: vanilla's beacon sounds, as it is vanilla's beacon beam
            at(p, SoundEvents.BEACON_ACTIVATE, 0.3f, 1.15f, own(p));
        else at(p, ev("beam.on"), 0.6f, 1f, own(p));
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
            at(ent, ev("beam.snap"), 0.07f, 1.3f, own(ent));
        }
        VOICES.values().removeIf(v -> v.done);
        HEARD.keySet().retainAll(VOICES.keySet());
        for (var it = LATER.iterator(); it.hasNext(); ) {
            Later o = it.next();
            if (o.ticks-- > 0) continue;
            it.remove();
            if (o.cut > 0) mc.getSoundManager().play(new CutSound(o.event, o.vol, o.pitch, o.cut));
            else if (o.vol > 0) mc.getSoundManager().play(new Once(o.event, o.vol, o.pitch));
        }
    }

    /** Local player's pressure dropped a level: one soft, low snap. */
    public static void levelDown(int level) {
        Minecraft.getInstance().getSoundManager().play(new Once(ev("beam.snap"), 0.06f * com.ethermod.config.ClientConfig.beam(), 0.85f));
    }

    /** Local player's pressure went up: a double snap, louder and higher per level. */
    public static void levelUp(int level) {
        int l = Math.max(1, Math.min(4, level));
        Minecraft.getInstance()
                .getSoundManager()
                .play(new Once(ev("beam.snap"), (0.08f + 0.02f * l) * com.ethermod.config.ClientConfig.beam(), 0.95f + 0.1f * l));
        LATER.add(new Later(1, ev("beam.snap"), 0.06f + 0.02f * l, 1f + 0.1f * l, 0, com.ethermod.config.ClientConfig.beam()));
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
        final boolean light;

        BeamVoice(Minecraft mc, Player p, IntSupplier level) {
            this.mc = mc;
            this.player = p;
            this.level = level;
            lv = lvl();
            light = com.ethermod.item.OrbMode.get(p.getMainHandItem()) == com.ethermod.item.OrbMode.LIGHT;
            if (light) {
                hum = new Layer(player, SoundEvents.BEACON_AMBIENT, 0.45f, 1.15f);
                mc.getSoundManager().play(hum);
                return;
            }
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
                if (crackle != null) crackle.fade();
                if (sub != null) sub.fade();
                done = true;
                if (light) at(player, SoundEvents.BEACON_DEACTIVATE, 0.3f, 1.15f, own(player));
                else at(player, ev("beam.off"), 0.6f, 1f, own(player));
                return;
            }
            if (light) return;
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
            this.volume = vol * 0.5f * own(player);
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
            volume += (tv * own(player) - volume) * 0.35f;
            pitch += (tp - pitch) * 0.35f;
        }

        @Override
        public boolean canStartSilent() {
            return true;
        }
    }
}
