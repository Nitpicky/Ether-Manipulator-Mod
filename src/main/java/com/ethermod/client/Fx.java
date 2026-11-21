package com.ethermod.client;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;


import java.util.Arrays;
import java.util.Optional;
import java.util.OptionalDouble;

import org.joml.Matrix4fc;
import org.joml.Vector4f;

/**
 * The one place every glowing effect of the mod is drawn: beam, sparks, arcs, block glow, orbs,
 * bolts, markers.
 *
 * <p>Effects never draw themselves. Wherever they are produced (the world pass, an entity
 * renderer) they only record coloured quads, camera-relative, into one of two layers:
 *
 * <ul>
 *   <li>{@link #beam()}: the beam ribbons. Where they overlap, the brightest value wins instead of
 *       adding up, and the result is laid over the world as a soft glow.
 *   <li>{@link #glow()}: everything else, added onto the world as light.
 * </ul>
 *
 * <p>Once per frame, when Minecraft has completely finished the world (clouds, weather, water,
 * particles, and the "Fabulous" merge of all of them), both layers are drawn over it. They are
 * hidden only by solid blocks and entities (a copy of the depth taken before water and glass), so
 * nothing translucent, clouds included, can ever cover them, and they never write depth, so the
 * order of recorded quads never changes the picture.
 *
 * <p>26.3: drawn with the mod's own GPU passes (ModPipelines). The same path works under Iris
 * shader packs (checked with Complementary on CI), so the 1.21 build's separate shader path is gone;
 * only the shadow pass is skipped (ShaderCompat).
 */
@OnlyIn(Dist.CLIENT)
public final class Fx {

    private Fx() {}

    // =========================================================================
    // Recording
    // =========================================================================

    /**
     * Most vertices a layer keeps in one frame (65536 quads, far above what the effects need). If a
     * pipeline never reaches the draw stage the layer would otherwise grow without end; past the
     * cap further quads are dropped (a multiple of 4, so only whole quads are kept).
     */
    private static final int MAX_VERTICES = 4 * 65536;

    /** A growable list of quads: position (camera-relative) and packed colour per vertex. */
    private static final class Layer implements VertexConsumer {
        float[] pos = new float[3 * 4096];
        int[] col = new int[4096];
        int n; // vertices recorded
        boolean dropping; // the last vertex was over the cap: its colour goes nowhere too

        @Override
        public VertexConsumer addVertex(float x, float y, float z) {
            if (n >= MAX_VERTICES) {
                dropping = true;
                return this;
            }
            dropping = false;
            if (n == col.length) {
                int size = Math.min(MAX_VERTICES, n * 2);
                col = Arrays.copyOf(col, size);
                pos = Arrays.copyOf(pos, size * 3);
            }
            pos[3 * n] = x;
            pos[3 * n + 1] = y;
            pos[3 * n + 2] = z;
            col[n] = 0xFFFFFFFF;
            n++;
            return this;
        }

        @Override
        public VertexConsumer setColor(int r, int g, int b, int a) {
            if (n > 0 && !dropping)
                col[n - 1] = (a & 255) << 24 | (r & 255) << 16 | (g & 255) << 8 | (b & 255);
            return this;
        }

        @Override
        public VertexConsumer setUv(float u, float v) {
            return this;
        }

        @Override
        public VertexConsumer setUv1(int u, int v) {
            return this;
        }

        @Override
        public VertexConsumer setUv2(int u, int v) {
            return this;
        }

        @Override
        public VertexConsumer setNormal(float x, float y, float z) {
            return this;
        }

        @Override
        public VertexConsumer setColor(int argb) {
            return setColor(argb >> 16 & 255, argb >> 8 & 255, argb & 255, argb >>> 24);
        }

        @Override
        public VertexConsumer setUv3(float u, float v) {
            return this;
        }

        @Override
        public VertexConsumer setLineWidth(float width) {
            return this;
        }

        void clear() {
            n = 0;
            dropping = false;
        }

        /** Whole quads only: a half-written one at the end is dropped. */
        int quads() {
            return n / 4;
        }
    }

