package com.mattermod.beam;

import com.mattermod.trait.Traits;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.common.Tags;

import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;

/**
 * Excavation: Ore Sense. While summoned, ores within 8 blocks glow through walls, fainter the
 * further away and the more blocks are in the way. Scans the client's own world once a second;
 * draws soft boxes with depth testing off (vanilla lightning shader, so shader packs cope).
 */
@OnlyIn(Dist.CLIENT)
final class OreSense {

    private OreSense() {}

    private static final int RADIUS = 8;
    private static final int MAX_ORES = 96;

    static final RenderType XRAY =
            RenderType.create(
                    "mattermod_ore_sense",
                    DefaultVertexFormat.POSITION_COLOR,
                    VertexFormat.Mode.QUADS,
                    1536,
                    false,
                    true,
                    RenderType.CompositeState.builder()
                            .setShaderState(RenderStateShard.RENDERTYPE_LIGHTNING_SHADER)
                            .setTransparencyState(RenderStateShard.LIGHTNING_TRANSPARENCY)
                            .setDepthTestState(RenderStateShard.NO_DEPTH_TEST)
                            .setCullState(RenderStateShard.NO_CULL)
                            .setWriteMaskState(RenderStateShard.COLOR_WRITE)
                            .createCompositeState(false));

    private static final List<BlockPos> ores = new ArrayList<>();

    /** How strongly each ore glows, 0..1 (distance and blocks in the way). */
    private static final List<Float> strength = new ArrayList<>();

    private static long lastScan;

    static void render(
            Minecraft mc,
            Player player,
            ItemStack held,
            Vec3 camPos,
            float time,
            MultiBufferSource.BufferSource buf,
            Matrix4f m) {
        if (!Traits.has(held, "excavation.ore_sense")) return;
        long now = mc.level.getGameTime();
        if (now - lastScan >= 20 || now < lastScan) {
            lastScan = now;
            ores.clear();
            strength.clear();
            BlockPos c = player.blockPosition();
            Vec3 eye = player.getEyePosition();
            for (BlockPos p :
                    BlockPos.betweenClosed(
                            c.offset(-RADIUS, -RADIUS, -RADIUS),
                            c.offset(RADIUS, RADIUS, RADIUS))) {
                if (!mc.level.getBlockState(p).is(Tags.Blocks.ORES)) continue;
                Vec3 to = Vec3.atCenterOf(p);
                double dist = to.distanceTo(eye);
                if (dist > RADIUS + 0.5) continue;
                // blocks in the way, sampled along the line (the ore itself not counted)
                int solid = 0;
                BlockPos last = null;
                for (double t = 0.5; t < dist - 0.6; t += 0.5) {
                    BlockPos q = BlockPos.containing(eye.add(to.subtract(eye).scale(t / dist)));
                    if (q.equals(last)) continue;
                    last = q;
                    if (mc.level.getBlockState(q).canOcclude()) solid++;
                }
                float f = (float) ((1 - dist / (RADIUS + 1)) / (1 + 0.35 * solid));
                if (f < 0.05f) continue;
                ores.add(p.immutable());
                strength.add(f);
                if (ores.size() >= MAX_ORES) break;
            }
        }
        if (ores.isEmpty()) return;
        VertexConsumer vc = buf.getBuffer(XRAY);
        float pulse = (float) (0.8 + 0.2 * Math.sin(time * 0.15));
        for (int i = 0; i < ores.size(); i++) {
            BlockPos p = ores.get(i);
            int a = (int) (150 * strength.get(i) * pulse);
            float x0 = (float) (p.getX() - camPos.x) + 0.1f,
                    y0 = (float) (p.getY() - camPos.y) + 0.1f,
                    z0 = (float) (p.getZ() - camPos.z) + 0.1f;
            float x1 = x0 + 0.8f, y1 = y0 + 0.8f, z1 = z0 + 0.8f;
            box(vc, m, x0, y0, z0, x1, y1, z1, 255, 220, 120, a);
        }
        buf.endBatch(XRAY);
    }

    private static void box(
            VertexConsumer vc,
            Matrix4f m,
            float x0,
            float y0,
            float z0,
            float x1,
            float y1,
            float z1,
            int r,
            int g,
            int b,
            int a) {
        quad(vc, m, x0, y0, z0, x1, y0, z0, x1, y1, z0, x0, y1, z0, r, g, b, a);
        quad(vc, m, x0, y0, z1, x0, y1, z1, x1, y1, z1, x1, y0, z1, r, g, b, a);
        quad(vc, m, x0, y0, z0, x0, y1, z0, x0, y1, z1, x0, y0, z1, r, g, b, a);
        quad(vc, m, x1, y0, z0, x1, y0, z1, x1, y1, z1, x1, y1, z0, r, g, b, a);
        quad(vc, m, x0, y0, z0, x0, y0, z1, x1, y0, z1, x1, y0, z0, r, g, b, a);
        quad(vc, m, x0, y1, z0, x1, y1, z0, x1, y1, z1, x0, y1, z1, r, g, b, a);
    }

    private static void quad(
            VertexConsumer vc,
            Matrix4f m,
            float ax,
            float ay,
            float az,
            float bx,
            float by,
            float bz,
            float cx,
            float cy,
            float cz,
            float dx,
            float dy,
            float dz,
            int r,
            int g,
            int b,
            int a) {
        vc.addVertex(m, ax, ay, az).setColor(r, g, b, a);
        vc.addVertex(m, bx, by, bz).setColor(r, g, b, a);
        vc.addVertex(m, cx, cy, cz).setColor(r, g, b, a);
        vc.addVertex(m, dx, dy, dz).setColor(r, g, b, a);
    }
}
