package com.ethermod.item;

import com.ethermod.manipulator.Manipulator;
import com.ethermod.trait.ModuleUnlocks;

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
        this.module = ResourceLocation.fromNamespaceAndPath("ethermod", module);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(
            Level level, Player player, InteractionHand hand) {
        var item = player.getItemInHand(hand);
        if (player instanceof ServerPlayer sp) {
            if (!ModuleUnlocks.unlock(Manipulator.stack(sp), module)) {
                sp.displayClientMessage(
                        Component.translatable("message.ethermod.module_known"), true);
                return InteractionResultHolder.fail(item);
            }
            item.consume(1, sp);
            com.ethermod.advancement.ManipulatorTrigger.fire(sp, "module_" + module.getPath());
            Manipulator.sync(sp);
            sp.displayClientMessage(
                    Component.translatable("message.ethermod.module_unlocked"), true);
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
                Component.translatable("item.ethermod.module_" + module.getPath())
                        .withStyle(style -> style.withColor(colour(module.getPath()))));
        tooltip.add(
                Component.translatable("tooltip.ethermod.gem_use")
                        .withStyle(net.minecraft.ChatFormatting.DARK_GRAY));
    }

    /** The Module's accent, as on the K screen (trait_line colours). */
    private static int colour(String module) {
        return switch (module) {
            case "plasmic" -> 0xE99F20;
            case "botanic" -> 0x60D060;
            case "tectonic" -> 0xD48050;
            case "hydraulic" -> 0x3F8CFF;
            case "gravitic" -> 0xC080FF;
            case "kinetic" -> 0x66E0FF;
            case "photonic" -> 0xFFE070;
            case "pneumatic" -> 0xB8E8E0;
            case "pyrotechnic" -> 0xFF7020;
            default -> 0x55FFFF;
        };
    }
}
