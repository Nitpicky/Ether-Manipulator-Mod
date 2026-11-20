package com.ethermod.beam;

import com.ethermod.manipulator.Manipulator;
import com.ethermod.trait.Traits;

import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Tectonic: the Mass Driver mode.
 *
 * <p>Left click taps one block out of the world; holding left click and sweeping picks up more.
 * Everything carried is one rigid formation that keeps its world shape: each block sits where it
 * was relative to the first one. The formation floats a little in front of you and follows your
 * aim with weight (heavier the more blocks it holds) and turns with your view. It never goes
 * through walls: it bounces off them, and swung into a creature it hits it and bounces back.
 * Left click again lets it go (the blocks drop and settle where they land); right click charges
 * and throws it: thrown blocks bounce once off what they hit, then place themselves.
 *
 * <p>Steady Hand trades all of the fighting for building: no turning, no lag, no bouncing and no
 * damage. The formation snaps to the grid exactly where it will be set down (ghosts show any block
 * that can't go there), and left click places it as it is.
 *
 * <p>The server moves every piece each tick (the client only follows, see BeamBallEntity.tick).
 */
public final class MassDriver {

    private MassDriver() {}

    private static final String TAG = "mm_mass_block";
    public static final double REACH = 6.0;
    /** Ticks a freshly lifted block takes to fly into its slot. */
    private static final int ARRIVE = 4;

    /** True while a lift posts its BreakEvent (so break listeners leave it alone). */
    public static boolean lifting;

    private static final class Piece {
        final BeamBallEntity entity;
        final BlockPos offset; // from the first block, in the world shape
        Vec3 from;
        int arriving;

        Piece(BeamBallEntity entity, BlockPos offset) {
            this.entity = entity;
            this.offset = offset;
        }
    }

    private static final class Hold {
        final ServerPlayer player;
        final List<Piece> pieces = new ArrayList<>();
        final Set<BlockPos> sources = new HashSet<>();
        final BlockPos origin;
        /** The formation's middle (mean offset); {@link #center} is where it is in the world. */
        Vec3 pivot = Vec3.ZERO, center, velocity = Vec3.ZERO;
        float yaw, pitch;
        final float startYaw, startPitch;
        long bumpUntil;

        Hold(ServerPlayer p, BlockPos origin) {
            player = p;
            this.origin = origin;
            startYaw = p.getYRot();
            startPitch = p.getXRot();
        }
    }

    private static final Map<UUID, Hold> HOLDS = new HashMap<>();
    private static final Map<BeamBallEntity, Hold> PIECES = new IdentityHashMap<>();

    public static boolean isHeld(BeamBallEntity entity) {
        return PIECES.containsKey(entity);
    }

    public static boolean holding(ServerPlayer p) {
        return HOLDS.containsKey(p.getUUID());
    }

    private static boolean steady(ServerPlayer p) {
        return Traits.has(Manipulator.stack(p), "tectonic.steady_hand");
    }

    // ---------------------------------------------------------------- lifting

    /** LMB pressed while carrying nothing: lifts the block aimed at. */
    public static boolean press(ServerPlayer p, ItemStack st) {
        if (holding(p)) {
            release(p, true);
            return false;
        }
        var hit = aim(p);
        if (hit == null) return false;
        var hold = new Hold(p, hit.getBlockPos().immutable());
        HOLDS.put(p.getUUID(), hold);
        if (!take(hold, st, hit.getBlockPos())) {
            HOLDS.remove(p.getUUID());
            return false;
        }
        return true;
    }

    /** LMB held: sweeping over blocks picks them up too (Mass Pickup: a 3x3x3 at a time). */
    public static void paint(ServerPlayer p, ItemStack st, int held) {
        if (held < 6 || held % 3 != 0) return;
        var h = HOLDS.get(p.getUUID());
        var hit = aim(p);
        if (h == null || hit == null) return;
        if (Traits.has(st, "tectonic.mass_pickup")) {
            for (var at :
                    BlockPos.betweenClosed(
                            hit.getBlockPos().offset(-1, -1, -1), hit.getBlockPos().offset(1, 1, 1)))
                take(h, st, at.immutable());
        } else take(h, st, hit.getBlockPos());
    }

    private static boolean take(Hold h, ItemStack st, BlockPos at) {
        var p = h.player;
        var level = p.level();
        var bs = level.getBlockState(at);
        if (h.sources.contains(at)
                || h.pieces.size() >= com.ethermod.config.ServerConfig.massMax()
                || !canLift(level, p, at, bs)
                || p.blockActionRestricted(level, at, p.gameMode.getGameModeForPlayer()))
            return false;
        lifting = true;
        boolean cancelled;
        try {
            cancelled =
                    net.neoforged.neoforge.common.NeoForge.EVENT_BUS
                            .post(new net.neoforged.neoforge.event.level.block.BreakBlockEvent(level, at, bs, p))
                            .isCanceled();
        } finally {
            lifting = false;
        }
        if (cancelled) return false;
        var entity =
                new BeamBallEntity(
                        level, p, 0, com.ethermod.item.ManipulatorStage.get(st), com.ethermod.item.OrbMode.MASS);
        entity.configure(st);
        entity.setCarried(bs);
        var be = level.getBlockEntity(at);
        if (be != null) entity.carriedBe = be.saveWithFullMetadata(level.registryAccess());
        entity.setPos(Vec3.atCenterOf(at));
        entity.setDeltaMovement(Vec3.ZERO);
        var piece = new Piece(entity, at.subtract(h.origin));
        entity.setMassHeld(true, piece.offset);
        if (!level.addFreshEntity(entity)) return false; // nothing taken: the block keeps its contents
        // emptied only once the piece carries the copy, so removing the block spills nothing
        if (be instanceof net.minecraft.world.Container box) box.clearContent();
        level.setBlock(at, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        // the rip (FX bench): the block's own break, low, over gravel and deepslate, once per grab
        level.playSound(null, at, bs.getSoundType().getBreakSound(), SoundSource.BLOCKS, .4f, .8f);
        if (h.sources.isEmpty()) {
            level.playSound(null, at, com.ethermod.sound.ModSounds.WORLD_GRAVEL_BREAK, SoundSource.BLOCKS, .5f, .7f);
            level.playSound(null, at, com.ethermod.sound.ModSounds.WORLD_DEEPSLATE_BREAK, SoundSource.BLOCKS, .25f, .7f);
        }
        h.sources.add(at.immutable());
        piece.from = Vec3.atCenterOf(at);
        piece.arriving = h.pieces.isEmpty() ? 0 : ARRIVE;
        h.pieces.add(piece);
        PIECES.put(entity, h);
        // the middle moves with the new block; the formation stays where it is in the world
        Vec3 pivot = Vec3.ZERO;
        for (var q : h.pieces) pivot = pivot.add(Vec3.atLowerCornerOf(q.offset));
        pivot = pivot.scale(1.0 / h.pieces.size());
        if (h.center == null) h.center = Vec3.atCenterOf(at).add(pivot);
        else h.center = h.center.add(turn(pivot.subtract(h.pivot), h.yaw, h.pitch));
        h.pivot = pivot;
        setCarried(st, bs);
        Manipulator.sync(p);
        return true;
    }

    // ---------------------------------------------------------------- carrying

    /** The held pieces skip their own physics: the formation moves them (see {@link #tick}). */
    public static boolean heldTick(BeamBallEntity entity) {
        return PIECES.containsKey(entity);
    }

    @SubscribeEvent
    public static void tick(PlayerTickEvent.Post ev) {
        if (!(ev.getEntity() instanceof ServerPlayer p)) return;
        var h = HOLDS.get(p.getUUID());
        if (h == null) return;
        if (!p.isAlive()
                || !Manipulator.isSummoned(p)
                || com.ethermod.item.OrbMode.get(Manipulator.stack(p)) != com.ethermod.item.OrbMode.MASS) {
            release(p, false);
            return;
        }
        h.pieces.removeIf(q -> q.entity.isRemoved());
        if (h.pieces.isEmpty()) {
            release(p, false);
            return;
        }
        if (steady(p)) steadyMove(h);
        else swing(h);
        for (var q : h.pieces) {
            Vec3 slot = slot(h, q);
            Vec3 at = slot;
            if (q.arriving > 0) {
                float t = 1 - --q.arriving / (float) ARRIVE;
                at = q.from.add(slot.subtract(q.from).scale(t * t * (3 - 2 * t)));
            }
            q.entity.setPos(at);
            q.entity.setDeltaMovement(h.velocity);
            q.entity.setYRot(-h.yaw);
            q.entity.setXRot(h.pitch);
        }
    }

    /** Where a piece belongs in the formation right now. */
    private static Vec3 slot(Hold h, Piece q) {
        return h.center.add(turn(Vec3.atLowerCornerOf(q.offset).subtract(h.pivot), h.yaw, h.pitch));
    }

    private static Vec3 turn(Vec3 v, float yaw, float pitch) {
        return v.xRot((float) -Math.toRadians(pitch)).yRot((float) -Math.toRadians(yaw));
    }

    /** How far the formation reaches from its middle. */
    private static double extent(Hold h) {
        double r = 0;
        for (var q : h.pieces) r = Math.max(r, Vec3.atLowerCornerOf(q.offset).distanceTo(h.pivot));
        return r;
    }

    /** Free swinging: weight, turning with the view, bouncing off walls and creatures. */
    private static void swing(Hold h) {
        var p = h.player;
        var sl = p.level();
        Vec3 target = p.getEyePosition().add(p.getLookAngle().scale(2.2 + extent(h)));
        // heavier with every block: slower to follow, longer to settle
        double weight = 1 + .12 * (h.pieces.size() - 1);
        h.velocity = h.velocity.scale(Math.pow(.72, 1 / Math.sqrt(weight))).add(target.subtract(h.center).scale(.2 / weight));
        if (h.velocity.length() > 1.4) h.velocity = h.velocity.normalize().scale(1.4);

        // turn with the view, unless the turned shape would be inside a wall
        float wantYaw = Mth.wrapDegrees(p.getYRot() - h.startYaw);
        float wantPitch = Mth.clamp(p.getXRot() - h.startPitch, -80, 80);
        float ease = (float) (.3 / weight);
        float yaw = h.yaw + Mth.wrapDegrees(wantYaw - h.yaw) * ease;
        float pitch = h.pitch + (wantPitch - h.pitch) * ease;
        if (fits(h, h.center, yaw, pitch) || !fits(h, h.center, h.yaw, h.pitch)) {
            h.yaw = yaw;
            h.pitch = pitch;
        }

        // move axis by axis in small steps; a blocked axis bounces
        Vec3 v = h.velocity;
        int steps = Math.max(1, (int) Math.ceil(v.length() / .3));
        double vx = v.x, vy = v.y, vz = v.z;
        Vec3 c = h.center;
        boolean bumped = false;
        // already inside a wall (a block flew into a slot that is): move freely until it is out
        if (!fits(h, c, h.yaw, h.pitch)) steps = 0;
        if (steps == 0) c = c.add(v);
        for (int i = 0; i < steps; i++) {
            Vec3 n = c.add(vx / steps, 0, 0);
            if (fits(h, n, h.yaw, h.pitch)) c = n;
            else if (vx != 0) {
                bumped |= Math.abs(vx) > .12;
                vx = -vx * .5;
            }
            n = c.add(0, vy / steps, 0);
            if (fits(h, n, h.yaw, h.pitch)) c = n;
            else if (vy != 0) {
                bumped |= Math.abs(vy) > .12;
                vy = -vy * .5;
            }
            n = c.add(0, 0, vz / steps);
            if (fits(h, n, h.yaw, h.pitch)) c = n;
            else if (vz != 0) {
                bumped |= Math.abs(vz) > .12;
                vz = -vz * .5;
            }
        }
        h.center = c;
        h.velocity = new Vec3(vx, vy, vz);
        if (bumped) bump(h, h.pieces.get(0).entity.getCarried(), c);

        // creatures in the way get hit, and the formation bounces back off them
        double speed = v.length();
        long now = sl.getGameTime();
        for (var q : h.pieces) {
            if (q.arriving > 0) continue;
            AABB box = AABB.ofSize(slot(h, q), 1, 1, 1);
            for (var mob :
                    sl.getEntitiesOfClass(
                            LivingEntity.class, box.inflate(.1), e -> com.ethermod.trait.Allegiance.enemy(p, e))) {
                if (com.ethermod.util.MobMemory.peek(mob).getLongOr("mm_mass_hit", 0L) > now) continue;
                if (speed < .08) continue;
                com.ethermod.util.MobMemory.of(mob).putLong("mm_mass_hit", now + 8);
                float hard = Math.max(1, q.entity.getCarried().getDestroySpeed(sl, q.entity.blockPosition()));
                mob.hurt(p.damageSources().playerAttack(p), (float) Math.min(16, (2 + hard * .5) * (1 + Math.min(1.4, speed) * .5)));
                com.ethermod.trait.Stasis.impulse(mob, v.scale(.8).add(0, .25, 0));
                Vec3 away = h.center.subtract(mob.getBoundingBox().getCenter());
                away = away.lengthSqr() < 1e-4 ? v.scale(-1) : away;
                h.velocity = away.normalize().scale(Math.max(.35, speed * .7));
                bump(h, q.entity.getCarried(), slot(h, q));
            }
        }
    }

    /** Steady Hand: snapped to the grid where left click will set it down; no weight, no turning. */
    private static void steadyMove(Hold h) {
        h.yaw = h.pitch = 0;
        h.velocity = Vec3.ZERO;
        BlockPos base = steadyBase(h);
        // the origin block sits on the grid at base; the formation's middle follows from it
        h.center = Vec3.atCenterOf(base).add(h.pivot);
    }

    /** Steady Hand: where the first block goes (on the face aimed at, or out in front). */
    private static BlockPos steadyBase(Hold h) {
        var p = h.player;
        var hit = aim(p);
        if (hit != null) return hit.getBlockPos().relative(hit.getDirection());
        return BlockPos.containing(p.getEyePosition().add(p.getLookAngle().scale(2.2 + extent(h))))
                .subtract(BlockPos.containing(h.pivot));
    }

    /** Would every settled block of the formation be out of the walls here? */
    private static boolean fits(Hold h, Vec3 center, float yaw, float pitch) {
        var sl = h.player.level();
        for (var q : h.pieces) {
            if (q.arriving > 0) continue;
            Vec3 at = center.add(turn(Vec3.atLowerCornerOf(q.offset).subtract(h.pivot), yaw, pitch));
            if (!sl.noCollision(AABB.ofSize(at, .9, .9, .9))) return false;
        }
        return true;
    }

    private static void bump(Hold h, BlockState bs, Vec3 at) {
        long now = h.player.level().getGameTime();
        if (now < h.bumpUntil) return;
        h.bumpUntil = now + 5;
        var sound = bs.getSoundType();
        h.player.level().playSound(null, at.x, at.y, at.z, sound.getHitSound(), SoundSource.PLAYERS, 1f, sound.getPitch() * .7f);
    }

    // ---------------------------------------------------------------- letting go

    /**
     * LMB again: with Steady Hand the formation is placed exactly as it floats (only if every block
     * fits); otherwise ({@code place} false, or no Steady Hand) the blocks drop and settle where
     * they land.
     */
    public static void release(ServerPlayer p, boolean place) {
        var h = HOLDS.get(p.getUUID());
        if (h == null) return;
        var sl = p.level();
        if (place && steady(p)) {
            BlockPos base = steadyBase(h);
            // the shape must fit as a whole; the ghosts show what is in the way. The world checks
            // go first for every block, so claims are only asked once the whole shape fits
            boolean fits = true;
            for (var q : h.pieces) fits &= fitsHere(sl, q.entity.getCarried(), base.offset(q.offset));
            if (fits)
                for (var q : h.pieces)
                    if (!com.ethermod.util.Protect.may(sl, p, base.offset(q.offset))) {
                        fits = false;
                        break;
                    }
            if (!fits) {
                sl.playSound(null, p.blockPosition(), com.ethermod.sound.ModSounds.WORLD_DISPENSER_FAIL, SoundSource.PLAYERS, .6f, 1.4f);
                return;
            }
            HOLDS.remove(p.getUUID());
            for (var q : h.pieces) {
                PIECES.remove(q.entity);
                var bs = q.entity.getCarried();
                BlockPos at = base.offset(q.offset);
                sl.setBlockAndUpdate(at, bs);
                OrbMass.restoreBe(sl, at, q.entity);
                sl.playSound(null, at, bs.getSoundType().getPlaceSound(), SoundSource.BLOCKS, .8f, bs.getSoundType().getPitch() * .9f);
                if (q.offset.equals(BlockPos.ZERO)) // a heavy thud as it lands (FX bench)
                    sl.playSound(null, at, com.ethermod.sound.ModSounds.WORLD_HEAVY_CORE_PLACE, SoundSource.BLOCKS, .35f, .8f);
                q.entity.setCarried(Blocks.AIR.defaultBlockState());
                q.entity.discard();
            }
        } else {
            HOLDS.remove(p.getUUID());
            for (var q : h.pieces) {
                PIECES.remove(q.entity);
                if (q.entity.getCarried().isAir()) {
                    q.entity.discard();
                    continue;
                }
                q.entity.setDeltaMovement(h.velocity.scale(.5));
                q.entity.releaseMass(0, true);
            }
        }
        setCarried(Manipulator.stack(p), null);
        Manipulator.sync(p);
    }

    /** Could this block be set down here (same rules as the client's ghosts)? */
    public static boolean canPlace(Level level, Player p, BlockState bs, BlockPos at) {
        return fitsHere(level, bs, at) && com.ethermod.util.Protect.may(level, p, at);
    }

    /** The world half of {@link #canPlace}: room and support, no protection probe. */
    private static boolean fitsHere(Level level, BlockState bs, BlockPos at) {
        return level.hasChunkAt(at)
                && level.getBlockState(at).canBeReplaced()
                && bs.canSurvive(level, at)
                && level.isUnobstructed(bs, at, CollisionContext.empty());
    }

    /** RMB released: throws the formation along the aim, faster with more charge. */
    public static void launch(ServerPlayer p, int phase) {
        var h = HOLDS.remove(p.getUUID());
        if (h == null) return;
        boolean gentle = steady(p);
        var velocity =
                p.getLookAngle().scale(com.ethermod.item.OrbMode.MASS.speed(phase)).add(0, .08, 0).add(h.velocity.scale(.3));
        for (var q : h.pieces) {
            PIECES.remove(q.entity);
            if (q.entity.getCarried().isAir()) {
                q.entity.discard();
                continue;
            }
            q.entity.setDeltaMovement(velocity);
            // a thrown block bounces once off what it hits, then places itself; Steady Hand's
            // blocks don't fight: no bounce, no damage
            q.entity.releaseMass(gentle ? 0 : 1, gentle);
        }
        setCarried(Manipulator.stack(p), null);
        Manipulator.sync(p);
        p.level()
                .playSound(null, p.getX(), p.getEyeY(), p.getZ(), com.ethermod.sound.ModSounds.WORLD_BREEZE_SHOOT, SoundSource.PLAYERS, .8f, .7f - .1f * phase);
    }

    @SubscribeEvent
    public static void logout(net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent ev) {
        if (ev.getEntity() instanceof ServerPlayer p) release(p, false);
    }

    @SubscribeEvent
    public static void stop(net.neoforged.neoforge.event.server.ServerStoppingEvent ev) {
        for (var h : new ArrayList<>(HOLDS.values())) release(h.player, false);
        HOLDS.clear();
        PIECES.clear();
    }

    // ---------------------------------------------------------------- the stack's record

    public static BlockState carried(ItemStack stack) {
        CompoundTag t = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        if (!t.contains(TAG)) return Blocks.AIR.defaultBlockState();
        return NbtUtils.readBlockState(BuiltInRegistries.BLOCK, t.getCompoundOrEmpty(TAG));
    }

    public static void setCarried(ItemStack stack, BlockState state) {
        CompoundTag t = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        if (state == null || state.isAir()) t.remove(TAG);
        else t.put(TAG, NbtUtils.writeBlockState(state));
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(t));
    }

    /** The block in reach under the crosshair, or null. */
    public static BlockHitResult aim(Player p) {
        HitResult h = p.pick(REACH, 1f, false);
        return h instanceof BlockHitResult bh && h.getType() == HitResult.Type.BLOCK ? bh : null;
    }

    /** Ordinary solid blocks only: no fluids, containers, two-part blocks or unbreakables. */
    public static boolean canLift(Level level, Player p, BlockPos pos, BlockState bs) {
        if (bs.isAir() || !bs.getFluidState().isEmpty() || com.ethermod.config.ServerConfig.blacklisted(bs)) return false;
        if (bs.hasBlockEntity()
                && (!com.ethermod.config.ServerConfig.massLiftsContainers()
                        || bs.hasProperty(BlockStateProperties.CHEST_TYPE)
                                && bs.getValue(BlockStateProperties.CHEST_TYPE) != net.minecraft.world.level.block.state.properties.ChestType.SINGLE))
            return false;
        float hard = bs.getDestroySpeed(level, pos);
        if (hard < 0 || hard > 50) return false;
        if (bs.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF) || bs.hasProperty(BlockStateProperties.BED_PART))
            return false;
        if (bs.getBlock().asItem() == net.minecraft.world.item.Items.AIR) return false;
        // claims answer the BreakEvent take() posts for the lift itself; probing here as well
        // would post two break events for one lifted block
        return com.ethermod.util.Protect.mayInteract(level, p, pos);
    }
}
