package com.mattermod.beam;

import com.mattermod.client.ShaderCompat;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexSorting;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import org.joml.Matrix4f;
import org.joml.Matrix4fStack;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL30;

import java.util.ArrayList;
import java.util.List;

/**
 * Draws the beam ribbons so their glow never adds onto itself, without touching anything else in
 * the world.
 *
 * <p>The ribbons go into their own off-screen layer with MAX blending: where a ribbon overlaps
 * itself (a bend, or a beam seen end-on) a pixel keeps the brightest layer instead of adding them
 * up. The layer is then laid over the world with normal (premultiplied) alpha blending, so the beam
 * reads as a soft glow rather than a colour-dodge cut-out. Nothing writes to the world's depth
 * buffer, so clouds, water and particles behind the beam stay intact.
 *
 * <p>Occlusion uses a copy of the world's depth taken before water and other translucent blocks are
 * drawn ({@link #captureDepth}), so the beam stays visible across a water surface, from above or
 * from below.
 *
 * <p>With a shader pack, off-screen layers are not safe, so the ribbons are drawn straight into the
 * world with the vanilla lightning type instead.
 */
@OnlyIn(Dist.CLIENT)
final class BeamRibbons {

    private BeamRibbons() {}

    /** Keeps the brightest of overlapping layers (needs premultiplied colour). */
    private static final RenderStateShard.TransparencyStateShard MAX_BLEND =
            new RenderStateShard.TransparencyStateShard(
                    "mattermod_max_blend",
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

    private static final RenderType LAYER =
            RenderType.create(
                    "mattermod_beam_layer",
                    DefaultVertexFormat.POSITION_COLOR,
                    VertexFormat.Mode.QUADS,
                    4096,
                    false,
                    false,
                    RenderType.CompositeState.builder()
                            .setShaderState(RenderStateShard.RENDERTYPE_LIGHTNING_SHADER)
                            .setTransparencyState(MAX_BLEND)
                            .setDepthTestState(RenderStateShard.LEQUAL_DEPTH_TEST)
                            .setCullState(RenderStateShard.NO_CULL)
                            .setWriteMaskState(RenderStateShard.COLOR_WRITE)
                            .createCompositeState(false));

    private record Quad(
            Vec3 p0, int[] c0, Vec3 p1, int[] c1, Vec3 p2, int[] c2, Vec3 p3, int[] c3) {}

    private static final List<Quad> quads = new ArrayList<>();
    private static TextureTarget layer;
    private static boolean depthReady;
    private static boolean drewLastFrame;

    /** Queues one ribbon quad (camera-relative), drawn at {@link #flush}. */
    static void quad(Vec3 p0, int[] c0, Vec3 p1, int[] c1, Vec3 p2, int[] c2, Vec3 p3, int[] c3) {
        quads.add(new Quad(p0, c0, p1, c1, p2, c2, p3, c3));
    }

    /**
     * Called before translucent blocks are drawn: clears the layer and copies the world's depth
     * into it. Skipped when no beam is likely this frame.
     */
    static void captureDepth() {
        depthReady = false;
        if (ShaderCompat.shadersActive()) return;
        if (!drewLastFrame && !BeamRenderState.beamActive) return;
        RenderTarget main = Minecraft.getInstance().getMainRenderTarget();
        int prev = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);
        if (layer == null) {
            layer = new TextureTarget(main.width, main.height, true, Minecraft.ON_OSX);
        } else if (layer.width != main.width || layer.height != main.height) {
            layer.resize(main.width, main.height, Minecraft.ON_OSX);
        }
        layer.setClearColor(0f, 0f, 0f, 0f);
        layer.clear(Minecraft.ON_OSX);
        layer.copyDepthFrom(main);
        GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, prev);
        depthReady = true;
    }

    static void flush(MultiBufferSource.BufferSource buf, Matrix4f m) {
        drewLastFrame = !quads.isEmpty();
        if (quads.isEmpty()) return;
        RenderTarget main = Minecraft.getInstance().getMainRenderTarget();
        if (!depthReady
                || layer == null
                || layer.width != main.width
                || layer.height != main.height) {
            // shader pack, or the first frame of a beam: draw straight into the world
            emit(buf.getBuffer(RenderType.lightning()), m, false);
            buf.endBatch(RenderType.lightning());
            quads.clear();
            return;
        }
        depthReady = false;

        int prev = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);
        layer.bindWrite(false);
        emit(buf.getBuffer(LAYER), m, true);
        buf.endBatch(LAYER);
        GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, prev);
        composite();
        quads.clear();
    }

    /** Lays the ribbon layer over the world with premultiplied alpha. */
    private static void composite() {
        RenderSystem.backupProjectionMatrix();
        RenderSystem.setProjectionMatrix(new Matrix4f(), VertexSorting.ORTHOGRAPHIC_Z);
        Matrix4fStack mv = RenderSystem.getModelViewStack();
        mv.pushMatrix();
        mv.identity();
        RenderSystem.applyModelViewMatrix();

        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.enableBlend();
        RenderSystem.blendFunc(
                GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA);
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

        RenderSystem.depthMask(true);
        RenderSystem.enableDepthTest();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableBlend();
        mv.popMatrix();
        RenderSystem.applyModelViewMatrix();
        RenderSystem.restoreProjectionMatrix();
    }

    private static void emit(VertexConsumer vc, Matrix4f m, boolean premultiply) {
        for (Quad q : quads) {
            v(vc, m, q.p0, q.c0, premultiply);
            v(vc, m, q.p1, q.c1, premultiply);
            v(vc, m, q.p2, q.c2, premultiply);
            v(vc, m, q.p3, q.c3, premultiply);
        }
    }

    private static void v(VertexConsumer vc, Matrix4f m, Vec3 p, int[] c, boolean premultiply) {
        int r = c[0], g = c[1], b = c[2], a = c[3];
        if (premultiply) {
            r = r * a / 255;
            g = g * a / 255;
            b = b * a / 255;
        }
        vc.addVertex(m, (float) p.x, (float) p.y, (float) p.z).setColor(r, g, b, a);
    }
}
