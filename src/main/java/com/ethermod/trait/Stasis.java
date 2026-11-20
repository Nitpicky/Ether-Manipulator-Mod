package com.ethermod.trait;

import net.minecraft.server.level.*;
import net.minecraft.world.entity.*;
import net.minecraft.world.phys.*;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.living.*;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

import java.util.*;

/**
 * Stasis (the Stasis Grenade's hold): a held creature stays on its spot with gravity and AI off,
 * stores every knockback it takes (amplified), and is launched with it on release. Its own
 * NoGravity/NoAI flags are kept on it (mm_stasis_restore) so they come back after a save,
 * a dimension change or a server stop.
 */
public final class Stasis {
    private Stasis() {}

    private record Frozen(
            ServerLevel level,
            UUID entity,
            Vec3 at,
            long expires,
            boolean noGravity,
            boolean noAi) {}

    private static final Map<UUID, Frozen> FROZEN = new HashMap<>();

    /**
     * Saved on the creature while it is held: its own NoGravity/NoAI flags, so they come back even
     * if it leaves the world before the stasis ends (FROZEN only lives in memory).
     */
    private static final String STASIS_RESTORE = "mm_stasis_restore";

    private static void restoreStasisFlags(LivingEntity e) {
        var r = e.getPersistentData().getCompound(STASIS_RESTORE);
        e.setNoGravity(r.getBoolean("gravity"));
        if (e instanceof Mob m) m.setNoAi(r.getBoolean("ai"));
        e.getPersistentData().remove(STASIS_RESTORE);
    }

    public static void stasis(LivingEntity e, int ticks) {
        if (!(e.level() instanceof ServerLevel sl)
                || FROZEN.containsKey(e.getUUID())
                || com.ethermod.util.MobMemory.peek(e).getLong("mm_stasis_cooldown") > sl.getGameTime()) return;
        FROZEN.put(
                e.getUUID(),
                new Frozen(
                        sl,
                        e.getUUID(),
                        e.position(),
                        sl.getGameTime() + ticks,
                        e.isNoGravity(),
                        e instanceof Mob m && m.isNoAi()));
        com.ethermod.util.MobMemory.of(e).putDouble("mm_stasis_force", 0);
        var restore = new net.minecraft.nbt.CompoundTag();
        restore.putBoolean("gravity", e.isNoGravity());
        restore.putBoolean("ai", e instanceof Mob m0 && m0.isNoAi());
        e.getPersistentData().put(STASIS_RESTORE, restore);
        e.setDeltaMovement(Vec3.ZERO);
        e.setNoGravity(true);
        if (e instanceof Mob m) m.setNoAi(true);
        // the amber tint and the quickening ding (client StasisFx)
        net.neoforged.neoforge.network.PacketDistributor.sendToPlayersTrackingEntityAndSelf(
                e, new com.ethermod.network.payload.StasisPayload(e.getId(), ticks));
    }

    public static boolean inStasis(LivingEntity e) {
        return FROZEN.containsKey(e.getUUID());
    }

    /** The Stasis Grenade's burst: adds its strength to the stored push, keeping the last hit's aim. */
    public static void burstInto(LivingEntity e, Vec3 push) {
        var d = com.ethermod.util.MobMemory.of(e);
        if (!inStasis(e)) {
            impulse(e, push);
            return;
        }
        { // the grenade's spot is the one authority on direction; stored hits only add strength
            var n = push.normalize();
            d.putDouble("mm_stasis_x", n.x);
            d.putDouble("mm_stasis_y", n.y);
            d.putDouble("mm_stasis_z", n.z);
        }
        d.putDouble("mm_stasis_force", Math.min(STASIS_MAX, d.getDouble("mm_stasis_force") + push.length()));
    }

    /** Lets a held creature go now, with all the knockback it stored. */
    public static void thaw(LivingEntity e) {
        var f = FROZEN.remove(e.getUUID());
        if (f != null) let(e, f, e.level().getGameTime());
    }

    private static void let(LivingEntity e, Frozen f, long now) {
        var d = com.ethermod.util.MobMemory.of(e);
        var v = launch(e, false);
        e.setNoGravity(f.noGravity());
        if (e instanceof Mob m) m.setNoAi(f.noAi());
        e.getPersistentData().remove(STASIS_RESTORE);
        e.setDeltaMovement(v);
        e.hurtMarked = true;
        d.putLong("mm_stasis_cooldown", now + 40);
        d.remove("mm_stasis_ex");
        d.remove("mm_stasis_ey");
        d.remove("mm_stasis_ez");
        net.neoforged.neoforge.network.PacketDistributor.sendToPlayersTrackingEntityAndSelf(
                e, new com.ethermod.network.payload.StasisPayload(e.getId(), 0));
    }

    public static void impulse(LivingEntity e, Vec3 force) {
        if (FROZEN.containsKey(e.getUUID())) {
            store(e, force);
            return;
        }
        e.setDeltaMovement(e.getDeltaMovement().add(force));
        e.hurtMarked = true;
    }

    /**
     * Stasis amplifies what it stores: each knockback taken while held counts this much more, up to
     * {@link #STASIS_MAX} (blocks/tick), so a setup of a few hits sends it flying.
     */
    public static final double STASIS_GAIN = 1.8, STASIS_MAX = 3.0;

    private static void store(LivingEntity e, Vec3 force) {
        if (force.lengthSqr() < 1e-6) return;
        var d = com.ethermod.util.MobMemory.of(e);
        d.putDouble(
                "mm_stasis_force",
                Math.min(STASIS_MAX, d.getDouble("mm_stasis_force") + force.length() * STASIS_GAIN));
        var n = force.normalize();
        d.putDouble("mm_stasis_x", n.x);
        d.putDouble("mm_stasis_y", n.y);
        d.putDouble("mm_stasis_z", n.z);
        sendForce(e);
    }

