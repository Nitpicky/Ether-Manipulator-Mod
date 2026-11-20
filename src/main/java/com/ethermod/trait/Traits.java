package com.ethermod.trait;

import com.ethermod.EtherMod;

import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

/**
 * Effect-side queries. They read only the stack, so they work anywhere (server, client, render
 * code) without registry access.
 */
public final class Traits {

    private Traits() {}

    public static final Identifier TECTONIC = id("tectonic");
    public static final Identifier PLASMIC = id("plasmic");
    public static final Identifier KINETIC = id("kinetic");
    public static final Identifier HYDRAULIC = id("hydraulic");
    public static final Identifier BOTANIC = id("botanic");
    public static final Identifier GRAVITIC = id("gravitic");
    public static final Identifier PYROTECHNIC = id("pyrotechnic");
    public static final Identifier PNEUMATIC = id("pneumatic");
    public static final Identifier PHOTONIC = id("photonic");

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(EtherMod.MODID, path);
    }

    /** The line is equipped in an open slot (its badge is active). */
    public static boolean hasLine(ItemStack stack, Identifier line) {
        return TraitBuild.slotOf(stack, line) >= 0;
    }

    /**
     * A line's common node is active: common 0 opens with Master (Diamond), common 1 with
     * Grandmaster (Netherite).
     */
    public static boolean hasCommon(ItemStack stack, Identifier line, int common) {
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
     * How strong a picked choice is: 0 if not picked, otherwise 1. Ranks no longer grow with the
     * stage, so this is {@code has(...) ? 1 : 0}.
     */
    public static int rank(ItemStack stack, String traitId) {
        return has(stack, traitId) ? 1 : 0;
    }

    // ---- Derived numbers ----------------------------------------------------------

    /** Beam reach: stage range, +1 with Combat's Focus Lens. */
    public static float beamRange(ItemStack stack) {
        float r = com.ethermod.item.ManipulatorStage.get(stack).beamRange();
        if (hasCommon(stack, PLASMIC, 1)) r += 1;
        return r * com.ethermod.config.ServerConfig.rangeMultiplier();
    }

    /** Ticks to full orb charge, -20% with Orb's Quick Charge. */
    public static int chargeTicks(ItemStack stack) {
        int t = com.ethermod.item.ManipulatorStage.get(stack).maxChargeTicks();
        t = Math.max(1, Math.round(t * com.ethermod.config.ServerConfig.chargeTime()));
        return hasCommon(stack, KINETIC, 0) ? Math.max(1, Math.round(t * 0.8f)) : t;
    }

    // ---- Badge numbers ----------------------------------------------------------

    /** Mining speed: +2 with the Excavation badge. */
    public static float miningSpeedBonus(ItemStack stack) {
        return hasLine(stack, TECTONIC) ? 2.0f : 0f;
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
