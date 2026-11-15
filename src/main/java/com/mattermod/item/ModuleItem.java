package com.mattermod.item;

import com.mattermod.manipulator.Manipulator;
import com.mattermod.trait.ModuleUnlocks;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.*;
import net.minecraft.world.level.Level;

public final class ModuleItem extends Item {
    private final ResourceLocation module;

    public ModuleItem(String module) {
        super(new Properties().rarity(Rarity.UNCOMMON));
        this.module = ResourceLocation.fromNamespaceAndPath("mattermod", module);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(
            Level level, Player player, InteractionHand hand) {
        var item = player.getItemInHand(hand);
        if (player instanceof ServerPlayer sp) {
            if (!ModuleUnlocks.unlock(Manipulator.stack(sp), module)) {
                sp.displayClientMessage(
                        Component.translatable("message.mattermod.module_known"), true);
                return InteractionResultHolder.fail(item);
            }
            item.consume(1, sp);
            com.mattermod.advancement.ManipulatorTrigger.fire(sp, "module_" + module.getPath());
            Manipulator.sync(sp);
            sp.displayClientMessage(
                    Component.translatable("message.mattermod.module_unlocked"), true);
        }
        return InteractionResultHolder.sidedSuccess(item, level.isClientSide);
    }

    @Override
    public void appendHoverText(
            ItemStack stack,
            Item.TooltipContext context,
            java.util.List<Component> tooltip,
            net.minecraft.world.item.TooltipFlag flag) {
        tooltip.add(
                Component.translatable("item.mattermod.module_" + module.getPath())
                        .withStyle(style -> style.withColor(colour(module.getPath()))));
        tooltip.add(
                Component.translatable("tooltip.mattermod.gem_use")
                        .withStyle(net.minecraft.ChatFormatting.DARK_GRAY));
    }

    /** The Module's accent, as on the K screen (trait_line colours). */
    private static int colour(String module) {
        return switch (module) {
            case "combat" -> 0xE99F20;
            case "cultivation" -> 0x60D060;
            case "excavation" -> 0xD48050;
            case "hydraulics" -> 0x3F8CFF;
            case "mechatronic" -> 0xE92031;
            case "mobility" -> 0xC080FF;
            case "orb" -> 0x66E0FF;
            case "photonic" -> 0xFFE070;
            case "pneumatic" -> 0xB8E8E0;
            case "pyrotechnics" -> 0xFF7020;
            default -> 0x55FFFF;
        };
    }
}
