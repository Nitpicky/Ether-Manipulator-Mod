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

    public ResourceLocation module() {
        return module;
    }

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
            unlockSound(sp, module.getPath());
        }
        return InteractionResultHolder.sidedSuccess(item, level.isClientSide);
    }

    /** An Eye of Ender seating in a portal frame, with the unlocked Module's mode-switch cue. */
    private static void unlockSound(ServerPlayer sp, String module) {
        var level = sp.level();
        var at = sp.blockPosition();
        level.playSound(
                null, at, net.minecraft.sounds.SoundEvents.END_PORTAL_FRAME_FILL,
                net.minecraft.sounds.SoundSource.PLAYERS, 1f, 1f);
        net.minecraft.sounds.SoundEvent cue;
        float vol, pitch;
        switch (module) {
            case "hydraulic" -> {
                cue = net.minecraft.sounds.SoundEvents.BUCKET_FILL;
                vol = .6f;
                pitch = 1.2f;
            }
            case "pyrotechnic" -> {
                cue = net.minecraft.sounds.SoundEvents.BUCKET_FILL_LAVA;
                vol = .4f;
                pitch = 1.15f;
            }
            case "pneumatic" -> {
                cue = net.minecraft.sounds.SoundEvents.BREEZE_IDLE_AIR;
                vol = .5f;
                pitch = 1.25f;
            }
            case "photonic" -> {
                cue =
                        net.minecraft.sounds.SoundEvent.createVariableRangeEvent(
                                ResourceLocation.fromNamespaceAndPath("ethermod", "mode.photonic"));
                vol = 1f;
                pitch = 1f;
            }
            case "gravitic" -> {
                cue = net.minecraft.sounds.SoundEvents.CHORUS_FRUIT_TELEPORT;
                vol = .2f;
                pitch = 1.4f;
            }
            case "botanic" -> {
                cue = net.minecraft.sounds.SoundEvents.GRASS_HIT;
                vol = 1f;
                pitch = 1.1f;
            }
            case "tectonic" -> {
                cue = net.minecraft.sounds.SoundEvents.DEEPSLATE_HIT;
                vol = .5f;
                pitch = 1f;
            }
            default -> {
                return;
            }
        }
        level.playSound(null, at, cue, net.minecraft.sounds.SoundSource.PLAYERS, vol, pitch);
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
