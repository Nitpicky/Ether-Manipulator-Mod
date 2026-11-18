package com.ethermod.beam;

import com.ethermod.item.ManipulatorStage;
import com.ethermod.trait.Traits;
import com.ethermod.util.FoliageHelper;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.*;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Server-side beam ray-cast, fired every BEAM_TICK_INTERVAL ticks from ModEvents.
 *
 * <p>The beam is context sensitive: - on a creature: continuous damage that ramps 1x / 2x / 3x the
 * longer it stays on target (Symmetra) - on a block: mining that ramps from 0.8x to 2x vanilla
 * speed over 2 s - while sneaking: a harmless utility beam that does the right-click jobs vanilla
 * tools lose (till, path, strip, scrape, light campfires/candles) and pulls dropped items and
 * experience towards you
 */
public final class BeamCastHelper {

    private BeamCastHelper() {}

    /**
     * Damage of one strike at pressure 1 is stage.beamTickDamage() (+ the Plasmic badge) scaled by
     * this; pressure raises both the strike and the cadence ({@link BeamState#hitInterval}). No
     * knockback, no i-frames.
     */
    private static final float DAMAGE_SCALE = 1.0f;

    /** Arc: how far a strike can jump from the creature in the beam. */
    private static final double ARC_RANGE = 5;

    /**
     * Tells this player and everyone watching the beam's pressure when it changes, and each
     * strike (with the creatures it arced to), so the beam's look and sound follow the real
     * numbers.
     */
    private static void sync(
            ServerPlayer player, BeamState state, int level, boolean strike, List<Integer> arcs) {
        if (!strike && level == state.sentLevel) return;
        state.sentLevel = level;
        net.neoforged.neoforge.network.PacketDistributor.sendToPlayersTrackingEntityAndSelf(
                player,
                new com.ethermod.network.payload.BeamPulsePayload(
                        player.getId(), level, strike, arcs));
    }

    /** The beam's own damage type: no knockback (tagged), credited to the player. */
    static net.minecraft.world.damagesource.DamageSource beamSource(ServerLevel level, ServerPlayer player) {
        return new net.minecraft.world.damagesource.DamageSource(
                level.registryAccess()
                        .registryOrThrow(net.minecraft.core.registries.Registries.DAMAGE_TYPE)
                        .getHolderOrThrow(BEAM_DAMAGE),
                player);
    }

    public static final net.minecraft.resources.ResourceKey<net.minecraft.world.damagesource.DamageType>
            BEAM_DAMAGE =
                    net.minecraft.resources.ResourceKey.create(
                            net.minecraft.core.registries.Registries.DAMAGE_TYPE,
                            net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(
                                    "ethermod", "beam"));

