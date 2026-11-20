package com.ethermod.beam;

import com.ethermod.item.EtherManipulatorItem;
import com.ethermod.item.OrbMode;
import com.ethermod.manipulator.Manipulator;
import com.ethermod.trait.Traits;
import com.ethermod.trait.WorldEffects;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Left click for every mode except Beam (which mines; see ModEvents). The client reports LMB down
 * and up with the same packet as the beam; the server picks the mode's action:
 *
 * <ul>
 *   <li>Hydraulic / Pyrotechnic: charge and throw the water blob / molten glob, exactly like the
 *       right-click orbs charge.
 *   <li>Pneumatic: a blast grenade ({@link GustGrenade}).
 *   <li>Photonic: a beacon of light out of the nozzle while held, no cooldown.
 *   <li>Gravitic: fire the tether hook (Skyhook anchors on release).
 *   <li>Botanic: harvest the ripe crops ahead.
 *   <li>Tectonic: grab the block you aim at (hold to sweep up more); press again to set it down.
 * </ul>
 */
public final class LeftClick {

    private LeftClick() {}

    private static final class Hold {
        final OrbMode mode;
        int ticks;

        Hold(OrbMode mode) {
            this.mode = mode;
        }
    }

    private static final Map<UUID, Hold> HOLDS = new HashMap<>();
    private static final Map<UUID, Long> READY = new HashMap<>();

    /** The Beacon's lights right now, by player: they go out as soon as the aim moves on. */
    private static final Map<UUID, java.util.Set<BlockPos>> LIT = new HashMap<>();
    /** Every light this Beacon hold has placed: letting go clears them all, even ones the aim tracking lost. */
    private static final Map<UUID, java.util.Set<BlockPos>> LIT_ALL = new HashMap<>();

    public static final double BEACON_RANGE = 48;

    /** LMB pressed in a non-Beam mode. */
    public static void press(ServerPlayer p) {
        ItemStack st = Manipulator.stack(p);
        OrbMode mode = OrbMode.get(st);
        stop(p);
        // Detonator: left click again while your left-click shot is in the air bursts it
        if (BeamBallEntity.detonateFor(p, st, true)) return;
        switch (mode) {
            case WATER, LAVA, GUST -> {
                if (mode.tankLevel(st) < mode.shotCost(st, 0)) {
                    empty(p);
                    return;
                }
                HOLDS.put(p.getUUID(), new Hold(mode));
            }
            case LIGHT -> {
                HOLDS.put(p.getUUID(), new Hold(mode));
                // others see the beacon as a beam in the Light colour, like the holder does
                com.ethermod.event.ModEvents.showBeam(p, true);
            }
            case TETHER -> {
                if (ready(p, 6)) {
                    OrbTether.fire(p, st);
                    HOLDS.put(p.getUUID(), new Hold(mode)); // Skyhook waits for the release
                }
            }
            case GROWTH -> {
                if (ready(p, 8)) harvest(p, st);
            }
            case MASS -> {
                if (MassDriver.holding(p)) MassDriver.release(p, true);
                else if (MassDriver.press(p, st)) HOLDS.put(p.getUUID(), new Hold(mode));
            }
            default -> {}
        }
    }

    /** LMB released: the charged left clicks fire now. */
    public static void release(ServerPlayer p) {
        Hold h = stop(p);
        // Skyhook: letting go of left click anchors the flying hook in the air where it is
        if (h != null && h.mode == OrbMode.TETHER) {
            var hook = OrbTether.activeHook(p);
            if (hook != null && !hook.isStuck() && Traits.has(Manipulator.stack(p), "gravitic.skyhook"))
                OrbTether.hookAir(hook);
            return;
        }
        if (h == null || !h.mode.chargesOnLeft()) return;
        ItemStack st = Manipulator.stack(p);
        if (OrbMode.get(st) != h.mode || !Manipulator.isSummoned(p)) return;
        int phase = Math.min(2, h.ticks * 3 / Traits.chargeTicks(st));
        EtherManipulatorItem.fireVolley(p.level(), p, st, phase, h.mode, true);
    }

    /** Ends any held left click (the Beacon's lights go out with it). */
    private static Hold stop(ServerPlayer p) {
        return stop(p, p.serverLevel());
    }

    /**
     * The player changed dimension: ends any held left click, putting the Beacon's lights out in
     * the level they were lit in ({@code from}; null if it is gone, then they are only forgotten
     * and run out on their own timer). The new level is never touched at the old coordinates.
     */
    public static void changedLevel(ServerPlayer p, ServerLevel from) {
        stop(p, from);
    }

