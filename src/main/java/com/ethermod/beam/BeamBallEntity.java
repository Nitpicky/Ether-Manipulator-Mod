package com.ethermod.beam;

import com.ethermod.item.ManipulatorStage;
import com.ethermod.item.OrbMode;
import com.ethermod.manipulator.Manipulator;
import com.ethermod.registry.ModEntities;
import com.ethermod.trait.Traits;
import com.ethermod.trait.WorldEffects;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SimpleWaterloggedBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * The RMB projectile. Each mode does its vanilla job on a quick tap and gains depth with charge:
 *
 * <p>ENERGY Symmetra-style orb: damage, scaled by charge; bursts at max range WATER lobbed blob:
 * places water like a bucket; charged, it splashes out fire, knocks back and waters farmland LAVA
 * heavy glob: places lava like a bucket; charged, sets fire around FROST fast shard: freezes the
 * block it hits; charged, lays a temporary ice bridge under its flight that cracks and melts after
 * 30 s IGNITE fire seed: lights what it hits like flint and steel; charged, a ring of fire GROWTH
 * tap: a puff that bonemeals what it hits; charged: a slow pollen cloud that drifts where you look,
 * growing what it passes LIGHT bouncing ball: leaves a light where it stops, and (charged) a light
 * at every bounce for 3 minutes TETHER grapple hook: sticks, then reels you in while RMB is held
 * (Vacuum is a held cone, see VacuumCone; it has no projectile)
 *
 * <p>Every projectile does its job where it stops at max range too.
 *
 * <p>Foliage in its path is cut. Only the server runs game logic; the client just moves it
 * (maxRange is 0 client-side).
 */
public class BeamBallEntity extends Projectile {

    static final EntityDataAccessor<Integer> PHASE =
            SynchedEntityData.defineId(BeamBallEntity.class, EntityDataSerializers.INT);
    static final EntityDataAccessor<Boolean> SOULFIRE =
            SynchedEntityData.defineId(BeamBallEntity.class, EntityDataSerializers.BOOLEAN);
    static final EntityDataAccessor<Integer> MODE =
            SynchedEntityData.defineId(BeamBallEntity.class, EntityDataSerializers.INT);
    static final EntityDataAccessor<Integer> STAGE =
            SynchedEntityData.defineId(BeamBallEntity.class, EntityDataSerializers.INT);
    static final EntityDataAccessor<Integer> COLOR =
            SynchedEntityData.defineId(BeamBallEntity.class, EntityDataSerializers.INT);
    static final EntityDataAccessor<Boolean> OVERCHARGED =
            SynchedEntityData.defineId(BeamBallEntity.class, EntityDataSerializers.BOOLEAN);

    /** Tether hook that has caught a block. */
    static final EntityDataAccessor<Boolean> STUCK =
            SynchedEntityData.defineId(BeamBallEntity.class, EntityDataSerializers.BOOLEAN);

    /** Orbit: circling its owner as a shield until RMB is let go. */
    /** Mass Driver: the block it carries. */
    static final EntityDataAccessor<BlockState> CARRIED =
            SynchedEntityData.defineId(BeamBallEntity.class, EntityDataSerializers.BLOCK_STATE);

    /**
     * Zipline: this hook is an anchor end of a rope; PARTNER is the other end (-1 while waiting for
     * it).
     */
    static final EntityDataAccessor<Boolean> ANCHOR =
            SynchedEntityData.defineId(BeamBallEntity.class, EntityDataSerializers.BOOLEAN);

    static final EntityDataAccessor<Integer> PARTNER =
            SynchedEntityData.defineId(BeamBallEntity.class, EntityDataSerializers.INT);
    static final EntityDataAccessor<Boolean> ORBITING =
            SynchedEntityData.defineId(BeamBallEntity.class, EntityDataSerializers.BOOLEAN);

    /** Mass Driver: held in a formation (the client then only follows the server, no physics). */
    static final EntityDataAccessor<Boolean> MASS_HELD =
            SynchedEntityData.defineId(BeamBallEntity.class, EntityDataSerializers.BOOLEAN);

    /** Mass Driver: this piece's place in the held formation (for the landing preview). */
    static final EntityDataAccessor<BlockPos> MASS_OFFSET =
            SynchedEntityData.defineId(BeamBallEntity.class, EntityDataSerializers.BLOCK_POS);

    /** Fired with left click (Detonator: the same input bursts it). */
    static final EntityDataAccessor<Boolean> FIRED_LEFT =
            SynchedEntityData.defineId(BeamBallEntity.class, EntityDataSerializers.BOOLEAN);

    /** How long Frost bridge blocks last, in ticks. */
    public static final int BRIDGE_TICKS = 200;

    /** How long a Light orb's bounce lights last, in ticks (3 minutes). */
    static final int BOUNCE_LIGHT_TICKS = 3600;

    // server-only working state for the newer modes
    int lifeTicks;
    /** Tether: game time the claw clamps on what it hit (sound, then reeling); -1 when done. */
    long hookAt = -1;
    float hookPitch;
    boolean zipPlacing;
    boolean virtualHit, airReelAfterRelease;

    /** Tether reeling: a tap reels all the way (reelFull), holding reels while held (reeling). */
    boolean reelFull, reeling, jumpWas;

    /** Playtest: the tether's reel state. */
    public String debugTether() {
        return "stuck=" + isStuck() + " hooked=" + (hooked == null ? "none" : hooked.getType().toShortString()) + " hookAt=" + hookAt
                + " reeling=" + reeling + " full=" + reelFull;
    }

    /** Gravity Shift: carrying its owner onto the caught face (game time it started, or -1). */
    long landingSince = -1;

    /** Tether: the caught face and the last right click (ropeLength: the rope while not reeling). */
    Direction hookFace;
    long lastPress = -100;
    int idleTicks;
    boolean releasedMass;


    /**
     * Mass Driver: the block is let go and flies on its own. {@code bounces}: how many times it
     * bounces off what it hits before it places itself; {@code gentle} (Steady Hand): it hurts
     * nothing.
     */
    public void releaseMass(int bounces, boolean gentle) {
        releasedMass = true;
        massBounces = bounces;
        massGentle = gentle;
        entityData.set(MASS_HELD, false);
    }

    int massBounces;
    boolean massGentle;

    int bridgeLaid;
    final java.util.Set<Long> bridgeSeen = new java.util.HashSet<>();
    final java.util.Map<Long, Long> growSeen = new java.util.HashMap<>();
    BlockPos stuckBlock;

    /** Tether: what the hook caught, if a creature or item (Recall). */
    Entity hooked;

    /** Where the hook struck what it caught, in that body's own frame (null: its middle). */
    Vec3 hookOffset;

    /** Tether with Swing: the rope's length while swinging. */
    double ropeLength;

    /** Tether: where its owner's hands were when it caught (Zipline's far end). */
    Vec3 ropeStart;

    /** Tidecaller: the owner rides along this Water orb. */
    boolean carrying;

    /** Meteor: 1 rising, 2 falling on its target. */
    int meteor;

    /** Echo: this is the echo of an earlier impact (it doesn't echo again). */
    boolean echo;

    /** Zipline anchor: ticks left. */
    int anchorLife;

    /** Hammer Throw: the hooked mob is being whirled around the owner. */
    boolean whirling;

    /** Deflector: projectiles this Gust already turned around. */
    final java.util.Set<Integer> deflected = new java.util.HashSet<>();

    /** How long a Light orb's lights last (Prism II doubles it). */
    int lightLife = BOUNCE_LIGHT_TICKS;

    // Server-only — NOT synced intentionally (defaults to 0 on client)
    float damage;
    float maxRange;
    float traveledDistance;
    boolean burningLight;

    static final float[] PHASE_SPEED = {1.0f, 1.8f, 2.8f};
    public static final float[] PHASE_RADIUS = {0.18f, 0.35f, 0.58f};

    // 6/16 blocks — hitbox smaller than the visual 8-unit model
    static final float HITBOX = 6f / 16f;
    static final EntityDimensions DIMS = EntityDimensions.scalable(HITBOX, HITBOX);

    static final String TAG_PHASE = "mm_phase";
    static final String TAG_DAMAGE = "mm_ball_dmg";
    static final String TAG_RANGE = "mm_ball_range";
    static final String TAG_BURNING = "mm_ball_burn";
    static final String TAG_TRAVEL = "mm_ball_travel";

