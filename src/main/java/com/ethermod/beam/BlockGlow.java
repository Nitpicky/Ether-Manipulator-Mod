package com.ethermod.beam;

import com.ethermod.trait.Traits;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * A soft, pulsing glow, in the beam colour, on the blocks the beam is working on. With area traits
 * (Area Bore, Deep Bore) every affected block glows, drawn as one merged shape: faces shared by two
 * glowing blocks are skipped, so the group reads as a single solid. Blocks keep glowing for a while
 * after the beam moves on and fade out, matching the server, which keeps their mining progress for
 * the same time.
 */
@OnlyIn(Dist.CLIENT)
public final class BlockGlow {

    private BlockGlow() {}

    /**
     * A block glows fully for HOLD_TICKS after the beam leaves it, then fades over FADE_TICKS, in
     * step with its crack healing (MiningDamageTracker).
     */
    static final float HOLD_TICKS = MiningDamageTracker.HEAL_DELAY;

    static final float FADE_TICKS = 1f / MiningDamageTracker.HEAL_PER_TICK;
    static final float MEMORY_TICKS = HOLD_TICKS + FADE_TICKS;
    private static final float FADE_IN_TICKS = 5f;

    private static float R, G, B;

    private record Mark(long first, long last) {}

    private static final Map<Long, Mark> marks = new HashMap<>();

    /** Aim preview: block → how far faded in (0..1). */
    private static final Map<Long, Float> preview = new HashMap<>();

    /**
     * Drops every glow when the client changes world or server, so stale positions never render.
     */
    public static void clearCaches() {
        marks.clear();
        preview.clear();
    }

    private static final float PREVIEW_FADE = 0.25f; // seconds to fade in or out
    private static long lastNanos = System.nanoTime();

    static void render(
            Minecraft mc,
            Player player,
            ItemStack held,
            Vec3 camPos,
            float time,
            float pt,
            MultiBufferSource.BufferSource buf,
            Matrix4f m) {
        long now = mc.level.getGameTime();
        if (!com.ethermod.config.ClientConfig.minedGlow()) return;
        float[] c = com.ethermod.item.ManipulatorColors.beamRgba(held);
        R = c[0];
        G = c[1];
        B = c[2];
        java.util.Set<Long> aimed = new java.util.HashSet<>();
        // only Beam mode mines, so only it previews what it would mine
        if (!player.isShiftKeyDown()
                && com.ethermod.item.OrbMode.get(held) == com.ethermod.item.OrbMode.ENERGY) {
            // a creature in the way takes the beam: the blocks behind it are not being mined
            HitResult hit = BeamTrace.castWithEntities(player, Traits.beamRange(held), pt);
            if (hit instanceof BlockHitResult bh && hit.getType() == HitResult.Type.BLOCK) {
                for (BlockPos p : affected(mc, player, held, bh.getBlockPos())) {
                    // the aim itself shows vanilla's outline now (user); only mined blocks glow
                    if (BeamRenderState.beamActive) touch(p.asLong(), now);
                }
            }
        }

        // Aim preview: eases in on what you point at and out when you look away
        long nanos = System.nanoTime();
        float dt = Math.min(0.1f, (nanos - lastNanos) / 1e9f);
        lastNanos = nanos;
        for (long k : aimed) preview.putIfAbsent(k, 0f);
        preview.replaceAll(
                (k, a) ->
                        aimed.contains(k)
                                ? Math.min(1f, a + dt / PREVIEW_FADE)
                                : Math.max(0f, a - dt / PREVIEW_FADE));
        preview.values().removeIf(a -> a <= 0f);

        // Drop marks that faded out or whose block is gone
        Iterator<Map.Entry<Long, Mark>> it = marks.entrySet().iterator();
        while (it.hasNext()) {
            var e = it.next();
            if (now - e.getValue().last > MEMORY_TICKS
                    || now < e.getValue().first
                    || mc.level.getBlockState(BlockPos.of(e.getKey())).isAir()) it.remove();
        }
        if (marks.isEmpty() && preview.isEmpty()) return;

        // One brightness per block: the stronger of its mining glow (healing
        // away) and the preview, so a healing block you still aim at settles
        // down to the preview level instead of fading out and back in.
        float pulse = 0.5f + 0.5f * (float) Math.sin(time * 0.125f); // about 2.5 s per cycle
        float breathe = 0.85f + 0.15f * (float) Math.sin(time * 0.1f);
        Map<Long, Float> glow = new HashMap<>();
        for (var e : preview.entrySet()) {
            float a = e.getValue();
            a = a * a * (3 - 2 * a);
            glow.put(e.getKey(), 0.16f * breathe * a);
        }
        for (var e : marks.entrySet()) {
            Mark mk = e.getValue();
            float age = now - mk.first + pt;
            float idle = now - mk.last + pt;
            float fade =
                    Math.min(1f, age / FADE_IN_TICKS)
                            * (1f - Math.max(0f, Math.min(1f, (idle - HOLD_TICKS) / FADE_TICKS)));
            fade = fade * fade * (3 - 2 * fade); // ease in and out
            float a = (0.30f + 0.30f * pulse) * fade;
            glow.merge(e.getKey(), a, Math::max);
        }

        VertexConsumer vc = com.ethermod.client.Fx.glow();
        // Each block glows in its own shape (a stair as an L, a slab as half a cube), like
        // vanilla's outline; faces shared with another glowing block vanish, so a group of
        // blocks glows as one shape.
        Map<Long, List<net.minecraft.world.phys.AABB>> shapes = new HashMap<>();
        for (var e : glow.entrySet()) {
            if (e.getValue() <= 0.004f) continue;
            BlockPos p = BlockPos.of(e.getKey());
            shapes.put(e.getKey(), shapeOf(mc, p).toAabbs());
        }
        for (var e : glow.entrySet()) {
            float a = e.getValue();
            if (a <= 0.004f) continue;
            BlockPos p = BlockPos.of(e.getKey());
            ShapeSurface.faces(
                    shapeOf(mc, p),
                    p,
                    (x, y, z) -> {
                        BlockPos q = BlockPos.containing(x, y, z);
                        if (q.equals(p)) return false;
                        var boxes = shapes.get(q.asLong());
                        return boxes != null
                                && ShapeSurface.contains(
                                        boxes, x - q.getX(), y - q.getY(), z - q.getZ());
                    },
                    (d, x0, y0, z0, x1, y1, z1) -> rect(vc, m, d, x0, y0, z0, x1, y1, z1, camPos, 0.004f, a));
        }
    }