    private static Hold stop(ServerPlayer p, ServerLevel lightsIn) {
        var lit = LIT.remove(p.getUUID());
        if (lit != null && lightsIn != null) unlight(lightsIn, lit, java.util.Set.of());
        var all = LIT_ALL.remove(p.getUUID());
        if (all != null && lightsIn != null) unlight(lightsIn, all, java.util.Set.of());
        Hold h = HOLDS.remove(p.getUUID());
        if (h != null && h.mode == OrbMode.LIGHT) com.ethermod.event.ModEvents.showBeam(p, false);
        return h;
    }

    private static void unlight(
            ServerLevel sl, java.util.Set<BlockPos> lights, java.util.Set<BlockPos> keep) {
        for (BlockPos q : lights) {
            if (keep.contains(q)) continue;
            if (sl.isLoaded(q) && sl.getBlockState(q).is(net.minecraft.world.level.block.Blocks.LIGHT))
                sl.removeBlock(q, false);
            WorldEffects.forgetLight(sl, q);
        }
    }

    /**
     * Right click in Pneumatic mode: throws the Stasis Grenade, or bursts the one already out
     * (Detonator-style, like the other tools' second click).
     */
    public static void throwGrenade(ServerPlayer p) {
        ItemStack st = Manipulator.stack(p);
        if (GustGrenade.detonateFor(p, st)) return;
        if (ready(p, 10) && spend(p, st, OrbMode.GRENADE_COST)) GustGrenade.throwFrom(p, st);
    }

    /** True while LMB is held for an action (the playtest reads it). */
    public static boolean holding(ServerPlayer p) {
        return HOLDS.containsKey(p.getUUID());
    }

    /** Pays {@code cost} from the shared tank, or says it is empty and returns false. */
    private static boolean spend(ServerPlayer p, ItemStack st, int cost) {
        if (OrbMode.tank(st) < cost) {
            empty(p);
            return false;
        }
        OrbMode.setTank(st, OrbMode.tank(st) - cost);
        Manipulator.sync(p);
        return true;
    }

    private static void empty(ServerPlayer p) {
        DryFire.signal(p);
    }

    /** False (and nothing happens) until {@code cooldown} ticks after the last use. */
    private static boolean ready(ServerPlayer p, int cooldown) {
        long now = p.level().getGameTime();
        Long at = READY.get(p.getUUID());
        if (at != null && now < at) return false;
        READY.put(p.getUUID(), now + cooldown);
        return true;
    }

    @SubscribeEvent
    public static void tick(PlayerTickEvent.Post ev) {
        if (!(ev.getEntity() instanceof ServerPlayer p)) return;
        Hold h = HOLDS.get(p.getUUID());
        if (h == null) return;
        ItemStack st = Manipulator.stack(p);
        if (!p.isAlive() || !Manipulator.isSummoned(p) || OrbMode.get(st) != h.mode) {
            stop(p);
            return;
        }
        h.ticks++;
        switch (h.mode) {
            case LIGHT -> beacon(p, h.ticks);
            case MASS -> MassDriver.paint(p, st, h.ticks);
            default -> {}
        }
    }

    @SubscribeEvent
    public static void logout(PlayerEvent.PlayerLoggedOutEvent ev) {
        if (ev.getEntity() instanceof ServerPlayer p) stop(p);
        READY.remove(ev.getEntity().getUUID());
    }

    // ---- Photonic: Beacon ---------------------------------------------------------