    // =========================================================================
    // Constructors
    // =========================================================================

    /** Never the shooter's own mount (or anyone riding with them): shots fired from the saddle go past it. */
    @Override
    protected boolean canHitEntity(Entity e) {
        return super.canHitEntity(e) && !com.ethermod.beam.Mounts.own(getOwner(), e)
                && ((getMode() == OrbMode.TETHER ? com.ethermod.config.ServerConfig.tetherPlayers() : com.ethermod.config.ServerConfig.hurtPlayers())
                        || !(e instanceof net.minecraft.world.entity.player.Player) || e == getOwner());
    }

    public BeamBallEntity(EntityType<? extends BeamBallEntity> type, Level level) {
        super(type, level);
    }

    public BeamBallEntity(
            Level level, LivingEntity shooter, int phase, ManipulatorStage stage, OrbMode mode) {
        super(ModEntities.BEAM_BALL.get(), level);
        setOwner(shooter);

        int p = Math.max(0, Math.min(2, phase));
        entityData.set(PHASE, p);
        entityData.set(MODE, mode.ordinal());
        entityData.set(STAGE, stage.ordinal());

        this.damage = stage.ballDamage(p) * com.ethermod.config.ServerConfig.shotDamage();
        this.maxRange = mode.gravity() > 0 ? 96f : stage.ballRange();
        this.burningLight = false;
        this.traveledDistance = 0f;

        // Server-side spawn: approximate barrel tip offset from eye.
        // The GeckoLib muzzle_anchor bone position is client-only, so we use
        // a close approximation here. The ball moves away immediately so
        // sub-block precision is not needed for gameplay.
        Vec3 eye = shooter.getEyePosition();
        Vec3 look = shooter.getLookAngle();
        Vec3 up =
                com.ethermod.trait.MovementPhysics.rotate(
                        new Vec3(0, 1, 0),
                        com.ethermod.trait.MovementPhysics.down(
                                (net.minecraft.world.entity.player.Player) shooter));
        Vec3 right = look.cross(up).normalize();
        Vec3 camUp = right.cross(look).normalize(); // true camera up, accounts for pitch
        Vec3 spawn = eye.add(right.scale(0.45)).add(camUp.scale(-0.15)).add(look.scale(0.70));
        setPos(spawn.x, spawn.y, spawn.z);

        shoot(look.x, look.y, look.z, mode.speed(p), 0f);
    }

    /** Adds trait damage on top of the stage damage (server-side, before spawning). */
    public void addBonusDamage(float bonus) {
        this.damage += bonus;
    }

    /** A snapshot of the firing Manipulator, for trait checks. Server-only, not saved. */
    ItemStack traits = ItemStack.EMPTY;

    int bouncesLeft;
    int piercesLeft;
    java.util.UUID seekerTarget;
    final java.util.Set<Integer> pierced = new java.util.HashSet<>();

    public void scaleDamage(float amount) {
        damage *= amount;
    }

    float lastPathMark;

    /** Applies the Orb line's launch-time traits. Call before spawning. */
    public void configure(ItemStack manipulator) {
        entityData.set(COLOR, getMode().color);
        this.traits = manipulator.copy();
        entityData.set(SOULFIRE, Traits.has(manipulator, "pyrotechnic.soulwake"));
        zipPlacing =
                getMode() == OrbMode.TETHER
                        && has("gravitic.zipline")
                        && getOwner() != null
                        && getOwner().isShiftKeyDown();
        if (Traits.hasCommon(traits, Traits.KINETIC, 1)) maxRange += 16;
        int overcharge = Traits.rank(traits, "kinetic.overcharge");
        if (getPhase() == 2 && overcharge > 0) {
            damage *= 1.25f + 0.25f * overcharge; // x1.5, x1.75, x2
            entityData.set(OVERCHARGED, true);
        }
        if (getMode() == OrbMode.GUST) {
            maxRange = 32;
            // Cyclone: a charged Gust becomes a slow whirlwind
            if (getPhase() == 2 && Traits.has(traits, "pneumatic.cyclone"))
                setDeltaMovement(getDeltaMovement().normalize().scale(0.22));
        }
        if (getMode() == OrbMode.MASS) maxRange = 64;
        // Bank Shot: every mode bounces (not the hook, nor a drifting pollen cloud)
        int bank = Traits.rank(traits, "kinetic.bank_shot");
        if (getMode() != OrbMode.TETHER && !isDrifting())
            bouncesLeft = bank > 0 ? getPhase() + 1 : 0;
        piercesLeft = has("kinetic.piercing") ? getPhase() : 0;
        if (has("kinetic.marksman")
                && getOwner() != null
                && getOwner().getPersistentData().getInt("mm_charge_still") >= 10)
            setDeltaMovement(getDeltaMovement().scale(1.6));
        if (has("kinetic.seeker") && getOwner() instanceof ServerPlayer p) {
            var look = p.getLookAngle();
            var eye = p.getEyePosition();
            ((ServerLevel) level())
                            .getEntitiesOfClass(
                                    LivingEntity.class,
                                    p.getBoundingBox()
                                            .expandTowards(look.scale(maxRange))
                                            .inflate(4),
                                    e ->
                                            !com.ethermod.beam.Mounts.own(p, e)
                                                    && e.isAlive()
                                                    && !com.ethermod.trait.RedesignEffects.ally(
                                                            p, e)
                                                    && p.hasLineOfSight(e)
                                                    && e.getBoundingBox()
                                                                    .getCenter()
                                                                    .subtract(eye)
                                                                    .normalize()
                                                                    .dot(look)
                                                            > .978)
                            .stream()
                            .min(
                                    java.util.Comparator.comparingDouble(
                                            e ->
                                                    1
                                                            - e.getBoundingBox()
                                                                    .getCenter()
                                                                    .subtract(eye)
                                                                    .normalize()
                                                                    .dot(look)))
                            .ifPresent(e -> seekerTarget = e.getUUID());
        }
        // Light: a Moira-style ball that bounces 2, then 4 times when charged; Prism adds 2, 4, 6;
        // Bank Shot one more than other modes
        if (getMode() == OrbMode.LIGHT) {
            int prism = Traits.rank(traits, "photonic.prism");
            bouncesLeft =
                    (getPhase() == 0 ? 0 : getPhase() == 1 ? 2 : 4)
                            + 2 * prism
                            + (bank > 0 ? bank + 1 : 0);
            if (prism >= 2) lightLife = BOUNCE_LIGHT_TICKS * 2;
        }
    }

    /** Orbit: start circling the owner. */
    public void startOrbit() {
        entityData.set(ORBITING, true);
        setDeltaMovement(Vec3.ZERO);
        lifeTicks = 0;
    }

    public boolean isOrbiting() {
        return entityData.get(ORBITING);
    }

    /** Mass Driver: the carried block (air if none). */
    /** A lifted container's contents (server config mass_driver_lifts_containers), or null. */
    public net.minecraft.nbt.CompoundTag carriedBe;

    public BlockState getCarried() {
        return entityData.get(CARRIED);
    }

    public void setCarried(BlockState state) {
        entityData.set(CARRIED, state);
    }

    /** Zipline: an anchored rope end. */
    public boolean isAnchor() {
        return entityData.get(ANCHOR);
    }

    public int getPartnerId() {
        return entityData.get(PARTNER);
    }

    /** Zipline: this stuck hook stays as a rope end, waiting for its partner (or linked to it). */
    public void makeAnchor(int life, int partnerId) {
        entityData.set(ANCHOR, true);
        entityData.set(PARTNER, partnerId);
        entityData.set(STUCK, true);
        setDeltaMovement(Vec3.ZERO);
        hooked = null;
        anchorLife = life;
    }

    public void linkAnchor(int partnerId, int life) {
        entityData.set(PARTNER, partnerId);
        anchorLife = life;
    }

    public boolean isStuckOnBlock() {
        return isStuck() && hooked == null && stuckBlock != null;
    }

    @Override
    public void onClientRemoval() {
        super.onClientRemoval();
        if (level().isClientSide && getMode() == OrbMode.TETHER && isStuck() && !isAnchor()) {
            com.ethermod.client.ClientMobility.onHookGone(this);
        }
    }