    private static void touch(long key, long now) {
        Mark old = marks.get(key);
        marks.put(
                key,
                old == null || now - old.last > MEMORY_TICKS
                        ? new Mark(now, now)
                        : new Mark(old.first, now));
    }

    /** The blocks one beam tick works on, mirroring BeamCastHelper's area traits. */
    private static List<BlockPos> affected(
            Minecraft mc, Player player, ItemStack held, BlockPos center) {
        List<BlockPos> out = new ArrayList<>();
        if (!minable(mc, center)) return out;
        out.add(center);
        Vec3 look = player.getLookAngle();
        for (BlockPos p : Traits.areaBore(held, center, look)) {
            if (minable(mc, p)) out.add(p);
        }
        return out;
    }

    private static boolean minable(Minecraft mc, BlockPos p) {
        BlockState s = mc.level.getBlockState(p);
        return !s.isAir() && s.getFluidState().isEmpty() && s.getDestroySpeed(mc.level, p) >= 0;
    }

    /** The block's outline shape; a full cube when it has none (so it still shows). */
    private static net.minecraft.world.phys.shapes.VoxelShape shapeOf(Minecraft mc, BlockPos p) {
        var shape = mc.level.getBlockState(p).getShape(mc.level, p);
        return shape.isEmpty() ? net.minecraft.world.phys.shapes.Shapes.block() : shape;
    }

    /** A face rectangle (world coordinates), pushed out along its normal by {@code out}. */
    static void rect(
            VertexConsumer vc,
            Matrix4f m,
            Direction d,
            double wx0,
            double wy0,
            double wz0,
            double wx1,
            double wy1,
            double wz1,
            Vec3 cam,
            float out,
            float alpha) {
        int a = (int) (Math.max(0, Math.min(1, alpha)) * 255);
        rect(vc, m, d, wx0, wy0, wz0, wx1, wy1, wz1, cam, out, (int) (R * 255), (int) (G * 255), (int) (B * 255), a);
    }

    static void rect(
            VertexConsumer vc,
            Matrix4f m,
            Direction d,
            double wx0,
            double wy0,
            double wz0,
            double wx1,
            double wy1,
            double wz1,
            Vec3 cam,
            float out,
            int r,
            int g,
            int b,
            int a) {
        float ox = d.getStepX() * out, oy = d.getStepY() * out, oz = d.getStepZ() * out;
        float x0 = (float) (wx0 - cam.x) + ox,
                y0 = (float) (wy0 - cam.y) + oy,
                z0 = (float) (wz0 - cam.z) + oz;
        float x1 = (float) (wx1 - cam.x) + ox,
                y1 = (float) (wy1 - cam.y) + oy,
                z1 = (float) (wz1 - cam.z) + oz;
        switch (d.getAxis()) {
            case Y -> quad(vc, m, x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1, r, g, b, a);
            case Z -> quad(vc, m, x0, y0, z0, x0, y1, z0, x1, y1, z0, x1, y0, z0, r, g, b, a);
            case X -> quad(vc, m, x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0, r, g, b, a);
        }
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
        // both windings, so it shows from inside and outside
        vc.addVertex(m, ax, ay, az).setColor(r, g, b, a);
        vc.addVertex(m, bx, by, bz).setColor(r, g, b, a);
        vc.addVertex(m, cx, cy, cz).setColor(r, g, b, a);
        vc.addVertex(m, dx, dy, dz).setColor(r, g, b, a);
        vc.addVertex(m, dx, dy, dz).setColor(r, g, b, a);
        vc.addVertex(m, cx, cy, cz).setColor(r, g, b, a);
        vc.addVertex(m, bx, by, bz).setColor(r, g, b, a);
        vc.addVertex(m, ax, ay, az).setColor(r, g, b, a);
    }
}
