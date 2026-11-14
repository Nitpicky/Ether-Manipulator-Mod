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
                    () -> new TierUpgradeModule(ManipulatorStage.STONE, "Calibrated Upgrade"));

    /** Upgrades IRON → DIAMOND */
    public static final DeferredItem<TierUpgradeModule> UPGRADE_DIAMOND =
            ITEMS.register(
                    "upgrade_diamond",
                    () -> new TierUpgradeModule(ManipulatorStage.IRON, "Augmented Upgrade"));

    /** Upgrades DIAMOND → NETHERITE (smithing table) */
    public static final DeferredItem<TierUpgradeModule> UPGRADE_NETHERITE =
            ITEMS.register(
                    "upgrade_netherite",
                    () -> new TierUpgradeModule(ManipulatorStage.DIAMOND, "Overclocked Upgrade"));

    /** Module id -> its Enchanted Gemstone. Gems are found in Overworld structure chests (loot modifiers in
     *  data/mattermod/loot_modifiers); using one unlocks its Module directly. */
    public static final java.util.Map<String, String> GEM_OF_MODULE = new java.util.LinkedHashMap<>();

    static {
        String[][] pairs = {
            {"hydraulics", "sirenite"}, {"pyrotechnics", "gehennite"}, {"excavation", "stratite"},
            {"pneumatic", "zephyrite"}, {"cultivation", "quetzalite"}, {"mechatronic", "tinkerite"},
            {"photonic", "atenite"}, {"mobility", "oculite"}
        };
        for (String[] p : pairs) GEM_OF_MODULE.put(p[0], p[1]);
    }

    /** Gem id -> item. Each gem is the Module unlock itself. */
    public static final java.util.Map<String, DeferredItem<com.mattermod.item.ModuleItem>> GEMS =
            new java.util.LinkedHashMap<>();

    static {
        GEM_OF_MODULE.forEach(
                (module, gem) ->
                        GEMS.put(gem, ITEMS.register(gem, () -> new com.mattermod.item.ModuleItem(module))));
    }

    public static void register(IEventBus bus) {
        ITEMS.register(bus);
    }
}
