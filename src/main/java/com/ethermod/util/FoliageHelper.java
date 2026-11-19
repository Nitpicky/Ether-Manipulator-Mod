package com.ethermod.util;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Utility: determines whether a block is "foliage" that the beam passes through without stopping
 * (and instantly destroys).
 *
 * <p>Covers: grass, tall grass, ferns, flowers (single + double), leaves, vines, saplings, sea
 * grass, kelp, sugar cane, bamboo shoot, dead bush, moss, hanging roots, cave vines.
 *
 * <p>We intentionally avoid using Tags here so the logic works without data-gen on first boot.
 * Using material-based check + explicit list is the most reliable approach for 1.21.1.
 */
public final class FoliageHelper {

    private FoliageHelper() {}

    /** A crop the server lists in extra_crops: ripe when its "age" property is at its top value. */
    public static boolean extraRipe(BlockState s) {
        if (!com.ethermod.config.ServerConfig.extraCrop(s)) return false;
        for (var prop : s.getProperties())
            if (prop.getName().equals("age") && prop instanceof net.minecraft.world.level.block.state.properties.IntegerProperty ip)
                return s.getValue(ip) >= java.util.Collections.max(ip.getPossibleValues());
        return true;
    }

    /**
     * Does the beam pass through this block? Leaves and the smaller plants (grass, flowers, vines…)
     * each follow their own server setting: by default the beam mines leaves like any block and
     * passes through the small plants.
     */
    public static boolean isFoliage(BlockState state) {
        if (!isPlant(state)) return false;
        if (isLeaves(state)) return !com.ethermod.config.ServerConfig.beamMinesLeaves();
        return !com.ethermod.config.ServerConfig.beamMinesPlants();
    }

    public static boolean isLeaves(BlockState state) {
        return state.is(net.minecraft.tags.BlockTags.LEAVES);
    }

    /**
     * Leaves, grass, flowers, vines and the like, whatever the beam settings: what Gust and fire
     * shots cut and the sneak beam shears.
     */
    public static boolean isPlant(BlockState state) {
        Block b = state.getBlock();
        if (com.ethermod.config.ServerConfig.extraFoliage(state)) return true;
        if (isLeaves(state)) return true;

        // Leaves
        if (b == Blocks.OAK_LEAVES
                || b == Blocks.BIRCH_LEAVES
                || b == Blocks.SPRUCE_LEAVES
                || b == Blocks.JUNGLE_LEAVES
                || b == Blocks.ACACIA_LEAVES
                || b == Blocks.DARK_OAK_LEAVES
                || b == Blocks.MANGROVE_LEAVES
                || b == Blocks.CHERRY_LEAVES
                || b == Blocks.AZALEA_LEAVES
                || b == Blocks.FLOWERING_AZALEA_LEAVES) {
            return true;
        }

        // Grass / ferns
        if (b == Blocks.SHORT_GRASS
                || b == Blocks.TALL_GRASS
                || b == Blocks.FERN
                || b == Blocks.LARGE_FERN) {
            return true;
        }

        // Flowers (single-block)
        if (b == Blocks.DANDELION
                || b == Blocks.POPPY
                || b == Blocks.BLUE_ORCHID
                || b == Blocks.ALLIUM
                || b == Blocks.AZURE_BLUET
                || b == Blocks.RED_TULIP
                || b == Blocks.ORANGE_TULIP
                || b == Blocks.WHITE_TULIP
                || b == Blocks.PINK_TULIP
                || b == Blocks.OXEYE_DAISY
                || b == Blocks.CORNFLOWER
                || b == Blocks.LILY_OF_THE_VALLEY
                || b == Blocks.WITHER_ROSE
                || b == Blocks.TORCHFLOWER) {
            return true;
        }

        // Tall flowers
        if (b == Blocks.SUNFLOWER
                || b == Blocks.LILAC
                || b == Blocks.ROSE_BUSH
                || b == Blocks.PEONY
                || b == Blocks.PITCHER_PLANT) {
            return true;
        }

        // Vines / hanging stuff
        if (b == Blocks.VINE
                || b == Blocks.GLOW_LICHEN
                || b == Blocks.HANGING_ROOTS
                || b == Blocks.CAVE_VINES
                || b == Blocks.CAVE_VINES_PLANT
                || b == Blocks.WEEPING_VINES
                || b == Blocks.WEEPING_VINES_PLANT
                || b == Blocks.TWISTING_VINES
                || b == Blocks.TWISTING_VINES_PLANT) {
            return true;
        }

        // Saplings
        if (b == Blocks.OAK_SAPLING
                || b == Blocks.BIRCH_SAPLING
                || b == Blocks.SPRUCE_SAPLING
                || b == Blocks.JUNGLE_SAPLING
                || b == Blocks.ACACIA_SAPLING
                || b == Blocks.DARK_OAK_SAPLING
                || b == Blocks.MANGROVE_PROPAGULE
                || b == Blocks.CHERRY_SAPLING) {
            return true;
        }

        // Aquatic / misc
        if (b == Blocks.SEAGRASS
                || b == Blocks.TALL_SEAGRASS
                || b == Blocks.KELP
                || b == Blocks.KELP_PLANT
                || b == Blocks.SUGAR_CANE
                || b == Blocks.BAMBOO
                || b == Blocks.BAMBOO_SAPLING
                || b == Blocks.DEAD_BUSH
                || b == Blocks.MOSS_CARPET
                || b == Blocks.PINK_PETALS) {
            return true;
        }

        return false;
    }
}
