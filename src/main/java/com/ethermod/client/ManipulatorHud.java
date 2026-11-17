package com.ethermod.client;

import com.ethermod.item.OrbMode;
import com.ethermod.manipulator.Manipulator;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.util.List;

/**
 * The mode ring. While the Manipulator is summoned, the current mode's Badge sits centred above
 * the XP level, in its Module's colour (Beam is Plasmic, Kinetic while a right click charges),
 * with the shared Ether tank as "current / max" under the crosshair.
 *
 * <p>That slot is the bottom of a ring centred on the screen, so its distance from the centre is
 * the ring's radius. Holding the wheel key (({@link ModeWheel})) brings the other modes out
 * from the centre to their places on the ring; the current Badge stays put. Whenever the mode
 * changes, the ring turns the short way until the new mode is at the bottom while the others fade.
 * A tap (wheel closed) only shows the last stretch of that turn, right by the slot.
 */
@OnlyIn(Dist.CLIENT)
public final class ManipulatorHud {

    private ManipulatorHud() {}

    /** Wheel state, written by ({@link ModeWheel}): shown, and the mode pointed at. */
    static boolean wheelOpen;

    static OrbMode hovered;

    private static final float OPEN_MS = 120, SPIN_MS = 180;

    private static float open, rot, rotFrom, rotTo, spin = 1;
    private static OrbMode current, previous;

    /** The ring's modes last frame, to notice one being added or removed. */
    private static List<OrbMode> lastModes = List.of();
    private static boolean tapTurn;
    private static long lastFrame;

    /** Beam's Plasmic/Kinetic swap: the Badge it swaps from, and how far along (0..1). */
    private static String slotBadge, swapFrom;

    private static float swap = 1;

    /** The Badge slot's centre, from the bottom of the screen (GUI pixels). */
    static final int SLOT_FROM_BOTTOM = 49;

    public static void render(GuiGraphics g, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.options.hideGui || !Manipulator.isSummoned(mc.player)) {
            current = null;
            open = 0;
            return;
        }
        ItemStack stack = Manipulator.stack(mc.player);
        OrbMode mode = OrbMode.get(stack);
        List<OrbMode> modes = modes(stack);
        int n = Math.max(1, modes.size());
        float step = (float) (Math.PI * 2 / n);

        long now = net.minecraft.Util.getMillis();
        float dt = lastFrame == 0 ? 0 : Math.min(100, now - lastFrame);
        lastFrame = now;

        // a new mode: turn the ring the short way until it is at the bottom
        int idx = Math.max(0, modes.indexOf(mode));
        if (current == null || !modes.equals(lastModes)) {
            // first frame, or a mode was added or removed: the ring re-spaces itself, and the
            // current Badge stays put at the bottom (no turn)
            rot = rotFrom = rotTo = -idx * step;
            spin = 1;
            previous = null;
        } else if (mode != current) {
            // spamming: the badge that was still fading in leaves from how far it had got, so a
            // fast run of switches flows instead of popping
            prevFrom = spin < 1 && tapTurn ? Math.min(1, ease(spin) * 1.4f) : 1;
            previous = current;
            rotFrom = rot;
            float d = -idx * step - rot;
            d = (float) Math.atan2(Math.sin(d), Math.cos(d));
            rotTo = rot + d;
            spin = 0;
            tapTurn = open < .5f;
        }
        current = mode;
        lastModes = modes;
        if (spin < 1) {
            spin = Math.min(1, spin + dt / SPIN_MS);
            rot = rotFrom + (rotTo - rotFrom) * ease(spin);
        }
        open =
                wheelOpen
                        ? Math.min(1, open + dt / OPEN_MS)
                        : Math.max(0, open - dt / OPEN_MS);
        boolean spinning = spin < 1;
        // Beam turning Kinetic while charging (and back) is the same mode, but it gets the same
        // tap turn, one step of the ring by the slot
        // Plasmic and Kinetic are one mode, shown as whichever you used last: a right click
        // switches the slot to Kinetic and it stays there until a left click switches it back
        boolean charging =
                mode == OrbMode.ENERGY
                        && (com.ethermod.beam.BeamRenderState.isCharging
                                || com.ethermod.item.ManipulatorColors.lastOrb(stack))
                        && !com.ethermod.beam.BeamRenderState.beamActive;
        String slotNow = Badges.module(mode, charging);
        if (slotBadge != null && !slotNow.equals(slotBadge) && !spinning) {
            swapFrom = slotBadge;
            swap = 0;
        }
        slotBadge = slotNow;
        if (swap < 1) swap = Math.min(1, swap + dt / SPIN_MS);

