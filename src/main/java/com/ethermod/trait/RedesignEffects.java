package com.ethermod.trait;

import com.ethermod.manipulator.Manipulator;

import net.minecraft.core.*;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.*;
import net.minecraft.world.effect.*;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.ai.attributes.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.*;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.living.*;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.level.*;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

import java.util.*;

/**
 * The trait lines' passive perks, server side: attribute badges (Plasmic melee and reach,
 * Tectonic block reach, Hydraulic swim), Photonic cleanse, Pyrotechnic fireproofing and
 * Rekindle, Tectonic mining speed, Botanic crops, Kinetic ranged bonus, Plasmic Dissolve.
 */
public final class RedesignEffects {
    private RedesignEffects() {}

    private static final Identifier MELEE = id("melee_badge"),
            REACH = id("melee_reach"),
            BLOCK_REACH = id("block_reach"),
            SWIM = id("swim_badge");

    private static Identifier id(String s) {
        return Identifier.fromNamespaceAndPath("ethermod", s);
    }

    private static void attribute(
            Player p,
            net.minecraft.core.Holder<Attribute> key,
            Identifier id,
            double value,
            AttributeModifier.Operation op) {
        var a = p.getAttribute(key);
        if (a == null) return;
        if (value == 0) {
            a.removeModifier(id);
            return;
        }
        var old = a.getModifier(id);
        if (old != null && old.amount() == value) return;
        a.removeModifier(id);
        a.addTransientModifier(new AttributeModifier(id, value, op));
    }

    @SubscribeEvent
    public static void tick(PlayerTickEvent.Post ev) {
        if (!(ev.getEntity() instanceof ServerPlayer p)) return;
        var st = Manipulator.stack(p);
        long now = p.level().getGameTime();
        if (p.tickCount % 10 == 0) {
            PhotonicGlowSync.tick(p);
            attribute(
                    p,
                    Attributes.ATTACK_DAMAGE,
                    MELEE,
                    meleeBonus(p),
                    AttributeModifier.Operation.ADD_VALUE);
            attribute(
                    p,
                    Attributes.ENTITY_INTERACTION_RANGE,
                    REACH,
                    Traits.hasCommon(st, Traits.PLASMIC, 1) ? 1 : 0,
                    AttributeModifier.Operation.ADD_VALUE);
            attribute(
                    p,
                    Attributes.BLOCK_INTERACTION_RANGE,
                    BLOCK_REACH,
                    Traits.hasCommon(st, Traits.TECTONIC, 1) ? 2 : 0,
                    AttributeModifier.Operation.ADD_VALUE);
            attribute(
                    p,
                    net.neoforged.neoforge.common.NeoForgeMod.SWIM_SPEED,
                    SWIM,
                    Traits.hasLine(st, Traits.HYDRAULIC) ? 0.3 : 0,
                    AttributeModifier.Operation.ADD_MULTIPLIED_BASE);
            if (Traits.hasCommon(st, Traits.PHOTONIC, 1)) {
                p.removeEffect(MobEffects.BLINDNESS);
                p.removeEffect(MobEffects.DARKNESS);
            }
            if (Traits.hasLine(st, Traits.PYROTECHNIC)) p.clearFire();
        }
        if (now % 20 == 0
                && Traits.has(st, "pyrotechnic.rekindle")
                && (p.isInLava() || touchesFire(p))) p.heal(1f);
    }

    // The Photonic glow that follows the player is drawn by each client (client.PhotonicGlow), a
    // smoothly moving light: no light block is placed over the player's head.

    private static boolean touchesFire(Entity p) {
        for (var pos :
                BlockPos.betweenClosed(
                        BlockPos.containing(
                                p.getBoundingBox().minX,
                                p.getBoundingBox().minY,
                                p.getBoundingBox().minZ),
                        BlockPos.containing(
                                p.getBoundingBox().maxX,
                                p.getBoundingBox().maxY,
                                p.getBoundingBox().maxZ)))
            if (p.level().getBlockState(pos).is(net.minecraft.tags.BlockTags.FIRE)) return true;
        return false;
    }

    @SubscribeEvent
    public static void mine(PlayerEvent.BreakSpeed ev) {
        if (Traits.hasLine(Manipulator.stack(ev.getEntity()), Traits.TECTONIC)
                && !(ev.getEntity().getMainHandItem().getItem()
                        instanceof com.ethermod.item.EtherManipulatorItem))
            ev.setNewSpeed(ev.getNewSpeed() + 2);
    }

