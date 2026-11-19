package com.ethermod.beam;

import com.ethermod.item.OrbMode;
import com.ethermod.trait.WorldEffects;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SimpleWaterloggedBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Water and Lava modes: placing sources like a bucket, spills, filling containers, Kiln, Forge
 * Heart, Quench. Split out of {@link BeamBallEntity}; every method takes the orb as {@code b}.
 */
final class OrbFluids {

    private OrbFluids() {}

    /** Helmsman: Water orbs steer toward where their owner aims. */
    static void steerWater(BeamBallEntity b) {
        int helm = b.rank("hydraulic.helmsman");
        if (helm <= 0
                || !(b.getOwner() instanceof ServerPlayer sp)
                || !sp.isAlive()
                || sp.level() != b.level()) return;
        Vec3 v = b.getDeltaMovement();
        double speed = v.length();
        if (speed < 1e-3) return;
        Vec3 target = sp.getEyePosition().add(sp.getLookAngle().scale(48));
        Vec3 want = target.subtract(b.position()).normalize();
        double k = helm >= 2 ? 0.22 : 0.12;
        b.setDeltaMovement(
                v.scale(1 / speed).scale(1 - k).add(want.scale(k)).normalize().scale(speed));
    }

    /** Bucket-like fluid placement. Returns false if nothing was placed. */
    static boolean placeFluid(
            BeamBallEntity b,
            BlockPos pos,
            Direction face,
            net.minecraft.world.level.material.FlowingFluid fluid) {
        ServerLevel sl = (ServerLevel) b.level();
        ServerPlayer owner = b.getOwner() instanceof ServerPlayer sp ? sp : null;
        if (owner != null && !com.ethermod.util.Protect.may(sl, owner, pos)) return false;
        if (fluid == Fluids.LAVA ? !com.ethermod.config.ServerConfig.fireAndLava() : !com.ethermod.config.ServerConfig.waterAndFrost())
            return false;

        if (fluid == Fluids.WATER && sl.dimensionType().ultraWarm()) {
            sl.playSound(
                    null,
                    pos,
                    com.ethermod.sound.ModSounds.WORLD_FIRE_EXTINGUISH,
                    SoundSource.BLOCKS,
                    0.5f,
                    2.6f + (b.getRandom().nextFloat() - b.getRandom().nextFloat()) * 0.8f);
            sl.sendParticles(
                    ParticleTypes.LARGE_SMOKE,
                    pos.getX() + 0.5,
                    pos.getY() + 0.5,
                    pos.getZ() + 0.5,
                    8,
                    0.3,
                    0.3,
                    0.3,
                    0.02);
            return true; // the water is spent
        }

        BlockState state = sl.getBlockState(pos);
        if (fluid == Fluids.WATER
                && state.getBlock() instanceof SimpleWaterloggedBlock wl
                && face != null
                && wl.canPlaceLiquid(owner, sl, pos, state, fluid)) {
            wl.placeLiquid(sl, pos, state, fluid.getSource(false));
        } else if (state.isAir() || state.canBeReplaced(fluid)) {
            if (!state.isAir() && !state.liquid()) sl.destroyBlock(pos, true, owner);
            if (!sl.setBlock(
                    pos,
                    fluid.defaultFluidState().createLegacyBlock(),
                    Block.UPDATE_ALL_IMMEDIATE)) {
                return false;
            }
            settle(b, pos, fluid);
        } else {
            return false;
        }
        sl.playSound(
                null,
                pos,
                fluid == Fluids.LAVA ? com.ethermod.sound.ModSounds.WORLD_BUCKET_EMPTY_LAVA : com.ethermod.sound.ModSounds.WORLD_BUCKET_EMPTY,
                SoundSource.PLAYERS,
                1f,
                1f);
        return true;
    }

    /** Ticks a placed source lasts unless it is kept (8 s). */
    static final int FADE_TICKS = 160;

    /**
     * Water and lava from a shot don't last: the source dries up after {@link #FADE_TICKS}, and
     * what flowed from it drains away. Pressure (water) and Inferno (lava) keep a source that
     * touches a block on any side: it is a true source and stays.
     */
    static void settle(
            BeamBallEntity b, BlockPos pos, net.minecraft.world.level.material.FlowingFluid fluid) {
        ServerLevel sl = (ServerLevel) b.level();
        String keeper = fluid == Fluids.LAVA ? "pyrotechnic.inferno" : "hydraulic.pressure";
        if (b.has(keeper)) {
            for (Direction d : Direction.values()) {
                BlockState n = sl.getBlockState(pos.relative(d));
                if (!n.isAir() && n.getFluidState().isEmpty()) return; // held in place: it stays
            }
        }
        BlockPos at = pos.immutable();
        WorldEffects.schedule(
                sl,
                FADE_TICKS,
                () -> {
                    var f = sl.getFluidState(at);
                    if (sl.hasChunkAt(at) && f.isSource() && f.getType().isSame(fluid)
                            && sl.getBlockState(at).getBlock() instanceof net.minecraft.world.level.block.LiquidBlock)
                        sl.setBlockAndUpdate(at, Blocks.AIR.defaultBlockState());
                });
    }

