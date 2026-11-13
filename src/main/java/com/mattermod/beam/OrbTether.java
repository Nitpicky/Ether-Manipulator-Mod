package com.mattermod.beam;

import com.mattermod.item.OrbMode;
import com.mattermod.manipulator.Manipulator;
import com.mattermod.trait.Traits;
import com.mattermod.trait.WorldEffects;

import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;


/**
 * Tether mode: grapple hook, rope reeling, Swing, Recall, Hammer Throw and Zipline anchoring. Split
 * out of {@link BeamBallEntity}; every method takes the orb as {@code b}.
 */
public final class OrbTether {

    private OrbTether() {}

    /**
     * Tether: the hook lives only while its owner holds RMB. Once it catches a block it reels the
     * owner in; with Swing the rope keeps its length and the owner swings on it (the physics run on
     * the owner's client, see ClientMobility). A caught creature: Displacement swaps places on
     * sneak, Hammer Throw whirls it around you, Recall reels light ones in. Letting go: Slingshot
     * fires you past the hook; letting go while sneaking leaves the hook as a Zipline anchor.
     * Returns true while the hook is still flying (so normal flight continues).
     */
    /** The endpoint is a virtual block hit, not a separate hanging-rope or Zipline anchor. */
    public static void hookAir(BeamBallEntity b) {
        if (!(b.getOwner() instanceof ServerPlayer sp)) return;
        b.virtualHit = true;
        b.airReelAfterRelease = !sp.isUsingItem();
        Direction face = com.mattermod.trait.MovementPhysics.down(sp).getOpposite();
        b.onHitBlock(new BlockHitResult(b.position(), face, b.blockPosition(), false));
    }

    /**
     * Ticks between the hook catching something and the claw clamping on it (the grip animation's
     * clamp): the catch sound plays then, and reeling starts the tick after.
     */
    static final int HOOK_DELAY = 3;

    /** The claw clamps: the catch sound and sparks, once. */
    private static void clamp(BeamBallEntity b, ServerLevel sl) {
        b.hookAt = -1;
        sl.playSound(
                null,
                b.getX(),
                b.getY(),
                b.getZ(),
                SoundEvents.CHAIN_PLACE,
                SoundSource.PLAYERS,
                1f,
                b.hookPitch);
        sl.sendParticles(ParticleTypes.CRIT, b.getX(), b.getY(), b.getZ(), 6, 0.1, 0.1, 0.1, 0.1);
    }

