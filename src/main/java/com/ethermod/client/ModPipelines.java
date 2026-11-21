package com.ethermod.client;

import com.ethermod.EtherMod;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;

import net.minecraft.client.renderer.RenderPipelines;
import net.neoforged.neoforge.client.event.RegisterRenderPipelinesEvent;

/** The mod's own render pipelines (26.3: blend and depth state live in a pipeline). */
public final class ModPipelines {
    private ModPipelines() {}

    /** GUI colour fill that adds light (the K-screen's glows). */
    public static final RenderPipeline GUI_ADDITIVE =
            RenderPipeline.builder(RenderPipelines.GUI_SNIPPET)
                    .withLocation(EtherMod.MODID + ":pipeline/gui_additive")
                    .withColorTargetState(new ColorTargetState(BlendFunction.ADDITIVE))
                    .build();

    /** Textured GUI quad that inverts what is under it (the HUD's ammo digits). */
    public static final RenderPipeline GUI_TEXTURED_INVERT =
            RenderPipeline.builder(RenderPipelines.GUI_TEXTURED_SNIPPET)
                    .withLocation(EtherMod.MODID + ":pipeline/gui_textured_invert")
                    .withColorTargetState(
                            new ColorTargetState(
                                    new BlendFunction(
                                            com.mojang.renderpearl.api.pipeline.BlendFactor.ONE_MINUS_DST_COLOR,
                                            com.mojang.renderpearl.api.pipeline.BlendFactor.ONE_MINUS_SRC_COLOR,
                                            com.mojang.renderpearl.api.pipeline.BlendFactor.ONE,
                                            com.mojang.renderpearl.api.pipeline.BlendFactor.ZERO)))
                    .build();

    public static void register(RegisterRenderPipelinesEvent event) {
        event.registerPipeline(GUI_ADDITIVE);
        event.registerPipeline(GUI_TEXTURED_INVERT);
    }
}
