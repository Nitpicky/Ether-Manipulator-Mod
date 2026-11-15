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
 * runs (Stride Servos) or flies up (Rocket Boots), can be ridden (Saddle, with a horse-style charged jump), and
 * its Overcharge raises a Cube Field that builds Ministrosities or arcs its blows on (Supercharged).
 *
 * <p>Animations come from golem-model/geckolib/export_geckolib.py (20 fps). Attack timings below
 * are in animation frames and run 1.5x faster while the Redstone Generator is overheated.
 */
public final class RedstoneGolem extends PathfinderMob implements GeoEntity, net.minecraft.world.entity.PlayerRideableJumping {

    // ---- synced state ---------------------------------------------------------------------
    private static final EntityDataAccessor<Optional<UUID>> OWNER =
            SynchedEntityData.defineId(RedstoneGolem.class, EntityDataSerializers.OPTIONAL_UUID);
    /** Trait and state bits, see the F_ constants. */
    private static final EntityDataAccessor<Integer> FLAGS =
            SynchedEntityData.defineId(RedstoneGolem.class, EntityDataSerializers.INT);
    /** Game time of the last (re)assembly: the client plays the summon animation from it. */
    private static final EntityDataAccessor<Long> BUILT_AT =
            SynchedEntityData.defineId(RedstoneGolem.class, EntityDataSerializers.LONG);
    /** Redstone Generator heat in tenths of a hit, 0..GENERATOR_HITS*10: the client shows it as the redstone's signal strength. */
    private static final EntityDataAccessor<Integer> HEAT =
            SynchedEntityData.defineId(RedstoneGolem.class, EntityDataSerializers.INT);
    /** Super Move cooldown: game time it is ready again, and its length in ticks (the HUD's attack indicator). */
    private static final EntityDataAccessor<Long> SUPER_READY =
            SynchedEntityData.defineId(RedstoneGolem.class, EntityDataSerializers.LONG);
    private static final EntityDataAccessor<Integer> SUPER_LEN =
            SynchedEntityData.defineId(RedstoneGolem.class, EntityDataSerializers.INT);

    private static final int F_BOOSTING = 1, F_RECALLING = 2, F_OVERHEATED = 4, F_MINIS = 8, F_SADDLE = 16, F_STRIDE = 32,
            F_ROCKETS = 64, F_CHARGE = 128, F_STRIDING = 256, F_GLIDING = 512, F_VENTING = 1024, F_LIFTING = 2048, F_LEAPING = 4096;

    // ---- tuning ---------------------------------------------------------------------------
    public static final float MAX_HEALTH = 100, SWEEP_DAMAGE = 10, PISTON_DAMAGE = 14, BOLT_DAMAGE = 6;
    public static final double BASE_SPEED = .25, OVERHEAT_FACTOR = 1.5;
    public static final int SUMMON_FRAMES = 56, RECALL_FRAMES = 40, BREAKDOWN_FRAMES = 24;
    /** Basic attack = the lift up (anim "sweep"); Piston trait = the powered slam (anim "piston"). */
    static final int SWEEP_LEN = 32, SWEEP_HIT = 10, PISTON_LEN = 60, PISTON_HIT = 30, DISPENSER_LEN = 60;
    /** Dispenser: both arm cannons fire at once after a heavy charge, and the recoil knocks the Golem back. */
    static final int DISPENSER_FIRE = 32;
    static final double SLAM_RADIUS = 4, LIFT_REACH = 3.2;
    static final int PISTON_COOLDOWN = 120, DISPENSER_COOLDOWN = 100;
    /** Rocket Boots (always fitted; the Rocket Boots trait upgrades them): Terraria style. Holding Jump in the air thrusts up,
     *  accelerating to a top climb speed while the fuel lasts; spent, holding Jump glides down slowly (as Gust does), letting
     *  go falls; the fuel refills the moment it lands. Fuel in ticks, thrust and climb in blocks/tick. */
    static final int ROCKET_FUEL = 32, ROCKET_FUEL_UP = 48;
    static final double THRUST = .10, THRUST_UP = .12, CLIMB = .5, CLIMB_UP = .62, GLIDE = -.12;
    /** Ground speeds in blocks per tick, set from the animations (user 2026-11-15: feet must not slide). The walk is the slow
     *  heavy waddle; Stride (rider's double-tap sprint, or the AI covering ground) is the knuckle gallop at once, no ramp;
     *  the Stride Servos trait gallops faster. */
    static final double WALK_V = .07, STRIDE_V = .4, STRIDE_V_UP = .475;
    /** How far each loop's planted feet carry it per tick at play rate 1 (measured off the animation files: walk and
     *  sidestep feet, the gallop's fists and legs). The loops play at real ground speed over these, so feet stay planted. */
    static final double WALK_PACE = .0459, STRAFE_PACE = .0107, RUN_PACE = .171;
    /** Vanilla ground movement: a mob's input is its speed, so it covers 2.16 x speed^2 blocks a tick; a ridden mob covers
     *  2.16 x its ridden speed (the rider's full forward input). */
    private static final double GROUND = 2.16;
    static final int MINI_CAP = 3;
    static final int GENERATOR_HITS = 10, OVERHEAT_TICKS = 120;
    /** The overheat animation (frames): the Golem does nothing else meanwhile, and the overheat's time starts as it ends. */
    static final int OVERHEAT_LEN = 64, OVERHEAT_SCREAM = 17;
    /** A Super Move stokes the generator by this many hits as it is used. */
    static final int SUPER_HEAT = 3;
    /** Client: the overheat animation's scream (frames 17..26 at 1.5x speed) flashes the redstone to the eyes' white. */
    static final int FLASH_FROM = 11, FLASH_TO = 17, AFTERGLOW_TO = 22;
    static final double FOLLOW_DISTANCE = 4, CATCH_UP_DISTANCE = 24, GUARD_RANGE = 12, REACH = 3.4;

    // ---- server state ---------------------------------------------------------------------
    enum Order { FOLLOW, ATTACK, HOLD }

    enum Action { NONE, SWEEP, PISTON, DISPENSER, OVERHEAT }

    Order order = Order.FOLLOW;
    LivingEntity orderTarget;
    BlockPos holdAt;
    private Action action = Action.NONE;
    private float actionFrame;
    private LivingEntity actionTarget;
    private Vec3 actionAim;
    private int sweepCooldown, armsCooldown, recallTicks, stuckTicks, overheatTicks, lastDamageTick, lastStoke;
    private float heat;
    /** Rocket Boots: thrust ticks left; spent, holding Jump glides down. */
    private int fuel = ROCKET_FUEL;
    private boolean gliding, thrusting, jumpWas;
    /** Stride: the heading it was last moving along (degrees), to drop it on a sharp turn. */
    private float strideHeading;
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
        b.define(HEAT, 0);
        b.define(SUPER_READY, 0L);
        b.define(SUPER_LEN, 1);
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

    /** Jump held in the air: the rocket animation plays, fuelled or not. */
    public boolean boosting() {
        return flag(F_BOOSTING) || (level().isClientSide && (thrusting || gliding));
    }

    /** Boosting with the fuel spent: barely a signal comes out of the boots. */
    public boolean gliding() {
        return flag(F_GLIDING) || (level().isClientSide && gliding);
    }

    /** Vanilla's redstone wire colour at a signal strength 0..15 (RedStoneWireBlock), as dust. */
    public static DustParticleOptions wireDust(float power, float size) {
        float f = power / 15f;
        return new DustParticleOptions(new Vector3f(f * .6f + (f > 0 ? .4f : .3f), Mth.clamp(f * f * .7f - .5f, 0, 1),
                Mth.clamp(f * f * .6f - .7f, 0, 1)), size);
    }

    /** The signal ramp's bright tone (GolemSignalLayer): the texture's red hue-shifting up to the eyes' amber, as dust. */
    private static DustParticleOptions heatDust(float t, float size) {
        int[] ramp = {0xCC200A, 0xDE3C06, 0xEC6A06, 0xFFA61A};
        double x = Mth.clamp(t, 0, 1) * (ramp.length - 1);
        int i = Math.min(ramp.length - 2, (int) x);
        double k = x - i;
        int a = ramp[i], b = ramp[i + 1];
        return new DustParticleOptions(new Vector3f(
                (float) (((a >> 16 & 255) + ((b >> 16 & 255) - (a >> 16 & 255)) * k) / 255),
                (float) (((a >> 8 & 255) + ((b >> 8 & 255) - (a >> 8 & 255)) * k) / 255),
                (float) (((a & 255) + ((b & 255) - (a & 255)) * k) / 255)), size);
    }

