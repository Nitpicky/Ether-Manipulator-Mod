package com.ethermod.trait;

import com.ethermod.item.OrbMode;
import com.ethermod.manipulator.Manipulator;

import net.minecraft.core.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.*;
import net.minecraft.world.effect.*;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.ai.attributes.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.phys.*;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.living.*;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.level.*;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

import java.util.*;

/** Server-authoritative common effects. All spatial work is local and bounded. */
public final class RedesignEffects {
    private RedesignEffects() {}

    private static final Map<ServerLevel, Set<UUID>> ACTIVE = new HashMap<>();

    private static void track(LivingEntity e) {
        if (e.level() instanceof ServerLevel sl)
            ACTIVE.computeIfAbsent(sl, k -> new HashSet<>()).add(e.getUUID());
    }

    @SubscribeEvent
    public static void loaded(net.neoforged.neoforge.event.entity.EntityJoinLevelEvent ev) {
        if (ev.getEntity() instanceof LivingEntity e
                && (e.getPersistentData().hasUUID("mm_burn_owner")
                        || e.getPersistentData().hasUUID("mm_summoner"))) track(e);
    }

    private static final ResourceLocation MELEE = id("melee_badge"),
            REACH = id("melee_reach"),
            BLOCK_REACH = id("block_reach"),
            SWIM = id("swim_badge");

    private static ResourceLocation id(String s) {
        return ResourceLocation.fromNamespaceAndPath("ethermod", s);
    }

    private static void attribute(
            Player p,
            net.minecraft.core.Holder<Attribute> key,
            ResourceLocation id,
            double value,
            AttributeModifier.Operation op) {
        var a = p.getAttribute(key);
        if (a == null) return;
        if (value == 0) {
            a.removeModifier(id);
            return;
        }
        var old = a.getModifier(id);
        if (old != null && old.amount() == value) return;
        a.removeModifier(id);
        a.addTransientModifier(new AttributeModifier(id, value, op));
    }

    @SubscribeEvent
    public static void tick(PlayerTickEvent.Post ev) {
        if (!(ev.getEntity() instanceof ServerPlayer p)) return;
        var st = Manipulator.stack(p);
        long now = p.level().getGameTime();
        if (p.tickCount % 10 == 0) {
            attribute(
                    p,
                    Attributes.ATTACK_DAMAGE,
                    MELEE,
                    meleeBonus(p),
                    AttributeModifier.Operation.ADD_VALUE);
            attribute(
                    p,
                    Attributes.ENTITY_INTERACTION_RANGE,
                    REACH,
                    Traits.hasCommon(st, Traits.PLASMIC, 1) ? 1 : 0,
                    AttributeModifier.Operation.ADD_VALUE);
            attribute(
                    p,
                    Attributes.BLOCK_INTERACTION_RANGE,
                    BLOCK_REACH,
                    Traits.hasCommon(st, Traits.TECTONIC, 1) ? 2 : 0,
                    AttributeModifier.Operation.ADD_VALUE);
            attribute(
                    p,
                    net.neoforged.neoforge.common.NeoForgeMod.SWIM_SPEED,
                    SWIM,
                    Traits.hasLine(st, Traits.HYDRAULIC) ? 0.3 : 0,
                    AttributeModifier.Operation.ADD_MULTIPLIED_BASE);
            if (Traits.hasCommon(st, Traits.PHOTONIC, 1)) {
                p.removeEffect(MobEffects.BLINDNESS);
                p.removeEffect(MobEffects.DARKNESS);
            }
            if (Traits.hasLine(st, Traits.PYROTECHNIC)) p.clearFire();
        }
        boolean changed = false;
        // The shared tank reloads Jhin-style: nothing trickles back while you keep firing; once no Ether has been
        // spent for the reload delay (shorter at higher tiers), the whole tank fills at once. Four clicks lead in.
        int cap = OrbMode.capacity(st), tank = OrbMode.tank(st);
        var data = p.getPersistentData();
        // spent since last tick: restart the wait
        if (tank < data.getInt("mm_tank_last")) data.putInt("mm_refill_ether", 0);
        data.putInt("mm_tank_last", tank);
        if (tank < cap) {
            int delay = reloadDelay(st);
            int idle = data.getInt("mm_refill_ether") + 1;
            int lead = delay - idle;
            if (lead > 0 && lead <= 8 && lead % 2 == 0)
                p.playNotifySound(
                        net.minecraft.sounds.SoundEvents.LEVER_CLICK,
                        net.minecraft.sounds.SoundSource.PLAYERS,
                        0.25f,
                        1.5f + (8 - lead) * 0.05f);
            if (idle >= delay) {
                OrbMode.setTank(st, cap);
                data.putInt("mm_tank_last", cap);
                idle = 0;
                changed = true;
                p.playNotifySound(
                        net.minecraft.sounds.SoundEvents.ARMOR_EQUIP_NETHERITE.value(),
                        net.minecraft.sounds.SoundSource.PLAYERS,
                        0.3f,
                        1.2f);
            }
            data.putInt("mm_refill_ether", idle);
        } else data.putInt("mm_refill_ether", 0);
        if (changed) Manipulator.sync(p);
        if (now % 20 == 0
                && Traits.has(st, "pyrotechnic.rekindle")
                && (p.isInLava() || touchesFire(p))) p.heal(1f);
        if (now % 10 == 0 && Traits.hasLine(st, Traits.PHOTONIC) && !p.isSpectator()) {
            var at = p.blockPosition().above();
            if (p.serverLevel().hasChunkAt(at)) WorldEffects.tempLight(p.serverLevel(), at, 10, 15);
        }
    }

