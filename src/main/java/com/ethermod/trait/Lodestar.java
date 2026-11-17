package com.ethermod.trait;

import com.ethermod.network.payload.ModulePayloads;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Photonic: Lodestar. A phase 3 Light orb fired straight up hangs over that spot as a marker you
 * can see from anywhere in the dimension, for 20 minutes. One at a time. It gives no light.
 */
public final class Lodestar {

    private Lodestar() {}

    public static final int TICKS = 24000;

    /** The local player's marker, client side (null if none). */
    public static volatile ModulePayloads.LodestarMark clientMark;

    public static void set(ServerPlayer sp) {
        BlockPos at = sp.blockPosition().above(24);
        long expires = sp.level().getGameTime() + TICKS;
        ResourceLocation dim = sp.level().dimension().location();
        PacketDistributor.sendToPlayer(sp, new ModulePayloads.LodestarMark(at, dim, expires));
        sp.level()
                .playSound(
                        null,
                        sp.getX(),
                        sp.getEyeY(),
                        sp.getZ(),
                        SoundEvents.BEACON_ACTIVATE,
                        SoundSource.PLAYERS,
                        0.8f,
                        1.6f);
        sp.displayClientMessage(
                Component.translatable("message.ethermod.lodestar_set")
                        .withStyle(ChatFormatting.YELLOW),
                true);
    }
}
