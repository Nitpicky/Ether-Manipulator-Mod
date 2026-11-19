package com.ethermod.trait;

/** The two choices offered at each tier of every Module; a saved pick outside them resolves to "". */
public final class TraitChoices {
    private TraitChoices() {}
    public static String resolve(String module, java.util.List<String> saved, int tier) {
        if (tier < 0 || tier >= 3) return "";
        if (tier > 0 && resolve(module, saved, tier - 1).isEmpty()) return "";
        if (tier < saved.size() && accepts(module, tier, saved.get(tier))) return saved.get(tier);
        for (String id : saved) if (accepts(module, tier, id)) return id;
        return "";
    }

    public static boolean accepts(String module, int tier, String id) {
        if (tier < 0 || tier >= 3 || id.isEmpty()) return false;
        String[][] rows = switch (module) {
            case "plasmic" -> new String[][] {{"plasmic.lock_on", "plasmic.arc"}, {"plasmic.overload", "plasmic.disrupt"}, {"plasmic.dissolve", "plasmic.annihilate"}};
            case "kinetic" -> new String[][] {{"kinetic.split", "kinetic.bank_shot"}, {"kinetic.marksman", "kinetic.skirmisher"}, {"kinetic.detonator", "kinetic.seeker"}};
            case "hydraulic" -> new String[][] {{"hydraulic.pressure", "hydraulic.reservoir"}, {"hydraulic.glacier", "hydraulic.rapids"}, {"hydraulic.tidecaller", "hydraulic.diving_bell"}};
            case "pyrotechnic" -> new String[][] {{"pyrotechnic.inferno", "pyrotechnic.crucible"}, {"pyrotechnic.wildfire", "pyrotechnic.flashover"}, {"pyrotechnic.soulwake", "pyrotechnic.rekindle"}};
            case "tectonic" -> new String[][] {{"tectonic.momentum", "tectonic.steady_hand"}, {"tectonic.fortune", "tectonic.silk_touch"}, {"tectonic.vein_miner", "tectonic.mass_pickup"}};
            case "pneumatic" -> new String[][] {{"pneumatic.gale_force", "pneumatic.bellows"}, {"pneumatic.bunny_hop", "pneumatic.ground_slam"}, {"pneumatic.cyclone", "pneumatic.rebound"}};
            case "botanic" -> new String[][] {{"botanic.overgrowth", "botanic.seedbank"}, {"botanic.groundskeeper", "botanic.herder"}, {"botanic.bountiful", "botanic.cross_pollination"}};
            case "photonic" -> new String[][] {{"photonic.flare", "photonic.radiance"}, {"photonic.daybreak", "photonic.mirage"}, {"photonic.constellation", "photonic.supernova"}};
            case "gravitic" -> new String[][] {{"gravitic.blink", "gravitic.gravity_shift"}, {"gravitic.skyhook", "gravitic.zipline"}, {"gravitic.airwalk", "gravitic.flight"}};
            default -> new String[0][];
        };
        if (rows.length == 0) return false;
        for (String candidate : rows[tier]) if (candidate.equals(id)) return true;
        return false;
    }
}
