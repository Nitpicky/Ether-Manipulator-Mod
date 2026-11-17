package com.ethermod.manipulator;

import com.ethermod.registry.ModItems;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.world.item.ItemStack;

/**
 * Per-player Manipulator state, stored as a data attachment on the player.
 *
 * <p>The Manipulator never exists as an inventory item: its stack (stage, traits) lives here.
 * {@code summoned} is runtime-only and always starts false.
 */
public final class ManipulatorData {

    public static final Codec<ManipulatorData> CODEC =
            RecordCodecBuilder.create(
                    i ->
                            i.group(
                                            ItemStack.OPTIONAL_CODEC
                                                    .optionalFieldOf("stack", ItemStack.EMPTY)
                                                    .forGetter(d -> d.stack),
                                            Codec.BOOL
                                                    .optionalFieldOf("introduced", false)
                                                    .forGetter(d -> d.introduced))
                                    .apply(i, ManipulatorData::new));

    private ItemStack stack;
    private boolean introduced;
    private boolean summoned;

    public ManipulatorData() {
        this(ItemStack.EMPTY, false);
    }

    public ManipulatorData(ItemStack stack, boolean introduced) {
        this.stack = stack;
        this.introduced = introduced;
    }

    /** The Manipulator stack. Created on first access if missing or corrupted. */
    public ItemStack stack() {
        if (stack.isEmpty() || !stack.is(ModItems.ETHER_MANIPULATOR.get())) {
            stack = new ItemStack(ModItems.ETHER_MANIPULATOR.get());
        }
        return stack;
    }

    public void setStack(ItemStack stack) {
        this.stack = stack;
    }

    public boolean summoned() {
        return summoned;
    }

    public void setSummoned(boolean summoned) {
        this.summoned = summoned;
    }

    /** Whether the player has been told how to summon it (first join). */
    public boolean introduced() {
        return introduced;
    }

    public void setIntroduced(boolean introduced) {
        this.introduced = introduced;
    }
}
