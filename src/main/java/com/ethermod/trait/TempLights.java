package com.ethermod.trait;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LightBlock;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/** Invisible light blocks (Lit Path, Photonic, plain Light orbs) that remove themselves. */
public final class TempLights {

    private TempLights() {}

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

    /** Per-level tick: sparkle every 10 ticks, expire every 20. */
    static void tick(ServerLevel level, long now) {
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
    }

    /** Don't leave temporary lights behind in the save. */
    static void clearOnStop() {
        LIGHTS.forEach(
                (level, m) ->
                        m.keySet()
                                .forEach(
                                        p -> {
                                            if (level.getBlockState(p).is(Blocks.LIGHT))
                                                level.removeBlock(p, false);
                                        }));
        LIGHTS.clear();
    }
}