    public boolean recalling() {
        return flag(F_RECALLING);
    }

    private float signal, signalPrev;
    /** Client: smoothed blocks per tick, for the gallop's pace; the run_stop window after a Stride. */
    private double pace;
    private boolean wasStriding;
    int runStopUntil;
    private int flashTicks = -1;
    private boolean wasHot;
    private int idleBreakUntil;
    /** Client: frames into the walk loop, and whether it is finishing its step after stopping. */
    private int stillTicks, walkStopUntil;
    private boolean wasWalking;

    /** Client: the redstone's signal, 0 (the texture's own tones) to 1 (signal 15, ready to blow), see GolemSignalLayer. */
    public float signal(float partialTick) {
        return signalPrev + (signal - signalPrev) * partialTick;
    }

    /** Client: the moment of the overheat when the redstone flashes to the eyes' white. */
    public boolean flashing() {
        return flashTicks >= FLASH_FROM && flashTicks < FLASH_TO;
    }

    /** Client: just after the flash, a step halfway back to signal 15 so it does not cut straight back. */
    public boolean afterglow() {
        return flashTicks >= FLASH_TO && flashTicks < AFTERGLOW_TO;
    }

    /** The walk, sidestep and gallop loops' play rate: the speed it moves at in that gait over the ground the loop's planted
     *  feet cover at rate 1, so the feet don't slide. It is a constant per gait (GeckoLib jumps the playhead whenever the
     *  rate changes mid-animation), the same for a chain (run_start into run, run_stop into walk). Attacks and other
     *  one-offs play at the Overcharge rate. */
    double locoRate() {
        if (flag(F_LIFTING) || busy() || leaping()) return animSpeed();
        double k = overheated() ? OVERHEAT_FACTOR : 1;
        if (striding()) return (hasStride() ? STRIDE_V_UP : STRIDE_V) / RUN_PACE * k;
        if (!walkingNow()) return animSpeed();
        if (tickCount >= runStopUntil && Math.abs(lateral) > .5) return SIDESTEP * WALK_V / STRAFE_PACE * k;
        return WALK_V / WALK_PACE * k;
    }

    /** Client: walking this tick (stops at once when it stops, unlike the smoothed pace). */
    public boolean walkingNow() {
        return wasWalking;
    }

    /** Really moving over the ground (smoothed), not vanilla's limb swing, which a slow heavy walk never trips. */
    public boolean moving() {
        return pace > .006;
    }

    /** A ridden leap (or any long drop while ridden) in the air: the leap animation plays. */
    public boolean leaping() {
        return flag(F_LEAPING);
    }

    /** GolemSignalLayer's tones, dark to bright: signal stages 0..5 (0 = the texture's own redstone), the flash, the step after. */
    private static final int[][] TONES = {
            {0x6C1005, 0x851607, 0xA71D08, 0xCC200A},
            {0x731304, 0x8F1B06, 0xB22608, 0xD83009},
            {0x7C1704, 0x9A2305, 0xBF3308, 0xE64707},
            {0x851D03, 0xA72C04, 0xCD4307, 0xF66305},
            {0x8F2301, 0xB53702, 0xDC5507, 0xFC850D},
            {0x9A2A00, 0xC44400, 0xEC6A06, 0xFFA61A},
            {0xFFC890, 0xFFDCB4, 0xFFEEDC, 0xFFFFFF},
            {0xCC7948, 0xE2905A, 0xF6AC71, 0xFFD28C}};

    /** Client: redstone dust in the colours the redstone shows right now (its stage, or the flash), mostly the bright tones. */
    public DustParticleOptions stageDust(float size) {
        int[] row = flashing() ? TONES[6] : afterglow() ? TONES[7] : TONES[Math.min(5, Math.round(signal(1) * 5))];
        int c = row[1 + getRandom().nextInt(3)];
        return new DustParticleOptions(new Vector3f((c >> 16 & 255) / 255f, (c >> 8 & 255) / 255f, (c & 255) / 255f), size);
    }

    /** Client: the Super Move cooldown, 0 (just used) to 1 (ready). */
    public float superReady(float partialTick) {
        long left = entityData.get(SUPER_READY) - level().getGameTime();
        if (left <= 0) return 1;
        return Mth.clamp(1 - (left - partialTick) / Math.max(1, entityData.get(SUPER_LEN)), 0, 1);
    }

    /** Blowing its generator (the overheat animation): it can't move, attack or be steered. */
    public boolean venting() {
        return flag(F_VENTING);
    }

    /** Rising out of the ground, venting or folding away: nothing else can start. */
    public boolean busy() {
        return venting() || recalling() || assembling();
    }

    public boolean overheated() {
        return flag(F_OVERHEATED);
    }

    public boolean hasMinis() {
        return flag(F_MINIS);
    }

    public boolean hasCharge() {
        return flag(F_CHARGE);
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
        return flag(F_STRIDING) || (level().isClientSide && isVehicle() && strideOn);
    }

    /** Falling (not jumping up, not on its boots): the fall animation plays. Client. */
    public boolean falling() {
        return fallTicks > 6 && !boosting();
    }

    private int fallTicks;
    private boolean strideOn;

    private int maxFuel() {
        return hasRockets() ? ROCKET_FUEL_UP : ROCKET_FUEL;
    }

    /** Its ground speed now (blocks per tick): the slow walk, or the gallop at once on Stride; Overcharge speeds it up. */
    private double groundSpeed() {
        double v = strideOn ? hasStride() ? STRIDE_V_UP : STRIDE_V : WALK_V;
        return v * (overheated() ? OVERHEAT_FACTOR : 1);
    }

    /** The AI drops to all fours when it has ground to cover: its owner sprints away, it is far behind, or its target is. */
    private boolean aiWantsStride(ServerPlayer owner) {
        if (action != Action.NONE || order == Order.HOLD && getTarget() == null) return false;
        LivingEntity t = getTarget();
        // anything more than a few steps away, it gallops; it keeps galloping until it is close (no flicker between gaits)
        double go = strideOn ? 4 : 7;
        if (t != null) {           // it gallops right into melee: walking the last stretch was far too slow
            double reach = REACH + t.getBbWidth() / 2 + 1;
            return distanceToSqr(t) > reach * reach;
        }
        double d = distanceToSqr(owner);
        return d > (go + 1) * (go + 1) || owner.isSprinting() && d > FOLLOW_DISTANCE * FOLLOW_DISTANCE * 2;
    }

    /** Lets its rider sprint, which is what starts the Stride. */
    @Override
    public boolean canSprint() {
        return true;
    }

    /** One tick of Stride where the Golem is simulated: asked for (rider sprinting ahead, or the AI covering ground) and
     *  heading straight ahead, it drops on all fours at full gallop speed; anything else (sideways, backing up, a sharp
     *  turn, not asked) drops it at once. */
    private void strideTick(boolean want) {
        strideTick(want, 25, 35);
    }

    /** {@code off}: how far its motion may be off its facing; {@code turnMax}: the sharpest turn in a tick that keeps it. */
    private void strideTick(boolean want, float offMax, float turnMax) {
        Vec3 v = getDeltaMovement();
        double sp = v.horizontalDistance();
        float heading = (float) (Mth.atan2(v.z, v.x) * Mth.RAD_TO_DEG);
        float turn = Math.abs(Mth.wrapDegrees(heading - strideHeading));
        strideHeading = heading;
        float off = Math.abs(Mth.wrapDegrees(heading - 90 - yBodyRot));
        // starting from a walk it is still slow: only its heading counts until it is under way
        boolean ok = want && (sp < .03 || off <= offMax && turn <= turnMax);
        strideOn = ok;
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
        landing = 9;
        risen = 21;
    }

    private int landing, risen, clientRecall;
    /** Client: -1..1, how much it is stepping to its left (-) or right (+) rather than walking ahead. */
    private double lateral;

    private boolean assembling() {
        long since = level().getGameTime() - builtAt();
        return since >= 0 && since < SUMMON_FRAMES - 6;
    }