    @SubscribeEvent
    public static void trample(BlockEvent.FarmlandTrampleEvent ev) {
        if (ev.getEntity() instanceof Player p
                && Traits.hasLine(Manipulator.stack(p), Traits.BOTANIC)) ev.setCanceled(true);
    }

    @SubscribeEvent
    public static void drops(BlockDropsEvent ev) {
        if (!(ev.getBreaker() instanceof ServerPlayer p)) return;
        var st = Manipulator.stack(p);
        var bs = ev.getState();
        if (!(bs.getBlock() instanceof CropBlock crop) || !crop.isMaxAge(bs)) return;
        if (Traits.has(st, "botanic.bountiful")) {
            for (var d : ev.getDrops())
                if (!d.getItem().is(net.neoforged.neoforge.common.Tags.Items.SEEDS))
                    d.getItem().grow(Math.max(1, d.getItem().getCount() / 2));
        }
        if (Traits.hasCommon(st, Traits.BOTANIC, 0)) {
            boolean reserved = false;
            for (var d : ev.getDrops())
                if (!d.getItem().isEmpty() && isPlantingItem(d.getItem(), crop)) {
                    d.getItem().shrink(1);
                    reserved = true;
                    break;
                }
            if (reserved) {
                var level = ev.getLevel();
                var pos = ev.getPos().immutable();
                Scheduler.schedule(
                        level,
                        1,
                        () -> {
                            var fresh = crop.getStateForAge(0);
                            if (level.hasChunkAt(pos)
                                    && level.getBlockState(pos).isAir()
                                    && fresh.canSurvive(level, pos))
                                level.setBlockAndUpdate(pos, fresh);
                        });
            }
        }
        if (Traits.has(st, "botanic.cross_pollination"))
            for (var pos :
                    BlockPos.betweenClosed(
                            ev.getPos().offset(-2, 0, -2), ev.getPos().offset(2, 0, 2))) {
                var q = pos.immutable();
                var s = ev.getLevel().getBlockState(q);
                if (!q.equals(ev.getPos())
                        && s.getBlock() instanceof CropBlock c
                        && !c.isMaxAge(s)
                        && com.ethermod.util.Protect.may(ev.getLevel(), p, q))
                    ev.getLevel()
                            .setBlockAndUpdate(
                                    q, c.getStateForAge(Math.min(c.getMaxAge(), c.getAge(s) + 1)));
            }
    }

    private static boolean isPlantingItem(ItemStack item, CropBlock crop) {
        return item.getItem() instanceof BlockItem b && b.getBlock() == crop;
    }

    /** The Manipulator's melee bonus (Plasmic: Melee Damage +1). */
    public static float meleeBonus(ServerPlayer p) {
        return Traits.hasLine(Manipulator.stack(p), Traits.PLASMIC) ? 1 : 0;
    }

    /** The Manipulator's ranged bonus (Kinetic: Fully Charged Ranged Damage +3). */
    public static float rangedBonus(ServerPlayer p) {
        return Traits.hasLine(Manipulator.stack(p), Traits.KINETIC) ? 3 : 0;
    }

    @SubscribeEvent
    public static void ranged(LivingIncomingDamageEvent ev) {
        if (ev.getSource().getEntity() instanceof ServerPlayer p
                && ev.getSource().getDirectEntity()
                        instanceof net.minecraft.world.entity.projectile.arrow.AbstractArrow arrow
                && arrow.isCritArrow())
            ev.setAmount(ev.getAmount() + rangedBonus(p));
    }

    @SubscribeEvent
    public static void loot(LivingDropsEvent ev) {
        // a summon's drops are cleared (Burning.loot)
        if (ev.getEntity().getPersistentData().read("mm_summoner", net.minecraft.core.UUIDUtil.CODEC).isPresent()) return;
        if (!(ev.getSource().getEntity() instanceof ServerPlayer p)
                || !Traits.has(Manipulator.stack(p), "plasmic.dissolve")) return;
        int level =
                Math.max(
                        1,
                        Math.min(
                                4,
                                com.ethermod.util.MobMemory.peek(ev.getEntity()).getIntOr("mm_combat_pressure", 0)));
        if (level <= 1) return;
        var extra = new ArrayList<net.minecraft.world.entity.item.ItemEntity>();
        for (var item : ev.getDrops())
            if (p.getRandom().nextFloat() < (level - 1) * 0.25f)
                extra.add(
                        new net.minecraft.world.entity.item.ItemEntity(
                                p.level(),
                                item.getX(),
                                item.getY(),
                                item.getZ(),
                                item.getItem().copyWithCount(1)));
        ev.getDrops().addAll(extra);
    }
}