    /** Ticks without spending Ether before the tank reloads: the config value at Prototype, 15 less per tier. */
    public static int reloadDelay(net.minecraft.world.item.ItemStack st) {
        int base = com.ethermod.config.EtherConfig.ETHER_REFILL.get();
        return Math.max(10, base - 15 * com.ethermod.item.ManipulatorStage.get(st).ordinal());
    }

    private static boolean touchesFire(Entity p) {
        for (var pos :
                BlockPos.betweenClosed(
                        BlockPos.containing(
                                p.getBoundingBox().minX,
                                p.getBoundingBox().minY,
                                p.getBoundingBox().minZ),
                        BlockPos.containing(
                                p.getBoundingBox().maxX,
                                p.getBoundingBox().maxY,
                                p.getBoundingBox().maxZ)))
            if (p.level().getBlockState(pos).is(net.minecraft.tags.BlockTags.FIRE)) return true;
        return false;
    }

    /**
     * The virtual hand: vanilla's harvest check and break speed read the selected hotbar item, not
     * the main hand, so while the Manipulator is out they would judge whatever sits in that slot
     * (often nothing, so no drops). Judge the Manipulator instead.
     */
    @SubscribeEvent
    public static void harvest(PlayerEvent.HarvestCheck ev) {
        var p = ev.getEntity();
        if (!Manipulator.isSummoned(p)) return;
        ItemStack mm = p.getMainHandItem();
        if (!(mm.getItem() instanceof com.ethermod.item.EtherManipulatorItem)) return;
        var state = ev.getTargetBlock();
        ev.setCanHarvest(!state.requiresCorrectToolForDrops() || mm.isCorrectToolForDrops(state));
    }

    @SubscribeEvent(priority = net.neoforged.bus.api.EventPriority.HIGHEST)
    public static void virtualHandSpeed(PlayerEvent.BreakSpeed ev) {
        var p = ev.getEntity();
        if (!Manipulator.isSummoned(p)) return;
        ItemStack mm = p.getMainHandItem();
        if (!(mm.getItem() instanceof com.ethermod.item.EtherManipulatorItem)) return;
        float selected = Math.max(1f, p.getInventory().getDestroySpeed(ev.getState()));
        ev.setNewSpeed(ev.getNewSpeed() / selected * mm.getDestroySpeed(ev.getState()));
    }

    @SubscribeEvent
    public static void mine(PlayerEvent.BreakSpeed ev) {
        if (Traits.hasLine(Manipulator.stack(ev.getEntity()), Traits.TECTONIC)
                && !(ev.getEntity().getMainHandItem().getItem()
                        instanceof com.ethermod.item.EtherManipulatorItem))
            ev.setNewSpeed(ev.getNewSpeed() + 2);
    }

