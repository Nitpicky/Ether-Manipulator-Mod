package com.ethermod.beam;

import com.ethermod.item.OrbMode;
import com.ethermod.manipulator.Manipulator;
import com.ethermod.trait.Traits;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LayeredCauldronBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Vacuum mode (always available): while RMB is held, a suction cone in front of the nozzle.
 *
 * <p>Items and XP in the cone fly to you. Liquid: a tap drains the tile you aim at; keep holding
 * and it spreads to the touching tiles of the same liquid, a ring every few ticks. It also drains
 * cauldrons and other mods' tanks. Drained liquid is only kept with a tank: water with Hydraulics,
 * lava with Pyrotechnics (Wellspring stores 2-4 buckets per water tile).
 */
public final class VacuumCone {

    private VacuumCone() {}

    /** How far the drain reaches from the first tile, and from you. */
    private static final double REACH = 6.0;

    private static final int PER_RING = 12;

    private static final class Hold {
        long last;
        BlockPos origin;
        boolean lava;
        final Set<Long> seen = new HashSet<>();
        List<BlockPos> frontier = new ArrayList<>();
    }

    private static final Map<UUID, Hold> HOLDS = new HashMap<>();

    /** Called every server tick while the owner holds RMB in Vacuum mode. */
    public static void tick(ServerPlayer sp, ItemStack manip, int heldTicks) {
        ServerLevel sl = sp.serverLevel();
        // Pneumatic: the badge reaches 2 blocks farther; Turbine x1.5, x2, x3
        boolean pneumatic = Traits.hasLine(manip, Traits.PNEUMATIC);
        int turbine = Traits.rank(manip, "pneumatic.turbine");
        double len = Math.min(7, 3 + heldTicks / 15.0) + (pneumatic ? 2 : 0);
        if (turbine > 0) len *= turbine == 1 ? 1.5 : turbine == 2 ? 2 : 3;
        double cos = Math.cos(Math.toRadians(Math.min(40, 22 + heldTicks / 4.0)));
        Vec3 eye = sp.getEyePosition();
        Vec3 look = sp.getLookAngle();
        AABB box = new AABB(eye, eye.add(look.scale(len))).inflate(len * 0.6);

        // Items and XP: pulled toward you, picked up on arrival (Turbine III: small mobs too)
        boolean compressor = Traits.has(manip, "pneumatic.compressor");
        for (Entity e :
                sl.getEntities(
                        sp,
                        box,
                        e ->
                                e instanceof ItemEntity
                                        || e instanceof ExperienceOrb
                                        || turbine >= 3
                                                && e instanceof net.minecraft.world.entity.Mob m
                                                && m.getBbWidth() <= 0.7f
                                                && !(e
                                                        instanceof
                                                        net.minecraft.world.entity.monster
                                                                .Enemy))) {
            if (!inCone(eye, look, len, cos, e.position().add(0, 0.25, 0))) continue;
            Vec3 to = sp.position().add(0, 0.6, 0).subtract(e.position());
            // Compressor: items go straight into the inventory once close
            if (compressor && e instanceof ItemEntity ie && to.length() < 2.5) {
                ItemStack st = ie.getItem().copy();
                if (sp.getInventory().add(st)) {
                    ie.discard();
                    sl.playSound(
                            null,
                            sp.getX(),
                            sp.getY(),
                            sp.getZ(),
                            SoundEvents.ITEM_PICKUP,
                            SoundSource.PLAYERS,
                            0.2f,
                            1.6f);
                } else {
                    ie.setItem(st);
                }
                continue;
            }
            e.setDeltaMovement(e.getDeltaMovement().scale(0.5).add(to.normalize().scale(0.45)));
            e.hurtMarked = true;
            if (e instanceof ItemEntity ie) ie.setNoPickUpDelay();
        }
        if (compressor && heldTicks % 20 == 0)
            Compressor.pack(sp, Traits.rank(manip, "pneumatic.compressor"));

        // Vortex: mobs in the cone are dragged in and spun, hurt every second
        if (Traits.has(manip, "pneumatic.vortex")) vortex(sp, sl, eye, look, len, cos, heldTicks);
        // Stasis: the mob you aim at hangs helpless in the air
        if (Traits.has(manip, "pneumatic.stasis")) stasis(sp, sl, eye, look, len);

        // Liquids: the aimed tile first, then ring after ring of its neighbours
        long now = sl.getGameTime();
        Hold h = HOLDS.get(sp.getUUID());
        if (h == null || now - h.last > 2) {
            h = new Hold();
            HOLDS.put(sp.getUUID(), h);
        }
        h.last = now;
        Store store = new Store(manip);
        if (h.origin == null) {
            if (heldTicks % 4 == 0 || heldTicks <= 1) start(sp, sl, h, store, eye, look);
        } else if (heldTicks % 6 == 0) {
            spread(sp, sl, h, store, eye);
        }
        store.commit(sp, sl);
        if (HOLDS.size() > 64) HOLDS.values().removeIf(x -> now - x.last > 200);

        // Particles streaming into the nozzle
        if (heldTicks % 2 == 0) {
            for (int i = 0; i < 3; i++) {
                double t = 0.3 + sl.random.nextDouble() * 0.7;
                Vec3 off =
                        new Vec3(
                                        sl.random.nextGaussian(),
                                        sl.random.nextGaussian(),
                                        sl.random.nextGaussian())
                                .scale(0.35 * t * len * 0.3);
                Vec3 p = eye.add(look.scale(t * len)).add(off);
                Vec3 d = eye.subtract(p).normalize();
                sl.sendParticles(
                        ParticleTypes.REVERSE_PORTAL, p.x, p.y, p.z, 0, d.x, d.y, d.z, 0.6);
            }
        }
        if (heldTicks % 20 == 0) {
            sl.playSound(
                    null,
                    sp.getX(),
                    sp.getEyeY(),
                    sp.getZ(),
                    SoundEvents.BREEZE_INHALE,
                    SoundSource.PLAYERS,
                    0.25f,
                    1.6f);
        }
    }

