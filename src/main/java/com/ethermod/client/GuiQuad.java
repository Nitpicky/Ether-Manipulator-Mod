package com.ethermod.client;

import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.state.gui.GuiElementRenderState;

import org.joml.Matrix3x2f;
import org.joml.Matrix3x2fc;

/** A free-form coloured quad in the GUI (26.3 GUI drawing is recorded as render states). */
record GuiQuad(
        RenderPipeline pipeline, Matrix3x2fc pose, float[] xs, float[] ys, int argb, ScreenRectangle bounds)
        implements GuiElementRenderState {

    /** Four corners in order; a triangle repeats one corner. */
    static void submit(GuiGraphicsExtractor g, float[] xs, float[] ys, int argb) {
        submit(g, RenderPipelines.GUI, xs, ys, argb);
    }

    static void submit(GuiGraphicsExtractor g, RenderPipeline pipeline, float[] xs, float[] ys, int argb) {
        var pose = new Matrix3x2f(g.pose());
        float x0 = Math.min(Math.min(xs[0], xs[1]), Math.min(xs[2], xs[3]));
        float x1 = Math.max(Math.max(xs[0], xs[1]), Math.max(xs[2], xs[3]));
        float y0 = Math.min(Math.min(ys[0], ys[1]), Math.min(ys[2], ys[3]));
        float y1 = Math.max(Math.max(ys[0], ys[1]), Math.max(ys[2], ys[3]));
        var rect =
                new ScreenRectangle(
                                (int) Math.floor(x0),
                                (int) Math.floor(y0),
                                (int) Math.ceil(x1 - x0) + 1,
                                (int) Math.ceil(y1 - y0) + 1)
                        .transformMaxBounds(pose);
        g.submitGuiElementRenderState(new GuiQuad(pipeline, pose, xs, ys, argb, rect));
    }

    @Override
    public void buildVertices(VertexConsumer vc) {
        for (int i = 0; i < 4; i++) vc.addVertexWith2DPose(pose, xs[i], ys[i]).setColor(argb);
    }

    @Override
    public TextureSetup textureSetup() {
        return TextureSetup.noTexture();
    }

    @Override
    public ScreenRectangle scissorArea() {
        return null;
    }
}
