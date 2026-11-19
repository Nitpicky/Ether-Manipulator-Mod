package com.ethermod.client;

import com.mojang.blaze3d.platform.Lighting;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.item.ItemDisplayContext;

import org.joml.Quaternionf;

/** The Manipulator on its turntable: idle spin, drag-to-turn with coasting, double-click level. */
final class KModelView {
    /** The model's idle turntable speed, in radians a second. */
    private static final float SPIN = .7f;

    private Quaternionf returnFrom;
    private double returnStart;
    private float vy, yaw, pitch, spinDir = 1;
    private boolean dragging;
    private double dragAt;

    /** Whether a point in K-window units lies on the model's grab area. */
    static boolean hits(double lx, double ly) {
        return lx >= 17 && lx < 63 && ly >= 20 && ly < 66;
    }

    boolean dragging() {
        return dragging;
    }

    /** A press on the grab area: a quick second press levels the model, otherwise it is grabbed. */
    void press(boolean doubleClick, double now) {
        if (doubleClick) {
            returnFrom = new Quaternionf();
            returnStart = now;
            dragging = false;
        } else {
            dragging = true;
            returnFrom = null;
            dragAt = now;
            vy = 0;
        }
    }

    /** A drag while grabbed; dx and dy are GUI pixels, t is the clock in seconds. */
    void drag(double dx, double dy, float uiScale, double t) {
        double dt = Math.max(.008, t - dragAt);
        // a turntable: sideways drags turn it, up/down tilts it (within limits), so the model
        // always follows the cursor the way it is dragged
        float turn = (float) (dx / uiScale) * .035f, tilt = (float) (dy / uiScale) * .035f;
        yaw += turn;
        pitch = Math.clamp(pitch + tilt, -1.1f, 1.1f);
        vy = (float) (vy * .3 + turn / dt * .7);
        dragAt = t;
    }

    /** Releases a grab, if any; returns whether there was one. */
    boolean release(double t) {
        if (dragging) {
            dragging = false;
            if (t - dragAt > .08) vy = 0;
            vy = Math.clamp(vy, -18, 18);
            if (Math.abs(vy) > .3f) spinDir = Math.signum(vy);
            return true;
        }
        return false;
    }

    void render(GuiGraphics g, KUi ui, double dt) {
        if (returnFrom != null) {
            // double click: the tilt settles back level, the spin picks up again
            float progress = (float) Math.clamp((ui.now - returnStart) / .45, 0, 1);
            pitch += (0 - pitch) * (1 - (float) Math.pow(.8, dt * 60));
            if (progress == 1) returnFrom = null;
        }
        if (!dragging) {
            // a flick coasts on, then eases back into the advert-style turn, whichever way it
            // was last thrown
            float spin = com.ethermod.config.ClientConfig.spin() ? SPIN * spinDir : 0;
            vy += (spin - vy) * (1 - (float) Math.pow(.97, dt * 60));
            yaw += vy * (float) dt;
            pitch += (0 - pitch) * (1 - (float) Math.pow(.985, dt * 60));
        }
        g.pose().pushPose();
        g.pose().translate(40, 43, 130);
        g.pose().scale(46, -46, 46);
        g.pose().mulPose(new Quaternionf().rotateX(.25f + pitch).rotateY(yaw));
        Lighting.setupFor3DItems();
        ui.mc
                .getItemRenderer()
                .renderStatic(
                        ui.stack,
                        ItemDisplayContext.FIXED,
                        0xf000f0,
                        OverlayTexture.NO_OVERLAY,
                        g.pose(),
                        g.bufferSource(),
                        ui.mc.level,
                        0);
        g.flush();
        Lighting.setupFor3DItems();
        g.pose().popPose();
    }
}
