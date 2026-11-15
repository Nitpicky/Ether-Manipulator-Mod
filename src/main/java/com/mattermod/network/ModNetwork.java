package com.mattermod.network;

import com.mattermod.beam.RemoteBeams;
import com.mattermod.event.ModEvents;
import com.mattermod.item.OrbMode;
import com.mattermod.item.TierUpgradeModule;
import com.mattermod.manipulator.Manipulator;
import com.mattermod.manipulator.ManipulatorData;
import com.mattermod.network.payload.BeamActivePayload;
import com.mattermod.network.payload.BeamStatePayload;
import com.mattermod.network.payload.ManipulatorSyncPayload;
import com.mattermod.network.payload.OrbModePayload;
import com.mattermod.network.payload.SummonTogglePayload;
import com.mattermod.network.payload.TraitPayloads;
import com.mattermod.trait.TraitRules;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

public final class ModNetwork {

    private static final String VERSION = "17";

    private ModNetwork() {}

    public static void onRegisterPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar reg = event.registrar(VERSION);
        reg.playToServer(
                com.mattermod.network.payload.ModulePayloads.Motion.TYPE,
                com.mattermod.network.payload.ModulePayloads.Motion.CODEC,
                (p, ctx) ->
                        ctx.enqueueWork(
                                () -> {
                                    if (ctx.player() instanceof ServerPlayer sp)
                                        com.mattermod.trait.MovementPhysics.input(
                                                sp, p.flags() & 127);
                                }));
        reg.playToClient(
                com.mattermod.network.payload.ModulePayloads.WaterCurrent.TYPE,
                com.mattermod.network.payload.ModulePayloads.WaterCurrent.CODEC,
                (p, ctx) ->
                        ctx.enqueueWork(
                                () -> {
                                    com.mattermod.client.ClientPayloadHandlers.waterCurrent(p);
                                }));
        reg.playToClient(
                com.mattermod.network.payload.ModulePayloads.SoulFlame.TYPE,
                com.mattermod.network.payload.ModulePayloads.SoulFlame.CODEC,
                (p, ctx) ->
                        ctx.enqueueWork(
                                () -> {
                                    com.mattermod.client.ClientPayloadHandlers.soulFlame(p);
                                }));
        reg.playToClient(
                com.mattermod.network.payload.ModulePayloads.Gravity.TYPE,
                com.mattermod.network.payload.ModulePayloads.Gravity.CODEC,
                (p, ctx) ->
                        ctx.enqueueWork(
                                () ->
                                        com.mattermod.client.ClientMobility.gravity(
                                                p.entity(), p.down(), p.start())));
        reg.playToServer(
                BeamActivePayload.TYPE, BeamActivePayload.CODEC, ModNetwork::handleBeamActive);
        reg.playToServer(
                TraitPayloads.Equip.TYPE,
                TraitPayloads.Equip.CODEC,
                (p, ctx) ->
                        ctx.enqueueWork(
                                () -> {
                                    if (ctx.player() instanceof ServerPlayer sp)
                                        TraitRules.equip(sp, p.slot(), p.line().orElse(null));
                                }));
        reg.playToServer(
                TraitPayloads.Pick.TYPE,
                TraitPayloads.Pick.CODEC,
                (p, ctx) ->
                        ctx.enqueueWork(
                                () -> {
                                    if (ctx.player() instanceof ServerPlayer sp)
                                        TraitRules.pick(sp, p.slot(), p.tier(), p.choice());
                                }));
        reg.playToServer(
                TraitPayloads.StageUpgrade.TYPE,
                TraitPayloads.StageUpgrade.CODEC,
                (p, ctx) ->
                        ctx.enqueueWork(
                                () -> {
                                    if (ctx.player() instanceof ServerPlayer sp)
                                        TierUpgradeModule.installFromInventory(sp);
                                }));
        reg.playToServer(
                TraitPayloads.Paint.TYPE,
                TraitPayloads.Paint.CODEC,
                (p, ctx) ->
                        ctx.enqueueWork(
                                () -> {
                                    if (ctx.player() instanceof ServerPlayer sp)
                                        paint(sp, p.body(), p.invSlot());
                                }));
        reg.playToServer(
                TraitPayloads.PickColour.TYPE,
                TraitPayloads.PickColour.CODEC,
                (p, ctx) ->
                        ctx.enqueueWork(
                                () -> {
                                    if (!(ctx.player() instanceof ServerPlayer sp)) return;
                                    ItemStack mm = Manipulator.stack(sp);
                                    if (p.rgb() < -1 || p.rgb() > 0xFFFFFF) return;
                                    if (!com.mattermod.item.ManipulatorColors.isUnlocked(
                                                    mm, p.body(), p.rgb())
                                            && !(p.body()
                                                    && p.rgb() >= 0
                                                    && com.mattermod.item.ManipulatorColors
                                                            .customUnlocked(mm))) return;
                                    if (p.body())
                                        com.mattermod.item.ManipulatorColors.setBody(mm, p.rgb());
                                    else com.mattermod.item.ManipulatorColors.setBeam(mm, p.rgb());
                                    // The native UI plays local confirmation after synced state
                                    // changes.
                                    Manipulator.sync(sp);
                                }));
        reg.playToServer(
                TraitPayloads.Loadout.TYPE,
                TraitPayloads.Loadout.CODEC,
                (p, ctx) ->
                        ctx.enqueueWork(
                                () -> {
                                    if (ctx.player() instanceof ServerPlayer sp)
                                        com.mattermod.trait.Loadouts.handle(
                                                sp, p.op(), p.index(), p.name());
                                }));
        reg.playToServer(
                SummonTogglePayload.TYPE,
                SummonTogglePayload.CODEC,
                ModNetwork::handleSummonToggle);
        reg.playToServer(OrbModePayload.TYPE, OrbModePayload.CODEC, ModNetwork::handleOrbMode);
        reg.playToClient(
                BeamStatePayload.TYPE,
                BeamStatePayload.CODEC,
                (payload, ctx) ->
                        ctx.enqueueWork(
                                () -> RemoteBeams.set(payload.entityId(), payload.active())));
        reg.playToClient(
                com.mattermod.network.payload.LeftClickFxPayload.TYPE,
                com.mattermod.network.payload.LeftClickFxPayload.CODEC,
                (payload, ctx) ->
                        ctx.enqueueWork(
                                () -> com.mattermod.beam.LeftClickFxData.accept(payload)));
        reg.playToClient(
                ManipulatorSyncPayload.TYPE,
                ManipulatorSyncPayload.CODEC,
                ModNetwork::handleManipulatorSync);
        reg.playToServer(
                com.mattermod.network.payload.ModulePayloads.AirJump.TYPE,
                com.mattermod.network.payload.ModulePayloads.AirJump.CODEC,
                (p, ctx) ->
                        ctx.enqueueWork(
                                () -> {
                                    if (ctx.player() instanceof ServerPlayer sp)
                                        com.mattermod.trait.MobilityEffects.airJump(sp);
                                }));
        reg.playToClient(
                com.mattermod.network.payload.GolemFxPayload.TYPE,
                com.mattermod.network.payload.GolemFxPayload.CODEC,
                (p, ctx) -> ctx.enqueueWork(() -> com.mattermod.client.GolemFx.accept(p)));
        reg.playToClient(
                com.mattermod.network.payload.ModulePayloads.LodestarMark.TYPE,
                com.mattermod.network.payload.ModulePayloads.LodestarMark.CODEC,
                (p, ctx) -> ctx.enqueueWork(() -> com.mattermod.trait.Lodestar.clientMark = p));
    }

    private static void paint(ServerPlayer sp, boolean body, int invSlot) {
        ItemStack manipulator = Manipulator.stack(sp);
        if (invSlot < 0 || invSlot >= sp.getInventory().items.size()) return;
        ItemStack src = sp.getInventory().items.get(invSlot);
        if (body
                ? !com.mattermod.item.ManipulatorColors.isBodySource(src)
                : !com.mattermod.item.ManipulatorColors.isBeamSource(src)) return;
        int rgb =
                body
                        ? com.mattermod.item.ManipulatorColors.bodyFrom(src)
                        : com.mattermod.item.ManipulatorColors.beamFrom(src);
        if (!com.mattermod.item.ManipulatorColors.unlock(manipulator, body, rgb))
            return; // already unlocked: nothing used up
        if (body) {
            com.mattermod.advancement.ManipulatorTrigger.fire(sp, "dye");
            if (com.mattermod.item.ManipulatorColors.customUnlocked(manipulator))
                com.mattermod.advancement.ManipulatorTrigger.fire(sp, "all_dyes");
        }
        if (body) com.mattermod.item.ManipulatorColors.setBody(manipulator, rgb);
        else com.mattermod.item.ManipulatorColors.setBeam(manipulator, rgb);
        if (!sp.getAbilities().instabuild) src.shrink(1);
        // UI feedback is local; do not broadcast the old splash sound.
        Manipulator.sync(sp);
    }

    private static void handleSummonToggle(SummonTogglePayload payload, IPayloadContext ctx) {
        ctx.enqueueWork(
                () -> {
                    if (!(ctx.player() instanceof ServerPlayer sp)) return;
                    // Summoning is never blocked: no cooldown, no state or combat check.
                    if (sp.isUsingItem()) sp.stopUsingItem();
                    if (!payload.summon()) ModEvents.setBeamActive(sp, false);
                    Manipulator.setSummoned(sp, payload.summon());
                });
    }

    private static void handleOrbMode(OrbModePayload payload, IPayloadContext ctx) {
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

    public static void sendOrbMode(OrbMode mode) {
        PacketDistributor.sendToServer(new OrbModePayload(mode.ordinal()));
    }

    /** Client side. Only touches common classes, so it is safe to register on both sides. */
    private static void handleManipulatorSync(ManipulatorSyncPayload payload, IPayloadContext ctx) {
        ctx.enqueueWork(
                () -> {
                    Player player = ctx.player();
                    ManipulatorData data = Manipulator.data(player);
                    data.setStack(payload.stack());
                    data.setSummoned(payload.summoned());
                });
    }

    private static void handleBeamActive(BeamActivePayload payload, IPayloadContext ctx) {
        ctx.enqueueWork(
                () -> {
                    if (ctx.player() instanceof ServerPlayer sp) {
                        boolean canBeam = Manipulator.isSummoned(sp) && !sp.isSpectator();
                        ModEvents.setBeamActive(sp, payload.active() && canBeam);
                    }
                });
    }

    public static void send(
            net.minecraft.network.protocol.common.custom.CustomPacketPayload payload) {
        PacketDistributor.sendToServer(payload);
    }

    public static void sendSummonToggle(boolean summon) {
        PacketDistributor.sendToServer(new SummonTogglePayload(summon));
    }

    public static void sendBeamActive(boolean active) {
        PacketDistributor.sendToServer(new BeamActivePayload(active));
    }
}
