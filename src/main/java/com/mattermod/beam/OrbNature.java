package com.mattermod.beam;

import com.mattermod.trait.Traits;
import com.mattermod.trait.WorldEffects;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Ignite and Growth modes: fire seeds and rings, bone-meal puffs and the drifting pollen cloud.
 * Split out of {@link BeamBallEntity}; every method takes the orb as {@code b}.
 */
final class OrbNature {

    private OrbNature() {}

    /**
     * Charged Growth: a slow pollen cloud that drifts toward where its owner looks, growing crops
     * and plants below it (each block once a second).
     */
    static void driftTick(BeamBallEntity b) {
        ServerLevel sl = (ServerLevel) b.level();
        // Lingering Bloom: twice as long, pulsing twice as often
        boolean linger = b.has("cultivation.lingering_bloom");
        int life = (b.getPhase() == 2 ? 160 : 80) * (linger ? 2 : 1);
        if (++b.lifeTicks > life) {
            b.impactFx(b.position());
            b.discard();
            return;
        }
        if (b.getOwner() instanceof ServerPlayer sp && sp.isAlive() && sp.level() == sl) {
            Vec3 eye = sp.getEyePosition();
            double reach =
                    !b.traits.isEmpty() && Traits.hasCommon(b.traits, Traits.CULTIVATION, 1)
                            ? 48
                            : 24; // Pollen Trail
            Vec3 target =
                    sl.clip(
                                    new net.minecraft.world.level.ClipContext(
                                            eye,
                                            eye.add(sp.getLookAngle().scale(reach)),
                                            net.minecraft.world.level.ClipContext.Block.COLLIDER,
                                            net.minecraft.world.level.ClipContext.Fluid.NONE,
                                            sp))
                            .getLocation()
                            .add(0, 1.2, 0);
            Vec3 want = target.subtract(b.position());
            Vec3 v =
                    b.getDeltaMovement()
                            .scale(0.85)
                            .add(
                                    want.lengthSqr() > 0.25
                                            ? want.normalize().scale(0.06)
                                            : Vec3.ZERO);
            if (v.length() > 0.35) v = v.normalize().scale(0.35);
            b.setDeltaMovement(v);
        } else {
            b.setDeltaMovement(b.getDeltaMovement().scale(0.9));
        }
        Vec3 p = b.position().add(b.getDeltaMovement());
        b.setPos(p.x, p.y, p.z);

        ProjectileEffects.growthCloud(b, b.traits);
        int r =
                1
                        + Math.max(0, b.rank("cultivation.lingering_bloom") - 1)
                        + (b.getPhase() == 2 ? b.rank("orb.payload") : 0); // Payload: a wider cloud
        BlockPos c = b.blockPosition();
        long now = sl.getGameTime();
        for (BlockPos bp : BlockPos.betweenClosed(c.offset(-r, -3, -r), c.offset(r, 0, r))) {
            BlockState bs = sl.getBlockState(bp);
            if (!(bs.getBlock() instanceof net.minecraft.world.level.block.BonemealableBlock)
                    && !bs.getFluidState().is(net.minecraft.tags.FluidTags.WATER)) continue;
            long key = bp.asLong();
            Long next = b.growSeen.get(key);
            if (next != null && next > now) continue;
            b.growSeen.put(key, now + (linger ? 10 : 20));
            if (!b.mayBuild(bp)) continue;
            boneMeal(sl, bp.immutable(), 6);
        }
        if (now % 2 == 0) {
            sl.sendParticles(
                    ParticleTypes.SPORE_BLOSSOM_AIR,
                    b.getX(),
                    b.getY(),
                    b.getZ(),
                    4,
                    0.7,
                    0.3,
                    0.7,
                    0);
            sl.sendParticles(
                    ParticleTypes.HAPPY_VILLAGER,
                    b.getX(),
                    b.getY() - 0.5,
                    b.getZ(),
                    1,
                    0.8,
                    0.4,
                    0.8,
                    0);
        }
    }

