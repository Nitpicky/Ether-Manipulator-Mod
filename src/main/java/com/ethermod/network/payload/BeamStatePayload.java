package com.ethermod.network.payload;

import com.ethermod.EtherMod;

import io.netty.buffer.ByteBuf;

import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Server → nearby clients: a player's beam started or stopped, so others can see it. */
public record BeamStatePayload(int entityId, boolean active) implements CustomPacketPayload {

    public static final Type<BeamStatePayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(EtherMod.MODID, "beam_state"));

    public static final StreamCodec<ByteBuf, BeamStatePayload> CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT,
                    BeamStatePayload::entityId,
                    ByteBufCodecs.BOOL,
                    BeamStatePayload::active,
                    BeamStatePayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