    public static Vec3 tickBeam(
            ServerLevel level,
            ServerPlayer player,
            ManipulatorStage stage,
            BeamState state,
            int interval) {
        Vec3 eye = player.getEyePosition();
        Vec3 look = player.getLookAngle();
        ItemStack tool = player.getMainHandItem();
        float range = Traits.beamRange(tool);

        if (player.isShiftKeyDown()) {
            Vec3 end =
                    level.clip(
                                    new ClipContext(
                                            eye,
                                            eye.add(look.scale(range)),
                                            ClipContext.Block.COLLIDER,
                                            ClipContext.Fluid.NONE,
                                            player))
                            .getLocation();
            BlockHitResult bh =
                    level.clip(
                            new ClipContext(
                                    eye,
                                    eye.add(look.scale(range)),
                                    ClipContext.Block.OUTLINE,
                                    ClipContext.Fluid.NONE,
                                    player));
            Optional<EntityHitResult> animalHit =
                    (Traits.has(tool, "botanic.herder")
                                    || Traits.has(tool, "botanic.groundskeeper"))
                            ? findEntityOnRay(level, player, eye, look, (float) end.distanceTo(eye))
                            : Optional.empty();
            if (animalHit.isPresent()
                    && animalHit.get().getEntity()
                            instanceof net.minecraft.world.entity.animal.Animal animal) {
                if (Traits.has(tool, "botanic.herder"))
                    UtilityBeam.herd(level, player, animal, state);
                else UtilityBeam.shearEntity(level, player, animal, state);
            } else if (bh.getType() == HitResult.Type.BLOCK) {
                UtilityBeam.apply(level, player, bh, state);
            }
            tractor(level, player, eye, end);
            sync(player, state, state.trackTarget(-1, interval, tool, level), false, List.of());
            return end;
        }

        // --- Strike cadence: pressure sets how often the beam bites ----------
        state.hitCooldown = Math.max(0, state.hitCooldown - interval);
        boolean strike = state.hitCooldown == 0;

        // --- Foliage-aware block ray-cast ------------------------------------
        lastRefract = null;
        Vec3 hitPos = castThroughFoliage(level, player, eye, look, range);
        // --- Photonic: Refraction, the beam splits behind glass and ice
        if (lastRefract != null && strike) {
            float base = (stage.beamTickDamage() + Traits.beamDamageBonus(tool)) * DAMAGE_SCALE;
            for (Vec3 dir : refractRays(look, Traits.rank(tool, "photonic.refraction"))) {
                Vec3 from = lastRefract;
                Vec3 to =
                        level.clip(
                                        new ClipContext(
                                                from,
                                                from.add(dir.scale(range)),
                                                ClipContext.Block.COLLIDER,
                                                ClipContext.Fluid.NONE,
                                                player))
                                .getLocation();
                findEntityOnRay(level, player, from, dir, (float) to.distanceTo(from))
                        .ifPresent(
                                h ->
                                        beamHit(
                                                level,
                                                player,
                                                tool,
                                                (LivingEntity) h.getEntity(),
                                                base * 0.6f));
            }
        }

        // --- Pyrotechnics: Heat Ray lights what the beam touches; Controlled Burn III puts your
        // fire out
        BlockPos touched = BlockPos.containing(hitPos.add(look.scale(0.05)));
        BlockPos before = BlockPos.containing(hitPos.subtract(look.scale(0.05)));
        if (Traits.rank(tool, "pyrotechnic.controlled_burn") >= 3) {
            com.ethermod.trait.WorldEffects.putOut(level, before, player.getUUID());
            com.ethermod.trait.WorldEffects.putOut(level, touched.above(), player.getUUID());
        }
        if (Traits.has(tool, "pyrotechnic.heat_ray") && com.ethermod.util.Protect.may(level, player, touched))
            heatRay(level, player, touched);

        // --- Entity: Lock-On keeps the last target inside a small cone ---------
        LivingEntity target = null;
        Vec3 targetPoint = null;
        if (Traits.has(tool, "plasmic.lock_on")
                && state.level() >= 2
                && level.getEntity(state.targetId) instanceof LivingEntity locked
                && locked.isAlive()) {
            Vec3 to = locked.getBoundingBox().getCenter().subtract(eye);
            if (to.length() <= range
                    && to.normalize().dot(look) > Math.cos(Math.toRadians(12))
                    && player.hasLineOfSight(locked)) {
                target = locked;
                targetPoint = locked.getBoundingBox().getCenter();
            }
        }
        if (target == null) {
            float entityRange = (float) hitPos.distanceTo(eye);
            Optional<EntityHitResult> entityHit =
                    findEntityOnRay(level, player, eye, look, entityRange);
            if (entityHit.isPresent()) {
                target = (LivingEntity) entityHit.get().getEntity();
                targetPoint = entityHit.get().getLocation();
            }
        }

        if (target != null) {
            int combatLevel = state.trackTarget(target.getId(), interval, tool, level);
            player.getPersistentData().putInt("mm_beam_level", combatLevel);
            target.getPersistentData().putInt("mm_combat_pressure", combatLevel);
            if (Traits.has(tool, "pyrotechnic.heat_ray"))
                com.ethermod.trait.RedesignEffects.burn(target, player, 3, -1);
            if (!strike) {
                sync(player, state, combatLevel, false, List.of());
                return Traits.has(tool, "photonic.laser") ? hitPos : targetPoint;
            }
            state.hitCooldown = BeamState.hitInterval(combatLevel);
            float dmg =
                    (stage.beamTickDamage() + Traits.beamDamageBonus(tool))
                            * DAMAGE_SCALE
                            * BeamState.levelMultiplier(combatLevel)
                            // Overload II: the 4th level hits 25% harder
                            * (combatLevel >= 4 && Traits.rank(tool, "plasmic.overload") >= 2
                                    ? 1.25f
                                    : 1f);
            beamHit(level, player, tool, target, dmg);
            // Photonic: Laser, the beam goes through and hits everything in its line
            if (Traits.has(tool, "photonic.laser")) {
                final LivingEntity first = target;
                Vec3 end = eye.add(look.scale(hitPos.distanceTo(eye)));
                for (LivingEntity e :
                        level.getEntitiesOfClass(
                                LivingEntity.class,
                                player.getBoundingBox().expandTowards(end.subtract(eye)).inflate(1),
                                e ->
                                        !com.ethermod.beam.Mounts.own(player, e)
                                                && e != first
                                                && e.isAlive()
                                                && e.isPickable()
                                                && e.getBoundingBox()
                                                        .inflate(0.1)
                                                        .clip(eye, end)
                                                        .isPresent())) {
                    beamHit(level, player, tool, e, dmg);
                }
                targetPoint = hitPos;
            }

            // Arc: pressure jumps the strike to the nearest other creatures, one more per level
            // from level 2 (1 / 2 / 3), each taking half the strike
            List<Integer> arcs = new java.util.ArrayList<>();
            if (combatLevel >= 2 && Traits.has(tool, "plasmic.arc")) {
                final LivingEntity first = target;
                level
                        .getEntitiesOfClass(
                                LivingEntity.class,
                                target.getBoundingBox().inflate(ARC_RANGE),
                                e ->
                                        e != first
                                                && !com.ethermod.beam.Mounts.own(player, e)
                                                && e.isAlive()
                                                && e.isPickable()
                                                && !(e
                                                        instanceof
                                                        net.minecraft.world.entity.player.Player)
                                                && !(e
                                                                instanceof
                                                                net.minecraft.world.entity
                                                                        .OwnableEntity
                                                                own
                                                        && player.getUUID()
                                                                .equals(own.getOwnerUUID())))
                        .stream()
                        .sorted(Comparator.comparingDouble(e -> e.distanceToSqr(first)))
                        .limit(combatLevel - 1)
                        .forEach(
                                e -> {
                                    beamHit(level, player, tool, e, dmg * 0.5f);
                                    arcs.add(e.getId());
                                });
            }
            sync(player, state, combatLevel, true, arcs);
            return targetPoint;
        }
        boolean mining = hitPos.distanceTo(eye) < range - 0.1;
        // Momentum: mining builds (and keeps) the beam's pressure; otherwise it runs down
        boolean momentum = mining && Traits.has(tool, "tectonic.momentum");
        int pressure =
                momentum
                        ? state.trackMining(interval, tool, level)
                        : state.trackTarget(-1, interval, tool, level);
        if (momentum) player.getPersistentData().putInt("mm_beam_level", pressure);
        sync(player, state, pressure, false, List.of());

        // --- Block mining with ramp ------------------------------------------
        if (mining) {
            // step slightly INTO the block that was hit (not back towards the eye)
            BlockPos blockPos = BlockPos.containing(hitPos.add(look.scale(0.05)));
            float rampTicks = Traits.hasCommon(tool, Traits.TECTONIC, 0) ? 32f : 40f;
            float ramp = Math.min(1.0f, state.ticks / rampTicks);
            float speed =
                    (stage.miningSpeed() + Traits.miningSpeedBonus(tool))
                            * (momentum ? BeamState.miningMultiplier(pressure) : 1f);
            // Crack ids must not equal the player's entity id: the server skips
            // sending crack progress to the player whose id matches.
            int id = player.getId();
            Float centerHardness =
                    mine(
                            level,
                            player,
                            stage,
                            tool,
                            blockPos,
                            speed,
                            ramp,
                            interval,
                            crackId(id, 0),
                            null);
            if (centerHardness != null) {
                // Area Bore: 3x3, deeper with rank (see Traits.areaBore)
                int n = 1;
                for (BlockPos p : Traits.areaBore(tool, blockPos, look)) {
                    mine(
                            level,
                            player,
                            stage,
                            tool,
                            p,
                            speed,
                            ramp,
                            interval,
                            crackId(id, n++),
                            centerHardness);
                }
            }
        }
        return hitPos;
    }