    /**
     * Lights where the beam lands (and dimmer every 6 blocks on the way). Lights the aim has moved
     * off go out at once, and all of them when you let go.
     */
    private static void beacon(ServerPlayer p, int ticks) {
        ServerLevel sl = p.serverLevel();
        Vec3 eye = p.getEyePosition(), look = p.getLookAngle();
        var hit =
                sl.clip(
                        new ClipContext(
                                eye,
                                eye.add(look.scale(BEACON_RANGE)),
                                ClipContext.Block.COLLIDER,
                                ClipContext.Fluid.NONE,
                                p));
        Vec3 end = hit.getLocation();
        BlockPos at =
                hit.getType() == HitResult.Type.BLOCK
                        ? hit.getBlockPos().relative(hit.getDirection())
                        : BlockPos.containing(end);
        var now = new java.util.LinkedHashMap<BlockPos, Integer>();
        now.put(at.immutable(), 15);
        double len = end.distanceTo(eye);
        for (double d = 6; d < len - 2; d += 6)
            now.putIfAbsent(BlockPos.containing(eye.add(look.scale(d))), 12);
        var before = LIT.getOrDefault(p.getUUID(), java.util.Set.of());
        unlight(sl, before, now.keySet());
        var lit = new java.util.HashSet<BlockPos>();
        now.forEach(
                (q, level) -> {
                    var was = sl.getBlockState(q);
                    // only light air (or our own light): never touch someone else's light block
                    if (was.isAir()
                            || before.contains(q)
                                    && was.is(net.minecraft.world.level.block.Blocks.LIGHT)) {
                        WorldEffects.tempLight(sl, q, level, 20);
                        lit.add(q);
                    } else if (before.contains(q)) lit.add(q);   // still ours: keep tracking it so letting go clears it
                });
        LIT.put(p.getUUID(), lit);
        LIT_ALL.computeIfAbsent(p.getUUID(), k -> new java.util.HashSet<>()).addAll(lit);
        if (ticks % 4 != 0) return;
        // creatures in the light are outlined for a moment
        for (var e :
                sl.getEntitiesOfClass(
                        LivingEntity.class,
                        new AABB(eye, end).inflate(1),
                        e -> !com.ethermod.beam.Mounts.own(p, e) && e.getBoundingBox().inflate(0.3).clip(eye, end).isPresent()))
            e.addEffect(new MobEffectInstance(MobEffects.GLOWING, 30, 0, false, false));
    }

    // ---- Botanic: harvest ------------------------------------------------------------

    /** For the playtest: crops the Botanic left click has harvested. */
    public static int harvested;

    /**
     * Botanic left click: harvests every mature crop in a 3x3 (a block up or down too) around the
     * crop or farmland you aim at. Overgrowth carries on through every connected mature crop of the
     * same kind. Harvests break the crops as you would (Regrowth, Bountiful and Cross-Pollination
     * apply), and never touch unripe ones.
     */
    private static void harvest(ServerPlayer p, ItemStack st) {
        ServerLevel sl = p.serverLevel();
        Vec3 eye = p.getEyePosition(), look = p.getLookAngle();
        var hit =
                sl.clip(
                        new ClipContext(
                                eye,
                                eye.add(look.scale(Traits.beamRange(st))),
                                ClipContext.Block.OUTLINE,
                                ClipContext.Fluid.NONE,
                                p));
        if (hit.getType() == HitResult.Type.MISS) return;
        BlockPos center = hit.getBlockPos();
        if (!ripe(sl.getBlockState(center)) && ripe(sl.getBlockState(center.above())))
            center = center.above();
        var todo = new java.util.ArrayDeque<BlockPos>();
        var seen = new java.util.HashSet<BlockPos>();
        for (BlockPos q : BlockPos.betweenClosed(center.offset(-1, -1, -1), center.offset(1, 1, 1)))
            if (ripe(sl.getBlockState(q)) && seen.add(q.immutable())) todo.add(q.immutable());
        boolean overgrowth = Traits.has(st, "botanic.overgrowth");
        // find everything first, then cut it in one sweep from right to left (the animation's
        // swing), a column every tick, so the harvest reads as a sickle pass
        var cut = new java.util.ArrayList<BlockPos>();
        while (!todo.isEmpty() && cut.size() < 256) {
            BlockPos q = todo.poll();
            var state = sl.getBlockState(q);
            if (!ripe(state) || !com.ethermod.util.Protect.may(sl, p, q)) continue;
            cut.add(q);
            if (overgrowth)
                for (BlockPos n : BlockPos.betweenClosed(q.offset(-1, -1, -1), q.offset(1, 1, 1))) {
                    var s = sl.getBlockState(n);
                    if (s.getBlock() == state.getBlock() && ripe(s) && seen.add(n.immutable()))
                        todo.add(n.immutable());
                }
        }
        if (cut.isEmpty()) return;
        if (cut.size() >= 16) com.ethermod.advancement.ManipulatorTrigger.fire(p, "green_thumb");
        Vec3 right = new Vec3(-look.z, 0, look.x).normalize();
        Vec3 mid = Vec3.atCenterOf(center);
        double most = -1e9;
        for (BlockPos q : cut) most = Math.max(most, Vec3.atCenterOf(q).subtract(mid).dot(right));
        for (BlockPos q : cut) {
            Vec3 rel = Vec3.atCenterOf(q).subtract(mid);
            int delay =
                    (int) Math.min(
                            20,
                            Math.round(most - rel.dot(right))
                                    + Math.round(Math.max(0, rel.horizontalDistance() - 1.5) * .5));
            WorldEffects.schedule(sl, 1 + delay, () -> cutCrop(p, q, right));
        }
        sl.playSound(
                null,
                center,
                com.ethermod.sound.ModSounds.WORLD_PLAYER_ATTACK_SWEEP,
                SoundSource.PLAYERS,
                0.5f,
                1.4f);
    }

