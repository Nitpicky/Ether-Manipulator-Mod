package com.mattermod.network.payload;

import com.mattermod.MatterMod;

import io.netty.buffer.ByteBuf;

import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Client → Server: summon (true) or put away (false) the Manipulator. */
public record SummonTogglePayload(boolean summon) implements CustomPacketPayload {

    public static final Type<SummonTogglePayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MatterMod.MODID, "summon_toggle"));

    public static final StreamCodec<ByteBuf, SummonTogglePayload> CODEC =
            ByteBufCodecs.BOOL.map(SummonTogglePayload::new, SummonTogglePayload::summon);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
