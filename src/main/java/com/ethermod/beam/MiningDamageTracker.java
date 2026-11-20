package com.ethermod.beam;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Tracks accumulated ball-impact mining damage per block position.
 *
 * <p>Each ball hit contributes miningPower / blockHardness to the block's total. When total >= 1.0
 * the block breaks (drops items). Otherwise, the visual crack (destroyBlockProgress 0–9) is updated
 * — which MultiMine's hooks will detect and persist between hits.
 *
 * <p>Cracks heal: once a block has not been hit for HEAL_DELAY ticks, its damage drains at
 * HEAL_PER_TICK, the crack shrinking stage by stage until it is gone. The client's block glow fades
 * on the same timeline (BlockGlow).
 */
public final class MiningDamageTracker {

    private MiningDamageTracker() {}

    /** Ticks without a hit before a crack starts to heal (matches BlockGlow.HOLD_TICKS). */
    public static final int HEAL_DELAY = 10;

    /**
     * Damage healed per tick: a nearly broken block heals fully in 20 ticks (matches
     * BlockGlow.FADE_TICKS).
     */
    public static final float HEAL_PER_TICK = 0.05f;

    /**
     * Dimension → (block position key → accumulated damage state). Keyed per dimension so the same
     * x/y/z in two dimensions never share crack progress.
     */
    private static final Map<ResourceKey<Level>, Map<Long, DamageEntry>> DAMAGE_MAP =
            new HashMap<>();

    // =========================================================================
    // Public API
    // =========================================================================

    /**
     * Apply a hit's worth of mining damage to a block.
     *
     * @param level the server level
     * @param pos position of the block being damaged
     * @param breakerId unique ID used for the visual crack (use entity ID)
     * @param damageAmount fraction of block hardness to apply (0..1+)
     * @param player the owning player for drop attribution (may be null)
     */
    public static void applyDamage(
            ServerLevel level,
            BlockPos pos,
            int breakerId,
            float damageAmount,
            ServerPlayer player) {
        if (com.ethermod.config.ServerConfig.blacklisted(level.getBlockState(pos))) return;
        long key = pos.asLong();
        Map<Long, DamageEntry> damage =
                DAMAGE_MAP.computeIfAbsent(level.dimension(), k -> new HashMap<>());
        DamageEntry entry = damage.computeIfAbsent(key, k -> new DamageEntry());

        entry.accumulated += damageAmount;
        entry.lastHitTick = currentTick(level);
        entry.breakerId = breakerId;

        if (entry.accumulated >= 1.0f) {
            // Block is fully broken
            damage.remove(key);
            level.destroyBlockProgress(breakerId, pos, -1); // clear visual
            if (player != null) {
                // Full player break: protection/claims, BreakEvent, tool-aware loot and XP
                breakAsPlayer(player, pos);
            } else {
                level.destroyBlock(pos, true);
            }
        } else {
            // Update crack visual: destroyBlockProgress takes 0-9 (9 = almost broken)
            int visualStage = (int) (entry.accumulated * 9);
            level.destroyBlockProgress(breakerId, pos, visualStage);
        }
    }

    /** Called every server level tick: heals cracks that are no longer being hit. */
    public static void tick(ServerLevel level) {
        Map<Long, DamageEntry> damage = DAMAGE_MAP.get(level.dimension());
        if (damage == null || damage.isEmpty()) return;
        long now = currentTick(level);
        Iterator<Map.Entry<Long, DamageEntry>> it = damage.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Long, DamageEntry> e = it.next();
            DamageEntry d = e.getValue();
            if (now - d.lastHitTick <= HEAL_DELAY) continue;
            BlockPos pos = BlockPos.of(e.getKey());
            int before = (int) (d.accumulated * 9);
            d.accumulated -= HEAL_PER_TICK;
            if (d.accumulated <= 0f || level.getBlockState(pos).isAir()) {
                level.destroyBlockProgress(d.breakerId, pos, -1);
                it.remove();
            } else {
                int after = (int) (d.accumulated * 9);
                if (after != before) level.destroyBlockProgress(d.breakerId, pos, after);
            }
        }
    }

    /**
     * Breaks a block as the player (protection, BreakEvent, tool-aware loot and XP). Vanilla sends
     * the break particles and sound to everyone except the breaker, whose client normally plays
     * them itself; the beam has no client prediction, so send them to the breaker too.
     */
    public static boolean breakAsPlayer(ServerPlayer player, BlockPos pos) {
        BlockState state = player.level().getBlockState(pos);
        boolean broken = player.gameMode.destroyBlock(pos);
        if (broken) {
            player.connection.send(
                    new net.minecraft.network.protocol.game.ClientboundLevelEventPacket(
                            2001, pos, net.minecraft.world.level.block.Block.getId(state), false));
        }
        return broken;
    }

    /** Clears all tracked damage (call on level unload). */
    public static void clear() {
        DAMAGE_MAP.clear();
    }

    // =========================================================================
    // Internal
    // =========================================================================

    private static long currentTick(ServerLevel level) {
        return level.getGameTime();
    }

    private static class DamageEntry {
        float accumulated = 0f;
        long lastHitTick = 0L;
        int breakerId = 0;
    }
}
