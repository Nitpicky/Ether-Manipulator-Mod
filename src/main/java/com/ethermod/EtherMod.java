package com.ethermod;

import com.ethermod.event.ModEvents;
import com.ethermod.registry.ModAttachments;
import com.ethermod.registry.ModCreativeTabs;
import com.ethermod.registry.ModEntities;
import com.ethermod.registry.ModItems;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@Mod(EtherMod.MODID)
public class EtherMod {

    public static final String MODID = "ethermod";
    public static final Logger LOGGER = LogManager.getLogger(MODID);

    public EtherMod(IEventBus modEventBus, ModContainer container) {
        container.registerConfig(ModConfig.Type.COMMON, com.ethermod.config.EtherConfig.SPEC);

        // Register deferred items, entities, and creative tab
        ModItems.register(modEventBus);
        ModEntities.register(modEventBus);
        com.ethermod.loot.ModLoot.register(modEventBus);
        com.ethermod.registry.ModParticles.register(modEventBus);
        com.ethermod.registry.ModSounds.register(modEventBus);
        ModCreativeTabs.register(modEventBus);
        com.ethermod.advancement.ManipulatorTrigger.register(modEventBus);
        ModAttachments.register(modEventBus);

        // Register network payloads via mod event bus
        modEventBus.addListener(ModEvents::onRegisterPayloads);
        modEventBus.addListener(com.ethermod.trait.TraitLines::onNewRegistry);

        // Register server-side game events (NeoForge bus)
        ModEvents.register();

        LOGGER.info("Ether Manipulator initialised.");
    }
}