    static boolean tetherTick(BeamBallEntity b) {
        if (!(b.getOwner() instanceof ServerPlayer sp)
                || !sp.isAlive()
                || sp.level() != b.level()) {
            b.discard();
            return false;
        }
        if (!Traits.hasLine(Manipulator.stack(sp), Traits.MOBILITY)
                || com.mattermod.item.OrbMode.get(Manipulator.stack(sp)) != OrbMode.TETHER) {
            b.discard();
            return false;
        }
        ServerLevel sl = (ServerLevel) b.level();
        boolean held =
                sp.isUsingItem()
                        && sp.getUseItem().getItem()
                                instanceof com.mattermod.item.MatterManipulatorItem;
        // A jump cancels the tether, flying or caught (in the air, with the Slingshot fling)
        boolean jump = com.mattermod.trait.MovementPhysics.jump(sp);
        boolean jumped = jump && !b.jumpWas;
        b.jumpWas = jump;
        if (jumped && b.tickCount > 2) {
            if (b.isStuck() && b.hookAt < 0 && b.hooked == null) {
                if (sp.isShiftKeyDown() && b.has("mobility.zipline")) {
                    com.mattermod.trait.Ziplines.pin(sp, b);
                    return false;
                }
                if (!sp.onGround()) OrbTether.letGo(b, sp, sl);
            }
            b.discard();
            return false;
        }
        if (!b.isStuck()) {
            return !b.isStuck();
        }
        if (++b.idleTicks > 20 * 30 && !held && !b.reelFull) {
            b.discard(); // forgotten for 30 s
            return false;
        }
        if (held || b.reelFull) b.idleTicks = 0;
        b.reeling = held || b.reelFull;
        // the claw is still closing (the grip animation): it clamps with the sound, then reels
        if (b.hookAt >= 0) {
            if (sl.getGameTime() < b.hookAt) return false;
            clamp(b, sl);
            return false;
        }
        Vec3 hands = sp.getBoundingBox().getCenter();

        // A caught creature or item
        if (b.hooked != null) {
            if (!b.hooked.isAlive() || b.hooked.level() != sl) {
                b.discard();
                return false;
            }
            boolean mob =
                    b.hooked instanceof LivingEntity
                            && !(b.hooked instanceof net.minecraft.world.entity.player.Player)
                            && !b.hooked
                                    .getType()
                                    .is(net.neoforged.neoforge.common.Tags.EntityTypes.BOSSES);
            // Displacement: sneak to swap places with it
            if (mob && b.has("mobility.displacement") && sp.isShiftKeyDown()) {
                Vec3 me = sp.position(), it = b.hooked.position();
                b.hooked.teleportTo(me.x, me.y, me.z);
                sp.teleportTo(it.x, it.y, it.z);
                sp.resetFallDistance();
                sl.playSound(
                        null,
                        me.x,
                        me.y,
                        me.z,
                        SoundEvents.ENDERMAN_TELEPORT,
                        SoundSource.PLAYERS,
                        0.8f,
                        1.3f);
                sl.sendParticles(
                        ParticleTypes.REVERSE_PORTAL,
                        me.x,
                        me.y + 1,
                        me.z,
                        30,
                        0.3,
                        0.6,
                        0.3,
                        0.05);
                sl.sendParticles(
                        ParticleTypes.REVERSE_PORTAL,
                        it.x,
                        it.y + 1,
                        it.z,
                        30,
                        0.3,
                        0.6,
                        0.3,
                        0.05);
                b.discard();
                return false;
            }
            // Hammer Throw: whirl it around you
            if (mob && b.has("mobility.hammer_throw")) {
                b.whirling = true;
                double ang = b.lifeTicks++ * 0.45;
                double r = 2.6;
                Vec3 c = sp.position().add(0, 1.0, 0);
                Vec3 want = c.add(Math.cos(ang) * r, 0.2, Math.sin(ang) * r);
                b.hooked.setDeltaMovement(want.subtract(b.hooked.position()).scale(0.6));
                b.hooked.hurtMarked = true;
                b.hooked.resetFallDistance();
                if (b.hooked instanceof net.minecraft.world.entity.Mob m) m.getNavigation().stop();
                b.setPos(hookPoint(b));
                if (b.lifeTicks % 8 == 0)
                    sl.playSound(
                            null,
                            sp.getX(),
                            sp.getY(),
                            sp.getZ(),
                            SoundEvents.BREEZE_IDLE_GROUND,
                            SoundSource.PLAYERS,
                            0.5f,
                            1.6f);
                return false;
            }
            b.setPos(hookPoint(b));
            Vec3 d = b.position().subtract(hands);
            boolean light =
                    false
                            && b.has("mobility.recall")
                            && (!(b.hooked instanceof LivingEntity)
                                    || b.hooked.getBbWidth() * b.hooked.getBbHeight()
                                            <= sp.getBbWidth() * sp.getBbHeight());
            if (light) {
                if (d.length() > 1.5) {
                    b.hooked.setDeltaMovement(d.normalize().scale(-0.55).add(0, 0.08, 0));
                    b.hooked.hurtMarked = true;
                } else if (!(b.hooked instanceof LivingEntity)) {
                    b.hooked.teleportTo(sp.getX(), sp.getY() + 0.5, sp.getZ());
                    if (b.hooked instanceof ItemEntity ie) ie.setNoPickUpDelay();
                }
                // Recall II: everything loose near the hook comes along
                if (b.rank("mobility.recall") >= 2) {
                    for (Entity e :
                            sl.getEntities(
                                    b,
                                    b.getBoundingBox().inflate(2),
                                    e ->
                                            e != b.hooked
                                                    && (e instanceof ItemEntity
                                                            || e instanceof ExperienceOrb))) {
                        e.setDeltaMovement(
                                hands.subtract(e.position())
                                        .normalize()
                                        .scale(0.55)
                                        .add(0, 0.08, 0));
                        e.hurtMarked = true;
                    }
                }
                return false;
            }
            if (!b.reeling) return false;
            if (mob || b.hooked instanceof ItemEntity || b.hooked instanceof ExperienceOrb) {
                // the hooked thing comes to you; a tap brings it all the way
                if (d.length() > 2) {
                    b.hooked.setDeltaMovement(d.normalize().scale(-0.6).add(0, 0.1, 0));
                    b.hooked.hurtMarked = true;
                    // a hooked creature can't run off (a hurt pig panics away and halves the reel)
                    if (b.hooked instanceof net.minecraft.world.entity.Mob m) m.getNavigation().stop();
                    b.hooked.resetFallDistance();
                } else {
                    b.reelFull = false;
                    if (!(b.hooked instanceof LivingEntity)) {
                        b.hooked.teleportTo(sp.getX(), sp.getY() + 0.5, sp.getZ());
                        if (b.hooked instanceof ItemEntity ie) ie.setNoPickUpDelay();
                        b.discard();
                    }
                }
            } else OrbTether.reel(b, sp, d); // too big to move (bosses, players): you go to it
            return false;
        }

        if (b.stuckBlock != null && b.level().getBlockState(b.stuckBlock).isAir()) {
            b.discard();
            return false;
        }
        if (b.landingSince >= 0) {
            land(b, sp, sl);
            return false;
        }
        Vec3 d = b.position().subtract(hands);
        if (b.reeling) {
            OrbTether.reel(b, sp, d);
            b.ropeLength = Math.max(1.5, d.length());
        } else rope(b, sp, d);
        return false;
    }