    /** Zipline anchors melt away when their time is up or their partner is gone. */
    void anchorTick() {
        if (--anchorLife <= 0
                || (stuckBlock != null && level().getBlockState(stuckBlock).isAir())) {
            discard();
            return;
        }
        int partner = getPartnerId();
        if (partner >= 0
                && !(level().getEntity(partner) instanceof BeamBallEntity other && other.isAlive()))
            discard();
    }

    /** Tidecaller: the owner rides along. */
    public void carryOwner() {
        carrying = true;
    }

    /** Meteor: rise, then fall on the block the owner aims at. */
    public void makeMeteor() {
        meteor = 1;
        maxRange = 256;
    }

    /** Rotates the flight direction around the vertical axis (Split). */
    public void turn(float degrees) {
        Vec3 v = getDeltaMovement();
        double r = Math.toRadians(degrees), c = Math.cos(r), sn = Math.sin(r);
        setDeltaMovement(v.x * c - v.z * sn, v.y, v.x * sn + v.z * c);
    }

    boolean has(String trait) {
        return !traits.isEmpty() && Traits.has(traits, trait);
    }

    /** The trait's rank on the Manipulator that fired this orb (0 if not picked). */
    int rank(String trait) {
        return traits.isEmpty() ? 0 : Traits.rank(traits, trait);
    }