    private static void vortex(
            ServerPlayer sp,
            ServerLevel sl,
            Vec3 eye,
            Vec3 look,
            double len,
            double cos,
            int heldTicks) {
        Vec3 centre = eye.add(look.scale(Math.min(3, len * 0.4)));
        AABB box = new AABB(eye, eye.add(look.scale(len))).inflate(len * 0.6);
        for (net.minecraft.world.entity.LivingEntity e :
                sl.getEntitiesOfClass(
                        net.minecraft.world.entity.LivingEntity.class,
                        box,
                        e ->
                                !com.ethermod.beam.Mounts.own(sp, e)
                                        && e.isAlive()
                                        && !(e
                                                instanceof
                                                net.minecraft.world.entity.player.Player))) {
            if (!inCone(eye, look, len, cos, e.position().add(0, e.getBbHeight() * 0.5, 0)))
                continue;
            Vec3 off = e.position().subtract(centre);
            Vec3 tangent = new Vec3(-off.z, 0, off.x).normalize().scale(0.3);
            Vec3 in = off.scale(-0.12);
            e.setDeltaMovement(e.getDeltaMovement().scale(0.4).add(tangent).add(in.x, 0.05, in.z));
            e.hurtMarked = true;
            if (heldTicks % 20 == 0) {
                e.hurt(sl.damageSources().playerAttack(sp), 3f);
                sl.sendParticles(
                        ParticleTypes.GUST, e.getX(), e.getY(0.5), e.getZ(), 1, 0, 0, 0, 0);
            }
        }
    }