    /**
     * Not reeling: the hook holds you on a rope as long as it was when reeling stopped (or when it
     * caught), so you hang and swing under it instead of falling away.
     */
    private static void rope(BeamBallEntity b, ServerPlayer sp, Vec3 d) {
        double dist = d.length();
        if (b.ropeLength <= 0) b.ropeLength = Math.max(1.5, dist);
        if (dist <= b.ropeLength || dist < 1e-3) return;
        Vec3 in = d.scale(1 / dist);
        Vec3 v = sp.getDeltaMovement();
        double out = -v.dot(in);
        // take away the speed that stretches the rope, and pull back what it stretched
        if (out > 0) v = v.add(in.scale(out));
        v = v.add(in.scale(Math.min(.4, (dist - b.ropeLength) * .5)));
        sp.setDeltaMovement(v);
        sp.hurtMarked = true;
        sp.resetFallDistance();
        com.mattermod.trait.MobilityEffects.markSoftLanding(sp);
    }

    /** The player's hook, flying or caught (not a Zipline anchor); null if none. */
    public static BeamBallEntity activeHook(ServerPlayer sp) {
        for (BeamBallEntity e :
                sp.serverLevel()
                        .getEntitiesOfClass(
                                BeamBallEntity.class,
                                sp.getBoundingBox().inflate(192),
                                e ->
                                        e.getOwner() == sp
                                                && e.getMode() == OrbMode.TETHER
                                                && !e.isAnchor()
                                                && !e.isRemoved())) return e;
        return null;
    }

    /** Left click: fires a new hook (the old one lets go). */
    public static void fire(ServerPlayer sp, net.minecraft.world.item.ItemStack stack) {
        ServerLevel sl = sp.serverLevel();
        for (var old :
                sl.getEntitiesOfClass(
                        BeamBallEntity.class,
                        sp.getBoundingBox().inflate(192),
                        e -> e.getOwner() == sp && e.getMode() == OrbMode.TETHER && !e.isAnchor()))
            old.discard();
        BeamBallEntity hook =
                new BeamBallEntity(
                        sl, sp, 0, com.mattermod.item.ManipulatorStage.get(stack), OrbMode.TETHER);
        hook.configure(stack);
        sl.addFreshEntity(hook);
        sl.playSound(
                null,
                sp.getX(),
                sp.getEyeY(),
                sp.getZ(),
                SoundEvents.CROSSBOW_SHOOT,
                SoundSource.PLAYERS,
                0.7f,
                1.4f);
    }

    /** Ticks of right click that still count as a tap, and between the taps of a double tap. */
    static final int TAP = 5, DOUBLE_TAP = 7;

    /**
     * Right click pressed with a hook out. A second press soon after the first is a double tap:
     * Blink or Gravity Shift, and true (nothing to hold). Otherwise it starts reeling.
     */
    public static boolean press(BeamBallEntity b, ServerPlayer sp) {
        long now = sp.serverLevel().getGameTime();
        boolean twice = now - b.lastPress <= DOUBLE_TAP;
        b.lastPress = now;
        b.idleTicks = 0;
        if (twice && b.isStuck() && b.hookAt < 0) {
            b.lastPress = -100;
            if (b.has("mobility.blink")) {
                blink(b, sp);
                return true;
            }
            if (b.has("mobility.gravity_shift") && !b.virtualHit && b.hooked == null && b.hookFace != null) {
                com.mattermod.trait.MovementPhysics.setGravity(sp, b.hookFace.getOpposite());
                b.landingSince = now;
                return true;
            }
        }
        return false;
    }

