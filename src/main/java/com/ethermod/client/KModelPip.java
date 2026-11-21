package com.ethermod.client;

import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.pip.PictureInPictureRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.state.gui.pip.PictureInPictureRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;

import org.joml.Quaternionf;
import org.joml.Vector2f;

/**
 * 26.3: the K-screen turntable. GUI 3D is drawn into its own texture (a picture in picture), so
 * {@link KModelView} hands over the stack, the box it fills and its turn; this draws it.
 */
public final class KModelPip extends PictureInPictureRenderer<KModelPip.State> {

    /** One frame of the turntable: a screen box, the model's pixels per block, and its turn. */
    public record State(
            ItemStack stack,
            float pitch,
            float yaw,
            int x0,
            int y0,
            int x1,
            int y1,
            float scale,
            ScreenRectangle scissorArea,
            ScreenRectangle bounds)
            implements PictureInPictureRenderState {}

    private final ItemStackRenderState item = new ItemStackRenderState();

    /** Queues the model in a box given in the current GUI pose's units (centre cx, cy). */
    public static void submit(
            GuiGraphicsExtractor g, ItemStack stack, float cx, float cy, float halfW, float halfH, float scale,
            float pitch, float yaw) {
        Vector2f a = g.pose().transformPosition(cx - halfW, cy - halfH, new Vector2f());
        Vector2f b = g.pose().transformPosition(cx + halfW, cy + halfH, new Vector2f());
        float k = (b.x - a.x) / (2 * halfW);
        int x0 = Math.round(a.x), y0 = Math.round(a.y), x1 = Math.round(b.x), y1 = Math.round(b.y);
        ScreenRectangle scissor = g.peekScissorStack();
        ScreenRectangle bounds = PictureInPictureRenderState.getBounds(x0, y0, x1, y1, scissor);
        g.submitPictureInPictureRenderState(
                new State(stack.copy(), pitch, yaw, x0, y0, x1, y1, scale * k, scissor, bounds));
    }

    @Override
    public Class<State> getRenderStateClass() {
        return State.class;
    }

    @Override
    protected void renderToTexture(State state, PoseStack pose, SubmitNodeCollector collector) {
        var mc = Minecraft.getInstance();
        // GUI y runs down, the model's up
        pose.scale(1, -1, -1);
        pose.rotate(new Quaternionf().rotateX(.25f + state.pitch()).rotateY(state.yaw()));
        mc.getItemModelResolver().updateForTopItem(item, state.stack(), ItemDisplayContext.FIXED, mc.level, null, 0);
        mc.gameRenderer.lighting().setupFor(Lighting.Entry.ITEMS_3D);
        item.submit(pose, collector, 0xF000F0, OverlayTexture.NO_OVERLAY, 0);
    }

    protected String getTextureLabel() {
        return "ethermod model view";
    }
}
