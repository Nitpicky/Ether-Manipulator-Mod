package com.mattermod.registry;

import com.mattermod.MatterMod;
import com.mattermod.item.ManipulatorStage;
import com.mattermod.item.MatterManipulatorItem;
import com.mattermod.item.TierUpgradeModule;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModItems {

    private static final DeferredRegister.Items ITEMS =
            DeferredRegister.createItems(MatterMod.MODID);

    // ---- Core tool ----------------------------------------------------------
    // Never crafted or held in the inventory: each player owns exactly one,
    // stored in its own slot (ModAttachments.MANIPULATOR).
    public static final DeferredItem<MatterManipulatorItem> MATTER_MANIPULATOR =
            ITEMS.register("matter_manipulator", MatterManipulatorItem::new);

    // ---- Tier upgrade modules -----------------------------------------------
    // Each module upgrades the Manipulator from its required stage to the next.

    /** Upgrades STONE → IRON */
    public static final DeferredItem<TierUpgradeModule> UPGRADE_IRON =
            ITEMS.register(
                    "upgrade_iron",
                    () -> new TierUpgradeModule(ManipulatorStage.STONE, "Calibrated Chip"));

    /** Upgrades IRON → DIAMOND */
    public static final DeferredItem<TierUpgradeModule> UPGRADE_DIAMOND =
            ITEMS.register(
                    "upgrade_diamond",
                    () -> new TierUpgradeModule(ManipulatorStage.IRON, "Augmented Chip"));

    /** Upgrades DIAMOND → NETHERITE (smithing table) */
    public static final DeferredItem<TierUpgradeModule> UPGRADE_NETHERITE =
            ITEMS.register(
                    "upgrade_netherite",
                    () -> new TierUpgradeModule(ManipulatorStage.DIAMOND, "Overclocked Chip"));

    public static final java.util.Map<String, DeferredItem<com.mattermod.item.ModuleItem>> MODULES =
            new java.util.LinkedHashMap<>();

    static {
        for (String id :
                new String[] {
                    "hydraulics",
                    "pyrotechnics",
                    "excavation",
                    "pneumatic",
                    "cultivation",
                    "mechatronic",
                    "photonic",
                    "mobility"
                })
            MODULES.put(
                    id,
                    ITEMS.register("module_" + id, () -> new com.mattermod.item.ModuleItem(id)));
    }

    /**
     * Enchanted Gemstones: found in Overworld structure chests (loot modifiers in
     * data/mattermod/loot_modifiers), set in gold to craft their Module. Module id -> gem.
     */
    public static final java.util.Map<String, String> GEM_OF_MODULE =
            java.util.Map.of(
                    "hydraulics", "sirenite",
                    "pyrotechnics", "gehennite",
                    "excavation", "stratite",
                    "pneumatic", "zephyrite",
                    "cultivation", "quetzalite",
                    "mechatronic", "tinkerite",
                    "photonic", "atenite",
                    "mobility", "oculite");

    public static final java.util.Map<String, DeferredItem<net.minecraft.world.item.Item>> GEMS =
            new java.util.LinkedHashMap<>();

    static {
        for (String module : MODULES.keySet()) {
            String gem = GEM_OF_MODULE.get(module);
            GEMS.put(
                    gem,
                    ITEMS.registerSimpleItem(
                            gem,
                            new net.minecraft.world.item.Item.Properties()
                                    .rarity(net.minecraft.world.item.Rarity.UNCOMMON)));
        }
    }

    public static void register(IEventBus bus) {
        ITEMS.register(bus);
    }
}