    /** A negative id per player and sub-block, so it never matches an entity id. */
    private static int crackId(int playerId, int n) {
        return -1 - ((playerId << 6) + (n & 63));
    }

    /**
     * Applies one beam tick of mining to a block. Returns its hardness, or null if it can't be
     * mined. Neighbours (area mining) pass the centre hardness and are skipped if they are much
     * harder, unharvestable or protected.
     */
    private static Float mine(
            ServerLevel level,
            ServerPlayer player,
            ManipulatorStage stage,
            ItemStack tool,
            BlockPos pos,
            float speed,
            float ramp,
            int interval,
            int breakerId,
            Float centerHardness) {
        BlockState block = level.getBlockState(pos);
        if (block.isAir()
                || !block.getFluidState().isEmpty()
                        && block.getBlock() instanceof net.minecraft.world.level.block.LiquidBlock)
            return null;
        float hardness = block.getDestroySpeed(level, pos);
        if (hardness < 0) return null;
        // Gentle Hands: leave crops that aren't ripe
        if (block.getBlock() instanceof CropBlock crop
                && !crop.isMaxAge(block)
                && Traits.hasLine(tool, Traits.BOTANIC)) {
            // Gentle Hands II: the beam's warmth sometimes grows the crop a stage
            if (centerHardness == null
                    && Traits.rank(tool, "botanic.gentle_hands") >= 2
                    && level.random.nextFloat() < 0.04f * interval) {
                level.setBlockAndUpdate(
                        pos,
                        crop.getStateForAge(Math.min(crop.getMaxAge(), crop.getAge(block) + 1)));
                level.levelEvent(1505, pos, 4);
            }
            return null;
        }
        if (centerHardness != null) {
            if (!stage.canHarvest(block) || hardness > centerHardness * 2 + 0.5f) return null;
            if (!com.ethermod.util.Protect.may(level, player, pos)) return null;
        }
        float dmg;
        if (hardness == 0f || player.isCreative()) {
            dmg = 1.0f;
        } else {
            // Vanilla: speed / hardness / 30 per tick with the right tool, / 100 without.
            // Precision: soft blocks (dirt, sand, gravel, leaves) faster, almost instant at III
            float perTick =
                    speed
                            * Traits.miningSpeedMultiplier(tool, block)
                            / (stage.canHarvest(block) ? 30.0f : 100.0f);
            dmg = perTick * interval * (0.8f + 1.2f * ramp) / hardness;
        }
        // One crack id per player and block: a block keeps its crack (and its
        // progress, see MiningDamageTracker) while the beam works elsewhere.
        int crack = -1 - ((pos.hashCode() * 31 + player.getId()) & 0x3fffffff);
        MiningDamageTracker.applyDamage(level, pos, crack, dmg, player);
        // Vanilla's mining hit sound, every 4 ticks like a pickaxe (the main block only)
        if (centerHardness == null
                && level.getGameTime() % 4 < interval
                && !level.getBlockState(pos).isAir()) {
            net.minecraft.world.level.block.SoundType st = block.getSoundType(level, pos, player);
            level.playSound(
                    null,
                    pos,
                    st.getHitSound(),
                    net.minecraft.sounds.SoundSource.BLOCKS,
                    (st.getVolume() + 1f) / 8f,
                    st.getPitch() * 0.5f);
        }
        return hardness;
    }

