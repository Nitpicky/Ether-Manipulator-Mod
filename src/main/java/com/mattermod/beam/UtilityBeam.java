package com.mattermod.beam;

import com.mattermod.trait.Traits;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.BeehiveBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.CandleBlock;
import net.minecraft.world.level.block.CandleCakeBlock;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.FarmBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.ItemAbilities;
import net.neoforged.neoforge.common.ItemAbility;

/**
 * Sneak + beam on a block: the right-click jobs vanilla tools lose. Context sensitive, one change
 * per block per sweep: campfire / candle → light it top of dirt or grass → till if water or
 * farmland is near, else path side of dirt or grass → path log / wood → strip copper → scrape
 * oxidation, then wax
 *
 * <p>Cultivation: the badge makes it 3x3 and harvests ripe crops (7x7 with Harvest Moon);
 * Groundskeeper shears; Sower plants seeds on farmland.
 */
final class UtilityBeam {

    private UtilityBeam() {}

    static void apply(ServerLevel level, ServerPlayer player, BlockHitResult hit, BeamState state) {
        BlockPos pos = hit.getBlockPos();
        if (state.lastUtilityPos == pos.asLong()) return;
        state.lastUtilityPos = pos.asLong();
        ItemStack tool = player.getMainHandItem();

        boolean gardener = Traits.hasLine(tool, Traits.CULTIVATION);
        int harvestR = Traits.has(tool, "cultivation.harvest_moon") ? 3 : gardener ? 1 : 0;
        int areaR = gardener ? 1 : 0;
        // Groundskeeper and Sower reach wider with rank
        areaR +=
                Math.max(
                        0,
                        Math.max(
                                        Traits.rank(tool, "cultivation.groundskeeper"),
                                        Traits.rank(tool, "cultivation.sower"))
                                - 1);

        // Harvest ripe crops (Cultivation badge: 3x3; Harvest Moon: 7x7)
        if (harvestR > 0 && isRipeCrop(level.getBlockState(pos))) {
            for (BlockPos p :
                    BlockPos.betweenClosed(
                            pos.offset(-harvestR, -1, -harvestR),
                            pos.offset(harvestR, 1, harvestR))) {
                harvest(level, player, tool, p.immutable());
            }
            return;
        }

        boolean any = false;
        for (BlockPos p :
                BlockPos.betweenClosed(
                        pos.offset(-areaR, 0, -areaR), pos.offset(areaR, 0, areaR))) {
            BlockPos q = p.immutable();
            BlockHitResult h =
                    q.equals(pos)
                            ? hit
                            : new BlockHitResult(Vec3.atCenterOf(q), hit.getDirection(), q, false);
            any |= single(level, player, tool, h);
        }
        if (!any) state.lastUtilityPos = Long.MIN_VALUE; // nothing happened; try again next tick
    }

