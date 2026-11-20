package com.ethermod.client;

import com.ethermod.config.ClientConfig;
import net.minecraft.client.resources.sounds.AbstractSoundInstance;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.sound.PlaySoundEvent;

/**
 * Applies the client volume settings to every mod sound as it starts, whoever played it: the
 * mod's own beam sounds (beam.*), mode cues (mode.*) and the vanilla sounds it plays under its own
 * names (shot/, ui/, world/, see ModSounds). The mod's own looping sounds scale themselves.
 */
@EventBusSubscriber(modid = "ethermod", value = Dist.CLIENT)
public final class SoundVolumes {

    private SoundVolumes() {}

    @SubscribeEvent
    public static void onPlay(PlaySoundEvent ev) {
        var s = ev.getSound();
        if (s == null || !(s instanceof AbstractSoundInstance a)) return;
        if (!"ethermod".equals(s.location().getNamespace())) return;
        if (s.getClass().getName().startsWith("com.ethermod")) return; // scales itself
        float f = factor(s.location().getPath());
        if (f >= 0.999f) return;
        var acc = (com.ethermod.mixin.SoundVolumeAccessor) a;
        acc.setVolumeField(acc.getVolumeField() * f);
    }

    static float factor(String path) {
        if (path.startsWith("beam.")) return ClientConfig.beam();
        if (path.startsWith("shot/")) return ClientConfig.shot();
        if (path.startsWith("ui/") || path.startsWith("mode.")) return ClientConfig.ui();
        return ClientConfig.master();
    }
}