        int w = g.guiWidth(), h = g.guiHeight();
        float cx = w / 2f, cy = h / 2f;
        float radius = h - SLOT_FROM_BOTTOM - cy;

        if (hovered != null && open > .5f && !spinning)
            tint(g, w, h, modes.indexOf(hovered) * step + rot, Badges.color(Badges.module(hovered, false)));

        for (int i = 0; i < modes.size(); i++) {
            OrbMode m = modes.get(i);
            float a = i * step + rot;
            float alpha, r = radius;
            boolean isCur = m == current;
            if (isCur && spinning && tapTurn) {
                a = wrap(a) * .3f; // fades in over the last stretch, by the slot
                alpha = Math.min(1, ease(spin) * 1.4f);
            } else if (isCur) alpha = 1;
            else if (spinning) {
                alpha = (m == previous ? 1 : open) * (1 - ease(spin));
                if (m == previous && tapTurn) {
                    a = wrap(a) * .3f;
                    alpha = prevFrom * Math.max(0, 1 - ease(spin) * 1.4f);
                }
            } else {
                alpha = open;
                r = radius * open; // the others come out from the centre
            }
            if (isCur && swap < 1 && swapFrom != null) {
                // the old look leaves one way, the new one comes in from the other, by the slot
                float e = ease(swap);
                drawAt(g, swapFrom, cx, cy, radius, -step * .3f * e, Math.max(0, 1 - e * 1.4f), 1);
                a = step * .3f * (1 - e);
                alpha = Math.min(1, e * 1.4f);
            }
            if (alpha <= .01f) continue;
            boolean kinetic = isCur && charging;
            String badge = Badges.module(m, kinetic);
            float x = cx + (float) Math.sin(a) * r, y = cy + (float) Math.cos(a) * r;
            float scale = m == hovered && open > .5f && !spinning && !isCur ? 1.25f : 1f;
            int argb = Math.round(alpha * 255) << 24 | (Badges.color(badge) & 0xFFFFFF);
            g.pose().pushPose();
            g.pose().translate(x, y, 0);
            g.pose().scale(scale, scale, 1);
            Badges.draw(g, badge, -8, -8, 1, argb);
            g.pose().popPose();
        }

        if (open > .5f && !spinning) {
            OrbMode label = hovered != null ? hovered : current;
            g.drawCenteredString(
                    mc.font,
                    label.title(),
                    (int) cx,
                    (int) cy + 12,
                    Badges.color(Badges.module(label, false)));
        }