    /** Right click let go: a tap reels all the way in; a hold stops, keeping the rope's length. */
    public static void release(BeamBallEntity b, ServerPlayer sp, int ticksHeld) {
        if (ticksHeld <= TAP) b.reelFull = true; // (a tap before it catches reels once it does)
        else if (!b.isStuck()) return;
        else {
            b.reelFull = false;
            b.ropeLength = Math.max(1.5, b.position().distanceTo(sp.getBoundingBox().getCenter()));
        }
    }

    /** Blink: teleport to the hook (beside a caught creature, on the face of a caught block). */
    private static void blink(BeamBallEntity b, ServerPlayer sp) {
        ServerLevel sl = sp.serverLevel();
        if (b.hooked != null) {
            var dest = b.hooked.position().subtract(sp.getLookAngle().scale(1.2));
            if (sl.noCollision(sp, sp.getBoundingBox().move(dest.subtract(sp.position()))))
                BeamBallEntity.blinkTo(sp, dest);
            b.discard();
            return;
        }
        Direction face = b.hookFace == null ? Direction.UP : b.hookFace;
        Vec3 dest = b.position().add(Vec3.atLowerCornerOf(face.getNormal()).scale(0.5));
        if (face.getAxis() != Direction.Axis.Y) dest = dest.add(0, -0.9, 0);
        var old = sp.position();
        for (int i = 0; i < 6; i++) {
            var at = dest.add(0, i * 0.5, 0);
            if (sl.noCollision(sp, sp.getBoundingBox().move(at.subtract(old)))) {
                BeamBallEntity.blinkTo(sp, at);
                break;
            }
        }
        b.discard();
    }

    /**
     * Gravity Shift: glides you onto the caught face, feet first, slowing as you arrive, and sets
     * you down there gently (no dive into the new gravity).
     */
    private static void land(BeamBallEntity b, ServerPlayer sp, ServerLevel sl) {
        Direction face = b.hookFace;
        Vec3 dest =
                (b.stuckBlock != null ? Vec3.atCenterOf(b.stuckBlock) : b.position())
                        .add(Vec3.atLowerCornerOf(face.getNormal()).scale(b.stuckBlock != null ? .5 : 0));
        Vec3 d = dest.subtract(sp.position());
        double dist = d.length();
        sp.resetFallDistance();
        com.mattermod.trait.MobilityEffects.markSoftLanding(sp);
        if (dist < .35 || sl.getGameTime() - b.landingSince > 80) {
            sp.setDeltaMovement(Vec3.ZERO);
            sp.hurtMarked = true;
            sl.playSound(null, sp.blockPosition(), SoundEvents.AMETHYST_BLOCK_STEP, SoundSource.PLAYERS, .8f, .8f);
            b.discard();
            return;
        }
        double speed = Math.min(1.1, .12 + dist * .28);
        sp.setDeltaMovement(sp.getDeltaMovement().lerp(d.scale(speed / dist), .5));
        sp.hurtMarked = true;
    }

    /** Straight reel toward the hook, hanging there once close. */
    static void reel(BeamBallEntity b, ServerPlayer sp, Vec3 d) {
        double dist = d.length();
        if (dist <= 1.5) b.reelFull = false; // arrived: hang at the hook
        if (dist > 1.5) {
            double pull = 0.18;
            Vec3 v = sp.getDeltaMovement().scale(0.92).add(d.normalize().scale(pull));
            if (v.length() > 1.6) v = v.normalize().scale(1.6);
            sp.setDeltaMovement(v);
        } else {
            Vec3 up =
                    com.mattermod.trait.MovementPhysics.vector(
                            com.mattermod.trait.MovementPhysics.down(sp).getOpposite());
            Vec3 v = sp.getDeltaMovement().scale(.5);
            sp.setDeltaMovement(
                    sp.onGround() ? v.subtract(up.scale(v.dot(up))) : v.add(up.scale(.08)));
        }
        sp.hurtMarked = true;
        sp.resetFallDistance();
        com.mattermod.trait.MobilityEffects.markSoftLanding(sp);
    }

