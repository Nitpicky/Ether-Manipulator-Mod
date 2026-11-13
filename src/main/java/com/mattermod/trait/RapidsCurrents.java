package com.mattermod.trait;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

import java.util.*;

/**
 * Temporary flow vectors: water simulation and rendering read the same current. No blocks are
 * replaced.
 */
public final class RapidsCurrents {
    private RapidsCurrents() {}

    private record Current(Vec3 direction, long expires) {}

    private static final Map<Level, Map<BlockPos, Current>> CURRENTS =
            Collections.synchronizedMap(new WeakHashMap<>());
    public static java.util.function.Supplier<Level> clientLevel = () -> null;

    public static Vec3 flow(BlockGetter view, BlockPos pos) {
        Level level =
                view instanceof Level l
                        ? l
                        : view instanceof net.minecraft.world.level.BlockAndTintGetter
                                ? clientLevel.get()
                                : null;
        if (level == null) return null;
        var map = CURRENTS.get(level);
        var c = map == null ? null : map.get(pos);
        return c != null && c.expires() > level.getGameTime() ? c.direction() : null;
    }

    public static void receive(
            Level level, List<BlockPos> positions, Vec3 direction, long expires) {
        var map =
                CURRENTS.computeIfAbsent(
                        level, k -> new java.util.concurrent.ConcurrentHashMap<>());
        for (var pos : positions) {
            map.put(pos.immutable(), new Current(direction, expires));
            if (level.isClientSide) {
                var state = level.getBlockState(pos);
                level.sendBlockUpdated(pos, state, state, 8);
            }
        }
    }

    public static void redirect(ServerLevel level, Vec3 at, Vec3 travel, int width) {
        Vec3 dir = travel.multiply(1, 0, 1);
        if (dir.lengthSqr() < .0001) return;
        dir = dir.normalize();
        Vec3 side = new Vec3(-dir.z, 0, dir.x);
        long now = level.getGameTime(), expires = now + 100;
        var map =
                CURRENTS.computeIfAbsent(
                        level, k -> new java.util.concurrent.ConcurrentHashMap<>());
        var cells = new ArrayList<BlockPos>();
        var center = BlockPos.containing(at);
        for (var pos : BlockPos.betweenClosed(center.offset(-4, -2, -4), center.offset(4, 1, 4))) {
            if (!level.hasChunkAt(pos) || !level.getFluidState(pos).is(FluidTags.WATER)) continue;
            Vec3 offset =
                    Vec3.atCenterOf(pos).subtract(at).subtract(side.scale(width % 2 == 0 ? .5 : 0));
            // 6 long: 3 behind and 3 ahead of where the shot touched the water
            if (Math.abs(offset.dot(dir)) > 3 || Math.abs(offset.dot(side)) > width * .5 + .01)
                continue;
            var previous = map.get(pos);
            if (previous != null
                    && previous.expires() > now + 80
                    && previous.direction().normalize().dot(dir) > .999) continue;
            cells.add(pos.immutable());
        }
        if (cells.isEmpty()) return;
        // Vanilla applies its normal fluid-push calculation to this stronger directional current.
        Vec3 current = dir.scale(4);
        receive(level, cells, current, expires);
        net.neoforged.neoforge.network.PacketDistributor.sendToPlayersNear(
                level,
                null,
                at.x,
                at.y,
                at.z,
                128,
                new com.mattermod.network.payload.ModulePayloads.WaterCurrent(
                        cells, (float) current.x, (float) current.z, expires));
    }

    /** Speed a surge carries things to (blocks per tick, about a boat on ice) and how fast. */
    public static final double SURGE_SPEED = .95, SURGE_ACCEL = .16;

    /**
     * The surge itself: vanilla's fluid push is normalised and tiny (and boats barely feel it), so
     * everything in a surge cell is driven along it directly, up to {@link #SURGE_SPEED}. Each side
     * moves what it simulates: the server its mobs, items and unridden boats, the client the local
     * player and whatever they ride or steer.
     */
    private static void surge(Level level, Map<BlockPos, Current> map) {
        if (map.isEmpty()) return;
        long now = level.getGameTime();
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (var pos : map.keySet()) {
            minX = Math.min(minX, pos.getX());
            minY = Math.min(minY, pos.getY());
            minZ = Math.min(minZ, pos.getZ());
            maxX = Math.max(maxX, pos.getX());
            maxY = Math.max(maxY, pos.getY());
            maxZ = Math.max(maxZ, pos.getZ());
        }
        var area = new net.minecraft.world.phys.AABB(minX, minY, minZ, maxX + 1, maxY + 2, maxZ + 1);
        for (var e : level.getEntitiesOfClass(net.minecraft.world.entity.Entity.class, area)) {
            if (e.isSpectator() || e.noPhysics) continue;
            boolean local = e.isControlledByLocalInstance();
            if (level.isClientSide ? !local : local || e instanceof net.minecraft.world.entity.player.Player
                    || e.getControllingPassenger() instanceof net.minecraft.world.entity.player.Player)
                continue;
            if (e.isPassenger()) continue; // the vehicle carries them
            BlockPos at = e.blockPosition();
            Current c = map.get(at);
            if (c == null || c.expires() <= now) c = map.get(at.below());
            if (c == null || c.expires() <= now) continue;
            Vec3 dir = c.direction().multiply(1, 0, 1);
            if (dir.lengthSqr() < 1e-6) continue;
            dir = dir.normalize();
            Vec3 v = e.getDeltaMovement();
            double along = v.x * dir.x + v.z * dir.z;
            double push = Math.max(0, Math.min(SURGE_ACCEL, SURGE_SPEED - along));
            // sideways drift is damped a little so the surge carries things in a line
            Vec3 sideways = new Vec3(v.x - dir.x * along, 0, v.z - dir.z * along);
            e.setDeltaMovement(
                    v.x + dir.x * push - sideways.x * .08,
                    v.y,
                    v.z + dir.z * push - sideways.z * .08);
            if (!level.isClientSide) e.hurtMarked = true;
        }
    }

    @SubscribeEvent
    public static void tick(LevelTickEvent.Post event) {
        var level = event.getLevel();
        var map = CURRENTS.get(level);
        if (map == null) return;
        surge(level, map);
        var iterator = map.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            if (entry.getValue().expires() > level.getGameTime()) continue;
            if (level.isClientSide) {
                var state = level.getBlockState(entry.getKey());
                level.sendBlockUpdated(entry.getKey(), state, state, 8);
            }
            iterator.remove();
        }
    }

    @SubscribeEvent
    public static void stop(net.neoforged.neoforge.event.server.ServerStoppingEvent event) {
        CURRENTS.clear();
    }
}
