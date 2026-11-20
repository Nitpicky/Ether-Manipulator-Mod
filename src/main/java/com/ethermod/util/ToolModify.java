package com.ethermod.util;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.common.ItemAbility;

/**
 * Tilling, path flattening, log stripping, copper scraping and wax removal for the utility beam.
 * The 26.3 NeoForge build no longer ships the hoe/shovel/axe abilities, so each action first asks
 * the block through NeoForge's tool-modification hook (modded blocks that still answer to the old
 * ability names keep working), then falls back to the vanilla rules written out here.
 */
public final class ToolModify {
    private ToolModify() {}

    public enum Action {
        TILL("till"),
        FLATTEN("flatten"),
        STRIP("strip"),
        SCRAPE("scrape"),
        WAX_OFF("wax_off");

        final ItemAbility ability;

        Action(String name) {
            this.ability = ItemAbility.get(name);
        }
    }

    /** The state this action turns {@code bs} into, or null when it does nothing. */
    public static BlockState apply(BlockState bs, UseOnContext ctx, Action a, boolean simulate) {
        BlockState hooked = bs.getToolModifiedState(ctx, a.ability, simulate);
        if (hooked != null && hooked != bs) return hooked;
        return switch (a) {
            case TILL -> till(bs);
            case FLATTEN -> flatten(bs);
            case STRIP -> strip(bs);
            case SCRAPE -> net.minecraft.world.level.block.WeatheringCopper.getPrevious(bs).orElse(null);
            case WAX_OFF -> waxOff(bs);
        };
    }

    private static BlockState till(BlockState bs) {
        if (bs.is(Blocks.GRASS_BLOCK) || bs.is(Blocks.DIRT_PATH) || bs.is(Blocks.DIRT))
            return Blocks.FARMLAND.defaultBlockState();
        if (bs.is(Blocks.COARSE_DIRT) || bs.is(Blocks.ROOTED_DIRT)) return Blocks.DIRT.defaultBlockState();
        return null;
    }

    private static BlockState flatten(BlockState bs) {
        if (bs.is(Blocks.GRASS_BLOCK) || bs.is(Blocks.DIRT) || bs.is(Blocks.PODZOL) || bs.is(Blocks.COARSE_DIRT)
                || bs.is(Blocks.MYCELIUM) || bs.is(Blocks.ROOTED_DIRT))
            return Blocks.DIRT_PATH.defaultBlockState();
        return null;
    }

    /** Logs, woods and stems: the block named "stripped_" + its own name, keeping the axis. */
    private static BlockState strip(BlockState bs) {
        Identifier id = BuiltInRegistries.BLOCK.getKey(bs.getBlock());
        if (id.getPath().startsWith("stripped_")) return null;
        Block stripped =
                BuiltInRegistries.BLOCK
                        .getOptional(Identifier.fromNamespaceAndPath(id.getNamespace(), "stripped_" + id.getPath()))
                        .orElse(null);
        if (stripped == null) return null;
        BlockState out = stripped.defaultBlockState();
        if (bs.hasProperty(RotatedPillarBlock.AXIS) && out.hasProperty(RotatedPillarBlock.AXIS))
            out = out.setValue(RotatedPillarBlock.AXIS, bs.getValue(RotatedPillarBlock.AXIS));
        return out;
    }

    /** Waxed copper: the block of the same name without "waxed_", keeping every shared property. */
    private static BlockState waxOff(BlockState bs) {
        Identifier id = BuiltInRegistries.BLOCK.getKey(bs.getBlock());
        if (!id.getPath().startsWith("waxed_")) return null;
        Block plain =
                BuiltInRegistries.BLOCK
                        .getOptional(Identifier.fromNamespaceAndPath(id.getNamespace(), id.getPath().substring(6)))
                        .orElse(null);
        return plain == null ? null : plain.withPropertiesOf(bs);
    }
}
