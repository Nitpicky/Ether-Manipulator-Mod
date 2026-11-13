package com.mattermod.beam;

import com.mattermod.registry.ModEntities;
import com.mattermod.trait.*;

import net.minecraft.core.*;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.*;
import net.minecraft.world.effect.*;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.*;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

import java.util.*;

public final class PhotonicEffects {
    private record Node(
            ServerLevel level,
            UUID owner,
            Vec3 at,
            Vec3 previous,
            ItemStack traits,
            long expires) {}

    private static final List<Node> NODES = new ArrayList<>();
    private static final Map<UUID, Vec3> PREVIOUS = new HashMap<>();

    private PhotonicEffects() {}

    private static boolean friendly(ServerPlayer owner, LivingEntity e) {
        return e == owner
                || e instanceof Player
                || e instanceof Animal
                || RedesignEffects.ally(owner, e)
                || e.getPersistentData().hasUUID("mm_summoner");
    }

    public static void flight(BeamBallEntity b, ItemStack st) {
        if (b.tickCount % 5 == 0 && b.getOwner() instanceof ServerPlayer p)
            apply(p, b.position(), st, 1, false);
    }

    public static void bounce(BeamBallEntity b, ItemStack st, Vec3 at) {
        if (!(b.getOwner() instanceof ServerPlayer p)) return;
        Vec3 old = PREVIOUS.put(b.getUUID(), at);
        add(p, at, Traits.has(st, "photonic.constellation") ? old : null, st, 3600);
        if (Traits.has(st, "photonic.mirage")) decoy(p, at, 200);
    }

    public static void end(BeamBallEntity b, ItemStack st, Vec3 at) {
        if (!(b.getOwner() instanceof ServerPlayer p)) return;
        boolean nova = Traits.has(st, "photonic.supernova");
        if (!Traits.has(st, "photonic.lodestar") && !Traits.has(st, "photonic.constellation"))
            add(p, at, null, st, 3600);
        apply(p, at, st, nova ? 2 : 1, true);
        if (Traits.has(st, "photonic.mirage"))
            decoy(
                    p,
                    at,
                    Traits.has(st, "photonic.lodestar")
                            ? com.mattermod.config.MatterConfig.PHOTONIC_BEACON_TICKS.get()
                            : nova ? 240 : 120);
        if (Traits.has(st, "photonic.lodestar")) {
            int life = com.mattermod.config.MatterConfig.PHOTONIC_BEACON_TICKS.get();
            add(p, at, null, st, life);
            net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(
                    p,
                    new com.mattermod.network.payload.ModulePayloads.LodestarMark(
                            BlockPos.containing(at),
                            p.level().dimension().location(),
                            p.level().getGameTime() + life));
        }
        if (Traits.has(st, "photonic.constellation"))
            add(p, at, PREVIOUS.remove(b.getUUID()), st, 200);
        else PREVIOUS.remove(b.getUUID());
        if (nova) {
            for (var dir : Direction.values()) {
                var spot = BlockPos.containing(at).relative(dir, 3);
                if (p.serverLevel().hasChunkAt(spot) && p.serverLevel().mayInteract(p, spot))
                    WorldEffects.tempLight(p.serverLevel(), spot, 15, 80);
            }
            WorldEffects.tempLight(p.serverLevel(), BlockPos.containing(at), 15, 80);
            p.serverLevel().sendParticles(ParticleTypes.FLASH, at.x, at.y, at.z, 1, 0, 0, 0, 0);
        }
    }

    private static void add(ServerPlayer p, Vec3 at, Vec3 previous, ItemStack st, int ticks) {
        while (NODES.stream()
                        .filter(
                                n ->
                                        n.level() == p.serverLevel()
                                                && n.at().distanceToSqr(at) < 64 * 64)
                        .count()
                >= 24) {
            var first =
                    NODES.stream()
                            .filter(
                                    n ->
                                            n.level() == p.serverLevel()
                                                    && n.at().distanceToSqr(at) < 64 * 64)
                            .findFirst();
            if (first.isEmpty()) break;
            NODES.remove(first.get());
        }
        NODES.add(
                new Node(
                        p.serverLevel(),
                        p.getUUID(),
                        at,
                        previous,
                        st.copy(),
                        p.level().getGameTime() + ticks));
    }

    private static void decoy(ServerPlayer p, Vec3 at, int lifetime) {
        var sl = p.serverLevel();
        if (sl.getEntitiesOfClass(
                                LightDecoy.class,
                                p.getBoundingBox().inflate(96),
                                e -> e.owner().equals(p.getUUID()))
                        .size()
                >= 8) return;
        var e = ModEntities.LIGHT_DECOY.get().create(sl);
        if (e == null) return;
        e.setPos(at);
        e.configure(p, lifetime);
        sl.addFreshEntity(e);
        for (var mob :
                sl.getEntitiesOfClass(
                        Mob.class,
                        e.getBoundingBox().inflate(16),
                        m ->
                                m.getTarget() == p
                                        || m instanceof net.minecraft.world.entity.monster.Enemy))
            mob.setTarget(e);
    }

