package com.ethermod.client;

import com.ethermod.config.ClientConfig;
import com.ethermod.item.EtherManipulatorItem;
import com.ethermod.manipulator.Manipulator;
import com.ethermod.trait.Traits;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * The Photonic glow that follows a player, drawn like the Dynamic Lights mods do: every player
 * (this one and the others in view) whose summoned Manipulator has the Photonic line equipped is a
 * moving light source of level 15 at the eyes, falling off by 1 per block of straight-line
 * distance. Nothing is placed in the world.
 *
 * <p>LevelRenderer.getLightColor (terrain, block entities, particles; Sodium's light cache reads
 * it too) and EntityRenderer.getBlockLightLevel (entities) raise their block light to the glow
 * there (PhotonicGlowMixin). Taking the max makes it safe next to another dynamic lights mod.
 * When a source moves (more than 1/4 block, at most every 2 ticks), appears or goes, the chunk
 * sections in its reach are rebuilt.
 *
 * <p>Another player's Manipulator comes from vanilla's equipment sync: while summoned, the virtual
 * hand (PlayerMixin) is that player's main-hand item, trait build included (custom data).
 */
@OnlyIn(Dist.CLIENT)
public final class PhotonicGlow {

    private PhotonicGlow() {}

    /** Light level at the source; it reaches 0 this many blocks away. */
    public static final double LUMINANCE = 15;

    private static final double RADIUS_SQ = LUMINANCE * LUMINANCE;
    /** A source that moved less than this (squared, 0.25 block) keeps its lit sections. */
    private static final double MOVE_SQ = 0.25 * 0.25;
    /** Ticks between two rebuilds for one source. */
    private static final int MIN_INTERVAL = 2;

    /** What the render threads read: the level and the sources' x, y, z. Null when none. */
    private record Snapshot(Level level, double[] xyz) {}

    private static volatile Snapshot snapshot;

    private static final class Source {
        double x, y, z;
        long rebuilt;

        Source(Vec3 at, long now) {
            x = at.x;
            y = at.y;
            z = at.z;
            rebuilt = now;
        }
    }

    private static final Map<Integer, Source> SOURCES = new HashMap<>();
    private static ClientLevel trackedLevel;
    private static long ticks;