    /** One block's utility action. Returns true if something changed. */
    private static boolean single(
            ServerLevel level, ServerPlayer player, ItemStack tool, BlockHitResult hit) {
        BlockPos pos = hit.getBlockPos();
        if (!level.mayInteract(player, pos) || !player.mayUseItemAt(pos, hit.getDirection(), tool))
            return false;
        BlockState bs = level.getBlockState(pos);

        if (CampfireBlock.canLight(bs)
                || CandleBlock.canLight(bs)
                || CandleCakeBlock.canLight(bs)) {
            level.setBlock(
                    pos, bs.setValue(BlockStateProperties.LIT, true), Block.UPDATE_ALL_IMMEDIATE);
            level.playSound(null, pos, SoundEvents.FLINTANDSTEEL_USE, SoundSource.BLOCKS, 1f, 1.2f);
            level.gameEvent(player, GameEvent.BLOCK_CHANGE, pos);
            return true;
        }

        // The sneak beam shears foliage (leaves, grass, flowers...) like shears would: the beam
        // passes through it otherwise, so this is the way to take it
        if (com.mattermod.util.FoliageHelper.isFoliage(bs)) {
            level.removeBlock(pos, false);
            Block.popResource(level, pos, new ItemStack(bs.getBlock()));
            level.playSound(null, pos, SoundEvents.SHEEP_SHEAR, SoundSource.BLOCKS, 0.8f, 1.2f);
            return true;
        }

        // Groundskeeper: shear leaves, vines, cobwebs and grasses; take honeycomb
        if (Traits.has(tool, "cultivation.groundskeeper")) {
            if (isShearable(bs)) {
                level.removeBlock(pos, false);
                Block.popResource(level, pos, new ItemStack(bs.getBlock()));
                level.playSound(null, pos, SoundEvents.SHEEP_SHEAR, SoundSource.BLOCKS, 0.8f, 1.2f);
                return true;
            }
            if (bs.getBlock() instanceof BeehiveBlock hive
                    && bs.getValue(BeehiveBlock.HONEY_LEVEL) >= BeehiveBlock.MAX_HONEY_LEVELS) {
                BeehiveBlock.dropHoneycomb(level, pos);
                hive.releaseBeesAndResetHoneyLevel(
                        level,
                        bs,
                        pos,
                        player,
                        net.minecraft.world.level.block.entity.BeehiveBlockEntity.BeeReleaseStatus
                                .BEE_RELEASED);
                level.playSound(null, pos, SoundEvents.BEEHIVE_SHEAR, SoundSource.BLOCKS, 1f, 1f);
                return true;
            }
        }

        // Sower: plant seeds from the inventory on farmland
        if (bs.getBlock() instanceof FarmBlock
                && level.getBlockState(pos.above()).isAir()
                && Traits.has(tool, "cultivation.sower")) {
            if (sow(level, player, pos.above())) return true;
        }

        UseOnContext ctx = new UseOnContext(player, InteractionHand.MAIN_HAND, hit);
        boolean airAbove = level.getBlockState(pos.above()).isAir();
        // The top of dirt can become farmland or a path: till only where a
        // farm makes sense (water in reach, or farmland next to it), else path.
        ItemAbility ground =
                hit.getDirection() == Direction.UP && wantsFarmland(level, pos)
                        ? ItemAbilities.HOE_TILL
                        : ItemAbilities.SHOVEL_FLATTEN;
        ItemAbility[] order = {
            ground, ItemAbilities.AXE_STRIP, ItemAbilities.AXE_SCRAPE, ItemAbilities.AXE_WAX_OFF
        };

        for (ItemAbility ability : order) {
            boolean needsAir =
                    ability == ItemAbilities.HOE_TILL || ability == ItemAbilities.SHOVEL_FLATTEN;
            if (needsAir && !airAbove) continue;
            BlockState next = bs.getToolModifiedState(ctx, ability, false);
            if (next == null || next == bs) continue;
            level.setBlock(pos, next, Block.UPDATE_ALL_IMMEDIATE);
            level.playSound(null, pos, soundFor(ability), SoundSource.BLOCKS, 1f, 1f);
            if (ability == ItemAbilities.AXE_SCRAPE) level.levelEvent(null, 3005, pos, 0);
            if (ability == ItemAbilities.AXE_WAX_OFF) level.levelEvent(null, 3004, pos, 0);
            level.gameEvent(player, GameEvent.BLOCK_CHANGE, pos);
            return true;
        }
        return false;
    }

    /**
     * True when tilling here makes a working farm: water within 4 blocks on the same level or one
     * above (vanilla's hydration range), or farmland right next to it.
     */
    private static boolean wantsFarmland(ServerLevel level, BlockPos pos) {
        for (Direction d : Direction.Plane.HORIZONTAL) {
            if (level.getBlockState(pos.relative(d)).getBlock() instanceof FarmBlock) return true;
        }
        for (BlockPos p : BlockPos.betweenClosed(pos.offset(-4, 0, -4), pos.offset(4, 1, 4))) {
            if (level.getFluidState(p).is(net.minecraft.tags.FluidTags.WATER)) return true;
        }
        return false;
    }

    private static boolean isRipeCrop(BlockState bs) {
        return bs.getBlock() instanceof CropBlock crop && crop.isMaxAge(bs);
    }

    /** Breaks a ripe crop like the player would; Replant puts a fresh one back. */
    private static void harvest(
            ServerLevel level, ServerPlayer player, ItemStack tool, BlockPos p) {
        BlockState bs = level.getBlockState(p);
        if (!isRipeCrop(bs) || !level.mayInteract(player, p)) return;
        CropBlock crop = (CropBlock) bs.getBlock();
        if (!MiningDamageTracker.breakAsPlayer(player, p)) return;
    }