    @SubscribeEvent
    public static void trample(BlockEvent.FarmlandTrampleEvent ev) {
        if (ev.getEntity() instanceof Player p
                && Traits.hasLine(Manipulator.stack(p), Traits.BOTANIC)) ev.setCanceled(true);
    }

    @SubscribeEvent
    public static void drops(BlockDropsEvent ev) {
        if (!(ev.getBreaker() instanceof ServerPlayer p)) return;
        var st = Manipulator.stack(p);
        var bs = ev.getState();
        if (!(bs.getBlock() instanceof CropBlock crop) || !crop.isMaxAge(bs)) {
            collect(ev, p, st);
            return;
        }
        if (Traits.has(st, "botanic.bountiful")) {
            for (var d : ev.getDrops())
                if (!d.getItem().is(net.neoforged.neoforge.common.Tags.Items.SEEDS))
                    d.getItem().grow(Math.max(1, d.getItem().getCount() / 2));
        }
        if (Traits.hasCommon(st, Traits.BOTANIC, 0)) {
            boolean reserved = false;
            for (var d : ev.getDrops())
                if (!d.getItem().isEmpty() && isPlantingItem(d.getItem(), crop)) {
                    d.getItem().shrink(1);
                    reserved = true;
                    break;
                }
            if (reserved) {
                var level = ev.getLevel();
                var pos = ev.getPos().immutable();
                WorldEffects.schedule(
                        level,
                        1,
                        () -> {
                            var fresh = crop.getStateForAge(0);
                            if (level.hasChunkAt(pos)
                                    && level.getBlockState(pos).isAir()
                                    && fresh.canSurvive(level, pos))
                                level.setBlockAndUpdate(pos, fresh);
                        });
            }
        }
        if (Traits.has(st, "botanic.cross_pollination"))
            for (var pos :
                    BlockPos.betweenClosed(
                            ev.getPos().offset(-2, 0, -2), ev.getPos().offset(2, 0, 2))) {
                var q = pos.immutable();
                var s = ev.getLevel().getBlockState(q);
                if (!q.equals(ev.getPos())
                        && s.getBlock() instanceof CropBlock c
                        && !c.isMaxAge(s)
                        && ev.getLevel().mayInteract(p, q))
                    ev.getLevel()
                            .setBlockAndUpdate(
                                    q, c.getStateForAge(Math.min(c.getMaxAge(), c.getAge(s) + 1)));
            }
        collect(ev, p, st);
    }

    private static void collect(BlockDropsEvent ev, ServerPlayer p, ItemStack st) {
        // Collector is handled with the other drop traits (TraitEffects): drops fly to you
    }

