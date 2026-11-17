package com.ethermod.beam;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.RecipeType;

import java.util.List;

/**
 * Pneumatic: Compressor. While Vacuum runs, nuggets and ingots in the inventory are packed 9 into
 * their block (II: any item with a 3x3 or 2x2 recipe of itself, like snow or clay), using the
 * game's own recipes.
 */
final class Compressor {

    private Compressor() {}

    static void pack(ServerPlayer sp, int rank) {
        Inventory inv = sp.getInventory();
        java.util.Set<Item> seen = new java.util.HashSet<>();
        for (int i = 0; i < inv.items.size(); i++) {
            ItemStack st = inv.items.get(i);
            if (st.isEmpty() || !seen.add(st.getItem())) continue;
            boolean metal =
                    st.is(net.neoforged.neoforge.common.Tags.Items.NUGGETS)
                            || st.is(net.neoforged.neoforge.common.Tags.Items.INGOTS);
            if (!metal && rank < 2) continue;
            int have = inv.countItem(st.getItem());
            for (int side : rank >= 2 ? new int[] {3, 2} : new int[] {3}) {
                int need = side * side;
                if (have < need) continue;
                ItemStack one = st.copyWithCount(1);
                List<ItemStack> grid = new java.util.ArrayList<>();
                for (int k = 0; k < need; k++) grid.add(one.copy());
                CraftingInput input = CraftingInput.of(side, side, grid);
                var recipe =
                        sp.server
                                .getRecipeManager()
                                .getRecipeFor(RecipeType.CRAFTING, input, sp.level());
                if (recipe.isEmpty()) continue;
                ItemStack out = recipe.get().value().assemble(input, sp.level().registryAccess());
                if (out.isEmpty() || out.is(st.getItem())) continue;
                int times = have / need;
                remove(inv, st.getItem(), times * need);
                ItemStack give = out.copyWithCount(out.getCount() * times);
                if (!inv.add(give)) sp.drop(give, false);
                sp.level()
                        .playSound(
                                null,
                                sp.getX(),
                                sp.getY(),
                                sp.getZ(),
                                SoundEvents.PISTON_CONTRACT,
                                SoundSource.PLAYERS,
                                0.3f,
                                1.6f);
                break;
            }
        }
    }

    private static void remove(Inventory inv, Item item, int count) {
        for (int i = 0; i < inv.items.size() && count > 0; i++) {
            ItemStack st = inv.items.get(i);
            if (!st.is(item)) continue;
            int take = Math.min(count, st.getCount());
            st.shrink(take);
            count -= take;
        }
    }
}
