package com.ethermod.network.payload;

import com.ethermod.EtherMod;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Server → the shooter: a shot was refused because the Ether tank cannot pay for it. */
public record DryFirePayload() implements CustomPacketPayload {

    public static final DryFirePayload INSTANCE = new DryFirePayload();

    public static final Type<DryFirePayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(EtherMod.MODID, "dry_fire"));

    public static final StreamCodec<ByteBuf, DryFirePayload> CODEC = StreamCodec.unit(INSTANCE);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