    /** Swallows everything: used where an effect must not be recorded (shadow pass). */
    private static final VertexConsumer NONE =
            new VertexConsumer() {
                public VertexConsumer addVertex(float x, float y, float z) {
                    return this;
                }

                public VertexConsumer setColor(int r, int g, int b, int a) {
                    return this;
                }

                public VertexConsumer setUv(float u, float v) {
                    return this;
                }

                public VertexConsumer setUv1(int u, int v) {
                    return this;
                }

                public VertexConsumer setUv2(int u, int v) {
                    return this;
                }

                public VertexConsumer setNormal(float x, float y, float z) {
                    return this;
                }

                public VertexConsumer setColor(int argb) {
                    return this;
                }

                public VertexConsumer setUv3(float u, float v) {
                    return this;
                }

                public VertexConsumer setLineWidth(float width) {
                    return this;
                }
            };

    private static final Layer BEAM = new Layer();
    private static final Layer GLOW = new Layer();
    /** Soft glow around a brighter core: adds like GLOW, but never writes depth with a pack. */
    private static final Layer SOFT = new Layer();
    /** Glowing block faces: add like GLOW, but with a pack never write depth (they sit on a surface). */
    private static final Layer FACE = new Layer();

    /**
     * Where additive effects record their quads. Positions go through the caller's matrix
     * (addVertex(Matrix4f, ...)), which must be camera-relative and unrotated: the world pass uses
     * identity, an entity renderer its pose stack.
     */
    public static VertexConsumer glow() {
        return ShaderCompat.inShadowPass() ? NONE : GLOW;
    }

    /**
     * Like {@link #glow()}, for a soft shell around a core drawn into glow(): with a shader pack
     * only the core writes depth, so the shell's twisting faces can't cut the rope into bands.
     */
    public static VertexConsumer softGlow() {
        return ShaderCompat.inShadowPass() ? NONE : SOFT;
    }

    /**
     * Like {@link #glow()}, for faces lying on a block: with a pack they write no depth, so the
     * pack's depth effects can't fight the block under them (speckled artifacts).
     */
    public static VertexConsumer faceGlow() {
        return ShaderCompat.inShadowPass() ? NONE : FACE;
    }

    /** Where the beam ribbons record their quads (brightest wins where they overlap). */
    public static VertexConsumer beam() {
        return ShaderCompat.inShadowPass() ? NONE : BEAM;
    }

    /** One beam quad from camera-relative corners and RGBA (0..255) colours. */
    public static void beamQuad(
            Vec3 p0, int[] c0, Vec3 p1, int[] c1, Vec3 p2, int[] c2, Vec3 p3, int[] c3) {
        VertexConsumer vc = beam();
        vc.addVertex((float) p0.x, (float) p0.y, (float) p0.z).setColor(c0[0], c0[1], c0[2], c0[3]);
        vc.addVertex((float) p1.x, (float) p1.y, (float) p1.z).setColor(c1[0], c1[1], c1[2], c1[3]);
        vc.addVertex((float) p2.x, (float) p2.y, (float) p2.z).setColor(c2[0], c2[1], c2[2], c2[3]);
        vc.addVertex((float) p3.x, (float) p3.y, (float) p3.z).setColor(c3[0], c3[1], c3[2], c3[3]);
    }

    // =========================================================================
    // Frame
    // =========================================================================

    /** Start of a world frame: forget anything a skipped frame left behind. */
    public static void beginFrame() {
        BEAM.clear();
        GLOW.clear();
        SOFT.clear();
        FACE.clear();
        WorldDraw.clear();
        WorldDraw.beginFrame();
    }

    private static TextureTarget layer;
    private static boolean depthReady;

    /**
     * Once solid blocks and entities are drawn (before water and glass): copies the world's depth so
     * effects are hidden by those only.
     */
    public static void captureDepth() {
        depthReady = false;
        RenderTarget main = Minecraft.getInstance().gameRenderer.mainRenderTarget();
        if (main.getDepthTexture() == null) return;
        if (layer == null) {
            layer =
                    new TextureTarget(
                            "ethermod fx",
                            main.width,
                            main.height,
                            main.getColorTexture().getFormat(),
                            main.getDepthTexture().getFormat());
        } else if (layer.width != main.width || layer.height != main.height) {
            layer.resize(main.width, main.height);
        }
        layer.copyDepthFrom(main);
        depthReady = true;
    }

