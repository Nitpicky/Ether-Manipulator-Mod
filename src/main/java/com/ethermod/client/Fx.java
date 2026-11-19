package com.ethermod.client;

import com.ethermod.EtherMod;
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
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
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
 * the world at the end of its own passes (after weather) as a beacon-beam glow that writes depth:
 * the pack's clouds then see the effects as solid and stay behind them.
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
    /** Soft glow around a brighter core: adds like GLOW, but never writes depth with a pack. */
    private static final Layer SOFT = new Layer();

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
        if (!ShaderCompat.shadersActive()) return RenderLevelStageEvent.Stage.AFTER_LEVEL;
        // drawn with the particles (the pack places them right, before its clouds), or late
        return PARTICLE_STAGE
                ? RenderLevelStageEvent.Stage.AFTER_PARTICLES
                : RenderLevelStageEvent.Stage.AFTER_WEATHER;
    }

    /** Start of a world frame: forget anything a skipped frame left behind. */
    public static void beginFrame() {
        BEAM.clear();
        GLOW.clear();
        SOFT.clear();
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
            if (BEAM.quads() + GLOW.quads() + SOFT.quads() > 0) {
                if (ShaderCompat.shadersActive()) drawInWorld();
                else if (depthReady) drawLayered();
            }
        } finally {
            BEAM.clear();
            GLOW.clear();
        SOFT.clear();
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
        if (GLOW.quads() + SOFT.quads() > 0) {
            // light: adds up
            begin();
            RenderSystem.blendFunc(GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ONE);
            draw(SOFT);
            draw(GLOW);
            composite(main, GlStateManager.DestFactor.ONE);
        }

        RenderSystem.polygonOffset(0f, 0f);
        RenderSystem.disablePolygonOffset();
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
        // glows sit a hair off a block's face; without a pull toward the camera the copied depth
        // wins on some faces (most at grazing angles), so a glowing block lit only some sides
        RenderSystem.polygonOffset(-1f, -10f);
        RenderSystem.enablePolygonOffset();
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
    // Shader pack: into the world as a beacon-beam glow, solid to the pack
    // =========================================================================

    private static final ResourceLocation WHITE =
            ResourceLocation.fromNamespaceAndPath(EtherMod.MODID, "textures/misc/white.png");

    /**
     * How the effects are drawn with a shader pack. The pack decides which of its programs runs a
     * render type and when its clouds and sky are laid over, so each setup is checked with a real
     * pack on CI (-PfxVariant picks one there):
     *
     * <ul>
     *   <li>{@code beacon}: beacon-beam program, translucent, writes depth (in front of clouds,
     *       but packs light it like a solid: washed-out white)
     *   <li>{@code beacon_add}: beacon-beam program, added light, writes depth
     *   <li>{@code eyes}: eyes program (Enderman eyes), added light, writes depth
     *   <li>{@code eyes2}: eyes program colour only, then a depth-only pass
     *   <li>{@code emissive}: glowing-entity program, added light, writes depth
     *   <li>{@code particle}: the particle program in the particle stage (the beam's End Rod
     *       sparks look right with packs), added light, writes depth
     *   <li>{@code <setup>_p}: that setup, drawn in the particle stage
     * </ul>
     */
    static final String VARIANT = System.getProperty("ethermod.fxVariant", "particle_sort");

    /** {@code particle}, or any setup ending in {@code _p}: drawn in the particle stage. */
    static final boolean PARTICLE_STAGE = VARIANT.startsWith("particle") || VARIANT.endsWith("_p");

    /** Brightest wins where beam strands overlap (as without a pack), instead of adding up. */
    private static final RenderStateShard.TransparencyStateShard MAX_TRANSPARENCY =
            new RenderStateShard.TransparencyStateShard(
                    "ethermod_max",
                    () -> {
                        RenderSystem.enableBlend();
                        RenderSystem.blendEquation(GL14.GL_MAX);
                        RenderSystem.blendFunc(
                                GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ONE);
                    },
                    () -> {
                        RenderSystem.blendEquation(GL14.GL_FUNC_ADD);
                        RenderSystem.disableBlend();
                        RenderSystem.defaultBlendFunc();
                    });

    /**
     * Depth-only pass that can't change the picture: packs may run their own colour output whatever
     * the write mask says (a plain depth pass came out as flat beam or as sky), so the blend keeps
     * what is there (source x 0 + destination x 1).
     */
    private static final RenderStateShard.TransparencyStateShard KEEP_TRANSPARENCY =
            new RenderStateShard.TransparencyStateShard(
                    "ethermod_keep",
                    () -> {
                        RenderSystem.enableBlend();
                        RenderSystem.blendFunc(
                                GlStateManager.SourceFactor.ZERO, GlStateManager.DestFactor.ONE);
                    },
                    () -> {
                        RenderSystem.disableBlend();
                        RenderSystem.defaultBlendFunc();
                    });

    /**
     * {@code particle_keep}: as without a pack (beam brightest-wins, glows add, no depth write),
     * then a depth pass that keeps the colour, so the pack's clouds stay behind.
     *
     * <p>{@code particle_max}: beam brightest-wins; {@code particle_max_nd}: and no depth write;
     * {@code particle_max_d2}: colour first without depth (the beam can't cut its own segments
     * into bands), then a depth-only pass so the pack's clouds stay behind.
     */
    static boolean particleFamily() {
        return VARIANT.startsWith("particle");
    }

    private static RenderType shaderType(String name, boolean depthOnly) {
        return shaderType(name, depthOnly, false);
    }

    private static RenderType shaderType(String name, boolean depthOnly, boolean beamLayer) {
        return shaderType(name, depthOnly, beamLayer, false);
    }

    private static RenderType shaderType(
            String name, boolean depthOnly, boolean beamLayer, boolean soft) {
        boolean beacon = VARIANT.startsWith("beacon");
        boolean particle = particleFamily();
        RenderStateShard.ShaderStateShard shader =
                particle
                        ? new RenderStateShard.ShaderStateShard(GameRenderer::getParticleShader)
                        : beacon
                        ? RenderStateShard.RENDERTYPE_BEACON_BEAM_SHADER
                        : VARIANT.equals("emissive")
                                ? RenderStateShard.RENDERTYPE_ENTITY_TRANSLUCENT_EMISSIVE_SHADER
                                : RenderStateShard.RENDERTYPE_EYES_SHADER;
        var b =
                RenderType.CompositeState.builder()
                        .setShaderState(shader)
                        .setTextureState(new RenderStateShard.TextureStateShard(WHITE, false, false))
                        .setCullState(RenderStateShard.NO_CULL)
                        .setLayeringState(RenderStateShard.POLYGON_OFFSET_LAYERING);
        if (VARIANT.equals("emissive") || particle)
            b.setLightmapState(RenderStateShard.LIGHTMAP).setOverlayState(RenderStateShard.OVERLAY);
        if (depthOnly) {
            if (VARIANT.equals("particle_keep")) b.setTransparencyState(KEEP_TRANSPARENCY);
            b.setWriteMaskState(
                    VARIANT.equals("particle_keep")
                            ? RenderStateShard.COLOR_DEPTH_WRITE
                            : RenderStateShard.DEPTH_WRITE);
        } else {
            b.setTransparencyState(
                    VARIANT.equals("beacon")
                            ? RenderStateShard.TRANSLUCENT_TRANSPARENCY
                            : beamLayer
                                            && (VARIANT.startsWith("particle_max")
                                                    || VARIANT.equals("particle_keep")
                                                    || VARIANT.equals("particle_sort"))
                                    ? MAX_TRANSPARENCY
                                    : RenderStateShard.ADDITIVE_TRANSPARENCY);
            b.setWriteMaskState(
                    soft
                                    || VARIANT.equals("eyes2")
                                    || VARIANT.equals("particle_max_nd")
                                    || VARIANT.equals("particle_max_d2")
                                    || VARIANT.equals("particle_keep")
                            ? RenderStateShard.COLOR_WRITE
                            : RenderStateShard.COLOR_DEPTH_WRITE);
        }
        return RenderType.create(
                name,
                particle
                        ? DefaultVertexFormat.PARTICLE
                        : beacon ? DefaultVertexFormat.BLOCK : DefaultVertexFormat.NEW_ENTITY,
                VertexFormat.Mode.QUADS,
                1536,
                false,
                !depthOnly,
                b.createCompositeState(false));
    }

    private static final RenderType SHADER_FX = shaderType("ethermod_fx", false);
    private static final RenderType SHADER_FX_BEAM = shaderType("ethermod_fx_beam", false, true);
    private static final RenderType SHADER_FX_SOFT =
            shaderType("ethermod_fx_soft", false, false, true);
    private static final RenderType SHADER_FX_DEPTH =
            VARIANT.equals("eyes2")
                            || VARIANT.equals("particle_max_d2")
                            || VARIANT.equals("particle_keep")
                    ? shaderType("ethermod_fx_depth", true)
                    : null;

    /**
     * Vertex order for a layer. {@code particle_sort}: quads far to near, so with depth written
     * no part of the beam ever hides a farther part drawn after it (that cut the rope into bands);
     * everything adds up as without a pack, and the depth still keeps the pack's clouds behind.
     */
    private static int[] order(Layer l) {
        int quads = l.quads();
        int[] out = new int[quads * 4];
        Integer[] q = new Integer[quads];
        for (int i = 0; i < quads; i++) q[i] = i;
        if (VARIANT.equals("particle_sort")) {
            double[] d = new double[quads];
            for (int i = 0; i < quads; i++) {
                double x = 0, y = 0, z = 0;
                for (int v = 0; v < 4; v++) {
                    int o = 3 * (4 * i + v);
                    x += l.pos[o];
                    y += l.pos[o + 1];
                    z += l.pos[o + 2];
                }
                d[i] = x * x + y * y + z * z;
            }
            java.util.Arrays.sort(q, (a, b) -> Double.compare(d[b], d[a]));
        }
        for (int i = 0; i < quads; i++) for (int v = 0; v < 4; v++) out[4 * i + v] = 4 * q[i] + v;
        return out;
    }

    private static void drawInWorld() {
        MultiBufferSource.BufferSource buf = Minecraft.getInstance().renderBuffers().bufferSource();
        boolean beacon = VARIANT.startsWith("beacon"), particle = particleFamily();
        boolean premultiply = !VARIANT.equals("beacon"); // added light ignores alpha
        // the beam first (its own blend), then soft shells (no depth), the glows, an optional depth pass
        RenderType[] types = {SHADER_FX_BEAM, SHADER_FX_SOFT, SHADER_FX, SHADER_FX_DEPTH};
        Layer[][] layers = {{BEAM}, {SOFT}, {GLOW}, {BEAM, SOFT, GLOW}};
        for (int t = 0; t < types.length; t++) {
            RenderType type = types[t];
            if (type == null) continue;
            VertexConsumer vc = buf.getBuffer(type);
            for (Layer l : layers[t]) {
                for (int i : order(l)) {
                    int c = l.col[i];
                    int a = c >>> 24, k = premultiply ? a : 255;
                    vc.addVertex(l.pos[3 * i], l.pos[3 * i + 1], l.pos[3 * i + 2])
                            .setColor(
                                    (c >> 16 & 255) * k / 255,
                                    (c >> 8 & 255) * k / 255,
                                    (c & 255) * k / 255,
                                    premultiply ? 255 : a)
                            .setUv(0.5f, 0.5f);
                    if (particle) {
                        vc.setLight(LightTexture.FULL_BRIGHT); // particles carry no normal
                        continue;
                    }
                    if (!beacon) vc.setOverlay(OverlayTexture.NO_OVERLAY);
                    vc.setLight(LightTexture.FULL_BRIGHT).setNormal(0f, 1f, 0f);
                }
            }
            buf.endBatch(type);
        }
    }
}