    /** True while this player gives off the Photonic glow. */
    static boolean glows(Player p, Player local) {
        if (p.isSpectator() || !p.isAlive()) return false;
        ItemStack st;
        if (p == local) {
            if (!Manipulator.isSummoned(p)) return false;
            st = Manipulator.stack(p);
        } else {
            st = p.getMainHandItem();
            if (!(st.getItem() instanceof EtherManipulatorItem)) return false;
        }
        return Traits.hasLine(st, Traits.PHOTONIC);
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level != trackedLevel) clearCaches(); // the old level's sections are gone with it
        if (level == null) return;
        trackedLevel = level;
        ticks++;
        if (!ClientConfig.photonicDynamicLight()) {
            if (!SOURCES.isEmpty()) {
                List<double[]> dirty = new ArrayList<>();
                for (Source s : SOURCES.values()) dirty.add(new double[] {s.x, s.y, s.z});
                SOURCES.clear();
                snapshot = null;
                for (double[] d : dirty) markDirty(mc, level, d[0], d[1], d[2]);
            }
            return;
        }
        boolean changed = false;
        List<double[]> dirty = new ArrayList<>();
        java.util.Set<Integer> seen = new java.util.HashSet<>();
        for (Player p : level.players()) {
            if (!glows(p, mc.player)) continue;
            seen.add(p.getId());
            Vec3 at = p.getEyePosition();
            Source s = SOURCES.get(p.getId());
            if (s == null) {
                SOURCES.put(p.getId(), new Source(at, ticks));
                dirty.add(new double[] {at.x, at.y, at.z});
                changed = true;
            } else if (ticks - s.rebuilt >= MIN_INTERVAL
                    && at.distanceToSqr(s.x, s.y, s.z) > MOVE_SQ) {
                dirty.add(new double[] {s.x, s.y, s.z});
                dirty.add(new double[] {at.x, at.y, at.z});
                s.x = at.x;
                s.y = at.y;
                s.z = at.z;
                s.rebuilt = ticks;
                changed = true;
            }
        }
        for (Iterator<Map.Entry<Integer, Source>> it = SOURCES.entrySet().iterator(); it.hasNext(); ) {
            var e = it.next();
            if (seen.contains(e.getKey())) continue;
            Source s = e.getValue();
            dirty.add(new double[] {s.x, s.y, s.z});
            it.remove();
            changed = true;
        }
        if (!changed) return;
        // publish first, so the rebuilds below already read the new positions
        if (SOURCES.isEmpty()) snapshot = null;
        else {
            double[] xyz = new double[SOURCES.size() * 3];
            int i = 0;
            for (Source s : SOURCES.values()) {
                xyz[i++] = s.x;
                xyz[i++] = s.y;
                xyz[i++] = s.z;
            }
            snapshot = new Snapshot(level, xyz);
        }
        for (double[] d : dirty) markDirty(mc, level, d[0], d[1], d[2]);
    }

    /** Rebuilds the chunk sections a source at x, y, z reaches. */
    private static void markDirty(Minecraft mc, ClientLevel level, double x, double y, double z) {
        if (mc.levelRenderer == null) return;
        int x0 = Mth.floor(x - LUMINANCE) >> 4,
                x1 = Mth.floor(x + LUMINANCE) >> 4,
                y0 = Math.max(Mth.floor(y - LUMINANCE) >> 4, level.getMinSection()),
                y1 = Math.min(Mth.floor(y + LUMINANCE) >> 4, level.getMaxSection() - 1),
                z0 = Mth.floor(z - LUMINANCE) >> 4,
                z1 = Mth.floor(z + LUMINANCE) >> 4;
        for (int sx = x0; sx <= x1; sx++)
            for (int sy = y0; sy <= y1; sy++)
                for (int sz = z0; sz <= z1; sz++) mc.levelRenderer.setSectionDirty(sx, sy, sz);
    }

    /** Forgets every source (level change, disconnect); the next level rebuilds its own sections. */
    public static void clearCaches() {
        SOURCES.clear();
        snapshot = null;
        trackedLevel = null;
    }

    // ---- reads (render threads, chunk builders) ------------------------------------------

    /** The glow at a point, 0 to 15 (fractional), or 0 with no source near. */
    public static double level(double px, double py, double pz) {
        Snapshot s = snapshot;
        return s == null ? 0 : level(s.xyz, px, py, pz);
    }

    private static double level(double[] xyz, double px, double py, double pz) {
        double best = 0;
        for (int i = 0; i < xyz.length; i += 3) {
            double dx = px - xyz[i];
            if (dx > LUMINANCE || dx < -LUMINANCE) continue;
            double dy = py - xyz[i + 1];
            if (dy > LUMINANCE || dy < -LUMINANCE) continue;
            double dz = pz - xyz[i + 2];
            if (dz > LUMINANCE || dz < -LUMINANCE) continue;
            double d2 = dx * dx + dy * dy + dz * dz;
            if (d2 >= RADIUS_SQ) continue;
            double l = LUMINANCE - Math.sqrt(d2);
            if (l > best) best = l;
        }
        return best;
    }

    /** LevelRenderer.getLightColor: the packed light with its block part raised to the glow. */
    public static int lightColor(BlockAndTintGetter getter, BlockPos pos, int packed) {
        Snapshot s = snapshot;
        if (s == null) return packed;
        // another world drawn through the same method (a mod's preview scene) has no glow
        if (getter instanceof Level l && l != s.level) return packed;
        int block = (packed & 0xFFFF) >> 4;
        if (block >= 15) return packed;
        int glow = (int) level(s.xyz, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
        if (glow <= block) return packed;
        return (packed & 0xFFFF0000) | (glow << 4);
    }

    /** EntityRenderer.getBlockLightLevel: an entity's block light raised to the glow. */
    public static int entityLight(Entity entity, BlockPos pos, int light) {
        Snapshot s = snapshot;
        if (s == null || light >= 15 || entity.level() != s.level) return light;
        return Math.max(light, (int) level(s.xyz, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5));
    }

    /** Playtest: whether this entity id is a glow source now. */
    public static boolean isSource(int entityId) {
        return SOURCES.containsKey(entityId);
    }

    /** Playtest: how many sources are lit now. */
    public static int sourceCount() {
        Snapshot s = snapshot;
        return s == null ? 0 : s.xyz.length / 3;
    }
}
