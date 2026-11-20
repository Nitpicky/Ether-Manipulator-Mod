package com.ethermod.trait;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;

import java.util.HashMap;
import java.util.Map;

/**
 * Crash safety for temporary world edits (Frost bridges, air pockets, temporary lights).
 *
 * <p>Every temporary block is recorded here, per dimension, in the level's saved data together with
 * what it replaced. A normal revert removes the record. If the game crashes before the revert, the
 * record is saved alongside the chunk at the same autosave, so on the next start any block that is
 * still the one we placed is put back to what it was.
 */
public final class PendingReverts extends SavedData {

    private record Entry(BlockState placed, BlockState restore) {}

    private final Map<BlockPos, Entry> entries = new HashMap<>();

    public static PendingReverts get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(TYPE);
    }

    /** Records that {@code placed} now stands at {@code pos} and {@code restore} must come back. */
    public static void add(ServerLevel level, BlockPos pos, BlockState placed, BlockState restore) {
        PendingReverts data = get(level);
        // keep the oldest restore state if a temporary block is re-placed over another one
        Entry old = data.entries.get(pos);
        data.entries.put(pos.immutable(), new Entry(placed, old != null ? old.restore() : restore));
        data.setDirty();
    }

    /** The temporary block at {@code pos} was reverted (or is gone): forget it. */
    public static void remove(ServerLevel level, BlockPos pos) {
        PendingReverts data = get(level);
        if (data.entries.remove(pos) != null) data.setDirty();
    }

    /** Everything was reverted normally (clean shutdown). */
    public static void clear(ServerLevel level) {
        PendingReverts data = get(level);
        if (!data.entries.isEmpty()) {
            data.entries.clear();
            data.setDirty();
        }
    }

    /** After a crash: put back every recorded block that is still the one we placed. */
    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        for (ServerLevel level : event.getServer().getAllLevels()) {
            PendingReverts data = get(level);
            if (data.entries.isEmpty()) continue;
            data.entries.forEach(
                    (pos, e) -> {
                        if (level.getBlockState(pos).is(e.placed().getBlock()))
                            level.setBlock(pos, e.restore(), 3);
                    });
            data.entries.clear();
            data.setDirty();
        }
    }

    // --- persistence ---

    private static final com.mojang.serialization.Codec<Map.Entry<BlockPos, Entry>> ENTRY_CODEC =
            com.mojang.serialization.codecs.RecordCodecBuilder.create(
                    i ->
                            i.group(
                                            BlockPos.CODEC.fieldOf("pos").forGetter(Map.Entry::getKey),
                                            BlockState.CODEC
                                                    .fieldOf("placed")
                                                    .forGetter(e -> e.getValue().placed()),
                                            BlockState.CODEC
                                                    .fieldOf("restore")
                                                    .forGetter(e -> e.getValue().restore()))
                                    .apply(i, (pos, placed, restore) -> Map.entry(pos, new Entry(placed, restore))));

    private static final com.mojang.serialization.Codec<PendingReverts> CODEC =
            ENTRY_CODEC
                    .listOf()
                    .fieldOf("entries")
                    .codec()
                    .xmap(
                            list -> {
                                PendingReverts data = new PendingReverts();
                                list.forEach(e -> data.entries.put(e.getKey(), e.getValue()));
                                return data;
                            },
                            data -> java.util.List.copyOf(data.entries.entrySet()));

    private static final net.minecraft.world.level.saveddata.SavedDataType<PendingReverts> TYPE =
            new net.minecraft.world.level.saveddata.SavedDataType<>(
                    net.minecraft.resources.Identifier.fromNamespaceAndPath(
                            com.ethermod.EtherMod.MODID, "pending_reverts"),
                    PendingReverts::new,
                    CODEC);
}
