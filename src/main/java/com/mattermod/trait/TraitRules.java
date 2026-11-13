package com.mattermod.trait;

import com.mattermod.manipulator.Manipulator;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import javax.annotation.Nullable;

/**
 * Server-side rules for changing a build.
 *
 * <p>Filling an empty slot or tier is always allowed. Changing something already chosen is free,
 * but not in combat (5 s after dealing or taking damage), so builds are planned rather than swapped
 * mid-fight.
 */
public final class TraitRules {

    private TraitRules() {}

    private static final int COMBAT_TICKS = 100;
    private static final Map<UUID, Long> LAST_COMBAT = new HashMap<>();

    public static void markCombat(ServerPlayer player) {
        LAST_COMBAT.put(player.getUUID(), player.serverLevel().getGameTime());
    }

    public static void forget(UUID id) {
        LAST_COMBAT.remove(id);
    }

    public static void clear() {
        LAST_COMBAT.clear();
    }

    public static boolean inCombat(ServerPlayer player) {
        Long t = LAST_COMBAT.get(player.getUUID());
        return t != null && player.serverLevel().getGameTime() - t < COMBAT_TICKS;
    }

    /** Equip a line in a slot, or clear it (lineId null). */
    public static void equip(ServerPlayer player, int slot, @Nullable ResourceLocation lineId) {
        ItemStack stack = Manipulator.stack(player);
        if (slot < 0 || slot >= TraitBuild.openSlots(stack)) {
            deny(player, "slot_locked");
            return;
        }
        ResourceLocation current = TraitBuild.line(stack, slot);
        if (lineId != null && lineId.equals(current)) return;
        if (lineId != null) {
            if (TraitLines.get(player.registryAccess(), lineId).isEmpty()) return;
            if (!ModuleUnlocks.known(stack, lineId)) {
                deny(player, "module_unknown");
                return;
            }
            if (TraitBuild.slotOf(stack, lineId) >= 0) {
                deny(player, "module_equipped");
                return;
            }
        }
        if (current != null && inCombat(player)) {
            deny(player, "combat_swap_module");
            return;
        }
        TraitBuild.setLine(stack, slot, lineId);
        TraitEnchants.refresh(stack, player.registryAccess());
        Manipulator.sync(player);
    }

    /** Choose one of the two traits of a tier. */
    public static void pick(ServerPlayer player, int slot, int tier, int choice) {
        ItemStack stack = Manipulator.stack(player);
        ResourceLocation lineId = TraitBuild.line(stack, slot);
        if (lineId == null || tier < 0 || tier >= TraitLine.TIERS || choice < 0 || choice > 1)
            return;
        if (tier >= TraitBuild.openTiers(stack)) {
            deny(player, "tier_locked");
            return;
        }
        TraitLine line = TraitLines.get(player.registryAccess(), lineId).orElse(null);
        if (line == null) return;
        String traitId = line.tier(tier).get(choice).id();
        if (traitId.isEmpty()) return;
        String current = TraitBuild.pick(stack, slot, tier);
        if (tier > 0 && TraitBuild.pick(stack, slot, tier - 1).isEmpty()) return;
        if (traitId.equals(current)) {
            if (inCombat(player)) {
                deny(player, "combat_disable_trait");
                return;
            }
            for (int t = tier; t < TraitLine.TIERS; t++) TraitBuild.setPick(stack, slot, t, "");
            TraitEnchants.refresh(stack, player.registryAccess());
            Manipulator.sync(player);
            return;
        }
        if (!current.isEmpty() && inCombat(player)) {
            deny(player, "combat_swap_trait");
            return;
        }
        TraitBuild.setPick(stack, slot, tier, traitId);
        TraitEnchants.refresh(stack, player.registryAccess());
        Manipulator.sync(player);
    }

    private static void deny(ServerPlayer player, String msg) {
        player.displayClientMessage(
                Component.translatable("message.mattermod." + msg).withStyle(ChatFormatting.RED),
                true);
    }
}