    private static void stasis(ServerPlayer sp, ServerLevel sl, Vec3 eye, Vec3 look, double len) {
        Vec3 end = eye.add(look.scale(len));
        var hit =
                net.minecraft.world.entity.projectile.ProjectileUtil.getEntityHitResult(
                        sl,
                        sp,
                        eye,
                        end,
                        sp.getBoundingBox().expandTowards(look.scale(len)).inflate(1),
                        e ->
                                e instanceof net.minecraft.world.entity.LivingEntity
                                        && e.isAlive()
                                        && !(e instanceof net.minecraft.world.entity.player.Player)
                                        && !e.getType()
                                                .is(
                                                        net.neoforged.neoforge.common.Tags
                                                                .EntityTypes.BOSSES));
        if (hit == null) return;
        Entity e = hit.getEntity();
        double ground =
                e.getY()
                        - sl.getHeight(
                                net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING,
                                e.getBlockX(),
                                e.getBlockZ());
        e.setDeltaMovement(0, ground < 1.5 ? 0.12 : 0.0, 0);
        e.hurtMarked = true;
        e.resetFallDistance();
        if (e instanceof net.minecraft.world.entity.Mob m) {
            m.getNavigation().stop();
            m.setTarget(null);
        }
        if (sp.tickCount % 4 == 0)
            sl.sendParticles(
                    ParticleTypes.REVERSE_PORTAL,
                    e.getX(),
                    e.getY(0.5),
                    e.getZ(),
                    4,
                    0.3,
                    0.4,
                    0.3,
                    0.02);
    }

    private static boolean inCone(Vec3 eye, Vec3 look, double len, double cos, Vec3 p) {
        Vec3 d = p.subtract(eye);
        double dist = d.length();
        if (dist < 0.3) return true;
        if (dist > len + 0.5) return false;
        return d.scale(1 / dist).dot(look) >= cos;
    }

    /** The first tile: the liquid you aim at, or a cauldron / tank. */
    private static void start(
            ServerPlayer sp, ServerLevel sl, Hold h, Store store, Vec3 eye, Vec3 look) {
        Vec3 end = eye.add(look.scale(REACH));
        BlockHitResult fluidHit =
                sl.clip(
                        new ClipContext(
                                eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.ANY, sp));
        if (fluidHit.getType() == HitResult.Type.BLOCK) {
            BlockPos p = fluidHit.getBlockPos();
            FluidState fs = sl.getFluidState(p);
            if (!fs.isEmpty() && sl.mayInteract(sp, p)) {
                h.origin = p.immutable();
                h.lava = fs.is(FluidTags.LAVA);
                h.seen.add(p.asLong());
                if (drain(sl, p, store)) h.frontier.add(h.origin);
                return;
            }
            // no liquid there: a cauldron or another mod's tank
            if (sl.mayInteract(sp, p)) drainContainer(sl, p, fluidHit.getDirection(), store);
        }
    }

    /** One ring outward from the tiles drained last time. */
    private static void spread(ServerPlayer sp, ServerLevel sl, Hold h, Store store, Vec3 eye) {
        List<BlockPos> next = new ArrayList<>();
        for (BlockPos p : h.frontier) {
            for (Direction d : Direction.values()) {
                if (next.size() >= PER_RING) break;
                BlockPos q = p.relative(d);
                if (!h.seen.add(q.asLong())) continue;
                if (q.distSqr(h.origin) > REACH * REACH
                        || Vec3.atCenterOf(q).distanceTo(eye) > REACH + 2) continue;
                FluidState fs = sl.getFluidState(q);
                if (fs.isEmpty() || fs.is(FluidTags.LAVA) != h.lava || !sl.mayInteract(sp, q))
                    continue;
                if (drain(sl, q, store)) next.add(q.immutable());
            }
        }
        h.frontier = next;
    }

