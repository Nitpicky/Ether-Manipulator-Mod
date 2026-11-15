package com.mattermod.network.payload;

import com.mattermod.MatterMod;

import io.netty.buffer.ByteBuf;

import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.List;

/**
 * Server → the beaming player and everyone watching: the beam's pressure level changed, or it just
 * struck (with the creatures the strike arced to). Drives the beam's look and sound.
 */
public record BeamPulsePayload(int entityId, int level, boolean strike, List<Integer> arcs)
        implements CustomPacketPayload {

    public static final Type<BeamPulsePayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MatterMod.MODID, "beam_pulse"));

    public static final StreamCodec<ByteBuf, BeamPulsePayload> CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT,
                    BeamPulsePayload::entityId,
                    ByteBufCodecs.VAR_INT,
                    BeamPulsePayload::level,
                    ByteBufCodecs.BOOL,
                    BeamPulsePayload::strike,
                    ByteBufCodecs.VAR_INT.apply(ByteBufCodecs.list(8)),
                    BeamPulsePayload::arcs,
                    BeamPulsePayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
