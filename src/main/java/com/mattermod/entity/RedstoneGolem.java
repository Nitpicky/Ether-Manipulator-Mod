package com.mattermod.entity;

import com.mattermod.manipulator.Manipulator;
import com.mattermod.trait.RedesignEffects;
import com.mattermod.trait.Traits;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.npc.AbstractVillager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import org.joml.Vector3f;

import software.bernie.geckolib.animatable.GeoEntity;
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.animation.AnimationController;
import software.bernie.geckolib.animation.PlayState;
import software.bernie.geckolib.animation.RawAnimation;
import software.bernie.geckolib.util.GeckoLibUtil;

import java.util.Optional;
import java.util.UUID;

/**
 * The Mechatronic Module's Redstone Golem: one per player, built by Golem mode ({@link
 * GolemControl}), never saved with the world. It follows its owner, defends them, takes orders
 * (attack / hold / follow) and, with its traits, punches (Piston Arm) or shoots (Dispenser Arm),
 * runs (Stride Servos) or flies up (Rocket Boots), can be ridden (Saddle), and its overheat builds
 * Ministrosities or throws Redstone Arcs.
 *
 * <p>Animations come from golem-model/geckolib/export_geckolib.py (20 fps). Attack timings below
 * are in animation frames and run 1.5x faster while the Redstone Furnace is overheated.
 */
public final class RedstoneGolem extends PathfinderMob implements GeoEntity {

    // ---- synced state ---------------------------------------------------------------------
    private static final EntityDataAccessor<Optional<UUID>> OWNER =
            SynchedEntityData.defineId(RedstoneGolem.class, EntityDataSerializers.OPTIONAL_UUID);
    /** Trait and state bits, see the F_ constants. */
    private static final EntityDataAccessor<Integer> FLAGS =
            SynchedEntityData.defineId(RedstoneGolem.class, EntityDataSerializers.INT);
    /** Game time of the last (re)assembly: the client plays the summon animation from it. */
    private static final EntityDataAccessor<Long> BUILT_AT =
            SynchedEntityData.defineId(RedstoneGolem.class, EntityDataSerializers.LONG);

    private static final int F_BOOSTING = 1, F_RECALLING = 2, F_OVERHEATED = 4, F_MINIS = 8, F_SADDLE = 16, F_STRIDE = 32,
            F_ROCKETS = 64, F_ARCS = 128, F_STRIDING = 256;

    // ---- tuning ---------------------------------------------------------------------------
    public static final float MAX_HEALTH = 100, SWEEP_DAMAGE = 10, PISTON_DAMAGE = 14, BOLT_DAMAGE = 6;
    public static final double BASE_SPEED = .25, OVERHEAT_FACTOR = 1.5;
    public static final int SUMMON_FRAMES = 56, RECALL_FRAMES = 40, BREAKDOWN_FRAMES = 24;
    /** Basic attack = the lift up (anim "sweep"); Piston trait = the powered slam (anim "piston"). */
    static final int SWEEP_LEN = 32, SWEEP_HIT = 10, PISTON_LEN = 60, PISTON_HIT = 30, DISPENSER_LEN = 60;
    /** Dispenser: both arm cannons fire at once after a heavy charge, and the recoil knocks the Golem back. */
    static final int DISPENSER_FIRE = 32;
    /** Entity events: the redstone burns bright through a powered slam / a cannon charge (see heatGlow). */
    static final byte EV_BURN_PISTON = 70, EV_BURN_DISPENSER = 71;
    static final double SLAM_RADIUS = 4, LIFT_REACH = 3.2;
    static final int PISTON_COOLDOWN = 120, DISPENSER_COOLDOWN = 100;
    /** Rocket Boots (always fitted; the Rocket Boots trait upgrades them): Terraria style. Holding Jump in the air thrusts up,
     *  accelerating to a top climb speed while the fuel lasts; spent, holding Jump glides down slowly (as Gust does), letting
     *  go falls; the fuel refills the moment it lands. Fuel in ticks, thrust and climb in blocks/tick. */
    static final int ROCKET_FUEL = 32, ROCKET_FUEL_UP = 48;
    static final double THRUST = .10, THRUST_UP = .12, CLIMB = .5, CLIMB_UP = .62, GLIDE = -.12;
    /** Stride (always on; the Stride Servos trait upgrades it): running in a steady line builds speed up to STRIDE_TOP x walk
     *  over STRIDE_BUILD ticks, like Gravity Skate but much milder; stopping or a sharp turn sheds it. */
    static final double STRIDE_TOP = 1.5, STRIDE_TOP_UP = 1.9;
    static final int STRIDE_BUILD = 40, STRIDE_BUILD_UP = 24;
    static final int MINI_CAP = 3, ARC_TARGETS = 3;
    static final float ARC_DAMAGE = 4;
    static final double ARC_RANGE = 6;
    static final int FURNACE_HITS = 10, OVERHEAT_TICKS = 120;
    static final double FOLLOW_DISTANCE = 4, CATCH_UP_DISTANCE = 24, GUARD_RANGE = 12, REACH = 3.4;

    // ---- server state ---------------------------------------------------------------------
    enum Order { FOLLOW, ATTACK, HOLD }

    enum Action { NONE, SWEEP, PISTON, DISPENSER }

