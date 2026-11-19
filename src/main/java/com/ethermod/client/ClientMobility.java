package com.ethermod.client;

import com.ethermod.beam.BeamBallEntity;
import com.ethermod.manipulator.Manipulator;
import com.ethermod.network.ModNetwork;
import com.ethermod.network.payload.ModulePayloads;
import com.ethermod.trait.*;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;

import java.util.*;

public final class ClientMobility {
    private ClientMobility() {}

    public static final Deque<Vec3> TRAIL = new ArrayDeque<>();
    public static float trailAlpha;
    private static String dimension = "";
    private static boolean jumpWas, airJumped, wasGrounded, coyote;
    private static long groundedAt = -100, landedAt = -100, freeUntil;
    private static int jumpChain, motionWas = -1;
    private static double hopSpeed, airLevel, skateSpeed;

    /** Gravity Skate: the speed running builds to, and the most a landing can carry (b/tick). */
    private static final double SKATE_MAX = .48, SKATE_CARRY = .9;

    private static net.minecraft.world.phys.Vec3 skateVel = net.minecraft.world.phys.Vec3.ZERO;
    private static int hopChain, jumpCharge, chargeGrace;

    /** Pile Driver: ticks of Sneak for a fully charged jump. */
    private static final int JUMP_CHARGE_FULL = 20;
    private static long lastHop = -100;

    /** Bunny Hop speeds (blocks per tick): a sprint jump, the step per hop, the cap. */
    private static final double HOP_BASE = .36, HOP_STEP = .055, HOP_CAP = .75;
    private static boolean airArmed, airSuspended;
    private static LocalPlayer movementOwner;
    private static net.minecraft.core.Direction airGravity;

    private static void clearAirwalk() {
        airArmed = false;
        airSuspended = false;
        wasGrounded = false;
        coyote = false;
        dropping = false;
        AirwalkFloor.armed = false;
    }

    /** Airwalk: how long Sneak has been held, and whether a held Sneak has dropped the floor. */
    private static int sneakTicks;

    private static boolean dropping, sneakWas;
    private static double stepDistance;

    /** Ticks of Sneak that make a hold (fall until released) rather than a tap (one block down). */
    private static final int DROP_HOLD = 6;

    /** True while the local player stands on the Airwalk floor (the tile renderer reads it). */
    public static boolean onAirFloor() {
        return airSuspended;
    }

    /** The floor's height along "up", and whether it is out (the tile renderer reads them). */
    public static boolean airFloorArmed() {
        return airArmed;
    }

    public static double airFloorLevel() {
        return airLevel;
    }

    public static void gravity(int entity, int direction, long start) {
        var mc = Minecraft.getInstance();
        if (mc.level == null) return;
        var e = mc.level.getEntity(entity);
        if (e != null) {
            if (e == mc.player && MovementPhysics.down(mc.player).get3DDataValue() != direction)
                clearAirwalk();
            if (e instanceof net.minecraft.world.entity.player.Player player)
                MovementPhysics.beginTurn(
                        player, net.minecraft.core.Direction.from3DDataValue(direction), start);
            e.getPersistentData().putInt("mm_gravity", direction);
            e.refreshDimensions();
            e.setNoGravity(direction != 0);
        }
    }

    public static void ropeTick(BeamBallEntity hook) {
        var mc = Minecraft.getInstance();
        var p = mc.player;
        if (p == null || hook.getOwner() != p) return;
        if (p.input.jumping && !jumpWas && !p.onGround() && mc.gameMode != null) {
            if (Traits.has(Manipulator.stack(p), "gravitic.freeflight")
                    && p.distanceToSqr(hook) > 4) freeUntil = p.level().getGameTime() + 100;
            mc.gameMode.releaseUsingItem(p);
        }
    }

