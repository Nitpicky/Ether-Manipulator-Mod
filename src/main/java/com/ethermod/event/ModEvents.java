package com.ethermod.event;

import com.ethermod.beam.BeamCastHelper;
import com.ethermod.beam.BeamState;
import com.ethermod.beam.MiningDamageTracker;
import com.ethermod.item.ManipulatorStage;
import com.ethermod.item.EtherManipulatorItem;
import com.ethermod.manipulator.Manipulator;
import com.ethermod.manipulator.ManipulatorData;
import com.ethermod.network.ModNetwork;
import com.ethermod.network.payload.BeamStatePayload;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.living.LivingSwapItemsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class ModEvents {

    /** UUID → active beam state (mining and combat ramps). */
    private static final Map<UUID, BeamState> beamActivePlayers = new HashMap<>();

    private static final Map<UUID, BeamState> restingBeams = new HashMap<>();
    private static final int BEAM_TICK_INTERVAL = 2;

    public static void register() {
        NeoForge.EVENT_BUS.register(ModEvents.class);
        NeoForge.EVENT_BUS.register(RedesignCommands.class);
        NeoForge.EVENT_BUS.register(com.ethermod.trait.TraitEffects.class);
        NeoForge.EVENT_BUS.register(com.ethermod.trait.RedesignEffects.class);
        NeoForge.EVENT_BUS.register(com.ethermod.trait.MovementPhysics.class);
        NeoForge.EVENT_BUS.register(com.ethermod.trait.GustMotion.class);
        NeoForge.EVENT_BUS.register(com.ethermod.advancement.SecretAdvancements.class);
        NeoForge.EVENT_BUS.register(com.ethermod.beam.PhotonicEffects.class);
        NeoForge.EVENT_BUS.register(com.ethermod.beam.MassDriver.class);
        NeoForge.EVENT_BUS.register(com.ethermod.beam.LeftClick.class);
        NeoForge.EVENT_BUS.register(com.ethermod.beam.GustGrenade.class);
        NeoForge.EVENT_BUS.register(com.ethermod.trait.WorldEffects.class);
        NeoForge.EVENT_BUS.register(com.ethermod.trait.PendingReverts.class);
        NeoForge.EVENT_BUS.register(com.ethermod.trait.RapidsCurrents.class);
        NeoForge.EVENT_BUS.register(com.ethermod.trait.MobilityEffects.class);
    }

    /**
     * LMB down / up. Only Beam mode mines; every other mode has its own left click ({@link
     * com.ethermod.beam.LeftClick}).
     */
    public static void setBeamActive(ServerPlayer player, boolean active) {
        if (!active) com.ethermod.beam.LeftClick.release(player);
        if (active
                && com.ethermod.item.OrbMode.get(Manipulator.stack(player))
                        != com.ethermod.item.OrbMode.ENERGY) {
            com.ethermod.beam.LeftClick.press(player);
            return;
        }
        if (active) com.ethermod.item.ManipulatorColors.setLastOrb(player, false);
        boolean changed;
        if (active) {
            // pressure belongs to the player: a new beam picks up what is left of it (BeamState)
            BeamState state = restingBeams.computeIfAbsent(player.getUUID(), k -> new BeamState());
            changed = beamActivePlayers.putIfAbsent(player.getUUID(), state) == null;
            if (changed) {
                // only the pressure carries over; a new beam starts its own work
                state.ticks = 0;
                state.lastUtilityPos = Long.MIN_VALUE;
                state.resync();
                com.ethermod.advancement.SecretAdvancements.beamStarted(player);
            }
        } else changed = beamActivePlayers.remove(player.getUUID()) != null;
        if (changed) {
            // Let everyone nearby see the beam
            PacketDistributor.sendToPlayersTrackingEntity(
                    player, new BeamStatePayload(player.getId(), active));
        }
    }

    /** True while this player's beam is on. */
    public static boolean beaming(ServerPlayer player) {
        return beamActivePlayers.containsKey(player.getUUID());
    }

    /** Shows (or hides) this player's beam to the players around, without mining (Redstone). */
    public static void showBeam(ServerPlayer player, boolean on) {
        PacketDistributor.sendToPlayersTrackingEntity(
                player, new BeamStatePayload(player.getId(), on));
    }

    @SubscribeEvent
    public static void onLevelTick(net.neoforged.neoforge.event.tick.LevelTickEvent.Post event) {
        if (event.getLevel() instanceof net.minecraft.server.level.ServerLevel sl)
            MiningDamageTracker.tick(sl);
    }

    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer sp)) return;
        if (!(sp.level() instanceof ServerLevel sl)) return;

        BeamState state = beamActivePlayers.get(sp.getUUID());
        if (state == null) return;

        state.ticks++;
        if (state.ticks % BEAM_TICK_INTERVAL != 0) return;

        ItemStack held = sp.getMainHandItem();
        if (!(held.getItem() instanceof EtherManipulatorItem) || sp.isSpectator()) {
            setBeamActive(sp, false);
            return;
        }

        // switched away from Beam mode mid-beam: mining stops
        if (com.ethermod.item.OrbMode.get(Manipulator.stack(sp))
                != com.ethermod.item.OrbMode.ENERGY) {
            setBeamActive(sp, false);
            return;
        }
        BeamCastHelper.tickBeam(sl, sp, ManipulatorStage.get(held), state, BEAM_TICK_INTERVAL);
    }

    // =========================================================================
    // The Manipulator's own slot: give on first join, keep the client in sync
    // =========================================================================

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer sp)) return;
        ManipulatorData data = Manipulator.data(sp);
        // a Module whose definition is gone (e.g. shelved Mechatronic) leaves its slot empty instead of half-loaded
        for (int slot = 0; slot < com.ethermod.trait.TraitBuild.MAX_SLOTS; slot++) {
            var line = com.ethermod.trait.TraitBuild.line(data.stack(), slot);
            if (line != null && com.ethermod.trait.TraitLines.get(sp.registryAccess(), line).isEmpty())
                com.ethermod.trait.TraitBuild.setLine(data.stack(), slot, null);
        }
        com.ethermod.trait.TraitEnchants.refresh(data.stack(), sp.registryAccess());
        if (!data.introduced()) {
            data.setIntroduced(true);
            sp.displayClientMessage(
                    Component.translatable("message.ethermod.intro")
                            .withStyle(ChatFormatting.AQUA)
                            .append(
                                    Component.translatable("message.ethermod.intro_keys")
                                            .withStyle(ChatFormatting.GRAY)),
                    false);
        }
        Manipulator.sync(sp);
        PacketDistributor.sendToPlayer(
                sp,
                new com.ethermod.network.payload.ModulePayloads.Gravity(
                        sp.getId(), com.ethermod.trait.MovementPhysics.down(sp).get3DDataValue()));
    }

    /**
     * A respawn creates a new player on both sides; the data was copied (copyOnDeath), the client
     * needs it again.
     */
    @SubscribeEvent
    public static void onRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (event.getEntity() instanceof ServerPlayer sp) {
            com.ethermod.beam.MassDriver.release(sp, false);
            com.ethermod.trait.MovementPhysics.setGravity(sp, net.minecraft.core.Direction.DOWN);
            Manipulator.sync(sp);
        }
    }

    /** The client rebuilds its local player on a dimension change. */
    @SubscribeEvent
    public static void onChangeDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer sp) {
            com.ethermod.beam.MassDriver.release(sp, false);
            com.ethermod.trait.MovementPhysics.setGravity(sp, net.minecraft.core.Direction.DOWN);
            Manipulator.sync(sp);
        }
    }

    /** Dealing or taking damage puts a player "in combat" for trait swapping. */
    @SubscribeEvent
    public static void onDamage(
            net.neoforged.neoforge.event.entity.living.LivingDamageEvent.Post event) {
        if (event.getEntity() instanceof ServerPlayer victim)
            com.ethermod.trait.TraitRules.markCombat(victim);
        if (event.getSource().getEntity() instanceof ServerPlayer attacker)
            com.ethermod.trait.TraitRules.markCombat(attacker);
    }

    /** F would move the hidden hotbar item, so it does nothing while summoned. */
    @SubscribeEvent
    public static void onSwapHands(LivingSwapItemsEvent.Hands event) {
        if (event.getEntity() instanceof Player p && Manipulator.isSummoned(p))
            event.setCanceled(true);
    }

    /** Stop the beam when a player leaves, so a stale entry can't keep mining on rejoin. */
    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer sp) setBeamActive(sp, false);
        beamActivePlayers.remove(event.getEntity().getUUID());
        restingBeams.remove(event.getEntity().getUUID());
        com.ethermod.trait.TraitRules.forget(event.getEntity().getUUID());
    }

    /** A player who comes into view mid-beam should see it too. */
    @SubscribeEvent
    public static void onStartTracking(PlayerEvent.StartTracking event) {
        if (event.getTarget() instanceof ServerPlayer p
                && event.getEntity() instanceof ServerPlayer watcher)
            PacketDistributor.sendToPlayer(
                    watcher,
                    new com.ethermod.network.payload.ModulePayloads.Gravity(
                            p.getId(),
                            com.ethermod.trait.MovementPhysics.down(p).get3DDataValue()));
        if (event.getTarget() instanceof ServerPlayer target
                && event.getEntity() instanceof ServerPlayer watcher
                && beamActivePlayers.containsKey(target.getUUID())) {
            PacketDistributor.sendToPlayer(watcher, new BeamStatePayload(target.getId(), true));
        }
    }

    /** Static state outlives a singleplayer world; reset it when the server stops. */
    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        beamActivePlayers.clear();
        restingBeams.clear();
        com.ethermod.trait.TraitRules.clear();
        MiningDamageTracker.clear();
    }

    public static void onRegisterPayloads(RegisterPayloadHandlersEvent event) {
        ModNetwork.onRegisterPayloads(event);
    }
}
