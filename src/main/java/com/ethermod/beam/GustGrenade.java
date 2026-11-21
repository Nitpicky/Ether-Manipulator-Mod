package com.ethermod.beam;

import com.ethermod.network.payload.LeftClickFxPayload;
import com.ethermod.trait.Stasis;
import com.ethermod.trait.Allegiance;
import com.ethermod.trait.Traits;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Pneumatic left click: the Stasis Grenade. It latches onto the first block or creature it
 * touches, ticks for 1.5 s, then bursts: up to 4 damage and a push away from it in a 3-block
 * radius. A creature it sticks to is held in Stasis until the burst, storing every knockback it
 * takes, and all of it lands at once with the burst. You get pushed too (no damage), so it
 * doubles as a jump. With Bank Shot it ricochets once first.
 *
 * <p>No entity: the grenade is a point simulated on the server and drawn with particles.
 */
public final class GustGrenade {

    private GustGrenade() {}

    public static final int FUSE = 30;
    /** The fuse once it sticks to a creature: the Stasis hold, long enough for a few hits. */
    public static final int STASIS_FUSE = 60;

    /** A grenade lying still is re-sent to clients this often, in ticks (they drop it after 20). */
    private static final int RESEND = 5;
    public static final double RADIUS = 3;
    public static final float DAMAGE = 4;

    /** For the playtest: bursts so far. */
    public static int bursts;

    private static int nextId;

    private static final class Grenade {
        final ServerPlayer owner;
        final boolean ricochet;
        final int id = nextId++;
        Vec3 pos, vel, offset;
        Entity stuck;
        boolean landed;
        int age, fuse = FUSE, bounces, windup;
        /** What the clients were last told: where, and at which age (FX packet throttling). */
        Vec3 sentPos;
        int sentAge;
        boolean sentLanded;

        Grenade(ServerPlayer owner, ItemStack traits) {
            this.owner = owner;
            this.ricochet = Traits.has(traits, "kinetic.bank_shot");
        }
    }

    private static final Map<ServerLevel, List<Grenade>> LIVE = new HashMap<>();

    /** For the playtest: cut every live Stasis hold short so the burst lands within the tour. */
    public static void hurry() {
        for (List<Grenade> l : LIVE.values()) for (Grenade g : l) g.fuse = Math.min(g.fuse, 4);
    }

    /**
     * Ticks between the click and the grenade leaving: the Manipulator's lob animation gathers for
     * these, then flicks it out (animations: "grenade", flick at 0.1 s).
     */
    public static final int WINDUP = 2;

    public static void throwFrom(ServerPlayer p, ItemStack stack) {
        var g = new Grenade(p, stack);
        g.windup = WINDUP;
        LIVE.computeIfAbsent(p.level(), k -> new ArrayList<>()).add(g);
    }

    /** The flick: aimed where the owner looks now, not where they looked at the click. */
    private static void launch(Grenade g) {
        ServerPlayer p = g.owner;
        Vec3 look = p.getLookAngle();
        g.pos = p.getEyePosition().add(look.scale(0.8)).add(0, -0.15, 0);
        g.vel = look.scale(1.3).add(p.getDeltaMovement().multiply(1, 0, 1));
        p.level()
                .playSound(
                        null,
                        p.getX(),
                        p.getEyeY(),
                        p.getZ(),
                        com.ethermod.sound.ModSounds.WORLD_BREEZE_SHOOT,
                        SoundSource.PLAYERS,
                        0.6f,
                        1.8f);
    }

    /** Detonator: bursts this player's grenades still in the air; true if any did. */
    public static boolean detonateFor(ServerPlayer p, ItemStack stack) {
        if (!Traits.has(stack, "kinetic.detonator")) return false;
        var list = LIVE.get(p.level());
        if (list == null) return false;
        boolean any = false;
        for (var it = list.iterator(); it.hasNext(); ) {
            Grenade g = it.next();
            if (g.owner != p || g.windup > 0 || g.landed) continue;
            burst(p.level(), g);
            it.remove();
            any = true;
        }
        return any;
    }

    @SubscribeEvent
    public static void tick(LevelTickEvent.Post ev) {
        if (!(ev.getLevel() instanceof ServerLevel sl)) return;
        var list = LIVE.get(sl);
        if (list == null || list.isEmpty()) return;
        list.removeIf(g -> step(sl, g));
    }

    @SubscribeEvent
    public static void stop(ServerStoppingEvent ev) {
        LIVE.clear();
    }

