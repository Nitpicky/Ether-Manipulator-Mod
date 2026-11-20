package com.ethermod.network.payload;

import com.ethermod.EtherMod;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Server → players near it: this creature is held in Stasis for {@code ticks} ticks (0 = released). */
public record StasisPayload(int entity, int ticks) implements CustomPacketPayload {

    public static final Type<StasisPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(EtherMod.MODID, "stasis"));

    public static final net.minecraft.network.codec.StreamCodec<ByteBuf, StasisPayload> CODEC =
            net.minecraft.network.codec.StreamCodec.composite(
                    ByteBufCodecs.VAR_INT, StasisPayload::entity, ByteBufCodecs.VAR_INT, StasisPayload::ticks, StasisPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
