package com.ethermod.beam;

import com.ethermod.item.OrbMode;
import com.ethermod.manipulator.Manipulator;
import com.ethermod.trait.Traits;

import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
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
        Direction face = com.ethermod.trait.MovementPhysics.down(sp).getOpposite();
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
                com.ethermod.sound.ModSounds.WORLD_CHAIN_PLACE,
                SoundSource.PLAYERS,
                .6f,
                b.hookPitch - .2f); // 1.1 on blocks (FX bench), a touch higher on creatures
        // the End layer under the latch (FX bench)
        sl.playSound(
                null,
                b.getX(),
                b.getY(),
                b.getZ(),
                com.ethermod.sound.ModSounds.WORLD_END_PORTAL_FRAME_FILL,
                SoundSource.PLAYERS,
                .4f,
                1.3f);
        sl.sendParticles(ParticleTypes.CRIT, b.getX(), b.getY(), b.getZ(), 6, 0.1, 0.1, 0.1, 0.1);
        sl.sendParticles(ParticleTypes.REVERSE_PORTAL, b.getX(), b.getY(), b.getZ(), 10, 0.15, 0.15, 0.15, 0.02);
    }

    /** Reeling starts: the bobber's retrieve over a short End whoosh (the portal hum, cut at .5s). */
    private static void reelFx(BeamBallEntity b, ServerLevel sl) {
        sl.playSound(
                null,
                b.getX(),
                b.getY(),
                b.getZ(),
                com.ethermod.sound.ModSounds.WORLD_FISHING_BOBBER_RETRIEVE,
                SoundSource.PLAYERS,
                .4f,
                .8f);
        if (b.getOwner() instanceof ServerPlayer sp)
            sl.playSound(
                    null,
                    sp.getX(),
                    sp.getEyeY(),
                    sp.getZ(),
                    com.ethermod.sound.ModSounds.WORLD_PORTAL_TRIGGER,
                    SoundSource.PLAYERS,
                    .08f,
                    1.8f);
        Vec3 at = b.position();
        com.ethermod.trait.Scheduler.schedule(
                sl,
                10,
                () -> {
                    var stop =
                            new net.minecraft.network.protocol.game.ClientboundStopSoundPacket(
                                    com.ethermod.sound.ModSounds.WORLD_PORTAL_TRIGGER.location(),
                                    SoundSource.PLAYERS);
                    for (ServerPlayer p : sl.players())
                        if (p.position().distanceToSqr(at) < 64 * 64) p.connection.send(stop);
                });
    }

    /** The hook lets go of what it held: the chain's clink over a faint teleport. */
    static void unhookFx(BeamBallEntity b, ServerLevel sl) {
        if (!b.isStuck() || b.hookAt >= 0) return;
        sl.playSound(
                null,
                b.getX(),
                b.getY(),
                b.getZ(),
                com.ethermod.sound.ModSounds.WORLD_CHAIN_HIT,
                SoundSource.PLAYERS,
                .35f,
                1.2f);
        sl.playSound(
                null,
                b.getX(),
                b.getY(),
                b.getZ(),
                com.ethermod.sound.ModSounds.WORLD_ENDERMAN_TELEPORT,
                SoundSource.PLAYERS,
                .15f,
                1.8f);
        sl.sendParticles(ParticleTypes.REVERSE_PORTAL, b.getX(), b.getY(), b.getZ(), 8, 0.15, 0.15, 0.15, 0.02);
    }

    static boolean tetherTick(BeamBallEntity b) {
        if (!(b.getOwner() instanceof ServerPlayer sp)
                || !sp.isAlive()
                || sp.level() != b.level()) {
            b.discard();
            return false;
        }
        if (!Traits.hasLine(Manipulator.stack(sp), Traits.GRAVITIC)
                || com.ethermod.item.OrbMode.get(Manipulator.stack(sp)) != OrbMode.TETHER) {
            b.discard();
            return false;
        }
        // putting the Manipulator away lets go of the hook (and of whatever it holds)
        if (!b.isAnchor() && !Manipulator.isSummoned(sp)) {
            unhookFx(b, (ServerLevel) b.level());
            b.discard();
            return false;
        }
        ServerLevel sl = (ServerLevel) b.level();
        boolean held =
                sp.isUsingItem()
                        && sp.getUseItem().getItem()
                                instanceof com.ethermod.item.EtherManipulatorItem;
        // A jump cancels the tether, flying or caught (in the air, with the Slingshot fling)
        boolean jump = com.ethermod.trait.MovementPhysics.jump(sp);
        boolean jumped = jump && !b.jumpWas;
        b.jumpWas = jump;
        if (jumped && b.tickCount > 2) {
            if (b.isStuck() && b.hookAt < 0 && b.hooked == null) {
                if (sp.isShiftKeyDown() && b.has("gravitic.zipline")) {
                    com.ethermod.trait.Ziplines.pin(sp, b);
                    return false;
                }
                if (!sp.onGround()) OrbTether.letGo(b, sp, sl);
            }
            unhookFx(b, sl);
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
        if (b.reeling && !b.reelWas) reelFx(b, sl);
        b.reelWas = b.reeling;
        Vec3 hands = sp.getBoundingBox().getCenter();

        // A caught creature or item
        if (b.hooked != null) {
            if (!b.hooked.isAlive() || b.hooked.level() != sl) {
                b.discard();
                return false;
            }
            // Fish Out of Water: a water creature hauled out onto dry air
            if ((b.hooked instanceof net.minecraft.world.entity.animal.WaterAnimal
                            || b.hooked instanceof net.minecraft.world.entity.animal.axolotl.Axolotl)
                    && !b.hooked.isInWater())
                com.ethermod.advancement.ManipulatorTrigger.fire(sp, "fish_out");
            boolean mob =
                    b.hooked instanceof LivingEntity
                            && !(b.hooked instanceof net.minecraft.world.entity.player.Player)
                            && !b.hooked
                                    .getType()
                                    .is(net.neoforged.neoforge.common.Tags.EntityTypes.BOSSES);
            b.setPos(hookPoint(b));
            Vec3 d = b.position().subtract(hands);
            if (!b.reeling) return false;
            if (mob || b.hooked instanceof ItemEntity || b.hooked instanceof ExperienceOrb) {
                // the hooked thing comes to you; a tap brings it all the way
                if (d.length() > 2) {
                    b.hooked.setDeltaMovement(d.normalize().scale(-0.6).add(0, 0.1, 0));
                    b.hooked.needsSync = true;
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
        sp.needsSync = true;
        sp.resetFallDistance();
        com.ethermod.trait.MobilityEffects.markSoftLanding(sp);
    }

    /**
     * Each player's current hook (entity id). {@link #fire} is the only place hooks are made, so
     * this replaces scanning a 384-block box on every press. Checked on every lookup (a discarded
     * hook, a Zipline anchor or another level's entity no longer counts); cleared on logout and
     * server stop.
     */
    private static final java.util.Map<java.util.UUID, Integer> HOOKS = new java.util.HashMap<>();

    /** The player's hook, flying or caught (not a Zipline anchor); null if none. */
    public static BeamBallEntity activeHook(ServerPlayer sp) {
        Integer id = HOOKS.get(sp.getUUID());
        if (id == null) return null;
        if (sp.level().getEntity(id) instanceof BeamBallEntity e
                && e.getOwner() == sp
                && e.getMode() == OrbMode.TETHER
                && !e.isAnchor()
                && !e.isRemoved()) return e;
        HOOKS.remove(sp.getUUID());
        return null;
    }

    /** Logout: forget the player's hook. */
    public static void forget(java.util.UUID player) {
        HOOKS.remove(player);
    }

    public static void clear() {
        HOOKS.clear();
    }

    /** Left click: fires a new hook (the old one lets go). */
    public static void fire(ServerPlayer sp, net.minecraft.world.item.ItemStack stack) {
        ServerLevel sl = sp.level();
        var old = activeHook(sp);
        if (old != null) {
            unhookFx(old, sl);
            old.discard();
        }
        BeamBallEntity hook =
                new BeamBallEntity(
                        sl, sp, 0, com.ethermod.item.ManipulatorStage.get(stack), OrbMode.TETHER);
        hook.configure(stack);
        sl.addFreshEntity(hook);
        HOOKS.put(sp.getUUID(), hook.getId());
        sl.playSound(
                null,
                sp.getX(),
                sp.getEyeY(),
                sp.getZ(),
                com.ethermod.sound.ModSounds.WORLD_CROSSBOW_SHOOT,
                SoundSource.PLAYERS,
                0.6f,
                1.3f);
        // the End layer under the shot (FX bench)
        sl.playSound(
                null,
                sp.getX(),
                sp.getEyeY(),
                sp.getZ(),
                com.ethermod.sound.ModSounds.WORLD_CHORUS_FRUIT_TELEPORT,
                SoundSource.PLAYERS,
                0.22f,
                1.7f);
    }

    /** Ticks of right click that still count as a tap, and between the taps of a double tap. */
    public static final int TAP = 5, DOUBLE_TAP = 7;

    /**
     * Right click pressed with a hook out. A second press soon after the first is a double tap:
     * Blink or Gravity Shift, and true (nothing to hold). Otherwise it starts reeling.
     */
    public static boolean press(BeamBallEntity b, ServerPlayer sp) {
        long now = sp.level().getGameTime();
        boolean twice = now - b.lastPress <= DOUBLE_TAP;
        b.lastPress = now;
        b.idleTicks = 0;
        if (twice && b.isStuck() && b.hookAt < 0) {
            b.lastPress = -100;
            if (b.has("gravitic.blink")) {
                blink(b, sp);
                return true;
            }
            if (b.has("gravitic.gravity_shift") && !b.virtualHit && b.hooked == null && b.hookFace != null) {
                com.ethermod.trait.MovementPhysics.setGravity(sp, b.hookFace.getOpposite());
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
        ServerLevel sl = sp.level();
        if (b.hooked != null) {
            var dest = b.hooked.position().subtract(sp.getLookAngle().scale(1.2));
            if (sl.noCollision(sp, sp.getBoundingBox().move(dest.subtract(sp.position()))))
                blinkTo(sp, dest);
            b.discard();
            return;
        }
        Direction face = b.hookFace == null ? Direction.UP : b.hookFace;
        Vec3 dest = b.position().add(Vec3.atLowerCornerOf(face.getUnitVec3i()).scale(0.5));
        if (face.getAxis() != Direction.Axis.Y) dest = dest.add(0, -0.9, 0);
        var old = sp.position();
        for (int i = 0; i < 6; i++) {
            var at = dest.add(0, i * 0.5, 0);
            if (sl.noCollision(sp, sp.getBoundingBox().move(at.subtract(old)))) {
                blinkTo(sp, at);
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
                        .add(Vec3.atLowerCornerOf(face.getUnitVec3i()).scale(b.stuckBlock != null ? .5 : 0));
        Vec3 d = dest.subtract(sp.position());
        double dist = d.length();
        sp.resetFallDistance();
        com.ethermod.trait.MobilityEffects.markSoftLanding(sp);
        if (dist < .35 || sl.getGameTime() - b.landingSince > 80) {
            sp.setDeltaMovement(Vec3.ZERO);
            sp.needsSync = true;
            sl.playSound(null, sp.blockPosition(), com.ethermod.sound.ModSounds.WORLD_AMETHYST_BLOCK_STEP, SoundSource.PLAYERS, .8f, .8f);
            b.discard();
            return;
        }
        double speed = Math.min(1.1, .12 + dist * .28);
        sp.setDeltaMovement(sp.getDeltaMovement().lerp(d.scale(speed / dist), .5));
        sp.needsSync = true;
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
                    com.ethermod.trait.MovementPhysics.vector(
                            com.ethermod.trait.MovementPhysics.down(sp).getOpposite());
            Vec3 v = sp.getDeltaMovement().scale(.5);
            sp.setDeltaMovement(
                    sp.onGround() ? v.subtract(up.scale(v.dot(up))) : v.add(up.scale(.08)));
        }
        sp.needsSync = true;
        sp.resetFallDistance();
        com.ethermod.trait.MobilityEffects.markSoftLanding(sp);
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
                                    com.ethermod.trait.MovementPhysics.vector(
                                                    com.ethermod.trait.MovementPhysics.down(sp)
                                                            .getOpposite())
                                            .scale(.45)));
            sp.needsSync = true;
            com.ethermod.trait.MobilityEffects.markSoftLanding(sp);
            if (sling >= 3) com.ethermod.trait.MobilityEffects.markNoFall(sp);
            sl.playSound(
                    null,
                    sp.getX(),
                    sp.getY(),
                    sp.getZ(),
                    com.ethermod.sound.ModSounds.WORLD_CROSSBOW_SHOOT,
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
    }

    /** Blink: teleports the player to {@code at}, with portal particles and sounds at both ends. */
    static void blinkTo(ServerPlayer player, Vec3 at) {
        ServerLevel sl = player.level();
        Vec3 old = player.position();
        sl.sendParticles(ParticleTypes.PORTAL, old.x, old.y + 1, old.z, 32, .3, .7, .3, .15);
        player.teleportTo(
                sl, at.x, at.y, at.z, java.util.Set.<net.minecraft.world.entity.Relative>of(), player.getYRot(), player.getXRot(), false);
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
}
