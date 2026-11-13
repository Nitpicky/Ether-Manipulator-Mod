package com.mattermod.beam;

import com.mattermod.item.MatterManipulatorItem;
import com.mattermod.item.OrbMode;
import com.mattermod.manipulator.Manipulator;
import com.mattermod.trait.RedesignEffects;
import com.mattermod.trait.Traits;
import com.mattermod.trait.WorldEffects;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
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
 *   <li>Gravitic: yank the creature or item you aim at toward you.
 *   <li>Botanic: a poison puff in front of you.
 *   <li>Tectonic: grab the block you aim at (hold to sweep up more); press again to set it down.
 *   <li>Mechatronic: orders for the Golem ({@link com.mattermod.entity.GolemControl}).
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

    public static final double BEACON_RANGE = 48;
    public static final double PULL_RANGE = 24;

    /** LMB pressed in a non-Beam mode. */
    public static void press(ServerPlayer p) {
        ItemStack st = Manipulator.stack(p);
        OrbMode mode = OrbMode.get(st);
        stop(p);
        // Detonator: left click again while your left-click shot is in the air bursts it
        if (BeamBallEntity.detonateFor(p, st, true) || GustGrenade.detonateFor(p, st))
            return;
        switch (mode) {
            case WATER, LAVA -> {
                if (mode.tankLevel(st) < mode.shotCost(st, 0)) {
                    empty(p);
                    return;
                }
                HOLDS.put(p.getUUID(), new Hold(mode));
            }
            case GUST -> {
                if (ready(p, 10) && spend(p, st, OrbMode.GRENADE_COST))
                    GustGrenade.throwFrom(p, st);
            }
            case LIGHT -> {
                HOLDS.put(p.getUUID(), new Hold(mode));
                // others see the beacon as a beam in the Light colour, like the holder does
                com.mattermod.event.ModEvents.showBeam(p, true);
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
            case GOLEM -> com.mattermod.entity.GolemControl.leftClick(p);
            default -> {}
        }
    }

    /** LMB released: the charged left clicks fire now. */
    public static void release(ServerPlayer p) {
        Hold h = stop(p);
        // Skyhook: letting go of left click anchors the flying hook in the air where it is
        if (h != null && h.mode == OrbMode.TETHER) {
            var hook = OrbTether.activeHook(p);
            if (hook != null && !hook.isStuck() && Traits.has(Manipulator.stack(p), "mobility.skyhook"))
                OrbTether.hookAir(hook);
            return;
        }
        if (h == null || !h.mode.chargesOnLeft()) return;
        ItemStack st = Manipulator.stack(p);
        if (OrbMode.get(st) != h.mode || !Manipulator.isSummoned(p)) return;
        int phase = Math.min(2, h.ticks * 3 / Traits.chargeTicks(st));
        MatterManipulatorItem.fireVolley(p.level(), p, st, phase, h.mode, true);
    }

    /** Ends any held left click (the Beacon's lights go out with it). */
    private static Hold stop(ServerPlayer p) {
        var lit = LIT.remove(p.getUUID());
        if (lit != null) unlight(p.serverLevel(), lit, java.util.Set.of());
        Hold h = HOLDS.remove(p.getUUID());
        if (h != null && h.mode == OrbMode.LIGHT) com.mattermod.event.ModEvents.showBeam(p, false);
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
        p.displayClientMessage(
                Component.translatable("message.mattermod.not_enough_matter")
                        .withStyle(ChatFormatting.RED),
                true);
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
                    }
                });
        LIT.put(p.getUUID(), lit);
        if (ticks % 4 != 0) return;
        // creatures in the light are outlined for a moment
        for (var e :
                sl.getEntitiesOfClass(
                        LivingEntity.class,
                        new AABB(eye, end).inflate(1),
                        e -> e != p && e.getBoundingBox().inflate(0.3).clip(eye, end).isPresent()))
            e.addEffect(new MobEffectInstance(MobEffects.GLOWING, 30, 0, false, false));
    }

    // ---- Gravitic: pull -------------------------------------------------------------

    /** Yanks the creature or item under the crosshair toward you. */
    private static void pull(ServerPlayer p) {
        ServerLevel sl = p.serverLevel();
        Vec3 eye = p.getEyePosition(), look = p.getLookAngle();
        var wall =
                sl.clip(
                        new ClipContext(
                                eye,
                                eye.add(look.scale(PULL_RANGE)),
                                ClipContext.Block.COLLIDER,
                                ClipContext.Fluid.NONE,
                                p));
        Vec3 end = wall.getLocation();
        Entity target = null;
        double best = Double.MAX_VALUE;
        for (Entity e : sl.getEntities(p, new AABB(eye, end).inflate(1.5))) {
            boolean pullable =
                    e instanceof ItemEntity
                            || e instanceof ExperienceOrb
                            || e instanceof LivingEntity le
                                    && RedesignEffects.enemy(p, le)
                                    && !e.getType()
                                            .is(net.neoforged.neoforge.common.Tags.EntityTypes.BOSSES);
            if (!pullable || e.isSpectator()) continue;
            // small things are easier to catch
            double grow = e instanceof LivingEntity ? 0.3 : 0.6;
            var through = e.getBoundingBox().inflate(grow).clip(eye, end);
            if (through.isEmpty()) continue;
            double d = through.get().distanceToSqr(eye);
            if (d < best) {
                best = d;
                target = e;
            }
        }
        Vec3 to = target == null ? end : target.position().add(0, target.getBbHeight() * .5, 0);
        // the Tether rope shoots out to it (drawn on the clients: LeftClickFx)
        LeftClickFxData.send(
                sl,
                com.mattermod.network.payload.LeftClickFxPayload.PULL,
                0,
                p.getId(),
                to,
                target == null ? -1 : target.getId());
        sl.playSound(
                null,
                p.getX(),
                p.getEyeY(),
                p.getZ(),
                SoundEvents.FISHING_BOBBER_RETRIEVE,
                SoundSource.PLAYERS,
                0.8f,
                target == null ? 0.7f : 1.2f);
        if (target == null) return;
        Vec3 flat = new Vec3(p.getX() - target.getX(), 0, p.getZ() - target.getZ());
        double dist = flat.length();
        if (dist < 1.2) return;
        double dy = p.getY() - target.getY();
        Vec3 v;
        if (target instanceof LivingEntity le) {
            // ground friction eats most of it: lands a couple of blocks in front of you
            v =
                    flat.normalize()
                            .scale(Math.min(1.8, 0.3 + dist * 0.06))
                            .add(0, Math.max(0.3, Math.min(0.9, 0.42 + dy * 0.05)), 0);
            RedesignEffects.impulse(le, v);
        } else {
            v =
                    flat.normalize()
                            .scale(Math.min(2, dist * 0.055))
                            .add(0, Math.max(0.2, Math.min(0.8, 0.3 + dy * 0.05)), 0);
            target.setDeltaMovement(v);
            target.hurtMarked = true;
        }
        pulls++;
    }

    /** For the playtest: successful pulls so far. */
    public static int pulls;

    // ---- Botanic: poison puff --------------------------------------------------------

    public static final double PUFF_RANGE = 3.5;

    /** A small cloud a few blocks ahead: Poison I for 4 s to anything hostile inside, for 2 s. */
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
        boolean overgrowth = Traits.has(st, "cultivation.overgrowth");
        // find everything first, then cut it in one sweep from right to left (the animation's
        // swing), a column every tick, so the harvest reads as a sickle pass
        var cut = new java.util.ArrayList<BlockPos>();
        while (!todo.isEmpty() && cut.size() < 256) {
            BlockPos q = todo.poll();
            var state = sl.getBlockState(q);
            if (!ripe(state) || !sl.mayInteract(p, q)) continue;
            cut.add(q);
            if (overgrowth)
                for (BlockPos n : BlockPos.betweenClosed(q.offset(-1, -1, -1), q.offset(1, 1, 1))) {
                    var s = sl.getBlockState(n);
                    if (s.getBlock() == state.getBlock() && ripe(s) && seen.add(n.immutable()))
                        todo.add(n.immutable());
                }
        }
        if (cut.isEmpty()) return;
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
                SoundEvents.PLAYER_ATTACK_SWEEP,
                SoundSource.PLAYERS,
                0.5f,
                1.4f);
    }

    /** One crop of a harvest sweep: broken the way you would break it, with a cut across it. */
    private static void cutCrop(ServerPlayer p, BlockPos q, Vec3 right) {
        if (p.isRemoved() || !(p.level() instanceof ServerLevel sl) || !sl.isLoaded(q)) return;
        var state = sl.getBlockState(q);
        if (!ripe(state) || !sl.mayInteract(p, q)) return;
        harvesting = p;
        try {
            if (!p.gameMode.destroyBlock(q)) return;
        } finally {
            harvesting = null;
        }
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
                com.mattermod.network.payload.LeftClickFxPayload.CUT,
                0,
                p.getId(),
                new Vec3(q.getX() + .5, q.getY() + .3, q.getZ() + .5),
                (int) Math.round(Math.toDegrees(Math.atan2(sweep.z, sweep.x)) * 10));
    }

    /** The player whose Botanic harvest is breaking blocks right now (drops get pulled to them). */
    public static ServerPlayer harvesting;

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
        if (b instanceof net.minecraft.world.level.block.CropBlock crop) return crop.isMaxAge(s);
        if (b instanceof net.minecraft.world.level.block.NetherWartBlock)
            return s.getValue(net.minecraft.world.level.block.NetherWartBlock.AGE) >= 3;
        if (b instanceof net.minecraft.world.level.block.CocoaBlock)
            return s.getValue(net.minecraft.world.level.block.CocoaBlock.AGE) >= 2;
        return s.is(net.minecraft.world.level.block.Blocks.MELON)
                || s.is(net.minecraft.world.level.block.Blocks.PUMPKIN);
    }

    private static void puff(ServerPlayer p) {
        ServerLevel sl = p.serverLevel();
        Vec3 eye = p.getEyePosition(), look = p.getLookAngle();
        var hit =
                sl.clip(
                        new ClipContext(
                                eye,
                                eye.add(look.scale(PUFF_RANGE)),
                                ClipContext.Block.COLLIDER,
                                ClipContext.Fluid.NONE,
                                p));
        Vec3 at = hit.getLocation().subtract(look.scale(0.4));
        sl.playSound(
                null,
                at.x,
                at.y,
                at.z,
                SoundEvents.PUFFER_FISH_BLOW_OUT,
                SoundSource.PLAYERS,
                0.8f,
                1.3f);
        // a Growth pollen cloud, drawn on the clients like the right-click one (LeftClickFx)
        LeftClickFxData.send(
                sl, com.mattermod.network.payload.LeftClickFxPayload.PUFF, 0, p.getId(), at, 0);
        for (int k = 0; k < 8; k++) {
            WorldEffects.schedule(
                    sl,
                    k * 5,
                    () -> {
                        for (var e :
                                sl.getEntitiesOfClass(
                                        LivingEntity.class,
                                        new AABB(at, at).inflate(1.6),
                                        e -> RedesignEffects.enemy(p, e)))
                            e.addEffect(new MobEffectInstance(MobEffects.POISON, 80, 0));
                    });
        }
    }
}
