package com.ethermod.trait;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/** Blocks placed for a while (Frost bridges, placed water/lava, soul fire), then put back. */
public final class TempBlocks {

    private TempBlocks() {}

    /** A block placed for a while (Frost bridges), then put back as it was. */
    public static boolean showsExpiryCracks(
            net.minecraft.world.level.block.state.BlockState state) {
        return !state.is(net.minecraft.tags.BlockTags.FIRE);
    }

    private record TempBlock(
            net.minecraft.world.level.block.state.BlockState placed,
            net.minecraft.world.level.block.state.BlockState restore,
            long placedAt,
            long expires,
            int crackId,
            boolean holdWhileStood) {}

    private static final Map<ServerLevel, Map<BlockPos, TempBlock>> TEMP_BLOCKS = new HashMap<>();
    private static final Map<ServerLevel, Map<BlockPos, java.util.UUID>> BRIDGES = new HashMap<>();

    public static void markBridge(ServerLevel level, BlockPos pos, java.util.UUID bridge) {
        BRIDGES.computeIfAbsent(level, k -> new HashMap<>()).put(pos.immutable(), bridge);
    }

    public static boolean isAirPocket(ServerLevel level, BlockPos pos) {
        var map = TEMP_BLOCKS.get(level);
        var t = map == null ? null : map.get(pos);
        return t != null && t.placed().isAir() && t.restore().is(Blocks.WATER);
    }

    private static int nextCrackId = -0x4D4D0000;

    /**
     * Places {@code state} at {@code pos} for {@code ticks}, then restores what was there (air or
     * water). In its last 10 seconds it cracks visibly. Only replaces air, water sources, or
     * replaceable plants.
     */
    public static boolean tempBlock(
            ServerLevel level,
            BlockPos pos,
            net.minecraft.world.level.block.state.BlockState state,
            int ticks) {
        return tempBlock(level, pos, state, ticks, false);
    }

    /**
     * As above; with {@code holdWhileStood} it never starts cracking while a player stands on it
     * (Permafrost).
     */
    public static boolean tempBlock(
            ServerLevel level,
            BlockPos pos,
            net.minecraft.world.level.block.state.BlockState state,
            int ticks,
            boolean holdWhileStood) {
        var cur = level.getBlockState(pos);
        boolean waterSource = cur.is(Blocks.WATER);
        if (!cur.isAir() && !waterSource && !(cur.canBeReplaced() && cur.getFluidState().isEmpty()))
            return false;
        var restore = waterSource ? cur : Blocks.AIR.defaultBlockState();
        // the block's own placement checks (fire's canSurvive in onPlace) run inside setBlock,
        // before the position is recorded below: mark it as being placed for that call
        placingLevel = level;
        placing = pos;
        try {
            level.setBlock(pos, state, state.isAir() ? 2 : 3);
        } finally {
            placingLevel = null;
            placing = null;
        }
        long now = level.getGameTime();
        TEMP_BLOCKS
                .computeIfAbsent(level, k -> new HashMap<>())
                .put(
                        pos.immutable(),
                        new TempBlock(
                                state, restore, now, now + ticks, nextCrackId++, holdWhileStood));
        PendingReverts.add(level, pos, state, restore);
        return true;
    }

    /** The position {@link #tempBlock} is placing right now (server thread only), or null. */
    private static BlockPos placing;

    private static ServerLevel placingLevel;

    /** Whether {@code pos} holds (or is being given, inside {@link #tempBlock}) a temporary block. */
    public static boolean isTempBlock(ServerLevel level, BlockPos pos) {
        if (level == placingLevel && pos.equals(placing)) return true;
        Map<BlockPos, TempBlock> m = TEMP_BLOCKS.get(level);
        return m != null && m.containsKey(pos);
    }

    /** Per-level tick: expiry, restore and the crack animation. */
    static void tick(ServerLevel level, long now) {
        var occupiedBridges = new java.util.HashSet<java.util.UUID>();
        var bridgeIds = BRIDGES.get(level);
        if (bridgeIds != null)
            for (var player : level.players()) {
                var feet =
                        player.position()
                                .add(
                                        com.ethermod.trait.MovementPhysics.vector(
                                                        com.ethermod.trait.MovementPhysics.down(
                                                                player))
                                                .scale(.1));
                var group = bridgeIds.get(BlockPos.containing(feet));
                if (group != null) occupiedBridges.add(group);
            }
        Map<BlockPos, TempBlock> temps = TEMP_BLOCKS.get(level);
        if (temps != null && !temps.isEmpty()) {
            Iterator<Map.Entry<BlockPos, TempBlock>> it = temps.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<BlockPos, TempBlock> e = it.next();
                BlockPos p = e.getKey();
                TempBlock t = e.getValue();
                if (!level.isLoaded(p)) continue;
                if (t.placed().isAir()
                        && t.restore().is(Blocks.WATER)
                        && level.getBlockState(p).is(Blocks.WATER))
                    level.setBlock(p, Blocks.AIR.defaultBlockState(), 2);
                boolean stillThere = level.getBlockState(p).is(t.placed().getBlock());
                if (!stillThere) {
                    level.destroyBlockProgress(t.crackId(), p, -1);
                    PendingReverts.remove(level, p);
                    it.remove();
                    continue;
                }
                long left = t.expires() - now;
                if (t.holdWhileStood()
                        && left <= 200
                        && ((bridgeIds != null && occupiedBridges.contains(bridgeIds.get(p)))
                                || !level.getEntitiesOfClass(
                                                net.minecraft.world.entity.player.Player.class,
                                                new AABB(p.above()).inflate(0.2, 0.5, 0.2))
                                        .isEmpty())) {
                    // Permafrost: someone is standing on it; it waits for them
                    if (left < 200) level.destroyBlockProgress(t.crackId(), p, -1);
                    e.setValue(
                            new TempBlock(
                                    t.placed(),
                                    t.restore(),
                                    t.placedAt(),
                                    now + 201,
                                    t.crackId(),
                                    true));
                    continue;
                }
                if (left <= 0) {
                    level.destroyBlockProgress(t.crackId(), p, -1);
                    if (showsExpiryCracks(t.placed()))
                        level.levelEvent(
                                2001, p, net.minecraft.world.level.block.Block.getId(t.placed()));
                    level.setBlockAndUpdate(p, t.restore());
                    PendingReverts.remove(level, p);
                    it.remove();
                } else if (left <= 200 && now % 10 == 0 && showsExpiryCracks(t.placed())) {
                    // cracks grow over the last 10 seconds
                    level.destroyBlockProgress(t.crackId(), p, (int) (9 - left * 9 / 200));
                }
            }
        }
    }

    /** Puts every still-standing temporary block back. */
    static void restoreOnStop() {
        TEMP_BLOCKS.forEach(
                (level, m) ->
                        m.forEach(
                                (p, t) -> {
                                    if (level.getBlockState(p).is(t.placed().getBlock()))
                                        level.setBlock(p, t.restore(), 3);
                                }));
        TEMP_BLOCKS.clear();
        BRIDGES.clear();
    }
}
