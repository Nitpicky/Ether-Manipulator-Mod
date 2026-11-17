package com.ethermod.loot;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;
import net.minecraft.world.phys.Vec3;

import net.neoforged.neoforge.common.loot.IGlobalLootModifier;
import net.neoforged.neoforge.common.loot.LootModifier;

import java.util.List;

/**
 * Adds an Enchanted Gemstone to a structure chest. {@code chance}: the odds for any chest. {@code
 * guarantee_in}: structures where the first chest opened in each one always holds a gem
 * ({@link GemGuarantees} remembers which structures already gave theirs). {@code hidden_chest}:
 * the guarantee only goes to a chest next to sticky pistons (the Jungle Pyramid's lever-puzzle
 * chest).
 */
public final class GemLootModifier extends LootModifier {
    public static final MapCodec<GemLootModifier> CODEC =
            RecordCodecBuilder.mapCodec(
                    inst ->
                            codecStart(inst)
                                    .and(
                                            inst.group(
                                                    BuiltInRegistries.ITEM.byNameCodec().fieldOf("gem").forGetter(m -> m.gem),
                                                    Codec.FLOAT.optionalFieldOf("chance", 0f).forGetter(m -> m.chance),
                                                    ResourceLocation.CODEC.listOf().optionalFieldOf("guarantee_in", List.of()).forGetter(m -> m.guaranteeIn),
                                                    Codec.BOOL.optionalFieldOf("hidden_chest", false).forGetter(m -> m.hidden)))
                                    .apply(inst, GemLootModifier::new));

    private final Item gem;
    private final float chance;
    private final List<ResourceLocation> guaranteeIn;
    private final boolean hidden;

    public GemLootModifier(LootItemCondition[] conditions, Item gem, float chance, List<ResourceLocation> guaranteeIn, boolean hidden) {
        super(conditions);
        this.gem = gem;
        this.chance = chance;
        this.guaranteeIn = guaranteeIn;
        this.hidden = hidden;
    }

    @Override
    protected ObjectArrayList<ItemStack> doApply(ObjectArrayList<ItemStack> loot, LootContext ctx) {
        boolean give = false;
        Vec3 origin = ctx.getParamOrNull(LootContextParams.ORIGIN);
        ServerLevel level = ctx.getLevel();
        if (origin != null && !guaranteeIn.isEmpty()) {
            BlockPos pos = BlockPos.containing(origin);
            var structures = level.registryAccess().registryOrThrow(Registries.STRUCTURE);
            String key = null;
            for (ResourceLocation id : guaranteeIn) {
                var structure = structures.get(id);
                if (structure == null) continue;
                var start = level.structureManager().getStructureWithPieceAt(pos, structure);
                if (!start.isValid()) continue;
                key = id + "@" + start.getChunkPos().toLong();
                break;
            }
            // No registered structure here (placed with /place, or by another mod): the chest's
            // loot table already names the structure, so the guarantee goes once per 4x4 chunk area
            if (key == null)
                key = guaranteeIn.get(0) + "@placed" + (pos.getX() >> 6) + "," + (pos.getZ() >> 6);
            if ((!hidden || nearStickyPiston(level, pos))
                    && GemGuarantees.get(level).claim(key + "/" + BuiltInRegistries.ITEM.getKey(gem)))
                give = true;
        }
        if (!give && chance > 0 && ctx.getRandom().nextFloat() < chance) give = true;
        if (give) loot.add(new ItemStack(gem));
        return loot;
    }

    private static boolean nearStickyPiston(ServerLevel level, BlockPos pos) {
        for (BlockPos p : BlockPos.betweenClosed(pos.offset(-4, -3, -4), pos.offset(4, 3, 4)))
            if (level.getBlockState(p).is(Blocks.STICKY_PISTON)) return true;
        return false;
    }

    @Override
    public MapCodec<? extends IGlobalLootModifier> codec() {
        return CODEC;
    }
}
