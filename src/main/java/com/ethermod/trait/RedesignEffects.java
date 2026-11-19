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
        // a creature saved mid-stasis (chunk unloaded, logout, Save and Quit) gets its flags back
        if (ev.getEntity() instanceof LivingEntity held
                && held.getPersistentData().contains(STASIS_RESTORE)
                && !FROZEN.containsKey(held.getUUID())) restoreStasisFlags(held);
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
        // spent for the reload delay (shorter at higher tiers), the whole tank fills at once. The reload animation leads in.
        int cap = OrbMode.capacity(st), tank = OrbMode.tank(st);
        var data = p.getPersistentData();
        // spent since last tick: restart the wait
        if (tank < data.getInt("mm_tank_last")) {
            // fired during the reload animation: call it off
            if (reloadDelay(st) - data.getInt("mm_refill_ether") <= RELOAD_LEAD)
                net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(
                        p, new com.ethermod.network.payload.ReloadPayload(-100));
            data.putInt("mm_refill_ether", 0);
        }
        data.putInt("mm_tank_last", tank);
        if (tank < cap) {
            int delay = reloadDelay(st);
            int idle = data.getInt("mm_refill_ether") + 1;
            int lead = delay - idle;
            // the reload (FX bench "intake"): the animation starts RELOAD_LEAD ticks before the fill, so its clamp
            // lands on it; Ether is drawn in with a soft charge, and a small click when the tank is full
            if (lead == Math.min(RELOAD_LEAD, delay - 1))
                net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(
                        p, new com.ethermod.network.payload.ReloadPayload(lead));
            if (lead == RELOAD_LEAD - 8)
                p.playNotifySound(
                        com.ethermod.sound.ModSounds.UI_RESPAWN_ANCHOR_CHARGE,
                        net.minecraft.sounds.SoundSource.PLAYERS,
                        0.18f,
                        1.5f);
            if (idle >= delay) {
                OrbMode.setTank(st, cap);
                data.putInt("mm_tank_last", cap);
                idle = 0;
                changed = true;
                p.playNotifySound(
                        com.ethermod.sound.ModSounds.UI_COPPER_BULB_TURN_OFF,
                        net.minecraft.sounds.SoundSource.PLAYERS,
                        0.1f,
                        1.3f);
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

    /** Ticks from the reload animation's start to its clamp (1.16s of the 1.6s animation). */
    static final int RELOAD_LEAD = 23;

    /** Ticks without spending Ether before the tank reloads: from the server config, Prototype to Overclocked. */
    public static int reloadDelay(net.minecraft.world.item.ItemStack st) {
        return com.ethermod.config.ServerConfig.reloadTicks(com.ethermod.item.ManipulatorStage.get(st).ordinal());
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
                        && com.ethermod.util.Protect.may(ev.getLevel(), p, q))
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
        // other players only when both the hurt_players setting and the server's PvP allow it
        if (e instanceof Player && e != owner
                && !(com.ethermod.config.ServerConfig.hurtPlayers() && owner.server.isPvpAllowed()))
            return false;
        return enemyIgnoringPvp(owner, e);
    }

    /** As {@link #enemy}, without the player rule: for callers with their own player setting. */
    public static boolean enemyIgnoringPvp(ServerPlayer owner, LivingEntity e) {
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

    /**
     * Saved on the creature while it is held: its own NoGravity/NoAI flags, so they come back even
     * if it leaves the world before the stasis ends (FROZEN only lives in memory).
     */
    private static final String STASIS_RESTORE = "mm_stasis_restore";

    private static void restoreStasisFlags(LivingEntity e) {
        var r = e.getPersistentData().getCompound(STASIS_RESTORE);
        e.setNoGravity(r.getBoolean("gravity"));
        if (e instanceof Mob m) m.setNoAi(r.getBoolean("ai"));
        e.getPersistentData().remove(STASIS_RESTORE);
    }

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
        var restore = new net.minecraft.nbt.CompoundTag();
        restore.putBoolean("gravity", e.isNoGravity());
        restore.putBoolean("ai", e instanceof Mob m0 && m0.isNoAi());
        e.getPersistentData().put(STASIS_RESTORE, restore);
        e.setDeltaMovement(Vec3.ZERO);
        e.setNoGravity(true);
        if (e instanceof Mob m) m.setNoAi(true);
        // the amber tint and the quickening ding (client StasisFx)
        net.neoforged.neoforge.network.PacketDistributor.sendToPlayersTrackingEntityAndSelf(
                e, new com.ethermod.network.payload.StasisPayload(e.getId(), ticks));
    }

    public static boolean inStasis(LivingEntity e) {
        return FROZEN.containsKey(e.getUUID());
    }

    /** The Stasis Grenade's burst: adds its strength to the stored push, keeping the last hit's aim. */
    public static void burstInto(LivingEntity e, Vec3 push) {
        var d = e.getPersistentData();
        if (!inStasis(e)) {
            impulse(e, push);
            return;
        }
        { // the grenade's spot is the one authority on direction; stored hits only add strength
            var n = push.normalize();
            d.putDouble("mm_stasis_x", n.x);
            d.putDouble("mm_stasis_y", n.y);
            d.putDouble("mm_stasis_z", n.z);
        }
        d.putDouble("mm_stasis_force", Math.min(STASIS_MAX, d.getDouble("mm_stasis_force") + push.length()));
    }

    /** Lets a held creature go now, with all the knockback it stored. */
    public static void thaw(LivingEntity e) {
        var f = FROZEN.remove(e.getUUID());
        if (f != null) let(e, f, e.level().getGameTime());
    }

    private static void let(LivingEntity e, Frozen f, long now) {
        var d = e.getPersistentData();
        var v = launch(e, false);
        e.setNoGravity(f.noGravity());
        if (e instanceof Mob m) m.setNoAi(f.noAi());
        d.remove(STASIS_RESTORE);
        e.setDeltaMovement(v);
        e.hurtMarked = true;
        d.putLong("mm_stasis_cooldown", now + 40);
        d.remove("mm_stasis_ex");
        d.remove("mm_stasis_ey");
        d.remove("mm_stasis_ez");
        net.neoforged.neoforge.network.PacketDistributor.sendToPlayersTrackingEntityAndSelf(
                e, new com.ethermod.network.payload.StasisPayload(e.getId(), 0));
    }

    public static void impulse(LivingEntity e, Vec3 force) {
        if (FROZEN.containsKey(e.getUUID())) {
            store(e, force);
            return;
        }
        e.setDeltaMovement(e.getDeltaMovement().add(force));
        e.hurtMarked = true;
    }

    /**
     * Stasis amplifies what it stores: each knockback taken while held counts this much more, up to
     * {@link #STASIS_MAX} (blocks/tick), so a setup of a few hits sends it flying.
     */
    public static final double STASIS_GAIN = 1.8, STASIS_MAX = 3.0;

    private static void store(LivingEntity e, Vec3 force) {
        if (force.lengthSqr() < 1e-6) return;
        var d = e.getPersistentData();
        d.putDouble(
                "mm_stasis_force",
                Math.min(STASIS_MAX, d.getDouble("mm_stasis_force") + force.length() * STASIS_GAIN));
        var n = force.normalize();
        d.putDouble("mm_stasis_x", n.x);
        d.putDouble("mm_stasis_y", n.y);
        d.putDouble("mm_stasis_z", n.z);
        sendForce(e);
    }

    /**
     * What a Stasis Grenade's burst will add on release: its strength joins the stored knockback,
     * and its direction (away from where the grenade stuck) is the launch direction. Shown in the
     * arrow before it happens.
     */
    public static void expectBurst(LivingEntity e, Vec3 push) {
        var d = e.getPersistentData();
        d.putDouble("mm_stasis_ex", push.x);
        d.putDouble("mm_stasis_ey", push.y);
        d.putDouble("mm_stasis_ez", push.z);
        sendForce(e);
    }

    /**
     * The velocity a held creature leaves with: the stored strength (plus a pending burst) along the
     * last hit's direction (the burst's own when nothing hit it), always with enough lift to leave
     * the ground, so it flies instead of being dragged to a stop by friction. The arrow and the
     * release both use this, so the preview is the launch.
     */
    private static Vec3 launch(LivingEntity e, boolean withBurst) {
        var d = e.getPersistentData();
        var burst = withBurst
                ? new Vec3(d.getDouble("mm_stasis_ex"), d.getDouble("mm_stasis_ey"), d.getDouble("mm_stasis_ez"))
                : Vec3.ZERO;
        double force = Math.min(STASIS_MAX, d.getDouble("mm_stasis_force") + burst.length());
        if (force < 1e-6) return Vec3.ZERO;
        // the Stasis Grenade's spot on the body sets the direction (stored hits only add
        // strength); without a pending burst, the last stored direction
        var dir = burst.lengthSqr() > 1e-6
                ? burst.normalize()
                : new Vec3(d.getDouble("mm_stasis_x"), d.getDouble("mm_stasis_y"), d.getDouble("mm_stasis_z"));
        var v = dir.scale(force);
        // lift so a level push flies instead of dragging along the ground; a push aimed down
        // (grenade on top) stays down
        if (dir.y < -0.2) return v;
        return new Vec3(v.x, Math.max(v.y, .3 + .12 * force), v.z);
    }

    /** The teal arrow over the held creature: where (and how hard) it will fly on release. */
    private static void sendForce(LivingEntity e) {
        var v = launch(e, true);
        net.neoforged.neoforge.network.PacketDistributor.sendToPlayersTrackingEntityAndSelf(
                e, new com.ethermod.network.payload.StasisForcePayload(e.getId(), (float) v.x, (float) v.y, (float) v.z));
    }

    private static UUID quiet;

    /** Damages a held creature without its knockback counting toward the stored push. */
    public static void hurtQuietly(LivingEntity e, net.minecraft.world.damagesource.DamageSource src, float amount) {
        quiet = e.getUUID();
        try {
            e.hurt(src, amount);
        } finally {
            quiet = null;
        }
    }

    @SubscribeEvent
    public static void knock(LivingKnockBackEvent ev) {
        if (ev.getEntity().getUUID().equals(quiet)) {
            ev.setCanceled(true);
            return;
        }
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
                let(e, f, now);
                it.remove();
            } else {
                if (e.getDeltaMovement().lengthSqr() > .09) store(e, e.getDeltaMovement());
                if (e instanceof ServerPlayer sp) {
                    // players move on their own side: pull them back to the spot when they drift
                    if (sp.position().distanceToSqr(f.at()) > 1e-4)
                        sp.connection.teleport(f.at().x, f.at().y, f.at().z, sp.getYRot(), sp.getXRot());
                } else e.setPos(f.at());
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
                || !com.ethermod.config.ServerConfig.blazelings()
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
        // put held creatures back before they are saved
        for (var f : FROZEN.values())
            if (f.level().getEntity(f.entity()) instanceof LivingEntity e) restoreStasisFlags(e);
        FROZEN.clear();
        ACTIVE.clear();
    }
}
