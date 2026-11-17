package com.ethermod.beam;

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
 * Mass Driver with Steady Hand (the building pick; without it there is no preview): the formation
 * already floats exactly where left click sets it down, so only the blocks that can't go there are
 * marked, in red. Charging a throw: the arc it will fly along, in pixel dots, and ghosts where it
 * lands. Same rules as the server ({@link MassDriver#release}, {@link OrbMass#massPlace}).
 */
@OnlyIn(Dist.CLIENT)
final class MassPreview {

    private MassPreview() {}

    private static final int TECTONIC = 0xD48050, BLOCKED = 0xE04040;

    static void render(
            Minecraft mc, Player player, ItemStack st, Vec3 cam, float time, VertexConsumer vc, Matrix4f m) {
        if (OrbMode.get(st) != OrbMode.MASS || !Traits.has(st, "tectonic.steady_hand")) return;
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
            // the throw: same launch and fall as the server, traced for the formation's anchor
            int ticks = player.getTicksUsingItem();
            int phase = Math.min(2, ticks * 3 / Math.max(1, Traits.chargeTicks(st)));
            BeamBallEntity anchor = held.get(0);
            for (var b : held) if (b.massOffset().equals(BlockPos.ZERO)) anchor = b;
            Vec3 pos = anchor.position();
            Vec3 v = player.getLookAngle().scale(OrbMode.MASS.speed(phase)).add(0, .08, 0);
            BlockPos land = null;
            Direction face = null;
            for (int i = 0; i < 80 && land == null; i++) {
                v = v.add(0, -.08, 0);
                Vec3 next = pos.add(v);
                var hit =
                        mc.level.clip(
                                new ClipContext(pos, next, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
                if (hit.getType() == HitResult.Type.BLOCK) {
                    land = hit.getBlockPos().relative(hit.getDirection());
                    face = hit.getDirection();
                    next = hit.getLocation();
                }
                if (i % 2 == 1) dot(vc, m, next.subtract(cam), time + i, .7f * pulse);
                pos = next;
            }
            if (land == null) return;
            for (var b : held) {
                Vec3 rel = b.position().subtract(anchor.position());
                BlockPos at = land.offset((int) Math.round(rel.x), (int) Math.round(rel.y), (int) Math.round(rel.z));
                ghost(vc, m, cam, at, fits(mc, b.getCarried(), at), pulse);
            }
            return;
        }

        // set down: the formation floats on the grid where it goes; mark what is in the way
        var hit = MassDriver.aim(player);
        if (hit == null) return;
        BlockPos base = hit.getBlockPos().relative(hit.getDirection());
        for (var b : held) {
            BlockPos at = base.offset(b.massOffset());
            if (!fits(mc, b.getCarried(), at)) ghost(vc, m, cam, at, false, pulse);
        }
    }

    private static boolean fits(Minecraft mc, BlockState bs, BlockPos at) {
        return MassDriver.canPlace(mc.level, mc.player, bs, at);
    }

    /** A see-through cube in the spot, a hair larger than the block. */
    private static void ghost(VertexConsumer vc, Matrix4f m, Vec3 cam, BlockPos at, boolean ok, float pulse) {
        int c = ok ? TECTONIC : BLOCKED;
        int r = c >> 16 & 255, g = c >> 8 & 255, b = c & 255, a = (int) (255 * .22f * pulse);
        double x0 = at.getX(), y0 = at.getY(), z0 = at.getZ(), x1 = x0 + 1, y1 = y0 + 1, z1 = z0 + 1;
        BlockGlow.rect(vc, m, Direction.DOWN, x0, y0, z0, x1, y0, z1, cam, .004f, r, g, b, a);
        BlockGlow.rect(vc, m, Direction.UP, x0, y1, z0, x1, y1, z1, cam, .004f, r, g, b, a);
        BlockGlow.rect(vc, m, Direction.NORTH, x0, y0, z0, x1, y1, z0, cam, .004f, r, g, b, a);
        BlockGlow.rect(vc, m, Direction.SOUTH, x0, y0, z1, x1, y1, z1, cam, .004f, r, g, b, a);
        BlockGlow.rect(vc, m, Direction.WEST, x0, y0, z0, x0, y1, z1, cam, .004f, r, g, b, a);
        BlockGlow.rect(vc, m, Direction.EAST, x1, y0, z0, x1, y1, z1, cam, .004f, r, g, b, a);
    }

    private static void dot(VertexConsumer vc, Matrix4f m, Vec3 at, float spin, float alpha) {
        BeamFx.spinCube(
                vc, m, at, BeamFx.PX, spin * .2, .5, (TECTONIC >> 16 & 255) / 255f, (TECTONIC >> 8 & 255) / 255f,
                (TECTONIC & 255) / 255f, alpha);
    }
}
