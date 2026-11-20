package com.ethermod.item;

import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The four stages of the Ether Manipulator, named like hardware revisions: Prototype (start, no
 * traits), Calibrated (Iron module: 1 slot, Adept tier), Augmented (Diamond module: 2 slots,
 * Master), Overclocked (Netherite module: 3 slots, Grandmaster). The enum constants keep the
 * material names because the modules are crafted from those materials. Each stage matches the
 * mining tier and damage output of the corresponding tool (Prototype: copper).
 *
 * <p>Stage is stored as an int (1-4) in the item's CustomData component. Default (no tag) = STONE
 * (stage 1).
 */
public enum ManipulatorStage {

    // id, miningSpeed, beamDmg/2ticks, ballDmgBase, beamRange, ballRange,
    // maxChargeTicks
    // Prototype is on par with copper tools: copper's mining speed, stone's harvest level and
    // damage (copper tools share both with stone)
    STONE(1, 5.0f, 1.0f, 4.0f, 5.0f, 20.0f, 60),
    IRON(2, 6.0f, 2.0f, 6.0f, 6.0f, 24.0f, 50),
    DIAMOND(3, 8.0f, 3.0f, 9.0f, 7.0f, 28.0f, 45),
    NETHERITE(4, 9.0f, 4.0f, 12.0f, 8.0f, 32.0f, 40);

    private static final String TAG_STAGE = "mm_stage";

    public final int id;

    // Combat / mining
    private final float miningSpeed; // matches vanilla tier speed
    private final float beamTickDamage; // damage per 2 ticks of LMB beam
    private final float ballBaseDamage; // phase-2 ball damage
    private final float beamRange; // beam reach in blocks
    private final float ballRange; // max ball travel distance
    private final int maxChargeTicks; // ticks for full charge (phase 2)

    ManipulatorStage(
            int id,
            float miningSpeed,
            float beamTickDamage,
            float ballBaseDamage,
            float beamRange,
            float ballRange,
            int maxChargeTicks) {
        this.id = id;
        this.miningSpeed = miningSpeed;
        this.beamTickDamage = beamTickDamage;
        this.ballBaseDamage = ballBaseDamage;
        this.beamRange = beamRange;
        this.ballRange = ballRange;
        this.maxChargeTicks = maxChargeTicks;
    }

    /**
     * Translated stage name (lang key ethermod.stage.<name>).
     */
    public net.minecraft.network.chat.MutableComponent title() {
        return net.minecraft.network.chat.Component.translatable(
                "ethermod.stage." + name().toLowerCase(java.util.Locale.ROOT));
    }

    // =========================================================================
    // Accessors
    // =========================================================================

    public float miningSpeed() {
        return miningSpeed;
    }

    public float beamTickDamage() {
        return beamTickDamage;
    }

    public float ballBaseDamage() {
        return ballBaseDamage;
    }

    public float beamRange() {
        return beamRange;
    }

    public float ballRange() {
        return ballRange;
    }

    public int maxChargeTicks() {
        return maxChargeTicks;
    }

    /** Blocks this stage can't harvest, matching the vanilla tool tier. */
    public TagKey<Block> incorrectForDrops() {
        return switch (this) {
            case STONE -> BlockTags.INCORRECT_FOR_STONE_TOOL;
            case IRON -> BlockTags.INCORRECT_FOR_IRON_TOOL;
            case DIAMOND -> BlockTags.INCORRECT_FOR_DIAMOND_TOOL;
            case NETHERITE -> BlockTags.INCORRECT_FOR_NETHERITE_TOOL;
        };
    }

    public boolean canHarvest(BlockState state) {
        return !state.is(incorrectForDrops());
    }

    /** Ball damage scaled by phase (0=25%, 1=55%, 2=100%). */
    public float ballDamage(int phase) {
        float[] mult = {0.25f, 0.55f, 1.0f};
        return ballBaseDamage * mult[Math.max(0, Math.min(2, phase))];
    }

    /** Returns the next stage, or null if already NETHERITE. */
    public ManipulatorStage next() {
        return switch (this) {
            case STONE -> IRON;
            case IRON -> DIAMOND;
            case DIAMOND -> NETHERITE;
            case NETHERITE -> null;
        };
    }

    // =========================================================================
    // NBT helpers
    // =========================================================================

    public static ManipulatorStage get(ItemStack stack) {
        if (com.ethermod.config.ServerConfig.allTiers()) return NETHERITE;
        // read-only: getUnsafe avoids a deep copy of the whole stack data on every trait lookup
        int id =
                stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY)
                        .copyTag()
                        .getInt(TAG_STAGE);
        for (ManipulatorStage s : values()) {
            if (s.id == id) return s;
        }
        return STONE; // default
    }

    public static void set(ItemStack stack, ManipulatorStage stage) {
        CompoundTag tag =
                stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        tag.putInt(TAG_STAGE, stage.id);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
    }

    /** Trait slots and tiers this stage opens: 0 at Stone, then 1 per upgrade. */
    public int traitTiers() {
        return id - 1;
    }

    /** The stage that opens trait slot / tier {@code index} (0-2). */
    public static ManipulatorStage opening(int index) {
        ManipulatorStage[] v = values();
        return v[Math.max(1, Math.min(v.length - 1, index + 1))];
    }

    /** Try to apply one upgrade tier. Returns false if already max stage. */
    public static boolean tryUpgrade(ItemStack stack) {
        ManipulatorStage current = get(stack);
        ManipulatorStage next = current.next();
        if (next == null) return false;
        set(stack, next);
        return true;
    }
}
