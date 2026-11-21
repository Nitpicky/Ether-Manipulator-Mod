package com.ethermod.client.beam;

import com.ethermod.beam.BeamBallEntity;
import com.ethermod.beam.MassDriver;

import com.ethermod.item.OrbMode;
import com.ethermod.trait.Traits;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;

/**
 * Mass Driver previews. Charging a throw always shows the arc it will fly along, in pixel dots.
 * With Steady Hand (the building pick) it also shows faint ghosts where it lands, and while set
 * down the blocks that can't go where the formation floats are marked in red. Same rules as the
 * server ({@link MassDriver#release}, {@link OrbMass#massPlace}).
 */
@OnlyIn(Dist.CLIENT)
final class MassPreview {

    private MassPreview() {}

    private static final int TECTONIC = 0xD48050, BLOCKED = 0xE04040;

    static void render(
            Minecraft mc, Player player, ItemStack st, Vec3 cam, float time, VertexConsumer vc, Matrix4f m) {
        if (OrbMode.get(st) != OrbMode.MASS) return;
        // every throw shows its arc; Steady Hand adds where it lands and what is in the way
        boolean steady = Traits.has(st, "tectonic.steady_hand");
        List<BeamBallEntity> held = new ArrayList<>();
        for (var e : mc.level.entitiesForRendering())
            if (e instanceof BeamBallEntity b
                    && b.getMode() == OrbMode.MASS
                    && b.isMassHeld()
                    && b.getOwner() == player
                    && !b.getCarried().isAir()) held.add(b);
        if (held.isEmpty()) return;
        float pulse = .75f + .25f * (float) Math.sin(time * .25f);

        if (player.isUsingItem()) {
            // the throw: every held block flies on its own (same launch and fall as the server),
            // and lands where its own path first meets the world or a block that landed before it
            int ticks = player.getTicksUsingItem();
            int phase = Math.min(2, ticks * 3 / Math.max(1, Traits.chargeTicks(st)));
            Vec3 launch = player.getLookAngle().scale(OrbMode.MASS.speed(phase)).add(0, .08, 0);
            BeamBallEntity anchor = held.get(0);
            for (var b : held) if (b.massOffset().equals(BlockPos.ZERO)) anchor = b;
            int n = held.size();
            Vec3[] pos = new Vec3[n], vel = new Vec3[n];
            BlockPos[] land = new BlockPos[n];
            for (int k = 0; k < n; k++) {
                pos[k] = held.get(k).position();
                vel[k] = launch;
            }
            var spots = new java.util.HashSet<BlockPos>();
            int flying = n;
            for (int i = 0; i < 80 && flying > 0; i++) {
                for (int k = 0; k < n; k++) {
                    if (land[k] != null) continue;
                    vel[k] = vel[k].add(0, -.08, 0);
                    Vec3 from = pos[k], next = from.add(vel[k]);
                    var hit =
                            mc.level.clip(
                                    new ClipContext(from, next, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
                    BlockPos at = null;
                    if (hit.getType() == HitResult.Type.BLOCK) {
                        at = hit.getBlockPos().relative(hit.getDirection());
                        next = hit.getLocation();
                    }
                    // a block that already landed this throw stops the ones behind it
                    BlockPos was = BlockPos.containing(from);
                    for (int sIdx = 1; sIdx <= 8; sIdx++) {
                        BlockPos cell = BlockPos.containing(from.lerp(next, sIdx / 8.0));
                        if (spots.contains(cell)) {
                            at = was;
                            next = Vec3.atCenterOf(was);
                            break;
                        }
                        was = cell;
                    }
                    if (held.get(k) == anchor && i % 2 == 1)
                        dot(vc, m, next.subtract(cam), time + i, .7f * pulse);
                    pos[k] = next;
                    if (at != null) {
                        land[k] = at;
                        spots.add(at);
                        flying--;
                    }
                }
            }
            if (!steady) return;
            for (int k = 0; k < n; k++)
                if (land[k] != null)
                    ghost(vc, m, cam, land[k], fits(mc, held.get(k).getCarried(), land[k]), pulse, spots);
            return;
        }

        // set down: the formation floats on the grid where it goes; mark what is in the way
        if (!steady) return;
        var hit = MassDriver.aim(player);
        if (hit == null) return;
        BlockPos base = hit.getBlockPos().relative(hit.getDirection());
        for (var b : held) {
            BlockPos at = base.offset(b.massOffset());
            if (!fits(mc, b.getCarried(), at)) ghost(vc, m, cam, at, false, pulse, java.util.Set.of());
        }
    }

    private static boolean fits(Minecraft mc, BlockState bs, BlockPos at) {
        return MassDriver.canPlace(mc.level, mc.player, bs, at);
    }

    /**
     * A faint see-through cube in the spot, a hair larger than the block. Sides shared with another
     * spot in {@code spots} are left out, so a formation reads as one shape.
     */
    private static void ghost(
            VertexConsumer vc, Matrix4f m, Vec3 cam, BlockPos at, boolean ok, float pulse,
            java.util.Set<BlockPos> spots) {
        int c = ok ? TECTONIC : BLOCKED;
        int r = c >> 16 & 255, g = c >> 8 & 255, b = c & 255, a = (int) (255 * (ok ? .09f : .16f) * pulse);
        double x0 = at.getX(), y0 = at.getY(), z0 = at.getZ(), x1 = x0 + 1, y1 = y0 + 1, z1 = z0 + 1;
        if (!spots.contains(at.below()))
            BlockGlow.rect(vc, m, Direction.DOWN, x0, y0, z0, x1, y0, z1, cam, .004f, r, g, b, a);
        if (!spots.contains(at.above()))
            BlockGlow.rect(vc, m, Direction.UP, x0, y1, z0, x1, y1, z1, cam, .004f, r, g, b, a);
        if (!spots.contains(at.north()))
            BlockGlow.rect(vc, m, Direction.NORTH, x0, y0, z0, x1, y1, z0, cam, .004f, r, g, b, a);
        if (!spots.contains(at.south()))
            BlockGlow.rect(vc, m, Direction.SOUTH, x0, y0, z1, x1, y1, z1, cam, .004f, r, g, b, a);
        if (!spots.contains(at.west()))
            BlockGlow.rect(vc, m, Direction.WEST, x0, y0, z0, x0, y1, z1, cam, .004f, r, g, b, a);
        if (!spots.contains(at.east()))
            BlockGlow.rect(vc, m, Direction.EAST, x1, y0, z0, x1, y1, z1, cam, .004f, r, g, b, a);
    }

    private static void dot(VertexConsumer vc, Matrix4f m, Vec3 at, float spin, float alpha) {
        BeamFx.spinCube(
                vc, m, at, BeamFx.PX, spin * .2, .5, (TECTONIC >> 16 & 255) / 255f, (TECTONIC >> 8 & 255) / 255f,
                (TECTONIC & 255) / 255f, alpha);
    }
}
