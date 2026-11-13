package com.mattermod.registry;

import com.mattermod.MatterMod;

import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModCreativeTabs {

    private static final DeferredRegister<CreativeModeTab> TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, MatterMod.MODID);

    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> MATTER_TAB =
            TABS.register(
                    "matter_tab",
                    () ->
                            CreativeModeTab.builder()
                                    .title(Component.translatable("itemGroup.mattermod.tab"))
                                    .icon(() -> new ItemStack(ModItems.MATTER_MANIPULATOR.get()))
                                    .displayItems(
                                            (params, output) -> {
                                                output.accept(ModItems.UPGRADE_IRON.get());
                                                output.accept(ModItems.UPGRADE_DIAMOND.get());
                                                output.accept(ModItems.UPGRADE_NETHERITE.get());
                                                ModItems.GEMS
                                                        .values()
                                                        .forEach(item -> output.accept(item.get()));
                                                ModItems.MODULES
                                                        .values()
                                                        .forEach(item -> output.accept(item.get()));
                                            })
                                    .build());

    public static void register(IEventBus bus) {
        TABS.register(bus);
    }
}
