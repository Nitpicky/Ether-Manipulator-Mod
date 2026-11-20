package com.ethermod.network.payload;

import com.ethermod.EtherMod;

import io.netty.buffer.ByteBuf;

import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Client → Server: summon (true) or put away (false) the Manipulator. */
public record SummonTogglePayload(boolean summon) implements CustomPacketPayload {

    public static final Type<SummonTogglePayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(EtherMod.MODID, "summon_toggle"));

    public static final StreamCodec<ByteBuf, SummonTogglePayload> CODEC =
            ByteBufCodecs.BOOL.map(SummonTogglePayload::new, SummonTogglePayload::summon);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
