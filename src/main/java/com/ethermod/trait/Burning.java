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
        var d = com.ethermod.util.MobMemory.of(target);
        d.store("mm_burn_owner", net.minecraft.core.UUIDUtil.CODEC, owner.getUUID());
        d.putBoolean("mm_soul", Traits.has(st, "pyrotechnic.soulwake"));
        net.neoforged.neoforge.network.PacketDistributor.sendToPlayersTrackingEntityAndSelf(
                target,
                new com.ethermod.network.payload.ModulePayloads.SoulFlame(
                        target.getId(), d.getBooleanOr("mm_soul", false)));
        if (wasBurning
                && Traits.has(st, "pyrotechnic.flashover")
                && target.tickCount - d.getIntOr("mm_flashover", 0) > 20) {
            d.putInt("mm_flashover", target.tickCount);
            for (var e :
                    owner.level()
                            .getEntitiesOfClass(
                                    LivingEntity.class,
                                    target.getBoundingBox().inflate(3),
                                    e -> e != target && Allegiance.enemy(owner, e))) {
                track(e);
                e.igniteForSeconds(seconds);
                var ed = com.ethermod.util.MobMemory.of(e);
                ed.store("mm_burn_owner", net.minecraft.core.UUIDUtil.CODEC, owner.getUUID());
                ed.putBoolean("mm_soul", Traits.has(st, "pyrotechnic.soulwake"));
            }
        }
    }

    public static void soulBurn(LivingEntity target, ServerPlayer owner, int seconds) {
        burn(target, owner, seconds, -1);
        com.ethermod.util.MobMemory.of(target).putBoolean("mm_soul", true);
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
                && summon.getPersistentData().read("mm_summoner", net.minecraft.core.UUIDUtil.CODEC).isPresent()) {
            var owner =
                    summon.level()
                            .getServer()
                            .getPlayerList()
                            .getPlayer(summon.getPersistentData().read("mm_summoner", net.minecraft.core.UUIDUtil.CODEC).orElse(null));
            if (owner != null && Allegiance.ally(owner, ev.getEntity())) ev.setCanceled(true);
        }
    }

    /**
     * Burning soul fire: a Soul Blazeling's own soul flame (persistent, set by the entity) or a
     * soul burn it is under right now (in memory).
     */
    public static boolean soul(net.minecraft.world.entity.Entity e) {
        return com.ethermod.util.MobMemory.peek(e).getBooleanOr("mm_soul", false)
                || e.getPersistentData().getBooleanOr("mm_soul", false);
    }

    /** The player whose fire this entity is burning in, or null. */
    public static java.util.UUID burnOwner(net.minecraft.world.entity.Entity e) {
        var d = com.ethermod.util.MobMemory.peek(e);
        return d.read("mm_burn_owner", net.minecraft.core.UUIDUtil.CODEC).isPresent() ? d.read("mm_burn_owner", net.minecraft.core.UUIDUtil.CODEC).orElse(null) : null;
    }

    @SubscribeEvent
    public static void loaded(net.neoforged.neoforge.event.entity.EntityJoinLevelEvent ev) {
        if (ev.getLevel().isClientSide()) return;
        // older versions saved burn state on every mob they set alight: tidy it away
        com.ethermod.util.MobMemory.tidy(ev.getEntity());
        if (ev.getEntity() instanceof LivingEntity e
                && !(e instanceof com.ethermod.entity.SoulBlazeling)
                && !e.getPersistentData().read("mm_summoner", net.minecraft.core.UUIDUtil.CODEC).isPresent())
            e.getPersistentData().remove("mm_soul");
        if (ev.getEntity() instanceof LivingEntity e
                && e.getPersistentData().read("mm_summoner", net.minecraft.core.UUIDUtil.CODEC).isPresent()) track(e);
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
            if (!e.isOnFire() && !e.getPersistentData().read("mm_summoner", net.minecraft.core.UUIDUtil.CODEC).isPresent()) {
                active.remove(uuid);
                net.neoforged.neoforge.network.PacketDistributor.sendToPlayersTrackingEntityAndSelf(
                        e,
                        new com.ethermod.network.payload.ModulePayloads.SoulFlame(
                                e.getId(), false));
                var mem = com.ethermod.util.MobMemory.peek(e);
                mem.remove("mm_soul");
                mem.remove("mm_burn_owner");
                continue;
            }
            var d = e.getPersistentData();
            if (now % 20 == 0 && e.isOnFire())
                net.neoforged.neoforge.network.PacketDistributor.sendToPlayersTrackingEntityAndSelf(
                        e,
                        new com.ethermod.network.payload.ModulePayloads.SoulFlame(
                                e.getId(), soul(e)));
            if (d.read("mm_summoner", net.minecraft.core.UUIDUtil.CODEC).isPresent()) {
                if (now >= d.getLongOr("mm_summon_expires", 0L)) {
                    e.hurt(sl.damageSources().genericKill(), Float.MAX_VALUE);
                    continue;
                }
                var owner = sl.getServer().getPlayerList().getPlayer(d.read("mm_summoner", net.minecraft.core.UUIDUtil.CODEC).orElse(null));
                if (e instanceof Mob mob
                        && !(e instanceof com.ethermod.entity.SoulBlazeling)
                        && owner != null) {
                    var target = owner.getLastHurtMob();
                    if (target != null && target.isAlive() && Allegiance.enemy(owner, target))
                        mob.setTarget(target);
                    else if (mob.getTarget() == owner) mob.setTarget(null);
                }
            }
        }
    }

    @SubscribeEvent
    public static void noSummonXp(LivingExperienceDropEvent ev) {
        if (ev.getEntity().getPersistentData().read("mm_summoner", net.minecraft.core.UUIDUtil.CODEC).isPresent()) ev.setDroppedExperience(0);
    }

    @SubscribeEvent
    public static void loot(LivingDropsEvent ev) {
        if (ev.getEntity().getPersistentData().read("mm_summoner", net.minecraft.core.UUIDUtil.CODEC).isPresent()) ev.getDrops().clear();
    }

    @SubscribeEvent
    public static void death(LivingDeathEvent ev) {
        var victim = ev.getEntity();
        var burner = burnOwner(victim);
        if (!(victim.level() instanceof ServerLevel sl)
                || !victim.isOnFire()
                || !soul(victim)
                || burner == null
                || victim.getPersistentData().read("mm_summoner", net.minecraft.core.UUIDUtil.CODEC).isPresent()) return;
        var owner = sl.getServer().getPlayerList().getPlayer(burner);
        if (owner == null
                || !com.ethermod.config.ServerConfig.blazelings()
                || !Traits.has(Manipulator.stack(owner), "pyrotechnic.soulwake")
                || victim == owner
                || Allegiance.ally(owner, victim)) return;
        if (sl.getEntitiesOfClass(
                                Mob.class,
                                owner.getBoundingBox().inflate(64),
                                s ->
                                        s.getPersistentData().read("mm_summoner", net.minecraft.core.UUIDUtil.CODEC).isPresent()
                                                && s.getPersistentData()
                                                        .read("mm_summoner", net.minecraft.core.UUIDUtil.CODEC).orElse(null)
                                                        .equals(owner.getUUID()))
                        .size()
                >= com.ethermod.config.ServerConfig.maxSummons()) return;
        var pet = com.ethermod.registry.ModEntities.SOUL_BLAZELING.get().create(sl, net.minecraft.world.entity.EntitySpawnReason.MOB_SUMMONED);
        if (pet == null) return;
        pet.setPos(victim.position());
        pet.configure(owner);
        if (owner.getTeam() != null)
            sl.getScoreboard().addPlayerToTeam(pet.getScoreboardName(), owner.getTeam());
        sl.addFreshEntity(pet);
        track(pet);
        var pos = victim.blockPosition();
        if (com.ethermod.config.ServerConfig.fireAndLava() && com.ethermod.util.Protect.may(sl, owner, pos) && sl.getBlockState(pos).isAir())
            TempBlocks.tempBlock(sl, pos, Blocks.SOUL_FIRE.defaultBlockState(), 100);
    }

    @SubscribeEvent
    public static void stop(net.neoforged.neoforge.event.server.ServerStoppingEvent ev) {
        ACTIVE.clear();
    }
}
