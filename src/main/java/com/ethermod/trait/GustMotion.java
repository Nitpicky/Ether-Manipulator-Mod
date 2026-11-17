package com.ethermod.trait;

import net.minecraft.server.level.*;
import net.minecraft.world.entity.*;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.*;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

import java.util.*;

public final class GustMotion {
    private static final class Motion {
        LivingEntity mob;
        ServerPlayer owner;
        Vec3 v, start;
        int ticks, bounces, cooldown;
        boolean rebound;

        Motion(LivingEntity m, ServerPlayer o, boolean r) {
            mob = m;
            owner = o;
            v = m.getDeltaMovement();
            start = m.position();
            ticks = 80;
            bounces = 6;
            rebound = r;
        }
    }

    private static final Map<UUID, Motion> ACTIVE = new HashMap<>();

    public static void track(LivingEntity e, ServerPlayer p, boolean rebound) {
        ACTIVE.put(e.getUUID(), new Motion(e, p, rebound));
    }

    @SubscribeEvent
    public static void tick(LevelTickEvent.Post ev) {
        if (!(ev.getLevel() instanceof ServerLevel sl)) return;
        var it = ACTIVE.values().iterator();
        while (it.hasNext()) {
            var m = it.next();
            var e = m.mob;
            if (e.level() != sl) continue;
            if (!e.isAlive() || --m.ticks <= 0 || !m.owner.isAlive()) {
                it.remove();
                continue;
            }
            if (m.cooldown > 0) m.cooldown--;
            // Gone With the Wind: a creature carried 10 blocks by one Gust
            if (!(e instanceof net.minecraft.world.entity.player.Player)
                    && e.position().subtract(m.start).horizontalDistanceSqr() >= 100)
                com.ethermod.advancement.ManipulatorTrigger.fire(m.owner, "gust_launch");
            var v = e.getDeltaMovement();
            if (m.cooldown == 0 && m.v.lengthSqr() > .16) {
                var hit =
                        sl.clip(
                                new ClipContext(
                                        e.position().add(0, e.getBbHeight() * .5, 0),
                                        e.position().add(m.v).add(0, e.getBbHeight() * .5, 0),
                                        ClipContext.Block.COLLIDER,
                                        ClipContext.Fluid.NONE,
                                        e));
                LivingEntity target = null;
                for (var other :
                        sl.getEntitiesOfClass(
                                LivingEntity.class,
                                e.getBoundingBox().inflate(.3),
                                x -> x != e && x != m.owner && x.isAlive())) {
                    target = other;
                    break;
                }
                if (hit.getType() == HitResult.Type.BLOCK || target != null) {
                    float damage = (float) Math.min(12, m.v.length() * 3);
                    e.hurt(sl.damageSources().playerAttack(m.owner), damage);
                    if (target != null)
                        target.hurt(sl.damageSources().playerAttack(m.owner), damage);
                    m.cooldown = 5;
                    if (m.rebound && m.bounces-- > 0) {
                        var n =
                                target != null
                                        ? e.position().subtract(target.position()).normalize()
                                        : Vec3.atLowerCornerOf(hit.getDirection().getNormal());
                        v = m.v.subtract(n.scale(2 * m.v.dot(n))).scale(.8);
                        e.setDeltaMovement(v);
                        e.hurtMarked = true;
                    } else {
                        it.remove();
                        continue;
                    }
                }
            }
            if (v.lengthSqr() > .03) m.v = v;
        }
    }

    @SubscribeEvent
    public static void fall(net.neoforged.neoforge.event.entity.living.LivingFallEvent ev) {
        if (ev.getEntity() instanceof net.minecraft.world.entity.player.Player) return;
        var d = ev.getEntity().getPersistentData();
        if (d.getLong("mm_updraft_until") > ev.getEntity().level().getGameTime())
            ev.setDamageMultiplier(ev.getDamageMultiplier() * 2);
    }

    @SubscribeEvent
    public static void stop(net.neoforged.neoforge.event.server.ServerStoppingEvent ev) {
        ACTIVE.clear();
    }
}