    /** RMB let go while hooked to a block: Slingshot fires you past the hook. */
    static void letGo(BeamBallEntity b, ServerPlayer sp, ServerLevel sl) {
        Vec3 hands = sp.getEyePosition();
        Vec3 d = b.position().subtract(hands);
        int sling = 3;
        if (sling > 0 && d.length() > 2) {
            double power = sling >= 2 ? 1.9 : 1.4;
            sp.setDeltaMovement(
                    sp.getDeltaMovement()
                            .add(d.normalize().scale(power))
                            .add(
                                    com.mattermod.trait.MovementPhysics.vector(
                                                    com.mattermod.trait.MovementPhysics.down(sp)
                                                            .getOpposite())
                                            .scale(.45)));
            sp.hurtMarked = true;
            com.mattermod.trait.MobilityEffects.markSoftLanding(sp);
            if (sling >= 3) com.mattermod.trait.MobilityEffects.markNoFall(sp);
            sl.playSound(
                    null,
                    sp.getX(),
                    sp.getY(),
                    sp.getZ(),
                    SoundEvents.CROSSBOW_SHOOT,
                    SoundSource.PLAYERS,
                    0.8f,
                    0.7f);
            sl.sendParticles(
                    ParticleTypes.CLOUD,
                    sp.getX(),
                    sp.getY() + 0.5,
                    sp.getZ(),
                    8,
                    0.2,
                    0.2,
                    0.2,
                    0.05);
        }
    }

    /**
     * Hammer Throw: let go and the whirled mob flies where you look, hurting itself on what it
     * hits.
     */
    static void hammerThrow(BeamBallEntity b, ServerPlayer sp, ServerLevel sl) {
        Vec3 look = sp.getLookAngle();
        b.hooked.setDeltaMovement(look.scale(1.9).add(0, 0.35, 0));
        b.hooked.hurtMarked = true;
        if (b.hooked instanceof LivingEntity le) WorldEffects.thrown(sl, le, sp);
        sl.playSound(
                null,
                sp.getX(),
                sp.getY(),
                sp.getZ(),
                SoundEvents.BREEZE_SHOOT,
                SoundSource.PLAYERS,
                1f,
                0.7f);
    }

    /** Where the hook sits on what it caught: the struck spot, turning with the body. */
    static Vec3 hookPoint(BeamBallEntity b) {
        Entity e = b.hooked;
        if (b.hookOffset == null)
            return new Vec3(e.getX(), e.getY(e instanceof LivingEntity ? 0.6 : 0.5), e.getZ());
        return e.position().add(b.hookOffset.yRot(-turn(e)));
    }

    /** The body's facing in radians (a creature's body, anything else its own yaw). */
    private static float turn(Entity e) {
        return (float) Math.toRadians(e instanceof LivingEntity le ? le.yBodyRot : e.getYRot());
    }

    private static Vec3 unturn(Vec3 v, float yaw) {
        return v.yRot(yaw);
    }

    /** Tether: the hook catches a creature or item. */
    static void catchEntity(BeamBallEntity b, Entity e, ServerPlayer owner) {
        // the hook holds on where it struck the body, not at its middle
        Vec3 from = b.position(), to = from.add(b.getDeltaMovement());
        var box = e.getBoundingBox();
        Vec3 at = box.clip(from, to).or(() -> box.inflate(0.3).clip(from, to))
                .orElse(new Vec3(Math.max(box.minX, Math.min(box.maxX, from.x)),
                        Math.max(box.minY, Math.min(box.maxY, from.y)),
                        Math.max(box.minZ, Math.min(box.maxZ, from.z))));
        b.hookOffset = unturn(at.subtract(e.position()), turn(e));
        b.hooked = e;
        b.getEntityData().set(b.STUCK, true);
        b.setDeltaMovement(Vec3.ZERO);
        b.ropeStart = owner.position().add(0, owner.getBbHeight() + 0.1, 0);
        b.lifeTicks = 0;
        b.hookAt = b.level().getGameTime() + HOOK_DELAY;
        b.hookPitch = 1.5f;
        // Weightless: a hooked mob floats helplessly
        int weightless = b.rank("mobility.weightless");
        if (weightless > 0
                && e instanceof LivingEntity le
                && !(e instanceof net.minecraft.world.entity.player.Player)) {
            le.addEffect(
                    new net.minecraft.world.effect.MobEffectInstance(
                            net.minecraft.world.effect.MobEffects.LEVITATION,
                            weightless >= 2 ? 100 : 60,
                            0));
        }
        if (b.has("mobility.singularity"))
            WorldEffects.gravityWell((ServerLevel) b.level(), b.position(), 60, owner);
    }
}
