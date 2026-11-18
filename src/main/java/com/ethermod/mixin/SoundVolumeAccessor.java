package com.ethermod.mixin;

import net.minecraft.client.resources.sounds.AbstractSoundInstance;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Lets the client volume settings scale a sound before it starts. */
@Mixin(AbstractSoundInstance.class)
public interface SoundVolumeAccessor {
    @Accessor("volume")
    float getVolumeField();

    @Accessor("volume")
    void setVolumeField(float volume);
}
