package com.ethermod.trait;

import com.ethermod.EtherMod;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

/**
 * Effect-side queries. They read only the stack, so they work anywhere (server, client, render
 * code) without registry access.
 */
public final class Traits {

    private Traits() {}

    public static final ResourceLocation TECTONIC = id("tectonic");
    public static final ResourceLocation PLASMIC = id("plasmic");
    public static final ResourceLocation KINETIC = id("kinetic");
    public static final ResourceLocation HYDRAULIC = id("hydraulic");
    public static final ResourceLocation BOTANIC = id("botanic");
    public static final ResourceLocation GRAVITIC = id("gravitic");
    public static final ResourceLocation PYROTECHNIC = id("pyrotechnic");
    public static final ResourceLocation PNEUMATIC = id("pneumatic");
    public static final ResourceLocation PHOTONIC = id("photonic");

    public static final ResourceLocation MECHATRONIC = id("mechatronic");

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(EtherMod.MODID, path);
    }

    /** The line is equipped in an open slot (its badge is active). */
    public static boolean hasLine(ItemStack stack, ResourceLocation line) {
        return TraitBuild.slotOf(stack, line) >= 0;
    }

    /**
     * A line's common node is active: common 0 opens with Master (Diamond), common 1 with
     * Grandmaster (Netherite).
     */
    public static boolean hasCommon(ItemStack stack, ResourceLocation line, int common) {
        // Iron: badge + Adept; Diamond: common 1 + Master; Netherite: common 2 + Grandmaster
        int slot = TraitBuild.slotOf(stack, line);
        if (slot < 0 || common < 0 || common > 1 || TraitBuild.openTiers(stack) < common + 2)
            return false;
        for (int tier = 0; tier <= common; tier++) {
            if (TraitBuild.pick(stack, slot, tier).isEmpty()) return false;
        }
        return true;
    }

    /** A choice trait ("tectonic.fortune") is picked in an open tier of an equipped line. */
    public static boolean has(ItemStack stack, String traitId) {
        if (traitId.equals("plasmic.sustain")) return hasLine(stack, PLASMIC);
        if (traitId.equals("hydraulic.helmsman")) return hasLine(stack, HYDRAULIC);
        if (traitId.equals("pyrotechnic.kiln")) return hasLine(stack, PYROTECHNIC);
        if (traitId.equals("photonic.prism")) return hasLine(stack, PHOTONIC);
        if (traitId.equals("gravitic.freeflight")) return hasLine(stack, GRAVITIC);
        if (traitId.equals("pyrotechnic.phoenix")) return hasCommon(stack, PYROTECHNIC, 1);
        if (traitId.equals("gravitic.gravity_skate")) return hasCommon(stack, GRAVITIC, 1);
        if (traitId.equals("tectonic.ore_sense")) return has(stack, "tectonic.vein_miner");

        for (int slot = 0; slot < TraitBuild.MAX_SLOTS; slot++) {
            for (int tier = 0; tier < TraitLine.TIERS; tier++) {
                if (traitId.equals(TraitBuild.pick(stack, slot, tier))) return true;
            }
        }
        return false;
    }

    /**
     * How strong a picked choice is: 0 if not picked; otherwise 1, plus one for every stage reached
     * after its tier opened. Calibrated picks reach rank 3 at Overclocked, Augmented picks rank 2,
     * Overclocked picks rank 1.
     */
    public static int rank(ItemStack stack, String traitId) {
        if (!has(stack, traitId)) return 0;
        if (traitId.equals("plasmic.sustain") || traitId.equals("hydraulic.helmsman") || traitId.equals("pyrotechnic.kiln") || traitId.equals("photonic.prism") || traitId.equals("gravitic.freeflight") || traitId.equals("pyrotechnic.phoenix") || traitId.equals("gravitic.gravity_skate") || traitId.equals("tectonic.ore_sense")) return 1;
        for (int slot = 0; slot < TraitBuild.MAX_SLOTS; slot++) {
            for (int tier = 0; tier < TraitLine.TIERS; tier++) {
                if (traitId.equals(TraitBuild.pick(stack, slot, tier)))
                    return rankOfTier(stack, tier);
            }
        }
        return 0;
    }

    /** The rank a pick in this tier has at the stack's stage (see {@link #rank}). */
    public static int rankOfTier(ItemStack stack, int tier) {
        return 1; // Redesign: traits no longer rank up with MM Tier.
    }

    // ---- Derived numbers ----------------------------------------------------------

    /** Beam reach: stage range, +1 with Combat's Focus Lens. */
    public static float beamRange(ItemStack stack) {
        float r = com.ethermod.item.ManipulatorStage.get(stack).beamRange();
        if (hasCommon(stack, PLASMIC, 1)) r += 1;
        return r;
    }

    /** Ticks to full orb charge, -20% with Orb's Quick Charge. */
    public static int chargeTicks(ItemStack stack) {
        int t = com.ethermod.item.ManipulatorStage.get(stack).maxChargeTicks();
        return hasCommon(stack, KINETIC, 0) ? Math.round(t * 0.8f) : t;
    }

    // ---- Badge numbers ----------------------------------------------------------

    /** Mining speed: +2 with the Excavation badge. */
    public static float miningSpeedBonus(ItemStack stack) {
        return hasLine(stack, TECTONIC) ? 2.0f : 0f;
    }

    /**
     * Multiplier on mining speed for this block: Precision speeds up soft blocks (x1.5, x2, then
     * almost instant).
     */
    public static float miningSpeedMultiplier(
            ItemStack stack, net.minecraft.world.level.block.state.BlockState state) {
        int precision = rank(stack, "tectonic.precision");
        if (precision > 0 && isSoft(state))
            return precision >= 3 ? 12f : precision == 2 ? 2f : 1.5f;
        return 1f;
    }

    /** Dirt, sand, gravel, leaves and the like. */
    public static boolean isSoft(net.minecraft.world.level.block.state.BlockState state) {
        return state.is(net.minecraft.tags.BlockTags.MINEABLE_WITH_SHOVEL)
                || state.is(net.minecraft.tags.BlockTags.LEAVES)
                || state.is(net.minecraft.tags.BlockTags.WOOL);
    }

    /**
     * Area Bore's extra blocks around {@code center}, by rank: a 3x3 face, one, two, then three
     * blocks deep. The centre itself is not included.
     */
    public static java.util.List<net.minecraft.core.BlockPos> areaBore(
            ItemStack stack,
            net.minecraft.core.BlockPos center,
            net.minecraft.world.phys.Vec3 look) {
        java.util.List<net.minecraft.core.BlockPos> out = new java.util.ArrayList<>();
        int rank = rank(stack, "tectonic.area_bore");
        if (rank <= 0) return out;
        int r = 1;
        int depth = rank;
        net.minecraft.core.Direction dir =
                net.minecraft.core.Direction.getNearest(look.x, look.y, look.z);
        for (int d = 0; d < depth; d++) {
            for (int u = -r; u <= r; u++)
                for (int v = -r; v <= r; v++) {
                    if (d == 0 && u == 0 && v == 0) continue;
                    net.minecraft.core.BlockPos p =
                            switch (dir.getAxis()) {
                                case X -> center.offset(0, u, v);
                                case Y -> center.offset(u, 0, v);
                                case Z -> center.offset(u, v, 0);
                            };
                    out.add(p.relative(dir, d));
                }
        }
        return out;
    }

    /** Combat badge: +beam damage (base value before the beam's per-hit scaling). */
    public static float beamDamageBonus(ItemStack stack) {
        return hasLine(stack, PLASMIC) ? 1.0f : 0f;
    }

    /** Orb badge: +orb damage at full charge. */
    public static float orbDamageBonus(ItemStack stack) {
        return hasLine(stack, KINETIC) ? 3.0f : 0f;
    }
}