    public static void onHookGone(BeamBallEntity hook) {}

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post ev) {
        var mc = Minecraft.getInstance();
        var p = mc.player;
        if (p == null || mc.level == null) {
            TRAIL.clear();
            dimension = "";
            movementOwner = null;
            airGravity = null;
            clearAirwalk();
            return;
        }
        if (mc.isPaused()) return;
        String dim = mc.level.dimension().location().toString();
        if (!dim.equals(dimension)) {
            TRAIL.clear();
            dimension = dim;
            airArmed = false;
            airSuspended = false;
            airJumped = false;
            freeUntil = 0;
            motionWas = -1;
        }
        var gravity = MovementPhysics.down(p);
        if (movementOwner != p || airGravity != gravity) {
            clearAirwalk();
            movementOwner = p;
            airGravity = gravity;
            p.setOnGround(false);
        }
        var st = Manipulator.stack(p);
        long now = mc.level.getGameTime();
        // under sideways gravity, view bobbing and hand sway follow walking in your own floor
        // plane (vanilla only counts world-horizontal movement, so walking "up" a wall was still)
        if (gravity != net.minecraft.core.Direction.DOWN) {
            var moved = p.position().subtract(p.xo, p.yo, p.zo);
            double h = MovementPhysics.local(p, moved).horizontalDistance();
            p.walkDist = p.walkDistO + (float) h * .6f;
            float f = p.onGround() && !p.isDeadOrDying() ? (float) Math.min(.1, h) : 0;
            p.bob = p.oBob + (f - p.oBob) * .4f;
        }
        boolean jump = mc.screen == null && mc.options.keyJump.isDown();
        int motion =
                (jump ? 1 : 0)
                        | (p.isShiftKeyDown() ? 2 : 0)
                        | (p.isSprinting() ? 4 : 0)
                        | (p.input.forwardImpulse > 0 ? 8 : 0)
                        | (p.input.forwardImpulse < 0 ? 16 : 0)
                        | (p.input.leftImpulse > 0 ? 32 : 0)
                        | (p.input.leftImpulse < 0 ? 64 : 0);
        p.getPersistentData().putInt("mm_motion", motion);
        if (motion != motionWas) {
            ModNetwork.send(new ModulePayloads.Motion(motion));
            motionWas = motion;
        }
        // Standing on the Airwalk floor: it holds you like a block (AirwalkFloor), so onGround
        // is true there but nothing solid is under the feet.
        boolean support = MovementPhysics.hasRealSupport(p);
        airSuspended = airArmed && p.onGround() && !support;
        if (airSuspended) p.resetFallDistance();
        boolean realGround = p.onGround() && support;
        // the floor counts as ground for jumps, hops and coyote time
        if (realGround || airSuspended) {
            groundedAt = now;
            airJumped = false;
            // Freeflight steers the flight after a hook; it ends when you land (it bent later
            // jumps toward where you looked, so they came out lower)
            freeUntil = 0;
            if (!wasGrounded) landedAt = now;
        }
        if (realGround) {
            airArmed = true;
            airLevel =
                    p.position().dot(MovementPhysics.vector(MovementPhysics.down(p).getOpposite()));
        }
        boolean pressed = jump && !jumpWas;
        // Coyote time: a jump pressed just after walking off a ledge still jumps. Only once, and
        // never after a jump of your own (that would be a free second jump).
        var upward = MovementPhysics.vector(MovementPhysics.down(p).getOpposite());
        if (realGround || airSuspended) coyote = true;
        else if (wasGrounded && p.getDeltaMovement().dot(upward) > .1) coyote = false;
        if (pressed && now - groundedAt <= 3) {
            if (!realGround
                    && !airSuspended
                    && coyote
                    && Traits.hasLine(st, Traits.PNEUMATIC)
                    && !p.isInWater()
                    && !p.isPassenger()) {
                coyote = false;
                var v = p.getDeltaMovement();
                p.setDeltaMovement(MovementPhysics.withUp(p, v, .42));
            }
            if (Traits.has(st, "pneumatic.triple_jump")) {
                jumpChain = now - landedAt <= 5 ? (jumpChain % 3) + 1 : 1;
                var v = p.getDeltaMovement();
                p.setDeltaMovement(MovementPhysics.withUp(p, v, 0.42 + 0.18 * (jumpChain - 1)));
            }
        }
        // Pile Driver: Sneak on real ground charges a jump (full in 1 s); jumping unleashes it, up
        // to about three times as high. A short Sneak doesn't count; the charge waits a moment
        // after Sneak is let go.
        boolean sneaking = mc.screen == null && mc.options.keyShift.isDown();
        if (Traits.has(st, "pneumatic.ground_slam")) {
            if (sneaking && realGround) {
                jumpCharge = Math.min(JUMP_CHARGE_FULL, jumpCharge + 1);
                if (jumpCharge == JUMP_CHARGE_FULL - 1)
                    mc.level.playLocalSound(
                            p.getX(),
                            p.getY(),
                            p.getZ(),
                            com.ethermod.sound.ModSounds.WORLD_NOTE_BLOCK_CHIME,
                            net.minecraft.sounds.SoundSource.PLAYERS,
                            .35f,
                            1.6f,
                            false);
            }
            if (pressed && jumpCharge >= 5 && (realGround || now - groundedAt <= 1)) {
                double c = jumpCharge / (double) JUMP_CHARGE_FULL;
                p.setDeltaMovement(MovementPhysics.withUp(p, p.getDeltaMovement(), .42 * Math.sqrt(1 + 2 * c)));
                mc.level.playLocalSound(
                        p.getX(),
                        p.getY(),
                        p.getZ(),
                        com.ethermod.sound.ModSounds.WORLD_BREEZE_JUMP,
                        net.minecraft.sounds.SoundSource.PLAYERS,
                        .6f,
                        1.2f - .3f * (float) c,
                        false);
                jumpCharge = 0;
            } else if (sneaking) chargeGrace = 0;
            else if (++chargeGrace > 5) jumpCharge = 0; // let go of Sneak a moment before jumping
        } else jumpCharge = 0;
        // Bunny Hop: each jump made within a moment of landing adds a step of speed, from a
        // normal sprint jump up to a cap after about eight hops. Holding Jump hops too. The
        // chain breaks if you wait on the ground, or lose most of your speed (a wall).
        boolean hopJump =
                (pressed && now - groundedAt <= 3)
                        || (jump && wasGrounded && !realGround && p.getDeltaMovement().y > .2);
        if (hopJump && now - lastHop > 3 && Traits.has(st, "pneumatic.bunny_hop")) {
            double speed = MovementPhysics.local(p, p.getDeltaMovement()).horizontalDistance();
            boolean chained = now - landedAt <= 5 && hopChain > 0 && speed > hopSpeed * .55;
            hopChain = chained ? hopChain + 1 : 1;
            lastHop = now;
            if (hopChain == 1) hopSpeed = Math.max(speed, HOP_BASE);
            else {
                hopSpeed = Math.max(speed, Math.min(HOP_CAP, HOP_BASE + HOP_STEP * (hopChain - 1)));
                var wish = MovementPhysics.rotate(wish(p), MovementPhysics.down(p));
                if (wish.lengthSqr() > 0) {
                    var up = MovementPhysics.vector(MovementPhysics.down(p).getOpposite());
                    p.setDeltaMovement(
                            wish.normalize().scale(hopSpeed)
                                    .add(up.scale(p.getDeltaMovement().dot(up))));
                }
            }
        }
        if (pressed
                && !realGround
                && now - groundedAt > 3
                && !airJumped
                && Traits.hasLine(st, Traits.PNEUMATIC)
                && !p.isInWater()
                && !p.isPassenger()
                && !p.isFallFlying()) {
            airJumped = true;
            var v = p.getDeltaMovement();
            p.setDeltaMovement(MovementPhysics.withUp(p, v, 0.55));
            ModNetwork.send(new ModulePayloads.AirJump());
        }
        if (!p.onGround() && Traits.hasCommon(st, Traits.PNEUMATIC, 0) && !p.isFallFlying() && !p.isInWater()) {
            // Bullet Time: charging any weapon in the air (bow, crossbow, trident, modded ones,
            // the Manipulator's shots) slows the fall even more, no Jump needed
            // it lasts half a second past the release, so a shot's recoil (a Gust Shot fired
            // upward) can't slam you into the ground
            if (chargingWeapon(p)) bulletUntil = now + 10;
            double cap = now < bulletUntil ? -.03 : jump ? -.08 : Double.NEGATIVE_INFINITY;
            var up = MovementPhysics.vector(MovementPhysics.down(p).getOpposite());
            if (p.getDeltaMovement().dot(up) < cap)
                p.setDeltaMovement(MovementPhysics.withUp(p, p.getDeltaMovement(), cap));
        }
        if (!p.onGround() && !p.isFallFlying()) {
            var v = p.getDeltaMovement();
            var wish = MovementPhysics.rotate(wish(p), MovementPhysics.down(p));
            if (now < freeUntil && wish.lengthSqr() > 0) {
                var steer =
                        p.getLookAngle()
                                .scale(p.input.forwardImpulse)
                                .add(wish.scale(Math.abs(p.input.leftImpulse)));
                if (steer.lengthSqr() > 0)
                    p.setDeltaMovement(
                            v.lerp(steer.normalize().scale(v.length()), 0.3)
                                    .normalize()
                                    .scale(v.length()));
            } else if (Traits.hasCommon(st, Traits.PNEUMATIC, 1) && wish.lengthSqr() > 0)
                p.setDeltaMovement(v.add(wish.scale(.035)));
        }
        // Airwalk's invisible floor counts as ground for the skate
        boolean skateGround = realGround || (airSuspended && p.onGround());
        // Gravity Skate (Hermes Boots style): running builds up to about twice sprint speed over a
        // second; landing faster than that (a tether swing or fling) keeps the extra speed and
        // lets it bleed off gently. Steering blends, so turns stay smooth.
        if (Traits.has(st, "gravitic.gravity_skate") && skateGround && wish(p).lengthSqr() > 0) {
            var up = MovementPhysics.vector(MovementPhysics.down(p).getOpposite());
            var v = p.getDeltaMovement();
            var flat = v.subtract(up.scale(v.dot(up)));
            double have = flat.length();
            if (skateSpeed <= 0) {
                skateSpeed = Math.max(.2, Math.min(have, SKATE_CARRY));
                skateVel = flat;
            }
            if (p.horizontalCollision) { // ran into a wall: start over
                skateSpeed = .2;
                skateVel = flat;
            }
            if (have > skateSpeed + .05) skateSpeed = Math.min(SKATE_CARRY, have); // a fling lands
            skateSpeed =
                    skateSpeed < SKATE_MAX
                            ? Math.min(SKATE_MAX, skateSpeed + (p.isSprinting() ? .016 : .008))
                            : Math.max(SKATE_MAX, skateSpeed - .006);
            var direction = MovementPhysics.rotate(wish(p), MovementPhysics.down(p)).normalize();
            var want = direction.scale(skateSpeed);
            // blend from last tick's skate velocity (friction already ate into the real one)
            skateVel = skateVel.lerp(want, .35);
            p.setDeltaMovement(skateVel.add(up.scale(v.dot(up))));
            if (skateSpeed > .34 && now % 2 == 0)
                mc.level.addParticle(
                        net.minecraft.core.particles.ParticleTypes.CLOUD,
                        p.getX() - direction.x * 1.2,
                        p.getY() + .1,
                        p.getZ() - direction.z * 1.2,
                        0,
                        .02,
                        0);
        } else if (skateGround) skateSpeed = 0;

        // Airwalk: walking off a ledge keeps your height on an invisible floor. Jumping drops it.
        // Tap Sneak to step one block down; hold Sneak to fall until you let go, where a
        // new floor appears under you.
        boolean sneak = mc.screen == null && mc.options.keyShift.isDown();
        if (Traits.has(st, "gravitic.airwalk")
                && !p.getAbilities().flying
                && !p.isFallFlying()
                && !p.isInWater()
                && !p.isPassenger()
                && !p.isSpectator()) {
            var up = MovementPhysics.vector(MovementPhysics.down(p).getOpposite());
            double level = p.position().dot(up);
            // a jump drops the floor (it comes back once you stand on real ground again)
            if (pressed) {
                airArmed = false;
                dropping = false;
            }
            sneakTicks = sneak ? sneakTicks + 1 : 0;
            if (sneak && sneakTicks == DROP_HOLD && !realGround && (airArmed || airSuspended)) {
                dropping = true;
                airArmed = false;
            }
            if (!sneak && sneakWas) {
                if (dropping) {
                    dropping = false;
                    airArmed = true;
                    airLevel = level;
                } else if (airArmed && !realGround) airLevel -= 1;
            }
            if (airSuspended) {
                // amethyst steps on the floor, at vanilla's step rhythm
                stepDistance += p.position().subtract(p.xo, p.yo, p.zo).length() * .6;
                if (stepDistance > 1) {
                    stepDistance = 0;
                    mc.level.playLocalSound(
                            p.getX(),
                            p.getY(),
                            p.getZ(),
                            com.ethermod.sound.ModSounds.WORLD_AMETHYST_BLOCK_STEP,
                            net.minecraft.sounds.SoundSource.PLAYERS,
                            .25f,
                            1.15f,
                            false);
                }
            } else stepDistance = .7;
        } else {
            airArmed = false;
            airSuspended = false;
            dropping = false;
        }
        AirwalkFloor.owner = p;
        AirwalkFloor.armed = airArmed && !dropping;
        AirwalkFloor.level = airLevel;
        sneakWas = sneak;

        if (Traits.hasCommon(st, Traits.PHOTONIC, 0)
                && realGround
                && (TRAIL.isEmpty() || TRAIL.peekFirst().distanceToSqr(p.position()) > 4)) {
            TRAIL.addFirst(p.position());
            while (TRAIL.size() > 128) TRAIL.removeLast();
        }
        boolean show = Traits.hasCommon(st, Traits.PHOTONIC, 0) && p.isShiftKeyDown();
        trailAlpha = Math.max(0, Math.min(1, trailAlpha + (show ? .12f : -.12f)));
        wasGrounded = realGround || airSuspended;
        jumpWas = jump;
    }

    private static Vec3 wish(LocalPlayer p) {
        double yaw = Math.toRadians(p.getYRot());
        return new Vec3(
                        p.input.leftImpulse * Math.cos(yaw)
                                - p.input.forwardImpulse * Math.sin(yaw),
                        0,
                        p.input.forwardImpulse * Math.cos(yaw)
                                + p.input.leftImpulse * Math.sin(yaw))
                .normalize();
    }

    /** Drawing a bow or crossbow, raising a trident (or a modded weapon that does the same), or charging a Manipulator shot. */
    private static long bulletUntil;

    static boolean chargingWeapon(net.minecraft.world.entity.player.Player p) {
        if (com.ethermod.beam.BeamRenderState.chargeMode != null) return true;
        if (!p.isUsingItem()) return false;
        var use = p.getUseItem();
        if (use.getItem() instanceof com.ethermod.item.EtherManipulatorItem) return true;
        var anim = use.getUseAnimation();
        return anim == net.minecraft.world.item.UseAnim.BOW
                || anim == net.minecraft.world.item.UseAnim.CROSSBOW
                || anim == net.minecraft.world.item.UseAnim.SPEAR;
    }
}
