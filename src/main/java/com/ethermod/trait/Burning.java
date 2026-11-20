package com.ethermod.trait;

import com.ethermod.manipulator.Manipulator;

import net.minecraft.server.level.*;
import net.minecraft.world.entity.*;
import net.minecraft.world.level.block.*;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.living.*;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

import java.util.*;

/**
 * Owned burning and the summons it raises: Pyrotechnic ignition (mm_burn_owner, soul flame,
 * Flashover), Soulwake's Soul Blazelings and the summon rules (expiry, targeting, no friendly
 * fire, no XP or loot). Burning creatures and summons are tracked per level and ticked every 5
 * ticks; nothing is scanned globally.
 */
public final class Burning {
    private Burning() {}

    private static final Map<ServerLevel, Set<UUID>> ACTIVE = new HashMap<>();

    private static void track(LivingEntity e) {
        if (e.level() instanceof ServerLevel sl)
            ACTIVE.computeIfAbsent(sl, k -> new HashSet<>()).add(e.getUUID());
    }

    public static void burn(LivingEntity target, ServerPlayer owner, int seconds, int phase) {
        track(target);
        var st = Manipulator.stack(owner);
        boolean wasBurning = target.isOnFire();
        target.igniteForSeconds(seconds);
        var d = target.getPersistentData();
        d.putUUID("mm_burn_owner", owner.getUUID());
        d.putBoolean("mm_soul", Traits.has(st, "pyrotechnic.soulwake"));
        net.neoforged.neoforge.network.PacketDistributor.sendToPlayersTrackingEntityAndSelf(
                target,
                new com.ethermod.network.payload.ModulePayloads.SoulFlame(
                        target.getId(), d.getBoolean("mm_soul")));
        if (wasBurning
                && Traits.has(st, "pyrotechnic.flashover")
                && target.tickCount - d.getInt("mm_flashover") > 20) {
            d.putInt("mm_flashover", target.tickCount);
            for (var e :
                    owner.serverLevel()
                            .getEntitiesOfClass(
                                    LivingEntity.class,
                                    target.getBoundingBox().inflate(3),
                                    e -> e != target && Targeting.enemy(owner, e))) {
                track(e);
                e.igniteForSeconds(seconds);
                e.getPersistentData().putUUID("mm_burn_owner", owner.getUUID());
                e.getPersistentData()
                        .putBoolean("mm_soul", Traits.has(st, "pyrotechnic.soulwake"));
            }
        }
    }

    public static void soulBurn(LivingEntity target, ServerPlayer owner, int seconds) {
        burn(target, owner, seconds, -1);
        target.getPersistentData().putBoolean("mm_soul", true);
        net.neoforged.neoforge.network.PacketDistributor.sendToPlayersTrackingEntityAndSelf(
                target,
                new com.ethermod.network.payload.ModulePayloads.SoulFlame(target.getId(), true));
    }

    @SubscribeEvent
    public static void hit(LivingDamageEvent.Post ev) {
        if (ev.getSource().getEntity() instanceof ServerPlayer p
                && (ev.getSource().is(net.minecraft.world.damagesource.DamageTypes.PLAYER_ATTACK)
                        || ev.getSource().is(com.ethermod.beam.BeamCastHelper.BEAM_DAMAGE))
                && Traits.hasCommon(Manipulator.stack(p), Traits.PYROTECHNIC, 0))
            burn(ev.getEntity(), p, 4, -1);
    }