    /** One crop of a harvest sweep: broken the way you would break it, with a cut across it. */
    private static void cutCrop(ServerPlayer p, BlockPos q, Vec3 right) {
        if (p.isRemoved() || !(p.level() instanceof ServerLevel sl) || !sl.isLoaded(q)) return;
        var state = sl.getBlockState(q);
        if (!ripe(state) || !com.ethermod.util.Protect.may(sl, p, q)) return;
        if (!p.gameMode.destroyBlock(q)) return;
        harvested++;
        // vanilla sends the break particles and sound to everyone but the breaker (whose client
        // predicts its own), so the harvester gets them here
        p.connection.send(
                new net.minecraft.network.protocol.game.ClientboundLevelEventPacket(
                        2001, q, net.minecraft.world.level.block.Block.getId(state), false));
        // the cut itself, in the same pixel style as the other Module effects: a Growth slash
        // through the crop along the sweep (right to left), with chaff thrown on
        Vec3 sweep = right.scale(-1);
        LeftClickFxData.send(
                sl,
                com.ethermod.network.payload.LeftClickFxPayload.CUT,
                0,
                p.getId(),
                new Vec3(q.getX() + .5, q.getY() + .3, q.getZ() + .5),
                (int) Math.round(Math.toDegrees(Math.atan2(sweep.z, sweep.x)) * 10));
    }

    private record Pull(ItemEntity item, UUID owner, long start) {}

    private static final java.util.List<Pull> PULLS = new java.util.ArrayList<>();

    /**
     * Collector: a drop that popped out of a block flies to its breaker after a moment and is
     * picked up the vanilla way (dropped at their feet when the inventory is full).
     */
    public static void magnet(ItemEntity drop, ServerPlayer to) {
        PULLS.add(new Pull(drop, to.getUUID(), to.level().getGameTime()));
    }

    /**
     * Pulls collected items to their breaker: a short pop first, then they fly in and are picked
     * up the vanilla way. With a full inventory they simply drop at your feet.
     */
    @SubscribeEvent
    public static void pullHarvest(net.neoforged.neoforge.event.tick.ServerTickEvent.Post ev) {
        if (PULLS.isEmpty()) return;
        var it = PULLS.iterator();
        while (it.hasNext()) {
            Pull pl = it.next();
            ItemEntity e = pl.item();
            if (e.isRemoved()) {
                it.remove();
                continue;
            }
            long age = e.level().getGameTime() - pl.start();
            var owner = e.level().getPlayerByUUID(pl.owner());
            if (owner == null || age > 80 || owner.distanceToSqr(e) > 32 * 32) {
                e.setNoGravity(false);
                it.remove();
                continue;
            }
            if (age < 6) continue; // the vanilla pop out of the crop
            Vec3 to = owner.position().add(0, owner.getBbHeight() * .4, 0).subtract(e.position());
            double d = to.length();
            e.setNoPickUpDelay();
            if (d < .8) {
                // arrived: picked up on touch, or (inventory full) left to fall where you stand
                e.setNoGravity(false);
                e.setDeltaMovement(Vec3.ZERO);
                it.remove();
                continue;
            }
            e.setNoGravity(true);
            double speed = Math.min(.9, .12 + .05 * (age - 6));
            e.setDeltaMovement(to.scale(Math.min(speed, d) / d));
            e.hasImpulse = true;
        }
    }

    /** A crop ready to harvest: a fully grown crop, nether wart or cocoa, or a melon/pumpkin. */
    private static boolean ripe(net.minecraft.world.level.block.state.BlockState s) {
        var b = s.getBlock();
        if (com.ethermod.util.FoliageHelper.extraRipe(s)) return true;
        if (b instanceof net.minecraft.world.level.block.CropBlock crop) return crop.isMaxAge(s);
        if (b instanceof net.minecraft.world.level.block.NetherWartBlock)
            return s.getValue(net.minecraft.world.level.block.NetherWartBlock.AGE) >= 3;
        if (b instanceof net.minecraft.world.level.block.CocoaBlock)
            return s.getValue(net.minecraft.world.level.block.CocoaBlock.AGE) >= 2;
        return s.is(net.minecraft.world.level.block.Blocks.MELON)
                || s.is(net.minecraft.world.level.block.Blocks.PUMPKIN);
    }

}
