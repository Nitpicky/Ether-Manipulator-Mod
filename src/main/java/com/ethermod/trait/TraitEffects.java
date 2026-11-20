package com.ethermod.trait;

import com.ethermod.EtherMod;
import com.ethermod.item.EtherManipulatorItem;
import com.ethermod.manipulator.Manipulator;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.Tags;
import net.neoforged.neoforge.event.level.BlockDropsEvent;
import net.neoforged.neoforge.event.level.BlockEvent;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/**
 * Trait effects that hook game events (drops, block breaks). Effects that live inside the beam or
 * orb code are there instead.
 */
public final class TraitEffects {

    private TraitEffects() {}

    private static final int VEIN_LIMIT = 64;
    private static boolean veinMining = false;

    // =========================================================================
    // Drops: Collector
    // =========================================================================

    // last, so drops other mods and traits add are collected too
    @SubscribeEvent(priority = net.neoforged.bus.api.EventPriority.LOWEST)
    public static void onBlockDrops(BlockDropsEvent event) {
        if (!(event.getBreaker() instanceof ServerPlayer player)) return;
        ItemStack tool = event.getTool();
        if (!(tool.getItem() instanceof EtherManipulatorItem)) return;
        ServerLevel level = event.getLevel();
        List<ItemEntity> drops = event.getDrops();

        // Collector (Tectonic common): drops pop out, then fly to you
        if (Traits.hasCommon(tool, Traits.TECTONIC, 0)) {
            for (ItemEntity ie : drops) com.ethermod.beam.LeftClick.magnet(ie, player);
            if (event.getDroppedExperience() > 0) {
                ExperienceOrb.award(level, player.position(), event.getDroppedExperience());
                event.setDroppedExperience(0);
            }
        }
    }

    // =========================================================================
    // Vein Miner
    // =========================================================================

    @SubscribeEvent
    public static void onBreak(BlockEvent.BreakEvent event) {
        if (com.ethermod.beam.MassDriver.lifting || com.ethermod.util.Protect.probing) return;
        if (!(event.getPlayer() instanceof ServerPlayer player) || !Manipulator.isSummoned(player))
            return;
        ItemStack tool = player.getMainHandItem();
        BlockState state = event.getState();
        if (veinMining) return;
        if (!state.is(Tags.Blocks.ORES) || !Traits.has(tool, "tectonic.vein_miner")) return;
        if (!(event.getLevel() instanceof ServerLevel level)) return;

        Block block = state.getBlock();
        BlockPos origin = event.getPos();
        Set<BlockPos> seen = new HashSet<>();
        Deque<BlockPos> queue = new ArrayDeque<>();
        List<BlockPos> vein = new ArrayList<>();
        queue.add(origin);
        seen.add(origin);
        int limit = com.ethermod.config.ServerConfig.veinLimit();
        while (!queue.isEmpty() && vein.size() < limit) {
            BlockPos p = queue.poll();
            for (BlockPos n : BlockPos.betweenClosed(p.offset(-1, -1, -1), p.offset(1, 1, 1))) {
                BlockPos q = n.immutable();
                if (!seen.add(q)) continue;
                if (level.getBlockState(q).is(block)) {
                    vein.add(q);
                    queue.add(q);
                    if (vein.size() >= limit - 1) break;
                }
            }
        }
        // Break the rest after this event finishes, through the game mode (protection, loot, XP)
        level.getServer()
                .execute(
                        () -> {
                            veinMining = true;
                            try {
                                for (BlockPos q : vein) {
                                    if (!player.isAlive() || player.level() != level) break;
                                    com.ethermod.beam.MiningDamageTracker.breakAsPlayer(player, q);
                                }
                            } finally {
                                veinMining = false;
                            }
                        });
    }
}
