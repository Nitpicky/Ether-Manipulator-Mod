package com.ethermod.client.beam;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.RenderPlayerEvent;

/** Tells {@link Muzzle} whose third-person model is being drawn. */
@OnlyIn(Dist.CLIENT)
public final class MuzzleEvents {

    private MuzzleEvents() {}

    @SubscribeEvent
    public static void onRenderPlayerPre(RenderPlayerEvent.Pre<?> event) {
        Muzzle.renderingPlayer = event.getRenderState().id;
    }

    @SubscribeEvent
    public static void onRenderPlayerPost(RenderPlayerEvent.Post<?> event) {
        Muzzle.renderingPlayer = -1;
    }
}
