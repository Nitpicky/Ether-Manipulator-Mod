package com.ethermod.trait;

/** Canonical shipped choices for migration of builds saved before the two-choice redesign. */
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
            case "combat" -> new String[][] {{"combat.lock_on", "combat.arc"}, {"combat.overload", "combat.disrupt"}, {"combat.dissolve", "combat.annihilate"}};
            case "orb" -> new String[][] {{"orb.split", "orb.bank_shot"}, {"orb.marksman", "orb.skirmisher"}, {"orb.detonator", "orb.seeker"}};
            case "hydraulics" -> new String[][] {{"hydraulics.pressure", "hydraulics.reservoir"}, {"hydraulics.glacier", "hydraulics.rapids"}, {"hydraulics.tidecaller", "hydraulics.diving_bell"}};
            case "pyrotechnics" -> new String[][] {{"pyrotechnics.inferno", "pyrotechnics.crucible"}, {"pyrotechnics.wildfire", "pyrotechnics.flashover"}, {"pyrotechnics.soulwake", "pyrotechnics.rekindle"}};
            case "excavation" -> new String[][] {{"excavation.momentum", "excavation.steady_hand"}, {"excavation.fortune", "excavation.silk_touch"}, {"excavation.vein_miner", "excavation.mass_pickup"}};
            case "pneumatic" -> new String[][] {{"pneumatic.gale_force", "pneumatic.bellows"}, {"pneumatic.bunny_hop", "pneumatic.ground_slam"}, {"pneumatic.stasis", "pneumatic.rebound"}};
            case "cultivation" -> new String[][] {{"cultivation.overgrowth", "cultivation.seedbank"}, {"cultivation.groundskeeper", "cultivation.herder"}, {"cultivation.bountiful", "cultivation.cross_pollination"}};
            case "mechatronic" -> new String[][] {{"mechatronic.piston_arm", "mechatronic.dispenser_arm"}, {"mechatronic.ministrosities", "mechatronic.supercharged"}, {"mechatronic.stride_servos", "mechatronic.rocket_boots"}};
            case "photonic" -> new String[][] {{"photonic.flare", "photonic.radiance"}, {"photonic.daybreak", "photonic.mirage"}, {"photonic.constellation", "photonic.supernova"}};
            case "mobility" -> new String[][] {{"mobility.blink", "mobility.gravity_shift"}, {"mobility.skyhook", "mobility.zipline"}, {"mobility.airwalk", "mobility.flight"}};
            default -> new String[0][];
        };
        if (rows.length == 0) return false;
        for (String candidate : rows[tier]) if (candidate.equals(id)) return true;
        return false;
    }
}
