package com.ethermod.trait;

import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.item.enchantment.ItemEnchantments;

/**
 * Fortune, Silk Touch and Looting traits work by writing the matching vanilla enchantments onto the
 * Manipulator's stack, so vanilla and modded loot tables (match_tool, apply_bonus, looting) behave
 * exactly as with an enchanted tool. The item hides the glint, and it can never reach an anvil or
 * table.
 *
 * <p>Call {@link #refresh} whenever the build or stage changes.
 */
public final class TraitEnchants {

    private TraitEnchants() {}

    public static void refresh(ItemStack stack, HolderLookup.Provider registries) {
        HolderLookup.RegistryLookup<Enchantment> lookup =
                registries.lookupOrThrow(Registries.ENCHANTMENT);
        ItemEnchantments.Mutable m = new ItemEnchantments.Mutable(ItemEnchantments.EMPTY);
        grant(m, lookup, Enchantments.FORTUNE, Traits.has(stack, "tectonic.fortune") ? 3 : 0);
        grant(
                m,
                lookup,
                Enchantments.SILK_TOUCH,
                Traits.has(stack, "tectonic.silk_touch") ? 1 : 0);
        grant(m, lookup, Enchantments.LOOTING, 0);
        stack.set(DataComponents.ENCHANTMENTS, m.toImmutable());
    }

    private static void grant(
            ItemEnchantments.Mutable m,
            HolderLookup.RegistryLookup<Enchantment> lookup,
            ResourceKey<Enchantment> key,
            int level) {
        if (level <= 0) return;
        lookup.get(key).ifPresent(h -> m.set((Holder<Enchantment>) h, level));
    }
}