    /**
     * Runs {@code produce} (which records the world's effects), then draws everything recorded
     * this frame. {@code modelView} is the camera rotation (effects are recorded camera-relative).
     */
    public static void drawFrame(Matrix4fc modelView, Runnable produce) {
        try {
            produce.run();
            WorldDraw.flush(modelView);
            if (depthReady && BEAM.quads() + GLOW.quads() + SOFT.quads() + FACE.quads() > 0)
                drawLayered(modelView);
        } finally {
            BEAM.clear();
            GLOW.clear();
            SOFT.clear();
            FACE.clear();
            WorldDraw.clear();
            depthReady = false;
        }
    }

    // =========================================================================
    // Off-screen layer laid over the finished world
    // =========================================================================

    private static final ByteBufferBuilder MEMORY = new ByteBufferBuilder(1 << 18);

    private static void drawLayered(Matrix4fc modelView) {
        RenderTarget main = Minecraft.getInstance().gameRenderer.mainRenderTarget();
        if (layer == null || layer.width != main.width || layer.height != main.height) return;
        var encoder = RenderSystem.getDevice().createCommandEncoder();
        if (BEAM.quads() > 0) {
            // brightest wins where ribbons overlap
            drawLayer(encoder, modelView, ModPipelines.FX_BEAM, BEAM);
            composite(encoder, main, ModPipelines.FX_COMPOSITE_OVER);
        }
        if (GLOW.quads() + SOFT.quads() + FACE.quads() > 0) {
            // light: adds up
            drawLayer(encoder, modelView, ModPipelines.FX_GLOW, FACE, SOFT, GLOW);
            composite(encoder, main, ModPipelines.FX_COMPOSITE_ADD);
        }
    }

    /** Clears the layer's colour (its depth is the captured one) and draws the given layers into it. */
    private static void drawLayer(
            com.mojang.renderpearl.api.commands.CommandEncoder encoder,
            Matrix4fc modelView,
            com.mojang.renderpearl.api.pipeline.RenderPipeline pipeline,
            Layer... layers) {
        BufferBuilder b =
                new BufferBuilder(
                        MEMORY,
                        com.mojang.renderpearl.api.pipeline.PrimitiveTopology.QUADS,
                        DefaultVertexFormat.POSITION_COLOR);
        int quads = 0;
        for (Layer l : layers) {
            int verts = l.quads() * 4;
            quads += l.quads();
            for (int i = 0; i < verts; i++) {
                // premultiplied colour (rgb * alpha)
                int c = l.col[i];
                int a = c >>> 24;
                int r = (c >> 16 & 255) * a / 255, g = (c >> 8 & 255) * a / 255, bl = (c & 255) * a / 255;
                b.addVertex(l.pos[3 * i], l.pos[3 * i + 1], l.pos[3 * i + 2]).setColor(r, g, bl, a);
            }
        }
        MeshData mesh = b.build();
        try (var pass =
                encoder.createRenderPass(
                        () -> "ethermod fx layer",
                        layer.getColorTextureView(),
                        Optional.<org.joml.Vector4fc>of(new Vector4f(0, 0, 0, 0)),
                        layer.getDepthTextureView(),
                        OptionalDouble.empty())) {
            if (mesh == null) return;
            var vertices = WorldDraw.upload("ethermod fx", mesh);
            mesh.close();
            WorldDraw.drawQuads(pass, pipeline, vertices, quads, modelView);
        }
    }

    /** Lays the layer over the main image with the given blend. */
    private static void composite(
            com.mojang.renderpearl.api.commands.CommandEncoder encoder,
            RenderTarget main,
            com.mojang.renderpearl.api.pipeline.RenderPipeline pipeline) {
        try (var pass =
                encoder.createRenderPass(
                        () -> "ethermod fx composite", main.getColorTextureView(), Optional.empty())) {
            pass.setPipeline(RenderSystem.getCompiledPipeline(pipeline));
            RenderSystem.bindDefaultUniforms(pass);
            pass.setUniform(
                    "InSampler",
                    layer.getColorTextureView(),
                    RenderSystem.getSamplerCache()
                            .getClampToEdge(com.mojang.renderpearl.api.textures.FilterMode.NEAREST));
            pass.draw(3, 1, 0, 0);
        }
    }
}
