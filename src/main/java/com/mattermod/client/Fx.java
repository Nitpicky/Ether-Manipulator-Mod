package com.mattermod.client;

import com.mattermod.MatterMod;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

import java.util.Arrays;

/**
 * The one place every glowing effect of the mod is drawn: beam, sparks, arcs, block glow, orbs,
 * bolts, markers.
 *
 * <p>Plain and vanilla-like on purpose. Effects record coloured quads (camera-relative, through
 * the caller's matrix) wherever they are produced, and once per frame, right after entities, all
 * of them are drawn as ordinary world geometry with Minecraft's own glowing-entity type
 * (translucent, full bright, writes depth), the same way the game draws a glowing mob. Nothing
 * custom: no off-screen layers, no blending tricks, no late stage. Clouds, water, weather,
 * "Fabulous" graphics and shader packs all treat the effects exactly like any glowing entity.
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
    public static final RenderLevelStageEvent.Stage STAGE =
            RenderLevelStageEvent.Stage.AFTER_ENTITIES;

    /** Start of a world frame: forget anything a skipped frame left behind. */
    public static void beginFrame() {
        BEAM.clear();
        GLOW.clear();
    }

    private static final ResourceLocation WHITE =
            ResourceLocation.fromNamespaceAndPath(MatterMod.MODID, "textures/misc/white.png");

    private static final RenderType TYPE = RenderType.entityTranslucentEmissive(WHITE);

    /**
     * Runs {@code produce} (which records the world's effects), then draws everything recorded this
     * frame, entity renderers included.
     */
    public static void drawFrame(Runnable produce) {
        try {
            produce.run();
            if (BEAM.quads() + GLOW.quads() == 0) return;
            MultiBufferSource.BufferSource buf =
                    Minecraft.getInstance().renderBuffers().bufferSource();
            VertexConsumer vc = buf.getBuffer(TYPE);
            emit(vc, GLOW);
            emit(vc, BEAM);
            buf.endBatch(TYPE);
        } finally {
            BEAM.clear();
            GLOW.clear();
        }
    }

    private static void emit(VertexConsumer vc, Layer l) {
        int verts = l.quads() * 4;
        for (int i = 0; i < verts; i++) {
            int c = l.col[i];
            float x = l.pos[3 * i], y = l.pos[3 * i + 1], z = l.pos[3 * i + 2];
            vc.addVertex(
                    x, y, z,
                    c,
                    0.5f, 0.5f,
                    OverlayTexture.NO_OVERLAY,
                    LightTexture.FULL_BRIGHT,
                    0f, 1f, 0f);
        }
    }
}
