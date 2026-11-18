package com.ethermod.beam;

import com.ethermod.item.OrbMode;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;

/**
 * The launch of each right-click shot (FX bench, "Proposal" sheet): one short air kick, pitched per
 * Module, under the element's own layer (a splash of water, a crack of ice, a gloop of lava, a
 * struck flint, rising pings of light...). Layers a tick or two apart are scheduled on the server.
 */
public final class ShotSounds {

    private ShotSounds() {}

    public static void launch(Level level, Player p, OrbMode mode, int phase) {
        if (!(level instanceof ServerLevel sl)) return;
        float kick =
                switch (mode) {
                    case LAVA -> 1.2f;
                    case IGNITE -> 1.3f;
                    case LIGHT -> 1.6f;
                    default -> 1.5f;
                };
        play(sl, p, 0, com.ethermod.sound.ModSounds.SHOT_BREEZE_SHOOT, 0.32f, kick - 0.1f * phase);
        switch (mode) {
            case WATER -> play(sl, p, 0, com.ethermod.sound.ModSounds.SHOT_BUCKET_FILL, 0.25f, 1.6f);
            case FROST -> {
                play(sl, p, 0, com.ethermod.sound.ModSounds.SHOT_GLASS_BREAK, 0.08f, 1.9f);
                play(sl, p, 0, com.ethermod.sound.ModSounds.SHOT_POWDER_SNOW_BREAK, 0.25f, 1.6f);
                play(sl, p, 1, com.ethermod.sound.ModSounds.SHOT_GLASS_BREAK, 0.2f, 1.4f);
            }
            case LAVA -> play(sl, p, 0, com.ethermod.sound.ModSounds.SHOT_BUCKET_FILL_LAVA, 0.25f, 1.3f);
            case IGNITE -> {
                play(sl, p, 0, com.ethermod.sound.ModSounds.SHOT_FLINTANDSTEEL_USE, 0.45f, 1.1f);
                play(sl, p, 1, com.ethermod.sound.ModSounds.SHOT_FLINTANDSTEEL_USE, 0.12f, 1.8f);
                play(sl, p, 2, com.ethermod.sound.ModSounds.SHOT_FLINTANDSTEEL_USE, 0.1f, 2f);
            }
            case LIGHT -> {
                play(sl, p, 3, com.ethermod.sound.ModSounds.SHOT_AMETHYST_BLOCK_CHIME, 0.4f, 1.3f);
                play(sl, p, 6, com.ethermod.sound.ModSounds.SHOT_AMETHYST_BLOCK_CHIME, 0.4f, 1.6f);
                play(sl, p, 7, com.ethermod.sound.ModSounds.SHOT_AMETHYST_BLOCK_CHIME, 0.4f, 1.9f);
                play(sl, p, 8, com.ethermod.sound.ModSounds.SHOT_COPPER_BULB_TURN_ON, 0.3f, 1.4f);
            }
            case GROWTH -> {
                play(sl, p, 0, com.ethermod.sound.ModSounds.SHOT_GRASS_HIT, 0.4f, 1.2f);
                play(sl, p, 1, com.ethermod.sound.ModSounds.SHOT_GRASS_HIT, 0.3f, 1.5f);
            }
            default -> {}
        }
    }

    private static void play(ServerLevel sl, Player p, int delay, SoundEvent e, float vol, float pitch) {
        Runnable r = () -> sl.playSound(null, p.getX(), p.getEyeY(), p.getZ(), e, SoundSource.PLAYERS, vol, pitch);
        if (delay <= 0) r.run();
        else com.ethermod.trait.WorldEffects.schedule(sl, delay, r);
    }
}