    private static boolean isPlantingItem(ItemStack item, CropBlock crop) {
        return item.getItem() instanceof BlockItem b && b.getBlock() == crop;
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
        d.putBoolean("mm_weak_burn", false /* Heat Exhaustion retired; Phoenix is the second Common. */);
        d.putInt(
                "mm_inferno",
                false && phase >= 0
                        ? phase + 1
                        : wasBurning ? d.getInt("mm_inferno") : 0);
        if (wasBurning
                && Traits.has(st, "pyrotechnic.flashover")
                && target.tickCount - d.getInt("mm_flashover") > 20) {
            d.putInt("mm_flashover", target.tickCount);
            for (var e :
                    owner.serverLevel()
                            .getEntitiesOfClass(
                                    LivingEntity.class,
                                    target.getBoundingBox().inflate(3),
                                    e -> e != target && enemy(owner, e))) {
                track(e);
                e.igniteForSeconds(seconds);
                e.getPersistentData().putUUID("mm_burn_owner", owner.getUUID());
                e.getPersistentData()
                        .putBoolean("mm_soul", Traits.has(st, "pyrotechnic.soulwake"));
                e.getPersistentData()
                        .putBoolean("mm_weak_burn", false /* Heat Exhaustion retired; Phoenix is the second Common. */);
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

    public static boolean ally(ServerPlayer owner, LivingEntity e) {
        return e == owner
                || (e.getPersistentData().hasUUID("mm_summoner")
                        && owner.getUUID().equals(e.getPersistentData().getUUID("mm_summoner")))
                || owner.isAlliedTo(e)
                || (e instanceof net.minecraft.world.entity.TamableAnimal t
                        && owner.getUUID().equals(t.getOwnerUUID()));
    }

    public static boolean enemy(ServerPlayer owner, LivingEntity e) {
        return !com.ethermod.beam.Mounts.own(owner, e)
                && e.isAlive()
                && !ally(owner, e)
                && !(e instanceof net.minecraft.world.entity.decoration.ArmorStand);
    }

    /** The Manipulator's melee bonus (Plasmic: Melee Damage +1). */
    public static float meleeBonus(ServerPlayer p) {
        return Traits.hasLine(Manipulator.stack(p), Traits.PLASMIC) ? 1 : 0;
    }

    /** The Manipulator's ranged bonus (Kinetic: Fully Charged Ranged Damage +3). */
    public static float rangedBonus(ServerPlayer p) {
        return Traits.hasLine(Manipulator.stack(p), Traits.KINETIC) ? 3 : 0;
    }

    @SubscribeEvent
    public static void ranged(LivingIncomingDamageEvent ev) {
        if (ev.getSource().getEntity() instanceof ServerPlayer p
                && ev.getSource().getDirectEntity()
                        instanceof net.minecraft.world.entity.projectile.AbstractArrow arrow
                && arrow.isCritArrow())
            ev.setAmount(ev.getAmount() + rangedBonus(p));
        if (ev.getSource().getEntity() instanceof Mob summon
                && summon.getPersistentData().hasUUID("mm_summoner")) {
            var owner =
                    summon.level()
                            .getServer()
                            .getPlayerList()
                            .getPlayer(summon.getPersistentData().getUUID("mm_summoner"));
            if (owner != null && ally(owner, ev.getEntity())) ev.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void hit(LivingDamageEvent.Post ev) {
        if (ev.getSource().getEntity() instanceof ServerPlayer p
                && (ev.getSource().is(net.minecraft.world.damagesource.DamageTypes.PLAYER_ATTACK)
                        || ev.getSource().is(com.ethermod.beam.BeamCastHelper.BEAM_DAMAGE))
                && Traits.hasCommon(Manipulator.stack(p), Traits.PYROTECHNIC, 0))
            burn(ev.getEntity(), p, 4, -1);
    }

    private record Frozen(
            ServerLevel level,
            UUID entity,
            Vec3 at,
            long expires,
            boolean noGravity,
            boolean noAi) {}

    private static final Map<UUID, Frozen> FROZEN = new HashMap<>();

    public static void stasis(LivingEntity e, int ticks) {
        if (!(e.level() instanceof ServerLevel sl)
                || FROZEN.containsKey(e.getUUID())
                || e.getPersistentData().getLong("mm_stasis_cooldown") > sl.getGameTime()) return;
        FROZEN.put(
                e.getUUID(),
                new Frozen(
                        sl,
                        e.getUUID(),
                        e.position(),
                        sl.getGameTime() + ticks,
                        e.isNoGravity(),
                        e instanceof Mob m && m.isNoAi()));
        e.getPersistentData().putDouble("mm_stasis_force", 0);
        e.setDeltaMovement(Vec3.ZERO);
        e.setNoGravity(true);
        if (e instanceof Mob m) m.setNoAi(true);
    }

    public static void impulse(LivingEntity e, Vec3 force) {
        if (FROZEN.containsKey(e.getUUID())) {
            store(e, force);
            return;
        }
        e.setDeltaMovement(e.getDeltaMovement().add(force));
        e.hurtMarked = true;
    }

    private static void store(LivingEntity e, Vec3 force) {
        if (force.lengthSqr() < 1e-6) return;
        var d = e.getPersistentData();
        d.putDouble(
                "mm_stasis_force", Math.min(2.5, d.getDouble("mm_stasis_force") + force.length()));
        var n = force.normalize();
        d.putDouble("mm_stasis_x", n.x);
        d.putDouble("mm_stasis_y", n.y);
        d.putDouble("mm_stasis_z", n.z);
    }

    @SubscribeEvent
    public static void knock(LivingKnockBackEvent ev) {
        if (FROZEN.containsKey(ev.getEntity().getUUID())) {
            store(
                    ev.getEntity(),
                    new Vec3(-ev.getRatioX(), 0.25, -ev.getRatioZ())
                            .normalize()
                            .scale(ev.getStrength()));
            ev.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void world(LevelTickEvent.Post ev) {
        if (!(ev.getLevel() instanceof ServerLevel sl)) return;
        long now = sl.getGameTime();
        var it = FROZEN.entrySet().iterator();
        while (it.hasNext()) {
            var f = it.next().getValue();
            if (f.level() != sl) continue;
            if (!(sl.getEntity(f.entity()) instanceof LivingEntity e) || !e.isAlive()) {
                it.remove();
                continue;
            }
            if (now >= f.expires()) {
                var d = e.getPersistentData();
                var v =
                        new Vec3(
                                        d.getDouble("mm_stasis_x"),
                                        d.getDouble("mm_stasis_y"),
                                        d.getDouble("mm_stasis_z"))
                                .scale(d.getDouble("mm_stasis_force"));
                e.setNoGravity(f.noGravity());
                if (e instanceof Mob m) m.setNoAi(f.noAi());
                e.setDeltaMovement(v);
                e.hurtMarked = true;
                d.putLong("mm_stasis_cooldown", now + 40);
                it.remove();
            } else {
                if (e.getDeltaMovement().lengthSqr() > .09) store(e, e.getDeltaMovement());
                e.setPos(f.at());
                e.setDeltaMovement(Vec3.ZERO);
                e.resetFallDistance();
                if (e instanceof Mob m) m.getNavigation().stop();
            }
        }
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
            if (d.getBoolean("mm_weak_burn") && e.isOnFire())
                e.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, 15, 0, false, false, true));
            if (e.isOnFire() && d.getInt("mm_inferno") > 0 && e instanceof LivingEntity) {
                if (now % (Math.max(5, 20 - 5 * d.getInt("mm_inferno"))) == 0)
                    e.hurt(sl.damageSources().onFire(), 1);
            }
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
                    if (target != null && target.isAlive() && enemy(owner, target))
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
        if (ev.getEntity().getPersistentData().hasUUID("mm_summoner")) {
            ev.getDrops().clear();
            return;
        }
        if (!(ev.getSource().getEntity() instanceof ServerPlayer p)
                || !Traits.has(Manipulator.stack(p), "plasmic.dissolve")) return;
        int level =
                Math.max(
                        1,
                        Math.min(
                                4,
                                ev.getEntity().getPersistentData().getInt("mm_combat_pressure")));
        if (level <= 1) return;
        var extra = new ArrayList<net.minecraft.world.entity.item.ItemEntity>();
        for (var item : ev.getDrops())
            if (p.getRandom().nextFloat() < (level - 1) * 0.25f)
                extra.add(
                        new net.minecraft.world.entity.item.ItemEntity(
                                p.level(),
                                item.getX(),
                                item.getY(),
                                item.getZ(),
                                item.getItem().copyWithCount(1)));
        ev.getDrops().addAll(extra);
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
                || !Traits.has(Manipulator.stack(owner), "pyrotechnic.soulwake")
                || victim == owner
                || ally(owner, victim)) return;
        if (sl.getEntitiesOfClass(
                                Mob.class,
                                owner.getBoundingBox().inflate(64),
                                s ->
                                        s.getPersistentData().hasUUID("mm_summoner")
                                                && s.getPersistentData()
                                                        .getUUID("mm_summoner")
                                                        .equals(owner.getUUID()))
                        .size()
                >= 8) return;
        var pet = com.ethermod.registry.ModEntities.SOUL_BLAZELING.get().create(sl);
        if (pet == null) return;
        pet.setPos(victim.position());
        pet.configure(owner);
        if (owner.getTeam() != null)
            sl.getScoreboard().addPlayerToTeam(pet.getScoreboardName(), owner.getTeam());
        sl.addFreshEntity(pet);
        track(pet);
        var pos = victim.blockPosition();
        if (sl.mayInteract(owner, pos) && sl.getBlockState(pos).isAir())
            WorldEffects.tempBlock(sl, pos, Blocks.SOUL_FIRE.defaultBlockState(), 100);
    }

    @SubscribeEvent
    public static void stop(net.neoforged.neoforge.event.server.ServerStoppingEvent ev) {
        FROZEN.clear();
        ACTIVE.clear();
    }
}
