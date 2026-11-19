package com.ethermod.trait;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LightBlock;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Short-lived effects the orb leaves in the world: temporary lights (Lit Path, plain Light orbs)
 * and gravity wells. Server-side, ticked per level. Temporary lights still standing when the server
 * stops are removed then.
 */
public final class WorldEffects {

    private WorldEffects() {}

    /** A block placed for a while (Frost bridges), then put back as it was. */
    public static boolean showsExpiryCracks(
            net.minecraft.world.level.block.state.BlockState state) {
        return !state.is(net.minecraft.tags.BlockTags.FIRE);
    }

    private record TempBlock(
            net.minecraft.world.level.block.state.BlockState placed,
            net.minecraft.world.level.block.state.BlockState restore,
            long placedAt,
            long expires,
            int crackId,
            boolean holdWhileStood) {}

    private static final Map<ServerLevel, Map<BlockPos, TempBlock>> TEMP_BLOCKS = new HashMap<>();
    private static final Map<ServerLevel, Map<BlockPos, java.util.UUID>> BRIDGES = new HashMap<>();

    public static void markBridge(ServerLevel level, BlockPos pos, java.util.UUID bridge) {
        BRIDGES.computeIfAbsent(level, k -> new HashMap<>()).put(pos.immutable(), bridge);
    }

    public static boolean isAirPocket(ServerLevel level, BlockPos pos) {
        var map = TEMP_BLOCKS.get(level);
        var t = map == null ? null : map.get(pos);
        return t != null && t.placed().isAir() && t.restore().is(Blocks.WATER);
    }

    private static int nextCrackId = -0x4D4D0000;

    /**
     * Places {@code state} at {@code pos} for {@code ticks}, then restores what was there (air or
     * water). In its last 10 seconds it cracks visibly. Only replaces air, water sources, or
     * replaceable plants.
     */
    public static boolean tempBlock(
            ServerLevel level,
            BlockPos pos,
            net.minecraft.world.level.block.state.BlockState state,
            int ticks) {
        return tempBlock(level, pos, state, ticks, false);
    }

    /**
     * As above; with {@code holdWhileStood} it never starts cracking while a player stands on it
     * (Permafrost).
     */
    public static boolean tempBlock(
            ServerLevel level,
            BlockPos pos,
            net.minecraft.world.level.block.state.BlockState state,
            int ticks,
            boolean holdWhileStood) {
        var cur = level.getBlockState(pos);
        boolean waterSource = cur.is(Blocks.WATER);
        if (!cur.isAir() && !waterSource && !(cur.canBeReplaced() && cur.getFluidState().isEmpty()))
            return false;
        var restore = waterSource ? cur : Blocks.AIR.defaultBlockState();
        level.setBlock(pos, state, state.isAir() ? 2 : 3);
        long now = level.getGameTime();
        TEMP_BLOCKS
                .computeIfAbsent(level, k -> new HashMap<>())
                .put(
                        pos.immutable(),
                        new TempBlock(
                                state, restore, now, now + ticks, nextCrackId++, holdWhileStood));
        PendingReverts.add(level, pos, state, restore);
        return true;
    }

    public static boolean isTempBlock(ServerLevel level, BlockPos pos) {
        Map<BlockPos, TempBlock> m = TEMP_BLOCKS.get(level);
        return m != null && m.containsKey(pos);
    }

    private static final Map<ServerLevel, Map<BlockPos, Long>> LIGHTS = new HashMap<>();

    /** Places an invisible light that removes itself after {@code ticks}. */
    public static void tempLight(ServerLevel level, BlockPos pos, int brightness, int ticks) {
        var current = level.getBlockState(pos);
        if (!current.isAir() && !current.is(Blocks.LIGHT)) return;
        var lights = LIGHTS.computeIfAbsent(level, k -> new HashMap<>());
        if (!lights.containsKey(pos) && ticks > 20) {
            var nearby =
                    lights.entrySet().stream()
                            .filter(
                                    e ->
                                            e.getValue() > level.getGameTime()
                                                    && e.getKey().distSqr(pos) < 64 * 64)
                            .sorted(Map.Entry.comparingByValue())
                            .toList();
            if (nearby.size() >= 24) {
                var oldest = nearby.get(0).getKey();
                if (level.getBlockState(oldest).is(Blocks.LIGHT)) level.removeBlock(oldest, false);
                lights.remove(oldest);
                PendingReverts.remove(level, oldest);
            }
        }
        if (current.is(Blocks.LIGHT))
            brightness = Math.max(brightness, current.getValue(LightBlock.LEVEL));
        level.setBlockAndUpdate(
                pos,
                Blocks.LIGHT
                        .defaultBlockState()
                        .setValue(LightBlock.LEVEL, Math.min(15, brightness)));
        if (!current.is(Blocks.LIGHT))
            PendingReverts.add(level, pos, Blocks.LIGHT.defaultBlockState(), current);
        LIGHTS.computeIfAbsent(level, k -> new HashMap<>())
                .merge(pos.immutable(), level.getGameTime() + ticks, Math::max);
    }