    // =========================================================================
    // Entity definition
    // =========================================================================

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(SOULFIRE, false);
        builder.define(PHASE, 0);
        builder.define(MODE, 0);
        builder.define(STAGE, 0);
        builder.define(COLOR, com.ethermod.item.ManipulatorColors.DEFAULT_BEAM);
        builder.define(OVERCHARGED, false);
        builder.define(STUCK, false);
        builder.define(ORBITING, false);
        builder.define(CARRIED, Blocks.AIR.defaultBlockState());
        builder.define(ANCHOR, false);
        builder.define(PARTNER, -1);
        builder.define(FIRED_LEFT, false);
        builder.define(MASS_HELD, false);
        builder.define(MASS_OFFSET, BlockPos.ZERO);
    }

    public void setMassHeld(boolean held, BlockPos offset) {
        entityData.set(MASS_HELD, held);
        entityData.set(MASS_OFFSET, offset);
    }

    public boolean isMassHeld() {
        return entityData.get(MASS_HELD);
    }

    public BlockPos massOffset() {
        return entityData.get(MASS_OFFSET);
    }

    public boolean isSoulFire() {
        return entityData.get(SOULFIRE);
    }

    public int getPhase() {
        return entityData.get(PHASE);
    }

    public boolean isOvercharged() {
        return entityData.get(OVERCHARGED);
    }

    public boolean isStuck() {
        return entityData.get(STUCK);
    }

    /** A charged Growth orb: a slow pollen cloud that follows your aim. */
    public boolean isDrifting() {
        return getMode() == OrbMode.GROWTH && getPhase() >= 1;
    }

    public OrbMode getMode() {
        return OrbMode.byId(entityData.get(MODE));
    }

    /** The owner's beam colour (RGB), used for Energy orbs. */
    public int getBeamColor() {
        return entityData.get(COLOR);
    }

    public ManipulatorStage getStage() {
        ManipulatorStage[] v = ManipulatorStage.values();
        return v[Math.max(0, Math.min(v.length - 1, entityData.get(STAGE)))];
    }

    /** Smaller hitbox than the visual model (6 vs 8 model units). */
    @Override
    public EntityDimensions getDimensions(Pose pose) {
        return DIMS;
    }

    // =========================================================================
    // Tick
    // =========================================================================

    @Override
    public void tick() {
        OrbMode mode = getMode();
        // CLIENT: only advance position (with the mode's gravity), no game logic.
        // (maxRange = 0 on client, so a range check would instantly discard)
        if (level().isClientSide) {
            if (isOrbiting()) {
                orbitPosition();
                return;
            }
            if (isStuck()) {
                if (mode == OrbMode.TETHER && !isAnchor())
                    com.ethermod.client.ClientMobility.ropeTick(this);
                return;
            }
            // a held Mass Driver block goes exactly where the server puts it: moving it on here
            // too made it run ahead and snap back on every wall
            if (mode == OrbMode.MASS && isMassHeld()) return;
            if (mode.gravity() > 0 && !isDrifting())
                setDeltaMovement(getDeltaMovement().add(0, -gravityNow(), 0));
            // thrown blocks fall as they do on the server
            if (mode == OrbMode.MASS) setDeltaMovement(getDeltaMovement().add(0, -.08, 0));
            Vec3 pos = position();
            Vec3 mov = getDeltaMovement();
            setPos(pos.x + mov.x, pos.y + mov.y, pos.z + mov.z);
            return;
        }

        if (mode == OrbMode.MASS && !level().isClientSide) {
            if (MassDriver.heldTick(this)) return;
            releasedMass = true;
        }
        // SERVER: full physics
        if ((mode == OrbMode.LAVA || mode == OrbMode.IGNITE) && getOwner() instanceof ServerPlayer pyro)
            pyro.getPersistentData()
                    .putLong(com.ethermod.advancement.SecretAdvancements.PYRO_TAG, level().getGameTime() + 1200);
        // an Energy orb bats a ghast's fireball back, as a punch would (Ping Pong)
        if (mode == OrbMode.ENERGY && getOwner() instanceof ServerPlayer batter)
            for (var fb :
                    level().getEntitiesOfClass(
                            net.minecraft.world.entity.projectile.LargeFireball.class,
                            getBoundingBox().inflate(.6),
                            f -> f.getOwner() instanceof net.minecraft.world.entity.monster.Ghast)) {
                fb.hurt(batter.damageSources().playerAttack(batter), 1f);
                com.ethermod.advancement.ManipulatorTrigger.fire(batter, "ping_pong");
                impactFx(position());
                discard();
                return;
            }
        super.tick();

        if (isOrbiting()) {
            orbitTick();
            return;
        }
        if (isAnchor()) {
            anchorTick();
            return;
        }
        if (mode == OrbMode.TETHER && !OrbTether.tetherTick(this)) return;
        if (mode == OrbMode.WATER || mode == OrbMode.FROST)
            com.ethermod.beam.ProjectileEffects.waterPath(this, traits);
        if (mode == OrbMode.LIGHT) com.ethermod.beam.ProjectileEffects.lightFlight(this, traits);
        if (mode == OrbMode.GUST && OrbGust.gustTick(this)) return;
        if (isDrifting()) {
            OrbNature.driftTick(this);
            return;
        }
        lifeTicks++;
        if (mode.gravity() > 0) setDeltaMovement(getDeltaMovement().add(0, -gravityNow(), 0));
        if (mode == OrbMode.WATER || mode == OrbMode.FROST) OrbFluids.steerWater(this);
        if (meteor > 0) meteorTick();
        if (mode == OrbMode.MASS) {
            setDeltaMovement(getDeltaMovement().add(0, -.08, 0));
        }

        Vec3 movement = getDeltaMovement();
        // Slipstream: no range limit while flying through water
        boolean slip =
                (isInWater() || WorldEffects.isAirPocket((ServerLevel) level(), blockPosition()))
                        && (mode == OrbMode.WATER || mode == OrbMode.FROST)
                        && !traits.isEmpty()
                        && Traits.hasCommon(traits, Traits.HYDRAULIC, 1);
        if (!slip) traveledDistance += movement.length(); // Slipstream: no range spent underwater
        if (traveledDistance > maxRange || lifeTicks > 600) {
            expire();
            return;
        }
        if (carrying) carryTick();

        // fire and wind tear through foliage; every other shot passes through it
        if ((mode == OrbMode.LAVA || mode == OrbMode.IGNITE) && com.ethermod.config.ServerConfig.fireAndLava()
                || mode == OrbMode.GUST && com.ethermod.config.ServerConfig.gustCutsFoliage())
            FoliageCut.sweep(
                    (ServerLevel) level(),
                    getOwner() instanceof ServerPlayer sp ? sp : null,
                    position(),
                    position().add(movement),
                    mode != OrbMode.GUST);

        pathTraits();
        OrbFrost.frostBridge(this, position(), movement);
        movement = getDeltaMovement(); // Seeker may have steered

        // Hit detection. Grass and flowers have no collision; leaves do, but shots pass through
        // them too (nothing in the way is broken), so a leaf hit is looked past
        java.util.function.Predicate<Entity> canHit =
                e ->
                        canHitEntity(e) && !pierced.contains(e.getId());
        HitResult hit = ProjectileUtil.getHitResultOnMoveVector(this, canHit);
        if (leaves(hit) && movement.lengthSqr() > 1e-6) {
            Vec3 end = position().add(movement), dir = movement.normalize();
            HitResult past = hit;
            for (int i = 0; i < 8 && leaves(past); i++)
                past =
                        level().clip(
                                        new net.minecraft.world.level.ClipContext(
                                                past.getLocation().add(dir.scale(.05)),
                                                end,
                                                net.minecraft.world.level.ClipContext.Block.COLLIDER,
                                                net.minecraft.world.level.ClipContext.Fluid.NONE,
                                                this));
            if (leaves(past)) past = net.minecraft.world.phys.BlockHitResult.miss(end, Direction.UP, BlockPos.containing(end));
            Vec3 stop = past.getType() == HitResult.Type.MISS ? end : past.getLocation();
            var mob =
                    ProjectileUtil.getEntityHitResult(
                            level(), this, position(), stop, getBoundingBox().expandTowards(movement).inflate(1), canHit);
            hit = mob != null ? mob : past;
        }
        if (hit.getType() != HitResult.Type.MISS) {
            onHit(hit);
            return;
        }

        Vec3 pos = position();
        setPos(pos.x + movement.x, pos.y + movement.y, pos.z + movement.z);
    }

    private boolean leaves(HitResult h) {
        return h instanceof net.minecraft.world.phys.BlockHitResult bh
                && h.getType() == HitResult.Type.BLOCK
                && level().getBlockState(bh.getBlockPos()).is(net.minecraft.tags.BlockTags.LEAVES);
    }

    /** Gravity this tick: Slipstream floats liquid orbs in water, Tidecaller rides flatter. */
    float gravityNow() {
        OrbMode mode = getMode();
        if (meteor == 2) return 0.12f;
        if (mode == OrbMode.WATER
                && isInWater()
                && !traits.isEmpty()
                && Traits.hasCommon(traits, Traits.HYDRAULIC, 1)) return 0f;
        if (carrying) return mode.gravity() * 0.35f;
        return mode.gravity();
    }

    /** Tidecaller: pulls the owner along just behind the orb. */
    void carryTick() {
        if (!(getOwner() instanceof ServerPlayer sp) || !sp.isAlive() || sp.level() != level()) {
            carrying = false;
            return;
        }
        Vec3 v = getDeltaMovement();
        Vec3 spot =
                position()
                        .subtract(v.lengthSqr() > 1e-4 ? v.normalize().scale(2.2) : Vec3.ZERO)
                        .subtract(0, sp.getBbHeight() * 0.5, 0);
        Vec3 want = spot.subtract(sp.position());
        Vec3 nv = want.scale(0.5);
        if (nv.length() > 2.2) nv = nv.normalize().scale(2.2);
        sp.setDeltaMovement(nv);
        sp.hurtMarked = true;
        // the trident's riptide spin, kept going for as long as the shot carries you
        if (!sp.isAutoSpinAttack() || tickCount % 4 == 0) sp.startAutoSpinAttack(6, 6f, traits);
        sp.resetFallDistance();
        com.ethermod.trait.MobilityEffects.markSoftLanding(sp);
    }

    /** Meteor: once the glob slows at the top, it drops onto where its owner aims. */
    void meteorTick() {
        if (meteor != 1 || (getDeltaMovement().y > 0.15 && lifeTicks < 40)) return;
        meteor = 2;
        if (getOwner() instanceof ServerPlayer sp && sp.level() == level()) {
            Vec3 eye = sp.getEyePosition();
            Vec3 aim =
                    level().clip(
                                    new net.minecraft.world.level.ClipContext(
                                            eye,
                                            eye.add(sp.getLookAngle().scale(96)),
                                            net.minecraft.world.level.ClipContext.Block.COLLIDER,
                                            net.minecraft.world.level.ClipContext.Fluid.NONE,
                                            sp))
                            .getLocation();
            double y = Math.max(getY(), aim.y + 24);
            setPos(aim.x, y, aim.z);
        }
        setDeltaMovement(0, -1.2, 0);
        ((ServerLevel) level())
                .playSound(
                        null,
                        getX(),
                        getY(),
                        getZ(),
                        com.ethermod.sound.ModSounds.WORLD_BLAZE_SHOOT,
                        SoundSource.PLAYERS,
                        1f,
                        0.6f);
    }

    /** Orbit: where the orb sits on its circle around the owner (both sides run this). */
    void orbitPosition() {
        Entity o = getOwner();
        if (o == null) return;
        var peers =
                level().getEntitiesOfClass(
                                BeamBallEntity.class,
                                o.getBoundingBox().inflate(4),
                                e -> e.getOwner() == o && e.isOrbiting());
        peers.sort(java.util.Comparator.comparingInt(Entity::getId));
        int index = Math.max(0, peers.indexOf(this));
        double a =
                level().getGameTime() * 0.22 + index * (Math.PI * 2.0 / Math.max(1, peers.size()));
        Vec3 c = o.position().add(0, o.getBbHeight() * 0.55, 0);
        setPos(c.x + Math.cos(a) * 1.6, c.y, c.z + Math.sin(a) * 1.6);
    }

    /** Orbit: circle, hurt what it touches, launch when RMB is let go (or after 30 s). */
    void orbitTick() {
        if (!(getOwner() instanceof ServerPlayer sp) || !sp.isAlive() || sp.level() != level()) {
            discard();
            return;
        }
        orbitPosition();
        lifeTicks++;
        ServerLevel sl = (ServerLevel) level();
        if (lifeTicks > 600 || !Traits.has(Manipulator.stack(sp), "kinetic.orbit")) {
            discard();
            return;
        }
        HitResult block =
                sl.clip(
                        new net.minecraft.world.level.ClipContext(
                                sp.getEyePosition(),
                                position(),
                                net.minecraft.world.level.ClipContext.Block.COLLIDER,
                                net.minecraft.world.level.ClipContext.Fluid.NONE,
                                sp));
        if (block instanceof BlockHitResult bh && block.getType() == HitResult.Type.BLOCK) {
            onHitBlock(bh);
            discard();
            return;
        }
        for (var e :
                sl.getEntitiesOfClass(
                        LivingEntity.class,
                        getBoundingBox().inflate(0.35),
                        e ->
                                !com.ethermod.beam.Mounts.own(sp, e)
                                        && e.isAlive()
                                        && !com.ethermod.trait.RedesignEffects.ally(sp, e))) {
            onHitEntity(new EntityHitResult(e));
            break;
        }
    }

    /**
     * Max range reached without hitting anything: the projectile still does its job where it is,
     * instead of just vanishing.
     */
    void expire() {
        if (getMode() == OrbMode.TETHER && has("gravitic.skyhook") && !zipPlacing) {
            OrbTether.hookAir(this);
            return;
        }

        ServerLevel sl = (ServerLevel) level();
        Vec3 at = position();
        BlockPos here = blockPosition();
        switch (getMode()) {
            case ENERGY -> {
                double r = PHASE_RADIUS[getPhase()] * 2 + 1;
                for (LivingEntity e :
                        sl.getEntitiesOfClass(
                                LivingEntity.class,
                                getBoundingBox().inflate(r),
                                e -> e != getOwner() && e.isAlive())) {
                    e.hurt(
                            damageSources()
                                    .thrown(
                                            this,
                                            getOwner() instanceof LivingEntity le ? le : this),
                            damage * 0.6f);
                }
            }
            case WATER -> {
                if (!OrbFluids.placeFluid(this, here, Direction.UP, Fluids.WATER)) refundTank();
                ProjectileEffects.waterImpact(this, traits, at);
            }
            case LAVA -> {
                if (!OrbFluids.placeFluid(this, here, Direction.UP, Fluids.LAVA)) refundTank();
                else OrbFluids.lavaPlaced(this, here);
            }
            case IGNITE -> OrbNature.ignite(this, here.below(), Direction.UP);
            case FROST -> {
                if (mayBuild(here))
                    WorldEffects.tempBlock(
                            sl,
                            here,
                            Blocks.PACKED_ICE.defaultBlockState(),
                            OrbFrost.bridgeTicks(this),
                            OrbFrost.permafrost(this));
            }
            case LIGHT -> {
                OrbLight.lightImpact(this, here, null);
                OrbLight.lightExtras(this, at);
            }
            case GUST -> OrbGust.gustBurst(this, at);
            case MASS -> OrbMass.dropCarried(this, at);
            case GROWTH -> OrbNature.grow(this, here);
            default -> {}
        }
        impactFx(at);
        discard();
    }

    // =========================================================================
    // Flight traits (Orb line: paths and Seeker)
    // =========================================================================

    void pathTraits() {
        ServerLevel sl = (ServerLevel) level();
        BlockPos here = blockPosition();

        if (seekerTarget != null
                && getMode() != OrbMode.TETHER
                && getMode() != OrbMode.MASS
                && sl.getEntity(seekerTarget) instanceof LivingEntity target
                && target.isAlive()) {
            Vec3 v = getDeltaMovement();
            Vec3 wish = target.getBoundingBox().getCenter().subtract(position()).normalize();
            setDeltaMovement(
                    v.normalize().scale(.85).add(wish.scale(.15)).normalize().scale(v.length()));
        }
    }

    // =========================================================================
    // Hit resolution
    // =========================================================================

    @Override
    protected void onHitEntity(EntityHitResult result) {
        if (level().isClientSide) return;
        ServerLevel sl = (ServerLevel) level();
        Entity target = result.getEntity();
        Entity owner = getOwner();
        if (getMode() == OrbMode.WATER)
            ProjectileEffects.waterImpact(this, traits, result.getLocation());
        if (getMode() == OrbMode.WATER
                && target instanceof net.minecraft.world.entity.monster.EnderMan
                && owner instanceof ServerPlayer rain)
            com.ethermod.advancement.ManipulatorTrigger.fire(rain, "rain_check");
        int ph = getPhase();
        if (owner instanceof ServerPlayer sp
                && target instanceof LivingEntity le
                && (getMode() == OrbMode.LAVA || getMode() == OrbMode.IGNITE))
            com.ethermod.trait.RedesignEffects.burn(le, sp, 4 + ph * 2, ph);
        switch (getMode()) {
            case ENERGY -> {
                float dmg = damage;
                if (BeamCastHelper.isIonized(target)) dmg *= BeamCastHelper.ionizeBonus(target);
                target.hurt(
                        damageSources().thrown(this, owner instanceof LivingEntity le ? le : this),
                        dmg);
                if (burningLight && target instanceof LivingEntity le) le.igniteForSeconds(5);
            }
            case VACUUM -> {}
            case WATER -> {
                target.clearFire();
                int pressure = 0; // (the old Pressure bonus; Pressure now keeps placed water)
                double force = pressure > 0 ? 1.0 + 0.6 * ph : 0.4 + 0.3 * ph;
                if (pressure > 0)
                    target.hurt(
                            damageSources()
                                    .thrown(this, owner instanceof LivingEntity o ? o : this),
                            2 + 2 * ph);
                Vec3 push = getDeltaMovement().normalize().scale(force);
                target.setDeltaMovement(
                        target.getDeltaMovement().add(push.x, 0.15 + force * 0.1, push.z));
                target.hurtMarked = true;
                if (pressure > 0) OrbFluids.extinguish(this, target.blockPosition(), 1 + pressure);
                if (has("hydraulic.quench")) OrbFluids.extinguish(this, target.blockPosition(), 2);
                if (!OrbFluids.placeFluid(this, target.blockPosition(), null, Fluids.WATER))
                    refundTank();
            }
            case LAVA -> {
                target.igniteForSeconds(5);
                if (!OrbFluids.placeFluid(this, target.blockPosition(), null, Fluids.LAVA))
                    refundTank();
                else OrbFluids.lavaPlaced(this, target.blockPosition());
                OrbFluids.kiln(this, result.getLocation());
            }
            case FROST -> {
                if (target instanceof LivingEntity le) {
                    le.addEffect(
                            new net.minecraft.world.effect.MobEffectInstance(
                                    net.minecraft.world.effect.MobEffects.MOVEMENT_SLOWDOWN,
                                    100 + 60 * ph,
                                    1 + ph));
                    le.setTicksFrozen(
                            Math.max(le.getTicksFrozen(), le.getTicksRequiredToFreeze() + 60));
                    le.hurt(damageSources().freeze(), 1f + ph);
                }
            }
            case IGNITE -> {
                int inc = rank("pyrotechnic.incendiary");
                target.igniteForSeconds(inc >= 2 ? 12 : inc == 1 ? 8 : 4 + 2 * ph);
                OrbFluids.kiln(this, result.getLocation());
            }
            case GROWTH -> {
                if (target instanceof LivingEntity victim
                        && owner instanceof ServerPlayer sp
                        && com.ethermod.trait.RedesignEffects.enemy(sp, victim)
                        && (victim instanceof net.minecraft.world.entity.monster.Enemy
                                || victim instanceof net.minecraft.world.entity.Mob m
                                        && m.getTarget() == sp)) {
                    int potency = 0;
                    victim.addEffect(
                            new net.minecraft.world.effect.MobEffectInstance(
                                    net.minecraft.world.effect.MobEffects.POISON, 100, potency));
                    victim.addEffect(
                            new net.minecraft.world.effect.MobEffectInstance(
                                    net.minecraft.world.effect.MobEffects.MOVEMENT_SLOWDOWN,
                                    100,
                                    potency));
                }
                if (target instanceof net.minecraft.world.entity.AgeableMob baby && baby.isBaby()) {
                    baby.ageUp(
                            net.minecraft.world.entity.AgeableMob.getSpeedUpSecondsWhenFeeding(
                                    -baby.getAge()),
                            true);
                }
            }
            case LIGHT -> {
                if (target instanceof LivingEntity le) {

                    // Sunfire: Light burns the undead
                    int sun = rank("photonic.sunfire");
                    if (sun > 0 && le.getType().is(net.minecraft.tags.EntityTypeTags.UNDEAD)) {
                        le.hurt(
                                damageSources()
                                        .thrown(this, owner instanceof LivingEntity o ? o : this),
                                3f + 1.5f * sun + 2f * ph);
                        le.igniteForSeconds(5);
                    }
                }
                OrbLight.lightImpact(this, target.blockPosition(), null);
                OrbLight.lightExtras(this, result.getLocation());
            }
            case GUST -> OrbGust.gustBurst(this, result.getLocation());
            case MASS -> {
                // the block bounces back off the creature and flies on, then lands as usual
                OrbMass.massHitEntity(this, target, result.getLocation());
                return;
            }
            case TETHER -> {
                // the hook catches the creature and holds; right click reels it in (tetherTick)
                if (owner instanceof ServerPlayer sp) OrbTether.catchEntity(this, target, sp);
                return;
            }
            default -> {}
        }
        impactFx(result.getLocation());
        afterImpact(result.getLocation());
        if (piercesLeft-- > 0 && !isOrbiting()) {
            pierced.add(target.getId());
            setPos(result.getLocation().add(getDeltaMovement().normalize().scale(0.5)));
            return;
        }
        discard();
    }

    @Override
    protected void onHitBlock(BlockHitResult result) {
        if (level().isClientSide) {
            discard();
            return;
        }
        ServerLevel sl = (ServerLevel) level();
        BlockPos hitPos = result.getBlockPos();
        Direction face = result.getDirection();
        BlockPos front = hitPos.relative(face);
        int ph = getPhase();
        if (getMode() == OrbMode.WATER)
            ProjectileEffects.waterImpact(this, traits, result.getLocation());
        int payload = ph == 2 ? rank("kinetic.payload") : 0; // Payload: 3x3, then 5x5

        // Tether: the hook catches the block and holds (see tetherTick)
        if (getMode() == OrbMode.TETHER) {
            // Blink and Gravity Shift wait for a double tap of right click (OrbTether.press)
            hookFace = face;
            Vec3 at = result.getLocation();
            setPos(at.x, at.y, at.z);
            setDeltaMovement(Vec3.ZERO);
            entityData.set(STUCK, true);
            stuckBlock = virtualHit ? null : hitPos;
            if (getOwner() instanceof ServerPlayer sp) ropeStart = sp.getEyePosition();
            // the sound and the reel wait for the claw to clamp (OrbTether.HOOK_DELAY)
            hookAt = sl.getGameTime() + OrbTether.HOOK_DELAY;
            hookPitch = 1.3f;
            if (getOwner() instanceof ServerPlayer sp) {
                if (has("gravitic.singularity")) WorldEffects.gravityWell(sl, at, 60, sp);
                if (zipPlacing) {
                    com.ethermod.trait.Ziplines.onHookStuck(sp, this);
                    if (!isAnchor()) com.ethermod.trait.Ziplines.pin(sp, this);
                }
            }
            return;
        }

        // Bank Shot (and the Light orb's own bounces)
        if (bouncesLeft > 0 && meteor != 2) {
            bouncesLeft--;
            if (getMode() == OrbMode.LIGHT) traveledDistance = 0f;
            if (getMode() == OrbMode.LIGHT && mayBuild(front) && sl.getBlockState(front).isAir()) {
                WorldEffects.tempLight(sl, front, 13, lightLife);
                if (getOwner() instanceof ServerPlayer sp)
                    com.ethermod.trait.BeaconNet.add(sp, sl, front, lightLife);
                sl.sendParticles(
                        ParticleTypes.END_ROD,
                        front.getX() + 0.5,
                        front.getY() + 0.5,
                        front.getZ() + 0.5,
                        6,
                        0.2,
                        0.2,
                        0.2,
                        0.05);
            }
            Vec3 v = getDeltaMovement();
            Vec3 n = new Vec3(face.getStepX(), face.getStepY(), face.getStepZ());
            Vec3 r = v.subtract(n.scale(2 * v.dot(n)));
            setDeltaMovement(r);
            if (getMode() == OrbMode.LIGHT)
                ProjectileEffects.lightBounce(this, traits, result.getLocation().add(n.scale(0.3)));
            Vec3 at = result.getLocation().add(n.scale(0.2));
            setPos(at.x, at.y, at.z);
            sl.playSound(
                    null,
                    at.x,
                    at.y,
                    at.z,
                    com.ethermod.sound.ModSounds.WORLD_AMETHYST_BLOCK_HIT,
                    SoundSource.PLAYERS,
                    0.8f,
                    1.6f);
            return;
        }

        switch (getMode()) {
            case ENERGY, VACUUM -> {}
            case WATER -> {
                carrying = false;
                // Cauldrons and other mods' tanks take the bucket
                if (OrbFluids.fillContainer(this, hitPos, face, Fluids.WATER)) break;
                BlockState hitState = sl.getBlockState(hitPos);
                boolean placed =
                        (hitState.getBlock() instanceof SimpleWaterloggedBlock
                                        && OrbFluids.placeFluid(this, hitPos, face, Fluids.WATER))
                                || OrbFluids.placeFluid(this, front, face, Fluids.WATER);
                if (!placed) refundTank();
                else if (payload > 0) OrbFluids.spill(this, front, payload, Fluids.WATER);
                if (false && placed)
                    for (int d = 1; d <= 3 + ph * 2; d++) {
                        final int delay = d;
                        WorldEffects.schedule(
                                sl,
                                d,
                                () -> {
                                    for (var pos :
                                            BlockPos.betweenClosed(
                                                    front.offset(-delay, 0, -delay),
                                                    front.offset(delay, 0, delay)))
                                        if (sl.hasChunkAt(pos)
                                                && sl.getFluidState(pos).is(FluidTags.WATER))
                                            sl.scheduleTick(pos, Fluids.WATER, 1);
                                });
                    }
                // Quench: lava it lands on turns to obsidian or cobblestone, II in a 3x3
                if (has("hydraulic.quench"))
                    OrbFluids.quenchLava(this, front, rank("hydraulic.quench") >= 2 ? 1 : 0);
                OrbFluids.extinguish(
                        this,
                        front,
                        Math.max(
                                ph,
                                1));
                if (ph >= 1) {
                    // a splash: shove creatures back from the impact
                    for (LivingEntity e :
                            sl.getEntitiesOfClass(
                                    LivingEntity.class,
                                    new AABB(front).inflate(1 + ph),
                                    e -> e != getOwner())) {
                        Vec3 away = e.position().subtract(Vec3.atCenterOf(front));
                        away = away.lengthSqr() < 1e-4 ? new Vec3(0, 1, 0) : away.normalize();
                        e.setDeltaMovement(
                                e.getDeltaMovement()
                                        .add(away.x * 0.6 * ph, 0.25, away.z * 0.6 * ph));
                        e.hurtMarked = true;
                        e.clearFire();
                    }
                }
                if (ph == 2) {
                    // and soak the soil: farmland 5x5 becomes fully watered
                    for (BlockPos bp :
                            BlockPos.betweenClosed(
                                    front.offset(-2, -2, -2), front.offset(2, 1, 2))) {
                        BlockState fs = sl.getBlockState(bp);
                        if (fs.getBlock() instanceof net.minecraft.world.level.block.FarmBlock
                                && mayBuild(bp)) {
                            sl.setBlock(
                                    bp,
                                    fs.setValue(
                                            net.minecraft.world.level.block.FarmBlock.MOISTURE, 7),
                                    Block.UPDATE_CLIENTS);
                        }
                    }
                    sl.sendParticles(
                            ParticleTypes.SPLASH,
                            front.getX() + 0.5,
                            front.getY() + 0.2,
                            front.getZ() + 0.5,
                            40,
                            1.5,
                            0.2,
                            1.5,
                            0.1);
                    // Wave: the water rolls on along the ground
                    if (has("hydraulic.wave")) {
                        Vec3 v = getDeltaMovement();
                        Direction dir = Direction.getNearest(v.x, 0, v.z);
                        if (dir.getAxis().isHorizontal())
                            WorldEffects.wave(sl, front.relative(dir), dir, getOwner(), 14);
                    }
                }
            }
            case LAVA -> {
                if (OrbFluids.forgeHeart(this, hitPos)) break;
                if (OrbFluids.fillContainer(this, hitPos, face, Fluids.LAVA)) break;
                if (!OrbFluids.placeFluid(this, front, face, Fluids.LAVA)) refundTank();
                else {
                    OrbFluids.lavaPlaced(this, front);
                    if (payload > 0) OrbFluids.spill(this, front, payload, Fluids.LAVA);
                    // charged: fire splashes out around the pool
                    if (ph >= 1) WorldEffects.fireRing(sl, front, getOwner(), ph);
                }
                if (meteor == 2) meteorImpact(result.getLocation());
                OrbFluids.kiln(this, result.getLocation());
            }
            case FROST -> {
                // Tap: freeze the water block hit, or the air in front of the face, into ice
                if (sl.getBlockState(hitPos).is(Blocks.WATER) && mayBuild(hitPos)) {
                    WorldEffects.tempBlock(
                            sl,
                            hitPos,
                            Blocks.PACKED_ICE.defaultBlockState(),
                            OrbFrost.bridgeTicks(this),
                            OrbFrost.permafrost(this));
                } else if (mayBuild(front)) {
                    WorldEffects.tempBlock(
                            sl,
                            front,
                            Blocks.PACKED_ICE.defaultBlockState(),
                            OrbFrost.bridgeTicks(this),
                            OrbFrost.permafrost(this));
                }
                // Charged: freeze a patch of water (lava turns to obsidian for good)
                int r = (ph == 2 ? 2 : ph) + (rank("hydraulic.permafrost") >= 2 ? 1 : 0) + payload;
                for (BlockPos p :
                        BlockPos.betweenClosed(front.offset(-r, -1, -r), front.offset(r, 0, r))) {
                    if (sl.getBlockState(p).is(Blocks.WATER)
                            && sl.getFluidState(p).isSource()
                            && mayBuild(p)) {
                        WorldEffects.tempBlock(
                                sl,
                                p.immutable(),
                                Blocks.PACKED_ICE.defaultBlockState(),
                                OrbFrost.bridgeTicks(this),
                                OrbFrost.permafrost(this));
                    } else if (sl.getBlockState(p).is(Blocks.LAVA)
                            && sl.getFluidState(p).isSource()
                            && mayBuild(p)) {
                        sl.setBlockAndUpdate(p, Blocks.OBSIDIAN.defaultBlockState());
                    }
                }
                for (LivingEntity e :
                        sl.getEntitiesOfClass(
                                LivingEntity.class,
                                new AABB(front).inflate(1 + r),
                                e -> e != getOwner())) {
                    e.addEffect(
                            new net.minecraft.world.effect.MobEffectInstance(
                                    net.minecraft.world.effect.MobEffects.MOVEMENT_SLOWDOWN,
                                    100,
                                    1));
                }
            }
            case IGNITE -> {
                if (!OrbFluids.forgeHeart(this, hitPos)) OrbNature.ignite(this, hitPos, face);
            }
            case GROWTH -> OrbNature.grow(this, hitPos);
            case LIGHT -> {
                OrbLight.lightImpact(this, front, hitPos);
                OrbLight.lightExtras(this, result.getLocation());
            }
            case GUST -> OrbGust.gustBurst(this, result.getLocation());
            case MASS -> {
                // a thrown block bounces off the first thing it hits, then places itself
                if (massBounces > 0) {
                    massBounces--;
                    OrbMass.bounce(this, face, result.getLocation());
                    return;
                }
                OrbMass.massPlace(this, hitPos, face, result.getLocation());
            }
            default -> {}
        }
        impactFx(result.getLocation());
        afterImpact(result.getLocation());
        discard();
    }

    /** Effects that follow any impact. */
    void afterImpact(Vec3 at) {
        scheduleEcho(at);
    }

    /** Echo: a phase 3 impact happens again a second later, at half strength (full at II). */
    void scheduleEcho(Vec3 at) {
        if (echo
                || getPhase() != 2
                || !has("kinetic.echo")
                || isDrifting()
                || !(getOwner() instanceof LivingEntity owner)) return;
        ServerLevel sl = (ServerLevel) level();
        Vec3 v = getDeltaMovement().lengthSqr() > 1e-4 ? getDeltaMovement() : new Vec3(0, -0.5, 0);
        ItemStack snap = traits.copy();
        OrbMode mode = getMode();
        ManipulatorStage stage = getStage();
        float dmg = damage * (rank("kinetic.echo") >= 2 ? 1f : 0.5f);
        WorldEffects.schedule(
                sl,
                20,
                () -> {
                    if (!owner.isAlive() || owner.level() != sl) return;
                    BeamBallEntity e = new BeamBallEntity(sl, owner, 2, stage, mode);
                    e.configure(snap);
                    e.damage = dmg;
                    e.echo = true;
                    e.bouncesLeft = 0;
                    Vec3 start = at.subtract(v.normalize().scale(1.5));
                    e.setPos(start.x, start.y, start.z);
                    e.setDeltaMovement(v);
                    sl.addFreshEntity(e);
                });
    }

    // =========================================================================
    // Mode effects
    // =========================================================================

    boolean mayBuild(BlockPos pos) {
        return !(getOwner() instanceof ServerPlayer sp) || com.ethermod.util.Protect.may(level(), sp, pos);
    }

    /** Meteor landing: a blast that hurts and burns, without breaking blocks. */
    void meteorImpact(Vec3 at) {
        ServerLevel sl = (ServerLevel) level();
        for (LivingEntity e :
                sl.getEntitiesOfClass(
                        LivingEntity.class,
                        new AABB(at, at).inflate(3.5),
                        e -> e != getOwner() && e.isAlive())) {
            e.hurt(
                    damageSources().thrown(this, getOwner() instanceof LivingEntity le ? le : this),
                    10f);
            if (getOwner() instanceof ServerPlayer sp)
                com.ethermod.trait.RedesignEffects.burn(e, sp, 6, getPhase());
            else e.igniteForSeconds(6);
            Vec3 away = e.position().subtract(at);
            away = away.lengthSqr() < 1e-4 ? new Vec3(0, 1, 0) : away.normalize();
            e.setDeltaMovement(e.getDeltaMovement().add(away.x, 0.5, away.z));
            e.hurtMarked = true;
        }
        WorldEffects.fireRing(sl, BlockPos.containing(at), getOwner(), 2);
        sl.sendParticles(ParticleTypes.EXPLOSION_EMITTER, at.x, at.y, at.z, 1, 0, 0, 0, 0);
        sl.sendParticles(ParticleTypes.LAVA, at.x, at.y, at.z, 30, 1.5, 0.5, 1.5, 0.2);
        sl.playSound(
                null,
                at.x,
                at.y,
                at.z,
                com.ethermod.sound.ModSounds.WORLD_GENERIC_EXPLODE,
                SoundSource.PLAYERS,
                1.2f,
                0.8f);
    }

    /** Gives the bucket back if the liquid couldn't land anywhere. */
    void refundTank() {
        // One resource payment funds the whole volley. Failed individual impacts
        // cannot refund that payment repeatedly; firing spends it once.

    }

    /** Impact burst, visible to everyone nearby. */
    void impactFx(Vec3 pos) {
        if (!(level() instanceof ServerLevel sl)) return;
        int ph = getPhase();

        switch (getMode()) {
            case ENERGY -> {
                sl.sendParticles(ParticleTypes.FLASH, pos.x, pos.y, pos.z, 1, 0, 0, 0, 0);
                sl.sendParticles(
                        ParticleTypes.ELECTRIC_SPARK,
                        pos.x,
                        pos.y,
                        pos.z,
                        12 + 10 * ph,
                        0.2,
                        0.2,
                        0.2,
                        0.5 + 0.3 * ph);
                sl.sendParticles(
                        ParticleTypes.END_ROD,
                        pos.x,
                        pos.y,
                        pos.z,
                        4 + 4 * ph,
                        0.1,
                        0.1,
                        0.1,
                        0.12);
                sl.playSound(
                        null,
                        pos.x,
                        pos.y,
                        pos.z,
                        com.ethermod.sound.ModSounds.WORLD_AMETHYST_CLUSTER_BREAK,
                        SoundSource.PLAYERS,
                        0.7f + 0.2f * ph,
                        1.6f - 0.25f * ph);
            }
            case VACUUM -> {
                sl.sendParticles(
                        ParticleTypes.REVERSE_PORTAL,
                        pos.x,
                        pos.y,
                        pos.z,
                        30 + 15 * ph,
                        0.6,
                        0.6,
                        0.6,
                        0.1);
                sl.playSound(
                        null,
                        pos.x,
                        pos.y,
                        pos.z,
                        com.ethermod.sound.ModSounds.WORLD_ENDERMAN_TELEPORT,
                        SoundSource.PLAYERS,
                        0.5f,
                        1.8f);
            }
            case WATER -> {
                sl.sendParticles(
                        ParticleTypes.SPLASH,
                        pos.x,
                        pos.y,
                        pos.z,
                        30 + 15 * ph,
                        0.4,
                        0.3,
                        0.4,
                        0.2);
                sl.sendParticles(
                        ParticleTypes.BUBBLE_POP, pos.x, pos.y, pos.z, 10, 0.3, 0.3, 0.3, 0.05);
                sl.playSound(
                        null,
                        pos.x,
                        pos.y,
                        pos.z,
                        com.ethermod.sound.ModSounds.WORLD_PLAYER_SPLASH,
                        SoundSource.PLAYERS,
                        0.6f,
                        1.3f);
            }
            case LAVA -> {
                sl.sendParticles(
                        ParticleTypes.LAVA, pos.x, pos.y, pos.z, 10 + 6 * ph, 0.3, 0.2, 0.3, 0.1);
                sl.sendParticles(
                        ParticleTypes.LARGE_SMOKE, pos.x, pos.y, pos.z, 6, 0.3, 0.3, 0.3, 0.02);
                sl.playSound(
                        null,
                        pos.x,
                        pos.y,
                        pos.z,
                        com.ethermod.sound.ModSounds.WORLD_LAVA_EXTINGUISH,
                        SoundSource.PLAYERS,
                        0.5f,
                        1.2f);
            }
            case FROST -> {
                sl.sendParticles(
                        ParticleTypes.SNOWFLAKE,
                        pos.x,
                        pos.y,
                        pos.z,
                        25 + 15 * ph,
                        0.5,
                        0.4,
                        0.5,
                        0.05);
                sl.sendParticles(
                        ParticleTypes.ITEM_SNOWBALL, pos.x, pos.y, pos.z, 10, 0.3, 0.3, 0.3, 0.1);
                sl.playSound(
                        null,
                        pos.x,
                        pos.y,
                        pos.z,
                        com.ethermod.sound.ModSounds.WORLD_GLASS_BREAK,
                        SoundSource.PLAYERS,
                        0.7f,
                        1.5f);
            }
            case IGNITE -> {
                sl.sendParticles(
                        isSoulFire() ? ParticleTypes.SOUL_FIRE_FLAME : ParticleTypes.FLAME,
                        pos.x,
                        pos.y,
                        pos.z,
                        16 + 8 * ph,
                        0.3,
                        0.3,
                        0.3,
                        0.04);
                sl.sendParticles(ParticleTypes.SMOKE, pos.x, pos.y, pos.z, 8, 0.2, 0.2, 0.2, 0.02);
                sl.playSound(
                        null,
                        pos.x,
                        pos.y,
                        pos.z,
                        com.ethermod.sound.ModSounds.WORLD_FIRECHARGE_USE,
                        SoundSource.PLAYERS,
                        0.6f,
                        1.3f);
            }
            case GROWTH -> {
                sl.sendParticles(
                        ParticleTypes.HAPPY_VILLAGER,
                        pos.x,
                        pos.y,
                        pos.z,
                        15 + 10 * ph,
                        0.6,
                        0.4,
                        0.6,
                        0.05);
                sl.sendParticles(
                        ParticleTypes.CHERRY_LEAVES,
                        pos.x,
                        pos.y + 0.5,
                        pos.z,
                        8,
                        0.5,
                        0.3,
                        0.5,
                        0.02);
                sl.playSound(
                        null,
                        pos.x,
                        pos.y,
                        pos.z,
                        com.ethermod.sound.ModSounds.WORLD_BONE_MEAL_USE,
                        SoundSource.PLAYERS,
                        0.8f,
                        1.2f);
            }
            case LIGHT -> {
                sl.sendParticles(ParticleTypes.FLASH, pos.x, pos.y, pos.z, 1, 0, 0, 0, 0);
                sl.sendParticles(
                        ParticleTypes.END_ROD,
                        pos.x,
                        pos.y,
                        pos.z,
                        10 + 6 * ph,
                        0.3,
                        0.3,
                        0.3,
                        0.08);
                sl.playSound(
                        null,
                        pos.x,
                        pos.y,
                        pos.z,
                        com.ethermod.sound.ModSounds.WORLD_AMETHYST_BLOCK_CHIME,
                        SoundSource.PLAYERS,
                        1f,
                        1.6f);
            }
            case TETHER -> {
                sl.sendParticles(ParticleTypes.WITCH, pos.x, pos.y, pos.z, 12, 0.3, 0.3, 0.3, 0.05);
            }
            case MASS -> {
                BlockState c = getCarried();
                if (!c.isAir())
                    sl.sendParticles(
                            new net.minecraft.core.particles.BlockParticleOption(
                                    ParticleTypes.BLOCK, c),
                            pos.x,
                            pos.y,
                            pos.z,
                            20,
                            0.3,
                            0.3,
                            0.3,
                            0.1);
            }
            default -> {}
        }
    }

    // =========================================================================
    // Photonic
    // =========================================================================

    // =========================================================================
    // Pneumatic: Gust
    // =========================================================================

    // =========================================================================
    // Tectonic: Mass Driver
    // =========================================================================

    // =========================================================================
    // Kinetic: Detonator
    // =========================================================================

    /** Detonator: burst right where it is, with its full impact effect. */
    static void blinkTo(ServerPlayer player, Vec3 at) {
        ServerLevel sl = player.serverLevel();
        Vec3 old = player.position();
        sl.sendParticles(ParticleTypes.PORTAL, old.x, old.y + 1, old.z, 32, .3, .7, .3, .15);
        player.teleportTo(
                sl, at.x, at.y, at.z, java.util.Set.of(), player.getYRot(), player.getXRot());
        sl.sendParticles(ParticleTypes.PORTAL, at.x, at.y + 1, at.z, 32, .3, .7, .3, .15);
        sl.playSound(
                null,
                old.x,
                old.y,
                old.z,
                com.ethermod.sound.ModSounds.WORLD_ENDERMAN_TELEPORT,
                SoundSource.PLAYERS,
                .8f,
                1f);
        sl.playSound(
                null,
                at.x,
                at.y,
                at.z,
                com.ethermod.sound.ModSounds.WORLD_ENDERMAN_TELEPORT,
                SoundSource.PLAYERS,
                .8f,
                1f);
    }

    public void detonate() {
        if (level().isClientSide
                || isRemoved()
                || isStuck()
                || isAnchor()
                || getMode() == OrbMode.TETHER
                || getMode() == OrbMode.VACUUM
                || MassDriver.isHeld(this)) return;
        entityData.set(ORBITING, false);
        bouncesLeft = 0;
        BlockPos here = blockPosition();
        onHitBlock(new BlockHitResult(position(), Direction.UP, here.below(), false));
    }

    public void setFiredLeft(boolean left) {
        entityData.set(FIRED_LEFT, left);
    }

    public boolean firedLeft() {
        return entityData.get(FIRED_LEFT);
    }

    /** In the air and able to burst: not a hook, an anchor, a cone or a held block. */
    private boolean detonatable() {
        return !isRemoved()
                && !isStuck()
                && !isAnchor()
                && getMode() != OrbMode.TETHER
                && getMode() != OrbMode.VACUUM
                && getMode() != OrbMode.MASS;
    }

    /**
     * Detonator: pressing the input that fired a projectile again, while it is still in the air,
     * bursts it (every one, for a Split volley) instead of firing another. True when something
     * burst, so the press fires nothing; on the client it only answers whether one would.
     */
    public static boolean detonateFor(Player p, ItemStack stack, boolean left) {
        if (!Traits.has(stack, "kinetic.detonator")) return false;
        var live =
                p.level()
                        .getEntitiesOfClass(
                                BeamBallEntity.class,
                                p.getBoundingBox().inflate(160),
                                b -> b.getOwner() == p && b.firedLeft() == left && b.detonatable());
        if (live.isEmpty()) return false;
        if (!p.level().isClientSide) for (BeamBallEntity b : live) b.detonate();
        return true;
    }

    // =========================================================================
    // NBT
    // =========================================================================

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putBoolean("mm_soul_projectile", isSoulFire());
        tag.putInt(TAG_PHASE, getPhase());
        tag.putInt("mm_mode", getMode().ordinal());
        tag.putInt("mm_stage", entityData.get(STAGE));
        tag.putInt("mm_color", entityData.get(COLOR));
        tag.putFloat(TAG_DAMAGE, damage);
        tag.putFloat(TAG_RANGE, maxRange);
        tag.putBoolean(TAG_BURNING, burningLight);
        tag.putFloat(TAG_TRAVEL, traveledDistance);
        if (!getCarried().isAir())
            tag.put("mm_carried", net.minecraft.nbt.NbtUtils.writeBlockState(getCarried()));
        if (carriedBe != null) tag.put("mm_carried_be", carriedBe);
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        carriedBe = tag.contains("mm_carried_be") ? tag.getCompound("mm_carried_be") : null;
        entityData.set(PHASE, tag.getInt(TAG_PHASE));
        entityData.set(MODE, tag.getInt("mm_mode"));
        entityData.set(SOULFIRE, tag.getBoolean("mm_soul_projectile"));
        entityData.set(STAGE, tag.getInt("mm_stage"));
        if (tag.contains("mm_color")) entityData.set(COLOR, tag.getInt("mm_color"));
        damage = tag.getFloat(TAG_DAMAGE);
        maxRange = tag.getFloat(TAG_RANGE);
        burningLight = tag.getBoolean(TAG_BURNING);
        traveledDistance = tag.getFloat(TAG_TRAVEL);
        if (tag.contains("mm_carried"))
            setCarried(
                    net.minecraft.nbt.NbtUtils.readBlockState(
                            net.minecraft.core.registries.BuiltInRegistries.BLOCK.asLookup(),
                            tag.getCompound("mm_carried")));
    }
}