    /** Removes the liquid at a tile (sources go into the tank if there's room). */
    private static boolean drain(ServerLevel sl, BlockPos p, Store store) {
        BlockState bs = sl.getBlockState(p);
        FluidState fs = sl.getFluidState(p);
        boolean water = fs.is(FluidTags.WATER), lava = fs.is(FluidTags.LAVA);
        if (bs.getBlock() instanceof LiquidBlock) {
            if (fs.isSource()) store.add(water, lava);
            sl.setBlock(p, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        } else if (water && bs.hasProperty(BlockStateProperties.WATERLOGGED)) {
            sl.setBlock(p, bs.setValue(BlockStateProperties.WATERLOGGED, false), Block.UPDATE_ALL);
            store.add(true, false);
        } else {
            return false;
        }
        Vec3 c = Vec3.atCenterOf(p);
        sl.sendParticles(
                lava ? ParticleTypes.LAVA : ParticleTypes.SPLASH,
                c.x,
                c.y,
                c.z,
                4,
                0.3,
                0.3,
                0.3,
                0.05);
        store.drank = true;
        store.drankLava |= lava;
        return true;
    }

    /** Cauldrons and other mods' tanks: one bucket, only if the tank has room for it. */
    private static void drainContainer(ServerLevel sl, BlockPos p, Direction face, Store store) {
        BlockState bs = sl.getBlockState(p);
        if (bs.is(Blocks.WATER_CAULDRON)
                && bs.getValue(LayeredCauldronBlock.LEVEL) == 3
                && store.room(true)) {
            sl.setBlockAndUpdate(p, Blocks.CAULDRON.defaultBlockState());
            store.add(true, false);
            store.drank = true;
            return;
        }
        if (bs.is(Blocks.LAVA_CAULDRON) && store.room(false)) {
            sl.setBlockAndUpdate(p, Blocks.CAULDRON.defaultBlockState());
            store.add(false, true);
            store.drank = store.drankLava = true;
            return;
        }
        IFluidHandler handler = sl.getCapability(Capabilities.FluidHandler.BLOCK, p, face);
        if (handler == null) return;
        for (boolean lava : new boolean[] {false, true}) {
            if (!store.room(!lava)) continue;
            FluidStack want = new FluidStack(lava ? Fluids.LAVA : Fluids.WATER, 1000);
            if (handler.drain(want, IFluidHandler.FluidAction.SIMULATE).getAmount() < 1000)
                continue;
            handler.drain(want, IFluidHandler.FluidAction.EXECUTE);
            store.add(!lava, lava);
            store.drank = true;
            store.drankLava |= lava;
            return;
        }
    }

    /** The tanks while one tick of draining runs. */
    private static final class Store {
        final ItemStack manip;
        final int cap, lavaCap, perWater;
        int water, lava;
        final int w0, l0;
        boolean drank, drankLava;

        Store(ItemStack manip) {
            this.manip = manip;
            // (Vacuum is retired; both liquids now fill the one shared tank)
            cap = lavaCap = OrbMode.capacity(manip);
            int well = Traits.rank(manip, "hydraulic.wellspring");
            perWater = well > 0 ? 1 + well : 1; // Wellspring: 2, 3, 4 buckets a tile
            water = w0 = OrbMode.tank(manip);
            lava = l0 = 0;
        }

        boolean room(boolean isWater) {
            return isWater ? water < cap : lava < lavaCap;
        }

        void add(boolean isWater, boolean isLava) {
            if (isWater) water = Math.min(cap, water + perWater);
            else if (isLava) lava = Math.min(lavaCap, lava + 1);
        }

        void commit(ServerPlayer sp, ServerLevel sl) {
            if (water != w0 || lava != l0) {
                OrbMode.setTank(manip, water + lava);
                Manipulator.sync(sp);
            }
            if (drank) {
                sl.playSound(
                        null,
                        sp.getX(),
                        sp.getEyeY(),
                        sp.getZ(),
                        drankLava ? SoundEvents.BUCKET_FILL_LAVA : SoundEvents.BUCKET_FILL,
                        SoundSource.PLAYERS,
                        0.6f,
                        1.2f + sl.random.nextFloat() * 0.2f);
            }
        }
    }
}