    public static void forgetLight(ServerLevel level, BlockPos pos) {
        Map<BlockPos, Long> m = LIGHTS.get(level);
        if (m != null) m.remove(pos.immutable());
        PendingReverts.remove(level, pos);
    }

    /** A ring of fire of the given radius (1 = the 8 blocks around the centre). */
    public static void fireRing(ServerLevel level, BlockPos center, Entity owner, int radius) {
        for (BlockPos p :
                BlockPos.betweenClosed(
                        center.offset(-radius, -1, -radius), center.offset(radius, 1, radius))) {
            int dx = Math.abs(p.getX() - center.getX()), dz = Math.abs(p.getZ() - center.getZ());
            if (Math.max(dx, dz) != radius) continue;
            placeFire(level, p.immutable(), owner);
        }
    }

    // =========================================================================
    // Pyrotechnics: fire set by a player's orbs
    // =========================================================================

    /**
     * Fire (or lava) a player's orbs set: whose it is, and for Controlled Burn when it goes out.
     */
    private record Fire(java.util.UUID owner, long expires, boolean controlled) {}

    private static final Map<ServerLevel, Map<BlockPos, Fire>> FIRES = new HashMap<>();

    /**
     * Lights a fire like flint and steel would, remembering whose it is. With Controlled Burn it
     * never spreads and burns out after 30 s.
     */
    public static boolean placeFire(ServerLevel level, BlockPos pos, Entity owner) {
        if (!com.ethermod.config.ServerConfig.fireAndLava()) return false;
        if (owner instanceof ServerPlayer sp && !com.ethermod.util.Protect.may(level, sp, pos)) return false;
        if (!level.getBlockState(pos).isAir()
                || !BaseFireBlock.canBePlacedAt(level, pos, net.minecraft.core.Direction.UP))
            return false;
        boolean soul =
                owner instanceof ServerPlayer player
                        && Traits.has(
                                com.ethermod.manipulator.Manipulator.stack(player),
                                "pyrotechnic.soulwake");
        if (soul) tempBlock(level, pos, Blocks.SOUL_FIRE.defaultBlockState(), 600);
        else level.setBlockAndUpdate(pos, BaseFireBlock.getState(level, pos));
        if (owner instanceof ServerPlayer sp) {
            int burn =
                    Traits.has(
                                    com.ethermod.manipulator.Manipulator.stack(sp),
                                    "pyrotechnic.wildfire")
                            ? 0
                            : 1;
            long now = level.getGameTime();
            FIRES.computeIfAbsent(level, k -> new HashMap<>())
                    .put(
                            pos.immutable(),
                            new Fire(sp.getUUID(), now + 600, burn > 0));
        }
        return true;
    }

    public static void burnFromOwnedFire(ServerLevel level, BlockPos pos, Entity entity) {
        if (!(entity instanceof net.minecraft.world.entity.LivingEntity living)
                || entity.tickCount % 5 != 0) return;
        var map = FIRES.get(level);
        if (map == null) return;
        Fire fire = map.get(pos);
        if (fire == null) return;
        var owner = level.getServer().getPlayerList().getPlayer(fire.owner());
        if (owner != null && RedesignEffects.enemy(owner, living))
            RedesignEffects.burn(living, owner, 8, -1);
    }

    public static void burnFromOwnedLava(ServerLevel level, Entity entity) {
        if (!(entity instanceof net.minecraft.world.entity.LivingEntity)
                || entity.tickCount % 5 != 0) return;
        var map = FIRES.get(level);
        if (map == null || map.isEmpty()) return;
        var queue = new java.util.ArrayDeque<BlockPos>();
        var seen = new java.util.HashSet<BlockPos>();
        var box = entity.getBoundingBox();
        for (var at :
                BlockPos.betweenClosed(
                        BlockPos.containing(box.minX, box.minY, box.minZ),
                        BlockPos.containing(box.maxX, box.maxY, box.maxZ)))
            if (level.getFluidState(at).is(net.minecraft.tags.FluidTags.LAVA))
                queue.add(at.immutable());
        for (int n = 0; n < 64 && !queue.isEmpty(); n++) {
            var pos = queue.removeFirst();
            if (!seen.add(pos)) continue;
            if (map.containsKey(pos)) {
                burnFromOwnedFire(level, pos, entity);
                return;
            }
            int amount = level.getFluidState(pos).getAmount();
            for (var direction : net.minecraft.core.Direction.values()) {
                var next = pos.relative(direction);
                if (!level.isLoaded(next)) continue;
                var fluid = level.getFluidState(next);
                if (fluid.is(net.minecraft.tags.FluidTags.LAVA)
                        && fluid.getAmount() >= amount
                        && !seen.contains(next)) queue.add(next);
            }
        }
    }

