package com.ethermod.network;

import com.ethermod.event.ModEvents;
import com.ethermod.item.ManipulatorColors;
import com.ethermod.item.OrbMode;
import com.ethermod.item.TierUpgradeModule;
import com.ethermod.manipulator.Manipulator;
import com.ethermod.network.payload.BeamActivePayload;
import com.ethermod.network.payload.ModulePayloads;
import com.ethermod.network.payload.OrbModePayload;
import com.ethermod.network.payload.SummonTogglePayload;
import com.ethermod.network.payload.TraitPayloads;
import com.ethermod.trait.TraitRules;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Server-side handling of every client-to-server payload, one method per payload. Each runs its
 * work through {@code enqueueWork}. Server-only: nothing here may reach client classes.
 */
final class ServerPayloadHandlers {

    private ServerPayloadHandlers() {}

    static void motion(ModulePayloads.Motion p, IPayloadContext ctx) {
        ctx.enqueueWork(
                () -> {
                    if (ctx.player() instanceof ServerPlayer sp)
                        com.ethermod.trait.MovementPhysics.input(sp, p.flags());
                });
    }

    static void beamActive(BeamActivePayload payload, IPayloadContext ctx) {
        ctx.enqueueWork(
                () -> {
                    if (ctx.player() instanceof ServerPlayer sp) {
                        boolean canBeam = Manipulator.isSummoned(sp) && !sp.isSpectator();
                        ModEvents.setBeamActive(sp, payload.active() && canBeam);
                    }
                });
    }

    static void equip(TraitPayloads.Equip p, IPayloadContext ctx) {
        ctx.enqueueWork(
                () -> {
                    if (ctx.player() instanceof ServerPlayer sp)
                        TraitRules.equip(sp, p.slot(), p.line().orElse(null));
                });
    }

    static void pick(TraitPayloads.Pick p, IPayloadContext ctx) {
        ctx.enqueueWork(
                () -> {
                    if (ctx.player() instanceof ServerPlayer sp)
                        TraitRules.pick(sp, p.slot(), p.tier(), p.choice());
                });
    }

    static void stageUpgrade(TraitPayloads.StageUpgrade p, IPayloadContext ctx) {
        ctx.enqueueWork(
                () -> {
                    if (ctx.player() instanceof ServerPlayer sp)
                        TierUpgradeModule.installFromInventory(sp);
                });
    }

    static void paint(TraitPayloads.Paint p, IPayloadContext ctx) {
        ctx.enqueueWork(
                () -> {
                    if (ctx.player() instanceof ServerPlayer sp) paint(sp, p.body(), p.invSlot());
                });
    }

    private static void paint(ServerPlayer sp, boolean body, int invSlot) {
        ItemStack manipulator = Manipulator.stack(sp);
        if (invSlot < 0 || invSlot >= sp.getInventory().items.size()) return;
        ItemStack src = sp.getInventory().items.get(invSlot);
        if (body ? !ManipulatorColors.isBodySource(src) : !ManipulatorColors.isBeamSource(src))
            return;
        int rgb = body ? ManipulatorColors.bodyFrom(src) : ManipulatorColors.beamFrom(src);
        if (!ManipulatorColors.unlock(manipulator, body, rgb))
            return; // already unlocked: nothing used up
        if (body) {
            com.ethermod.advancement.ManipulatorTrigger.fire(sp, "dye");
            if (ManipulatorColors.customUnlocked(manipulator))
                com.ethermod.advancement.ManipulatorTrigger.fire(sp, "all_dyes");
        }
        if (body) ManipulatorColors.setBody(manipulator, rgb);
        else ManipulatorColors.setBeam(manipulator, rgb);
        if (!sp.getAbilities().instabuild) src.shrink(1);
        // UI feedback is local; do not broadcast the old splash sound.
        Manipulator.sync(sp);
    }

    static void pickColour(TraitPayloads.PickColour p, IPayloadContext ctx) {
        ctx.enqueueWork(
                () -> {
                    if (!(ctx.player() instanceof ServerPlayer sp)) return;
                    ItemStack mm = Manipulator.stack(sp);
                    if (p.rgb() < -1 || p.rgb() > 0xFFFFFF) return;
                    if (!ManipulatorColors.isUnlocked(mm, p.body(), p.rgb())
                            && !(p.body() && p.rgb() >= 0 && ManipulatorColors.customUnlocked(mm)))
                        return;
                    if (p.body()) ManipulatorColors.setBody(mm, p.rgb());
                    else ManipulatorColors.setBeam(mm, p.rgb());
                    // The native UI plays local confirmation after synced state
                    // changes.
                    Manipulator.sync(sp);
                });
    }

    static void loadout(TraitPayloads.Loadout p, IPayloadContext ctx) {
        ctx.enqueueWork(
                () -> {
                    if (ctx.player() instanceof ServerPlayer sp)
                        com.ethermod.trait.Loadouts.handle(sp, p.op(), p.index(), p.name());
                });
    }

    static void summonToggle(SummonTogglePayload payload, IPayloadContext ctx) {
        ctx.enqueueWork(
                () -> {
                    if (!(ctx.player() instanceof ServerPlayer sp)) return;
                    // Summoning is never blocked: no cooldown, no state or combat check.
                    if (sp.isUsingItem()) sp.stopUsingItem();
                    if (!payload.summon()) ModEvents.setBeamActive(sp, false);
                    Manipulator.setSummoned(sp, payload.summon());
                });
    }

    static void orbMode(OrbModePayload payload, IPayloadContext ctx) {
        ctx.enqueueWork(
                () -> {
                    if (!(ctx.player() instanceof ServerPlayer sp)) return;
                    ItemStack manipulator = Manipulator.stack(sp);
                    OrbMode mode = OrbMode.byId(payload.mode());
                    if (!mode.isUnlocked(manipulator) || OrbMode.get(manipulator) == mode) return;
                    if (sp.isUsingItem()) sp.releaseUsingItem();
                    OrbMode.set(manipulator, mode);
                    Manipulator.sync(sp);
                });
    }

    static void airJump(ModulePayloads.AirJump p, IPayloadContext ctx) {
        ctx.enqueueWork(
                () -> {
                    if (ctx.player() instanceof ServerPlayer sp)
                        com.ethermod.trait.MobilityEffects.airJump(sp);
                });
    }
}
