package com.ethermod.network.payload;

import com.ethermod.EtherMod;

import io.netty.buffer.ByteBuf;

import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Server → the reloading player: the shared tank fills in {@code ticks} ticks. Starts the reload
 * ("intake") animation so its clamp lands exactly on the fill.
 */
public record ReloadPayload(int ticks) implements CustomPacketPayload {

    public static final Type<ReloadPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(EtherMod.MODID, "reload"));

    public static final StreamCodec<ByteBuf, ReloadPayload> CODEC =
            StreamCodec.composite(ByteBufCodecs.VAR_INT, ReloadPayload::ticks, ReloadPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