    /** Payload: extra sources around the first, in a square of radius {@code r} (air only). */
    static void spill(
            BeamBallEntity b,
            BlockPos center,
            int r,
            net.minecraft.world.level.material.FlowingFluid fluid) {
        ServerLevel sl = (ServerLevel) b.level();
        for (BlockPos p :
                BlockPos.betweenClosed(center.offset(-r, 0, -r), center.offset(r, 0, r))) {
            if (p.equals(center) || !sl.getBlockState(p).isAir() || !b.mayBuild(p)) continue;
            sl.setBlock(p, fluid.defaultFluidState().createLegacyBlock(), Block.UPDATE_ALL);
            settle(b, p.immutable(), fluid);
            if (fluid == Fluids.LAVA) OrbFluids.lavaPlaced(b, p.immutable());
        }
    }

    /** Lava from an orb: remembered as the owner's (Fire Ward), and Obsidian Cast hardens it. */
    static void lavaPlaced(BeamBallEntity b, BlockPos pos) {
        ServerLevel sl = (ServerLevel) b.level();
        WorldEffects.ownLava(sl, pos, b.getOwner());
        int cast = b.rank("pyrotechnic.obsidian_cast");
        if (cast > 0) {
            BlockPos p = pos.immutable();
            WorldEffects.schedule(
                    sl,
                    cast >= 2 ? 60 : 200,
                    () -> {
                        if (sl.getBlockState(p).is(Blocks.LAVA) && sl.getFluidState(p).isSource()) {
                            sl.setBlockAndUpdate(p, Blocks.OBSIDIAN.defaultBlockState());
                            sl.levelEvent(1501, p, 0);
                        }
                    });
        }
    }

    /** Fluid Link (built in): cauldrons and other mods' tanks take the liquid instead. */
    static boolean fillContainer(
            BeamBallEntity b,
            BlockPos pos,
            Direction face,
            net.minecraft.world.level.material.Fluid fluid) {
        ServerLevel sl = (ServerLevel) b.level();
        if (!b.mayBuild(pos)) return false;
        BlockState bs = sl.getBlockState(pos);
        if (bs.is(Blocks.CAULDRON)) {
            sl.setBlockAndUpdate(
                    pos,
                    fluid == Fluids.LAVA
                            ? Blocks.LAVA_CAULDRON.defaultBlockState()
                            : Blocks.WATER_CAULDRON
                                    .defaultBlockState()
                                    .setValue(
                                            net.minecraft.world.level.block.LayeredCauldronBlock
                                                    .LEVEL,
                                            3));
            sl.playSound(
                    null,
                    pos,
                    fluid == Fluids.LAVA ? com.ethermod.sound.ModSounds.WORLD_BUCKET_EMPTY_LAVA : com.ethermod.sound.ModSounds.WORLD_BUCKET_EMPTY,
                    SoundSource.BLOCKS,
                    1f,
                    1f);
            return true;
        }
        var handler =
                sl.getCapability(
                        net.neoforged.neoforge.capabilities.Capabilities.FluidHandler.BLOCK,
                        pos,
                        face);
        if (handler == null) return false;
        var stack = new net.neoforged.neoforge.fluids.FluidStack(fluid, 1000);
        if (handler.fill(
                        stack,
                        net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction.SIMULATE)
                < 1000) return false;
        handler.fill(
                stack, net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction.EXECUTE);
        sl.playSound(
                null,
                pos,
                fluid == Fluids.LAVA ? com.ethermod.sound.ModSounds.WORLD_BUCKET_EMPTY_LAVA : com.ethermod.sound.ModSounds.WORLD_BUCKET_EMPTY,
                SoundSource.BLOCKS,
                1f,
                1f);
        return true;
    }

