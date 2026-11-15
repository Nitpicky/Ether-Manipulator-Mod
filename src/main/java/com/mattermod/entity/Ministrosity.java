package com.mattermod.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import org.joml.Vector3f;

import software.bernie.geckolib.animatable.GeoEntity;
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.animation.AnimationController;
import software.bernie.geckolib.animation.RawAnimation;
import software.bernie.geckolib.util.GeckoLibUtil;

import java.util.List;
import java.util.UUID;

/**
 * Ministrosities trait: a little redstone fighter built by the Golem's overheat. It joins the
 * Golem's fight, guards it otherwise, and falls apart when the Golem is gone. Never saved.
 */
public final class Ministrosity extends PathfinderMob implements GeoEntity {
    static final float MAX_HEALTH = 12, DAMAGE = 3;
    static final int ATTACK_COOLDOWN = 20, ATTACK_HIT = 5;
    public static final int SPAWN_TICKS = 20;
    private static final DustParticleOptions DUST = new DustParticleOptions(new Vector3f(.9f, .1f, .05f), 1f);

    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);
    private UUID golemId, ownerId;
    /** The Golem's entity id, synced so the client can match its overheat (colours and pace). */
    private static final net.minecraft.network.syncher.EntityDataAccessor<Integer> GOLEM =
            net.minecraft.network.syncher.SynchedEntityData.defineId(Ministrosity.class,
                    net.minecraft.network.syncher.EntityDataSerializers.INT);
    /** Overheat: they run and strike this much faster, and hit this much harder. */
    static final double OVERHEAT_FACTOR = RedstoneGolem.OVERHEAT_FACTOR;
    private int attackCooldown, hitIn, snickerIn;
    /** Shared by all Ministrosities (server): when the next snicker and the next yelp may play. */
    private static long nextSnicker, nextYelp;

    public Ministrosity(EntityType<? extends Ministrosity> type, Level level) {
        super(type, level);
    }

    public static AttributeSupplier.Builder attributes() {
        return PathfinderMob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, MAX_HEALTH)
                .add(Attributes.MOVEMENT_SPEED, .3)
                .add(Attributes.ATTACK_DAMAGE, DAMAGE)
                .add(Attributes.FOLLOW_RANGE, 24);
    }

    @Override
    protected void defineSynchedData(net.minecraft.network.syncher.SynchedEntityData.Builder b) {
        super.defineSynchedData(b);
        b.define(GOLEM, -1);
    }

    /** Client or server: its Golem, if loaded. */
    public RedstoneGolem linkedGolem() {
        return level().getEntity(entityData.get(GOLEM)) instanceof RedstoneGolem g ? g : null;
    }

    private boolean hot() {
        RedstoneGolem g = linkedGolem();
        return g != null && g.overheated();
    }

    @Override
    protected void registerGoals() {
        goalSelector.addGoal(0, new FloatGoal(this));
    }

    void bind(RedstoneGolem golem, ServerPlayer owner) {
        golemId = golem.getUUID();
        entityData.set(GOLEM, golem.getId());
        ownerId = owner.getUUID();
        // counts as the owner's ally everywhere (beam, orbs, Golem)
        getPersistentData().putUUID("mm_summoner", ownerId);
        // summons time out unless they carry an expiry; a Ministrosity lives as long as its Golem
        getPersistentData().putLong("mm_summon_expires", Long.MAX_VALUE);
    }

    /** The living Ministrosities of {@code golem}. */
    public static List<Ministrosity> of(RedstoneGolem golem) {
        return golem.level().getEntitiesOfClass(Ministrosity.class, golem.getBoundingBox().inflate(48),
                m -> m.isAlive() && golem.getUUID().equals(m.golemId));
    }

    private RedstoneGolem golem() {
        if (golemId == null || !(level() instanceof ServerLevel sl)) return null;
        return sl.getEntity(golemId) instanceof RedstoneGolem g && g.isAlive() && !g.recalling() ? g : null;
    }

    @Override
    public void tick() {
        super.tick();
        if (level().isClientSide) {
            // it digs itself up out of the ground, spinning
            if (tickCount < 14) {
                var under = level().getBlockState(blockPosition().below());
                if (!under.isAir())
                    for (int i = 0; i < 3; i++)
                        level().addParticle(new net.minecraft.core.particles.BlockParticleOption(net.minecraft.core.particles.ParticleTypes.BLOCK, under),
                                getX() + (getRandom().nextDouble() - .5) * .8, getY() + .1, getZ() + (getRandom().nextDouble() - .5) * .8, 0, .2, 0);
            }
            // lit redstone sheds dust like a powered wire, in its Golem's heat colours
            if (isAlive() && getRandom().nextInt(6) == 0)
                level().addParticle(linkedGolem() instanceof RedstoneGolem g ? g.stageDust(.8f) : RedstoneGolem.wireDust(8 + getRandom().nextInt(8), .8f),
                        getX() + (getRandom().nextDouble() - .5) * getBbWidth(), getY() + getRandom().nextDouble() * getBbHeight(),
                        getZ() + (getRandom().nextDouble() - .5) * getBbWidth(), 0, 0, 0);
            return;
        }
        if (!isAlive()) return;
        RedstoneGolem g = golem();
        ServerPlayer owner = g == null ? null : g.owner();
        if (g == null || owner == null) {
            com.mattermod.MatterMod.LOGGER.info("[ministrosity] falls apart at age {}: golem {} owner {}", tickCount, g != null, owner != null);
            fallApart();
            return;
        }
        // the Golem's overheat drives them too: faster on their feet and in the swing
        boolean hot = g.overheated();
        var speed = getAttribute(Attributes.MOVEMENT_SPEED);
        if (speed != null) speed.setBaseValue(hot ? .3 * OVERHEAT_FACTOR : .3);
        if (attackCooldown > 0) attackCooldown -= hot ? 2 : 1;
        if (attackCooldown < 0) attackCooldown = 0;
        if (hitIn > 0 && --hitIn == 0) strike(g, owner);
        if (tickCount < SPAWN_TICKS) {
            getNavigation().stop();
            return;
        }
        LivingEntity t = pick(g, owner);
        setTarget(t);
        setAggressive(t != null);   // synced: the client runs the chase walk
        if (t != null) {
            getLookControl().setLookAt(t, 30, 30);
            double reach = 1.2 + t.getBbWidth() / 2;
            if (distanceToSqr(t) > reach * reach) {
                if (tickCount % 8 == 0 || getNavigation().isDone()) getNavigation().moveTo(t, 1.1);
            } else {
                getNavigation().stop();
                if (attackCooldown == 0) {
                    attackCooldown = ATTACK_COOLDOWN;
                    hitIn = ATTACK_HIT;
                    triggerAnim("main", "attack");
                }
            }
            return;
        }
        // now and then, while standing around, a little snicker
        // one snicker at a time across all of them, spaced out; the giggle is timed to its cackle (frame 4)
        if (getNavigation().isDone() && hitIn == 0 && random.nextInt(240) == 0
                && (level().getGameTime() >= nextSnicker || nextSnicker - level().getGameTime() > 600)) {   // (another world's clock)
            nextSnicker = level().getGameTime() + 300 + random.nextInt(200);
            triggerAnim("main", "idle_break");
            snickerIn = 4;
        }
        if (snickerIn > 0 && --snickerIn == 0) playSound(com.mattermod.registry.ModSounds.MINI_AMBIENT.get(), 1, 1);
        if (distanceToSqr(g) > 16) {
            if (tickCount % 10 == 0 || getNavigation().isDone()) getNavigation().moveTo(g, 1.1);
        } else getNavigation().stop();
        if (distanceToSqr(g) > 32 * 32) moveTo(g.getX(), g.getY(), g.getZ());
    }

    private LivingEntity pick(RedstoneGolem g, ServerPlayer owner) {
        LivingEntity gt = g.getTarget();
        if (gt != null && g.canHit(owner, gt)) return gt;
        LivingEntity mine = getTarget();
        if (mine != null && g.canHit(owner, mine) && mine.distanceToSqr(g) < 16 * 16) return mine;
        LivingEntity best = null;
        double bestD = 8 * 8;
        for (var e : level().getEntitiesOfClass(LivingEntity.class, getBoundingBox().inflate(8), e -> e instanceof Enemy && g.canHit(owner, e))) {
            double d = distanceToSqr(e);
            if (d < bestD) {
                bestD = d;
                best = e;
            }
        }
        return best;
    }

    private void strike(RedstoneGolem g, ServerPlayer owner) {
        LivingEntity t = getTarget();
        if (t == null || !t.isAlive() || distanceToSqr(t) > 2.6 * 2.6) return;
        if (t.hurt(damageSources().mobAttack(this), hot() ? (float) (DAMAGE * OVERHEAT_FACTOR) : DAMAGE)) {
            Vec3 push = t.position().subtract(position()).multiply(1, 0, 1);
            if (push.lengthSqr() > 1e-4) push = push.normalize().scale(.35);
            t.push(push.x, .1, push.z);
            playSound(com.mattermod.registry.ModSounds.MINI_ATTACK.get(), .43f, 1);
        }
    }

    private void fallApart() {
        if (level() instanceof ServerLevel sl)
            sl.sendParticles(DUST, getX(), getY() + .4, getZ(), 12, .3, .3, .3, 0);
        discard();
    }

    @Override
    public boolean hurt(DamageSource source, float amount) {
        // its own side never hurts it
        if (ownerId != null && source.getEntity() != null
                && (ownerId.equals(source.getEntity().getUUID()) || source.getEntity() instanceof RedstoneGolem
                        || source.getEntity() instanceof Ministrosity)) return false;
        return super.hurt(source, amount);
    }

    @Override
    public void die(DamageSource source) {
        com.mattermod.MatterMod.LOGGER.info("[ministrosity] died at age {} from {} by {}", tickCount, source.getMsgId(),
                source.getEntity() == null ? "-" : source.getEntity().getType().toShortString());
        super.die(source);
    }

    @Override
    public void remove(RemovalReason reason) {
        com.mattermod.MatterMod.LOGGER.info("[ministrosity] removed at age {}: {}", tickCount, reason);
        super.remove(reason);
    }

    @Override
    public boolean shouldBeSaved() {
        return false;
    }

    @Override
    public boolean removeWhenFarAway(double distance) {
        return false;
    }

    @Override
    protected boolean shouldDropLoot() {
        return false;
    }

    @Override
    protected SoundEvent getHurtSound(DamageSource source) {
        return com.mattermod.registry.ModSounds.MINI_HURT.get();
    }

    @Override
    protected SoundEvent getDeathSound() {
        return com.mattermod.registry.ModSounds.MINI_DEATH.get();
    }

    @Override
    protected void playStepSound(BlockPos pos, BlockState state) {
        playSound(com.mattermod.registry.ModSounds.MINI_STEP.get(), .25f, 1);
    }

    @Override
    public float getVoicePitch() {
        return .95f + random.nextFloat() * .1f;   // its hurt and death sounds are already pitched up
    }

    @Override
    protected SoundEvent getAmbientSound() {
        return com.mattermod.registry.ModSounds.MINI_AMBIENT.get();
    }

    @Override
    public void playSound(SoundEvent sound, float volume, float pitch) {
        super.playSound(sound, volume * RedstoneGolem.SOUND_GAIN, pitch);
    }

    /** No random ambient: it only giggles with its snicker animation, one Ministrosity at a time (see the tick). */
    @Override
    public void playAmbientSound() {}

    /** A group yelps at most once a second between them: three hit by one sweep make one yelp, not a rattle. */
    @Override
    protected void playHurtSound(DamageSource source) {
        long now = level().getGameTime();
        if (now < nextYelp && nextYelp - now <= 20) return;
        nextYelp = now + 20;
        super.playHurtSound(source);
    }

    // ---- GeckoLib -------------------------------------------------------------------------------

    static final RawAnimation IDLE = RawAnimation.begin().thenLoop("animation.ministrosity.idle"),
            WALK = RawAnimation.begin().thenLoop("animation.ministrosity.walk"),
            CHASE = RawAnimation.begin().thenLoop("animation.ministrosity.chase"),
            SPAWN = RawAnimation.begin().thenPlayAndHold("animation.ministrosity.spawn");

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        var main = new AnimationController<>(this, "main", 2, state -> {
            Ministrosity m = state.getAnimatable();
            if (m.tickCount < SPAWN_TICKS) return state.setAndContinue(SPAWN);
            return state.setAndContinue(state.isMoving() ? (m.isAggressive() ? CHASE : WALK) : IDLE);
        });
        main.setAnimationSpeedHandler(m -> m.hot() ? OVERHEAT_FACTOR : 1.0);
        main.triggerableAnim("attack", RawAnimation.begin().thenPlay("animation.ministrosity.attack"));
        main.triggerableAnim("idle_break", RawAnimation.begin().thenPlay("animation.ministrosity.idle_break"));
        controllers.add(main);
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return cache;
    }
}
