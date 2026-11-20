package com.ethermod.registry;

import com.ethermod.EtherMod;
import com.ethermod.item.ManipulatorStage;
import com.ethermod.item.EtherManipulatorItem;
import com.ethermod.item.TierUpgradeModule;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModItems {

    private static final DeferredRegister.Items ITEMS =
            DeferredRegister.createItems(EtherMod.MODID);

    // ---- Core tool ----------------------------------------------------------
    // Never crafted or held in the inventory: each player owns exactly one,
    // stored in its own slot (ModAttachments.MANIPULATOR).
    public static final DeferredItem<EtherManipulatorItem> ETHER_MANIPULATOR =
            ITEMS.registerItem("ether_manipulator", EtherManipulatorItem::new);

    // ---- Tier upgrade modules -----------------------------------------------
    // Each module upgrades the Manipulator from its required stage to the next.

    /** Upgrades STONE → IRON */
    public static final DeferredItem<TierUpgradeModule> UPGRADE_IRON =
            ITEMS.registerItem(
                    "upgrade_iron",
                    p -> new TierUpgradeModule(p, ManipulatorStage.STONE));

    /** Upgrades IRON → DIAMOND */
    public static final DeferredItem<TierUpgradeModule> UPGRADE_DIAMOND =
            ITEMS.registerItem(
                    "upgrade_diamond",
                    p -> new TierUpgradeModule(p, ManipulatorStage.IRON));

    /** Upgrades DIAMOND → NETHERITE (smithing table) */
    public static final DeferredItem<TierUpgradeModule> UPGRADE_NETHERITE =
            ITEMS.registerItem(
                    "upgrade_netherite",
                    p -> new TierUpgradeModule(p, ManipulatorStage.DIAMOND));

    /** Module id -> its Enchanted Gemstone. Gems are found in Overworld structure chests (loot modifiers in
     *  data/ethermod/loot_modifiers); using one unlocks its Module directly. */
    public static final java.util.Map<String, String> GEM_OF_MODULE = new java.util.LinkedHashMap<>();

    static {
        String[][] pairs = {
            {"hydraulic", "sirenite"}, {"pyrotechnic", "gehennite"}, {"tectonic", "stratite"},
            {"pneumatic", "zephyrite"}, {"botanic", "quetzalite"},
            {"photonic", "atenite"}, {"gravitic", "oculite"}
        };
        for (String[] p : pairs) GEM_OF_MODULE.put(p[0], p[1]);
    }

    /** Gem id -> item. Each gem is the Module unlock itself. */
    public static final java.util.Map<String, DeferredItem<com.ethermod.item.ModuleItem>> GEMS =
            new java.util.LinkedHashMap<>();

    static {
        GEM_OF_MODULE.forEach(
                (module, gem) ->
                        GEMS.put(gem, ITEMS.registerItem(gem, p -> new com.ethermod.item.ModuleItem(p, module))));
    }

    /** Icon-only items for the secret Module advancements (the K screen's Badge sprites); not in any tab. */
    static {
        for (String m : new String[] {"tectonic", "hydraulic", "pyrotechnic", "pneumatic", "photonic", "gravitic",
                "botanic", "plasmic", "kinetic"})
            ITEMS.registerSimpleItem("module_icon_" + m);
    }

    public static void register(IEventBus bus) {
        ITEMS.register(bus);
    }
}
