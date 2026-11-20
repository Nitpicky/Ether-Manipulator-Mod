package com.ethermod.trait;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.Blocks;

import java.util.HashMap;
import java.util.Map;

/** Fire and lava a player's orbs set: ownership, Controlled Burn, Fire Ward lookups. */
public final class OwnedFire {

    private OwnedFire() {}

    /** A ring of fire of the given radius (1 = the 8 blocks around the centre). */
    public static void fireRing(ServerLevel level, BlockPos center, Entity owner, int radius) {
        for (BlockPos p :
                BlockPos.betweenClosed(
                        center.offset(-radius, -1, -radius), center.offset(radius, 1, radius))) {
            int dx = Math.abs(p.getX() - center.getX()), dz = Math.abs(p.getZ() - center.getZ());
            if (Math.max(dx, dz) != radius) continue;
            placeFire(level, p.immutable(), owner);
        }
    }

    /**
     * Fire (or lava) a player's orbs set: whose it is, and for Controlled Burn when it goes out.
     */
    private record Fire(java.util.UUID owner, long expires, boolean controlled) {}

    private static final Map<ServerLevel, Map<BlockPos, Fire>> FIRES = new HashMap<>();

    /**
     * Lights a fire like flint and steel would, remembering whose it is. With Controlled Burn it
     * never spreads and burns out after 30 s.
     */
    public static boolean placeFire(ServerLevel level, BlockPos pos, Entity owner) {
        if (!com.ethermod.config.ServerConfig.fireAndLava()) return false;
        if (owner instanceof ServerPlayer sp && !com.ethermod.util.Protect.may(level, sp, pos)) return false;
        if (!level.getBlockState(pos).isAir()
                || !BaseFireBlock.canBePlacedAt(level, pos, net.minecraft.core.Direction.UP))
            return false;
        boolean soul =
                owner instanceof ServerPlayer player
                        && Traits.has(
                                com.ethermod.manipulator.Manipulator.stack(player),
                                "pyrotechnic.soulwake");
        if (soul) TempBlocks.tempBlock(level, pos, Blocks.SOUL_FIRE.defaultBlockState(), 600);
        else level.setBlockAndUpdate(pos, BaseFireBlock.getState(level, pos));
        if (owner instanceof ServerPlayer sp) {
            int burn =
                    Traits.has(
                                    com.ethermod.manipulator.Manipulator.stack(sp),
                                    "pyrotechnic.wildfire")
                            ? 0
                            : 1;
            long now = level.getGameTime();
            FIRES.computeIfAbsent(level, k -> new HashMap<>())
                    .put(
                            pos.immutable(),
                            new Fire(sp.getUUID(), now + 600, burn > 0));
        }
        return true;
    }

    public static void burnFromOwnedFire(ServerLevel level, BlockPos pos, Entity entity) {
        if (!(entity instanceof net.minecraft.world.entity.LivingEntity living)
                || entity.tickCount % 5 != 0) return;
        var map = FIRES.get(level);
        if (map == null) return;
        Fire fire = map.get(pos);
        if (fire == null) return;
        var owner = level.getServer().getPlayerList().getPlayer(fire.owner());
        if (owner != null && Targeting.enemy(owner, living))
            Burning.burn(living, owner, 8, -1);
    }

    public static void burnFromOwnedLava(ServerLevel level, Entity entity) {
        if (!(entity instanceof net.minecraft.world.entity.LivingEntity)
                || entity.tickCount % 5 != 0) return;
        var map = FIRES.get(level);
        if (map == null || map.isEmpty()) return;
        var queue = new java.util.ArrayDeque<BlockPos>();
        var seen = new java.util.HashSet<BlockPos>();
        var box = entity.getBoundingBox();
        for (var at :
                BlockPos.betweenClosed(
                        BlockPos.containing(box.minX, box.minY, box.minZ),
                        BlockPos.containing(box.maxX, box.maxY, box.maxZ)))
            if (level.getFluidState(at).is(net.minecraft.tags.FluidTags.LAVA))
                queue.add(at.immutable());
        for (int n = 0; n < 64 && !queue.isEmpty(); n++) {
            var pos = queue.removeFirst();
            if (!seen.add(pos)) continue;
            if (map.containsKey(pos)) {
                burnFromOwnedFire(level, pos, entity);
                return;
            }
            int amount = level.getFluidState(pos).getAmount();
            for (var direction : net.minecraft.core.Direction.values()) {
                var next = pos.relative(direction);
                if (!level.isLoaded(next)) continue;
                var fluid = level.getFluidState(next);
                if (fluid.is(net.minecraft.tags.FluidTags.LAVA)
                        && fluid.getAmount() >= amount
                        && !seen.contains(next)) queue.add(next);
            }
        }
    }

    /** Remembers lava a player's orb placed, for Fire Ward. */
    public static void ownLava(ServerLevel level, BlockPos pos, Entity owner) {
        if (owner instanceof ServerPlayer sp) {
            FIRES.computeIfAbsent(level, k -> new HashMap<>())
                    .put(pos.immutable(), new Fire(sp.getUUID(), Long.MAX_VALUE - 1000, false));
        }
    }

    /**
     * Called from the fire block's tick (FireBlockMixin). For a controlled fire: keep it lit
     * without spreading until it burns out, and return true so vanilla's spreading tick is skipped.
     */
    public static boolean tickControlledFire(ServerLevel level, BlockPos pos) {
        Map<BlockPos, Fire> m = FIRES.get(level);
        Fire f = m == null ? null : m.get(pos);
        if (f == null || !f.controlled()) return false;
        var state = level.getBlockState(pos);
        if (level.getGameTime() >= f.expires() || !state.canSurvive(level, pos)) {
            level.removeBlock(pos, false);
            m.remove(pos);
        } else {
            level.scheduleTick(pos, state.getBlock(), 30 + level.random.nextInt(10));
        }
        return true;
    }

    /** Drops expired entries and ones whose fire/lava is gone (every 100 ticks). */
    static void tick(ServerLevel level, long now) {
        Map<BlockPos, Fire> fires = FIRES.get(level);
        if (fires != null && now % 100 == 0)
            fires.entrySet()
                    .removeIf(
                            e ->
                                    e.getValue().expires() + 200 < now
                                            || level.isLoaded(e.getKey())
                                                    && !level.getBlockState(e.getKey())
                                                            .is(net.minecraft.tags.BlockTags.FIRE)
                                                    && !level.getFluidState(e.getKey())
                                                            .is(net.minecraft.tags.FluidTags.LAVA));
    }

    static void clear() {
        FIRES.clear();
    }
}