    private static void apply(ServerPlayer p, Vec3 at, ItemStack st, int power, boolean impact) {
        var sl = p.serverLevel();
        long now = sl.getGameTime();
        double radius =
                Traits.has(st, "photonic.flare")
                        ? 12 * power
                        : Traits.has(st, "photonic.daybreak") ? 6 * power : 4 * power;
        for (var e :
                sl.getEntitiesOfClass(
                        LivingEntity.class,
                        new AABB(at, at).inflate(radius),
                        LivingEntity::isAlive)) {
            boolean friend = friendly(p, e);
            if (Traits.has(st, "photonic.flare") && e != p)
                e.addEffect(
                        new MobEffectInstance(
                                MobEffects.GLOWING, 80 * power, 0, false, false, true));
            if (Traits.has(st, "photonic.radiance")
                    && friend
                    && e.distanceToSqr(at) <= 16 * power * power) {
                var data = e.getPersistentData();
                boolean fresh = data.getLong("mm_radiance_next") <= now;
                int prior = fresh ? 0 : data.getInt("mm_radiance_power");
                if (power > prior) {
                    e.heal(
                            (float)
                                    ((power - prior)
                                            * com.mattermod.config.MatterConfig.RADIANCE_HEAL
                                                    .get()));
                    data.putInt("mm_radiance_power", power);
                    if (fresh) data.putLong("mm_radiance_next", now + 20);
                }
            }

            if (e instanceof Mob mob && !friend) {
                if (impact
                        && Traits.has(st, "photonic.dazzle")
                        && e.distanceToSqr(at) <= 9 * power * power) {
                    e.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, 40 * power));
                    e.getPersistentData().putLong("mm_dazzled_until", now + 40 * power);
                    mob.setTarget(null);
                    mob.getNavigation().stop();
                }
                boolean dark =
                        e.getType().is(net.minecraft.tags.EntityTypeTags.UNDEAD)
                                || e instanceof net.minecraft.world.entity.monster.Spider;
                if (Traits.has(st, "photonic.daybreak")
                        && dark
                        && !e.getType().is(net.neoforged.neoforge.common.Tags.EntityTypes.BOSSES)
                        && e.distanceToSqr(at) <= 36 * power * power) {
                    mob.setTarget(null);
                    var away = e.position().subtract(at).normalize();
                    mob.getNavigation()
                            .moveTo(e.getX() + away.x * 6, e.getY(), e.getZ() + away.z * 6, 1.3);
                    if (e.distanceToSqr(at) < 4 * power * power) {
                        e.igniteForSeconds(3 * power);
                        if (power > 1 && now % 20 == 0) e.hurt(sl.damageSources().onFire(), 2);
                    }
                }
            }
        }
    }

    private static final net.minecraft.core.particles.DustParticleOptions LINK =
            new net.minecraft.core.particles.DustParticleOptions(
                    new org.joml.Vector3f(1f, .86f, .45f), .45f);
    private static final net.minecraft.core.particles.DustParticleOptions SPARK =
            new net.minecraft.core.particles.DustParticleOptions(
                    new org.joml.Vector3f(1f, .98f, .85f), 1.1f);
    private static final net.minecraft.core.particles.DustParticleOptions STAR =
            new net.minecraft.core.particles.DustParticleOptions(
                    new org.joml.Vector3f(1f, .95f, .7f), .8f);

    /**
     * A link between two bounce lights: a faint dotted thread of light, every other dot lit in
     * turn so it shimmers, with a bright spark running along it from the older light to the newer.
     */
    private static void constellation(ServerLevel sl, Vec3 from, Vec3 to, long now) {
        double distance = from.distanceTo(to);
        int dots = Math.min(48, Math.max(2, (int) (distance / .6)));
        int parity = (int) (now / 5 % 2);
        for (int i = parity; i <= dots; i += 2) {
            var at = from.lerp(to, i / (double) dots);
            sl.sendParticles(LINK, at.x, at.y, at.z, 1, 0, 0, 0, 0);
        }
        // the spark covers the link in about a second, whatever its length
        double t = (now % 20) / 20.0;
        for (double k : new double[] {t, t - .05, t - .1, t - .15}) { // a short comet tail
            if (k < 0) continue;
            var at = from.lerp(to, k);
            sl.sendParticles(SPARK, at.x, at.y, at.z, 1, 0, 0, 0, 0);
        }
    }

    @SubscribeEvent
    public static void tick(LevelTickEvent.Post ev) {
        if (!(ev.getLevel() instanceof ServerLevel sl) || sl.getGameTime() % 5 != 0) return;
        long now = sl.getGameTime();
        NODES.removeIf(n -> n.level() == sl && n.expires() <= now);
        for (var n : NODES) {
            if (n.level() != sl || !sl.hasChunkAt(BlockPos.containing(n.at()))) continue;
            var owner = sl.getServer().getPlayerList().getPlayer(n.owner());
            if (owner == null || owner.level() != sl) continue;
            apply(owner, n.at(), n.traits(), 1, true);
            if (n.previous() != null) {
                double distance = n.at().distanceTo(n.previous());
                int steps = Math.min(32, Math.max(1, (int) distance));
                for (int i = 0; i <= steps; i += 3)
                    apply(owner, n.previous().lerp(n.at(), i / (double) steps), n.traits(), 1, false);
                constellation(sl, n.previous(), n.at(), now);
            }
            // each star twinkles now and then
            if (n.previous() != null && sl.random.nextInt(4) == 0)
                sl.sendParticles(STAR, n.at().x, n.at().y, n.at().z, 2, .15, .15, .15, 0);
        }
    }

    @SubscribeEvent
    public static void stop(net.neoforged.neoforge.event.server.ServerStoppingEvent ev) {
        NODES.clear();
        PREVIOUS.clear();
    }
}
