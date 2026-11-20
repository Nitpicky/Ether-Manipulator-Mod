package com.ethermod.beam;

import com.ethermod.trait.Traits;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.BeehiveBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.CandleBlock;
import net.minecraft.world.level.block.CandleCakeBlock;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.FarmlandBlock;
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
 * <p>Cultivation: the badge makes it 3x3 and harvests ripe crops; Groundskeeper shears.
 */
final class UtilityBeam {

    private UtilityBeam() {}

    static void apply(ServerLevel level, ServerPlayer player, BlockHitResult hit, BeamState state) {
        BlockPos pos = hit.getBlockPos();
        if (state.lastUtilityPos == pos.asLong()) return;
        state.lastUtilityPos = pos.asLong();
        ItemStack tool = player.getMainHandItem();

        boolean gardener = Traits.hasLine(tool, Traits.BOTANIC);
        int harvestR = gardener ? 1 : 0;
        int areaR = gardener ? 1 : 0;

        // Harvest ripe crops (Cultivation badge: 3x3)
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
        // the beam sits on a block for many ticks: the claim probe (a synthetic break event, see
        // Protect) is only posted once there is something to change, right before changing it
        if (!com.ethermod.util.Protect.mayInteract(level, player, pos) || !player.mayUseItemAt(pos, hit.getDirection(), tool))
            return false;
        BlockState bs = level.getBlockState(pos);

        if (CampfireBlock.canLight(bs)
                || CandleBlock.canLight(bs)
                || CandleCakeBlock.canLight(bs)) {
            if (!com.ethermod.util.Protect.may(level, player, pos)) return false;
            level.setBlock(
                    pos, bs.setValue(BlockStateProperties.LIT, true), Block.UPDATE_ALL_IMMEDIATE);
            level.playSound(null, pos, com.ethermod.sound.ModSounds.WORLD_FLINTANDSTEEL_USE, SoundSource.BLOCKS, 1f, 1.2f);
            level.gameEvent(player, GameEvent.BLOCK_CHANGE, pos);
            return true;
        }

        // The sneak beam shears foliage (leaves, grass, flowers...) like shears would: the beam
        // passes through it otherwise, so this is the way to take it
        if (com.ethermod.util.FoliageHelper.isPlant(bs)) {
            if (!com.ethermod.util.Protect.may(level, player, pos)) return false;
            level.removeBlock(pos, false);
            Block.popResource(level, pos, new ItemStack(bs.getBlock()));
            level.playSound(null, pos, com.ethermod.sound.ModSounds.WORLD_SHEEP_SHEAR, SoundSource.BLOCKS, 0.8f, 1.2f);
            return true;
        }

        // Groundskeeper: shear leaves, vines, cobwebs and grasses; take honeycomb
        if (Traits.has(tool, "botanic.groundskeeper")) {
            if (isShearable(bs)) {
                if (!com.ethermod.util.Protect.may(level, player, pos)) return false;
                level.removeBlock(pos, false);
                Block.popResource(level, pos, new ItemStack(bs.getBlock()));
                level.playSound(null, pos, com.ethermod.sound.ModSounds.WORLD_SHEEP_SHEAR, SoundSource.BLOCKS, 0.8f, 1.2f);
                return true;
            }
            if (bs.getBlock() instanceof BeehiveBlock hive
                    && bs.getValue(BeehiveBlock.HONEY_LEVEL) >= BeehiveBlock.MAX_HONEY_LEVELS) {
                if (!com.ethermod.util.Protect.may(level, player, pos)) return false;
                BeehiveBlock.dropHoneycomb(level, pos);
                hive.releaseBeesAndResetHoneyLevel(
                        level,
                        bs,
                        pos,
                        player,
                        net.minecraft.world.level.block.entity.BeehiveBlockEntity.BeeReleaseStatus
                                .BEE_RELEASED);
                level.playSound(null, pos, com.ethermod.sound.ModSounds.WORLD_BEEHIVE_SHEAR, SoundSource.BLOCKS, 1f, 1f);
                return true;
            }
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
            BlockState next = bs.getToolModifiedState(ctx, ability, true);
            if (next == null || next == bs) continue;
            if (!com.ethermod.util.Protect.may(level, player, pos)) return false;
            next = bs.getToolModifiedState(ctx, ability, false);
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
            if (level.getBlockState(pos.relative(d)).getBlock() instanceof FarmlandBlock) return true;
        }
        for (BlockPos p : BlockPos.betweenClosed(pos.offset(-4, 0, -4), pos.offset(4, 1, 4))) {
            if (level.getFluidState(p).is(net.minecraft.tags.FluidTags.WATER)) return true;
        }
        return false;
    }

    private static boolean isRipeCrop(BlockState bs) {
        return bs.getBlock() instanceof CropBlock crop && crop.isMaxAge(bs)
                || com.ethermod.util.FoliageHelper.extraRipe(bs);
    }

    /** Breaks a ripe crop like the player would; Replant puts a fresh one back. */
    private static void harvest(
            ServerLevel level, ServerPlayer player, ItemStack tool, BlockPos p) {
        BlockState bs = level.getBlockState(p);
        // breakAsPlayer posts the real break event claims answer, so no probe before it
        if (!isRipeCrop(bs) || !com.ethermod.util.Protect.mayInteract(level, player, p)) return;
        if (!MiningDamageTracker.breakAsPlayer(player, p)) return;
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
                    com.ethermod.sound.ModSounds.WORLD_GENERIC_EAT,
                    SoundSource.NEUTRAL,
                    0.6f,
                    1.2f);
    }

    private static SoundEvent soundFor(ItemAbility a) {
        if (a == ItemAbilities.HOE_TILL) return com.ethermod.sound.ModSounds.WORLD_HOE_TILL;
        if (a == ItemAbilities.SHOVEL_FLATTEN) return com.ethermod.sound.ModSounds.WORLD_SHOVEL_FLATTEN;
        if (a == ItemAbilities.AXE_SCRAPE) return com.ethermod.sound.ModSounds.WORLD_AXE_SCRAPE;
        if (a == ItemAbilities.AXE_WAX_OFF) return com.ethermod.sound.ModSounds.WORLD_AXE_WAX_OFF;
        return com.ethermod.sound.ModSounds.WORLD_AXE_STRIP;
    }
}