    /**
     * What a Stasis Grenade's burst will add on release: its strength joins the stored knockback,
     * and its direction (away from where the grenade stuck) is the launch direction. Shown in the
     * arrow before it happens.
     */
    public static void expectBurst(LivingEntity e, Vec3 push) {
        var d = com.ethermod.util.MobMemory.of(e);
        d.putDouble("mm_stasis_ex", push.x);
        d.putDouble("mm_stasis_ey", push.y);
        d.putDouble("mm_stasis_ez", push.z);
        sendForce(e);
    }

    /**
     * The velocity a held creature leaves with: the stored strength (plus a pending burst) along the
     * last hit's direction (the burst's own when nothing hit it), always with enough lift to leave
     * the ground, so it flies instead of being dragged to a stop by friction. The arrow and the
     * release both use this, so the preview is the launch.
     */
    private static Vec3 launch(LivingEntity e, boolean withBurst) {
        var d = com.ethermod.util.MobMemory.peek(e);
        var burst = withBurst
                ? new Vec3(d.getDouble("mm_stasis_ex"), d.getDouble("mm_stasis_ey"), d.getDouble("mm_stasis_ez"))
                : Vec3.ZERO;
        double force = Math.min(STASIS_MAX, d.getDouble("mm_stasis_force") + burst.length());
        if (force < 1e-6) return Vec3.ZERO;
        // the Stasis Grenade's spot on the body sets the direction (stored hits only add
        // strength); without a pending burst, the last stored direction
        var dir = burst.lengthSqr() > 1e-6
                ? burst.normalize()
                : new Vec3(d.getDouble("mm_stasis_x"), d.getDouble("mm_stasis_y"), d.getDouble("mm_stasis_z"));
        var v = dir.scale(force);
        // lift so a level push flies instead of dragging along the ground; a push aimed down
        // (grenade on top) stays down
        if (dir.y < -0.2) return v;
        return new Vec3(v.x, Math.max(v.y, .3 + .12 * force), v.z);
    }

    /** The teal arrow over the held creature: where (and how hard) it will fly on release. */
    private static void sendForce(LivingEntity e) {
        var v = launch(e, true);
        net.neoforged.neoforge.network.PacketDistributor.sendToPlayersTrackingEntityAndSelf(
                e, new com.ethermod.network.payload.StasisForcePayload(e.getId(), (float) v.x, (float) v.y, (float) v.z));
    }

    private static UUID quiet;

    /** Damages a held creature without its knockback counting toward the stored push. */
    public static void hurtQuietly(LivingEntity e, net.minecraft.world.damagesource.DamageSource src, float amount) {
        quiet = e.getUUID();
        try {
            e.hurt(src, amount);
        } finally {
            quiet = null;
        }
    }

    @SubscribeEvent
    public static void knock(LivingKnockBackEvent ev) {
        if (ev.getEntity().getUUID().equals(quiet)) {
            ev.setCanceled(true);
            return;
        }
        if (FROZEN.containsKey(ev.getEntity().getUUID())) {
            store(
                    ev.getEntity(),
                    new Vec3(-ev.getRatioX(), 0.25, -ev.getRatioZ())
                            .normalize()
                            .scale(ev.getStrength()));
            ev.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void loaded(net.neoforged.neoforge.event.entity.EntityJoinLevelEvent ev) {
        // held, then moved to another level (a player changing dimension): the hold ends there and
        // its own flags come back; the old level's tick would only drop the entry and leave
        // NoGravity on
        if (ev.getLevel() instanceof ServerLevel joined
                && ev.getEntity() instanceof LivingEntity moved) {
            Frozen held = FROZEN.get(moved.getUUID());
            if (held != null && held.level() != joined) {
                FROZEN.remove(moved.getUUID());
                restoreStasisFlags(moved);
            }
        }
        // a creature saved mid-stasis (chunk unloaded, logout, Save and Quit) gets its flags back
        if (ev.getEntity() instanceof LivingEntity held
                && held.getPersistentData().contains(STASIS_RESTORE)
                && !FROZEN.containsKey(held.getUUID())) restoreStasisFlags(held);
    }

    @SubscribeEvent
    public static void world(LevelTickEvent.Post ev) {
        if (!(ev.getLevel() instanceof ServerLevel sl)) return;
        long now = sl.getGameTime();
        var it = FROZEN.entrySet().iterator();
        while (it.hasNext()) {
            var f = it.next().getValue();
            if (f.level() != sl) continue;
            if (!(sl.getEntity(f.entity()) instanceof LivingEntity e) || !e.isAlive()) {
                it.remove();
                continue;
            }
            if (now >= f.expires()) {
                let(e, f, now);
                it.remove();
            } else {
                if (e.getDeltaMovement().lengthSqr() > .09) store(e, e.getDeltaMovement());
                if (e instanceof ServerPlayer sp) {
                    // players move on their own side: pull them back to the spot when they drift
                    if (sp.position().distanceToSqr(f.at()) > 1e-4)
                        sp.connection.teleport(f.at().x, f.at().y, f.at().z, sp.getYRot(), sp.getXRot());
                } else e.setPos(f.at());
                e.setDeltaMovement(Vec3.ZERO);
                e.resetFallDistance();
                if (e instanceof Mob m) m.getNavigation().stop();
            }
        }
    }

    @SubscribeEvent
    public static void stop(net.neoforged.neoforge.event.server.ServerStoppingEvent ev) {
        // put held creatures back before they are saved
        for (var f : FROZEN.values())
            if (f.level().getEntity(f.entity()) instanceof LivingEntity e) restoreStasisFlags(e);
        FROZEN.clear();
    }
}
