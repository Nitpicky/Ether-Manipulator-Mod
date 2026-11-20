package com.ethermod.network.payload;

import com.ethermod.EtherMod;

import io.netty.buffer.ByteBuf;

import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Client → Server: pick the orb mode (OrbMode ordinal). */
public record OrbModePayload(int mode) implements CustomPacketPayload {

    public static final Type<OrbModePayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(EtherMod.MODID, "orb_mode"));

    public static final StreamCodec<ByteBuf, OrbModePayload> CODEC =
            ByteBufCodecs.VAR_INT.map(OrbModePayload::new, OrbModePayload::mode);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