    /** A summon never hurts its owner's allies. */
    @SubscribeEvent
    public static void friendlyFire(LivingIncomingDamageEvent ev) {
        if (ev.getSource().getEntity() instanceof Mob summon
                && summon.getPersistentData().hasUUID("mm_summoner")) {
            var owner =
                    summon.level()
                            .getServer()
                            .getPlayerList()
                            .getPlayer(summon.getPersistentData().getUUID("mm_summoner"));
            if (owner != null && Targeting.ally(owner, ev.getEntity())) ev.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void loaded(net.neoforged.neoforge.event.entity.EntityJoinLevelEvent ev) {
        if (ev.getEntity() instanceof LivingEntity e
                && (e.getPersistentData().hasUUID("mm_burn_owner")
                        || e.getPersistentData().hasUUID("mm_summoner"))) track(e);
    }

    @SubscribeEvent
    public static void world(LevelTickEvent.Post ev) {
        if (!(ev.getLevel() instanceof ServerLevel sl)) return;
        long now = sl.getGameTime();
        if (now % 5 != 0) return;
        // Tick only active entities in loaded chunks; no global block scans.
        var active = ACTIVE.computeIfAbsent(sl, k -> new HashSet<>());
        for (var uuid : new ArrayList<>(active)) {
            var entity = sl.getEntity(uuid);
            if (!(entity instanceof LivingEntity e) || !e.isAlive()) {
                active.remove(uuid);
                continue;
            }
            if (!e.isOnFire() && !e.getPersistentData().hasUUID("mm_summoner")) {
                active.remove(uuid);
                net.neoforged.neoforge.network.PacketDistributor.sendToPlayersTrackingEntityAndSelf(
                        e,
                        new com.ethermod.network.payload.ModulePayloads.SoulFlame(
                                e.getId(), false));
                e.getPersistentData().remove("mm_soul");
                e.getPersistentData().remove("mm_burn_owner");
                continue;
            }
            var d = e.getPersistentData();
            if (now % 20 == 0 && e.isOnFire())
                net.neoforged.neoforge.network.PacketDistributor.sendToPlayersTrackingEntityAndSelf(
                        e,
                        new com.ethermod.network.payload.ModulePayloads.SoulFlame(
                                e.getId(), d.getBoolean("mm_soul")));
            if (d.hasUUID("mm_summoner")) {
                if (now >= d.getLong("mm_summon_expires")) {
                    e.hurt(sl.damageSources().genericKill(), Float.MAX_VALUE);
                    continue;
                }
                var owner = sl.getServer().getPlayerList().getPlayer(d.getUUID("mm_summoner"));
                if (e instanceof Mob mob
                        && !(e instanceof com.ethermod.entity.SoulBlazeling)
                        && owner != null) {
                    var target = owner.getLastHurtMob();
                    if (target != null && target.isAlive() && Targeting.enemy(owner, target))
                        mob.setTarget(target);
                    else if (mob.getTarget() == owner) mob.setTarget(null);
                }
            }
        }
    }

    @SubscribeEvent
    public static void noSummonXp(LivingExperienceDropEvent ev) {
        if (ev.getEntity().getPersistentData().hasUUID("mm_summoner")) ev.setDroppedExperience(0);
    }

    @SubscribeEvent
    public static void loot(LivingDropsEvent ev) {
        if (ev.getEntity().getPersistentData().hasUUID("mm_summoner")) ev.getDrops().clear();
    }

    @SubscribeEvent
    public static void death(LivingDeathEvent ev) {
        var victim = ev.getEntity();
        var d = victim.getPersistentData();
        if (!(victim.level() instanceof ServerLevel sl)
                || !victim.isOnFire()
                || !d.getBoolean("mm_soul")
                || !d.hasUUID("mm_burn_owner")
                || d.hasUUID("mm_summoner")) return;
        var owner = sl.getServer().getPlayerList().getPlayer(d.getUUID("mm_burn_owner"));
        if (owner == null
                || !com.ethermod.config.ServerConfig.blazelings()
                || !Traits.has(Manipulator.stack(owner), "pyrotechnic.soulwake")
                || victim == owner
                || Targeting.ally(owner, victim)) return;
        if (sl.getEntitiesOfClass(
                                Mob.class,
                                owner.getBoundingBox().inflate(64),
                                s ->
                                        s.getPersistentData().hasUUID("mm_summoner")
                                                && s.getPersistentData()
                                                        .getUUID("mm_summoner")
                                                        .equals(owner.getUUID()))
                        .size()
                >= com.ethermod.config.ServerConfig.maxSummons()) return;
        var pet = com.ethermod.registry.ModEntities.SOUL_BLAZELING.get().create(sl);
        if (pet == null) return;
        pet.setPos(victim.position());
        pet.configure(owner);
        if (owner.getTeam() != null)
            sl.getScoreboard().addPlayerToTeam(pet.getScoreboardName(), owner.getTeam());
        sl.addFreshEntity(pet);
        track(pet);
        var pos = victim.blockPosition();
        if (com.ethermod.config.ServerConfig.fireAndLava() && com.ethermod.util.Protect.may(sl, owner, pos) && sl.getBlockState(pos).isAir())
            WorldEffects.tempBlock(sl, pos, Blocks.SOUL_FIRE.defaultBlockState(), 100);
    }

    @SubscribeEvent
    public static void stop(net.neoforged.neoforge.event.server.ServerStoppingEvent ev) {
        ACTIVE.clear();
    }
}
