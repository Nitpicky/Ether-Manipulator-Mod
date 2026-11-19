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
                                                    .forGetter(ManipulatorData::repaired),
                                            Codec.BOOL
                                                    .optionalFieldOf("introduced", false)
                                                    .forGetter(d -> d.introduced),
                                            // saves from before these flags: owned and already greeted
                                            Codec.BOOL.optionalFieldOf("owned", true).forGetter(d -> d.owned),
                                            Codec.BOOL.optionalFieldOf("greeted", true).forGetter(d -> d.greeted))
                                    .apply(i, ManipulatorData::new));

    private ItemStack stack;
    private boolean introduced;
    private boolean summoned;
    private boolean owned = true, greeted;

    public ManipulatorData() {
        this(ItemStack.EMPTY, false);
    }

    public ManipulatorData(ItemStack stack, boolean introduced) {
        this.stack = stack;
        this.introduced = introduced;
    }

    public ManipulatorData(ItemStack stack, boolean introduced, boolean owned, boolean greeted) {
        this(stack, introduced);
        this.owned = owned;
        this.greeted = greeted;
    }

    /** Whether the player has the Manipulator (server config give_on_first_join, or /ethermod grant). */
    public boolean owned() {
        return owned;
    }

    public void setOwned(boolean owned) {
        this.owned = owned;
    }

    /** Whether the summon hint was shown. */
    public boolean greeted() {
        return greeted;
    }

    public void setGreeted(boolean greeted) {
        this.greeted = greeted;
    }

    /**
     * The stored stack, with a shrunk-away Manipulator brought back. While summoned the
     * Manipulator is the main-hand stack, so a vanilla interaction that consumes the held item
     * (item frame, allay, decorated pot...) can shrink it to 0; it is still the same Manipulator
     * with all its progress, so it gets its count back instead of being replaced.
     */
    private ItemStack repaired() {
        if (stack != ItemStack.EMPTY && stack.getCount() <= 0) {
            ItemStack shrunk = stack;
            int count = shrunk.getCount();
            shrunk.setCount(1); // an empty stack reports AIR; with a count it shows its real item
            if (shrunk.is(ModItems.ETHER_MANIPULATOR.get())) {
                com.ethermod.EtherMod.LOGGER.warn(
                        "Ether Manipulator stack was consumed to {} by an interaction; restored it", count);
            } else {
                shrunk.setCount(count);
            }
        }
        return stack;
    }

    /** The Manipulator stack. Created on first access if missing or corrupted. */
    public ItemStack stack() {
        repaired();
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
