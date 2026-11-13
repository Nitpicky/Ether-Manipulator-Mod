package com.mattermod.trait;

import com.mattermod.manipulator.Manipulator;

import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.List;

/**
 * Mobility: Beacon Net. The lights your Light orbs leave are remembered on the Manipulator as
 * waypoints (position + dimension) and drawn through walls, for as long as the lights last: the
 * last 4, then 8 and 16 with rank.
 */
public final class BeaconNet {

    private BeaconNet() {}

    public static int max(ItemStack stack) {
        return 2 << Math.max(1, Traits.rank(stack, "photonic.beacon_net")); // 4, 8, 16
    }

    private static final String TAG = "mm_beacons";

    /** {@code expires} is the game time the light fades (0 = never). */
    public record Beacon(BlockPos pos, String dim, long expires) {
        public boolean same(Beacon o) {
            return pos.equals(o.pos) && dim.equals(o.dim);
        }
    }

    public static List<Beacon> read(ItemStack stack) {
        List<Beacon> out = new ArrayList<>();
        CompoundTag root =
                stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        ListTag list = root.getList(TAG, Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag t = list.getCompound(i);
            out.add(new Beacon(BlockPos.of(t.getLong("p")), t.getString("d"), t.getLong("e")));
        }
        return out;
    }

    private static void write(ItemStack stack, List<Beacon> beacons) {
        CompoundTag root =
                stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        ListTag list = new ListTag();
        for (Beacon b : beacons) {
            CompoundTag t = new CompoundTag();
            t.putLong("p", b.pos().asLong());
            t.putString("d", b.dim());
            t.putLong("e", b.expires());
            list.add(t);
        }
        if (list.isEmpty()) root.remove(TAG);
        else root.put(TAG, list);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(root));
    }

    private static String dim(Level level) {
        return level.dimension().location().toString();
    }

    /** Adds a waypoint that fades with its light; the oldest one drops off past the limit. */
    public static void add(ServerPlayer player, Level level, BlockPos pos, int lifeTicks) {
        ItemStack stack = Manipulator.stack(player);
        if (!Traits.has(stack, "photonic.beacon_net")) return;
        List<Beacon> list = read(stack);
        long now = level.getGameTime();
        Beacon b = new Beacon(pos.immutable(), dim(level), lifeTicks > 0 ? now + lifeTicks : 0);
        list.removeIf(x -> x.same(b) || x.expires() != 0 && x.expires() <= now);
        list.add(b);
        while (list.size() > max(stack)) list.remove(0);
        write(stack, list);
        Manipulator.sync(player);
    }

    /** Removes a waypoint (the light was toggled off). */
    public static void remove(ServerPlayer player, Level level, BlockPos pos) {
        ItemStack stack = Manipulator.stack(player);
        List<Beacon> list = read(stack);
        Beacon b = new Beacon(pos.immutable(), dim(level), 0);
        if (list.removeIf(x -> x.same(b))) {
            write(stack, list);
            Manipulator.sync(player);
        }
    }
}
