package com.ethermod.beam;

import com.ethermod.network.payload.LeftClickFxPayload;
import com.ethermod.trait.RedesignEffects;
import com.ethermod.trait.Traits;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
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
 * Pneumatic left click: a blast grenade. It latches onto the first block or creature it touches,
 * ticks for 1.5 s, then bursts: up to 4 damage and a push away from it in a 3-block radius. You
 * get pushed too (no damage), so it doubles as a jump. With Bank Shot it ricochets once first.
 *
 * <p>No entity: the grenade is a point simulated on the server and drawn with particles.
 */
public final class GustGrenade {

    private GustGrenade() {}

    public static final int FUSE = 30;
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

        Grenade(ServerPlayer owner, ItemStack traits) {
            this.owner = owner;
            this.ricochet = Traits.has(traits, "kinetic.bank_shot");
        }
    }

    private static final Map<ServerLevel, List<Grenade>> LIVE = new HashMap<>();

    /**
     * Ticks between the click and the grenade leaving: the Manipulator's lob animation gathers for
     * these, then flicks it out (animations: "grenade", flick at 0.1 s).
     */
    public static final int WINDUP = 2;

    public static void throwFrom(ServerPlayer p, ItemStack stack) {
        var g = new Grenade(p, stack);
        g.windup = WINDUP;
        LIVE.computeIfAbsent(p.serverLevel(), k -> new ArrayList<>()).add(g);
    }

    /** The flick: aimed where the owner looks now, not where they looked at the click. */
    private static void launch(Grenade g) {
        ServerPlayer p = g.owner;
        Vec3 look = p.getLookAngle();
        g.pos = p.getEyePosition().add(look.scale(0.8)).add(0, -0.15, 0);
        g.vel = look.scale(1.3).add(p.getDeltaMovement().multiply(1, 0, 1));
        p.serverLevel()
                .playSound(
                        null,
                        p.getX(),
                        p.getEyeY(),
                        p.getZ(),
                        SoundEvents.BREEZE_SHOOT,
                        SoundSource.PLAYERS,
                        0.6f,
                        1.8f);
    }

    /** Detonator: bursts this player's grenades still in the air; true if any did. */
    public static boolean detonateFor(ServerPlayer p, ItemStack stack) {
        if (!Traits.has(stack, "kinetic.detonator")) return false;
        var list = LIVE.get(p.serverLevel());
        if (list == null) return false;
        boolean any = false;
        for (var it = list.iterator(); it.hasNext(); ) {
            Grenade g = it.next();
            if (g.owner != p || g.windup > 0 || g.landed) continue;
            burst(p.serverLevel(), g);
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
            FoliageCut.sweep(sl, g.owner, g.pos, next, false); // wind cuts foliage loose
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
                                            && RedesignEffects.enemy(g.owner, le));
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
            } else if (block.getType() != HitResult.Type.MISS) {
                var n = Vec3.atLowerCornerOf(block.getDirection().getNormal());
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
                            SoundEvents.BREEZE_SHOOT,
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
            if (g.fuse % every == 0) {
                sl.playSound(
                        null,
                        g.pos.x,
                        g.pos.y,
                        g.pos.z,
                        SoundEvents.NOTE_BLOCK_HAT.value(),
                        SoundSource.PLAYERS,
                        0.6f,
                        g.fuse > 12 ? 1.4f : 1.9f);
            }
        }
        // drawn on the clients like the right-click Gust swirl (LeftClickFx)
        LeftClickFxData.send(
                sl, LeftClickFxPayload.GRENADE, g.id, g.owner.getId(), g.pos, g.landed ? g.fuse : -1);
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
                SoundEvents.SLIME_ATTACK,
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
                SoundEvents.WIND_CHARGE_BURST.value(),
                SoundSource.PLAYERS,
                1f,
                0.9f);
        for (Entity e : sl.getEntities((Entity) null, new AABB(at, at).inflate(RADIUS))) {
            Vec3 centre = e.position().add(0, e.getBbHeight() * 0.5, 0);
            double d = centre.distanceTo(at);
            if (d > RADIUS || e.isSpectator()) continue;
            double near = 1 - d / RADIUS;
            Vec3 away = d < 0.01 ? new Vec3(0, 1, 0) : centre.subtract(at).normalize();
            Vec3 push = away.scale(0.5 + 1.1 * near).add(0, 0.35, 0);
            if (e == g.owner) {
                // your own grenade launches you but never hurts you
                e.setDeltaMovement(e.getDeltaMovement().add(push));
                e.hurtMarked = true;
            } else if (e instanceof LivingEntity le) {
                if (!RedesignEffects.enemy(g.owner, le)) continue;
                le.hurt(sl.damageSources().playerAttack(g.owner), DAMAGE * (float) (0.5 + 0.5 * near));
                RedesignEffects.impulse(le, push);
            } else if (e instanceof ItemEntity) {
                e.setDeltaMovement(e.getDeltaMovement().add(push.scale(0.6)));
                e.hurtMarked = true;
            }
        }
    }
}