        // the shared Ether tank every projectile draws from, as "current/max" in tiny pixel
        // digits under the crosshair and its attack indicator, drawn the way the crosshair is
        // (inverting what is behind it). Full for a while, it fades out; any change brings it back.
        if (mode.usesTank() || mode.rmbShot().usesTank()) {
            int cap = OrbMode.capacity(stack), have = Math.min(cap, OrbMode.tank(stack));
            if (have != lastTank || have < cap) fullSince = now;
            lastTank = have;
            float target = now - fullSince > AMMO_LINGER_MS || open > .5f ? 0 : 1;
            ammoAlpha += (target - ammoAlpha) * Math.min(1, dt / 180f);
            if (ammoAlpha > .02f) ammo(g, have + "/" + cap, (int) cx, (int) cy + 16, ammoAlpha);
        } else ammoAlpha = 0;
        // Golem mode: the Golem's health, or the seconds until it can be rebuilt
        if (mode == OrbMode.GOLEM) {
            String text = golemText(stack);
            if (text != null) ammo(g, text, (int) cx, (int) cy + 16, 1);
            superMoveCooldown(g, (int) cx, (int) cy);
        }
    }

    private static final net.minecraft.resources.ResourceLocation ATTACK_BG =
                    net.minecraft.resources.ResourceLocation.withDefaultNamespace("hud/crosshair_attack_indicator_background"),
            ATTACK_PROGRESS =
                    net.minecraft.resources.ResourceLocation.withDefaultNamespace("hud/crosshair_attack_indicator_progress");

    /** The Golem's Super Move cooldown as vanilla's attack indicator (the little sword bar) under the crosshair,
     *  drawn the way vanilla draws it (inverting what is behind it). */
    private static void superMoveCooldown(GuiGraphics g, int cx, int cy) {
        var golem = ownGolem();
        if (golem == null) return;
        float pt = net.minecraft.client.Minecraft.getInstance().getTimer().getGameTimeDeltaPartialTick(true);
        float f = golem.superReady(pt);
        if (f >= 1) return;
        int x = cx - 8, y = cy - 7 + 16;
        com.mojang.blaze3d.systems.RenderSystem.enableBlend();
        com.mojang.blaze3d.systems.RenderSystem.blendFuncSeparate(
                com.mojang.blaze3d.platform.GlStateManager.SourceFactor.ONE_MINUS_DST_COLOR,
                com.mojang.blaze3d.platform.GlStateManager.DestFactor.ONE_MINUS_SRC_COLOR,
                com.mojang.blaze3d.platform.GlStateManager.SourceFactor.ONE,
                com.mojang.blaze3d.platform.GlStateManager.DestFactor.ZERO);
        g.blitSprite(ATTACK_BG, x, y, 16, 4);
        g.blitSprite(ATTACK_PROGRESS, 16, 4, 0, 0, x, y, (int) (f * 17), 4);
        com.mojang.blaze3d.systems.RenderSystem.defaultBlendFunc();
        com.mojang.blaze3d.systems.RenderSystem.disableBlend();
    }

    private static com.ethermod.entity.RedstoneGolem ownGolem() {
        var mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return null;
        var id = mc.player.getUUID();
        for (var golem :
                mc.level.getEntitiesOfClass(
                        com.ethermod.entity.RedstoneGolem.class,
                        mc.player.getBoundingBox().inflate(96),
                        e -> e.isAlive() && e.ownerId().map(id::equals).orElse(false)))
            return golem;
        return null;
    }

    private static String golemText(net.minecraft.world.item.ItemStack stack) {
        var mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return null;
        long wait = com.ethermod.entity.GolemControl.rebuildAt(stack) - mc.level.getGameTime();
        if (wait > 0) return String.valueOf((wait + 19) / 20);
        var id = mc.player.getUUID();
        for (var golem :
                mc.level.getEntitiesOfClass(
                        com.ethermod.entity.RedstoneGolem.class,
                        mc.player.getBoundingBox().inflate(96),
                        e -> e.isAlive() && e.ownerId().map(id::equals).orElse(false)))
            return (int) Math.ceil(golem.getHealth()) + "/" + (int) golem.getMaxHealth();
        return null;
    }

    private static final net.minecraft.resources.ResourceLocation DIGITS =
            net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(
                    "ethermod", "textures/gui/ammo_digits.png");
    private static final long AMMO_LINGER_MS = 2500;
    private static int lastTank = -1;
    private static float prevFrom = 1;
    private static long fullSince;
    private static float ammoAlpha;

    /** Pixel digits (3x5, from ammo_digits.png: 0-9 then '/'), centred on x, blended like the crosshair. */
    private static void ammo(GuiGraphics g, String text, int x, int y, float alpha) {
        int w = text.length() * 4 - 1;
        int left = x - w / 2;
        com.mojang.blaze3d.systems.RenderSystem.enableBlend();
        com.mojang.blaze3d.systems.RenderSystem.blendFuncSeparate(
                com.mojang.blaze3d.platform.GlStateManager.SourceFactor.ONE_MINUS_DST_COLOR,
                com.mojang.blaze3d.platform.GlStateManager.DestFactor.ONE_MINUS_SRC_COLOR,
                com.mojang.blaze3d.platform.GlStateManager.SourceFactor.ONE,
                com.mojang.blaze3d.platform.GlStateManager.DestFactor.ZERO);
        // with the inverting blend a darker source inverts less, so fading is a grey ramp
        g.setColor(alpha, alpha, alpha, 1);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            int glyph = c == '/' ? 10 : c - '0';
            if (glyph < 0 || glyph > 10) continue;
            g.blit(DIGITS, left + i * 4, y, glyph * 4, 0, 3, 5, 44, 5);
        }
        g.setColor(1, 1, 1, 1);
        com.mojang.blaze3d.systems.RenderSystem.defaultBlendFunc();
        com.mojang.blaze3d.systems.RenderSystem.disableBlend();
    }

    private static void drawAt(
            GuiGraphics g, String badge, float cx, float cy, float r, float a, float alpha, float scale) {
        if (alpha <= .01f) return;
        int argb = Math.round(alpha * 255) << 24 | (Badges.color(badge) & 0xFFFFFF);
        g.pose().pushPose();
        g.pose().translate(cx + (float) Math.sin(a) * r, cy + (float) Math.cos(a) * r, 0);
        g.pose().scale(scale, scale, 1);
        Badges.draw(g, badge, -8, -8, 1, argb);
        g.pose().popPose();
    }

    /** The modes on the ring, in a fixed order. */
    static List<OrbMode> modes(ItemStack stack) {
        return java.util.Arrays.stream(OrbMode.values()).filter(m -> m.isUnlocked(stack)).toList();
    }

    /**
     * The mode on the side the mouse points to. With four modes the screen's corner-to-corner
     * diagonals split it into four triangles; otherwise each mode gets an equal slice of angle.
     */
    static OrbMode pointed(List<OrbMode> modes, double dx, double dy, int w, int h) {
        if (modes.isEmpty() || dx * dx + dy * dy < 4) return null;
        double ang;
        if (modes.size() == 4)
            ang =
                    Math.abs(dx) * h > Math.abs(dy) * w
                            ? (dx > 0 ? Math.PI / 2 : -Math.PI / 2)
                            : (dy > 0 ? 0 : Math.PI);
        else ang = Math.atan2(dx, dy);
        float step = (float) (Math.PI * 2 / modes.size());
        OrbMode best = null;
        double bestD = 1e9;
        for (int i = 0; i < modes.size(); i++) {
            double d = Math.abs(wrap((float) (ang - (i * step + rot))));
            if (d < bestD) {
                bestD = d;
                best = modes.get(i);
            }
        }
        return best;
    }

    /** The pointed-at triangle, very faintly, from the centre to the screen's edge. */
    private static void tint(GuiGraphics g, int w, int h, float a, int color) {
        int sx = Math.round((float) Math.sin(a)), sy = Math.round((float) Math.cos(a));
        float[][] c =
                sx > 0
                        ? new float[][] {{w, 0}, {w, h}}
                        : sx < 0
                                ? new float[][] {{0, h}, {0, 0}}
                                : sy > 0 ? new float[][] {{w, h}, {0, h}} : new float[][] {{0, 0}, {w, 0}};
        int argb = Math.round(open * 18) << 24 | (color & 0xFFFFFF);
        var m = g.pose().last().pose();
        var vc = g.bufferSource().getBuffer(RenderType.gui());
        vc.addVertex(m, w / 2f, h / 2f, 0).setColor(argb);
        vc.addVertex(m, c[0][0], c[0][1], 0).setColor(argb);
        vc.addVertex(m, c[1][0], c[1][1], 0).setColor(argb);
        vc.addVertex(m, w / 2f, h / 2f, 0).setColor(argb);
        g.flush();
    }

    private static float wrap(float a) {
        return (float) Math.atan2(Math.sin(a), Math.cos(a));
    }

    private static float ease(float t) {
        t = Math.max(0, Math.min(1, t));
        return 1 - (1 - t) * (1 - t) * (1 - t) + (float) Math.sin(t * Math.PI) * .04f;
    }
}
