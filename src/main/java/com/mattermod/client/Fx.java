package com.mattermod.client;

import com.mattermod.MatterMod;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexSorting;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

import org.joml.Matrix4f;
import org.joml.Matrix4fStack;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL30;

import java.util.Arrays;

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
 * <p>With a shader pack (Iris) the pack finishes the world itself, so the same quads are drawn into
 * the world at the end of its own passes (after weather) as an emissive beacon-beam type that
 * writes depth: the pack's clouds and fog then see the effects as solid and stay behind them.
 */
@OnlyIn(Dist.CLIENT)
public final class Fx {

    private Fx() {}

    // =========================================================================
    // Recording
    // =========================================================================

    /** A growable list of quads: position (camera-relative) and packed colour per vertex. */
    private static final class Layer implements VertexConsumer {
        float[] pos = new float[3 * 4096];
        int[] col = new int[4096];
        int n; // vertices recorded

        @Override
        public VertexConsumer addVertex(float x, float y, float z) {
            if (n == col.length) {
                col = Arrays.copyOf(col, n * 2);
                pos = Arrays.copyOf(pos, n * 6);
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
            if (n > 0) col[n - 1] = (a & 255) << 24 | (r & 255) << 16 | (g & 255) << 8 | (b & 255);
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

        void clear() {
            n = 0;
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
            };

    private static final Layer BEAM = new Layer();
    private static final Layer GLOW = new Layer();

    /**
     * Where additive effects record their quads. Positions go through the caller's matrix
     * (addVertex(Matrix4f, ...)), which must be camera-relative and unrotated: the world pass uses
     * identity, an entity renderer its pose stack.
     */
    public static VertexConsumer glow() {
        return ShaderCompat.inShadowPass() ? NONE : GLOW;
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

    /** The stage at which the world's effects are produced and everything is drawn. */
    public static RenderLevelStageEvent.Stage drawStage() {
        return ShaderCompat.shadersActive()
                ? RenderLevelStageEvent.Stage.AFTER_WEATHER
                : RenderLevelStageEvent.Stage.AFTER_LEVEL;
    }

    /** Start of a world frame: forget anything a skipped frame left behind. */
    public static void beginFrame() {
        BEAM.clear();
        GLOW.clear();
    }

    private static TextureTarget layer;
    private static boolean depthReady;

    /**
     * Before water and glass are drawn: copies the world's depth so effects are hidden by solid
     * blocks and entities only.
     */
    public static void captureDepth() {
        depthReady = false;
        if (ShaderCompat.shadersActive()) return;
        RenderTarget main = Minecraft.getInstance().getMainRenderTarget();
        int prev = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);
        if (layer == null) {
            layer = new TextureTarget(main.width, main.height, true, Minecraft.ON_OSX);
        } else if (layer.width != main.width || layer.height != main.height) {
            layer.resize(main.width, main.height, Minecraft.ON_OSX);
        }
        layer.copyDepthFrom(main);
        GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, prev);
        depthReady = true;
    }

    /**
     * Runs {@code produce} (which records the world's effects), then draws everything recorded
     * this frame. After the level the camera rotation is no longer applied, so it is set here.
     */
    public static void drawFrame(RenderLevelStageEvent event, Runnable produce) {
        boolean afterLevel = event.getStage() == RenderLevelStageEvent.Stage.AFTER_LEVEL;
        Matrix4fStack mv = RenderSystem.getModelViewStack();
        if (afterLevel) {
            mv.pushMatrix();
            mv.identity();
            mv.mul(event.getModelViewMatrix());
            RenderSystem.applyModelViewMatrix();
            RenderSystem.backupProjectionMatrix();
            RenderSystem.setProjectionMatrix(
                    event.getProjectionMatrix(), VertexSorting.DISTANCE_TO_ORIGIN);
        }
        try {
            produce.run();
            if (BEAM.quads() + GLOW.quads() > 0) {
                if (ShaderCompat.shadersActive()) drawInWorld();
                else if (depthReady) drawLayered();
            }
        } finally {
            BEAM.clear();
            GLOW.clear();
            depthReady = false;
            if (afterLevel) {
                mv.popMatrix();
                RenderSystem.applyModelViewMatrix();
                RenderSystem.restoreProjectionMatrix();
            }
        }
    }

    // =========================================================================
    // Vanilla: off-screen layer laid over the finished world
    // =========================================================================

    private static void drawLayered() {
        RenderTarget main = Minecraft.getInstance().getMainRenderTarget();
        if (layer == null || layer.width != main.width || layer.height != main.height) return;

        if (BEAM.quads() > 0) {
            // brightest wins where ribbons overlap (premultiplied colour)
            begin();
            RenderSystem.blendEquation(GL14.GL_MAX);
            RenderSystem.blendFunc(GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ONE);
            draw(BEAM);
            RenderSystem.blendEquation(GL14.GL_FUNC_ADD);
            composite(main, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA);
        }
        if (GLOW.quads() > 0) {
            // light: adds up
            begin();
            RenderSystem.blendFunc(GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ONE);
            draw(GLOW);
            composite(main, GlStateManager.DestFactor.ONE);
        }

        RenderSystem.depthMask(true);
        RenderSystem.enableDepthTest();
        RenderSystem.depthFunc(GL11.GL_LEQUAL);
        RenderSystem.enableCull();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableBlend();
        main.bindWrite(false);
    }

    /** Binds the layer with empty colour (its depth is the captured one) and sets the draw state. */
    private static void begin() {
        layer.bindWrite(false);
        RenderSystem.clearColor(0f, 0f, 0f, 0f);
        RenderSystem.clear(GL11.GL_COLOR_BUFFER_BIT, Minecraft.ON_OSX);
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        RenderSystem.enableDepthTest();
        RenderSystem.depthFunc(GL11.GL_LEQUAL);
        RenderSystem.depthMask(false);
        RenderSystem.disableCull();
        RenderSystem.enableBlend();
    }

    /** Draws a layer's quads with premultiplied colour (rgb * alpha). */
    private static void draw(Layer l) {
        BufferBuilder b =
                Tesselator.getInstance()
                        .begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        int verts = l.quads() * 4;
        for (int i = 0; i < verts; i++) {
            int c = l.col[i];
            int a = c >>> 24;
            int r = (c >> 16 & 255) * a / 255, g = (c >> 8 & 255) * a / 255, bl = (c & 255) * a / 255;
            b.addVertex(l.pos[3 * i], l.pos[3 * i + 1], l.pos[3 * i + 2]).setColor(r, g, bl, a);
        }
        MeshData mesh = b.build();
        if (mesh != null) BufferUploader.drawWithShader(mesh);
    }

    /** Lays the layer over the main image with ONE + {@code dst} blending. */
    private static void composite(RenderTarget main, GlStateManager.DestFactor dst) {
        main.bindWrite(false);
        RenderSystem.backupProjectionMatrix();
        RenderSystem.setProjectionMatrix(new Matrix4f(), VertexSorting.ORTHOGRAPHIC_Z);
        Matrix4fStack mv = RenderSystem.getModelViewStack();
        mv.pushMatrix();
        mv.identity();
        RenderSystem.applyModelViewMatrix();

        RenderSystem.disableDepthTest();
        RenderSystem.enableBlend();
        RenderSystem.blendFunc(GlStateManager.SourceFactor.ONE, dst);
        RenderSystem.setShader(GameRenderer::getPositionTexShader);
        RenderSystem.setShaderTexture(0, layer.getColorTextureId());
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
        BufferBuilder b =
                Tesselator.getInstance()
                        .begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX);
        b.addVertex(-1f, -1f, 0f).setUv(0f, 0f);
        b.addVertex(1f, -1f, 0f).setUv(1f, 0f);
        b.addVertex(1f, 1f, 0f).setUv(1f, 1f);
        b.addVertex(-1f, 1f, 0f).setUv(0f, 1f);
        BufferUploader.drawWithShader(b.buildOrThrow());

        mv.popMatrix();
        RenderSystem.applyModelViewMatrix();
        RenderSystem.restoreProjectionMatrix();
    }

