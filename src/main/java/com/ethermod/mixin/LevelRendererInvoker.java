package com.ethermod.mixin;

import net.minecraft.client.renderer.LevelRenderer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Vanilla's private section rebuild with the "on the main thread, this frame" flag, as dynamic
 * lights mods use it: the Photonic glow's sections are rebuilt at once instead of queued, so the
 * light keeps up with a moving player. Sodium takes this method over and keeps the flag's meaning.
 */
@Mixin(LevelRenderer.class)
public interface LevelRendererInvoker {

    @Invoker("setSectionDirty")
    void ethermod$setSectionDirty(int sectionX, int sectionY, int sectionZ, boolean reRenderOnMainThread);
}