    /** Copies the owner's Mechatronic traits onto the Golem. */
    void readTraits(ServerPlayer owner) {
        var st = Manipulator.stack(owner);
        setFlag(F_SADDLE, hasCommon(owner, 1));
        setFlag(F_MINIS, Traits.has(st, "mechatronic.ministrosities"));
        setFlag(F_CHARGE, Traits.has(st, "mechatronic.supercharged"));
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
            // the generator's heat as signal: climbs quickly with each hit, cools slowly (2.5s from full) after an overheat
            signalPrev = signal;
            boolean hot = overheated();
            if (hot && !wasHot) {
                flashTicks = 0;
                fieldOnTick = tickCount;
            }
            if (!hot && wasHot) fieldOffTick = tickCount;
            wasHot = hot;
            float target = hot ? 1 : entityData.get(HEAT) / (GENERATOR_HITS * 10f);
            signal += target > signal ? Math.min(target - signal, 1 / 10f) : Math.max(target - signal, -1 / 50f);
            if (flashTicks >= 0 && ++flashTicks >= AFTERGLOW_TO) flashTicks = -1;
            // Stride: how fast it really moves sets the gallop's pace; leaving it plays the push back upright
            double moved = Math.hypot(getX() - xo, getZ() - zo);
            pace += (moved - pace) * .2;
            // sideways against its facing: the heavy sidestep plays instead of the walk
            double yaw = Math.toRadians(yBodyRot), dx = getX() - xo, dz = getZ() - zo;
            double fwd = -Math.sin(yaw) * dx + Math.cos(yaw) * dz, right = -Math.cos(yaw) * dx - Math.sin(yaw) * dz;
            lateral += ((Math.abs(right) > Math.abs(fwd) * 1.2 && Math.abs(right) > .01 ? Math.signum(right) : 0) - lateral) * .35;
            fallTicks = !onGround() && getY() - yo < -.12 ? fallTicks + 1 : 0;
            // vanilla-style stop: the walk ends the tick it stops moving (not when the smoothed pace runs down), and the
            // controller eases the legs back to rest from wherever they are (walkStopUntil: a longer blend, like vanilla's
            // limb swing amplitude dying away)
            double movedNow = Math.hypot(getX() - xo, getZ() - zo);
            stillTicks = movedNow < .004 ? stillTicks + 1 : 0;
            boolean walking = stillTicks < 2 && pace > .004 && !striding() && !leaping();
            if (wasWalking && !walking) walkStopUntil = tickCount + 6;
            wasWalking = walking;
            boolean str = striding();
            if (wasStriding && !str) runStopUntil = tickCount + 8;
            if (str) runStopUntil = 0;
            wasStriding = str;
            clientFx();
            return;
        }
        if (!isAlive()) return;
        if (landing > 0 && --landing == 0)
            playSound(com.mattermod.registry.ModSounds.GOLEM_AWAKEN.get(), .57f, 1);   // the roar peaks 2s in: at the summon's roar (frame 49)
        if (risen > 0 && --risen == 0) {          // fully out of the ground: it powers up
            playSound(com.mattermod.registry.ModSounds.GOLEM_LAND.get(), 1, 1);
        }
        ServerPlayer owner = owner();
        if (owner == null || owner.level() != level() || !Traits.hasLine(Manipulator.stack(owner), Traits.MECHATRONIC)) {
            GolemControl.putAway(this, false);
            return;
        }
        if (recalling()) {
            getNavigation().stop();
            if (++recallTicks == 10) playSound(com.mattermod.registry.ModSounds.GOLEM_POWER_DOWN.get(), .97f, 1);
            if (recallTicks == 18) playSound(com.mattermod.registry.ModSounds.GOLEM_LAND.get(), .57f, 1);
            if (recallTicks >= RECALL_FRAMES) GolemControl.putAway(this, false);
            return;
        }
        if (tickCount % 20 == 0) readTraits(owner);
        if (sweepCooldown > 0) sweepCooldown--;
        if (armsCooldown > 0) armsCooldown--;

        // what it really moved since last tick: a ridden Golem is moved by its rider's client between ticks, so xo (set at
        // the start of the tick) shows no motion on the server; track the position ourselves
        Vec3 now = position();
        double movedH = lastPos == null ? 0 : Math.hypot(now.x - lastPos.x, now.z - lastPos.z);
        risingY = lastPos == null ? 0 : now.y - lastPos.y;
        lastPos = now;
        // speed: Stride and the overheat
        // the AI follows paths round corners: it keeps the gallop through wider turns than a rider does
        if (!isVehicle()) strideTick(aiWantsStride(owner), 60, 50);
        else if (getControllingPassenger() instanceof Player rider) {
            // the rider's client runs the Stride; others see it from here: sprinting, and really moving
            strideOn = rider.isSprinting() && movedH > .08;
        }
        setFlag(F_STRIDING, strideOn);
        pace += (movedH - pace) * .2;
        landingTick(owner);
        footsteps();
        playCues();
        // the visuals carry the Overcharge: only a rare, faint spark
        if (overheated() && random.nextInt(80) == 0) playSound(com.mattermod.registry.ModSounds.GOLEM_SPARKS.get(), .2f, 1);
        double speed = Math.sqrt(groundSpeed() / GROUND);
        var attr = getAttribute(Attributes.MOVEMENT_SPEED);
        if (attr != null && attr.getBaseValue() != speed) attr.setBaseValue(speed);

        // now and then, while standing guard, it looks around
        boolean still = !isVehicle() && getNavigation().isDone() && !moving() && getDeltaMovement().horizontalDistanceSqr() < 1e-4
                && Math.abs(getX() - xo) + Math.abs(getZ() - zo) < 1e-3 && onGround();
        if (action == Action.NONE && still && !assembling() && !overheatPending && random.nextInt(400) == 0) {
            triggerAnim("main", "idle_break");
            idleBreakUntil = tickCount + 80;
            cue(6, com.mattermod.registry.ModSounds.GOLEM_LAUGH.get(), .48f, 1);   // the animation is timed to this laugh starting at frame 6
        } else if (idleBreakUntil > tickCount && (moving() || isVehicle() || getTarget() != null || action != Action.NONE)) {
            // once started it plays out: only real movement, a fight or a rider cuts it (a nudge or a glance does not)
            if (action == Action.NONE) stopTriggeredAnim("main", "idle_break");
            idleBreakUntil = 0;
        }

        cool();
        if (overheated()) fieldTick(owner);
        // Redstone Generator: the overheat runs out
        if (overheatTicks > 0 && action != Action.OVERHEAT && --overheatTicks == 0) {
            setFlag(F_OVERHEATED, false);
            playSound(com.mattermod.registry.ModSounds.GOLEM_FIELD_DROP.get(), .8f, 1);   // the Cube Field collapses
        }

        // slow repair once out of the fight
        if (tickCount % 40 == 0 && tickCount - lastDamageTick > 100 && getHealth() < getMaxHealth()) heal(1);

        if (tickCount % 20 == 0) bulwark(owner);

        if (!isVehicle()) {
            boolean want = wantsThrust();
            if (want && onGround()) jumpFromGround();
            rocketTick(want);
            if (rocketGoal != null && !onGround() && (thrusting || gliding)) {
                // climb first, then cross over to the goal once level with it
                Vec3 flat = rocketGoal.subtract(position()).multiply(1, 0, 1);
                Vec3 v = getDeltaMovement();
                if (flat.lengthSqr() > .5) {
                    Vec3 d = flat.normalize().scale(getY() > rocketGoal.y - 1 ? .2 : .04);
                    setDeltaMovement(d.x, v.y, d.z);
                }
            }
            if (!onGround() && (thrusting || gliding)) hurtMarked = true;
            setFlag(F_BOOSTING, thrusting || gliding);
            setFlag(F_GLIDING, gliding);
        }
        // the generator blows as soon as the Golem is free: nothing else until it is done
        if (action == Action.NONE && overheatPending && !assembling()) {
            overheatPending = false;
            queuedUntil = 0;
            start(Action.OVERHEAT, null, null);
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
            if (toOwner < 14 * 14 && rocketTo(owner.position())) {
                // flying up to where it can't walk
            } else if (GolemControl.teleportNear(this, owner)) {
                stuckTicks = 0;
                return;
            }
        }

