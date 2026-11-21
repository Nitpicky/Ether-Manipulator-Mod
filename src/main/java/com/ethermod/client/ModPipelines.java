package com.ethermod.client;

import com.ethermod.EtherMod;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;

import net.minecraft.client.renderer.RenderPipelines;
import net.neoforged.neoforge.client.event.RegisterRenderPipelinesEvent;

/** The mod's own render pipelines (26.3: blend and depth state live in a pipeline). */
public final class ModPipelines {
    private ModPipelines() {}

    /** GUI colour fill that adds light (the K-screen's glows). */
    public static final RenderPipeline GUI_ADDITIVE =
            RenderPipeline.builder(RenderPipelines.GUI_SNIPPET)
                    .withLocation(net.minecraft.resources.Identifier.fromNamespaceAndPath(EtherMod.MODID, "pipeline/gui_additive"))
                    .withColorTargetState(new ColorTargetState(BlendFunction.ADDITIVE))
                    .build();

    /** Textured GUI quad that inverts what is under it (the HUD's ammo digits). */
    public static final RenderPipeline GUI_TEXTURED_INVERT =
            RenderPipeline.builder(RenderPipelines.GUI_TEXTURED_SNIPPET)
                    .withLocation(net.minecraft.resources.Identifier.fromNamespaceAndPath(EtherMod.MODID, "pipeline/gui_textured_invert"))
                    .withColorTargetState(
                            new ColorTargetState(
                                    new BlendFunction(
                                            com.mojang.renderpearl.api.pipeline.BlendFactor.ONE_MINUS_DST_COLOR,
                                            com.mojang.renderpearl.api.pipeline.BlendFactor.ONE_MINUS_SRC_COLOR,
                                            com.mojang.renderpearl.api.pipeline.BlendFactor.ONE,
                                            com.mojang.renderpearl.api.pipeline.BlendFactor.ZERO)))
                    .build();

    // ---------------------------------------------------------------------------------------
    // World effects (Fx, WorldDraw): drawn by the mod's own passes once the level is finished
    // ---------------------------------------------------------------------------------------

    private static BlendFunction blend(
            com.mojang.renderpearl.api.pipeline.BlendFactor src,
            com.mojang.renderpearl.api.pipeline.BlendFactor dst) {
        return new BlendFunction(src, dst);
    }

    private static final com.mojang.renderpearl.api.pipeline.BlendFactor ONE =
            com.mojang.renderpearl.api.pipeline.BlendFactor.ONE;

    /** Reversed depth (clear to 0) flips which way "towards the camera" is for the bias. */
    private static final float TOWARDS_CAMERA =
            com.mojang.blaze3d.systems.RenderSystem.DEFAULT_DEPTH_CLEAR_VALUE == 0 ? 1f : -1f;

    /** Tested against the copied depth, never written, pulled a hair towards the camera. */
    private static final DepthStencilState FX_DEPTH =
            new DepthStencilState(
                    DepthStencilState.DEFAULT.depthTest(), false, TOWARDS_CAMERA, 10 * TOWARDS_CAMERA);

    private static RenderPipeline.Builder fx(String name) {
        return RenderPipeline.builder(RenderPipelines.DEBUG_FILLED_SNIPPET)
                .withLocation(net.minecraft.resources.Identifier.fromNamespaceAndPath(EtherMod.MODID, "pipeline/" + name))
                .withVertexBinding(0, com.mojang.blaze3d.vertex.DefaultVertexFormat.POSITION_COLOR)
                .withPrimitiveTopology(PrimitiveTopology.QUADS)
                .withCull(false)
                .withDepthStencilState(FX_DEPTH);
    }

    /** Beam ribbons: where they overlap the brightest value wins (premultiplied colour). */
    public static final RenderPipeline FX_BEAM =
            fx("fx_beam").withColorTargetState(new ColorTargetState(BlendFunction.MAX)).build();

    /** Glows: light adds up (premultiplied colour). */
    public static final RenderPipeline FX_GLOW =
            fx("fx_glow").withColorTargetState(new ColorTargetState(blend(ONE, ONE))).build();

    /** Lays the beam layer over the world (premultiplied "over"). */
    public static final RenderPipeline FX_COMPOSITE_OVER =
            RenderPipelines.ENTITY_OUTLINE_BLIT.toBuilder()
                    .withLocation(net.minecraft.resources.Identifier.fromNamespaceAndPath(EtherMod.MODID, "pipeline/fx_composite_over"))
                    .withColorTargetState(
                            new ColorTargetState(
                                    blend(ONE, com.mojang.renderpearl.api.pipeline.BlendFactor.ONE_MINUS_SRC_ALPHA)))
                    .build();

    /** Adds the glow layer onto the world. */
    public static final RenderPipeline FX_COMPOSITE_ADD =
            RenderPipelines.ENTITY_OUTLINE_BLIT.toBuilder()
                    .withLocation(net.minecraft.resources.Identifier.fromNamespaceAndPath(EtherMod.MODID, "pipeline/fx_composite_add"))
                    .withColorTargetState(new ColorTargetState(blend(ONE, ONE)))
                    .build();

    private static RenderPipeline.Builder worldTextured(String name, boolean depthWrite) {
        return RenderPipeline.builder(RenderPipelines.GUI_TEXTURED_SNIPPET)
                .withLocation(net.minecraft.resources.Identifier.fromNamespaceAndPath(EtherMod.MODID, "pipeline/" + name))
                .withPrimitiveTopology(PrimitiveTopology.QUADS)
                .withCull(false)
                .withDepthStencilState(
                        new DepthStencilState(DepthStencilState.DEFAULT.depthTest(), depthWrite));
    }

    /** Photonic's Beacon column: the beacon beam texture added as light. */
    public static final RenderPipeline WORLD_BEACON =
            worldTextured("world_beacon", false)
                    .withColorTargetState(new ColorTargetState(BlendFunction.LIGHTNING))
                    .build();

    /** Airwalk's glass pane: translucent, writes depth. */
    public static final RenderPipeline WORLD_PANE =
            worldTextured("world_pane", true)
                    .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
                    .build();

    /** Airwalk's veins: added light. */
    public static final RenderPipeline WORLD_VEINS =
            worldTextured("world_veins", false)
                    .withColorTargetState(new ColorTargetState(BlendFunction.LIGHTNING))
                    .build();

    /** Ore Sense: coloured boxes seen through everything. */
    public static final RenderPipeline WORLD_XRAY =
            RenderPipeline.builder(RenderPipelines.DEBUG_FILLED_SNIPPET)
                    .withLocation(net.minecraft.resources.Identifier.fromNamespaceAndPath(EtherMod.MODID, "pipeline/world_xray"))
                    .withVertexBinding(0, com.mojang.blaze3d.vertex.DefaultVertexFormat.POSITION_COLOR)
                    .withPrimitiveTopology(PrimitiveTopology.QUADS)
                    .withCull(false)
                    .withDepthStencilState(java.util.Optional.empty())
                    .withColorTargetState(new ColorTargetState(BlendFunction.LIGHTNING))
                    .build();

    public static void register(RegisterRenderPipelinesEvent event) {
        for (var p : new RenderPipeline[] {
                GUI_ADDITIVE, GUI_TEXTURED_INVERT, FX_BEAM, FX_GLOW, FX_COMPOSITE_OVER, FX_COMPOSITE_ADD,
                WORLD_BEACON, WORLD_PANE, WORLD_VEINS, WORLD_XRAY})
            event.registerPipeline(p);
    }
}