    /** Forge Heart: a Lava orb fuels a furnace, smoker or blast furnace for 32 items (64 at II). */
    static boolean forgeHeart(BeamBallEntity b, BlockPos pos) {
        int heart = b.getMode() == OrbMode.IGNITE ? b.rank("pyrotechnic.kiln") : 0;
        if (heart <= 0 || !b.mayBuild(pos)) return false;
        ServerLevel sl = (ServerLevel) b.level();
        if (!(sl.getBlockEntity(pos)
                instanceof
                net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity furnace))
            return false;
        int items = heart >= 2 ? 64 : 32;
        // a furnace cooks an item in 200 ticks; smokers and blast furnaces in 100
        int perItem =
                furnace instanceof net.minecraft.world.level.block.entity.FurnaceBlockEntity
                        ? 200
                        : 100;
        var acc = (com.ethermod.mixin.FurnaceAccessor) furnace;
        int ticks = Math.max(acc.ethermod$getLitTime(), items * perItem);
        acc.ethermod$setLitTime(ticks);
        acc.ethermod$setLitDuration(ticks);
        BlockState bs = sl.getBlockState(pos);
        if (bs.hasProperty(net.minecraft.world.level.block.AbstractFurnaceBlock.LIT)) {
            sl.setBlock(
                    pos,
                    bs.setValue(net.minecraft.world.level.block.AbstractFurnaceBlock.LIT, true),
                    Block.UPDATE_ALL);
        }
        furnace.setChanged();
        sl.playSound(null, pos, com.ethermod.sound.ModSounds.WORLD_BUCKET_EMPTY_LAVA, SoundSource.BLOCKS, 1f, 0.9f);
        sl.sendParticles(
                b.isSoulFire() ? ParticleTypes.SOUL_FIRE_FLAME : ParticleTypes.FLAME,
                pos.getX() + 0.5,
                pos.getY() + 0.8,
                pos.getZ() + 0.5,
                12,
                0.3,
                0.2,
                0.3,
                0.02);
        return true;
    }

    /** Kiln: smelts dropped items near a fire impact (1 block, then 3x3, 5x5 and food too). */
    static void kiln(BeamBallEntity b, Vec3 at) {
        int k = b.rank("pyrotechnic.kiln");
        if (true) return; // Kiln is furnace fuelling, not dropped-item smelting.
        ServerLevel sl = (ServerLevel) b.level();
        double r = 0.5 + (k - 1);
        for (ItemEntity ie :
                sl.getEntitiesOfClass(
                        ItemEntity.class, new AABB(at, at).inflate(r + 0.5, 1, r + 0.5))) {
            ItemStack in = ie.getItem();
            if (k < 3 && in.has(net.minecraft.core.component.DataComponents.FOOD)) continue;
            var recipe =
                    sl.getRecipeManager()
                            .getRecipeFor(
                                    net.minecraft.world.item.crafting.RecipeType.SMELTING,
                                    new net.minecraft.world.item.crafting.SingleRecipeInput(in),
                                    sl);
            if (recipe.isEmpty()) continue;
            ItemStack out = recipe.get().value().getResultItem(sl.registryAccess());
            if (out.isEmpty()) continue;
            ie.setItem(
                    out.copyWithCount(
                            Math.min(out.getMaxStackSize(), out.getCount() * in.getCount())));
            sl.sendParticles(
                    b.isSoulFire() ? ParticleTypes.SOUL_FIRE_FLAME : ParticleTypes.FLAME,
                    ie.getX(),
                    ie.getY() + 0.2,
                    ie.getZ(),
                    4,
                    0.1,
                    0.1,
                    0.1,
                    0.01);
        }
    }

    /** Puts out fire around a spot. */
    static void extinguish(BeamBallEntity b, BlockPos pos, int r) {
        ServerLevel sl = (ServerLevel) b.level();
        for (BlockPos bp : BlockPos.betweenClosed(pos.offset(-r, -1, -r), pos.offset(r, 1, r))) {
            if (sl.getBlockState(bp).is(BlockTags.FIRE)) sl.removeBlock(bp, false);
        }
        for (LivingEntity e : sl.getEntitiesOfClass(LivingEntity.class, new AABB(pos).inflate(r)))
            e.clearFire();
    }

    /** Water on lava: sources become obsidian, flowing lava cobblestone. */
    static void quenchLava(BeamBallEntity b, BlockPos pos, int r) {
        ServerLevel sl = (ServerLevel) b.level();
        for (BlockPos bp : BlockPos.betweenClosed(pos.offset(-r, -1, -r), pos.offset(r, 1, r))) {
            FluidState fs = sl.getFluidState(bp);
            if (!fs.is(FluidTags.LAVA) || !b.mayBuild(bp)) continue;
            sl.setBlockAndUpdate(
                    bp, (fs.isSource() ? Blocks.OBSIDIAN : Blocks.COBBLESTONE).defaultBlockState());
            sl.levelEvent(1501, bp, 0);
        }
    }
}