    /** One tick of one grenade; true once it has burst. */
    private static boolean step(ServerLevel sl, Grenade g) {
        if (g.windup > 0) {
            if (g.owner.isRemoved() || g.owner.level() != sl) return true;
            if (--g.windup > 0) return false;
            launch(g);
        }
        g.age++;
        if (!g.landed) {
            Vec3 next = g.pos.add(g.vel);
            if (com.ethermod.config.ServerConfig.gustCutsFoliage()) FoliageCut.sweep(sl, g.owner, g.pos, next, false); // wind cuts foliage loose
            var block =
                    sl.clip(
                            new ClipContext(
                                    g.pos,
                                    next,
                                    ClipContext.Block.COLLIDER,
                                    ClipContext.Fluid.NONE,
                                    g.owner));
            Vec3 end = block.getType() == HitResult.Type.MISS ? next : block.getLocation();
            var mob =
                    ProjectileUtil.getEntityHitResult(
                            sl,
                            g.owner,
                            g.pos,
                            end,
                            new AABB(g.pos, end).inflate(0.6),
                            e ->
                                    e instanceof LivingEntity le
                                            && !e.isSpectator()
                                            && Allegiance.enemyIgnoringPvp(g.owner, le)
                                            && affects(g.owner, le),
                            0.3f);
            if (mob != null) {
                g.stuck = mob.getEntity();
                // latch where it touched, on the creature's skin (its box), and turn with it
                // (this lookup only names the creature: its location is the feet, so find
                // where the throw's path actually meets the creature's box)
                AABB box = g.stuck.getBoundingBox();
                Vec3 hit =
                        box.inflate(0.3)
                                .clip(g.pos, end)
                                .orElseGet(() -> box.getCenter().add(g.pos).scale(0.5));
                Vec3 skin =
                        new Vec3(
                                Math.clamp(hit.x, box.minX, box.maxX),
                                Math.clamp(hit.y, box.minY, box.maxY),
                                Math.clamp(hit.z, box.minZ, box.maxZ));
                g.offset = skin.subtract(g.stuck.position()).yRot((float) Math.toRadians(bodyYaw(g.stuck)));
                g.pos = skin;
                land(sl, g);
                // Stasis: the creature it sticks to is held until the burst; every knockback it
                // takes meanwhile is stored and lands together with the burst
                if (g.stuck instanceof LivingEntity le) {
                    g.fuse = STASIS_FUSE; // time to set up hits on it
                    Stasis.stasis(le, g.fuse + 5);
                    if (Stasis.inStasis(le)) Stasis.expectBurst(le, heldPush(g, le));
                }
            } else if (block.getType() != HitResult.Type.MISS) {
                var n = Vec3.atLowerCornerOf(block.getDirection().getUnitVec3i());
                if (g.ricochet && g.bounces == 0) {
                    g.bounces++;
                    // reflect off the face, losing some speed
                    g.vel = g.vel.subtract(n.scale(2 * g.vel.dot(n))).scale(0.6);
                    g.pos = block.getLocation().add(n.scale(0.1));
                    sl.playSound(
                            null,
                            g.pos.x,
                            g.pos.y,
                            g.pos.z,
                            com.ethermod.sound.ModSounds.WORLD_BREEZE_SHOOT,
                            SoundSource.PLAYERS,
                            0.5f,
                            1.6f);
                } else {
                    g.pos = block.getLocation().add(n.scale(0.15));
                    land(sl, g);
                }
            } else {
                g.pos = next;
                g.vel = g.vel.scale(0.99).add(0, -0.05, 0);
                if (g.age > 80) land(sl, g);
            }
        } else {
            if (g.stuck != null) {
                if (g.stuck.isAlive() && g.stuck.level() == sl)
                    g.pos =
                            g.stuck.position()
                                    .add(g.offset.yRot((float) -Math.toRadians(bodyYaw(g.stuck))));
                else g.stuck = null;
            }
            g.fuse--;
            // ticks faster as it nears the burst
            int every = g.fuse > 12 ? 6 : 3;
            // held in Stasis, the creature's own quickening tick replaces the fuse's (StasisFx)
            boolean held = g.stuck instanceof LivingEntity le && Stasis.inStasis(le);
            if (held && g.fuse % 4 == 0) // the thrower may move: keep the arrow's burst current
                Stasis.expectBurst((LivingEntity) g.stuck, heldPush(g, (LivingEntity) g.stuck));
            if (!held && g.fuse % every == 0) {
                sl.playSound(
                        null,
                        g.pos.x,
                        g.pos.y,
                        g.pos.z,
                        com.ethermod.sound.ModSounds.WORLD_NOTE_BLOCK_HAT,
                        SoundSource.PLAYERS,
                        0.6f,
                        g.fuse > 12 ? 1.4f : 1.9f);
            }
        }
        // drawn on the clients like the right-click Gust swirl (LeftClickFx). In flight, or
        // riding a creature that moves, every tick; lying still (on a block, or on a creature
        // held in Stasis) only when it lands and then every few ticks: the client counts the
        // fuse down itself in between (LeftClickFxData.tick)
        if (!g.landed
                || !g.sentLanded
                || g.sentPos == null
                || g.pos.distanceToSqr(g.sentPos) > 1e-4
                || g.age - g.sentAge >= RESEND) {
            LeftClickFxData.send(
                    sl, LeftClickFxPayload.GRENADE, g.id, g.owner.getId(), g.pos, g.landed ? g.fuse : -1);
            g.sentPos = g.pos;
            g.sentAge = g.age;
            g.sentLanded = g.landed;
        }
        if (g.landed && g.fuse <= 0) {
            burst(sl, g);
            return true;
        }
        return false;
    }