    /** Remembers lava a player's orb placed, for Fire Ward. */
    public static void ownLava(ServerLevel level, BlockPos pos, Entity owner) {
        if (owner instanceof ServerPlayer sp) {
            FIRES.computeIfAbsent(level, k -> new HashMap<>())
                    .put(pos.immutable(), new Fire(sp.getUUID(), Long.MAX_VALUE - 1000, false));
        }
    }

    /**
     * Called from the fire block's tick (FireBlockMixin). For a controlled fire: keep it lit
     * without spreading until it burns out, and return true so vanilla's spreading tick is skipped.
     */
    public static boolean tickControlledFire(ServerLevel level, BlockPos pos) {
        Map<BlockPos, Fire> m = FIRES.get(level);
        Fire f = m == null ? null : m.get(pos);
        if (f == null || !f.controlled()) return false;
        var state = level.getBlockState(pos);
        if (level.getGameTime() >= f.expires() || !state.canSurvive(level, pos)) {
            level.removeBlock(pos, false);
            m.remove(pos);
        } else {
            level.scheduleTick(pos, state.getBlock(), 30 + level.random.nextInt(10));
        }
        return true;
    }

    // =========================================================================
    // Scheduled work
    // =========================================================================

    private record Task(long at, Runnable run, boolean runOnStop) {}

    private static final Map<ServerLevel, List<Task>> TASKS = new HashMap<>();

    /**
     * Runs {@code r} on the server after {@code delay} ticks (dropped if the server stops first).
     */
    public static void schedule(ServerLevel level, int delay, Runnable r) {
        schedule(level, delay, r, false);
    }

    /**
     * As {@link #schedule(ServerLevel, int, Runnable)}; with {@code runOnStop} the task runs early
     * when the server stops instead of being dropped (clean-ups that must not be skipped).
     */
    public static void schedule(ServerLevel level, int delay, Runnable r, boolean runOnStop) {
        TASKS.computeIfAbsent(level, k -> new ArrayList<>())
                .add(new Task(level.getGameTime() + delay, r, runOnStop));
    }

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        long now = level.getGameTime();

