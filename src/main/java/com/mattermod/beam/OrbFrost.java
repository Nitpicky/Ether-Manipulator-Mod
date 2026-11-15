package com.mattermod.beam;

import com.mattermod.item.OrbMode;
import com.mattermod.trait.WorldEffects;
import com.mattermod.util.FoliageHelper;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Frost mode: the ice bridge laid under a charged shard, and foliage cleared from its path. Split
 * out of {@link BeamBallEntity}; every method takes the orb as {@code b}.
 */
final class OrbFrost {

    private OrbFrost() {}

    /**
     * Charged Frost: lays a 1-wide ice bridge one block under the shard's
     * flight, so a level shot makes a walkable path at your feet's height. 8 blocks at phase 2, 16
     * at phase 3. The ice cracks and melts back after {@link #BRIDGE_TICKS}.
     */
    static void frostBridge(BeamBallEntity b, Vec3 from, Vec3 movement) {
        if (b.getMode() != OrbMode.FROST || b.getPhase() == 0) return;
        ServerLevel sl = (ServerLevel) b.level();
        int steps = Math.max(1, (int) Math.ceil(movement.length() / 0.2));
        BlockPos previous = BlockPos.containing(from.x, from.y - 2, from.z);
        for (int i = 0; i <= steps; i++) {
            Vec3 v = from.add(movement.scale(i / (double) steps));
            BlockPos next = BlockPos.containing(v.x, v.y - 2, v.z);
            // A Manhattan connector closes diagonal corner gaps without widening the base bridge.
            BlockPos cursor = previous;
            while (!cursor.equals(next)) {
                OrbFrost.layBridgeCell(b, sl, cursor);
                if (cursor.getX() != next.getX())
                    cursor = cursor.offset(Integer.signum(next.getX() - cursor.getX()), 0, 0);
                else if (cursor.getZ() != next.getZ())
                    cursor = cursor.offset(0, 0, Integer.signum(next.getZ() - cursor.getZ()));
                else cursor = cursor.offset(0, Integer.signum(next.getY() - cursor.getY()), 0);
            }
            OrbFrost.layBridgeCell(b, sl, next);
            previous = next;
        }
    }

    static void layBridgeCell(BeamBallEntity b, ServerLevel sl, BlockPos q) {
        if (!b.bridgeSeen.add(q.asLong()) || !b.mayBuild(q)) return;
        if (b.getOwner() != null && b.getOwner().getBoundingBox().intersects(new AABB(q))) return;
        if (WorldEffects.tempBlock(
                sl, q, Blocks.PACKED_ICE.defaultBlockState(), OrbFrost.bridgeTicks(b), true))
            WorldEffects.markBridge(sl, q, b.getUUID());
    }

    /** Frost bridges last 3x longer with Glacier. */
    static int bridgeTicks(BeamBallEntity b) {
        return b.BRIDGE_TICKS * (b.has("hydraulics.glacier") ? 3 : 1);
    }

    /** Permafrost: Frost ice never cracks while someone stands on it. */
    static boolean permafrost(BeamBallEntity b) {
        return true;
    }
}