    // =========================================================================
    // Shader pack: into the world, emissive, solid to the pack
    // =========================================================================

    private static final ResourceLocation WHITE =
            ResourceLocation.fromNamespaceAndPath(MatterMod.MODID, "textures/misc/white.png");

    /** Beacon-beam (every pack draws it as a glow), both sides, writing depth. */
    private static final RenderType SHADER_FX =
            RenderType.create(
                    "mattermod_fx",
                    DefaultVertexFormat.BLOCK,
                    VertexFormat.Mode.QUADS,
                    1536,
                    false,
                    true,
                    RenderType.CompositeState.builder()
                            .setShaderState(RenderStateShard.RENDERTYPE_BEACON_BEAM_SHADER)
                            .setTextureState(
                                    new RenderStateShard.TextureStateShard(WHITE, false, false))
                            .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
                            .setCullState(RenderStateShard.NO_CULL)
                            .setWriteMaskState(RenderStateShard.COLOR_DEPTH_WRITE)
                            .createCompositeState(false));

    private static void drawInWorld() {
        MultiBufferSource.BufferSource buf = Minecraft.getInstance().renderBuffers().bufferSource();
        VertexConsumer vc = buf.getBuffer(SHADER_FX);
        for (Layer l : new Layer[] {BEAM, GLOW}) {
            int verts = l.quads() * 4;
            for (int i = 0; i < verts; i++) {
                int c = l.col[i];
                vc.addVertex(l.pos[3 * i], l.pos[3 * i + 1], l.pos[3 * i + 2])
                        .setColor(c >> 16 & 255, c >> 8 & 255, c & 255, c >>> 24)
                        .setUv(0.5f, 0.5f)
                        .setUv2(240, 240)
                        .setNormal(0f, 1f, 0f);
            }
        }
        buf.endBatch(SHADER_FX);
    }
}
