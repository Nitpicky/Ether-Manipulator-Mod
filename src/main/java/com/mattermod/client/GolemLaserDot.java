package com.mattermod.client;

import com.mattermod.entity.GolemControl;
import com.mattermod.item.OrbMode;
import com.mattermod.manipulator.Manipulator;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/**
 * Golem mode's laser pointer: while the Manipulator is out in Golem mode, a single red pixel of light sits on whatever
 * you aim at (the same creature-first, then block pick the Golem's orders use), so you can see what you are pointing at.
 * Everyone around sees it: other players' Manipulators reach this client through their synced main hand.
 */
public final class GolemLaserDot {

    private GolemLaserDot() {}

    private static final ResourceLocation DOT = ResourceLocation.fromNamespaceAndPath("mattermod", "textures/misc/laser_dot.png");

    @SubscribeEvent
    public static void render(RenderLevelStageEvent ev) {
        if (ev.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;
        if (ShaderCompat.inShadowPass()) return;
        var mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        float pt = ev.getPartialTick().getGameTimeDeltaPartialTick(false);
        for (var p : mc.level.players()) {
            boolean pointing = p == mc.player
                    ? Manipulator.isSummoned(p) && OrbMode.get(Manipulator.stack(p)) == OrbMode.GOLEM
                    : p.getMainHandItem().getItem() instanceof com.mattermod.item.MatterManipulatorItem
                            && OrbMode.get(p.getMainHandItem()) == OrbMode.GOLEM;
            if (pointing && !p.isSpectator() && p.distanceToSqr(mc.player) < 96 * 96) dot(ev, mc, p, pt);
        }
    }

    private static void dot(RenderLevelStageEvent ev, Minecraft mc, net.minecraft.world.entity.player.Player p, float pt) {
        Vec3 eye = p.getEyePosition(pt), look = p.getViewVector(pt);
        double range = GolemControl.ORDER_RANGE;
        HitResult block = p.pick(range, pt, false);
        double reach = block.getType() == HitResult.Type.MISS ? range : block.getLocation().distanceTo(eye);
        Vec3 end = eye.add(look.scale(reach));
        var ridden = p.getVehicle();
        var hit = ProjectileUtil.getEntityHitResult(p, eye, end, p.getBoundingBox().expandTowards(look.scale(reach)).inflate(1),
                e -> e instanceof LivingEntity && e.isAlive() && e != ridden && !e.isSpectator(), reach * reach);
        Vec3 at;
        Vec3 normal;
        if (hit != null) {
            at = hit.getLocation();
            normal = look.reverse();
        } else if (block instanceof BlockHitResult bh && block.getType() == HitResult.Type.BLOCK) {
            at = bh.getLocation();
            normal = Vec3.atLowerCornerOf(bh.getDirection().getNormal());
        } else return;

        Vec3 cam = ev.getCamera().getPosition();
        double dist = at.distanceTo(cam);
        // one pixel (1/16 block) up close, growing a little with distance so it stays visible
        float half = (float) (Math.max(1 / 16.0, dist * .0045) / 2);
        Vec3 c = at.add(normal.scale(.01)).subtract(cam);
        // a square facing the camera, laid on the surface: the two axes span the plane across the normal
        Vec3 a = Math.abs(normal.y) > .9 ? new Vec3(1, 0, 0) : normal.cross(new Vec3(0, 1, 0)).normalize();
        Vec3 b = normal.cross(a).normalize();
        PoseStack ps = ev.getPoseStack();
        ps.pushPose();
        var pose = ps.last();
        var buffers = mc.renderBuffers().bufferSource();
        RenderType type = RenderType.eyes(DOT);
        VertexConsumer vc = buffers.getBuffer(type);
        float[][] corners = {{-1, -1}, {1, -1}, {1, 1}, {-1, 1}};
        for (int n = 0; n < 8; n++) { // both windings, the type culls back faces
            int i = n < 4 ? n : 7 - n;
            Vec3 v = c.add(a.scale(corners[i][0] * half)).add(b.scale(corners[i][1] * half));
            vc.addVertex(pose, (float) v.x, (float) v.y, (float) v.z)
                    .setColor(255, 40, 30, 255)
                    .setUv(corners[i][0] > 0 ? 1 : 0, corners[i][1] > 0 ? 1 : 0)
                    .setOverlay(OverlayTexture.NO_OVERLAY)
                    .setLight(LightTexture.FULL_BRIGHT)
                    .setNormal(pose, (float) normal.x, (float) normal.y, (float) normal.z);
        }
        buffers.endBatch(type);
        ps.popPose();
    }
}