    private static boolean sow(ServerLevel level, ServerPlayer player, BlockPos at) {
        var inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (s.isEmpty()
                    || !(s.getItem() instanceof BlockItem bi)
                    || !(bi.getBlock() instanceof CropBlock crop)) continue;
            BlockState plant = crop.defaultBlockState();
            if (!plant.canSurvive(level, at)) continue;
            level.setBlockAndUpdate(at, plant);
            level.playSound(null, at, SoundEvents.CROP_PLANTED, SoundSource.BLOCKS, 1f, 1f);
            if (!player.getAbilities().instabuild) s.shrink(1);
            return true;
        }
        return false;
    }

    private static boolean isShearable(BlockState bs) {
        return bs.is(BlockTags.LEAVES)
                || bs.is(Blocks.VINE)
                || bs.is(Blocks.COBWEB)
                || bs.is(Blocks.SHORT_GRASS)
                || bs.is(Blocks.TALL_GRASS)
                || bs.is(Blocks.FERN)
                || bs.is(Blocks.LARGE_FERN)
                || bs.is(Blocks.DEAD_BUSH)
                || bs.is(Blocks.SEAGRASS)
                || bs.is(Blocks.GLOW_LICHEN)
                || bs.is(Blocks.HANGING_ROOTS)
                || bs.is(Blocks.TWISTING_VINES)
                || bs.is(Blocks.WEEPING_VINES);
    }

    static void shearEntity(
            ServerLevel level,
            ServerPlayer player,
            net.minecraft.world.entity.LivingEntity target,
            BeamState state) {
        if (state.lastUtilityPos == -target.getId() - 1L) return;
        if (target instanceof net.minecraft.world.entity.animal.Sheep sheep
                && sheep.readyForShearing()) {
            sheep.shear(SoundSource.PLAYERS);
            state.lastUtilityPos = -target.getId() - 1L;
        } else if (target instanceof net.minecraft.world.entity.animal.SnowGolem golem
                && golem.readyForShearing()) {
            golem.shear(SoundSource.PLAYERS);
            state.lastUtilityPos = -target.getId() - 1L;
        } else if (target instanceof net.minecraft.world.entity.animal.MushroomCow cow
                && cow.readyForShearing()) {
            cow.shear(SoundSource.PLAYERS);
            state.lastUtilityPos = -target.getId() - 1L;
        }
    }

    /** Herder: feeding an animal from your inventory with the sneak beam. */
    /**
     * Herder: the sneak beam on an animal feeds every animal in 5x5 around it for free (no food
     * used): babies grow up, adults fall in love.
     */
    static void herd(ServerLevel level, ServerPlayer player, Animal animal, BeamState state) {
        if (state.lastUtilityPos == -animal.getId() - 1L) return;
        state.lastUtilityPos = -animal.getId() - 1L;
        boolean fed = false;
        for (Animal a :
                level.getEntitiesOfClass(
                        Animal.class,
                        animal.getBoundingBox().inflate(2.5, 1.5, 2.5),
                        Animal::isAlive)) {
            boolean eligible = a.isBaby() || (a.getAge() == 0 && a.canFallInLove());
            if (!eligible) continue;
            ItemStack food = ItemStack.EMPTY;
            for (var candidate : player.getInventory().items)
                if (!candidate.isEmpty() && a.isFood(candidate)) {
                    food = candidate;
                    break;
                }
            if (food.isEmpty()) continue;
            if (!player.getAbilities().instabuild) food.shrink(1);
            if (a.isBaby())
                a.ageUp(
                        net.minecraft.world.entity.AgeableMob.getSpeedUpSecondsWhenFeeding(
                                -a.getAge()),
                        true);
            else a.setInLove(player);
            fed = true;
            level.sendParticles(
                    net.minecraft.core.particles.ParticleTypes.HAPPY_VILLAGER,
                    a.getX(),
                    a.getY(1),
                    a.getZ(),
                    4,
                    0.3,
                    0.2,
                    0.3,
                    0);
        }
        if (fed)
            level.playSound(
                    null,
                    animal.blockPosition(),
                    SoundEvents.GENERIC_EAT,
                    SoundSource.NEUTRAL,
                    0.6f,
                    1.2f);
    }

    private static SoundEvent soundFor(ItemAbility a) {
        if (a == ItemAbilities.HOE_TILL) return SoundEvents.HOE_TILL;
        if (a == ItemAbilities.SHOVEL_FLATTEN) return SoundEvents.SHOVEL_FLATTEN;
        if (a == ItemAbilities.AXE_SCRAPE) return SoundEvents.AXE_SCRAPE;
        if (a == ItemAbilities.AXE_WAX_OFF) return SoundEvents.AXE_WAX_OFF;
        return SoundEvents.AXE_STRIP;
    }
}
