package com.mattermod.entity;

import com.mattermod.item.OrbMode;
import com.mattermod.manipulator.Manipulator;
import com.mattermod.registry.ModEntities;
import com.mattermod.trait.Traits;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Golem mode (Mechatronic): summoning, orders and the Super Move of each player's Redstone Golem.
 *
 * <p>Right click: summon it where you aim (no Golem out), otherwise its Super Move on what you aim
 * at; Sneak + right click recalls it. Left click: aim at a creature to make it attack, at a block to
 * make it hold there; Sneak + left click: follow me. Riding it (Saddle), left click is its sweep and
 * right click its Super Move.
 *
 * <p>The Golem is never saved with the world: it is put away (keeping its health in the player's
 * data) when its owner logs out, changes dimension or unequips the Module. When it breaks down it
 * can be rebuilt after {@link #REBUILD_TICKS}; the time is kept on the Manipulator for the HUD.
 */
public final class GolemControl {
    private GolemControl() {}

    public static final int REBUILD_TICKS = 1200;
    public static final double ORDER_RANGE = 32, SUMMON_RANGE = 8;
    /** Manipulator stack data: game time the Golem can be rebuilt at. */
    public static final String TAG_REBUILD = "mm_golem_rebuild";
    private static final String TAG_HEALTH = "mm_golem_health";

    private static final Map<UUID, UUID> GOLEMS = new HashMap<>();

    /** The player's Golem, if it is out in their level. */
    public static RedstoneGolem golem(ServerPlayer p) {
        UUID id = GOLEMS.get(p.getUUID());
        if (id == null) return null;
        Entity e = p.serverLevel().getEntity(id);
        if (e instanceof RedstoneGolem g && g.isAlive()) return g;
        if (e == null || !e.isAlive()) GOLEMS.remove(p.getUUID());
        return null;
    }

    public static void rightClick(ServerPlayer p) {
        if (p.getVehicle() instanceof RedstoneGolem ridden && ridden.ownerId().map(p.getUUID()::equals).orElse(false)) {
            HitResult aim = aim(p, ridden);
            ridden.armsMove(p, target(aim), aim.getLocation());
            return;
        }
        RedstoneGolem g = golem(p);
        if (g == null) {
            summon(p);
            return;
        }
        if (p.isShiftKeyDown()) {
            g.recall();
            say(p, "message.mattermod.golem_recall");
            return;
        }
        HitResult aim = aim(p, g);
        if (!g.armsMove(p, target(aim), aim.getLocation())) {
            boolean hasArms = Traits.has(Manipulator.stack(p), "mechatronic.piston_arm")
                    || Traits.has(Manipulator.stack(p), "mechatronic.dispenser_arm");
            if (!hasArms) say(p, "message.mattermod.golem_no_arms");
        }
    }

    public static void leftClick(ServerPlayer p) {
        if (p.getVehicle() instanceof RedstoneGolem ridden && ridden.ownerId().map(p.getUUID()::equals).orElse(false)) {
            ridden.sweep(target(aim(p, ridden)));
            return;
        }
        RedstoneGolem g = golem(p);
        if (g == null) return;
        if (p.isShiftKeyDown()) {
            g.order = RedstoneGolem.Order.FOLLOW;
            g.orderTarget = null;
            g.setTarget(null);
            say(p, "message.mattermod.golem_follow");
            return;
        }
        HitResult aim = aim(p, g);
        LivingEntity t = target(aim);
        if (t != null && g.canHit(p, t)) {
            g.order = RedstoneGolem.Order.ATTACK;
            g.orderTarget = t;
            g.setTarget(t);
            say(p, "message.mattermod.golem_attack");
        } else if (aim instanceof BlockHitResult bh && aim.getType() == HitResult.Type.BLOCK) {
            g.order = RedstoneGolem.Order.HOLD;
            g.holdAt = bh.getBlockPos().relative(bh.getDirection());
            g.orderTarget = null;
            g.setTarget(null);
            say(p, "message.mattermod.golem_hold");
        }
    }

    private static void say(ServerPlayer p, String key) {
        p.displayClientMessage(Component.translatable(key).withStyle(ChatFormatting.RED), true);
    }

    /** What the player aims at within the order range: a creature first, else a block. */
    static HitResult aim(ServerPlayer p, Entity ignore) {
        Vec3 eye = p.getEyePosition(), look = p.getViewVector(1);
        HitResult block = p.pick(ORDER_RANGE, 1, false);
        double reach = block.getType() == HitResult.Type.MISS ? ORDER_RANGE : block.getLocation().distanceTo(eye);
        Vec3 end = eye.add(look.scale(reach));
        var hit = ProjectileUtil.getEntityHitResult(
                p, eye, end, p.getBoundingBox().expandTowards(look.scale(reach)).inflate(1),
                e -> e instanceof LivingEntity && e.isAlive() && e != ignore && !e.isSpectator(), reach * reach);
        return hit != null ? hit : block;
    }

    static LivingEntity target(HitResult hit) {
        return hit instanceof net.minecraft.world.phys.EntityHitResult eh && eh.getEntity() instanceof LivingEntity l ? l : null;
    }

    // ---- summon, recall, breakdown ------------------------------------------------------------

    public static long rebuildAt(ItemStack stack) {
        return stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag().getLong(TAG_REBUILD);
    }

    private static void setRebuildAt(ServerPlayer p, long at) {
        ItemStack st = Manipulator.stack(p);
        var t = st.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        t.putLong(TAG_REBUILD, at);
        st.set(DataComponents.CUSTOM_DATA, CustomData.of(t));
        Manipulator.sync(p);
    }

    public static void summon(ServerPlayer p) {
        ServerLevel sl = p.serverLevel();
        long now = sl.getGameTime();
        long ready = rebuildAt(Manipulator.stack(p));
        if (now < ready) {
            p.displayClientMessage(
                    Component.translatable("message.mattermod.golem_rebuilding", (ready - now + 19) / 20)
                            .withStyle(ChatFormatting.RED),
                    true);
            return;
        }
        RedstoneGolem g = ModEntities.REDSTONE_GOLEM.get().create(sl);
        if (g == null) return;
        g.setOwner(p);
        g.readTraits(p);
        HitResult aim = p.pick(SUMMON_RANGE, 1, false);
        Vec3 want = aim instanceof BlockHitResult bh && aim.getType() == HitResult.Type.BLOCK
                ? Vec3.atBottomCenterOf(bh.getBlockPos().relative(bh.getDirection()))
                : p.position().add(p.getViewVector(1).multiply(1, 0, 1).normalize().scale(3));
        Vec3 at = findSpot(g, sl, want, p.position());
        if (at == null) {
            say(p, "message.mattermod.golem_no_room");
            return;
        }
        g.moveTo(at.x, at.y, at.z, p.getYRot() + 180, 0);
        g.yBodyRot = g.yHeadRot = p.getYRot() + 180;
        float hp = p.getPersistentData().contains(TAG_HEALTH) ? p.getPersistentData().getFloat(TAG_HEALTH) : RedstoneGolem.MAX_HEALTH;
        g.setHealth(Math.max(1, Math.min(RedstoneGolem.MAX_HEALTH, hp)));
        if (p.getTeam() != null) sl.getScoreboard().addPlayerToTeam(g.getScoreboardName(), p.getTeam());
        sl.addFreshEntity(g);
        g.assembled();
        GOLEMS.put(p.getUUID(), g.getUUID());
    }

    /** Puts the Golem away now (recall finished, logout, Module unequipped), keeping its health. */
    public static void putAway(RedstoneGolem g, boolean silent) {
        ServerPlayer owner = g.owner();
        if (owner != null) {
            owner.getPersistentData().putFloat(TAG_HEALTH, g.getHealth());
            GOLEMS.remove(owner.getUUID(), g.getUUID());
        }
        g.ejectPassengers();
        if (!silent && g.level() instanceof ServerLevel sl)
            sl.sendParticles(net.minecraft.core.particles.ParticleTypes.POOF, g.getX(), g.getY() + 1, g.getZ(), 12, .6, .8, .6, .02);
        g.discard();
    }

    /** It broke down: no health to keep, and it can be rebuilt after a minute. */
    static void brokeDown(RedstoneGolem g) {
        ServerPlayer owner = g.owner();
        if (owner == null) return;
        owner.getPersistentData().remove(TAG_HEALTH);
        GOLEMS.remove(owner.getUUID(), g.getUUID());
        setRebuildAt(owner, g.level().getGameTime() + REBUILD_TICKS);
        say(owner, "message.mattermod.golem_broke");
    }

    /** Fell too far behind or got stuck: it folds up and reassembles next to its owner. */
    static boolean teleportNear(RedstoneGolem g, ServerPlayer owner) {
        Vec3 behind = owner.position().subtract(owner.getViewVector(1).multiply(1, 0, 1).normalize().scale(3));
        Vec3 at = findSpot(g, owner.serverLevel(), behind, owner.position());
        if (at == null) return false;
        g.getNavigation().stop();
        g.teleportTo(at.x, at.y, at.z);
        g.assembled();
        return true;
    }

    /** A spot near {@code want} (else around {@code fallback}) with ground under it and room for the Golem. */
    private static Vec3 findSpot(RedstoneGolem g, ServerLevel sl, Vec3 want, Vec3 fallback) {
        for (Vec3 c : new Vec3[] {want, fallback}) {
            for (int r = 0; r <= 4; r++) {
                for (int i = 0; i < Math.max(1, r * 8); i++) {
                    double a = i * Math.PI * 2 / Math.max(1, r * 8);
                    BlockPos base = BlockPos.containing(c.x + Math.cos(a) * r, c.y + 2, c.z + Math.sin(a) * r);
                    for (int dy = 0; dy < 6; dy++) {
                        BlockPos pos = base.below(dy);
                        if (!sl.getBlockState(pos.below()).isFaceSturdy(sl, pos.below(), net.minecraft.core.Direction.UP)) continue;
                        Vec3 v = Vec3.atBottomCenterOf(pos);
                        AABB box = g.getType().getDimensions().makeBoundingBox(v);
                        if (sl.noCollision(g, box) && !sl.containsAnyLiquid(box)) return v;
                        break;
                    }
                }
            }
        }
        return null;
    }

    // ---- events ------------------------------------------------------------------------------

    private static void putAwayFor(ServerPlayer p) {
        RedstoneGolem g = golem(p);
        if (g != null) putAway(g, true);
        GOLEMS.remove(p.getUUID());
    }

    @SubscribeEvent
    public static void logout(PlayerEvent.PlayerLoggedOutEvent ev) {
        if (ev.getEntity() instanceof ServerPlayer p) putAwayFor(p);
    }

    @SubscribeEvent
    public static void changedDimension(PlayerEvent.PlayerChangedDimensionEvent ev) {
        if (!(ev.getEntity() instanceof ServerPlayer p)) return;
        UUID id = GOLEMS.remove(p.getUUID());
        if (id == null) return;
        var server = p.getServer();
        if (server == null) return;
        for (ServerLevel l : server.getAllLevels())
            if (l.getEntity(id) instanceof RedstoneGolem g) {
                p.getPersistentData().putFloat(TAG_HEALTH, g.getHealth());
                g.discard();
            }
    }

    @SubscribeEvent
    public static void stopping(ServerStoppingEvent ev) {
        for (ServerPlayer p : ev.getServer().getPlayerList().getPlayers()) putAwayFor(p);
        GOLEMS.clear();
    }

    /** Whether Golem mode is the Manipulator's current mode (for the HUD and tests). */
    public static boolean inGolemMode(Player p) {
        return OrbMode.get(Manipulator.stack(p)) == OrbMode.GOLEM;
    }
}