    private static float bodyYaw(Entity e) {
        return e instanceof LivingEntity le ? le.yBodyRot : e.getYRot();
    }

    private static void land(ServerLevel sl, Grenade g) {
        g.landed = true;
        g.vel = Vec3.ZERO;
        sl.playSound(
                null,
                g.pos.x,
                g.pos.y,
                g.pos.z,
                com.ethermod.sound.ModSounds.WORLD_SLIME_ATTACK,
                SoundSource.PLAYERS,
                0.5f,
                1.6f);
    }

    private static void burst(ServerLevel sl, Grenade g) {
        bursts++;
        Vec3 at = g.pos;
        sl.sendParticles(ParticleTypes.GUST_EMITTER_LARGE, at.x, at.y, at.z, 1, 0, 0, 0, 0);
        LeftClickFxData.send(sl, LeftClickFxPayload.BURST, g.id, g.owner.getId(), at, 0);
        sl.playSound(
                null,
                at.x,
                at.y,
                at.z,
                com.ethermod.sound.ModSounds.WORLD_WIND_CHARGE_BURST,
                SoundSource.PLAYERS,
                1f,
                0.9f);
        for (Entity e : sl.getEntities((Entity) null, new AABB(at, at).inflate(RADIUS))) {
            Vec3 centre = e.position().add(0, e.getBbHeight() * 0.5, 0);
            double d = centre.distanceTo(at);
            if (d > RADIUS || e.isSpectator()) continue;
            Vec3 push = push(e, at);
            double near = 1 - d / RADIUS;
            if (e == g.owner) {
                // your own grenade launches you but never hurts you
                e.setDeltaMovement(e.getDeltaMovement().add(push));
                com.ethermod.util.MotionSync.sync(e);
            } else if (e instanceof LivingEntity le) {
                if (!Allegiance.enemyIgnoringPvp(g.owner, le) || !affects(g.owner, le)) continue;
                float dmg = DAMAGE * (float) (0.5 + 0.5 * near);
                if (Stasis.inStasis(le)) {
                    // the burst joins the stored push (aimed by the last hit); the hit lands while
                    // still held with its knockback dropped (vanilla knockback would halve the launch
                    // and cap its lift at 0.4), then the creature lets go
                    Stasis.burstInto(le, heldPush(g, le));
                    Stasis.hurtQuietly(le, sl.damageSources().playerAttack(g.owner), dmg);
                    Stasis.thaw(le);
                } else {
                    // hit first, push after: the hit's own knockback must not halve the push
                    le.hurt(sl.damageSources().playerAttack(g.owner), dmg);
                    Stasis.impulse(le, push);
                }
            } else if (e instanceof ItemEntity) {
                e.setDeltaMovement(e.getDeltaMovement().add(push.scale(0.6)));
                com.ethermod.util.MotionSync.sync(e);
            }
        }
    }

    /** The burst's push on {@code e}: away from the grenade, harder up close, and a little up. */
    private static Vec3 push(Entity e, Vec3 at) {
        Vec3 centre = e.position().add(0, e.getBbHeight() * 0.5, 0);
        double d = centre.distanceTo(at);
        double near = Math.max(0, 1 - d / RADIUS);
        Vec3 away = d < 0.01 ? new Vec3(0, 1, 0) : centre.subtract(at).normalize();
        return away.scale(0.5 + 1.1 * near).add(0, 0.35, 0);
    }

    /**
     * The burst's push on the creature it holds: straight away from where the grenade stuck on
     * the body (on its side, level; on top, down; underneath, up). This sets the launch direction;
     * hits stored meanwhile only add strength.
     */
    private static Vec3 heldPush(Grenade g, LivingEntity e) {
        Vec3 centre = e.position().add(0, e.getBbHeight() * 0.5, 0);
        Vec3 away = centre.subtract(g.pos);
        if (away.lengthSqr() < 1e-4) return new Vec3(0, 1.6, 0);
        return away.normalize().scale(1.6);
    }

    /** Other players only when the server allows it (PvP on, and the Stasis Grenade setting). */
    private static boolean affects(ServerPlayer owner, LivingEntity e) {
        if (!(e instanceof net.minecraft.world.entity.player.Player)) return true;
        return com.ethermod.config.ServerConfig.stasisGrenadePlayers()
                && owner.level().getServer().getGameRules().get(net.minecraft.world.level.gamerules.GameRules.PVP);
    }
}
