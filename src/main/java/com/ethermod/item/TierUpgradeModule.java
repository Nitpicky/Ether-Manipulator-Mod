package com.ethermod.item;

import com.ethermod.manipulator.Manipulator;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

import java.util.List;

/**
 * An upgrade module that pushes the player's Manipulator to the next stage. Right-click the module,
 * or use the Install button on the upgrade screen (K).
 *
 * <p>Two variants: Iron (Stone → Iron) and Diamond (Iron → Diamond). requiredStage is the stage the
 * Manipulator must be at to accept this module.
 */
public class TierUpgradeModule extends Item {

    private final ManipulatorStage requiredStage;

    public TierUpgradeModule(ManipulatorStage requiredStage) {
        super(new Item.Properties().stacksTo(16).rarity(Rarity.UNCOMMON));
        this.requiredStage = requiredStage;
    }

    public ManipulatorStage getRequiredStage() {
        return requiredStage;
    }

    @Override
    public InteractionResultHolder<ItemStack> use(
            Level level, Player player, InteractionHand hand) {
        ItemStack module = player.getItemInHand(hand);
        if (!(player instanceof ServerPlayer sp)) return InteractionResultHolder.success(module);
        return install(sp, module)
                ? InteractionResultHolder.consume(module)
                : InteractionResultHolder.fail(module);
    }

    /**
     * Installs a module into the player's Manipulator. Returns false (with a message) if it doesn't
     * fit.
     */
    public static boolean install(ServerPlayer sp, ItemStack module) {
        return install(sp, module, true);
    }

    private static boolean install(ServerPlayer sp, ItemStack module, boolean worldSound) {
        if (!(module.getItem() instanceof TierUpgradeModule m)) return false;
        ItemStack manipulator = Manipulator.stack(sp);
        ManipulatorStage current = ManipulatorStage.get(manipulator);
        if (current != m.requiredStage) {
            Component msg =
                    current.id > m.requiredStage.id
                            ? Component.translatable("message.ethermod.upgrade_past")
                            : Component.translatable(
                                    "message.ethermod.upgrade_first", m.requiredStage.title());
            sp.displayClientMessage(msg.copy().withStyle(ChatFormatting.RED), true);
            return false;
        }
        if (!ManipulatorStage.tryUpgrade(manipulator)) return false;

        module.consume(1, sp);
        com.ethermod.trait.TraitEnchants.refresh(manipulator, sp.registryAccess());
        ManipulatorStage next = ManipulatorStage.get(manipulator);
        com.ethermod.advancement.ManipulatorTrigger.fire(sp, "stage_" + next.name().toLowerCase(java.util.Locale.ROOT));
        if (worldSound)
            sp.level()
                    .playSound(
                            null,
                            sp.blockPosition(),
                            SoundEvents.BEACON_POWER_SELECT,
                            SoundSource.PLAYERS,
                            0.8f,
                            1.4f);
        sp.displayClientMessage(
                Component.translatable(
                                "message.ethermod.upgraded",
                                next.title().withStyle(ChatFormatting.AQUA))
                        .withStyle(ChatFormatting.GRAY),
                false);
        sp.displayClientMessage(
                Component.translatable("message.ethermod.upgraded_hint", next.title())
                        .withStyle(ChatFormatting.GREEN),
                true);
        Manipulator.sync(sp);
        return true;
    }

    /** Finds the module that fits the player's current stage in their inventory and installs it. */
    public static void installFromInventory(ServerPlayer sp) {
        ManipulatorStage current = ManipulatorStage.get(Manipulator.stack(sp));
        var inv = sp.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (s.getItem() instanceof TierUpgradeModule m && m.requiredStage == current) {
                install(sp, s, false);
                return;
            }
        }
        sp.displayClientMessage(
                (current.next() == null
                                ? Component.translatable("message.ethermod.max_stage")
                                : Component.translatable(
                                        "message.ethermod.need_chip", current.next().title()))
                        .withStyle(ChatFormatting.RED),
                true);
    }

    @Override
    public void appendHoverText(
            ItemStack stack, TooltipContext ctx, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(
                Component.translatable("tooltip.ethermod.chip_for", requiredStage.title())
                        .withStyle(ChatFormatting.GRAY));
        tooltip.add(
                Component.translatable("tooltip.ethermod.chip_install")
                        .withStyle(ChatFormatting.DARK_GRAY));
    }
}