    /**
     * Flint and steel: light campfires/candles, prime TNT, or start a fire (and portals). Charged:
     * a ring of fire, or with Incendiary a 4-point star.
     */
    static void ignite(BeamBallEntity b, BlockPos hitPos, Direction face) {
        ServerLevel sl = (ServerLevel) b.level();
        Entity owner = b.getOwner();
        if (!b.mayBuild(hitPos)) return;
        BlockState bs = sl.getBlockState(hitPos);
        if (net.minecraft.world.level.block.CampfireBlock.canLight(bs)
                || net.minecraft.world.level.block.CandleBlock.canLight(bs)
                || net.minecraft.world.level.block.CandleCakeBlock.canLight(bs)) {
            sl.setBlock(
                    hitPos,
                    (b.isSoulFire() && bs.is(Blocks.CAMPFIRE)
                                    ? Blocks.SOUL_CAMPFIRE.withPropertiesOf(bs)
                                    : bs)
                            .setValue(
                                    net.minecraft.world.level.block.state.properties
                                            .BlockStateProperties.LIT,
                                    true),
                    Block.UPDATE_ALL_IMMEDIATE);
        } else if (bs.getBlock() instanceof net.minecraft.world.level.block.TntBlock) {
            bs.onCaughtFire(sl, hitPos, face, owner instanceof LivingEntity le ? le : null);
            sl.removeBlock(hitPos, false);
        } else {
            BlockPos firePos = hitPos.relative(face);
            if (net.minecraft.world.level.block.BaseFireBlock.canBePlacedAt(sl, firePos, face)
                    && b.mayBuild(firePos)) {
                // a portal frame lights through vanilla's own placement
                if (net.minecraft.world.level.block.BaseFireBlock.getState(sl, firePos)
                        .is(Blocks.FIRE)) {
                    WorldEffects.placeFire(sl, firePos, owner);
                } else {
                    WorldEffects.placeFire(sl, firePos, owner);
                }
                int inc = b.rank("pyrotechnics.incendiary");
                if (b.getPhase() == 2 && inc > 0)
                    WorldEffects.fireStar(sl, firePos, owner, inc >= 2 ? 3 : 2);
                else if (b.getPhase() >= 1)
                    WorldEffects.fireRing(
                            sl, firePos, owner, b.getPhase()); // charged: ring of 1, then 2
            }
        }
        sl.playSound(
                null,
                hitPos,
                SoundEvents.FLINTANDSTEEL_USE,
                SoundSource.BLOCKS,
                1f,
                0.9f + b.getRandom().nextFloat() * 0.2f);
    }

    /**
     * Exactly what a bone meal item does on this block: grows crops and saplings, spreads grass and
     * flowers on grass blocks (and moss, nylium...), and grows seagrass and coral plants on the
     * floor under water.
     */
    static boolean boneMeal(ServerLevel sl, BlockPos pos, int particles) {
        ItemStack meal = new ItemStack(net.minecraft.world.item.Items.BONE_MEAL);
        if (net.minecraft.world.item.BoneMealItem.growCrop(meal, sl, pos)) {
            sl.levelEvent(1505, pos, particles);
            return true;
        }
        BlockState bs = sl.getBlockState(pos);
        if (bs.getFluidState().is(net.minecraft.tags.FluidTags.WATER)
                && net.minecraft.world.item.BoneMealItem.growWaterPlant(meal, sl, pos, Direction.UP)) {
            sl.levelEvent(1505, pos, particles);
            return true;
        }
        return false;
    }

    /**
     * Bone meal on the block hit. Phase 3 covers 5x5 (9x9 with Verdant Pulse, wider with Payload);
     * Rewild greens bare dirt and spreads flowers (II: moss onto stone and cobblestone).
     */
    static void grow(BeamBallEntity b, BlockPos hitPos) {
        ServerLevel sl = (ServerLevel) b.level();
        int r =
                (b.getPhase() == 2
                                ? (b.has("cultivation.verdant_pulse") ? 4 : 2)
                                        + b.rank("orb.payload")
                                : 0)
                        + Math.max(0, b.rank("cultivation.lingering_bloom") - 1);
        boolean rewild = b.has("cultivation.rewild");
        boolean moss = b.rank("cultivation.rewild") >= 2;
        for (BlockPos p :
                BlockPos.betweenClosed(hitPos.offset(-r, -1, -r), hitPos.offset(r, 1, r))) {
            if (!b.mayBuild(p)) continue;
            BlockPos q = p.immutable();
            BlockState here = sl.getBlockState(q);
            if (rewild && here.is(Blocks.DIRT) && sl.getBlockState(q.above()).isAir()) {
                sl.setBlockAndUpdate(q, Blocks.GRASS_BLOCK.defaultBlockState());
            } else if (moss
                    && (here.is(Blocks.STONE) || here.is(Blocks.COBBLESTONE))
                    && sl.getBlockState(q.above()).isAir()
                    && b.getRandom().nextInt(3) > 0) {
                sl.setBlockAndUpdate(
                        q,
                        (here.is(Blocks.COBBLESTONE) ? Blocks.MOSSY_COBBLESTONE : Blocks.MOSS_BLOCK)
                                .defaultBlockState());
            }
            boneMeal(sl, q, 15);
        }
        if (rewild) {
            BlockPos top = hitPos.above();
            for (int i = 0; i < 4 + 4 * b.getPhase(); i++) {
                BlockPos p =
                        top.offset(b.getRandom().nextInt(5) - 2, 0, b.getRandom().nextInt(5) - 2);
                if (sl.getBlockState(p).isAir()
                        && sl.getBlockState(p.below()).is(BlockTags.DIRT)
                        && b.mayBuild(p)) {
                    BlockState flower =
                            b.getRandom().nextInt(4) == 0
                                    ? Blocks.MOSS_CARPET.defaultBlockState()
                                    : (b.getRandom().nextBoolean()
                                                    ? Blocks.POPPY
                                                    : Blocks.DANDELION)
                                            .defaultBlockState();
                    if (flower.canSurvive(sl, p)) sl.setBlockAndUpdate(p, flower);
                }
            }
        }
    }
}
