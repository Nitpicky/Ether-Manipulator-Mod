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

    private record TimedLight(BlockPos pos, long expires) {}

    private record Well(Vec3 center, long expires, Entity owner) {}

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
    private static final Map<ServerLevel, List<Well>> WELLS = new HashMap<>();

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

    /** Pulls creatures and items toward a point for a while (Gravity Well). */
    public static void gravityWell(ServerLevel level, Vec3 at, int ticks, Entity owner) {
        WELLS.computeIfAbsent(level, k -> new ArrayList<>())
                .add(new Well(at, level.getGameTime() + ticks, owner));
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

    /** Incendiary: fire in a 4-point star (a plus sign) with arms {@code arm} blocks long. */
    public static void fireStar(ServerLevel level, BlockPos center, Entity owner, int arm) {
        placeFire(level, center, owner);
        for (net.minecraft.core.Direction d : net.minecraft.core.Direction.Plane.HORIZONTAL) {
            for (int i = 1; i <= arm; i++) {
                BlockPos p = center.relative(d, i);
                // follow the ground a block up or down
                if (!placeFire(level, p, owner) && !placeFire(level, p.above(), owner))
                    placeFire(level, p.below(), owner);
            }
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
     * never spreads and burns out after 30 s (10 s at II).
     */
    public static boolean placeFire(ServerLevel level, BlockPos pos, Entity owner) {
        if (owner instanceof ServerPlayer sp && !level.mayInteract(sp, pos)) return false;
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
                            new Fire(sp.getUUID(), now + (burn >= 2 ? 200 : 600), burn > 0));
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

    /** True if the fire or lava touching this box was set by that player. */
    public static boolean ownedNear(ServerLevel level, AABB box, java.util.UUID player) {
        Map<BlockPos, Fire> m = FIRES.get(level);
        if (m == null || m.isEmpty()) return false;
        for (BlockPos p :
                BlockPos.betweenClosed(
                        BlockPos.containing(box.minX, box.minY, box.minZ),
                        BlockPos.containing(box.maxX, box.maxY, box.maxZ))) {
            Fire f = m.get(p);
            if (f != null && f.owner().equals(player)) return true;
        }
        return false;
    }

    /** Puts out a player's own controlled fire (Controlled Burn III: beaming it). */
    public static boolean putOut(ServerLevel level, BlockPos pos, java.util.UUID player) {
        Map<BlockPos, Fire> m = FIRES.get(level);
        Fire f = m == null ? null : m.get(pos);
        if (f == null
                || !f.owner().equals(player)
                || !level.getBlockState(pos).is(net.minecraft.tags.BlockTags.FIRE)) return false;
        level.removeBlock(pos, false);
        m.remove(pos);
        level.playSound(
                null,
                pos,
                net.minecraft.sounds.SoundEvents.FIRE_EXTINGUISH,
                net.minecraft.sounds.SoundSource.BLOCKS,
                0.4f,
                1.6f);
        return true;
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
    // Scheduled work (Echo, Obsidian Cast, Wave)
    // =========================================================================

    private record Task(long at, Runnable run) {}

    private static final Map<ServerLevel, List<Task>> TASKS = new HashMap<>();

    /**
     * Runs {@code r} on the server after {@code delay} ticks (dropped if the server stops first).
     */
    public static void schedule(ServerLevel level, int delay, Runnable r) {
        TASKS.computeIfAbsent(level, k -> new ArrayList<>())
                .add(new Task(level.getGameTime() + delay, r));
    }

    /**
     * Wave: a 3-wide roll of water moving along the ground from {@code start}, one block every 3
     * ticks, shoving creatures and items along. Each block of it drains away a moment later.
     */
    public static void wave(
            ServerLevel level,
            BlockPos start,
            net.minecraft.core.Direction dir,
            Entity owner,
            int steps) {
        net.minecraft.core.Direction side = dir.getClockWise();
        for (int i = 0; i < steps; i++) {
            final int step = i;
            schedule(
                    level,
                    3 * i,
                    () -> {
                        BlockPos c = start.relative(dir, step);
                        for (int w = -1; w <= 1; w++) {
                            BlockPos q = c.relative(side, w);
                            if (!level.getBlockState(q).isAir()) q = q.above();
                            else if (level.getBlockState(q.below()).isAir()) q = q.below();
                            if (owner instanceof ServerPlayer sp && !level.mayInteract(sp, q))
                                continue;
                            if (level.getBlockState(q).isAir())
                                tempBlock(level, q, Blocks.WATER.defaultBlockState(), 8);
                            for (Entity e :
                                    level.getEntities(
                                            owner,
                                            new AABB(q).inflate(0.6),
                                            e ->
                                                    e instanceof LivingEntity
                                                            || e instanceof ItemEntity
                                                            || e instanceof ExperienceOrb)) {
                                e.setDeltaMovement(
                                        e.getDeltaMovement()
                                                .scale(0.3)
                                                .add(
                                                        dir.getStepX() * 0.9,
                                                        0.25,
                                                        dir.getStepZ() * 0.9));
                                e.hurtMarked = true;
                                e.clearFire();
                            }
                            level.sendParticles(
                                    net.minecraft.core.particles.ParticleTypes.SPLASH,
                                    q.getX() + 0.5,
                                    q.getY() + 1,
                                    q.getZ() + 0.5,
                                    6,
                                    0.4,
                                    0.2,
                                    0.4,
                                    0.1);
                        }
                        if (step % 3 == 0)
                            level.playSound(
                                    null,
                                    c,
                                    net.minecraft.sounds.SoundEvents.PLAYER_SPLASH_HIGH_SPEED,
                                    net.minecraft.sounds.SoundSource.PLAYERS,
                                    0.4f,
                                    1.2f);
                    });
        }
    }

    // =========================================================================
    // Gravitic: Hammer Throw
    // =========================================================================

    private record Thrown(
            java.lang.ref.WeakReference<net.minecraft.world.entity.LivingEntity> mob,
            ServerPlayer by,
            long since) {}

    private static final Map<ServerLevel, List<Thrown>> THROWN = new HashMap<>();

    /** A mob flung by Hammer Throw: it gets hurt by whatever it slams into. */
    public static void thrown(
            ServerLevel level, net.minecraft.world.entity.LivingEntity mob, ServerPlayer by) {
        THROWN.computeIfAbsent(level, k -> new ArrayList<>())
                .add(new Thrown(new java.lang.ref.WeakReference<>(mob), by, level.getGameTime()));
    }

    private static void tickThrown(ServerLevel level, long now) {
        List<Thrown> list = THROWN.get(level);
        if (list == null || list.isEmpty()) return;
        list.removeIf(
                t -> {
                    var mob = t.mob().get();
                    if (mob == null || !mob.isAlive() || now - t.since() > 60) return true;
                    if (now - t.since() < 2) return false;
                    if (mob.horizontalCollision || mob.onGround()) {
                        double speed = mob.getDeltaMovement().length();
                        float dmg = (float) (6 + speed * 6);
                        mob.hurt(level.damageSources().playerAttack(t.by()), dmg);
                        level.sendParticles(
                                net.minecraft.core.particles.ParticleTypes.EXPLOSION,
                                mob.getX(),
                                mob.getY() + 0.5,
                                mob.getZ(),
                                1,
                                0,
                                0,
                                0,
                                0);
                        level.playSound(
                                null,
                                mob.blockPosition(),
                                net.minecraft.sounds.SoundEvents.MACE_SMASH_GROUND,
                                net.minecraft.sounds.SoundSource.PLAYERS,
                                0.8f,
                                1f);
                        return true;
                    }
                    return false;
                });
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
                if (level.isLoaded(e.getKey())
                        && level.getBlockState(e.getKey()).is(Blocks.LIGHT)) {
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
        tickThrown(level, now);
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

        List<Well> wells = WELLS.get(level);
        if (wells != null && !wells.isEmpty()) {
            wells.removeIf(w -> w.expires() <= now);
            for (Well w : wells) {
                for (Entity e :
                        level.getEntities(
                                (Entity) null,
                                new AABB(w.center(), w.center()).inflate(5),
                                e ->
                                        e != w.owner()
                                                && (e instanceof LivingEntity
                                                        || e instanceof ItemEntity
                                                        || e instanceof ExperienceOrb)
                                                && !(e instanceof ServerPlayer))) {
                    Vec3 d = w.center().subtract(e.position());
                    if (d.lengthSqr() < 0.25) continue;
                    e.setDeltaMovement(
                            e.getDeltaMovement().scale(0.6).add(d.normalize().scale(0.25)));
                    e.hurtMarked = true;
                }
                if (now % 4 == 0) {
                    level.sendParticles(
                            net.minecraft.core.particles.ParticleTypes.REVERSE_PORTAL,
                            w.center().x,
                            w.center().y,
                            w.center().z,
                            8,
                            1.5,
                            1.5,
                            1.5,
                            0.05);
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
        WELLS.clear();
        TASKS.clear();
        FIRES.clear();
        THROWN.clear();
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
