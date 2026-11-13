package com.mattermod;

import com.mattermod.event.ModEvents;
import com.mattermod.registry.ModAttachments;
import com.mattermod.registry.ModCreativeTabs;
import com.mattermod.registry.ModEntities;
import com.mattermod.registry.ModItems;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@Mod(MatterMod.MODID)
public class MatterMod {

    public static final String MODID = "mattermod";
    public static final Logger LOGGER = LogManager.getLogger(MODID);

    public MatterMod(IEventBus modEventBus, ModContainer container) {
        container.registerConfig(ModConfig.Type.COMMON, com.mattermod.config.MatterConfig.SPEC);

        // Register deferred items, entities, and creative tab
        ModItems.register(modEventBus);
        ModEntities.register(modEventBus);
        com.mattermod.loot.ModLoot.register(modEventBus);
        com.mattermod.registry.ModParticles.register(modEventBus);
        ModCreativeTabs.register(modEventBus);
        ModAttachments.register(modEventBus);

        // Register network payloads via mod event bus
        modEventBus.addListener(ModEvents::onRegisterPayloads);
        modEventBus.addListener(com.mattermod.trait.TraitLines::onNewRegistry);

        // Register server-side game events (NeoForge bus)
        ModEvents.register();

        LOGGER.info("Matter Manipulator initialised.");
    }
}
