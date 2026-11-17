package com.ethermod.beam;

import com.ethermod.manipulator.Manipulator;
import com.ethermod.trait.BeaconNet;
import com.ethermod.trait.Traits;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import org.joml.Matrix4f;

/** Mobility: Beacon Net. Draws each waypoint as a soft light pillar seen through walls. */
@OnlyIn(Dist.CLIENT)
final class BeaconMarkers {

    private BeaconMarkers() {}

    private static final float HEIGHT = 48f;

    static void render(
            Minecraft mc,
            Player player,
            Vec3 camPos,
            Vec3 camLeft,
            float time,
            MultiBufferSource.BufferSource buf,
            Matrix4f m) {
        ItemStack stack = Manipulator.stack(player);
        if (!Traits.has(stack, "photonic.beacon_net")) return;
        String dim = mc.level.dimension().location().toString();
        VertexConsumer vc = null;
        for (BeaconNet.Beacon b : BeaconNet.read(stack)) {
            if (!b.dim().equals(dim)) continue;
            BlockPos p = b.pos();
            if (b.expires() != 0 && b.expires() <= mc.level.getGameTime()) continue;
            // a faded temporary light is no longer a waypoint
            if (mc.level.isLoaded(p) && !mc.level.getBlockState(p).is(Blocks.LIGHT)) continue;
            if (vc == null) vc = buf.getBuffer(OreSense.XRAY);
            float cx = (float) (p.getX() + 0.5 - camPos.x),
                    cz = (float) (p.getZ() + 0.5 - camPos.z);
            float y0 = (float) (p.getY() + 0.2 - camPos.y);
            double dist = Math.sqrt(cx * cx + cz * cz);
            // keep the pillar a readable width far away
            float w = (float) Math.max(0.15, dist * 0.006);
            float lx = (float) camLeft.x * w, lz = (float) camLeft.z * w;
            int a = (int) (110 + 40 * Math.sin(time * 0.15 + p.hashCode()));
            // core column fading upward
            vc.addVertex(m, cx - lx, y0, cz - lz).setColor(255, 245, 170, a);
            vc.addVertex(m, cx + lx, y0, cz + lz).setColor(255, 245, 170, a);
            vc.addVertex(m, cx + lx, y0 + HEIGHT, cz + lz).setColor(255, 245, 170, 0);
            vc.addVertex(m, cx - lx, y0 + HEIGHT, cz - lz).setColor(255, 245, 170, 0);
            // small diamond at the light itself
            float s = w * 3, ym = y0 + 0.3f;
            vc.addVertex(m, cx, ym - s, cz).setColor(255, 255, 220, 200);
            vc.addVertex(m, cx + lx * 3, ym, cz + lz * 3).setColor(255, 255, 220, 200);
            vc.addVertex(m, cx, ym + s, cz).setColor(255, 255, 220, 200);
            vc.addVertex(m, cx - lx * 3, ym, cz - lz * 3).setColor(255, 255, 220, 200);
        }
        if (vc != null) buf.endBatch(OreSense.XRAY);

        // Beacon Net III: the distance to each waypoint, over it
        if (Traits.rank(stack, "photonic.beacon_net") >= 3) {
            var font = mc.font;
            var rot = mc.gameRenderer.getMainCamera().rotation();
            for (BeaconNet.Beacon b : BeaconNet.read(stack)) {
                if (!b.dim().equals(dim)
                        || b.expires() != 0 && b.expires() <= mc.level.getGameTime()) continue;
                BlockPos p = b.pos();
                if (mc.level.isLoaded(p) && !mc.level.getBlockState(p).is(Blocks.LIGHT)) continue;
                Vec3 rel = Vec3.atCenterOf(p).subtract(camPos);
                double dist = rel.length();
                // keep the label a readable size far away, no bigger than close up
                double show = Math.min(dist, 24);
                Vec3 at = rel.scale(show / Math.max(0.01, dist)).add(0, 1.2 * show / 24 + 0.6, 0);
                float s = (float) (0.025 * Math.max(1, show / 8));
                Matrix4f mat =
                        new Matrix4f(m)
                                .translate((float) at.x, (float) at.y, (float) at.z)
                                .rotate(rot)
                                .scale(s, -s, s);
                String text = (int) Math.round(dist) + " m";
                font.drawInBatch(
                        text,
                        -font.width(text) / 2f,
                        0,
                        0xFFFFF5AA,
                        false,
                        mat,
                        buf,
                        net.minecraft.client.gui.Font.DisplayMode.SEE_THROUGH,
                        0x40000000,
                        0xF000F0);
            }
            buf.endBatch();
        }
    }
}
