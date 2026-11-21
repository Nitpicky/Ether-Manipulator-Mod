package com.ethermod.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.textures.FilterMode;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;

import org.joml.Matrix4f;
import org.joml.Matrix4fc;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 26.3: the few world overlays that are not Fx glows (Photonic's Beacon column, Airwalk's tiles,
 * Ore Sense) are recorded here during the frame, camera-relative, and drawn in one go once the
 * level is finished, with the mod's own pipelines ({@link ModPipelines}).
 */
public final class WorldDraw {
    private WorldDraw() {}

    private record Batch(RenderPipeline pipeline, Identifier texture, boolean repeat) {}

    private static final Map<Batch, BufferBuilder> BATCHES = new LinkedHashMap<>();
    private static final Map<Batch, ByteBufferBuilder> MEMORY = new LinkedHashMap<>();

    /** GPU buffers handed out last frame: freed once the next frame starts. */
    private static final List<GpuBuffer> RETIRED = new ArrayList<>();

    /** Where to record quads for a pipeline (and its texture, or null). */
    public static VertexConsumer buffer(RenderPipeline pipeline, Identifier texture, boolean repeat) {
        Batch key = new Batch(pipeline, texture, repeat);
        return BATCHES.computeIfAbsent(
                key,
                k ->
                        new BufferBuilder(
                                MEMORY.computeIfAbsent(k, m -> new ByteBufferBuilder(1 << 16)),
                                PrimitiveTopology.QUADS,
                                pipeline.getVertexFormatBinding(0)));
    }

    public static VertexConsumer buffer(RenderPipeline pipeline) {
        return buffer(pipeline, null, false);
    }

    /** Frees last frame's buffers. */
    static void beginFrame() {
        for (GpuBuffer b : RETIRED) b.close();
        RETIRED.clear();
    }

    /** Uploads a finished mesh's vertices into a buffer that lives until the next frame. */
    static GpuBuffer upload(String label, MeshData mesh) {
        GpuBuffer b =
                RenderSystem.getDevice()
                        .createBuffer(() -> label, GpuBuffer.USAGE_VERTEX, mesh.vertexBuffer());
        RETIRED.add(b);
        return b;
    }

    /** Draws {@code quads} quads from a vertex buffer in an open pass. */
    static void drawQuads(RenderPass pass, RenderPipeline pipeline, GpuBuffer vertices, int quads, Matrix4fc modelView) {
        drawQuads(pass, pipeline, vertices, quads, modelView, () -> {});
    }

    /** As above; {@code bind} sets the pipeline's extra uniforms (textures) once it is bound. */
    static void drawQuads(
            RenderPass pass, RenderPipeline pipeline, GpuBuffer vertices, int quads, Matrix4fc modelView, Runnable bind) {
        pass.setPipeline(RenderSystem.getCompiledPipeline(pipeline));
        RenderSystem.bindDefaultUniforms(pass);
        bind.run();
        pass.setUniform(
                "DynamicTransforms", RenderSystem.getDynamicUniforms().writeTransform(new Matrix4f(modelView)));
        pass.setVertexBuffer(0, vertices.slice());
        var sequential = RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS);
        int indices = quads * 6;
        pass.setIndexBuffer(sequential.getBuffer(indices), sequential.type());
        pass.drawIndexed(indices, 1, 0, 0, 0);
    }

    /** Draws everything recorded this frame into the main image (with its depth). */
    static void flush(Matrix4fc modelView) {
        if (BATCHES.isEmpty()) return;
        var mc = Minecraft.getInstance();
        var main = mc.gameRenderer.mainRenderTarget();
        // everything that touches the device (uploads, first use of a texture) runs before the pass
        record Ready(Batch batch, GpuBuffer vertices, int quads, com.mojang.renderpearl.api.textures.GpuTextureView texture) {}
        List<Ready> ready = new ArrayList<>();
        try {
            for (var e : BATCHES.entrySet()) {
                MeshData mesh = e.getValue().build();
                if (mesh == null) continue;
                int quads = mesh.drawState().vertexCount() / 4;
                GpuBuffer vertices = upload("ethermod world overlay", mesh);
                mesh.close();
                Batch b = e.getKey();
                var texture = b.texture() == null ? null : mc.getTextureManager().getTexture(b.texture()).getTextureView();
                ready.add(new Ready(b, vertices, quads, texture));
            }
        } finally {
            BATCHES.clear();
        }
        if (ready.isEmpty()) return;
        var encoder = RenderSystem.getDevice().createCommandEncoder();
        var samplers = RenderSystem.getSamplerCache();
        try (RenderPass pass =
                encoder.createRenderPass(
                        () -> "ethermod world overlays",
                        main.getColorTextureView(),
                        Optional.empty(),
                        main.getDepthTextureView(),
                        java.util.OptionalDouble.empty())) {
            for (Ready r : ready)
                drawQuads(pass, r.batch().pipeline(), r.vertices(), r.quads(), modelView, () -> {
                    if (r.texture() == null) return;
                    pass.setUniform(
                            "Sampler0",
                            r.texture(),
                            r.batch().repeat()
                                    ? samplers.getRepeat(FilterMode.NEAREST)
                                    : samplers.getClampToEdge(FilterMode.NEAREST));
                });
        }
    }

    /** Drops anything recorded (a frame that never reached the draw). */
    static void clear() {
        for (var b : BATCHES.values()) {
            MeshData mesh = b.build();
            if (mesh != null) mesh.close();
        }
        BATCHES.clear();
    }
}