    Order order = Order.FOLLOW;
    LivingEntity orderTarget;
    BlockPos holdAt;
    private Action action = Action.NONE;
    private float actionFrame;
    private LivingEntity actionTarget;
    private Vec3 actionAim;
    private int sweepCooldown, armsCooldown, recallTicks, stuckTicks, heat, overheatTicks, lastDamageTick;
    /** Rocket Boots: thrust ticks left; spent, holding Jump glides down. */
    private int fuel = ROCKET_FUEL;
    private boolean gliding, thrusting, jumpWas;
    /** Stride: built-up speed, 0..1, and the heading it was built along (degrees). */
    private float strideCharge, strideHeading;
    private Vec3 rocketGoal;

    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);

    public RedstoneGolem(EntityType<? extends RedstoneGolem> type, Level level) {
        super(type, level);
        xpReward = 0;
        setPersistenceRequired();
    }

    public static AttributeSupplier.Builder attributes() {
        return Mob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, MAX_HEALTH)
                .add(Attributes.MOVEMENT_SPEED, BASE_SPEED)
                .add(Attributes.ATTACK_DAMAGE, SWEEP_DAMAGE)
                .add(Attributes.KNOCKBACK_RESISTANCE, 1)
                .add(Attributes.FOLLOW_RANGE, 32)
                .add(Attributes.ARMOR, 0)
                .add(Attributes.ARMOR_TOUGHNESS, 0)
                .add(Attributes.STEP_HEIGHT, 1.1);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder b) {
        super.defineSynchedData(b);
        b.define(OWNER, Optional.empty());
        b.define(FLAGS, 0);
        b.define(BUILT_AT, 0L);
    }

    @Override
    protected void registerGoals() {
        goalSelector.addGoal(0, new FloatGoal(this));
    }

    // ---- owner and flags ------------------------------------------------------------------

    public Optional<UUID> ownerId() {
        return entityData.get(OWNER);
    }

    public void setOwner(ServerPlayer owner) {
        entityData.set(OWNER, Optional.of(owner.getUUID()));
    }

    public ServerPlayer owner() {
        if (!(level() instanceof ServerLevel sl)) return null;
        return ownerId().map(id -> sl.getServer().getPlayerList().getPlayer(id)).orElse(null);
    }

    private boolean flag(int f) {
        return (entityData.get(FLAGS) & f) != 0;
    }

    private void setFlag(int f, boolean on) {
        int b = entityData.get(FLAGS);
        int n = on ? b | f : b & ~f;
        if (n != b) entityData.set(FLAGS, n);
    }

    public boolean boosting() {
        return flag(F_BOOSTING) || (level().isClientSide && thrusting);
    }

    public boolean recalling() {
        return flag(F_RECALLING);
    }

    private float heatGlow, heatGlowPrev;
    private int idleBreakUntil;

    /** Client: how hot the redstone looks, 0 (red) to 1 (yellow), see GolemHeatLayer. */
    public float heatGlow(float partialTick) {
        return heatGlowPrev + (heatGlow - heatGlowPrev) * partialTick;
    }

    public boolean overheated() {
        return flag(F_OVERHEATED);
    }

    public boolean hasMinis() {
        return flag(F_MINIS);
    }

    public boolean hasArcs() {
        return flag(F_ARCS);
    }

    public int fuel() {
        return fuel;
    }

    public boolean hasSaddle() {
        return flag(F_SADDLE);
    }

    /** The Stride Servos trait: Stride builds faster and higher. */
    public boolean hasStride() {
        return flag(F_STRIDE);
    }

    /** The Rocket Boots trait: more fuel and a stronger climb. */
    public boolean hasRockets() {
        return flag(F_ROCKETS);
    }

    /** Running at a good part of its built-up Stride speed (the run animation plays). */
    public boolean striding() {
        return flag(F_STRIDING) || (level().isClientSide && isVehicle() && strideCharge > .5f);
    }

    private int maxFuel() {
        return hasRockets() ? ROCKET_FUEL_UP : ROCKET_FUEL;
    }

    private double strideMult() {
        return 1 + ((hasStride() ? STRIDE_TOP_UP : STRIDE_TOP) - 1) * strideCharge;
    }

    /** One tick of Stride where the Golem is simulated: a steady line builds it, stopping or turning sharply sheds it. */
    private void strideTick() {
        Vec3 v = getDeltaMovement();
        double sp = v.horizontalDistance();
        if (sp < .05 || !onGround() && strideCharge == 0) {
            strideCharge = Math.max(0, strideCharge - .2f);
            return;
        }
        float heading = (float) (Mth.atan2(v.z, v.x) * Mth.RAD_TO_DEG);
        float turn = Math.abs(Mth.wrapDegrees(heading - strideHeading));
        strideHeading = heading;
        if (turn > 35) strideCharge *= .4f;
        else if (onGround()) strideCharge = Math.min(1, strideCharge + 1f / (hasStride() ? STRIDE_BUILD_UP : STRIDE_BUILD));
    }

    /** One line of state for the playtest log. */
    public String debugState() {
        var t = getTarget();
        return String.format("pos=%.1f,%.1f,%.1f yaw=%.0f order=%s target=%s action=%s frame=%.0f navDone=%s speed=%.3f assembling=%s armsCd=%d",
                getX(), getY(), getZ(), getYRot(), order, t == null ? "-" : t.getType().toShortString() + "@" + String.format("%.1f", Math.sqrt(distanceToSqr(t))),
                action, actionFrame, getNavigation().isDone(), getAttributeValue(Attributes.MOVEMENT_SPEED), assembling(), armsCooldown);
    }

    public long builtAt() {
        return entityData.get(BUILT_AT);
    }

    /** Marks a fresh assembly: the summon animation plays and the Golem stands still meanwhile. */
    public void assembled() {
        entityData.set(BUILT_AT, level().getGameTime());
        playSound(SoundEvents.IRON_GOLEM_REPAIR, 1, .7f);
        playSound(SoundEvents.PISTON_EXTEND, .6f, .6f);
        landing = 8;
    }

    private int landing, clientRecall;
    private float burnFrame = -1, burnPeak = 1, burnLen = 1;

    private boolean assembling() {
        long since = level().getGameTime() - builtAt();
        return since >= 0 && since < SUMMON_FRAMES - 6;
    }

    /** Copies the owner's Mechatronic traits onto the Golem. */
    void readTraits(ServerPlayer owner) {
        var st = Manipulator.stack(owner);
        setFlag(F_SADDLE, hasCommon(owner, 1));
        setFlag(F_MINIS, Traits.has(st, "mechatronic.ministrosities"));
        setFlag(F_ARCS, Traits.has(st, "mechatronic.redstone_arcs"));
        setFlag(F_STRIDE, Traits.has(st, "mechatronic.stride_servos"));
        setFlag(F_ROCKETS, Traits.has(st, "mechatronic.rocket_boots"));
    }

    boolean hasTrait(ServerPlayer owner, String id) {
        return owner != null && Traits.has(Manipulator.stack(owner), id);
    }

    boolean hasCommon(ServerPlayer owner, int common) {
        return owner != null && Traits.hasCommon(Manipulator.stack(owner), Traits.MECHATRONIC, common);
    }

    // ---- never saved, never despawns ------------------------------------------------------

    @Override
    public boolean shouldBeSaved() {
        return false;
    }

    @Override
    public boolean removeWhenFarAway(double distance) {
        return false;
    }

    @Override
    public boolean causeFallDamage(float distance, float multiplier, DamageSource source) {
        return false;
    }

    @Override
    public boolean isPushable() {
        return !isVehicle();
    }

    // ---- targets --------------------------------------------------------------------------

    /** Whom the Golem may hit: not its owner's allies, players, villagers, golems or anyone's pets. */
    boolean canHit(ServerPlayer owner, LivingEntity e) {
        return e != null
                && e != this
                && e.isAlive()
                && !(e instanceof Player)
                && !(e instanceof AbstractVillager)
                && !(e instanceof IronGolem)
                && !(e instanceof RedstoneGolem)
                && !(e instanceof Ministrosity)
                && !(e instanceof ArmorStand)
                && !(e instanceof net.minecraft.world.entity.OwnableEntity o && o.getOwnerUUID() != null)
                && (owner == null || !RedesignEffects.ally(owner, e));
    }

    /** Whom it goes for on its own: hostile mobs, never Creepers (like the Iron Golem). */
    private boolean autoTarget(ServerPlayer owner, LivingEntity e) {
        return canHit(owner, e) && !(e instanceof Creeper) && (e instanceof Enemy || e == getLastHurtByMob());
    }

    private LivingEntity pickTarget(ServerPlayer owner) {
        Vec3 guard = order == Order.HOLD && holdAt != null ? Vec3.atBottomCenterOf(holdAt) : position();
        if (order == Order.ATTACK) {
            if (canHit(owner, orderTarget) && distanceToSqr(orderTarget) < 48 * 48) return orderTarget;
            order = Order.FOLLOW;
            orderTarget = null;
        }
        double range = GUARD_RANGE * GUARD_RANGE;
        LivingEntity hitByOwner = owner.tickCount - owner.getLastHurtMobTimestamp() < 200 ? owner.getLastHurtMob() : null;
        for (LivingEntity c : new LivingEntity[] {owner.getLastHurtByMob(), hitByOwner, getLastHurtByMob(), getTarget()}) {
            if (c != null && autoTargetOrAttacker(owner, c) && c.position().distanceToSqr(guard) < range * 2)
                return c;
        }
        LivingEntity best = null;
        double bestD = range;
        for (var e :
                level().getEntitiesOfClass(
                        LivingEntity.class, new AABB(guard, guard).inflate(GUARD_RANGE), e -> autoTarget(owner, e))) {
            double d = e.position().distanceToSqr(guard);
            if (d < bestD) {
                bestD = d;
                best = e;
            }
        }
        return best;
    }

    /** What hurt the owner or the Golem, or what the owner hit, is fair game even when not hostile. */
    private boolean autoTargetOrAttacker(ServerPlayer owner, LivingEntity c) {
        return canHit(owner, c)
                && (c instanceof Enemy && !(c instanceof Creeper)
                        || c == owner.getLastHurtByMob()
                        || (c == owner.getLastHurtMob() && owner.tickCount - owner.getLastHurtMobTimestamp() < 200)
                        || c == getLastHurtByMob());
    }

    // ---- tick -----------------------------------------------------------------------------

    @Override
    public void tick() {
        super.tick();
        if (level().isClientSide) {
            heatGlowPrev = heatGlow;
            // overheat colour: the redstone heats up over 1.5s, then cools back down over 2.5s when it ends
            heatGlow = overheated() ? Math.min(1, heatGlow + 1 / 30f) : Math.max(0, heatGlow - 1 / 50f);
            if (burnFrame >= 0) burnFrame += animSpeed();
            heatGlow = Math.max(heatGlow, burn());
            clientFx();
            return;
        }
        if (!isAlive()) return;
        if (landing > 0 && --landing == 0) {      // the summon drop lands
            playSound(SoundEvents.GENERIC_EXPLODE.value(), .8f, .6f);
            playSound(SoundEvents.ANVIL_LAND, .8f, .5f);
        }
        ServerPlayer owner = owner();
        if (owner == null || owner.level() != level() || !Traits.hasLine(Manipulator.stack(owner), Traits.MECHATRONIC)) {
            GolemControl.putAway(this, false);
            return;
        }
        if (recalling()) {
            getNavigation().stop();
            if (++recallTicks == 12) playSound(SoundEvents.BEACON_DEACTIVATE, 1, .6f);
            if (recallTicks == 18) playSound(SoundEvents.GRAVEL_BREAK, 1.4f, .5f);
            if (recallTicks >= RECALL_FRAMES) GolemControl.putAway(this, false);
            return;
        }
        if (tickCount % 20 == 0) readTraits(owner);
        if (sweepCooldown > 0) sweepCooldown--;
        if (armsCooldown > 0) armsCooldown--;

        // speed: Stride's built-up speed and the overheat
        if (!isVehicle()) strideTick();
        setFlag(F_STRIDING, strideCharge > .5f);
        double speed = BASE_SPEED * strideMult() * (overheated() ? OVERHEAT_FACTOR : 1);
        var attr = getAttribute(Attributes.MOVEMENT_SPEED);
        if (attr != null && attr.getBaseValue() != speed) attr.setBaseValue(speed);

        // now and then, while standing guard, it looks around
        if (action == Action.NONE && getNavigation().isDone() && getDeltaMovement().horizontalDistanceSqr() < 1e-3
                && random.nextInt(400) == 0) {
            triggerAnim("main", "idle_break");
            idleBreakUntil = tickCount + 64;
        } else if (idleBreakUntil > tickCount && (!getNavigation().isDone() || action != Action.NONE)) {
            if (action == Action.NONE) stopTriggeredAnim("main", "idle_break");
            idleBreakUntil = 0;
        }

        // Redstone Furnace: the overheat runs out
        if (overheatTicks > 0 && --overheatTicks == 0) setFlag(F_OVERHEATED, false);

        // slow repair once out of the fight
        if (tickCount % 40 == 0 && tickCount - lastDamageTick > 100 && getHealth() < getMaxHealth()) heal(1);

        if (tickCount % 20 == 0) bulwark(owner);

        if (!isVehicle()) {
            boolean want = wantsThrust();
            if (want && onGround()) jumpFromGround();
            rocketTick(want);
            if (rocketGoal != null && !onGround() && thrusting) {
                Vec3 flat = rocketGoal.subtract(position()).multiply(1, 0, 1);
                Vec3 v = getDeltaMovement();
                if (flat.lengthSqr() > .5) {
                    Vec3 d = flat.normalize().scale(.16);
                    setDeltaMovement(d.x, v.y, d.z);
                }
            }
            if (!onGround() && (thrusting || gliding)) hurtMarked = true;
            setFlag(F_BOOSTING, thrusting);
        }
        if (action == Action.NONE && queuedUntil > 0) {
            if (tickCount <= queuedUntil) armsMove(owner, queuedTarget != null && queuedTarget.isAlive() ? queuedTarget : null, queuedAim);
            queuedUntil = 0;
        }
        if (action != Action.NONE) {
            actionTick(owner);
            return;
        }
        if (assembling()) {
            getNavigation().stop();
            return;
        }
        if (isVehicle()) return; // the rider drives
        think(owner);
    }

    private void think(ServerPlayer owner) {
        if (tickCount % 5 == 0) setTarget(pickTarget(owner));
        LivingEntity target = getTarget();
        double toOwner = distanceToSqr(owner);

        // catch up: too far, or stuck for 3s while trying to reach the owner
        boolean following = order != Order.HOLD && target == null;
        if (following && toOwner > FOLLOW_DISTANCE * FOLLOW_DISTANCE * 4 && getNavigation().isDone()) stuckTicks++;
        else stuckTicks = 0;
        if (order != Order.HOLD && (toOwner > CATCH_UP_DISTANCE * CATCH_UP_DISTANCE || stuckTicks > 60)) {
            if (fuel == maxFuel() && onGround() && toOwner < 14 * 14 && owner.getY() > getY() + 2) {
                rocketGoal = owner.position();
            } else if (GolemControl.teleportNear(this, owner)) {
                stuckTicks = 0;
                return;
            }
        }

        if (target != null) {
            // Rocket Boots: up to flying or perched targets
            if (fuel == maxFuel() && onGround() && target.getY() > getY() + 2.5
                    && horizontalDistance(target.position()) < 10) rocketGoal = target.position();
            getLookControl().setLookAt(target, 30, 30);
            double reach = REACH + target.getBbWidth() / 2;
            if (distanceToSqr(target) > reach * reach) {
                if (tickCount % 10 == 0 || getNavigation().isDone()) getNavigation().moveTo(target, 1);
            } else {
                getNavigation().stop();
                if (sweepCooldown <= 0) start(Action.SWEEP, target, null);
            }
            return;
        }
        if (order == Order.HOLD && holdAt != null) {
            Vec3 at = Vec3.atBottomCenterOf(holdAt);
            if (position().distanceToSqr(at) > 2.25) {
                if (tickCount % 10 == 0 || getNavigation().isDone()) getNavigation().moveTo(at.x, at.y, at.z, 1);
            } else getNavigation().stop();
            return;
        }
        if (toOwner > FOLLOW_DISTANCE * FOLLOW_DISTANCE) {
            if (tickCount % 10 == 0 || getNavigation().isDone())
                getNavigation().moveTo(owner, toOwner > 100 ? 1.25 : 1);
        } else {
            getNavigation().stop();
            if (tickCount % 40 == 0) getLookControl().setLookAt(owner, 10, 10);
        }
    }

    private double horizontalDistance(Vec3 p) {
        double dx = p.x - getX(), dz = p.z - getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

    // ---- actions --------------------------------------------------------------------------

    private float animSpeed() {
        return overheated() ? (float) OVERHEAT_FACTOR : 1;
    }

    /** Starts the arm sweep (order or rider), if it isn't busy or cooling down. */
    public boolean sweep(LivingEntity target) {
        if (action != Action.NONE || sweepCooldown > 0 || recalling()) return false;
        start(Action.SWEEP, target, null);
        return true;
    }

    /** The Arms move on {@code target} (or toward {@code aim}): Piston uppercut or Dispenser bolts.
     * Asked mid-sweep, it is queued and starts as soon as the sweep ends (within 1.5s). */
    public boolean armsMove(ServerPlayer owner, LivingEntity target, Vec3 aim) {
        boolean piston = hasTrait(owner, "mechatronic.piston_arm");
        if (!piston && !hasTrait(owner, "mechatronic.dispenser_arm")) return false;
        if (armsCooldown > 0 || recalling() || assembling()) return false;
        if (action != Action.NONE) {
            if (action != Action.SWEEP) return false;
            queuedTarget = target;
            queuedAim = aim;
            queuedUntil = tickCount + 30;
            return true;
        }
        queuedUntil = 0;
        if (piston) {
            if (target == null) target = nearestInFront(owner, 8);
            start(Action.PISTON, target, aim);
            armsCooldown = PISTON_COOLDOWN;
        } else {
            start(Action.DISPENSER, target, aim);
            armsCooldown = DISPENSER_COOLDOWN;
        }
        return true;
    }

    private LivingEntity queuedTarget;
    private Vec3 queuedAim;
    private int queuedUntil;

    public boolean armsReady() {
        return armsCooldown <= 0 && action == Action.NONE;
    }

    private LivingEntity nearestInFront(ServerPlayer owner, double range) {
        Vec3 fwd = Vec3.directionFromRotation(0, getYRot());
        LivingEntity best = null;
        double bestD = range * range;
        for (var e : level().getEntitiesOfClass(LivingEntity.class, getBoundingBox().inflate(range), e -> canHit(owner, e))) {
            Vec3 to = e.position().subtract(position());
            double d = to.lengthSqr();
            if (d < bestD && to.normalize().dot(fwd) > .3) {
                bestD = d;
                best = e;
            }
        }
        return best;
    }

    private void start(Action a, LivingEntity target, Vec3 aim) {
        action = a;
        actionFrame = 0;
        actionTarget = target;
        actionAim = aim;
        getNavigation().stop();
        if (a == Action.SWEEP) sweepCooldown = Math.round(SWEEP_LEN / animSpeed());
        triggerAnim("main", switch (a) {
            case SWEEP -> "sweep";
            case PISTON -> "piston";
            case DISPENSER -> "dispenser";
            default -> "sweep";
        });
        if (a == Action.PISTON) playSound(SoundEvents.PISTON_CONTRACT, 1, .6f);
        if (a == Action.PISTON || a == Action.DISPENSER)
            level().broadcastEntityEvent(this, a == Action.PISTON ? EV_BURN_PISTON : EV_BURN_DISPENSER);
    }

    @Override
    public void handleEntityEvent(byte id) {
        if (id == EV_BURN_PISTON || id == EV_BURN_DISPENSER) {
            burnFrame = 0;
            burnPeak = id == EV_BURN_PISTON ? 22 : DISPENSER_FIRE;
            burnLen = id == EV_BURN_PISTON ? PISTON_LEN : DISPENSER_LEN;
        } else super.handleEntityEvent(id);
    }

    /** Client: the burn of a powered slam or a cannon charge: up to full heat by the peak, held, cooled by the end. */
    private float burn() {
        if (burnFrame < 0 || burnFrame >= burnLen) return 0;
        if (burnFrame < burnPeak) return burnFrame / burnPeak;
        if (burnFrame < burnPeak + 10) return 1;
        return Math.max(0, 1 - (burnFrame - burnPeak - 10) / (burnLen - burnPeak - 10));
    }

    private Vec3 aimPoint() {
        if (actionTarget != null && actionTarget.isAlive()) return actionTarget.getBoundingBox().getCenter();
        return actionAim;
    }

    private void face(Vec3 p) {
        if (p == null || isVehicle()) return;
        float yaw = (float) (Mth.atan2(p.z - getZ(), p.x - getX()) * Mth.RAD_TO_DEG) - 90;
        setYRot(yaw);
        yBodyRot = yaw;
        yHeadRot = yaw;
    }

    private boolean crossed(float frame, float prev, int at) {
        return prev < at && frame >= at;
    }

    private void actionTick(ServerPlayer owner) {
        float prev = actionFrame;
        actionFrame += animSpeed();
        float f = actionFrame;
        Vec3 aim = aimPoint();
        boolean early = f < (action == Action.SWEEP ? SWEEP_HIT : action == Action.PISTON ? PISTON_HIT - 4 : DISPENSER_FIRE);
        if (early) face(aim);
        switch (action) {
            case SWEEP -> {
                if (crossed(f, prev, SWEEP_HIT)) sweepHit(owner);
                if (f >= SWEEP_LEN - 10) action = Action.NONE;
            }
            case PISTON -> {
                if (crossed(f, prev, PISTON_HIT - 4)) playSound(SoundEvents.PISTON_EXTEND, 1.2f, .6f);
                if (crossed(f, prev, PISTON_HIT)) pistonHit(owner);
                if (f >= PISTON_LEN - 8) action = Action.NONE;
            }
            case DISPENSER -> {
                if (crossed(f, prev, DISPENSER_FIRE)) {
                    // both bolts home: on the target, and the second on the enemy closest to it when there is one
                    LivingEntity first = boltTarget(owner, aim, null), second = first == null ? null : boltTarget(owner, first.getBoundingBox().getCenter(), first);
                    fireBolt(owner, aim, true, first);
                    fireBolt(owner, aim, false, second != null ? second : first);
                    recoilStep(.36);           // the recoil drives it two heavy steps back, sliding a little
                    playSound(SoundEvents.IRON_GOLEM_STEP, 1.2f, .6f);
                    playSound(SoundEvents.GENERIC_EXPLODE.value(), .6f, 1.2f);
                }
                if (crossed(f, prev, DISPENSER_FIRE + 3)) {
                    recoilStep(.3);
                    playSound(SoundEvents.IRON_GOLEM_STEP, 1.2f, .55f);
                }
                if (f >= DISPENSER_LEN) action = Action.NONE;
            }
            default -> action = Action.NONE;
        }
    }

    /** Basic attack, the lift up: both arms heave up through the front, everything in a 3-block arc ahead is hit and thrown up. */
    private void sweepHit(ServerPlayer owner) {
        playSound(SoundEvents.IRON_GOLEM_ATTACK, 1.2f, .55f);
        playSound(SoundEvents.RAVAGER_ROAR, .6f, 1.3f);
        Vec3 fwd = Vec3.directionFromRotation(0, getYRot());
        int hits = 0;
        for (var e : level().getEntitiesOfClass(LivingEntity.class, getBoundingBox().inflate(LIFT_REACH, 1.5, LIFT_REACH), e -> canHit(owner, e))) {
            Vec3 to = e.position().subtract(position()).multiply(1, 0, 1);
            double d = to.length();
            if (d > LIFT_REACH + e.getBbWidth() / 2 + getBbWidth() / 2) continue;
            if (d > .01 && to.normalize().dot(fwd) < .2) continue;          // in front only
            if (!e.hurt(damageSources().mobAttack(this), (float) getAttributeValue(Attributes.ATTACK_DAMAGE))) continue;
            e.setDeltaMovement(fwd.x * .12, 1.15, fwd.z * .12);   // straight up, like the iron golem's toss
            e.hurtMarked = true;
            if (overheated()) e.igniteForSeconds(4);
            arc(owner, e);
            hits++;
        }
        if (hits > 0) stoke(owner, hits);
        if (level() instanceof ServerLevel sl) {
            var below = level().getBlockState(blockPosition().below());
            if (!below.isAir())
                sl.sendParticles(new net.minecraft.core.particles.BlockParticleOption(ParticleTypes.BLOCK, below),
                        getX() + fwd.x * 1.6, getY() + .2, getZ() + fwd.z * 1.6, 30, .8, .4, .8, .25);
        }
    }

    /** Piston trait, the powered slam: both fists crash down in front, everything around is hit hard and thrown away. */
    private void pistonHit(ServerPlayer owner) {
        playSound(SoundEvents.IRON_GOLEM_ATTACK, 1.4f, .45f);
        playSound(SoundEvents.GENERIC_EXPLODE.value(), .9f, .7f);
        playSound(SoundEvents.ANVIL_LAND, .7f, .5f);
        Vec3 fwd = Vec3.directionFromRotation(0, getYRot());
        Vec3 c = position().add(fwd.scale(1.6));
        int hits = 0;
        for (var e : level().getEntitiesOfClass(LivingEntity.class, new AABB(c, c).inflate(SLAM_RADIUS, 2, SLAM_RADIUS), e -> canHit(owner, e))) {
            Vec3 to = e.position().subtract(c).multiply(1, 0, 1);
            double d = to.length();
            if (d > SLAM_RADIUS + e.getBbWidth() / 2 || Math.abs(e.getY() - getY()) > 2.5) continue;
            if (!e.hurt(damageSources().mobAttack(this), PISTON_DAMAGE)) continue;
            Vec3 push = (d > .01 ? to.normalize() : fwd).scale(1.5 - .2 * d);
            e.setDeltaMovement(e.getDeltaMovement().add(push.x, .7, push.z));
            e.hurtMarked = true;
            if (overheated()) e.igniteForSeconds(4);
            arc(owner, e);
            hits++;
        }
        if (hits > 0) stoke(owner, hits);
        if (level() instanceof ServerLevel sl) {
            var below = level().getBlockState(BlockPos.containing(c).below());
            if (!below.isAir())
                sl.sendParticles(new net.minecraft.core.particles.BlockParticleOption(ParticleTypes.BLOCK, below), c.x, getY() + .1, c.z, 120, SLAM_RADIUS * .5, .15, SLAM_RADIUS * .5, .3);
            sl.sendParticles(new DustParticleOptions(new Vector3f(.9f, .1f, .05f), 2f), c.x, getY() + .2, c.z, 40, SLAM_RADIUS * .5, .1, SLAM_RADIUS * .5, 0);
            sl.sendParticles(ParticleTypes.EXPLOSION_EMITTER, c.x, getY() + .3, c.z, 1, 0, 0, 0, 0);
            sl.sendParticles(ParticleTypes.CAMPFIRE_COSY_SMOKE, c.x, getY() + .2, c.z, 12, SLAM_RADIUS * .4, .1, SLAM_RADIUS * .4, .02);
        }
    }

    private void recoilStep(double speed) {
        Vec3 back = Vec3.directionFromRotation(0, getYRot()).scale(-speed);
        setDeltaMovement(back.x, getDeltaMovement().y, back.z);
        hurtMarked = true;
    }

    /** One bolt out of the right or the left fist, held level as a cannon. */
    /** The bolt's homing target: the action target if it is still alive, else the hittable enemy closest to {@code near}
     *  (within 8 blocks), never {@code not}. */
    private LivingEntity boltTarget(ServerPlayer owner, Vec3 near, LivingEntity not) {
        if (not == null && actionTarget != null && actionTarget.isAlive()) return actionTarget;
        if (near == null) return null;
        LivingEntity best = null;
        double bd = 8 * 8;
        for (LivingEntity e : level().getEntitiesOfClass(LivingEntity.class, new net.minecraft.world.phys.AABB(near, near).inflate(8),
                e -> e != not && e.isAlive() && canHit(owner, e) && (e instanceof Enemy || e == getTarget()))) {
            double d = e.getBoundingBox().getCenter().distanceToSqr(near);
            if (d < bd) { bd = d; best = e; }
        }
        return best;
    }

    private void fireBolt(ServerPlayer owner, Vec3 aim, boolean right, LivingEntity homing) {
        Vec3 fwd = Vec3.directionFromRotation(0, getYRot());
        Vec3 left = new Vec3(fwd.z, 0, -fwd.x);
        Vec3 from = position().add(fwd.scale(2.0)).add(left.scale(right ? -1.15 : 1.15)).add(0, 1.75, 0);
        Vec3 to = homing != null ? homing.getBoundingBox().getCenter() : aim != null ? aim : from.add(fwd.scale(24));
        var bolt = new RedstoneBolt(level(), this, owner);
        bolt.home(homing);
        bolt.setPos(from.x, from.y, from.z);
        bolt.setDeltaMovement(to.subtract(from).normalize().scale(1.6));
        level().addFreshEntity(bolt);
        playSound(SoundEvents.DISPENSER_LAUNCH, 1, .8f);
    }

    /** Called when a bolt hits: it stokes the furnace too. */
    void boltHit(ServerPlayer owner, LivingEntity hit) {
        arc(owner, hit);
        stoke(owner, 1);
    }

    // ---- Redstone Furnace -------------------------------------------------------------------

    private void stoke(ServerPlayer owner, int hits) {
        if (!hasCommon(owner, 0) || overheated()) return;
        heat += hits;
        if (heat >= FURNACE_HITS) {
            heat = 0;
            overheatTicks = OVERHEAT_TICKS;
            setFlag(F_OVERHEATED, true);
            playSound(SoundEvents.FIRECHARGE_USE, 1.2f, .6f);
            if (action == Action.NONE) triggerAnim("main", "overheat");
            if (hasMinis()) buildMinis(owner);
        }
    }

    /** Playtest hook: stokes the furnace to an overheat at once. */
    public String debugOverheat(ServerPlayer owner) {
        String before = "furnace=" + hasCommon(owner, 0) + " overheated=" + overheated() + " heat=" + heat + " minis=" + hasMinis()
                + " out=" + Ministrosity.of(this).size();
        if (overheated()) {
            // already hot from the fight: an overheat still has to build its Ministrosities
            if (hasMinis()) buildMinis(owner);
        } else stoke(owner, FURNACE_HITS);
        return before + " -> out=" + Ministrosity.of(this).size();
    }

    public int heat() {
        return heat;
    }

    // ---- Bulwark (badge) ---------------------------------------------------------------------

    private void bulwark(ServerPlayer owner) {
        var armor = getAttribute(Attributes.ARMOR);
        var tough = getAttribute(Attributes.ARMOR_TOUGHNESS);
        if (armor != null) armor.setBaseValue(owner.getArmorValue());
        if (tough != null) tough.setBaseValue(owner.getAttributeValue(Attributes.ARMOR_TOUGHNESS));
        for (var m : level().getEntitiesOfClass(Mob.class, getBoundingBox().inflate(GUARD_RANGE), m -> m instanceof Enemy && m.getTarget() == owner))
            m.setTarget(this);
    }

    // ---- Ministrosities and Redstone Arcs (overheat) -------------------------------------------

    /** Ministrosities: an overheat builds up to 3, never more than 3 out at once. */
    private void buildMinis(ServerPlayer owner) {
        if (!(level() instanceof ServerLevel sl)) return;
        int out = Ministrosity.of(this).size();
        for (int i = out; i < MINI_CAP; i++) {
            var m = com.mattermod.registry.ModEntities.MINISTROSITY.get().create(sl);
            if (m == null) return;
            double a = getRandom().nextDouble() * Math.PI * 2;
            Vec3 at = position().add(Math.cos(a) * 1.8, 0, Math.sin(a) * 1.8);
            m.moveTo(at.x, getY(), at.z, getYRot(), 0);
            m.bind(this, owner);
            sl.addFreshEntity(m);
            sl.sendParticles(new DustParticleOptions(new Vector3f(.9f, .1f, .05f), 1.2f), at.x, getY() + .4, at.z, 10, .3, .3, .3, 0);
        }
        playSound(SoundEvents.PISTON_EXTEND, 1, 1.4f);
    }

    /** Redstone Arcs: while overheated, a hit jumps on to up to 3 more enemies within 6 blocks. */
    void arc(ServerPlayer owner, LivingEntity from) {
        if (!hasArcs() || !overheated() || !(level() instanceof ServerLevel sl)) return;
        var near = level().getEntitiesOfClass(LivingEntity.class, from.getBoundingBox().inflate(ARC_RANGE),
                e -> e != from && canHit(owner, e) && (e instanceof Enemy || e == getTarget()));
        near.sort(java.util.Comparator.comparingDouble(e -> e.distanceToSqr(from)));
        var dust = new DustParticleOptions(new Vector3f(1f, .15f, .05f), 1f);
        Vec3 a = from.getBoundingBox().getCenter();
        for (int i = 0; i < Math.min(ARC_TARGETS, near.size()); i++) {
            LivingEntity e = near.get(i);
            Vec3 b = e.getBoundingBox().getCenter();
            int steps = (int) Math.ceil(a.distanceTo(b) * 3);
            for (int k = 0; k <= steps; k++) {
                Vec3 p = a.lerp(b, k / (double) steps);
                double j = k == 0 || k == steps ? 0 : .18;
                sl.sendParticles(dust, p.x + (getRandom().nextDouble() - .5) * j, p.y + (getRandom().nextDouble() - .5) * j, p.z + (getRandom().nextDouble() - .5) * j, 1, 0, 0, 0, 0);
            }
            e.hurt(damageSources().indirectMagic(this, owner), ARC_DAMAGE);
        }
        if (!near.isEmpty()) sl.playSound(null, from.getX(), from.getY(), from.getZ(), SoundEvents.REDSTONE_TORCH_BURNOUT, SoundSource.HOSTILE, 1, 1.6f);
    }

    // ---- Rocket Boots -----------------------------------------------------------------------

    /** The AI's hold on Jump: up toward its rocket goal, gliding on once the fuel is spent, let go above the goal. */
    private boolean wantsThrust() {
        if (rocketGoal == null) return false;
        if (onGround() && fuel < maxFuel()) {   // it took off and came down again
            rocketGoal = null;
            return false;
        }
        return getY() <= rocketGoal.y + 1.2;
    }

    /**
     * One tick of the boots where the Golem is simulated (server, or the rider's client), Terraria style: Jump held in the
     * air thrusts up, accelerating to the top climb speed, while the fuel lasts; spent, Jump held glides down slowly and
     * letting go falls; the fuel refills the moment it lands.
     */
    private void rocketTick(boolean held) {
        Vec3 v = getDeltaMovement();
        boolean was = thrusting;
        thrusting = gliding = false;
        if (onGround()) {
            fuel = maxFuel();
            return;
        }
        if (!held) return;
        if (fuel > 0) {
            thrusting = true;
            fuel--;
            double climb = hasRockets() ? CLIMB_UP : CLIMB;
            setDeltaMovement(v.x, Math.min(Math.max(v.y, -.2) + (hasRockets() ? THRUST_UP : THRUST), climb), v.z);
            fallDistance = 0;
            if (!was) playSound(SoundEvents.FIREWORK_ROCKET_LAUNCH, 1.2f, .6f);
        } else {
            gliding = true;
            if (v.y < GLIDE) setDeltaMovement(v.x, GLIDE, v.z);
            fallDistance = 0;
        }
    }

    // ---- riding (Saddle) ----------------------------------------------------------------------

    @Override
    public InteractionResult mobInteract(Player player, InteractionHand hand) {
        boolean mine = ownerId().map(player.getUUID()::equals).orElse(false);
        var held = player.getItemInHand(hand);
        if (mine && held.is(Items.REDSTONE) && getHealth() < getMaxHealth()) {
            if (!level().isClientSide) {
                heal(10);
                if (!player.getAbilities().instabuild) held.shrink(1);
                playSound(SoundEvents.IRON_GOLEM_REPAIR, 1, 1);
            }
            return InteractionResult.sidedSuccess(level().isClientSide);
        }
        if (mine && hasSaddle() && !player.isSecondaryUseActive() && !isVehicle() && !recalling()) {
            if (!level().isClientSide) player.startRiding(this);
            return InteractionResult.sidedSuccess(level().isClientSide);
        }
        return super.mobInteract(player, hand);
    }

    @Override
    public LivingEntity getControllingPassenger() {
        if (hasSaddle() && getFirstPassenger() instanceof Player p) return p;
        return super.getControllingPassenger();
    }

    @Override
    protected void tickRidden(Player player, Vec3 input) {
        super.tickRidden(player, input);
        setRot(player.getYRot(), player.getXRot() * .5f);
        yRotO = yBodyRot = yHeadRot = getYRot();
        // the rider's client simulates the ride: Jump jumps, held in the air it fires the boots
        if (level().isClientSide) {
            boolean jump = com.mattermod.client.GolemRiding.jumpHeld(player);
            if (jump && !jumpWas && onGround()) jumpFromGround();
            rocketTick(jump && !onGround());
            strideTick();
            jumpWas = jump;
        }
    }

    @Override
    protected Vec3 getRiddenInput(Player player, Vec3 input) {
        float side = player.xxa * .5f, fwd = player.zza;
        if (fwd <= 0) fwd *= .25f;
        return new Vec3(side, 0, fwd);
    }

    @Override
    protected float getRiddenSpeed(Player player) {
        return (float) (BASE_SPEED * strideMult() * (overheated() ? OVERHEAT_FACTOR : 1) * .9);
    }

    /** The seat: the stone square in the middle of the body's top face (31 units up). */
    public static final double SEAT_Y = 31 / 16.0;
    /** Client: where the seat is this frame, from the animated body bone (set by the renderer), relative to the Golem. */
    public Vec3 seatOffset;

    @Override
    protected Vec3 getPassengerAttachmentPoint(Entity passenger, EntityDimensions dims, float scale) {
        return new Vec3(0, SEAT_Y * scale, 0);
    }

    /** The rider sits on the animated body, so it rises, dips and tilts with it (on the client, where it is drawn). */
    @Override
    public Vec3 getPassengerRidingPosition(Entity passenger) {
        if (level().isClientSide && seatOffset != null) return position().add(seatOffset);
        return super.getPassengerRidingPosition(passenger);
    }

    // ---- damage, breakdown -----------------------------------------------------------------

    @Override
    public boolean hurt(DamageSource source, float amount) {
        if (recalling()) return false;
        if (source.getEntity() != null && ownerId().map(source.getEntity().getUUID()::equals).orElse(false))
            return false;
        if (source.getEntity() == this) return false;
        boolean hit = super.hurt(source, amount);
        if (hit && !level().isClientSide) {
            lastDamageTick = tickCount;
        }
        return hit;
    }

    @Override
    public void die(DamageSource source) {
        if (!level().isClientSide) {
            action = Action.NONE;
            for (String a : new String[] {"sweep", "piston", "dispenser", "idle_break", "overheat"}) stopTriggeredAnim("main", a);
            GolemControl.brokeDown(this);
        }
        super.die(source);
    }

    @Override
    protected void tickDeath() {
        deathTime++;
        if (deathTime >= BREAKDOWN_FRAMES && !level().isClientSide() && !isRemoved()) {
            level().broadcastEntityEvent(this, (byte) 60);
            remove(RemovalReason.KILLED);
        }
    }

    @Override
    protected boolean shouldDropLoot() {
        return false;
    }

    /** Starts the recall: it folds up and vanishes, keeping its health. */
    public void recall() {
        if (recalling()) return;
        ejectPassengers();
        action = Action.NONE;
        recallTicks = 0;
        setFlag(F_RECALLING, true);
        getNavigation().stop();
        playSound(SoundEvents.PISTON_CONTRACT, 1, .5f);
    }

    // ---- sounds ------------------------------------------------------------------------------

    @Override
    protected SoundEvent getHurtSound(DamageSource source) {
        return SoundEvents.IRON_GOLEM_HURT;
    }

    @Override
    protected SoundEvent getDeathSound() {
        return SoundEvents.IRON_GOLEM_DEATH;
    }

    @Override
    protected void playStepSound(BlockPos pos, BlockState state) {
        playSound(SoundEvents.IRON_GOLEM_STEP, 1, .8f);
    }

    @Override
    public float getVoicePitch() {
        return .8f;
    }

    // ---- client effects ---------------------------------------------------------------------

    private void clientFx() {
        var r = getRandom();
        if (boosting()) {
            Vec3 fwd = Vec3.directionFromRotation(0, yBodyRot);
            Vec3 side = new Vec3(fwd.z, 0, -fwd.x);
            for (int s : new int[] {-1, 1}) {
                Vec3 p = position().add(side.scale(.6 * s));
                level().addParticle(ParticleTypes.FLAME, p.x, p.y + .1, p.z, (r.nextDouble() - .5) * .05, -.25, (r.nextDouble() - .5) * .05);
                if (r.nextInt(3) == 0) level().addParticle(ParticleTypes.SMOKE, p.x, p.y, p.z, 0, -.1, 0);
            }
        }
        if (overheated() && r.nextInt(2) == 0) {
            level().addParticle(ParticleTypes.FLAME, getX() + (r.nextDouble() - .5) * 1.6, getY() + 1.4 + r.nextDouble(), getZ() + (r.nextDouble() - .5) * 1.6, 0, .04, 0);
            if (r.nextInt(3) == 0) level().addParticle(ParticleTypes.LARGE_SMOKE, getX(), getY() + 2.6, getZ(), 0, .06, 0);
        }
        // summon: it drops out of the sky and lands at frame 8
        long since = level().getGameTime() - builtAt();
        if (since == 8)
            for (int i = 0; i < 40; i++) {
                double a = r.nextDouble() * Math.PI * 2, v = .25 + r.nextDouble() * .3;
                level().addParticle(ParticleTypes.CAMPFIRE_COSY_SMOKE, getX(), getY() + .1, getZ(), Math.cos(a) * v, .02, Math.sin(a) * v);
            }
        // recall: it powers down on all fours and sinks into the ground from frame 18
        clientRecall = recalling() ? clientRecall + 1 : 0;
        if (clientRecall >= 18) {
            var below = level().getBlockState(blockPosition().below());
            for (int i = 0; i < 4; i++) {
                double a = r.nextDouble() * Math.PI * 2;
                if (!below.isAir())
                    level().addParticle(new net.minecraft.core.particles.BlockParticleOption(ParticleTypes.BLOCK, below),
                            getX() + Math.cos(a) * 1.2, getY() + .1, getZ() + Math.sin(a) * 1.2, 0, .1, 0);
                level().addParticle(ParticleTypes.CAMPFIRE_COSY_SMOKE, getX() + Math.cos(a), getY(), getZ() + Math.sin(a), 0, .01, 0);
            }
        }
        if (isDeadOrDying() && deathTime >= BREAKDOWN_FRAMES - 2)
            for (int i = 0; i < 6; i++)
                level().addParticle(ParticleTypes.POOF, getX() + (r.nextDouble() - .5) * 2, getY() + r.nextDouble() * 2.5, getZ() + (r.nextDouble() - .5) * 2, 0, .05, 0);
    }

    // ---- GeckoLib -------------------------------------------------------------------------------

    private static RawAnimation anim(String name) {
        return RawAnimation.begin().thenLoop("animation.monstrosity." + name);
    }

    private static RawAnimation once(String name) {
        return RawAnimation.begin().thenPlay("animation.monstrosity." + name);
    }

    private static RawAnimation hold(String name) {
        return RawAnimation.begin().thenPlayAndHold("animation.monstrosity." + name);
    }

    static final RawAnimation IDLE = anim("idle"), WALK = anim("walk"), RUN = anim("run"), ROCKET = anim("rocket"),
            SUMMON = hold("summon"), RECALL = hold("recall"), BREAK_DOWN = hold("break_down");

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        var main =
                new AnimationController<>(
                        this,
                        "main",
                        3,
                        state -> {
                            RedstoneGolem g = state.getAnimatable();
                            if (g.isDeadOrDying()) return state.setAndContinue(BREAK_DOWN);
                            if (g.recalling()) return state.setAndContinue(RECALL);
                            long since = g.level().getGameTime() - g.builtAt();
                            if (since >= 0 && since < SUMMON_FRAMES) return state.setAndContinue(SUMMON);
                            if (g.boosting()) return state.setAndContinue(ROCKET);
                            if (state.isMoving()) return state.setAndContinue(g.striding() ? RUN : WALK);
                            return state.setAndContinue(IDLE);
                        });
        main.setAnimationSpeedHandler(g -> g.overheated() ? OVERHEAT_FACTOR : 1.0);
        main.triggerableAnim("sweep", once("sweep"))
                .triggerableAnim("piston", once("piston"))
                .triggerableAnim("dispenser", once("dispenser"))
                .triggerableAnim("idle_break", once("idle_break"))
                .triggerableAnim("overheat", once("overheat"));
        controllers.add(main);
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return cache;
    }
}