        Map<BlockPos, Long> lights = LIGHTS.get(level);
        if (lights != null && now % 10 == 0)
            for (var light : lights.entrySet()) {
                if (light.getValue() > now
                        && level.hasChunkAt(light.getKey())
                        && level.getBlockState(light.getKey()).is(Blocks.LIGHT)) {
                    var p = light.getKey();
                    level.sendParticles(
                            net.minecraft.core.particles.ParticleTypes.END_ROD,
                            p.getX() + .5,
                            p.getY() + .5,
                            p.getZ() + .5,
                            1,
                            .15,
                            .15,
                            .15,
                            .005);
                }
            }
        if (lights != null && now % 20 == 0) {
            Iterator<Map.Entry<BlockPos, Long>> it = lights.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<BlockPos, Long> e = it.next();
                if (e.getValue() > now) continue;
                // keep tracking it until its chunk is back, or the Light block stays forever
                if (!level.isLoaded(e.getKey())) continue;
                if (level.getBlockState(e.getKey()).is(Blocks.LIGHT)) {
                    level.removeBlock(e.getKey(), false);
                }
                PendingReverts.remove(level, e.getKey());
                it.remove();
            }
        }

        List<Task> tasks = TASKS.get(level);
        if (tasks != null && !tasks.isEmpty()) {
            List<Task> due = new ArrayList<>();
            tasks.removeIf(
                    t -> {
                        if (t.at() <= now) {
                            due.add(t);
                            return true;
                        }
                        return false;
                    });
            for (Task t : due) t.run().run();
        }
        Map<BlockPos, Fire> fires = FIRES.get(level);
        if (fires != null && now % 100 == 0)
            fires.entrySet()
                    .removeIf(
                            e ->
                                    e.getValue().expires() + 200 < now
                                            || level.isLoaded(e.getKey())
                                                    && !level.getBlockState(e.getKey())
                                                            .is(net.minecraft.tags.BlockTags.FIRE)
                                                    && !level.getFluidState(e.getKey())
                                                            .is(net.minecraft.tags.FluidTags.LAVA));

        var occupiedBridges = new java.util.HashSet<java.util.UUID>();
        var bridgeIds = BRIDGES.get(level);
        if (bridgeIds != null)
            for (var player : level.players()) {
                var feet =
                        player.position()
                                .add(
                                        com.ethermod.trait.MovementPhysics.vector(
                                                        com.ethermod.trait.MovementPhysics.down(
                                                                player))
                                                .scale(.1));
                var group = bridgeIds.get(BlockPos.containing(feet));
                if (group != null) occupiedBridges.add(group);
            }
        Map<BlockPos, TempBlock> temps = TEMP_BLOCKS.get(level);
        if (temps != null && !temps.isEmpty()) {
            Iterator<Map.Entry<BlockPos, TempBlock>> it = temps.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<BlockPos, TempBlock> e = it.next();
                BlockPos p = e.getKey();
                TempBlock t = e.getValue();
                if (!level.isLoaded(p)) continue;
                if (t.placed().isAir()
                        && t.restore().is(Blocks.WATER)
                        && level.getBlockState(p).is(Blocks.WATER))
                    level.setBlock(p, Blocks.AIR.defaultBlockState(), 2);
                boolean stillThere = level.getBlockState(p).is(t.placed().getBlock());
                if (!stillThere) {
                    level.destroyBlockProgress(t.crackId(), p, -1);
                    PendingReverts.remove(level, p);
                    it.remove();
                    continue;
                }
                long left = t.expires() - now;
                if (t.holdWhileStood()
                        && left <= 200
                        && ((bridgeIds != null && occupiedBridges.contains(bridgeIds.get(p)))
                                || !level.getEntitiesOfClass(
                                                net.minecraft.world.entity.player.Player.class,
                                                new AABB(p.above()).inflate(0.2, 0.5, 0.2))
                                        .isEmpty())) {
                    // Permafrost: someone is standing on it; it waits for them
                    if (left < 200) level.destroyBlockProgress(t.crackId(), p, -1);
                    e.setValue(
                            new TempBlock(
                                    t.placed(),
                                    t.restore(),
                                    t.placedAt(),
                                    now + 201,
                                    t.crackId(),
                                    true));
                    continue;
                }
                if (left <= 0) {
                    level.destroyBlockProgress(t.crackId(), p, -1);
                    if (showsExpiryCracks(t.placed()))
                        level.levelEvent(
                                2001, p, net.minecraft.world.level.block.Block.getId(t.placed()));
                    level.setBlockAndUpdate(p, t.restore());
                    PendingReverts.remove(level, p);
                    it.remove();
                } else if (left <= 200 && now % 10 == 0 && showsExpiryCracks(t.placed())) {
                    // cracks grow over the last 10 seconds
                    level.destroyBlockProgress(t.crackId(), p, (int) (9 - left * 9 / 200));
                }
            }
        }
    }

    /** Don't leave temporary lights behind in the save. */
    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        LIGHTS.forEach(
                (level, m) ->
                        m.keySet()
                                .forEach(
                                        p -> {
                                            if (level.getBlockState(p).is(Blocks.LIGHT))
                                                level.removeBlock(p, false);
                                        }));
        LIGHTS.clear();
        // clean-ups such as shot fluids drying up would otherwise leave permanent blocks
        TASKS.values()
                .forEach(
                        list ->
                                list.stream()
                                        .filter(Task::runOnStop)
                                        .forEach(
                                                t -> {
                                                    try {
                                                        t.run().run();
                                                    } catch (RuntimeException e) {
                                                        com.ethermod.EtherMod.LOGGER.warn(
                                                                "Clean-up task failed on stop", e);
                                                    }
                                                }));
        TASKS.clear();
        FIRES.clear();
        Ziplines.clear();
        TEMP_BLOCKS.forEach(
                (level, m) ->
                        m.forEach(
                                (p, t) -> {
                                    if (level.getBlockState(p).is(t.placed().getBlock()))
                                        level.setBlock(p, t.restore(), 3);
                                }));
        TEMP_BLOCKS.clear();
        BRIDGES.clear();
        // everything above was put back, so nothing is pending for the next start
        for (ServerLevel level : event.getServer().getAllLevels()) PendingReverts.clear(level);
    }
}