        if (target != null) {
            // Rocket Boots: up to flying or perched targets
            if (target.getY() > getY() + 2.5 && horizontalDistance(target.position()) < 10 && tickCount % 10 == 0)
                rocketTo(target.position());
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
        // follow with some slack (starts past 6 blocks, stops within FOLLOW_DISTANCE) so it doesn't stop-start on every
        // step of its owner, and re-paths only when the owner has moved on, not every half second
        boolean far = toOwner > 6 * 6;
        if (far || followGoal != null && toOwner > FOLLOW_DISTANCE * FOLLOW_DISTANCE) {
            if (followGoal == null || getNavigation().isDone() || owner.position().distanceToSqr(followGoal) > 2.25) {
                followGoal = owner.position();
                getNavigation().moveTo(owner, 1);
            }
        } else {
            if (followGoal != null) getNavigation().stop();
            followGoal = null;
            if (tickCount % 40 == 0) getLookControl().setLookAt(owner, 10, 10);
        }
    }

    /** Where its owner was when it last set off after them, null while it stands by them. */
    private Vec3 followGoal;

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
        if (action != Action.NONE || sweepCooldown > 0 || recalling() || assembling() || overheatPending) return false;
        start(Action.SWEEP, target, null);
        return true;
    }

    /** The Super Move on {@code target} (or toward {@code aim}): Piston uppercut or Dispenser bolts.
     * Asked mid-sweep, it is queued and starts as soon as the sweep ends (within 1.5s). */
    public boolean armsMove(ServerPlayer owner, LivingEntity target, Vec3 aim) {
        boolean piston = hasTrait(owner, "mechatronic.piston_arm");
        if (!piston && !hasTrait(owner, "mechatronic.dispenser_arm")) return false;
        if (armsCooldown > 0 || recalling() || assembling() || overheatPending || action == Action.OVERHEAT) return false;
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
        entityData.set(SUPER_LEN, armsCooldown);
        entityData.set(SUPER_READY, level().getGameTime() + armsCooldown);
        stoke(owner, SUPER_HEAT);
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
        if (a != Action.OVERHEAT) {
            setFlag(F_LIFTING, true);           // planted for any blow: the rider can't steer it meanwhile
            setDeltaMovement(0, getDeltaMovement().y, 0);
        }
        if (a == Action.SWEEP) {
            sweepCooldown = Math.round(SWEEP_LEN / animSpeed()) + 4;   // +4: a new trigger on the very tick the last
            stopTriggeredAnim("main", "sweep");                           // one ended got lost on the client
            strideOn = false;
        }
        if (a == Action.OVERHEAT) {
            setFlag(F_OVERHEATED, true);
            setFlag(F_VENTING, true);
            setDeltaMovement(0, getDeltaMovement().y, 0);
            stopTriggeredAnim("main", "idle_break");
            // the overcharge animation plays at 1.5x: frame 12 = tick 8, frame 17 = tick 11
            cue(8, com.mattermod.registry.ModSounds.GOLEM_SCREAM.get(), .72f, 1);
            cue(8, com.mattermod.registry.ModSounds.GOLEM_SPARK_HIT.get(), 1, 1);
            cue(11, com.mattermod.registry.ModSounds.GOLEM_OVERHEAT.get(), .93f, 1);   // the field rising
        }
        triggerAnim("main", switch (a) {
            case SWEEP -> "sweep";
            case PISTON -> "piston";
            case DISPENSER -> "dispenser";
            case OVERHEAT -> "overheat";
            default -> "sweep";
        });
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
        if (action == Action.DISPENSER && owner != null && actionFrame <= DISPENSER_FIRE) {
            // the cannons follow where its owner is aiming right up to the shot, not where they aimed when they clicked
            var look = GolemControl.aim(owner, this);
            actionTarget = GolemControl.target(look);
            actionAim = look.getLocation();
        }
        Vec3 aim = aimPoint();
        boolean early = f < (action == Action.SWEEP ? SWEEP_HIT : action == Action.PISTON ? PISTON_HIT - 4 : DISPENSER_FIRE);
        if (early) face(aim);
        switch (action) {
            case SWEEP -> {
                // the grunt on one lift in three: the most repeated sound of a fight stays sparse
                if (crossed(f, prev, 1) && random.nextInt(3) == 0) playSound(com.mattermod.registry.ModSounds.GOLEM_VOICE_SHORT.get(), .45f, 1);
                if (crossed(f, prev, 6)) playSound(com.mattermod.registry.ModSounds.GOLEM_SWIPE.get(), .3f, .9f);
                if (crossed(f, prev, SWEEP_HIT)) sweepHit(owner);
                setDeltaMovement(0, getDeltaMovement().y, 0);     // planted for the heave
                if (f >= SWEEP_LEN) {          // locked until the animation is over
                    action = Action.NONE;
                    setFlag(F_LIFTING, false);
                }
            }
            case PISTON -> {
                if (crossed(f, prev, 4)) playSound(com.mattermod.registry.ModSounds.GOLEM_ARM.get(), .22f, 1);        // the shoulders turn
                if (crossed(f, prev, 15)) playSound(com.mattermod.registry.ModSounds.GOLEM_CHARGE.get(), .31f, 1);    // the warning
                if (crossed(f, prev, PISTON_HIT)) pistonHit(owner);
                if (f >= PISTON_LEN) {
                    action = Action.NONE;
                    setFlag(F_LIFTING, false);
                }
            }
            case DISPENSER -> {
                if (crossed(f, prev, 1)) playSound(com.mattermod.registry.ModSounds.GOLEM_LOAD.get(), .5f, 1);     // the telegraph
                if (crossed(f, prev, DISPENSER_FIRE - 2)) playSound(com.mattermod.registry.ModSounds.GOLEM_SHOOT.get(), .48f, 1);
                if (crossed(f, prev, DISPENSER_FIRE)) {
                    // both bolts home: on the target, and the second on the enemy closest to it when there is one
                    LivingEntity first = boltTarget(owner, aim, null), second = first == null ? null : boltTarget(owner, first.getBoundingBox().getCenter(), first);
                    fireBolt(owner, aim, true, first);
                    fireBolt(owner, aim, false, second != null ? second : first);
                    recoilStep(.36);           // the recoil drives it two heavy steps back, sliding a little
                    playSound(com.mattermod.registry.ModSounds.GOLEM_STEP.get(), .26f, 1);
                }
                if (crossed(f, prev, DISPENSER_FIRE + 3)) {
                    recoilStep(.3);
                }
                if (f >= DISPENSER_LEN) {
                    action = Action.NONE;
                    setFlag(F_LIFTING, false);
                }
            }
            case OVERHEAT -> {
                getNavigation().stop();
                if (crossed(f, prev, OVERHEAT_SCREAM) && hasMinis()) buildMinis(owner);   // they come up as it screams
                if (f >= OVERHEAT_LEN) {
                    action = Action.NONE;
                    setFlag(F_VENTING, false);
                    overheatTicks = OVERHEAT_TICKS;      // the overheat's time starts once it is back in action
                }
            }
            default -> action = Action.NONE;
        }
    }

    /** Basic attack, the lift up: both arms heave up through the front, everything in a 3-block arc ahead is hit and thrown up. */
    /** Heavyweight Bout: an Iron Golem caught in a blow is shoved, not hurt, and earns the owner the advancement. */
    private void bout(ServerPlayer owner, AABB area) {
        for (var ig : level().getEntitiesOfClass(IronGolem.class, area)) {
            Vec3 away = ig.position().subtract(position());
            ig.knockback(.8, -away.x, -away.z);
            if (owner != null) com.mattermod.advancement.ManipulatorTrigger.fire(owner, "heavyweight");
        }
    }

    private void sweepHit(ServerPlayer owner) {
        bout(owner, getBoundingBox().inflate(LIFT_REACH, 1.5, LIFT_REACH));
        playSound(com.mattermod.registry.ModSounds.GOLEM_LAND.get(), 1, 1.2f);
        Vec3 fwd = Vec3.directionFromRotation(0, getYRot());
        int hits = 0;
        for (var e : level().getEntitiesOfClass(LivingEntity.class, getBoundingBox().inflate(LIFT_REACH, 1.5, LIFT_REACH), e -> canHit(owner, e))) {
            Vec3 to = e.position().subtract(position()).multiply(1, 0, 1);
            double d = to.length();
            if (d > LIFT_REACH + e.getBbWidth() / 2 + getBbWidth() / 2) continue;
            if (d > .01 && to.normalize().dot(fwd) < .2) continue;          // in front only
            if (!e.hurt(damageSources().mobAttack(this), melee(owner, (float) getAttributeValue(Attributes.ATTACK_DAMAGE)))) continue;
            // straight up, like the iron golem's toss; raging, it hurls them up and away
            double k = knock();
            e.setDeltaMovement(fwd.x * (k > 1 ? .9 : .12), 1.15 * (k > 1 ? 1.2 : 1), fwd.z * (k > 1 ? .9 : .12));
            e.hurtMarked = true;
            landed(owner, e);
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
        playSound(com.mattermod.registry.ModSounds.GOLEM_SLAM.get(), 1, 1);
        playSound(com.mattermod.registry.ModSounds.GOLEM_SPARK_HIT.get(), 1, 1);
        // the blast itself: as big as a creeper's (vanilla explosion, a little lower and heavier)
        level().playSound(null, getX(), getY(), getZ(), SoundEvents.GENERIC_EXPLODE.value(), getSoundSource(), 2.5f, .8f + random.nextFloat() * .1f);
        Vec3 fwd = Vec3.directionFromRotation(0, getYRot());
        Vec3 c = position().add(fwd.scale(1.6));
        bout(owner, new AABB(c, c).inflate(SLAM_RADIUS, 2, SLAM_RADIUS));
        int hits = 0;
        for (var e : level().getEntitiesOfClass(LivingEntity.class, new AABB(c, c).inflate(SLAM_RADIUS, 2, SLAM_RADIUS), e -> canHit(owner, e))) {
            Vec3 to = e.position().subtract(c).multiply(1, 0, 1);
            double d = to.length();
            if (d > SLAM_RADIUS + e.getBbWidth() / 2 || Math.abs(e.getY() - getY()) > 2.5) continue;
            if (!e.hurt(damageSources().mobAttack(this), melee(owner, PISTON_DAMAGE))) continue;
            Vec3 push = (d > .01 ? to.normalize() : fwd).scale((1.5 - .2 * d) * knock());
            e.setDeltaMovement(e.getDeltaMovement().add(push.x, .7 * Math.sqrt(knock()), push.z));
            e.hurtMarked = true;
            landed(owner, e);
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
    }

    /** Called when a bolt hits: it stokes the generator too. */
    void boltHit(ServerPlayer owner, LivingEntity hit) {
        if (overheated()) {
            Vec3 push = hit.position().subtract(position()).multiply(1, 0, 1);
            if (push.lengthSqr() > 1e-4) push = push.normalize().scale(.9);
            hit.setDeltaMovement(hit.getDeltaMovement().add(push.x, .35, push.z));
            hit.hurtMarked = true;
        }
        landed(owner, hit);
        stoke(owner, 1);
    }

    // ---- Redstone Generator -------------------------------------------------------------------

    private boolean overheatPending;

    /** Overcharged, each blow that lands keeps it going this much longer (up to the full Overcharge time). */
    static final int OVERCHARGE_PER_HIT = 30;

    private void stoke(ServerPlayer owner, int hits) {
        if (overheated() && action != Action.OVERHEAT && overheatTicks > 0) {
            overheatTicks = Math.min(OVERHEAT_TICKS, overheatTicks + OVERCHARGE_PER_HIT * hits);   // keeps fighting, stays charged
            return;
        }
        if (!hasCommon(owner, 0) || overheated() || overheatPending) return;
        heat += hits;
        lastStoke = tickCount;
        entityData.set(HEAT, Math.round(Math.min(heat, GENERATOR_HITS) * 10));
        if (heat >= GENERATOR_HITS) {
            heat = 0;
            entityData.set(HEAT, 0);
            overheatPending = true;      // blows once the current move is over (see tick)
        }
    }

    /** Playtest hook: stokes the generator to an overheat at once. */
    public String debugOverheat(ServerPlayer owner) {
        String before = "generator=" + hasCommon(owner, 0) + " overheated=" + overheated() + " heat=" + heat + " minis=" + hasMinis()
                + " out=" + Ministrosity.of(this).size();
        if (overheated()) {
            // already hot from the fight: an overheat still has to build its Ministrosities
            if (hasMinis()) buildMinis(owner);
        } else stoke(owner, GENERATOR_HITS);
        return before + " -> out=" + Ministrosity.of(this).size();
    }

    /** Heat cools once the hits stop: it holds for HEAT_HOLD ticks, then drains faster and faster
     *  (full heat is gone about 10s later). */
    static final int HEAT_HOLD = 200;
    static final float HEAT_DRAIN = .0005f;

    private void cool() {
        int idle = tickCount - lastStoke - HEAT_HOLD;
        if (heat <= 0 || idle <= 0 || overheatPending) return;
        heat = Math.max(0, heat - HEAT_DRAIN * idle);
        int synced = Math.round(heat * 10);
        if (entityData.get(HEAT) != synced) entityData.set(HEAT, synced);
    }

    public float heat() {
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
        playSound(com.mattermod.registry.ModSounds.MINI_SPAWN.get(), .76f, 1);   // once for the whole group
    }

    /** Supercharged: while it rages it crackles with arcs, every blow it lands arcs on from enemy to enemy, and it
     *  shrugs off a share of the damage. */
    static final int CHAIN_JUMPS = 3;
    static final double CHAIN_RANGE = 5;
    static final float CHAIN_DAMAGE = 5, CHARGED_TAKEN = .7f;

    /** Supercharged and overheated right now. */
    public boolean charged() {
        return hasCharge() && overheated();
    }

    /** Playtest/debug: how many arcs it has thrown. */
    public int arcsThrown;

    /**
     * Chain arcs from {@code first} (just hit): each jump goes to the nearest enemy not yet struck within CHAIN_RANGE of
     * the last one, with a clear line between them, up to CHAIN_JUMPS. Clients draw each jump as a pixel bolt.
     * Returns the number of jumps.
     */
    public int chainArcs(ServerPlayer owner, LivingEntity first) {
        if (!(level() instanceof ServerLevel sl) || first == null) return 0;
        java.util.Set<LivingEntity> struck = new java.util.HashSet<>();
        struck.add(first);
        LivingEntity from = first;
        java.util.List<Float> pts = new java.util.ArrayList<>();
        addPoint(pts, from.getBoundingBox().getCenter());
        int jumps = 0;
        for (int i = 0; i < CHAIN_JUMPS; i++) {
            Vec3 at = from.getBoundingBox().getCenter();
            LivingEntity best = null;
            double bd = CHAIN_RANGE * CHAIN_RANGE;
            for (var e : level().getEntitiesOfClass(LivingEntity.class, new AABB(at, at).inflate(CHAIN_RANGE),
                    e -> !struck.contains(e) && canHit(owner, e) && (e instanceof Enemy || e == getTarget() || e.getLastHurtByMob() == this))) {
                double d = e.getBoundingBox().getCenter().distanceToSqr(at);
                if (d >= bd) continue;
                var clip = level().clip(new net.minecraft.world.level.ClipContext(at, e.getBoundingBox().getCenter(),
                        net.minecraft.world.level.ClipContext.Block.COLLIDER, net.minecraft.world.level.ClipContext.Fluid.NONE, this));
                if (clip.getType() != net.minecraft.world.phys.HitResult.Type.MISS) continue;
                bd = d;
                best = e;
            }
            if (best == null) break;
            struck.add(best);
            best.hurt(damageSources().indirectMagic(this, owner), CHAIN_DAMAGE);
            Vec3 c = best.getBoundingBox().getCenter();
            sl.sendParticles(new DustParticleOptions(new Vector3f(1f, .65f, .2f), 1f), c.x, c.y, c.z, 6, .25, .3, .25, 0);
            addPoint(pts, c);
            from = best;
            jumps++;
        }
        if (jumps == 0) return 0;
        arcsThrown += jumps;
        sl.playSound(null, first.getX(), first.getY(), first.getZ(), com.mattermod.registry.ModSounds.GOLEM_ARC.get(), SoundSource.HOSTILE, .61f * SOUND_GAIN, 1);
        net.neoforged.neoforge.network.PacketDistributor.sendToPlayersTrackingEntityAndSelf(this,
                new com.mattermod.network.payload.GolemFxPayload(getId(), com.mattermod.network.payload.GolemFxPayload.CHAIN, pts));
        return jumps;
    }

    private static void addPoint(java.util.List<Float> pts, Vec3 v) {
        pts.add((float) v.x);
        pts.add((float) v.y);
        pts.add((float) v.z);
    }

    /** A blow landed on {@code e}: Supercharged arcs it on. */
    private void landed(ServerPlayer owner, LivingEntity e) {
        if (charged()) chainArcs(owner, e);
    }

    /** Overcharge (overheated() in code): it hits harder and throws foes much further. */
    public static final float RAGE_HIT = 1.5f;
    public static final double RAGE_KNOCK = 1.7;

    /** Damage dealt, with the Overcharge. */
    float dealt(float base) {
        return overheated() ? base * RAGE_HIT : base;
    }

    /** Its melee blows (the lift, the Piston slam) carry the owner's Matter Manipulator melee bonus. */
    private float melee(ServerPlayer owner, float base) {
        return dealt(base + (owner == null ? 0 : com.mattermod.trait.RedesignEffects.meleeBonus(owner)));
    }

    /** Its Dispenser bolts carry the owner's Matter Manipulator ranged bonus. */
    float boltDamage(ServerPlayer owner) {
        return dealt(BOLT_DAMAGE + (owner == null ? 0 : com.mattermod.trait.RedesignEffects.rangedBonus(owner)));
    }

    private double knock() {
        return overheated() ? RAGE_KNOCK : 1;
    }

    // ---- Cube Field (Overcharge) -------------------------------------------------------------

    /** The Cube Field it raises as it overcharges: a 7-block cube centred on it, moving with it, for the whole Overcharge.
     *  Hostile projectiles can't cross it either way; friendly ones (its own, its owner's, players') pass. */
    public static final double FIELD_HALF = 3.5;
    /** Playtest/debug: projectiles the field has stopped. */
    public int fieldBlocks;

    /** The field: a cube centred on the middle of its body. */
    public AABB fieldBox() {
        double cy = fieldCentreY();
        return new AABB(getX() - FIELD_HALF, cy - FIELD_HALF, getZ() - FIELD_HALF, getX() + FIELD_HALF, cy + FIELD_HALF, getZ() + FIELD_HALF);
    }

    /** Height of the field's centre above its feet. */
    public double fieldCentreOffset() {
        return getBbHeight() / 2;
    }

    private double fieldCentreY() {
        return getY() + fieldCentreOffset();
    }

    private void fieldTick(ServerPlayer owner) {
        if (!(level() instanceof ServerLevel sl)) return;
        AABB box = fieldBox();
        for (var pr : level().getEntitiesOfClass(net.minecraft.world.entity.projectile.Projectile.class, box.inflate(4))) {
            if (!pr.isAlive()) continue;
            Entity shooter = pr.getOwner();
            if (shooter != null && !(shooter instanceof LivingEntity l && canHit(owner, l))) continue;   // friendly fire passes
            Vec3 from = new Vec3(pr.xo, pr.yo, pr.zo), to = pr.position().add(pr.getDeltaMovement());
            boolean in0 = box.contains(from), in1 = box.contains(to);
            Optional<Vec3> at;
            if (in0 && in1) continue;
            else if (in0) at = box.clip(to, from);         // leaving: where it would come out
            else at = box.clip(from, to);                  // coming in, or passing through
            if (at.isEmpty()) continue;
            Vec3 p = at.get();
            pr.discard();
            fieldBlocks++;
            sl.playSound(null, p.x, p.y, p.z, com.mattermod.registry.ModSounds.GOLEM_FIELD_HIT.get(), SoundSource.HOSTILE, .51f * SOUND_GAIN, 1);
            java.util.List<Float> pts = new java.util.ArrayList<>();
            addPoint(pts, p);
            net.neoforged.neoforge.network.PacketDistributor.sendToPlayersTrackingEntityAndSelf(this,
                    new com.mattermod.network.payload.GolemFxPayload(getId(), com.mattermod.network.payload.GolemFxPayload.FIELD_HIT, pts));
        }
    }

    /** Drawn while the field (or its collapse) may be on screen even when the Golem itself is not. */
    @Override
    public AABB getBoundingBoxForCulling() {
        return overheated() || level().isClientSide && tickCount - fieldOffTick < 12 ? fieldBox().inflate(.5) : super.getBoundingBoxForCulling();
    }

    /** Client: when the field last went up (for its deploy) and down (for its collapse). */
    public int fieldOnTick = -100, fieldOffTick = -100;

    // ---- the charged jump (Saddle), horse style --------------------------------------------------

    /** Hold Jump to charge (the jump bar fills), let go to jump: from a hop to about 4 blocks. Striding, it is a Winston
     *  leap instead: launched up and forward along its heading on a committed arc (no steering in the air, no drag),
     *  from about 8 to 20 blocks (up to 6 high), and it lands with a slam. In the air Jump fires the Rocket Boots. */
    static final double JUMP_LOW = .45, JUMP_HIGH = .85, LEAP_V_LOW = .7, LEAP_V_HIGH = 1.05, LEAP_H_LOW = .4, LEAP_H_HIGH = .7;
    /** The leap's landing: everything within LAND_RADIUS is hit and thrown out; it needs this many ticks in the air. */
    static final double LAND_RADIUS = 3;
    static final float LAND_DAMAGE = 6;
    static final int LAND_AIR_TICKS = 12;
    /** Client (rider): the charge of the jump to make, 0 = none. */
    private float pendingJump;
    /** Client (rider): the leap's locked ground velocity while in the air, null when not leaping; its ticks airborne. */
    private Vec3 leapVel;
    private int leapTicks;
    /** Server: ticks in the air while ridden, for the landing slam. */
    private int airTicks;
    /** Server: its position last tick and how much it rose since (see the tick). */
    private Vec3 lastPos;
    private double risingY;
    /** Server: this time in the air began with a jump (it rose), not a drop. */
    private boolean jumped;

    @Override
    public void onPlayerJump(int power) {
        if (power <= 0) return;
        pendingJump = power >= 90 ? 1 : .4f + .4f * power / 90f;
    }

    @Override
    public boolean canJump() {
        return hasSaddle() && grounded() && !busy() && !flag(F_LIFTING);
    }

    /** Coyote time: a few ticks after it leaves the ground (a gallop over a bump, off a ledge) it still counts as on the
     *  ground for the charged jump, so a charge in progress is not lost and a late release still jumps. */
    static final int COYOTE_TICKS = 6;
    /** Client (rider): the tick it was last on the ground (reset by a jump so it can't jump twice in the air). */
    private int groundTick = -100;

    private boolean grounded() {
        return onGround() || tickCount - groundTick <= COYOTE_TICKS;
    }

    @Override
    public void handleStartJump(int power) {}

    @Override
    public void handleStopJump() {}

    /** Client (rider): the jump itself. Public for the playtest. */
    public void leap(float scale) {
        Vec3 v = getDeltaMovement();
        if (strideOn) {
            leapVel = Vec3.directionFromRotation(0, getYRot()).scale(LEAP_H_LOW + (LEAP_H_HIGH - LEAP_H_LOW) * scale);
            leapTicks = 0;
            setDeltaMovement(leapVel.x, LEAP_V_LOW + (LEAP_V_HIGH - LEAP_V_LOW) * scale, leapVel.z);
        } else setDeltaMovement(v.x, JUMP_LOW + (JUMP_HIGH - JUMP_LOW) * scale, v.z);
        hasImpulse = true;
        fallDistance = 0;
    }

    /** Client (rider): carries the leap's momentum through the air (no drag on it) while the rider still steers: sideways
     *  input bends the arc, holding back brakes it, until it lands or hits a wall. */
    private void leapTick(Player rider) {
        if (leapVel == null) return;
        if (++leapTicks > 2 && onGround() || horizontalCollision || isInWater()) {
            leapVel = null;
            return;
        }
        Vec3 v = getDeltaMovement(), dir = leapVel.normalize();
        double along = v.x * dir.x + v.z * dir.z, keep = leapVel.length() * (rider.zza < 0 ? .7 : 1);
        if (along < keep) {
            double add = keep - along;
            setDeltaMovement(v.x + dir.x * add, v.y, v.z + dir.z * add);
        }
    }

    /** Server: a ridden leap (or any long drop) comes down with a slam, Winston style: the area around is hit and thrown out. */
    private void landingTick(ServerPlayer owner) {
        if (!isVehicle()) {
            airTicks = 0;
            setFlag(F_LEAPING, false);
            return;
        }
        if (!onGround()) {
            if (airTicks++ == 0) jumped = risingY > .2;
            if (airTicks == 1 && jumped) playSound(com.mattermod.registry.ModSounds.GOLEM_JUMP.get(), .87f, 1);   // takes off
            // a jump shows the leap at once; just running off a bump or a ledge keeps the gallop for a moment first
            setFlag(F_LEAPING, (jumped ? airTicks >= 2 : airTicks >= 8) && !boosting());
            return;
        }
        setFlag(F_LEAPING, false);
        boolean slam = airTicks >= LAND_AIR_TICKS;
        airTicks = 0;
        if (!slam) return;
        triggerAnim("main", "land");
        playSound(com.mattermod.registry.ModSounds.GOLEM_SLAM.get(), 1, 1);
        bout(owner, getBoundingBox().inflate(LAND_RADIUS, 1.5, LAND_RADIUS));
        int hits = 0;
        for (var e : level().getEntitiesOfClass(LivingEntity.class, getBoundingBox().inflate(LAND_RADIUS, 1.5, LAND_RADIUS), e -> canHit(owner, e))) {
            Vec3 to = e.position().subtract(position()).multiply(1, 0, 1);
            double d = to.length();
            if (d > LAND_RADIUS + e.getBbWidth() / 2 + getBbWidth() / 2) continue;
            if (!e.hurt(damageSources().mobAttack(this), melee(owner, LAND_DAMAGE))) continue;
            Vec3 push = (d > .01 ? to.normalize() : Vec3.directionFromRotation(0, getYRot())).scale(1.1 * knock());
            e.setDeltaMovement(e.getDeltaMovement().add(push.x, .5, push.z));
            e.hurtMarked = true;
            landed(owner, e);
            hits++;
        }
        if (hits > 0) stoke(owner, hits);
        if (level() instanceof ServerLevel sl) {
            var below = level().getBlockState(blockPosition().below());
            if (!below.isAir())
                sl.sendParticles(new net.minecraft.core.particles.BlockParticleOption(ParticleTypes.BLOCK, below), getX(), getY() + .1, getZ(), 80, LAND_RADIUS * .5, .15, LAND_RADIUS * .5, .3);
            sl.sendParticles(new DustParticleOptions(new Vector3f(.9f, .1f, .05f), 2f), getX(), getY() + .2, getZ(), 30, LAND_RADIUS * .5, .1, LAND_RADIUS * .5, 0);
        }
    }

    /** Playtest: the highest it got since the last reset. */
    public double peakY = -1e9;

    // ---- Rocket Boots -----------------------------------------------------------------------

    /** Ticks before the AI may take off again after a flight, so it never hops about in a loop. */
    private int rocketRest;

    /**
     * The AI only flies when it has to: the goal is well above it, its boots are full and rested, and walking can't get
     * it there (no path reaches it). Returns true when it takes off.
     */
    private boolean rocketTo(Vec3 goal) {
        if (rocketGoal != null || rocketRest > 0 || !onGround() || fuel < maxFuel() || goal.y < getY() + 2) return false;
        var path = getNavigation().createPath(net.minecraft.core.BlockPos.containing(goal), 1);
        if (path != null && path.canReach()) return false;
        rocketGoal = goal;
        return true;
    }

    /** The AI's hold on Jump: up toward its rocket goal, gliding on once the fuel is spent, let go above the goal. */
    private boolean wantsThrust() {
        if (rocketRest > 0) rocketRest--;
        if (rocketGoal == null) return false;
        if (onGround() && fuel < maxFuel()) {   // it took off and came down again
            rocketGoal = null;
            rocketRest = 100;
            return false;
        }
        if (getY() > rocketGoal.y + 1.2 && horizontalDistance(rocketGoal) < 1.5) {
            // above the ledge: let go and drop onto it
            rocketGoal = null;
            rocketRest = 100;
            return false;
        }
        return true;
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
            if (!was) {
                // ridden, only the rider's client runs the boots: a sound played there must be local (playSound would go
                // to the server's players only, i.e. nobody)
                if (level().isClientSide) level().playLocalSound(getX(), getY(), getZ(), com.mattermod.registry.ModSounds.GOLEM_ROCKET.get(), getSoundSource(), .72f * SOUND_GAIN, 1, false);
                else playSound(com.mattermod.registry.ModSounds.GOLEM_ROCKET.get(), .72f, 1);
            }
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
                playSound(com.mattermod.registry.ModSounds.GOLEM_REPAIR.get(), .93f, 1);
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
            boolean jump = com.mattermod.client.GolemRiding.jumpHeld(player) && !busy();
            if (onGround()) groundTick = tickCount;
            if (pendingJump > 0) {
                if (grounded() && !busy()) {
                    leap(pendingJump);
                    groundTick = -100;
                }
                pendingJump = 0;
                jump = false;        // the release that jumped
            }
            leapTick(player);
            rocketTick(jump && !onGround());
            peakY = Math.max(peakY, getY());
            strideTick(player.isSprinting() && player.zza > 0 && Math.abs(player.xxa) < .3f && !busy());
            jumpWas = jump;
        }
    }

    @Override
    protected Vec3 getRiddenInput(Player player, Vec3 input) {
        // rising, venting, folding away or heaving in its basic attack: it stands its ground
        if (busy() || flag(F_LIFTING)) return Vec3.ZERO;      // (F_LIFTING: any attack or Super Move under way)
        if (leapVel != null) return new Vec3(player.xxa * .8, 0, player.zza * .5);   // mid-leap: air control
        // heavy: it steps sideways and backs up slowly
        float side = player.xxa * SIDESTEP, fwd = player.zza;
        if (fwd <= 0) fwd *= .25f;
        return new Vec3(side, 0, fwd);
    }

    @Override
    protected float getRiddenSpeed(Player player) {
        return (float) (groundSpeed() / GROUND);
    }

    /** Heavy: its head turns less far than most mobs' before the body follows, and the body turns slowly. */
    @Override
    public int getMaxHeadYRot() {
        return 40;
    }

    @Override
    public int getHeadRotSpeed() {
        return 6;
    }

    /** Sideways speed against forward, for the rider and the stride check. */
    static final float SIDESTEP = .25f;

    /** The seat: the stone square in the middle of the body's top face (31 units up), sunk 2 units so the rider sits down
     *  between the shoulder towers with the thighs just over the head (its top is at 29). */
    public static final double SEAT_Y = 29 / 16.0;
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
        if (charged()) amount *= CHARGED_TAKEN;
        boolean hit = super.hurt(source, amount);
        if (hit && !level().isClientSide) {
            lastDamageTick = tickCount;
        }
        return hit;
    }

    @Override
    public void die(DamageSource source) {
        if (!level().isClientSide) {
            stopActions();
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

    /** Drops whatever it was doing (and its animation), for the recall and the breakdown. */
    private void stopActions() {
        if (action == Action.OVERHEAT) overheatTicks = OVERHEAT_TICKS;
        action = Action.NONE;
        overheatPending = false;
        queuedUntil = 0;
        idleBreakUntil = 0;
        setFlag(F_VENTING, false);
        setFlag(F_LIFTING, false);
        for (String a : new String[] {"sweep", "piston", "dispenser", "idle_break", "overheat"}) stopTriggeredAnim("main", a);
    }

    /** Starts the recall: it folds up and vanishes, keeping its health. */
    public void recall() {
        if (recalling()) return;
        ejectPassengers();
        stopActions();
        recallTicks = 0;
        setFlag(F_RECALLING, true);
        getNavigation().stop();
    }

    // ---- sounds ------------------------------------------------------------------------------

    @Override
    protected SoundEvent getHurtSound(DamageSource source) {
        return com.mattermod.registry.ModSounds.GOLEM_HURT.get();
    }

    @Override
    protected SoundEvent getDeathSound() {
        return com.mattermod.registry.ModSounds.GOLEM_DEATH.get();
    }

    @Override
    protected SoundEvent getAmbientSound() {
        return com.mattermod.registry.ModSounds.GOLEM_AMBIENT.get();
    }

    @Override
    public int getAmbientSoundInterval() {
        return 300;
    }

    /** Overall level of its sounds over the bench mix (user 2026-11-15: the bench levels came out too tame in game). The
     *  engine clamps the final volume at 1, so the loud ones stay where they were and the quiet ones come up. */
    static final float SOUND_GAIN = 1.6f;

    @Override
    public void playSound(SoundEvent sound, float volume, float pitch) {
        super.playSound(sound, volume * SOUND_GAIN, pitch);
    }

    /** Its hum only while it stands idle, never on the move, in a fight or ridden. */
    @Override
    public void playAmbientSound() {
        if (action != Action.NONE || getTarget() != null || isVehicle() || moving() || overheated()) return;
        super.playAmbientSound();
    }

    /** Footfalls follow the animations (see footsteps), not vanilla's distance count. */
    @Override
    protected void playStepSound(BlockPos pos, BlockState state) {}

    private float stepClock;

    /** Server: a stomp on each footfall of the walk (two per 40-frame cycle), a gallop beat for each fist and foot pair
     *  of the run (two per 20-frame loop), at the loops' play rate. */
    private void footsteps() {
        // pace, not the motion: a ridden Golem is moved by its rider's client, the server sees no motion
        boolean moving = onGround() && moving() && action == Action.NONE;
        if (!moving) {
            stepClock = 0;
            return;
        }
        boolean run = strideOn, side = !run && Math.abs(lateral) > .5;
        float before = stepClock;
        double k = overheated() ? OVERHEAT_FACTOR : 1;
        stepClock += (float) ((run ? (hasStride() ? STRIDE_V_UP : STRIDE_V) / RUN_PACE : side ? SIDESTEP * WALK_V / STRAFE_PACE : WALK_V / WALK_PACE) * k);
        int period = run ? 10 : side ? 20 : 16;
        int beat = (int) (stepClock / period);
        if ((int) (before / period) != beat || before == 0) {
            // the bench's levels: galloping, the hind legs thud and the fists tap
            float pitch = .95f + random.nextFloat() * .1f;
            if (!run) playSound(com.mattermod.registry.ModSounds.GOLEM_STEP.get(), .41f, pitch);
            else if (beat % 2 == 0) playSound(com.mattermod.registry.ModSounds.GOLEM_GALLOP.get(), .67f, pitch);
            else playSound(com.mattermod.registry.ModSounds.GOLEM_STEP.get(), .2f, pitch);
        }
    }

    /** Server: sounds that land on a later animation frame. */
    private final java.util.List<Object[]> cues = new java.util.ArrayList<>();

    private void cue(int ticks, SoundEvent sound, float volume, float pitch) {
        cues.add(new Object[] {tickCount + ticks, sound, volume, pitch});
    }

    private void playCues() {
        cues.removeIf(c -> {
            if (tickCount < (int) c[0]) return false;
            playSound((SoundEvent) c[1], (float) c[2], (float) c[3]);
            return true;
        });
    }

    @Override
    public float getVoicePitch() {
        return .95f + random.nextFloat() * .1f;
    }

    // ---- client effects ---------------------------------------------------------------------

    private void clientFx() {
        var r = getRandom();
        if (boosting()) {
            Vec3 fwd = Vec3.directionFromRotation(0, yBodyRot);
            Vec3 side = new Vec3(fwd.z, 0, -fwd.x);
            boolean empty = gliding();
            for (int s : new int[] {-1, 1}) {
                Vec3 p = position().add(side.scale(.6 * s));
                if (empty) {
                    // fuel spent: barely a signal, and a wisp of smoke
                    if (r.nextInt(3) == 0) level().addParticle(wireDust(1 + r.nextInt(2), .8f), p.x, p.y + .1, p.z, 0, -.15, 0);
                    if (r.nextInt(4) == 0) level().addParticle(ParticleTypes.SMOKE, p.x, p.y, p.z, 0, -.05, 0);
                } else
                    // thrust: a full signal of redstone, burning through the overheat colours
                    for (int k = 0; k < 3; k++)
                        level().addParticle(heatDust(r.nextFloat(), 1.2f), p.x + (r.nextDouble() - .5) * .3, p.y + .1, p.z + (r.nextDouble() - .5) * .3,
                                (r.nextDouble() - .5) * .05, -.3, (r.nextDouble() - .5) * .05);
            }
        }
        // lit redstone sheds dust like a powered wire
        if (!recalling() && r.nextInt(3) == 0)
            level().addParticle(stageDust(1f), getX() + (r.nextDouble() - .5) * getBbWidth(), getY() + r.nextDouble() * getBbHeight(),
                    getZ() + (r.nextDouble() - .5) * getBbWidth(), 0, 0, 0);
        // Overcharge: electric sparks jump off it (no fire, no smoke)
        if (overheated() && r.nextInt(2) == 0)
            level().addParticle(ParticleTypes.ELECTRIC_SPARK, getX() + (r.nextDouble() - .5) * 1.8, getY() + .4 + r.nextDouble() * 2,
                    getZ() + (r.nextDouble() - .5) * 1.8, (r.nextDouble() - .5) * .3, r.nextDouble() * .2, (r.nextDouble() - .5) * .3);
        // summon: it rises out of the ground (out by frame 20), the reverse of the recall
        long since = level().getGameTime() - builtAt();
        if (since >= 0 && since < 22) {
            var under = level().getBlockState(blockPosition().below());
            for (int i = 0; i < 5; i++) {
                double a = r.nextDouble() * Math.PI * 2;
                if (!under.isAir())
                    level().addParticle(new net.minecraft.core.particles.BlockParticleOption(ParticleTypes.BLOCK, under),
                            getX() + Math.cos(a) * 1.2, getY() + .1, getZ() + Math.sin(a) * 1.2, 0, .15, 0);
                level().addParticle(ParticleTypes.CAMPFIRE_COSY_SMOKE, getX() + Math.cos(a), getY(), getZ() + Math.sin(a), 0, .01, 0);
            }
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

    /** Stride: it drops onto its fists and knuckle-gallops (faster as Stride builds), then pushes back upright. */
    static final RawAnimation RUN = RawAnimation.begin().thenPlay("animation.monstrosity.run_start").thenLoop("animation.monstrosity.run"),
            STOP_WALK = RawAnimation.begin().thenPlay("animation.monstrosity.run_stop").thenLoop("animation.monstrosity.walk"),
            STOP_IDLE = RawAnimation.begin().thenPlay("animation.monstrosity.run_stop").thenLoop("animation.monstrosity.idle");
    static final RawAnimation FALL = anim("fall"), LEAP = anim("leap");
    static final RawAnimation STRAFE_RIGHT = anim("strafe_right"), STRAFE_LEFT = anim("strafe_left");
    static final RawAnimation IDLE = anim("idle"), WALK = anim("walk"), ROCKET = anim("rocket"),
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
                            // straight into the summon (no blend from a standing pose, which would show it above ground)
                            state.getController().transitionLength(since >= 0 && since < SUMMON_FRAMES ? 0 : 3);
                            if (since >= 0 && since < SUMMON_FRAMES) {
                                if (since < 2) state.getController().forceAnimationReset();
                                return state.setAndContinue(SUMMON);
                            }
                            if (g.boosting()) return state.setAndContinue(ROCKET);
                            if (g.leaping()) return state.setAndContinue(LEAP);
                            if (g.falling()) return state.setAndContinue(FALL);
                            if (g.striding()) return state.setAndContinue(RUN);
                            if (g.tickCount < g.runStopUntil) return state.setAndContinue(g.walkingNow() ? STOP_WALK : STOP_IDLE);
                            if (g.walkingNow() && Math.abs(g.lateral) > .5)
                                return state.setAndContinue(g.lateral > 0 ? STRAFE_RIGHT : STRAFE_LEFT);
                            if (g.walkingNow()) return state.setAndContinue(WALK);
                            if (g.tickCount < g.walkStopUntil) state.getController().transitionLength(6);
                            return state.setAndContinue(IDLE);
                        });
        main.setAnimationSpeedHandler(g -> g.locoRate());
        main.triggerableAnim("sweep", once("sweep"))
                .triggerableAnim("piston", once("piston"))
                .triggerableAnim("dispenser", once("dispenser"))
                .triggerableAnim("idle_break", once("idle_break"))
                .triggerableAnim("overheat", once("overheat"))
                .triggerableAnim("land", once("land"));
        controllers.add(main);
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return cache;
    }
}