    // ---- Excavation: Momentum ------------------------------------------------

    private record Streak(net.minecraft.world.level.block.Block block, int count, long last) {}

    private static final java.util.Map<java.util.UUID, Streak> STREAKS = new java.util.HashMap<>();

    /** Momentum: called when the beam breaks a block; the same type in a row builds a streak. */
    public static void onBeamBreak(ServerPlayer player, BlockState state) {
        ItemStack tool = player.getMainHandItem();
        int rank = Traits.rank(tool, "tectonic.momentum");
        if (rank <= 0) return;
        long now = player.level().getGameTime();
        Streak s = STREAKS.get(player.getUUID());
        int keep = rank >= 3 ? 40 : 20;
        boolean same = s != null && s.block() == state.getBlock() && now - s.last() <= keep;
        STREAKS.put(player.getUUID(), new Streak(state.getBlock(), same ? s.count() + 1 : 1, now));
    }

    /**
     * Momentum's speed factor for mining this block: +10% per block in the streak, up to
     * +30/50/80%.
     */
    private static float momentum(ServerPlayer player, ItemStack tool, BlockState state) {
        int rank = Traits.rank(tool, "tectonic.momentum");
        if (rank <= 0) return 1f;
        Streak s = STREAKS.get(player.getUUID());
        if (s == null || s.block() != state.getBlock()) return 1f;
        long idle = player.level().getGameTime() - s.last();
        if (idle > (rank >= 3 ? 40 : 20) + 40) return 1f; // mining one block takes a while too
        float cap = rank >= 3 ? 0.8f : rank == 2 ? 0.5f : 0.3f;
        return 1f + Math.min(cap, 0.1f * s.count());
    }

    // ---- Mobility: Rocket Beam ------------------------------------------------

    // ---- Pyrotechnics: Heat Ray -----------------------------------------------

    /** Heat Ray: the beam lights candles, campfires and TNT it touches. */
    private static void heatRay(ServerLevel level, ServerPlayer player, BlockPos pos) {
        BlockState bs = level.getBlockState(pos);
        if (net.minecraft.world.level.block.CampfireBlock.canLight(bs)
                || net.minecraft.world.level.block.CandleBlock.canLight(bs)
                || net.minecraft.world.level.block.CandleCakeBlock.canLight(bs)) {
            level.setBlock(
                    pos,
                    bs.setValue(
                            net.minecraft.world.level.block.state.properties.BlockStateProperties
                                    .LIT,
                            true),
                    11);
            level.playSound(
                    null,
                    pos,
                    com.ethermod.sound.ModSounds.WORLD_FLINTANDSTEEL_USE,
                    net.minecraft.sounds.SoundSource.BLOCKS,
                    0.6f,
                    1.2f);
        } else if (bs.getBlock() instanceof net.minecraft.world.level.block.TntBlock) {
            bs.onCaughtFire(level, pos, Direction.UP, player);
            level.removeBlock(pos, false);
        }
    }

    /** One beam hit on a creature, with the Combat line's on-hit traits. */
    private static void beamHit(
            ServerLevel level,
            ServerPlayer player,
            ItemStack tool,
            LivingEntity target,
            float dmg) {
        // Disrupt: the beam goes straight through shields and knocks them out
        if (target instanceof net.minecraft.world.entity.player.Player p
                && p.isBlocking()
                && Traits.has(tool, "plasmic.disrupt")) {
            p.disableShield();
        }
        if (Traits.has(tool, "plasmic.disrupt")
                && player.getPersistentData().getInt("mm_beam_level") >= 2) {
            dmg *= 1.15f;
            target.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, 30, 0, false, false, true));
        }
        // Disrupt II: the beam also slows what it holds
        if (Traits.rank(tool, "plasmic.disrupt") >= 2) {
            target.addEffect(
                    new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 30, 0, false, true, true));
        }
        // Photonic: Sunfire, the beam burns the undead
        int sun = Traits.rank(tool, "photonic.sunfire");
        if (sun > 0 && target.getType().is(net.minecraft.tags.EntityTypeTags.UNDEAD)) {
            dmg *= 1f + 0.5f * sun;
            target.igniteForSeconds(3);
        }
        dmg *= com.ethermod.config.ServerConfig.beamDamage();
        Vec3 incomingVelocity = target.getDeltaMovement();
        float before = target.getHealth();
        // A continuous beam: ignore the 10-tick invulnerability window.
        target.invulnerableTime = 0;
        target.hurt(beamSource(level, player), dmg);
        target.invulnerableTime = 0;
        target.setDeltaMovement(incomingVelocity);
        float dealt = Math.max(0, before - target.getHealth());
        target.getPersistentData()
                .putInt(
                        "mm_combat_pressure",
                        Math.max(1, player.getPersistentData().getInt("mm_beam_level")));

        if (Traits.has(tool, "plasmic.leech") && dealt > 0)
            player.heal(dealt * (0.05f + 0.05f * Traits.rank(tool, "plasmic.leech")));
        int ionize = Traits.rank(tool, "plasmic.ionize");
        if (ionize > 0) {
            target.getPersistentData().putLong(IONIZED_TAG, level.getGameTime());
            target.getPersistentData().putInt(IONIZED_TAG + "_rank", ionize);
        }

        // Continuous damage must not erase the target's voluntary movement.
        // Restore the incoming velocity after damage, without repeatedly pinning it.

    }

    /** Set on creatures hit by an Ionize beam; orbs deal +20% for 3 s after. */
    public static final String IONIZED_TAG = "ethermod_ionized";

    public static boolean isIonized(Entity e) {
        long t = e.getPersistentData().getLong(IONIZED_TAG);
        return t != 0 && e.level().getGameTime() - t < 60;
    }

    /** Orb damage multiplier on an ionized creature: +20%, +35% at rank II. */
    public static float ionizeBonus(Entity e) {
        return e.getPersistentData().getInt(IONIZED_TAG + "_rank") >= 2 ? 1.35f : 1.2f;
    }

    // =========================================================================
    // Internals
    // =========================================================================

    /** Pulls item entities and experience orbs near the ray towards the player. */
    private static void tractor(ServerLevel level, ServerPlayer player, Vec3 start, Vec3 end) {
        AABB box = new AABB(start, end).inflate(1.5);
        Vec3 dir = end.subtract(start);
        double lenSq = Math.max(1e-6, dir.lengthSqr());
        for (Entity e :
                level.getEntities(
                        player, box, e -> e instanceof ItemEntity || e instanceof ExperienceOrb)) {
            Vec3 p = e.position();
            double t = Math.max(0, Math.min(1, p.subtract(start).dot(dir) / lenSq));
            if (p.distanceToSqr(start.add(dir.scale(t))) > 2.25) continue;
            Vec3 pull = player.position().add(0, 0.6, 0).subtract(p).normalize().scale(0.35);
            e.setDeltaMovement(e.getDeltaMovement().scale(0.5).add(pull));
            e.hurtMarked = true;
        }
    }

    /** Where the beam came out of glass this tick (Refraction), or null. Server thread only. */
    private static Vec3 lastRefract;

    /** Refraction: glass and ice let the beam through. */
    public static boolean refracts(ItemStack tool, BlockState state) {
        return (state.is(net.neoforged.neoforge.common.Tags.Blocks.GLASS_BLOCKS)
                        || state.is(net.neoforged.neoforge.common.Tags.Blocks.GLASS_PANES)
                        || state.is(net.minecraft.tags.BlockTags.ICE))
                && Traits.has(tool, "photonic.refraction");
    }

    /** The extra rays behind the glass: one at I, two at II. */
    public static java.util.List<Vec3> refractRays(Vec3 look, int rank) {
        java.util.List<Vec3> out = new java.util.ArrayList<>();
        out.add(look.yRot((float) Math.toRadians(7)));
        if (rank >= 2) out.add(look.yRot((float) Math.toRadians(-7)));
        return out;
    }

    private static Vec3 castThroughFoliage(
            ServerLevel level, ServerPlayer player, Vec3 start, Vec3 dir, float range) {
        Vec3 current = start;
        float remaining = range;

        while (remaining > 0.01f) {
            Vec3 end = current.add(dir.scale(remaining));
            BlockHitResult hit =
                    level.clip(
                            new ClipContext(
                                    current,
                                    end,
                                    ClipContext.Block.COLLIDER,
                                    ClipContext.Fluid.NONE,
                                    player));

            if (hit.getType() == HitResult.Type.MISS) return end;

            BlockPos pos = hit.getBlockPos();
            BlockState state = level.getBlockState(pos);

            if (lastRefract == null && refracts(player.getMainHandItem(), state)) {
                // Refraction: pass through the glass; the rays split where it comes out
                Vec3 face = Vec3.atCenterOf(pos);
                float step = (float) current.distanceTo(face) + 0.6f;
                remaining -= step;
                current = current.add(dir.scale(step));
                lastRefract = current;
                continue;
            }
            if (FoliageHelper.isFoliage(state)) {
                // foliage (leaves, grass, flowers and the like) is passed through and left standing
                Vec3 face = Vec3.atCenterOf(pos);
                float step = (float) current.distanceTo(face) + 0.01f;
                remaining -= step;
                current = current.add(dir.scale(step));
            } else {
                return hit.getLocation();
            }
        }
        return current;
    }

    private static Optional<EntityHitResult> findEntityOnRay(
            Level level, ServerPlayer player, Vec3 start, Vec3 dir, float maxDist) {
        Vec3 end = start.add(dir.scale(maxDist));
        AABB search = player.getBoundingBox().expandTowards(dir.scale(maxDist)).inflate(1.0);

        return level
                .getEntitiesOfClass(
                        LivingEntity.class,
                        search,
                        e -> !com.ethermod.beam.Mounts.own(player, e) && e.isAlive() && e.isPickable()
                                && (com.ethermod.config.ServerConfig.hurtPlayers() || !(e instanceof net.minecraft.world.entity.player.Player)))
                .stream()
                .filter(e -> e.getBoundingBox().inflate(0.1).clip(start, end).isPresent())
                .min(Comparator.comparingDouble(e -> start.distanceToSqr(e.getEyePosition())))
                .map(
                        e ->
                                new EntityHitResult(
                                        e,
                                        e.getBoundingBox()
                                                .inflate(0.1)
                                                .clip(start, end)
                                                .orElse(e.position())));
    }
}
